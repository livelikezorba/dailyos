package com.dan.dailyos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val C_TEXT = Color.parseColor("#1F2330")
    private val C_SUB = Color.parseColor("#6B7280")
    private val C_ACCENT = Color.parseColor("#3B5BDB")
    private val C_WARN = Color.parseColor("#E03131")

    private data class Snapshot(
        val items: List<Item>, val logs: Map<String, Log>, val limits: List<Limit>,
        val totals: Map<String, Int>, val mine: List<Usage.Entry>, val error: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
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

    // ── 공통 UI 헬퍼 ─────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = C_TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        if (primary) {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(C_ACCENT); cornerRadius = dp(10).toFloat() }
        }
        setOnClickListener { onClick() }
    }

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

    // ── 메인 화면 ────────────────────────────────
    private fun load() {
        SyncJob.schedule(this)
        if (root.childCount == 0) renderMessage("불러오는 중…")
        thread {
            val snap = try {
                Repo.flushPending()
                val items = Repo.fetchItems()
                Reminders.scheduleNext(this)
                val day = today()
                val logs = Repo.fetchLogs(day)
                Reminders.dismissCompleted(this, items, logs)
                var mine = emptyList<Usage.Entry>()
                if (Usage.hasPermission(this)) {
                    mine = Usage.collect(this, LocalDate.now())
                    try { Repo.uploadUsage(day, mine) } catch (e: Exception) { }
                }
                Snapshot(items, logs, Repo.fetchLimits(), Repo.usageTotals(day), mine, null)
            } catch (e: NotLoggedIn) {
                runOnUiThread { renderLogin() }; return@thread
            } catch (e: Exception) {
                Snapshot(Repo.cachedItems(), emptyMap(), emptyList(), emptyMap(), emptyList(), e.message ?: "연결 오류")
            }
            runOnUiThread { render(snap) }
        }
    }

    private fun render(s: Snapshot) {
        root.removeAllViews()
        val date = LocalDate.now()
        root.addView(text(date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)), 14f, C_SUB))
        root.addView(text("오늘의 루틴", 26f, bold = true))
        if (s.error != null) root.addView(text("⚠ 오프라인: ${s.error}", 13f, C_WARN))
        val pending = Repo.pendingCount()
        if (pending > 0) root.addView(text("동기화 대기 중인 기록 ${pending}건", 13f, C_SUB))

        renderPermissions()

        val todayItems = s.items.filter { it.scheduledOn(date) }
        val done = todayItems.count { it.isDone(s.logs[it.id]) }
        if (todayItems.isNotEmpty()) {
            val c = card()
            c.addView(text("달성 $done / ${todayItems.size}", 18f, bold = true))
            c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = todayItems.size; progress = done
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(10)).apply { topMargin = dp(6) }
            })
            root.addView(c)
        } else {
            root.addView(card().apply { addView(text("오늘 예정된 루틴이 없어요.\nPC 대시보드의 [설정]에서 루틴을 추가하세요.", 14f, C_SUB)) })
        }

        for ((cat, list) in todayItems.groupBy { it.catName }) {
            val c = card()
            val color = Reminders.safeColor(list.first().catColor)
            c.addView(text("● $cat", 15f, color, bold = true))
            for (item in list) c.addView(itemRow(item, s.logs[item.id]))
            root.addView(c)
        }

        renderUsage(s)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        actions.addView(button("새로고침") { load() })
        if (BuildConfig.DASHBOARD_URL.isNotBlank())
            actions.addView(button("대시보드") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL))) })
        actions.addView(button("설정") { settingsDialog() })
        root.addView(actions)

        val last = Prefs.long("last_sync")
        val next = Prefs.str("next_alarm")?.replace("T", " ")?.take(16)
        root.addView(text(
            "${Prefs.deviceName}" + (if (next != null) " · 다음 알림 $next" else "") +
                (if (last > 0) " · 백그라운드 동기화 ${java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(java.util.Date(last))}" else ""),
            12f, C_SUB
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
    }

    private fun itemRow(item: Item, log: Log?): View {
        val day = today()
        if (!item.isNumber) {
            return CheckBox(this).apply {
                text = item.name; textSize = 16f; setTextColor(C_TEXT)
                isChecked = item.isDone(log)
                setPadding(dp(4), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked ->
                    bg {
                        Repo.setDone(item.id, day, checked)
                        if (checked) Reminders.cancel(this@MainActivity, item.id)
                    }
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(6))
        }
        val tgt = item.target?.let { "/${fmtNum(it)}" } ?: ""
        val doneMark = if (item.isDone(log)) "✅ " else ""
        row.addView(text("$doneMark${item.name}", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(text("${fmtNum(log?.value)}$tgt${item.unit}", 15f, C_SUB, bold = true).apply { setPadding(0, 0, dp(8), 0) })
        row.addView(button("+") { numberDialog(item, log) }.apply { minWidth = 0; minimumWidth = dp(48) })
        return row
    }

    private fun numberDialog(item: Item, log: Log?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "숫자 (${item.unit.ifBlank { "값" }})"
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("현재 ${fmtNum(log?.value)}${item.unit}")
            .setView(input)
            .setPositiveButton("더하기") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { v -> bg { Repo.addValue(item.id, today(), v); runOnUiThread { load() } } }
            }
            .setNeutralButton("이 값으로 설정") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { v -> bg { Repo.setValue(item.id, today(), v, item.target); runOnUiThread { load() } } }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renderUsage(s: Snapshot) {
        val c = card()
        c.addView(text("📱 앱 사용량 (오늘)", 15f, bold = true))
        if (!Usage.hasPermission(this)) {
            c.addView(text("사용량 접근 권한이 필요해요", 14f, C_SUB))
            root.addView(c); return
        }
        if (s.limits.isNotEmpty()) {
            c.addView(text("두 폰 합산 / 한도", 12f, C_SUB).apply { setPadding(0, dp(6), 0, 0) })
            for (l in s.limits) {
                val used = s.totals[l.pkg] ?: 0
                val over = used >= l.minutes
                c.addView(text("${l.label}  ${used}분 / ${l.minutes}분" + if (over) "  ⛔" else "", 15f, if (over) C_WARN else C_TEXT))
                c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = l.minutes; progress = minOf(used, l.minutes)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(8))
                })
            }
        }
        if (s.mine.isNotEmpty()) {
            c.addView(text("이 폰 Top 5", 12f, C_SUB).apply { setPadding(0, dp(10), 0, 0) })
            for (e in s.mine.take(5)) c.addView(text("${e.label} · ${e.minutes / 60}시간 ${e.minutes % 60}분", 14f))
        }
        root.addView(c)
    }

    private fun renderPermissions() {
        val missing = ArrayList<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing += "알림 허용" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        if (!Usage.hasPermission(this))
            missing += "사용량 접근 허용 (DailyOS 찾아서 켜기)" to { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
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

    private fun settingsDialog() {
        val name = EditText(this).apply { setText(Prefs.deviceName) }
        AlertDialog.Builder(this)
            .setTitle("이 폰 이름")
            .setView(name)
            .setPositiveButton("저장") { _, _ ->
                Prefs.deviceName = name.text.toString().trim().ifBlank { Prefs.deviceName }
                bg { Repo.registerDevice() }
                load()
            }
            .setNeutralButton("로그아웃") { _, _ ->
                Prefs.clearSession(); Reminders.scheduleNext(this); renderLogin()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
