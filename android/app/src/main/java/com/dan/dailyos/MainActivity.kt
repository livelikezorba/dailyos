package com.dan.dailyos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val C_TEXT = Color.parseColor("#1F2330")
    private val C_SUB = Color.parseColor("#6B7280")
    private val C_ACCENT = Color.parseColor("#3B5BDB")
    private val C_WARN = Color.parseColor("#E03131")
    private val C_LINE = Color.parseColor("#E5E7EB")
    private val C_JP = Color.parseColor("#EB6834")
    private val C_HEALTH = Color.parseColor("#1BAF7A")
    private val C_SLEEP = Color.parseColor("#4A3AA7")

    private var month: YearMonth = YearMonth.from(LocalDate.parse(today()))
    private var selected: LocalDate = LocalDate.parse(today())
    private var settings: AppSettings = SettingsStore.parse(null)
    private var limits: List<Limit> = emptyList()
    private var days: Map<String, DayData> = emptyMap()
    private var items: List<Item> = emptyList()
    private var selLogs: Map<String, Log> = emptyMap()
    private var selUsage: List<UsageRow> = emptyList()
    private var deviceNames: Map<String, String> = emptyMap()
    private var error: String? = null

    private data class UsageRow(val device: String, val pkg: String, val label: String, val minutes: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        settings = SettingsStore.cached()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Supa.configured) { renderMessage("Supabase 설정이 비어 있어요.\nGitHub Secrets(SUPABASE_URL, SUPABASE_KEY)를 확인하고 다시 빌드하세요."); return }
        if (!Prefs.loggedIn) renderLogin() else load()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Prefs.loggedIn) load()
    }

    // ── UI 헬퍼 ─────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = C_TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        if (primary) {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(C_ACCENT); cornerRadius = dp(10).toFloat() }
        }
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        minHeight = 0; minimumHeight = dp(36); minWidth = 0; minimumWidth = dp(56)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    /** 왼쪽 글, 오른쪽 버튼 한 줄 */
    private fun row(label: String, sub: String? = null, color: Int = C_TEXT, action: Pair<String, () -> Unit>? = null): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(text(label, 15f, color))
        if (sub != null) col.addView(text(sub, 12f, C_SUB))
        r.addView(col)
        if (action != null) r.addView(smallButton(action.first, action.second))
        return r
    }

    private fun sectionTitle(label: String, color: Int) =
        text("● $label", 15f, color, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) }

    private fun bg(work: () -> Unit) = thread {
        try { work() } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, e.message ?: "오류", Toast.LENGTH_LONG).show() }
        }
    }

    // ── 로그인 ─────────────────────────────────
    private fun renderMessage(msg: String) {
        root.removeAllViews()
        root.addView(text("DailyOS", 26f, bold = true))
        root.addView(card().apply { addView(text(msg)) })
    }

    private fun renderLogin() {
        root.removeAllViews()
        root.addView(text("DailyOS", 26f, bold = true))
        root.addView(text("Supabase에 만든 계정으로 로그인하세요", 14f, C_SUB))
        val c = card()
        val email = EditText(this).apply { hint = "이메일"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pw = EditText(this).apply {
            hint = "비밀번호"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val name = EditText(this).apply { hint = "이 폰 이름 (예: 메인폰)"; setText(Prefs.deviceName) }
        c.addView(email); c.addView(pw); c.addView(name)
        c.addView(button("로그인", primary = true) {
            val e = email.text.toString().trim(); val p = pw.text.toString()
            Prefs.deviceName = name.text.toString().trim().ifBlank { Prefs.deviceName }
            bg {
                Supa.signIn(e, p)
                try { SyncJob.run(this) } catch (ex: Exception) { }
                runOnUiThread { SyncJob.schedule(this); load() }
            }
        })
        root.addView(c)
    }

    // ── 데이터 불러오기 ───────────────────────────
    private fun load() {
        SyncJob.schedule(this)
        Daily.scheduleNext(this)
        if (root.childCount == 0) renderMessage("불러오는 중…")
        thread {
            try {
                Repo.flushPending()
                settings = try { SettingsStore.fetch() } catch (e: Exception) { SettingsStore.cached() }
                limits = Repo.fetchLimits()
                items = Repo.fetchItems()
                Reminders.scheduleNext(this)
                try { Anki.upload(this, settings) } catch (e: Exception) { }
                if (Usage.hasPermission(this)) {
                    val t = LocalDate.now()
                    try { Repo.uploadUsage(t.toString(), Usage.collect(this, t)) } catch (e: Exception) { }
                    try { SyncJob.uploadNight(this, t) } catch (e: Exception) { }
                }
                deviceNames = try {
                    val a = Supa.select("devices", "select=device_id,name")
                    (0 until a.length()).associate { a.getJSONObject(it).getString("device_id") to a.getJSONObject(it).getString("name") }
                } catch (e: Exception) { emptyMap() }
                loadMonth()
                loadSelected()
                error = null
            } catch (e: NotLoggedIn) {
                runOnUiThread { renderLogin() }; return@thread
            } catch (e: Exception) {
                error = e.message ?: "연결 오류"
            }
            runOnUiThread { render() }
        }
    }

    private fun loadMonth() {
        days = Metrics.loadRange(month.atDay(1), month.atEndOfMonth(), settings, limits)
    }

    private fun loadSelected() {
        val d = selected.toString()
        selLogs = Repo.fetchLogs(d)
        val a = Supa.select("app_usage", "select=device_id,package,label,minutes&day=eq.$d")
        selUsage = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            UsageRow(o.getString("device_id"), o.getString("package"), o.optString("label", o.getString("package")), o.optInt("minutes"))
        }
        if (!days.containsKey(d)) {
            val extra = Metrics.loadRange(selected, selected, settings, limits)
            days = days + extra
        }
    }

    private fun reloadData(alsoMonth: Boolean = true) = thread {
        try {
            if (alsoMonth) loadMonth()
            loadSelected()
        } catch (e: Exception) { error = e.message }
        runOnUiThread { render() }
    }

    // ── 화면 ────────────────────────────────────
    private fun render() {
        root.removeAllViews()
        // 헤더: 월 이동
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("${month.year}년 ${month.monthValue}월", 24f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        head.addView(smallButton("‹") { month = month.minusMonths(1); reloadData() })
        head.addView(smallButton("오늘") {
            month = YearMonth.from(LocalDate.parse(today())); selected = LocalDate.parse(today()); reloadData()
        })
        head.addView(smallButton("›") { month = month.plusMonths(1); reloadData() })
        root.addView(head)
        if (error != null) root.addView(text("⚠ 오프라인: $error", 13f, C_WARN))
        val pending = Repo.pendingCount()
        if (pending > 0) root.addView(text("동기화 대기 중인 기록 ${pending}건", 13f, C_SUB))

        renderPermissions()
        renderCalendar()
        renderDetail()

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        actions.addView(button("새로고침") { load() })
        if (BuildConfig.DASHBOARD_URL.isNotBlank())
            actions.addView(button("대시보드") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL))) })
        actions.addView(button("설정") { settingsMenu() })
        root.addView(actions)

        val last = Prefs.long("last_sync")
        root.addView(text(
            Prefs.deviceName + (if (last > 0) " · 백그라운드 동기화 " +
                java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(java.util.Date(last)) else ""),
            12f, C_SUB
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
    }

    private fun dot(color: Int, ratio: Double?): View = View(this).apply {
        val c = when {
            ratio == null || ratio <= 0.0 -> C_LINE
            ratio >= 0.999 -> color
            else -> Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
        }
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(1); marginEnd = dp(1) }
    }

    private fun renderCalendar() {
        val c = card()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, d ->
            header.addView(text(d, 12f, if (i >= 5) C_WARN else C_SUB).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
        c.addView(header)
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val len = month.lengthOfMonth()
        val weeks = (offset + len + 6) / 7
        val logicalToday = LocalDate.parse(today())
        for (w in 0 until weeks) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..6) {
                val n = w * 7 + col - offset + 1
                if (n !in 1..len) {
                    r.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f) })
                    continue
                }
                val date = month.atDay(n)
                val dd = days[date.toString()]
                val sc = dd?.score
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(1), dp(2), dp(1), dp(2)) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        val a = if (sc == null) 0 else (sc.total / 100.0 * 110).toInt().coerceIn(0, 110)
                        setColor(Color.argb(a, 59, 91, 219))
                        if (date == selected) setStroke(dp(2), C_ACCENT)
                        else if (date == logicalToday) setStroke(dp(1), C_SUB)
                    }
                    setOnClickListener { selected = date; reloadData(alsoMonth = false) }
                }
                val future = date.isAfter(logicalToday)
                cell.addView(text("$n", 14f, if (future) C_LINE else C_TEXT, bold = date == logicalToday).apply { gravity = Gravity.CENTER })
                if (!future) {
                    val dots = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    }
                    dots.addView(dot(C_JP, sc?.jp))
                    dots.addView(dot(C_HEALTH, sc?.ex))
                    dots.addView(dot(C_SLEEP, sc?.sleep))
                    cell.addView(dots)
                }
                r.addView(cell)
            }
            c.addView(r)
        }
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        legend.addView(text("● 일본어  ", 12f, C_JP)); legend.addView(text("● 건강  ", 12f, C_HEALTH))
        legend.addView(text("● 잠  ", 12f, C_SLEEP)); legend.addView(text("배경 진할수록 점수↑", 12f, C_SUB))
        c.addView(legend)
        root.addView(c)
    }

    private fun renderDetail() {
        val d = selected.toString()
        val dd = days[d]
        val logicalToday = LocalDate.parse(today())
        val editable = !selected.isAfter(logicalToday)
        val title = selected.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))

        // 점수
        val top = card()
        val sc = dd?.score
        top.addView(text(title, 14f, C_SUB))
        top.addView(text(if (sc != null) "생산성 ${sc.total}점" else "기록 없음", 24f, bold = true))
        if (sc != null) {
            val w = settings.w
            top.addView(text(
                "일본어 ${pts(w.jp * sc.jp)}/${pts(w.jp)} · 운동 ${pts(w.ex * sc.ex)}/${pts(w.ex)} · 잠 ${pts(w.sleep * sc.sleep)}/${pts(w.sleep)}\n" +
                    "폰 절제 ${pts(w.phone * sc.phone)}/${pts(w.phone)} · 기록 ${pts(w.rec * sc.rec)}/${pts(w.rec)}",
                12f, C_SUB
            ))
        }
        root.addView(top)

        // 일본어
        val jp = card()
        jp.addView(sectionTitle("일본어 (Anki)", C_JP))
        val a = dd?.anki
        if (a == null) {
            jp.addView(text(ankiHint(), 14f, C_SUB))
        } else {
            val req = a.requiredNew ?: 0
            val nw = a.newStudied ?: 0
            jp.addView(row("새 카드 $nw / 목표 $req", null, if (nw >= req) C_TEXT else C_WARN))
            jp.addView(row("복습 ${a.reviewed ?: 0}장" + (a.dueLeft?.let { " · 남은 복습 ${it}장" } ?: "")))
            val dleft = try { ChronoUnit.DAYS.between(selected, LocalDate.parse(settings.ankiTarget)) } catch (e: Exception) { 0L }
            if (a.remainingNew != null)
                jp.addView(row("남은 새 카드 ${a.remainingNew}장", "목표일 ${settings.ankiTarget} (D-$dleft)"))
            if (a.total != null) jp.addView(row("암기 완료 ${a.mature ?: 0} / 전체 ${a.total}"))
            if ((a.reviewed ?: 0) >= settings.reviewWarn)
                jp.addView(text("⚠️ 복습이 ${settings.reviewWarn}장을 넘었어요. 새 카드 수를 줄이는 걸 고려하세요.", 13f, C_WARN))
        }
        root.addView(jp)

        // 건강
        val h = dd?.health
        val he = card()
        he.addView(sectionTitle("건강 (허리)", C_HEALTH))
        val walk = h?.walkMin ?: 0.0
        he.addView(row(
            "🚶 걷기 ${fmtNum(walk)} / ${settings.walkGoal}분", h?.walkKm?.let { "${fmtNum(it)} km" },
            if (walk >= settings.walkGoal) C_TEXT else C_WARN, if (editable) "+ 입력" to { walkDialog(d) } else null
        ))
        val push = h?.pushups ?: 0
        he.addView(row(
            "💪 푸시업 $push / ${settings.pushupGoal}개", null,
            if (push >= settings.pushupGoal) C_TEXT else C_WARN, if (editable) "+ 입력" to { pushDialog(d) } else null
        ))
        he.addView(row(
            "🩺 아침 통증 ${h?.painAm ?: "–"}", h?.noteAm?.ifBlank { null }, C_TEXT,
            if (editable) "기록" to { painDialog(d, "am", h?.painAm, h?.noteAm) } else null
        ))
        he.addView(row(
            "🩺 저녁 통증 ${h?.painPm ?: "–"}", h?.notePm?.ifBlank { null }, C_TEXT,
            if (editable) "기록" to { painDialog(d, "pm", h?.painPm, h?.notePm) } else null
        ))
        root.addView(he)

        // 잠
        val sl = card()
        sl.addView(sectionTitle("잠", C_SLEEP))
        val s = dd?.sleep
        when {
            s == null -> sl.addView(text(if (Usage.hasPermission(this)) "데이터 없음" else "사용량 접근 권한이 필요해요", 14f, C_SUB))
            s.status == "ok" -> {
                sl.addView(row(
                    "${Metrics.fmtClock(s.start)} → ${Metrics.fmtClock(s.end)} · ${Metrics.fmtHours(s.minutes)}",
                    "목표 ${settings.sleepGoal / 60}시간 · 두 폰 모두 안 쓴 시간 기준",
                    if ((s.minutes ?: 0) >= settings.sleepGoal) C_TEXT else C_WARN
                ))
            }
            s.status == "pending" -> sl.addView(row("측정 중", "마지막 사용 ${Metrics.fmtClock(s.start)} · 아침에 폰을 쓰면 확정돼요"))
            else -> sl.addView(row("기상 기록 없음", "잠든 시각 ${Metrics.fmtClock(s.start)}"))
        }
        root.addView(sl)

        // 앱 사용량
        val us = card()
        us.addView(text("📱 앱 사용량 (두 폰 합산)", 15f, bold = true))
        if (selUsage.isEmpty()) us.addView(text("기록 없음", 14f, C_SUB))
        val byPkg = selUsage.groupBy { it.pkg }
        for (l in limits) {
            val used = byPkg[l.pkg]?.sumOf { it.minutes } ?: 0
            us.addView(row("${l.label} ${used}분 / 한도 ${l.minutes}분" + if (used > l.minutes) " ⛔" else "", null,
                if (used > l.minutes) C_WARN else C_TEXT))
        }
        val topApps = byPkg.entries.map { (pkg, rows) -> Triple(rows.first().label.ifBlank { pkg }, rows.sumOf { it.minutes }, rows) }
            .sortedByDescending { it.second }.take(6)
        if (topApps.isNotEmpty()) us.addView(text("상위 앱", 12f, C_SUB).apply { setPadding(0, dp(6), 0, 0) })
        for ((label, total, rows) in topApps) {
            val per = rows.joinToString(" · ") { "${deviceNames[it.device] ?: "폰"} ${it.minutes}분" }
            us.addView(row("$label · ${total / 60}시간 ${total % 60}분", if (rows.size > 1) per else deviceNames[rows[0].device]))
        }
        root.addView(us)

        // 기존 루틴 체크
        val todays = items.filter { it.scheduledOn(selected) }
        if (todays.isNotEmpty()) {
            val rc = card()
            rc.addView(text("루틴", 15f, bold = true))
            for ((cat, list) in todays.groupBy { it.catName }) {
                rc.addView(text(cat, 12f, Reminders.safeColor(list.first().catColor), bold = true).apply { setPadding(0, dp(6), 0, 0) })
                for (item in list) rc.addView(itemRow(item, selLogs[item.id], d, editable))
            }
            root.addView(rc)
        }
    }

    private fun pts(v: Double) = Math.round(v).toString()

    private fun ankiHint(): String = when {
        !Anki.installed(this) -> "이 폰에는 AnkiDroid가 없어요. (Anki 공부하는 폰에서 기록돼요)"
        !Anki.hasPermission(this) -> "위의 [Anki 연결 허용]을 눌러주세요"
        settings.ankiDecks.isEmpty() -> "설정 → Anki 덱 선택에서 덱을 고르세요"
        else -> "이 날의 Anki 기록이 없어요"
    }

    private fun itemRow(item: Item, log: Log?, day: String, editable: Boolean): View {
        if (!item.isNumber) {
            return CheckBox(this).apply {
                text = item.name; textSize = 15f; setTextColor(C_TEXT)
                isChecked = item.isDone(log); isEnabled = editable
                setOnCheckedChangeListener { _, checked ->
                    bg {
                        Repo.setDone(item.id, day, checked)
                        if (checked) Reminders.cancel(this@MainActivity, item.id)
                    }
                }
            }
        }
        val tgt = item.target?.let { "/${fmtNum(it)}" } ?: ""
        return row(
            (if (item.isDone(log)) "✅ " else "") + item.name, "${fmtNum(log?.value)}$tgt${item.unit}", C_TEXT,
            if (editable) "+" to { numberDialog(item, log, day) } else null
        )
    }

    // ── 입력 다이얼로그 ───────────────────────────
    private fun numField(hint: String, decimal: Boolean = true) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
            (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }

    private fun box(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it) }
    }

    private fun walkDialog(day: String) {
        val min = numField("분 (예: 60)")
        val km = numField("km (선택, 예: 4.5)")
        AlertDialog.Builder(this).setTitle("🚶 걷기 추가")
            .setMessage("입력한 만큼 오늘 기록에 더해져요. 잘못 넣었으면 음수(-10)로 빼세요.")
            .setView(box(min, km))
            .setPositiveButton("더하기") { _, _ ->
                val m = min.text.toString().toDoubleOrNull() ?: 0.0
                val k = km.text.toString().toDoubleOrNull() ?: 0.0
                if (m != 0.0 || k != 0.0) bg { Repo.addHealth(day, walkMin = m, walkKm = k); reloadData() }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun pushDialog(day: String) {
        val n = numField("개수 (예: 20)", decimal = false)
        AlertDialog.Builder(this).setTitle("💪 푸시업 추가")
            .setMessage("입력한 만큼 더해져요. 빼려면 음수로 입력하세요.")
            .setView(box(n))
            .setPositiveButton("더하기") { _, _ ->
                val v = n.text.toString().toIntOrNull() ?: 0
                if (v != 0) bg { Repo.addHealth(day, pushups = v); reloadData() }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun painDialog(day: String, whenKey: String, cur: Int?, note: String?) {
        val score = numField("통증 0~10", decimal = false).apply { cur?.let { setText(it.toString()) } }
        val memo = EditText(this).apply { hint = "짧은 메모 (선택)"; setText(note ?: "") }
        AlertDialog.Builder(this).setTitle(if (whenKey == "am") "🩺 아침 통증" else "🩺 저녁 통증")
            .setMessage("0 = 전혀 없음, 10 = 매우 심함")
            .setView(box(score, memo))
            .setPositiveButton("저장") { _, _ ->
                val v = score.text.toString().toIntOrNull()
                if (v == null || v !in 0..10) Toast.makeText(this, "0~10 사이 숫자를 입력하세요", Toast.LENGTH_SHORT).show()
                else bg { Repo.setPain(day, whenKey, v, memo.text.toString().trim()); reloadData() }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun numberDialog(item: Item, log: Log?, day: String) {
        val input = numField("숫자 (${item.unit.ifBlank { "값" }})")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("현재 ${fmtNum(log?.value)}${item.unit}")
            .setView(box(input))
            .setPositiveButton("더하기") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { v -> bg { Repo.addValue(item.id, day, v); reloadData(false) } }
            }
            .setNeutralButton("이 값으로 설정") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { v -> bg { Repo.setValue(item.id, day, v, item.target); reloadData(false) } }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 권한 ───────────────────────────────────
    private fun renderPermissions() {
        val missing = ArrayList<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing += "알림 허용" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        if (!Usage.hasPermission(this))
            missing += "사용량 접근 허용 (DailyOS 찾아서 켜기)" to { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        if (Anki.installed(this) && !Anki.hasPermission(this))
            missing += "Anki 연결 허용" to { requestPermissions(arrayOf(Anki.PERM), 2) }
        if (!Reminders.canExact(this) && Build.VERSION.SDK_INT >= 31)
            missing += "정확한 시간 알림 허용" to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            missing += "배터리 최적화 제외 (알림 누락 방지)" to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        if (missing.isEmpty()) return
        val c = card()
        c.addView(text("권한 설정이 필요해요", 15f, C_WARN, bold = true))
        for ((label, act) in missing) c.addView(button(label) { act() })
        root.addView(c)
    }

    // ── 설정 ───────────────────────────────────
    private fun settingsMenu() {
        val options = arrayOf(
            "Anki 덱 선택 (지금: ${settings.ankiDecks.size}개)",
            "새 카드 완료 목표일 (지금: ${settings.ankiTarget})",
            "Anki 연결 테스트",
            "이 폰 이름 (${Prefs.deviceName})",
            "로그아웃"
        )
        AlertDialog.Builder(this).setTitle("설정").setItems(options) { _, which ->
            when (which) {
                0 -> deckDialog()
                1 -> targetDialog()
                2 -> ankiTest()
                3 -> nameDialog()
                4 -> { Prefs.clearSession(); Reminders.scheduleNext(this); renderLogin() }
            }
        }.setNegativeButton("닫기", null).show()
    }

    private fun needAnki(): Boolean {
        if (!Anki.installed(this)) { Toast.makeText(this, "이 폰에 AnkiDroid가 설치돼 있지 않아요", Toast.LENGTH_LONG).show(); return false }
        if (!Anki.hasPermission(this)) { requestPermissions(arrayOf(Anki.PERM), 2); return false }
        return true
    }

    private fun deckDialog() {
        if (!needAnki()) return
        bg {
            val names = Anki.decks(this).map { it.name }.filter { !it.contains("::") }
            runOnUiThread {
                if (names.isEmpty()) { Toast.makeText(this, "덱을 불러오지 못했어요. [Anki 연결 테스트]를 해보세요", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val checked = BooleanArray(names.size) { settings.ankiDecks.contains(names[it]) }
                AlertDialog.Builder(this).setTitle("추적할 Anki 덱")
                    .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton("저장") { _, _ ->
                        val sel = names.filterIndexed { i, _ -> checked[i] }
                        bg {
                            SettingsStore.update { it.put("anki_decks", JSONArray(sel)) }
                            settings = SettingsStore.cached()
                            Anki.upload(this, settings)
                            reloadData()
                        }
                    }
                    .setNegativeButton("취소", null).show()
            }
        }
    }

    private fun targetDialog() {
        val cur = try { LocalDate.parse(settings.ankiTarget) } catch (e: Exception) { LocalDate.now().plusMonths(1) }
        DatePickerDialog(this, { _, y, m, d ->
            val v = LocalDate.of(y, m + 1, d).toString()
            bg {
                SettingsStore.update { it.put("anki_target", v) }
                settings = SettingsStore.cached()
                try { Anki.upload(this, settings) } catch (e: Exception) { }
                reloadData()
            }
        }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
    }

    private fun ankiTest() {
        if (!needAnki()) return
        bg {
            val msg = StringBuilder()
            try {
                val decks = Anki.decks(this)
                msg.append("덱 ${decks.size}개 확인\n")
                val tracked = settings.ankiDecks
                msg.append("추적 덱: ${tracked.joinToString(", ").ifBlank { "없음" }}\n\n")
                if (tracked.isNotEmpty()) {
                    val q = Anki.deckQuery(tracked)
                    fun line(label: String, extra: String) {
                        val n = try { Anki.count(this, if (extra.isEmpty()) q else "$q $extra") } catch (e: Exception) { -2 }
                        msg.append("$label: ${if (n < 0) "실패($n)" else n.toString()}\n")
                    }
                    line("전체", "")
                    line("남은 새 카드 (is:new)", "is:new")
                    line("오늘 새로 공부 (introduced:1)", "introduced:1")
                    line("오늘 답한 카드 (rated:1)", "rated:1")
                    line("암기 완료 (ivl≥21)", "prop:ivl>=21")
                    val t = decks.filter { tracked.contains(it.name) }
                    msg.append("오늘 남은 학습/복습/새: ${t.sumOf { it.learn }}/${t.sumOf { it.review }}/${t.sumOf { it.new }}\n")
                }
            } catch (e: Exception) {
                msg.append("오류: ${e.javaClass.simpleName} ${e.message}")
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Anki 연결 테스트").setMessage(msg.toString())
                    .setPositiveButton("확인", null).show()
            }
        }
    }

    private fun nameDialog() {
        val name = EditText(this).apply { setText(Prefs.deviceName) }
        AlertDialog.Builder(this).setTitle("이 폰 이름").setView(box(name))
            .setPositiveButton("저장") { _, _ ->
                Prefs.deviceName = name.text.toString().trim().ifBlank { Prefs.deviceName }
                bg { Repo.registerDevice() }
                load()
            }
            .setNegativeButton("닫기", null).show()
    }
}
