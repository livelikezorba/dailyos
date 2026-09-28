// DailyOS 공통 계산 — 안드로이드 Metrics.kt 와 같은 규칙을 유지하세요.

export const DEFAULT_SETTINGS = {
  anki_decks: ['N1 4월교재_단어장', 'N1_4월교재_문제파일'],
  anki_target: '2026-10-31',   // 새 카드 완료 목표일
  anki_review_warn: 250,        // 하루 복습 이 이상이면 과부하 경고
  sleep_goal_min: 420,          // 7시간
  walk_goal_min: 60,
  pushup_goal: 20,
  weights: { jp: 35, ex: 20, sleep: 30, phone: 10, rec: 5 },
};

export function mergeSettings(data) {
  const d = data || {};
  return { ...DEFAULT_SETTINGS, ...d, weights: { ...DEFAULT_SETTINGS.weights, ...(d.weights || {}) } };
}

// 새벽 4시 이전은 전날로 취급 (Anki와 동일)
export function logicalToday(now = new Date()) {
  const d = new Date(now.getTime() - 4 * 3600 * 1000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * 수면 계산. sessions = 모든 기기의 [시작분, 끝분] (해당 날짜 0시 기준, 0~720)
 * - 잠든 시각: 07:00 이전에 시작한 사용 구간 중 가장 늦게 끝난 시각 (없으면 00:00)
 * - 일어난 시각: 그 이후 처음 시작한 사용 구간
 * nowMin: 오늘이면 현재 시각(0시 기준 분), 지난 날짜면 null
 */
export function computeSleep(sessions, nowMin = null) {
  if (!sessions || !sessions.length) {
    if (nowMin != null && nowMin < 720) return { start: 0, end: null, minutes: null, status: 'pending' };
    return null;
  }
  const s = sessions.map(([a, b]) => [Math.max(0, a), Math.min(720, b)]).filter(([a, b]) => b >= a).sort((x, y) => x[0] - y[0]);
  const pre = s.filter(([a]) => a < 420);
  const start = pre.length ? Math.max(...pre.map(([, b]) => b)) : 0;
  const post = s.filter(([a]) => a >= 420 && a > start);
  if (!post.length) {
    if (nowMin != null && nowMin < 720) return { start, end: null, minutes: null, status: 'pending' };
    return { start, end: null, minutes: null, status: 'unknown' };
  }
  const end = post[0][0];
  return { start, end, minutes: end - start, status: 'ok' };
}

export const fmtClock = (m) => (m == null ? '–' : `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
export const fmtHours = (m) => (m == null ? '–' : `${Math.floor(m / 60)}시간 ${m % 60}분`);

/** 목표일까지 남은 새 카드를 오늘 몇 장 해야 하는지 */
export function requiredNew(remainingNew, studiedToday, day, target) {
  const start = (remainingNew || 0) + (studiedToday || 0);
  const days = Math.round((Date.parse(target) - Date.parse(day)) / 86400000) + 1;
  if (!Number.isFinite(days)) return 0;
  if (days <= 0) return start;
  return Math.ceil(start / days);
}

/**
 * 하루 점수. 입력:
 *  anki:   anki_daily 행 or null
 *  health: health_log 행 or null
 *  sleep:  computeSleep 결과 or null
 *  phone:  { used, limit } 한도 대상 앱 합계(분)
 * 반환: { total, parts: {jp, ex, sleep, phone, rec}, ratios: {jp, ex, sleep, phone, rec} }
 */
export function dayScore(settings, { anki, health, sleep, phone }) {
  const W = settings.weights;
  const r = {};
  if (anki) {
    const req = anki.required_new ?? 0;
    const newPart = req <= 0 ? 1 : Math.min((anki.new_studied || 0) / req, 1);
    const rev = anki.reviewed || 0, left = anki.due_left;
    const revPart = left == null ? (rev > 0 ? 1 : 0) : rev + left === 0 ? 1 : rev / (rev + left);
    r.jp = 0.6 * newPart + 0.4 * revPart;
  } else r.jp = 0;
  const walk = +(health?.walk_min || 0), push = +(health?.pushups || 0);
  r.ex = 0.6 * Math.min(walk / settings.walk_goal_min, 1) + 0.4 * Math.min(push / settings.pushup_goal, 1);
  r.sleep = sleep?.minutes != null ? Math.min(sleep.minutes / settings.sleep_goal_min, 1) : 0;
  if (!phone || !phone.limit) r.phone = 1;
  else r.phone = phone.used <= phone.limit ? 1 : Math.max(0, 1 - (phone.used - phone.limit) / phone.limit);
  r.rec = (health?.pain_am != null ? 0.5 : 0) + (health?.pain_pm != null ? 0.5 : 0);
  const parts = {};
  let total = 0;
  for (const k of Object.keys(W)) { parts[k] = W[k] * (r[k] || 0); total += parts[k]; }
  return { total: Math.round(total), parts, ratios: r };
}
