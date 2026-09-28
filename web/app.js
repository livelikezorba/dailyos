import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm';
import { mergeSettings, logicalToday, computeSleep, dayScore, requiredNew, fmtClock, fmtHours } from './metrics.js';

// ───────────────────────── 설정 ─────────────────────────
const CFG = window.DAILYOS_CONFIG || {};
const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

if (!CFG.url || !CFG.key) {
  document.body.innerHTML = '<div class="login"><div class="login-card"><h1>DailyOS</h1><p>config.js에 Supabase URL/Key가 없어요. GitHub Secrets를 확인하세요.</p></div></div>';
  throw new Error('missing config');
}
const sb = createClient(CFG.url, CFG.key);

// 카테고리 기본 색 (검증된 categorical 팔레트 순서)
const SERIES = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948'];
const SERIES_DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'];
const isDark = () => matchMedia('(prefers-color-scheme: dark)').matches && document.documentElement.dataset.theme !== 'light';
const seriesColor = (i) => (isDark() ? SERIES_DARK : SERIES)[i % 8];
const HEAT_LIGHT = ['#cde2fb', '#86b6ef', '#3987e5', '#1c5cab'];
const HEAT_DARK = ['#184f95', '#256abf', '#3987e5', '#86b6ef'];
const DOW = ['월', '화', '수', '목', '금', '토', '일'];

// ───────────────────────── 날짜 ─────────────────────────
const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const parseYmd = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (s, n) => { const d = parseYmd(s); d.setDate(d.getDate() + n); return ymd(d); };
const isoDow = (s) => ((parseYmd(s).getDay() + 6) % 7) + 1; // 1=월 … 7=일
const today = () => logicalToday(); // 새벽 4시 이전은 전날
const dayRange = (from, to) => { const out = []; for (let d = from; d <= to; d = addDays(d, 1)) out.push(d); return out; };
const fmtDay = (s) => { const d = parseYmd(s); return `${d.getMonth() + 1}/${d.getDate()}`; };
const weekStart = (s) => addDays(s, 1 - isoDow(s));
const fmtNum = (v) => (v == null ? '0' : Number.isInteger(+v) ? String(+v) : (+v).toFixed(1));
const daysLabel = (ds) => { const k = [...ds].sort().join(''); return k === '1234567' ? '매일' : k === '12345' ? '평일' : k === '67' ? '주말' : ds.map((d) => DOW[d - 1]).join(''); };
const fmtMin = (m) => (m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분` : `${m}분`);

// ───────────────────────── 상태 ─────────────────────────
const state = {
  user: null, categories: [], items: [], limits: [], devices: [],
  todayDate: today(), reportDays: 30, usageDays: 7, trendItem: null,
  settingsRaw: {}, settings: mergeSettings({}), calMonth: today().slice(0, 7), calSel: today(), calData: {},
};
const charts = {};

function toast(msg, ms = 2200) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}
async function run(promise) {
  const { data, error } = await promise;
  if (error) { toast('오류: ' + error.message, 4000); throw error; }
  return data;
}
async function fetchAll(build) {
  const page = 1000; let from = 0; const out = [];
  for (;;) {
    const rows = await run(build().range(from, from + page - 1));
    out.push(...rows);
    if (rows.length < page) return out;
    from += page;
  }
}

// ───────────────────────── 도메인 ─────────────────────────
const catOf = (item) => state.categories.find((c) => c.id === item.category_id);
const catName = (item) => catOf(item)?.name ?? '기타';
const catColor = (item) => catOf(item)?.color ?? '#868e96';
const scheduledOn = (item, day) => item.active && (item.days || []).includes(isoDow(day)) && item.start_day <= day;
function isDone(item, log) {
  if (!log) return false;
  if (item.kind !== 'number') return !!log.done;
  if (log.value == null) return false;
  return item.target == null ? +log.value > 0 : +log.value >= +item.target;
}
const sortedItems = () => [...state.items].sort((a, b) => {
  const ca = catOf(a), cb = catOf(b);
  return (ca?.sort ?? 999) - (cb?.sort ?? 999) || (ca?.name ?? '').localeCompare(cb?.name ?? '') || a.sort - b.sort || a.name.localeCompare(b.name);
});

async function loadMeta() {
  const [cats, items, limits, devices, us] = await Promise.all([
    run(sb.from('categories').select('*').order('sort').order('name')),
    run(sb.from('items').select('*')),
    run(sb.from('usage_limits').select('*').order('label')),
    run(sb.from('devices').select('*').order('last_seen', { ascending: false })),
    run(sb.from('user_settings').select('data')),
  ]);
  const raw = us[0]?.data || {};
  Object.assign(state, { categories: cats, items, limits, devices, settingsRaw: raw, settings: mergeSettings(raw) });
}
const logsBetween = (from, to) =>
  fetchAll(() => sb.from('logs').select('item_id,day,done,value').gte('day', from).lte('day', to).order('day'));
const logKey = (itemId, day) => `${itemId}|${day}`;
const indexLogs = (logs) => new Map(logs.map((l) => [logKey(l.item_id, l.day), l]));

// ───────────────────────── 인증 ─────────────────────────
async function boot() {
  const { data } = await sb.auth.getSession();
  if (data.session) { state.user = data.session.user; await enterApp(); } else showLogin();
}
function showLogin() { $('#app').classList.add('hidden'); $('#login').classList.remove('hidden'); }
$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#login-error').textContent = '';
  const { data, error } = await sb.auth.signInWithPassword({ email: $('#email').value.trim(), password: $('#password').value });
  if (error) { $('#login-error').textContent = error.message; return; }
  state.user = data.user; await enterApp();
});
$('#logout').addEventListener('click', async () => { await sb.auth.signOut(); location.reload(); });

async function enterApp() {
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  await loadMeta();
  const saved = (() => { try { return localStorage.getItem('dailyos.tab'); } catch { return null; } })();
  showTab(saved || 'calendar');
}

$('#tabs').addEventListener('click', (e) => { const b = e.target.closest('button[data-tab]'); if (b) showTab(b.dataset.tab); });
function showTab(tab) {
  $$('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  $$('.tab').forEach((s) => s.classList.toggle('hidden', s.id !== 'tab-' + tab));
  try { localStorage.setItem('dailyos.tab', tab); } catch {}
  ({ calendar: renderCalendar, today: renderToday, report: renderReport, usage: renderUsage, settings: renderSettings }[tab] || renderCalendar)();
}

// ═════════════════════════ 지표 (일본어·건강·잠·점수) ═════════════════════════
const AREA = { jp: '#eb6834', health: '#1baf7a', sleep: '#4a3aa7' };
const pts = (v) => Math.round(v);

async function loadMetrics(from, to) {
  const pk = state.limits.map((l) => l.package);
  const [anki, health, nights, usage] = await Promise.all([
    fetchAll(() => sb.from('anki_daily').select('*').gte('day', from).lte('day', to)),
    fetchAll(() => sb.from('health_log').select('*').gte('day', from).lte('day', to)),
    fetchAll(() => sb.from('night_activity').select('day,device_id,sessions').gte('day', from).lte('day', to)),
    pk.length ? fetchAll(() => sb.from('app_usage').select('day,package,minutes').gte('day', from).lte('day', to).in('package', pk)) : Promise.resolve([]),
  ]);
  const S = state.settings;
  const limit = state.limits.reduce((a, l) => a + l.daily_limit_min, 0);
  const calToday = ymd(new Date());
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const out = {};
  for (const d of dayRange(from, to)) {
    const a = anki.find((r) => r.day === d) || null;
    const h = health.find((r) => r.day === d) || null;
    const sessions = nights.filter((r) => r.day === d).flatMap((r) => r.sessions || []);
    const sleep = d > calToday ? null : computeSleep(sessions, d === calToday ? nowMin : null);
    const used = usage.filter((r) => r.day === d).reduce((x, r) => x + r.minutes, 0);
    const score = d > today() ? null : dayScore(S, { anki: a, health: h, sleep, phone: { used, limit } });
    out[d] = { day: d, anki: a, health: h, sleep, used, limit, score };
  }
  return out;
}

async function renderCalendar() {
  const el = $('#tab-calendar');
  const [y, m] = state.calMonth.split('-').map(Number);
  const first = `${state.calMonth}-01`;
  const last = ymd(new Date(y, m, 0));
  state.calData = await loadMetrics(first, last);
  if (!state.calData[state.calSel]) Object.assign(state.calData, await loadMetrics(state.calSel, state.calSel));
  const offset = isoDow(first) - 1;
  const days = dayRange(first, last);
  const t = today();
  const heat = (score) => (score == null ? 'transparent' : `color-mix(in srgb, var(--accent) ${Math.round(score * 0.32)}%, var(--surface))`);

  const cells = [];
  for (let i = 0; i < offset; i++) cells.push('<div class="cal-cell empty"></div>');
  for (const d of days) {
    const x = state.calData[d];
    const future = d > t;
    const a = x?.anki, h = x?.health, sl = x?.sleep, sc = x?.score;
    const lines = [];
    if (!future) {
      if (a) {
        lines.push(`<span class="dot" style="background:${AREA.jp}"></span>新 ${a.new_studied ?? 0}/${a.required_new ?? '–'}`);
        lines.push(`<span class="dot" style="background:${AREA.jp}"></span>復 ${a.reviewed ?? 0}${a.due_left ? `<small>+${a.due_left}</small>` : ''}`);
      }
      if (h && (h.walk_min || h.pushups)) lines.push(`<span class="dot" style="background:${AREA.health}"></span>🚶${fmtNum(h.walk_min)} 💪${h.pushups ?? 0}`);
      if (h && (h.pain_am != null || h.pain_pm != null)) lines.push(`<span class="dot" style="background:${AREA.health}"></span>통증 ${h.pain_am ?? '–'}/${h.pain_pm ?? '–'}`);
      if (sl?.minutes != null) lines.push(`<span class="dot" style="background:${AREA.sleep}"></span>😴${(sl.minutes / 60).toFixed(1)}h`);
    }
    cells.push(`<button class="cal-cell ${d === state.calSel ? 'sel' : ''} ${d === t ? 'today' : ''} ${future ? 'future' : ''}" data-day="${d}" style="background:${heat(sc?.total)}">
      <div class="cal-top"><span class="cal-date">${+d.slice(8)}</span>${sc ? `<span class="cal-score">${sc.total}</span>` : ''}</div>
      ${lines.map((l) => `<div class="cal-line">${l}</div>`).join('')}
    </button>`);
  }

  el.innerHTML = `
    <div class="toolbar">
      <div class="title">${y}년 ${m}월</div>
      <button class="small" data-mnav="-1">‹</button>
      <button class="small" data-mnav="0">오늘</button>
      <button class="small" data-mnav="1">›</button>
    </div>
    <div class="cal-wrap">
      <div class="card cal-card">
        <div class="cal-grid cal-head">${DOW.map((w, i) => `<div class="${i >= 5 ? 'weekend' : ''}">${w}</div>`).join('')}</div>
        <div class="cal-grid">${cells.join('')}</div>
        <div class="cal-legend"><span><span class="dot" style="background:${AREA.jp}"></span>일본어 新=새 카드(한 것/목표) 復=복습</span>
          <span><span class="dot" style="background:${AREA.health}"></span>건강 (걷기분·푸시업·통증 아침/저녁)</span>
          <span><span class="dot" style="background:${AREA.sleep}"></span>잠</span><span>오른쪽 위 숫자 = 생산성 점수 · 배경이 진할수록 높음</span></div>
      </div>
      <div id="cal-detail"></div>
    </div>`;

  el.querySelectorAll('[data-mnav]').forEach((b) => b.addEventListener('click', () => {
    const n = +b.dataset.mnav;
    if (n === 0) { state.calMonth = t.slice(0, 7); state.calSel = t; }
    else { const dt = new Date(y, m - 1 + n, 1); state.calMonth = ymd(dt).slice(0, 7); }
    renderCalendar();
  }));
  el.querySelectorAll('.cal-cell[data-day]').forEach((c) => c.addEventListener('click', () => {
    state.calSel = c.dataset.day;
    el.querySelectorAll('.cal-cell').forEach((x) => x.classList.toggle('sel', x.dataset.day === state.calSel));
    renderDayDetail();
  }));
  renderDayDetail();
}

async function renderDayDetail() {
  const box = $('#cal-detail');
  const d = state.calSel;
  const x = state.calData[d] || {};
  const S = state.settings, W = S.weights;
  const editable = d <= today();
  const usage = await run(sb.from('app_usage').select('device_id,package,label,minutes').eq('day', d));
  const devName = (id) => state.devices.find((v) => v.device_id === id)?.name ?? '폰';
  const byPkg = {};
  for (const r of usage) (byPkg[r.package] ||= { label: r.label || r.package, total: 0, rows: [] }, byPkg[r.package].total += r.minutes, byPkg[r.package].rows.push(r));
  const apps = Object.entries(byPkg).sort((a, b) => b[1].total - a[1].total).slice(0, 8);
  const a = x.anki, h = x.health || {}, sl = x.sleep, sc = x.score;
  const dd = parseYmd(d);
  const dLeft = Math.round((Date.parse(S.anki_target) - Date.parse(d)) / 86400000);
  const warn = (ok) => (ok ? '' : ' class="miss"');

  box.innerHTML = `
    <div class="card detail">
      <div class="muted">${dd.getMonth() + 1}월 ${dd.getDate()}일 (${DOW[isoDow(d) - 1]})</div>
      <h2>${sc ? `생산성 ${sc.total}점` : '기록 없음'}</h2>
      ${sc ? `<div class="score-bars">${[['jp', '일본어'], ['ex', '운동'], ['sleep', '잠'], ['phone', '폰 절제'], ['rec', '기록']].map(([k, n]) => `
        <div class="sb-row"><span>${n}</span><div class="bar"><span style="width:${(sc.ratios[k] || 0) * 100}%"></span></div><b>${pts(sc.parts[k])}/${W[k]}</b></div>`).join('')}</div>` : ''}

      <h3 class="area"><span class="dot" style="background:${AREA.jp}"></span>일본어 (Anki)</h3>
      ${a ? `<div class="kv"><span${warn((a.new_studied ?? 0) >= (a.required_new ?? 0))}>새 카드</span><b>${a.new_studied ?? 0} / 목표 ${a.required_new ?? '–'}</b></div>
        <div class="kv"><span>복습</span><b>${a.reviewed ?? 0}장${a.due_left != null ? ` · 남은 ${a.due_left}` : ''}</b></div>
        ${a.remaining_new != null ? `<div class="kv"><span>남은 새 카드</span><b>${a.remaining_new}장 · ${esc(S.anki_target)} (D-${dLeft})</b></div>` : ''}
        ${a.total_cards != null ? `<div class="kv"><span>암기 완료</span><b>${a.mature ?? 0} / ${a.total_cards}</b></div>` : ''}
        ${(a.reviewed ?? 0) >= S.anki_review_warn ? `<p class="error">⚠️ 복습 ${a.reviewed}장 — 새 카드 수를 줄이는 걸 고려하세요</p>` : ''}`
        : '<p class="muted">Anki 기록 없음 (Anki를 쓰는 폰에서 DailyOS의 Anki 연결을 허용하세요)</p>'}

      <h3 class="area"><span class="dot" style="background:${AREA.health}"></span>건강 (허리)</h3>
      <div class="kv"><span${warn((+h.walk_min || 0) >= S.walk_goal_min)}>🚶 걷기</span><b>${fmtNum(h.walk_min)} / ${S.walk_goal_min}분${h.walk_km ? ` · ${fmtNum(h.walk_km)}km` : ''}</b></div>
      <div class="kv"><span${warn((h.pushups || 0) >= S.pushup_goal)}>💪 푸시업</span><b>${h.pushups ?? 0} / ${S.pushup_goal}개</b></div>
      <div class="kv"><span>🩺 통증 아침</span><b>${h.pain_am ?? '–'}${h.note_am ? ` · ${esc(h.note_am)}` : ''}</b></div>
      <div class="kv"><span>🩺 통증 저녁</span><b>${h.pain_pm ?? '–'}${h.note_pm ? ` · ${esc(h.note_pm)}` : ''}</b></div>
      ${editable ? `<div class="inputs">
        <div class="in-row"><input id="in-walk" type="number" step="any" placeholder="걷기 분"><input id="in-km" type="number" step="any" placeholder="km"><input id="in-push" type="number" placeholder="푸시업"><button class="small primary" id="btn-health">더하기</button></div>
        <div class="in-row"><select id="in-when"><option value="am">아침</option><option value="pm" ${new Date().getHours() >= 15 ? 'selected' : ''}>저녁</option></select>
          <input id="in-pain" type="number" min="0" max="10" placeholder="통증 0~10"><input id="in-note" placeholder="메모"><button class="small primary" id="btn-pain">저장</button></div>
        <p class="muted">걷기·푸시업은 입력한 만큼 더해져요 (빼려면 음수)</p></div>` : ''}

      <h3 class="area"><span class="dot" style="background:${AREA.sleep}"></span>잠</h3>
      ${!sl ? '<p class="muted">데이터 없음</p>'
        : sl.status === 'ok' ? `<div class="kv"><span${warn(sl.minutes >= S.sleep_goal_min)}>${fmtClock(sl.start)} → ${fmtClock(sl.end)}</span><b>${fmtHours(sl.minutes)}</b></div><p class="muted">목표 ${S.sleep_goal_min / 60}시간 · 두 폰 모두 안 쓴 시간 기준</p>`
        : sl.status === 'pending' ? `<p class="muted">측정 중 · 마지막 사용 ${fmtClock(sl.start)}</p>`
        : `<p class="muted">기상 기록 없음 · 잠든 시각 ${fmtClock(sl.start)}</p>`}

      <h3 class="area">📱 앱 사용량 (두 폰 합산)</h3>
      ${state.limits.map((l) => { const u = byPkg[l.package]?.total || 0; return `<div class="kv"><span${warn(u <= l.daily_limit_min)}>${esc(l.label)}</span><b>${u} / ${l.daily_limit_min}분</b></div>`; }).join('')}
      ${apps.length ? apps.map(([pkg, v]) => `<div class="kv"><span>${esc(v.label)}</span><b>${fmtMin(v.total)}<small class="muted"> ${v.rows.length > 1 ? v.rows.map((r) => `${esc(devName(r.device_id))} ${r.minutes}`).join(' · ') : esc(devName(v.rows[0].device_id))}</small></b></div>`).join('') : '<p class="muted">기록 없음</p>'}
    </div>`;

  $('#btn-health')?.addEventListener('click', async () => {
    const w = parseFloat($('#in-walk').value) || 0, k = parseFloat($('#in-km').value) || 0, p = parseInt($('#in-push').value) || 0;
    if (!w && !k && !p) return toast('숫자를 입력하세요');
    await run(sb.rpc('add_health', { p_day: d, p_walk_min: w, p_walk_km: k, p_pushups: p }));
    toast('저장됨'); renderCalendar();
  });
  $('#btn-pain')?.addEventListener('click', async () => {
    const v = parseInt($('#in-pain').value);
    if (Number.isNaN(v) || v < 0 || v > 10) return toast('통증은 0~10');
    const wk = $('#in-when').value;
    await run(sb.from('health_log').upsert({ user_id: state.user.id, day: d, [`pain_${wk}`]: v, [`note_${wk}`]: $('#in-note').value.trim(), updated_at: new Date().toISOString() }, { onConflict: 'user_id,day' }));
    toast('저장됨'); renderCalendar();
  });
}

async function saveSettings(patch) {
  const { data } = await sb.from('user_settings').select('data');
  const raw = { ...(data?.[0]?.data || {}), ...patch };
  await run(sb.from('user_settings').upsert({ user_id: state.user.id, data: raw, updated_at: new Date().toISOString() }, { onConflict: 'user_id' }));
  state.settingsRaw = raw; state.settings = mergeSettings(raw);
}

function goalsCardHtml() {
  const S = state.settings, W = S.weights;
  const avail = [...new Set([...(state.settingsRaw.anki_available || []), ...S.anki_decks])];
  return `<div class="card"><h3>목표 설정</h3><p class="muted">폰 앱과 같이 쓰여요. 저장하면 폰에는 15분 안에 반영돼요.</p>
    <div class="goal-grid">
      <div class="full"><b>추적할 Anki 덱</b> <span class="muted">(폰에서 Anki 연결 후 목록이 채워져요)</span>
        <div class="deck-list">${avail.map((n) => `<label class="inline"><input type="checkbox" class="g-deck" value="${esc(n)}" ${S.anki_decks.includes(n) ? 'checked' : ''}> ${esc(n)}</label>`).join('')}</div>
        <input id="g-deck-add" placeholder="덱 이름 직접 추가 (Anki에 보이는 그대로)"></div>
      <label>새 카드 완료 목표일<input id="g-target" type="date" value="${esc(S.anki_target)}"></label>
      <label>복습 과부하 경고 (장)<input id="g-warn" type="number" value="${S.anki_review_warn}"></label>
      <label>목표 수면 (시간)<input id="g-sleep" type="number" step="0.5" value="${S.sleep_goal_min / 60}"></label>
      <label>걷기 목표 (분)<input id="g-walk" type="number" value="${S.walk_goal_min}"></label>
      <label>푸시업 목표 (개)<input id="g-push" type="number" value="${S.pushup_goal}"></label>
      <div class="full"><b>점수 비중</b> <span class="muted">(합계 100)</span><div class="w-row">
        ${[['jp', '일본어'], ['ex', '운동'], ['sleep', '잠'], ['phone', '폰 절제'], ['rec', '기록']].map(([k, n]) => `<label>${n}<input class="g-w" data-k="${k}" type="number" value="${W[k]}"></label>`).join('')}
        <span id="g-wsum" class="muted"></span></div></div>
    </div>
    <button class="primary" id="g-save" style="margin-top:10px">목표 저장</button></div>`;
}

function bindGoals(rerender) {
  const sum = () => $$('.g-w').reduce((a, i) => a + (+i.value || 0), 0);
  const upd = () => { const s = sum(); $('#g-wsum').textContent = `합계 ${s}`; $('#g-wsum').className = s === 100 ? 'muted' : 'error'; };
  $$('.g-w').forEach((i) => i.addEventListener('input', upd)); upd();
  $('#g-save').addEventListener('click', async () => {
    if (sum() !== 100) return toast('점수 비중 합계를 100으로 맞춰주세요');
    const decks = $$('.g-deck').filter((c) => c.checked).map((c) => c.value);
    const add = $('#g-deck-add').value.trim(); if (add && !decks.includes(add)) decks.push(add);
    const weights = Object.fromEntries($$('.g-w').map((i) => [i.dataset.k, +i.value]));
    await saveSettings({
      anki_decks: decks, anki_target: $('#g-target').value, anki_review_warn: +$('#g-warn').value || 250,
      sleep_goal_min: Math.round((+$('#g-sleep').value || 7) * 60), walk_goal_min: +$('#g-walk').value || 60,
      pushup_goal: +$('#g-push').value || 20, weights,
    });
    toast('목표 저장됨'); rerender();
  });
}

async function metricChartsHtml(from, to) {
  const data = await loadMetrics(from, to);
  const ds = dayRange(from, to).filter((d) => d <= today());
  return { data, ds };
}

function drawMetricCharts(data, ds) {
  const S = state.settings;
  const lab = ds.map(fmtDay);
  const v = (f) => ds.map((d) => { const r = f(data[d]); return r == null || Number.isNaN(r) ? null : r; });
  drawChart('m-score', { type: 'line', data: { labels: lab, datasets: [{ label: '생산성 점수', data: v((x) => x?.score?.total), borderColor: cssVar('--accent'), backgroundColor: cssVar('--accent'), borderWidth: 2, pointRadius: 0, pointHoverRadius: 5, tension: 0.25, spanGaps: true }] }, options: { ...baseOptions({ max: 100, suffix: '점' }), plugins: { ...baseOptions().plugins, legend: { display: false } } } });
  drawChart('m-anki', { type: 'bar', data: { labels: lab, datasets: [
    { label: '새 카드', data: v((x) => x?.anki?.new_studied), backgroundColor: AREA.jp, borderRadius: 4, maxBarThickness: 22 },
    { type: 'line', label: '목표', data: v((x) => x?.anki?.required_new), borderColor: cssVar('--muted'), borderDash: [5, 4], borderWidth: 2, pointRadius: 0 },
  ] }, options: baseOptions({ suffix: '장' }) });
  drawChart('m-sleep', { type: 'bar', data: { labels: lab, datasets: [
    { label: '수면', data: v((x) => (x?.sleep?.minutes != null ? +(x.sleep.minutes / 60).toFixed(1) : null)), backgroundColor: AREA.sleep, borderRadius: 4, maxBarThickness: 22 },
    { type: 'line', label: '목표', data: ds.map(() => S.sleep_goal_min / 60), borderColor: cssVar('--muted'), borderDash: [5, 4], borderWidth: 2, pointRadius: 0 },
  ] }, options: baseOptions({ suffix: 'h' }) });
  drawChart('m-walk', { type: 'bar', data: { labels: lab, datasets: [
    { label: '걷기', data: v((x) => (x?.health?.walk_min != null ? +x.health.walk_min : null)), backgroundColor: AREA.health, borderRadius: 4, maxBarThickness: 22 },
    { type: 'line', label: '목표', data: ds.map(() => S.walk_goal_min), borderColor: cssVar('--muted'), borderDash: [5, 4], borderWidth: 2, pointRadius: 0 },
  ] }, options: baseOptions({ suffix: '분' }) });
  drawChart('m-pain', { type: 'line', data: { labels: lab, datasets: [
    { label: '아침', data: v((x) => x?.health?.pain_am), borderColor: seriesColor(0), backgroundColor: seriesColor(0), borderWidth: 2, pointRadius: 3, spanGaps: true },
    { label: '저녁', data: v((x) => x?.health?.pain_pm), borderColor: seriesColor(1), backgroundColor: seriesColor(1), borderWidth: 2, pointRadius: 3, spanGaps: true },
  ] }, options: baseOptions({ max: 10, suffix: '' }) });
}

// ═════════════════════════ 오늘 ═════════════════════════
async function renderToday() {
  const el = $('#tab-today');
  const day = state.todayDate;
  const logs = indexLogs(await logsBetween(day, day));
  const items = sortedItems().filter((i) => scheduledOn(i, day));
  const done = items.filter((i) => isDone(i, logs.get(logKey(i.id, day)))).length;
  const d = parseYmd(day);
  const label = `${d.getMonth() + 1}월 ${d.getDate()}일 (${DOW[isoDow(day) - 1]})`;

  const groups = new Map();
  for (const it of items) { const k = it.category_id ?? '_'; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(it); }

  el.innerHTML = `
    <div class="toolbar">
      <div class="title">${label}${day === today() ? ' <span class="badge">오늘</span>' : ''}</div>
      <button class="small" data-nav="-1">‹ 전날</button>
      <button class="small" data-nav="0">오늘</button>
      <button class="small" data-nav="1" ${day >= today() ? 'disabled' : ''}>다음날 ›</button>
    </div>
    <div class="grid tiles" style="margin-bottom:14px">
      <div class="card tile"><div class="label">달성</div><div class="value">${done} / ${items.length}</div>
        <div class="bar" style="margin-top:6px"><span style="width:${items.length ? (done / items.length) * 100 : 0}%"></span></div></div>
    </div>
    ${items.length ? '' : '<div class="card empty">이 날 예정된 루틴이 없어요. [설정] 탭에서 추가하세요.</div>'}
    <div class="grid cols-2">
      ${[...groups.values()].map((list) => `
        <div class="card">
          <h3><span class="dot" style="background:${esc(catColor(list[0]))}"></span>${esc(catName(list[0]))}</h3>
          ${list.map((it) => itemRowHtml(it, logs.get(logKey(it.id, day)))).join('')}
        </div>`).join('')}
    </div>`;

  el.querySelectorAll('[data-nav]').forEach((b) => b.addEventListener('click', () => {
    const n = +b.dataset.nav; state.todayDate = n === 0 ? today() : addDays(state.todayDate, n); renderToday();
  }));
  el.querySelectorAll('input[data-check]').forEach((cb) => cb.addEventListener('change', async () => {
    await run(sb.from('logs').upsert({
      user_id: state.user.id, item_id: cb.dataset.check, day, done: cb.checked, device: 'PC', updated_at: new Date().toISOString(),
    }, { onConflict: 'item_id,day' }));
    renderToday();
  }));
  el.querySelectorAll('[data-add],[data-set]').forEach((b) => b.addEventListener('click', async () => {
    const id = b.dataset.add || b.dataset.set;
    const input = el.querySelector(`input[data-num="${id}"]`);
    const v = parseFloat(input.value);
    if (Number.isNaN(v)) { toast('숫자를 입력하세요'); return; }
    const item = state.items.find((i) => i.id === id);
    if (b.dataset.add) {
      await run(sb.rpc('add_log_value', { p_item: id, p_day: day, p_delta: v, p_device: 'PC' }));
    } else {
      await run(sb.from('logs').upsert({
        user_id: state.user.id, item_id: id, day, value: v, device: 'PC', updated_at: new Date().toISOString(),
        done: item.target == null ? v > 0 : v >= +item.target,
      }, { onConflict: 'item_id,day' }));
    }
    renderToday();
  }));
}

function itemRowHtml(it, log) {
  const done = isDone(it, log);
  const times = (it.reminder_times || []).join(', ');
  const sub = times ? `<small>🔔 ${esc(times)}</small>` : '';
  if (it.kind !== 'number') {
    return `<label class="item-row ${done ? 'done' : ''}">
      <input type="checkbox" data-check="${it.id}" ${done ? 'checked' : ''}>
      <div class="name"><span>${esc(it.name)}</span>${sub}</div></label>`;
  }
  const tgt = it.target != null ? ` / ${fmtNum(it.target)}` : '';
  return `<div class="item-row">
    <div class="name"><span>${esc(it.name)}</span> ${done ? '<span class="badge good">달성</span>' : ''}${sub}</div>
    <div class="num-box">
      <span class="val">${fmtNum(log?.value)}${tgt}${esc(it.unit || '')}</span>
      <input type="number" step="any" data-num="${it.id}" placeholder="+">
      <button class="small" data-add="${it.id}">더하기</button>
      <button class="small ghost" data-set="${it.id}" title="입력한 값으로 덮어쓰기">설정</button>
    </div></div>`;
}

// ═════════════════════════ 리포트 ═════════════════════════
function streaks(item, logs, from, to) {
  let best = 0, cur = 0;
  for (const d of dayRange(from, to)) {
    if (!scheduledOn(item, d)) continue;
    if (isDone(item, logs.get(logKey(item.id, d)))) { cur++; best = Math.max(best, cur); } else if (d !== to) cur = 0;
  }
  // 현재 연속: 오늘부터 거꾸로 (오늘 아직 미완료면 어제부터)
  let now = 0;
  for (let d = to; d >= from; d = addDays(d, -1)) {
    if (!scheduledOn(item, d)) continue;
    if (isDone(item, logs.get(logKey(item.id, d)))) now++;
    else if (d === to) continue;
    else break;
  }
  return { best, now };
}

async function renderReport() {
  const el = $('#tab-report');
  const to = today();
  const from = addDays(to, -(state.reportDays - 1));
  const histFrom = addDays(to, -Math.max(120, state.reportDays));
  const logs = indexLogs(await logsBetween(histFrom, to));
  const items = sortedItems().filter((i) => i.active);
  const days = dayRange(from, to);

  // 날짜별 · 카테고리별 달성
  const perDay = days.map((d) => {
    const sch = items.filter((i) => scheduledOn(i, d));
    const dn = sch.filter((i) => isDone(i, logs.get(logKey(i.id, d))));
    return { d, sch: sch.length, done: dn.length, rate: sch.length ? dn.length / sch.length : null };
  });
  const catStats = state.categories.map((c, idx) => {
    const its = items.filter((i) => i.category_id === c.id);
    let sch = 0, dn = 0;
    const daily = days.map((d) => {
      let s = 0, k = 0;
      for (const i of its) if (scheduledOn(i, d)) { s++; if (isDone(i, logs.get(logKey(i.id, d)))) k++; }
      sch += s; dn += k; return { d, s, k };
    });
    return { c, idx, its, sch, dn, daily };
  }).filter((x) => x.its.length);

  const totalSch = perDay.reduce((a, x) => a + x.sch, 0);
  const totalDone = perDay.reduce((a, x) => a + x.done, 0);
  const perfect = perDay.filter((x) => x.sch && x.done === x.sch).length;
  let active = 0;
  for (let i = perDay.length - 1; i >= 0; i--) {
    if (perDay[i].done > 0) active++; else if (i === perDay.length - 1) continue; else break;
  }
  const pct = (a, b) => (b ? Math.round((a / b) * 100) : 0);

  // 주간 테이블
  const weeks = [...new Set(days.map(weekStart))];
  const numberItems = items.filter((i) => i.kind === 'number');
  if (!numberItems.find((i) => i.id === state.trendItem)) state.trendItem = numberItems[0]?.id ?? null;

  el.innerHTML = `
    <div class="toolbar">
      <div class="title">리포트</div>
      <div class="seg">${[7, 30, 90].map((n) => `<button data-range="${n}" class="${state.reportDays === n ? 'active' : ''}">${n}일</button>`).join('')}</div>
    </div>
    <h3 style="margin:4px 0 10px">핵심 지표</h3>
    <div class="grid cols-2" style="margin-bottom:22px">
      <div class="card"><h3>생산성 점수</h3><div class="chart-box" style="height:220px"><canvas id="m-score"></canvas></div></div>
      <div class="card"><h3><span class="dot" style="background:${AREA.jp}"></span>일본어 · 새 카드 vs 목표</h3><div class="chart-box" style="height:220px"><canvas id="m-anki"></canvas></div></div>
      <div class="card"><h3><span class="dot" style="background:${AREA.sleep}"></span>수면 시간</h3><div class="chart-box" style="height:220px"><canvas id="m-sleep"></canvas></div></div>
      <div class="card"><h3><span class="dot" style="background:${AREA.health}"></span>걷기</h3><div class="chart-box" style="height:220px"><canvas id="m-walk"></canvas></div></div>
      <div class="card"><h3><span class="dot" style="background:${AREA.health}"></span>허리 통증 (0~10)</h3><div class="chart-box" style="height:220px"><canvas id="m-pain"></canvas></div></div>
    </div>
    <h3 style="margin:4px 0 10px">루틴</h3>
    <div class="grid tiles">
      <div class="card tile"><div class="label">전체 달성률</div><div class="value">${pct(totalDone, totalSch)}%</div><div class="sub">${totalDone} / ${totalSch}건</div></div>
      <div class="card tile"><div class="label">완벽한 날</div><div class="value">${perfect}일</div><div class="sub">모든 루틴 달성</div></div>
      <div class="card tile"><div class="label">연속 기록</div><div class="value">${active}일</div><div class="sub">하나 이상 기록한 날</div></div>
      ${catStats.map((s) => `<div class="card tile"><div class="label"><span class="dot" style="background:${esc(s.c.color)}"></span>${esc(s.c.name)}</div>
        <div class="value">${pct(s.dn, s.sch)}%</div><div class="sub">${s.dn} / ${s.sch}건</div></div>`).join('')}
    </div>

    <div class="grid cols-2" style="margin-top:14px">
      <div class="card"><h3>영역별 달성률 추이</h3><p class="muted">${state.reportDays > 30 ? '주 단위' : state.reportDays === 7 ? '일 단위' : '최근 7일 이동평균'}</p><div class="chart-box"><canvas id="c-rate"></canvas></div></div>
      <div class="card"><h3>수치 기록 추이</h3>
        ${numberItems.length ? `<select id="trend-select" style="max-width:260px;margin-bottom:8px">${numberItems.map((i) => `<option value="${i.id}" ${i.id === state.trendItem ? 'selected' : ''}>${esc(catName(i))} · ${esc(i.name)}</option>`).join('')}</select>
        <div class="chart-box" style="height:246px"><canvas id="c-trend"></canvas></div>` : '<p class="empty">수치 항목이 없어요</p>'}
      </div>
    </div>

    <div class="card" style="margin-top:14px"><h3>일별 달성 히트맵</h3><p class="muted">최근 ${Math.max(state.reportDays, 91) === 91 ? '13주' : state.reportDays + '일'} · 칸에 마우스를 올리면 상세</p>
      <div id="heatmap"></div></div>

    <div class="card" style="margin-top:14px"><h3>항목별 성적</h3><div class="table-wrap"><table>
      <thead><tr><th>영역</th><th>항목</th><th class="num">달성률</th><th class="num">현재 연속</th><th class="num">최고 연속</th><th class="num">합계</th><th class="num">일평균</th></tr></thead>
      <tbody>${items.map((i) => {
        let s = 0, k = 0, sum = 0;
        for (const d of days) {
          const lg = logs.get(logKey(i.id, d));
          if (scheduledOn(i, d)) { s++; if (isDone(i, lg)) k++; }
          if (i.kind === 'number' && lg?.value != null) sum += +lg.value;
        }
        const st = streaks(i, logs, histFrom, to);
        return `<tr><td><span class="dot" style="background:${esc(catColor(i))}"></span> ${esc(catName(i))}</td><td>${esc(i.name)}</td>
          <td class="num">${s ? pct(k, s) + '%' : '–'}</td><td class="num">${st.now}일</td><td class="num">${st.best}일</td>
          <td class="num">${i.kind === 'number' ? fmtNum(sum) + esc(i.unit || '') : '–'}</td>
          <td class="num">${i.kind === 'number' ? fmtNum(sum / days.length) + esc(i.unit || '') : '–'}</td></tr>`;
      }).join('')}</tbody></table></div></div>

    <div class="card" style="margin-top:14px"><h3>주간 요약</h3><div class="table-wrap"><table>
      <thead><tr><th>주 (월요일 시작)</th>${catStats.map((s) => `<th class="num">${esc(s.c.name)}</th>`).join('')}<th class="num">전체</th></tr></thead>
      <tbody>${weeks.slice().reverse().map((w) => {
        const inW = (d) => weekStart(d) === w;
        const cells = catStats.map((s) => {
          const x = s.daily.filter((r) => inW(r.d)); const a = x.reduce((p, r) => p + r.k, 0), b = x.reduce((p, r) => p + r.s, 0);
          return `<td class="num">${b ? pct(a, b) + '%' : '–'}</td>`;
        }).join('');
        const x = perDay.filter((r) => inW(r.d)); const a = x.reduce((p, r) => p + r.done, 0), b = x.reduce((p, r) => p + r.sch, 0);
        return `<tr><td>${fmtDay(w)} ~ ${fmtDay(addDays(w, 6))}</td>${cells}<td class="num"><b>${b ? pct(a, b) + '%' : '–'}</b></td></tr>`;
      }).join('')}</tbody></table></div></div>`;

  el.querySelectorAll('[data-range]').forEach((b) => b.addEventListener('click', () => { state.reportDays = +b.dataset.range; renderReport(); }));
  metricChartsHtml(from, to).then(({ data, ds }) => drawMetricCharts(data, ds));
  $('#trend-select')?.addEventListener('change', (e) => { state.trendItem = e.target.value; renderReport(); });

  // 히트맵 (최소 13주)
  const hFrom = addDays(to, -(Math.max(state.reportDays, 91) - 1));
  const hStart = weekStart(hFrom);
  const heat = isDark() ? HEAT_DARK : HEAT_LIGHT;
  const cells = dayRange(hStart, to).map((d) => {
    const sch = items.filter((i) => scheduledOn(i, d));
    const dn = sch.filter((i) => isDone(i, logs.get(logKey(i.id, d)))).length;
    const r = sch.length ? dn / sch.length : null;
    const bg = d < hFrom || r == null || dn === 0 ? '' : `background:${heat[r >= 1 ? 3 : r >= 0.75 ? 2 : r >= 0.5 ? 1 : 0]}`;
    return `<div class="cell" style="${bg}" title="${fmtDay(d)} (${DOW[isoDow(d) - 1]}) · ${sch.length ? `${dn}/${sch.length} 달성` : '예정 없음'}"></div>`;
  }).join('');
  $('#heatmap').innerHTML = `<div class="heatmap">${cells}</div>
    <div class="heat-legend">적음 <span class="cell" style="background:var(--surface-2)"></span>${heat.map((c) => `<span class="cell" style="background:${c}"></span>`).join('')} 100%</div>`;

  // 달성률 추이 차트
  // 7일: 일 단위 / 30일: 최근 7일 이동평균 / 90일: 주 단위
  const bucket = state.reportDays > 30;
  const labels = bucket ? weeks.map(fmtDay) : days.map(fmtDay);
  const rateOver = (its, ds) => {
    let a = 0, b = 0;
    for (const d of ds) for (const i of its) if (scheduledOn(i, d)) { b++; if (isDone(i, logs.get(logKey(i.id, d)))) a++; }
    return b ? Math.round((a / b) * 100) : null;
  };
  const datasets = catStats.map((s) => {
    const vals = bucket
      ? weeks.map((w) => rateOver(s.its, dayRange(w, addDays(w, 6)).filter((d) => d >= from && d <= to)))
      : state.reportDays === 7
        ? days.map((d) => rateOver(s.its, [d]))
        : days.map((d) => rateOver(s.its, dayRange(addDays(d, -6), d)));
    return { label: s.c.name, data: vals, borderColor: s.c.color, backgroundColor: s.c.color, borderWidth: 2, pointRadius: 0, pointHoverRadius: 5, tension: 0.25, spanGaps: true };
  });
  drawChart('c-rate', { type: 'line', data: { labels, datasets }, options: baseOptions({ max: 100, suffix: '%' }) });

  // 수치 추이 차트
  const ti = numberItems.find((i) => i.id === state.trendItem);
  if (ti) {
    const vals = days.map((d) => { const v = logs.get(logKey(ti.id, d))?.value; return v == null ? 0 : +v; });
    const color = catColor(ti);
    const ds = [{ type: 'bar', label: ti.name, data: vals, backgroundColor: color, borderRadius: 4, borderSkipped: 'bottom', maxBarThickness: 24 }];
    if (ti.target != null) ds.push({ type: 'line', label: '목표', data: days.map(() => +ti.target), borderColor: cssVar('--muted'), borderDash: [5, 4], borderWidth: 2, pointRadius: 0 });
    drawChart('c-trend', { type: 'bar', data: { labels: days.map(fmtDay), datasets: ds }, options: baseOptions({ suffix: ti.unit || '' }) });
  }
}

// ═════════════════════════ 사용량 ═════════════════════════
async function renderUsage() {
  const el = $('#tab-usage');
  const to = today();
  const from = addDays(to, -(state.usageDays - 1));
  const rows = await fetchAll(() => sb.from('app_usage').select('device_id,day,package,label,minutes').gte('day', from).lte('day', to));
  const devName = (id) => state.devices.find((d) => d.device_id === id)?.name ?? id.slice(0, 6);
  const days = dayRange(from, to);

  const byPkg = new Map();
  for (const r of rows) {
    const p = byPkg.get(r.package) || { pkg: r.package, label: r.label || r.package, total: 0, byDay: {}, byDev: {} };
    p.total += r.minutes; p.byDay[r.day] = (p.byDay[r.day] || 0) + r.minutes; p.byDev[r.device_id] = (p.byDev[r.device_id] || 0) + r.minutes;
    byPkg.set(r.package, p);
  }
  const apps = [...byPkg.values()].sort((a, b) => b.total - a.total);
  const top = apps.slice(0, 5);
  const devIds = [...new Set(rows.map((r) => r.device_id))];
  const todayRows = rows.filter((r) => r.day === to);
  const todayTotal = todayRows.reduce((a, r) => a + r.minutes, 0);
  const todayPkg = (pkg) => todayRows.filter((r) => r.package === pkg).reduce((a, r) => a + r.minutes, 0);

  el.innerHTML = `
    <div class="toolbar"><div class="title">앱 사용량</div>
      <div class="seg">${[7, 30].map((n) => `<button data-urange="${n}" class="${state.usageDays === n ? 'active' : ''}">${n}일</button>`).join('')}</div></div>
    ${rows.length ? '' : '<div class="card empty">아직 사용량 데이터가 없어요. 폰 앱에서 "사용량 접근"을 허용하면 15분마다 올라와요.</div>'}
    <div class="grid tiles">
      <div class="card tile"><div class="label">오늘 총 사용 (전체 폰)</div><div class="value">${fmtMin(todayTotal)}</div></div>
      ${devIds.map((id) => `<div class="card tile"><div class="label">📱 ${esc(devName(id))}</div>
        <div class="value">${fmtMin(todayRows.filter((r) => r.device_id === id).reduce((a, r) => a + r.minutes, 0))}</div><div class="sub">오늘</div></div>`).join('')}
    </div>
    ${state.limits.length ? `<div class="card" style="margin-top:14px"><h3>오늘 한도</h3>
      ${state.limits.map((l) => { const u = todayPkg(l.package); const over = u >= l.daily_limit_min;
        return `<div style="margin:8px 0"><div style="display:flex;justify-content:space-between"><span>${esc(l.label)} ${over ? '⛔ 초과' : ''}</span>
        <span class="muted">${u}분 / ${l.daily_limit_min}분</span></div>
        <div class="bar ${over ? 'over' : ''}"><span style="width:${Math.min(100, (u / l.daily_limit_min) * 100)}%"></span></div></div>`; }).join('')}</div>` : ''}
    <div class="card" style="margin-top:14px"><h3>일별 사용 시간 (상위 5개 앱, 두 폰 합산)</h3><div class="chart-box"><canvas id="c-usage"></canvas></div></div>
    <div class="card" style="margin-top:14px"><h3>앱별 합계 (${state.usageDays}일)</h3><div class="table-wrap"><table>
      <thead><tr><th>앱</th><th class="num">합계</th><th class="num">일평균</th>${devIds.map((id) => `<th class="num">${esc(devName(id))}</th>`).join('')}</tr></thead>
      <tbody>${apps.slice(0, 25).map((a) => `<tr><td>${esc(a.label)}<div class="muted">${esc(a.pkg)}</div></td><td class="num">${fmtMin(a.total)}</td>
        <td class="num">${fmtMin(Math.round(a.total / days.length))}</td>${devIds.map((id) => `<td class="num">${a.byDev[id] ? fmtMin(a.byDev[id]) : '–'}</td>`).join('')}</tr>`).join('')}</tbody>
    </table></div></div>`;

  el.querySelectorAll('[data-urange]').forEach((b) => b.addEventListener('click', () => { state.usageDays = +b.dataset.urange; renderUsage(); }));

  if (rows.length) {
    const ds = top.map((a, i) => ({ label: a.label, data: days.map((d) => a.byDay[d] || 0), backgroundColor: seriesColor(i), borderColor: cssVar('--surface'), borderWidth: { top: 2 }, borderRadius: 2, maxBarThickness: 36 }));
    const rest = apps.slice(5);
    if (rest.length) ds.push({ label: '기타', data: days.map((d) => rest.reduce((a, x) => a + (x.byDay[d] || 0), 0)), backgroundColor: cssVar('--muted'), borderColor: cssVar('--surface'), borderWidth: { top: 2 }, maxBarThickness: 36 });
    const opt = baseOptions({ suffix: '분', stacked: true });
    drawChart('c-usage', { type: 'bar', data: { labels: days.map(fmtDay), datasets: ds }, options: opt });
  }
}

// ═════════════════════════ 설정 ═════════════════════════
async function renderSettings() {
  const el = $('#tab-settings');
  const recent = await run(sb.from('app_usage').select('package,label,minutes').gte('day', addDays(today(), -7)).limit(1000));
  const pkgMap = new Map();
  for (const r of recent) pkgMap.set(r.package, { label: r.label, m: (pkgMap.get(r.package)?.m || 0) + r.minutes });
  const suggestions = [...pkgMap.entries()].sort((a, b) => b[1].m - a[1].m).slice(0, 40);

  el.innerHTML = `
    <div class="toolbar"><div class="title">설정</div>
      ${state.categories.length ? '' : '<button class="primary" id="seed">기본 템플릿으로 시작하기</button>'}</div>

    ${goalsCardHtml()}

    <div class="card" style="margin-top:14px"><h3>영역</h3><p class="muted">투자·건강·일본어·커리어 같은 큰 분류예요.</p>
      <div id="cat-list">${state.categories.map((c) => `
        <div class="set-row" data-cat="${c.id}">
          <input type="color" value="${esc(c.color)}" data-f="color">
          <input class="grow" value="${esc(c.name)}" data-f="name">
          <input class="w80" type="number" value="${c.sort}" data-f="sort" title="정렬 순서">
          <button class="small" data-act="save">저장</button><button class="small ghost danger" data-act="del">삭제</button>
        </div>`).join('')}
        <div class="set-row" data-cat="new">
          <input type="color" value="${seriesColor(state.categories.length)}" data-f="color">
          <input class="grow" placeholder="새 영역 이름" data-f="name">
          <input class="w80" type="number" value="${state.categories.length}" data-f="sort">
          <button class="small primary" data-act="save">추가</button>
        </div></div></div>

    <div class="card" style="margin-top:14px"><div class="toolbar" style="margin:0 0 6px"><h3 class="title" style="font-size:16px">루틴 항목</h3>
      <button class="small primary" id="add-item" ${state.categories.length ? '' : 'disabled'}>+ 항목 추가</button></div>
      <div class="table-wrap"><table>
        <thead><tr><th>영역</th><th>항목</th><th>유형</th><th>목표</th><th>알림</th><th>요일</th><th></th></tr></thead>
        <tbody>${sortedItems().map((i) => `<tr style="${i.active ? '' : 'opacity:.5'}">
          <td><span class="dot" style="background:${esc(catColor(i))}"></span> ${esc(catName(i))}</td><td>${esc(i.name)}</td>
          <td>${i.kind === 'number' ? '수치' : '체크'}</td><td>${i.kind === 'number' && i.target != null ? fmtNum(i.target) + esc(i.unit || '') : '–'}</td>
          <td>${esc((i.reminder_times || []).join(', ')) || '–'}</td>
          <td>${daysLabel(i.days || [])}</td>
          <td><button class="small" data-edit="${i.id}">편집</button></td></tr>`).join('')}</tbody></table></div>
      ${state.items.length ? '' : '<p class="empty">항목이 없어요</p>'}</div>

    <div class="card" style="margin-top:14px"><h3>앱 사용 한도</h3><p class="muted">두 폰 합산 기준이에요. 80%와 100%에서 폰으로 경고가 가요.</p>
      <datalist id="pkg-suggest">${suggestions.map(([p, v]) => `<option value="${esc(p)}">${esc(v.label || p)} · 최근 7일 ${v.m}분</option>`).join('')}
        <option value="com.google.android.youtube">YouTube</option></datalist>
      ${state.limits.map((l) => `<div class="set-row" data-limit="${l.id}">
          <input class="grow" value="${esc(l.label)}" data-f="label" placeholder="표시 이름">
          <input class="grow" value="${esc(l.package)}" data-f="package" list="pkg-suggest" placeholder="패키지명">
          <input class="w80" type="number" value="${l.daily_limit_min}" data-f="daily_limit_min"> 분
          <button class="small" data-act="save">저장</button><button class="small ghost danger" data-act="del">삭제</button></div>`).join('')}
      <div class="set-row" data-limit="new">
        <input class="grow" data-f="label" placeholder="표시 이름 (예: YouTube)">
        <input class="grow" data-f="package" list="pkg-suggest" placeholder="패키지명 (목록에서 선택)">
        <input class="w80" type="number" value="60" data-f="daily_limit_min"> 분
        <button class="small primary" data-act="save">추가</button></div></div>

    <div class="card" style="margin-top:14px"><h3>연결된 기기</h3>
      ${state.devices.length ? `<table><thead><tr><th>이름</th><th>마지막 동기화</th></tr></thead><tbody>
        ${state.devices.map((d) => `<tr><td>📱 ${esc(d.name)}</td><td>${new Date(d.last_seen).toLocaleString('ko-KR')}</td></tr>`).join('')}</tbody></table>`
        : '<p class="muted">폰 앱에서 로그인하면 여기에 나타나요.</p>'}</div>`;

  // 영역
  el.querySelectorAll('[data-cat]').forEach((row) => row.addEventListener('click', async (e) => {
    const act = e.target.dataset.act; if (!act) return;
    const id = row.dataset.cat;
    const val = (f) => row.querySelector(`[data-f="${f}"]`).value;
    if (act === 'del') {
      if (!confirm('이 영역과 그 안의 항목·기록이 모두 삭제돼요. 계속할까요?')) return;
      await run(sb.from('categories').delete().eq('id', id));
    } else {
      if (!val('name').trim()) { toast('이름을 입력하세요'); return; }
      const rec = { name: val('name').trim(), color: val('color'), sort: +val('sort') || 0 };
      if (id === 'new') await run(sb.from('categories').insert({ ...rec, user_id: state.user.id }));
      else await run(sb.from('categories').update(rec).eq('id', id));
    }
    toast('저장됨'); await loadMeta(); renderSettings();
  }));

  // 한도
  el.querySelectorAll('[data-limit]').forEach((row) => row.addEventListener('click', async (e) => {
    const act = e.target.dataset.act; if (!act) return;
    const id = row.dataset.limit;
    const val = (f) => row.querySelector(`[data-f="${f}"]`).value.trim();
    if (act === 'del') await run(sb.from('usage_limits').delete().eq('id', id));
    else {
      const pkg = val('package');
      if (!pkg) { toast('패키지명을 입력하세요'); return; }
      const rec = { package: pkg, label: val('label') || pkgMap.get(pkg)?.label || pkg, daily_limit_min: +val('daily_limit_min') || 60 };
      if (id === 'new') await run(sb.from('usage_limits').insert({ ...rec, user_id: state.user.id }));
      else await run(sb.from('usage_limits').update(rec).eq('id', id));
    }
    toast('저장됨'); await loadMeta(); renderSettings();
  }));

  $('#add-item')?.addEventListener('click', () => openItemDialog(null));
  el.querySelectorAll('[data-edit]').forEach((b) => b.addEventListener('click', () => openItemDialog(state.items.find((i) => i.id === b.dataset.edit))));
  $('#seed')?.addEventListener('click', seedTemplate);
  bindGoals(renderSettings);
}

// 항목 편집 다이얼로그
const dlg = $('#item-dialog');
const form = $('#item-form');
let editing = null;
function syncKind() { $$('.num-only', form).forEach((x) => x.classList.toggle('hidden', form.kind.value !== 'number')); }
form.kind.addEventListener('change', syncKind);
function openItemDialog(item) {
  editing = item;
  $('#item-dialog-title').textContent = item ? '항목 편집' : '새 항목';
  form.category_id.innerHTML = state.categories.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
  form.name.value = item?.name ?? '';
  form.category_id.value = item?.category_id ?? state.categories[0]?.id ?? '';
  form.kind.value = item?.kind ?? 'check';
  form.unit.value = item?.unit ?? '';
  form.target.value = item?.target ?? '';
  form.reminder_times.value = (item?.reminder_times ?? []).join(', ');
  const days = item?.days ?? [1, 2, 3, 4, 5, 6, 7];
  for (let d = 1; d <= 7; d++) form['d' + d].checked = days.includes(d);
  form.sort.value = item?.sort ?? 0;
  form.active.checked = item?.active ?? true;
  $('#item-delete').classList.toggle('hidden', !item);
  syncKind();
  dlg.showModal();
}
$('#item-delete').addEventListener('click', async () => {
  if (!editing || !confirm(`"${editing.name}" 항목과 기록을 삭제할까요? (기록을 남기려면 '사용 중'만 끄세요)`)) return;
  await run(sb.from('items').delete().eq('id', editing.id));
  dlg.close(); toast('삭제됨'); await loadMeta(); renderSettings();
});
dlg.addEventListener('close', async () => {
  if (dlg.returnValue !== 'save') return;
  const times = form.reminder_times.value.split(/[,\s]+/).map((t) => t.trim()).filter(Boolean);
  const bad = times.find((t) => !/^([01]?\d|2[0-3]):[0-5]\d$/.test(t));
  if (bad) { toast(`알림 시각 형식 오류: ${bad} (예: 08:00)`, 4000); return; }
  const norm = times.map((t) => t.padStart(5, '0'));
  const days = [1, 2, 3, 4, 5, 6, 7].filter((d) => form['d' + d].checked);
  const rec = {
    name: form.name.value.trim(), category_id: form.category_id.value || null, kind: form.kind.value,
    unit: form.kind.value === 'number' ? form.unit.value.trim() || null : null,
    target: form.kind.value === 'number' && form.target.value !== '' ? +form.target.value : null,
    reminder_times: norm, days: days.length ? days : [1, 2, 3, 4, 5, 6, 7],
    sort: +form.sort.value || 0, active: form.active.checked,
  };
  if (editing) await run(sb.from('items').update(rec).eq('id', editing.id));
  else await run(sb.from('items').insert({ ...rec, user_id: state.user.id }));
  toast('저장됨 · 폰에는 앱을 열거나 15분 내 반영돼요', 3000);
  await loadMeta(); renderSettings();
});

async function seedTemplate() {
  const uid = state.user.id;
  const cats = await run(sb.from('categories').insert([
    { user_id: uid, name: '건강', color: '#1baf7a', sort: 0 },
    { user_id: uid, name: '일본어', color: '#eb6834', sort: 1 },
    { user_id: uid, name: '투자', color: '#2a78d6', sort: 2 },
    { user_id: uid, name: '커리어', color: '#4a3aa7', sort: 3 },
  ]).select());
  const c = Object.fromEntries(cats.map((x) => [x.name, x.id]));
  const WD = [1, 2, 3, 4, 5];
  await run(sb.from('items').insert([
    { name: '영양제 (아침)', category_id: c['건강'], kind: 'check', reminder_times: ['08:00'], sort: 0 },
    { name: '영양제 (저녁)', category_id: c['건강'], kind: 'check', reminder_times: ['21:00'], sort: 1 },
    { name: '운동', category_id: c['건강'], kind: 'number', unit: '분', target: 30, reminder_times: ['20:00'], sort: 2 },
    { name: '단어 암기', category_id: c['일본어'], kind: 'number', unit: '개', target: 30, reminder_times: ['22:00'], sort: 0 },
    { name: 'Anki 복습', category_id: c['일본어'], kind: 'check', reminder_times: ['07:30'], sort: 1 },
    { name: '시장 브리핑 확인', category_id: c['투자'], kind: 'check', reminder_times: ['07:45'], days: WD, sort: 0 },
    { name: '투자 일지', category_id: c['투자'], kind: 'check', reminder_times: [], days: [6, 7], sort: 1 },
    { name: '커리어 공부', category_id: c['커리어'], kind: 'number', unit: '분', target: 30, reminder_times: ['22:30'], sort: 0 },
  ].map((x) => ({ unit: null, target: null, days: [1, 2, 3, 4, 5, 6, 7], ...x, user_id: uid }))));
  if (!state.limits.find((l) => l.package === 'com.google.android.youtube'))
    await run(sb.from('usage_limits').insert({ user_id: uid, package: 'com.google.android.youtube', label: 'YouTube', daily_limit_min: 60 }));
  toast('템플릿을 추가했어요. 항목 이름·시간을 원하는 대로 바꾸세요', 3500);
  await loadMeta(); renderSettings();
}

// ───────────────────────── 차트 공통 ─────────────────────────
function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
function baseOptions({ max, suffix = '', stacked = false } = {}) {
  const grid = cssVar('--grid'), text = cssVar('--text-2');
  return {
    responsive: true, maintainAspectRatio: false, animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      legend: { position: 'bottom', labels: { color: text, boxWidth: 10, boxHeight: 10, usePointStyle: true } },
      tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${c.parsed.y == null ? '–' : fmtNum(c.parsed.y) + suffix}` } },
    },
    scales: {
      x: { stacked, grid: { display: false }, ticks: { color: text, maxRotation: 0, autoSkipPadding: 12 } },
      y: { stacked, beginAtZero: true, max, grid: { color: grid }, border: { display: false }, ticks: { color: text, callback: (v) => v + suffix } },
    },
  };
}
function drawChart(id, cfg) {
  charts[id]?.destroy();
  const cv = document.getElementById(id);
  if (cv && window.Chart) charts[id] = new Chart(cv, cfg);
}

boot();
