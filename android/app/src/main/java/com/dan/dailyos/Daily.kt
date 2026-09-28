package com.dan.dailyos

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * 고정 알림
 *  - 09:00 아침 통증 기록
 *  - 22:00 저녁 종합 (Anki 페이스 · 걷기 · 푸시업 · 저녁 통증)
 */
object Daily {
    const val A_FIRE = "com.dan.dailyos.DAILY_FIRE"
    const val A_WALK = "com.dan.dailyos.DAILY_WALK"
    const val A_PUSH = "com.dan.dailyos.DAILY_PUSH"
    const val A_PAIN = "com.dan.dailyos.DAILY_PAIN"
    const val KEY = "daily_input"
    const val TAG_AM = "daily_am"
    const val TAG_PM = "daily_pm"
    private const val ID = 21
    private val TIMES = listOf(LocalTime.of(9, 0) to "am", LocalTime.of(22, 0) to "pm")

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        var best: Pair<LocalDateTime, String>? = null
        for (d in 0..1) for ((t, kind) in TIMES) {
            val at = LocalDate.now().plusDays(d.toLong()).atTime(t)
            if (at.isAfter(now) && (best == null || at.isBefore(best.first))) best = at to kind
        }
        val (at, kind) = best ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 2000,
            Intent(ctx, DailyReceiver::class.java).setAction(A_FIRE)
                .putExtra("kind", kind).putExtra("day", at.toLocalDate().toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, ms, 5 * 60_000L, pi)
    }

    private fun pi(ctx: Context, action: String, kind: String, day: String): PendingIntent {
        val i = Intent(ctx, DailyReceiver::class.java).setAction(action).putExtra("kind", kind).putExtra("day", day)
        return PendingIntent.getBroadcast(
            ctx, (action + kind).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun inputAction(ctx: Context, label: String, hint: String, action: String, kind: String, day: String): Notification.Action {
        val ri = RemoteInput.Builder(KEY).setLabel(hint).build()
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), label, pi(ctx, action, kind, day))
            .addRemoteInput(ri).build()
    }

    private fun builder(ctx: Context, title: String, text: String, day: String): Notification.Builder =
        Notification.Builder(ctx, Reminders.CH_ROUTINE)
            .addExtras(android.os.Bundle().apply { putString("dailyos_day", day) })
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )

    /** 저녁 알림에 넣을 "아직 안 한 것" 목록 */
    data class Evening(val lines: List<String>, val needWalk: Boolean, val needPush: Boolean, val needPain: Boolean)

    fun evening(s: AppSettings, anki: AnkiDay?, h: HealthDay?): Evening {
        val lines = ArrayList<String>()
        if (s.ankiDecks.isNotEmpty()) {
            if (anki == null) lines += "📘 오늘 Anki 기록이 없어요"
            else {
                val req = anki.requiredNew ?: 0
                val nw = anki.newStudied ?: 0
                if (nw < req) lines += "📘 새 카드 $nw / $req"
                if ((anki.dueLeft ?: 0) > 0) lines += "📘 남은 복습 ${anki.dueLeft}장"
                if ((anki.reviewed ?: 0) >= s.reviewWarn) lines += "⚠️ 오늘 복습 ${anki.reviewed}장 — 새 카드 수를 줄이는 걸 고려하세요"
            }
        }
        val walk = h?.walkMin ?: 0.0
        val push = h?.pushups ?: 0
        val needWalk = walk < s.walkGoal
        val needPush = push < s.pushupGoal
        val needPain = h?.painPm == null
        if (needWalk) lines += "🚶 걷기 ${fmtNum(walk)} / ${s.walkGoal}분"
        if (needPush) lines += "💪 푸시업 $push / ${s.pushupGoal}개"
        if (needPain) lines += "🩺 저녁 통증 기록 (0~10)"
        return Evening(lines, needWalk, needPush, needPain)
    }

    /** 알림을 (다시) 그림. 할 일이 없으면 지움. fresh=true면 Anki를 새로 수집 */
    fun post(ctx: Context, kind: String, day: String, fresh: Boolean = false, alert: Boolean = true) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val s = try { SettingsStore.fetch() } catch (e: Exception) { SettingsStore.cached() }
        val h = try { Metrics.fetchHealth(day) } catch (e: Exception) { null }
        if (kind == "am") {
            if (h?.painAm != null) { nm.cancel(TAG_AM, ID); return }
            if (!alert && nm.activeNotifications.none { it.tag == TAG_AM }) return
            val b = builder(ctx, "🩺 아침 허리 상태", "통증 0~10과 짧은 메모를 남겨주세요 (예: 3 아침에 뻐근)", day)
                .addAction(inputAction(ctx, "통증 기록", "점수 메모 (예: 3 뻐근)", A_PAIN, "am", day))
            nm.notify(TAG_AM, ID, b.build())
            return
        }
        if (fresh) try { Anki.upload(ctx, s) } catch (e: Exception) { }
        val anki = try { Metrics.fetchAnki(day) } catch (e: Exception) { null }
        val ev = evening(s, anki, h)
        if (ev.lines.isEmpty()) { nm.cancel(TAG_PM, ID); return }
        if (!alert && nm.activeNotifications.none { it.tag == TAG_PM }) return
        val b = builder(ctx, "🌙 오늘 마무리", ev.lines.joinToString("\n"), day)
        if (ev.needWalk) b.addAction(inputAction(ctx, "걷기", "분 km (예: 60 4.5)", A_WALK, "pm", day))
        if (ev.needPush) b.addAction(inputAction(ctx, "푸시업", "개수 (예: 20)", A_PUSH, "pm", day))
        if (ev.needPain) b.addAction(inputAction(ctx, "통증", "점수 메모 (예: 2 괜찮음)", A_PAIN, "pm", day))
        nm.notify(TAG_PM, ID, b.build())
    }

    /** 동기화 때: 다른 기기에서 입력했으면 알림 내용 갱신/제거 */
    fun refreshActive(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (sbn in nm.activeNotifications) {
            if (sbn.id != ID) continue
            val day = sbn.notification.extras.getString("dailyos_day") ?: today()
            when (sbn.tag) {
                TAG_AM -> post(ctx, "am", day, alert = false)
                TAG_PM -> post(ctx, "pm", day, alert = false)
            }
        }
    }

    fun nums(text: String): List<Double> =
        Regex("\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDouble() }.toList()
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        val kind = intent.getStringExtra("kind") ?: "pm"
        val day = intent.getStringExtra("day") ?: today()
        val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Daily.KEY)?.toString()?.trim() ?: ""
        val pr = goAsync()
        thread {
            try {
                when (intent.action) {
                    Daily.A_FIRE -> {
                        if (Prefs.loggedIn) Daily.post(ctx, kind, day, fresh = true)
                        Daily.scheduleNext(ctx)
                    }
                    Daily.A_WALK -> {
                        val n = Daily.nums(input)
                        if (n.isNotEmpty()) Repo.addHealth(day, walkMin = n[0], walkKm = n.getOrElse(1) { 0.0 })
                        Daily.post(ctx, kind, day, alert = false)
                    }
                    Daily.A_PUSH -> {
                        val n = Daily.nums(input)
                        if (n.isNotEmpty()) Repo.addHealth(day, pushups = n[0].toInt())
                        Daily.post(ctx, kind, day, alert = false)
                    }
                    Daily.A_PAIN -> {
                        val m = Regex("\\d+").find(input)
                        val score = m?.value?.toIntOrNull()
                        if (score != null && score in 0..10) {
                            val note = input.removeRange(m.range).trim()
                            Repo.setPain(day, kind, score, note)
                        }
                        Daily.post(ctx, kind, day, alert = false)
                    }
                }
            } catch (e: Exception) {
                Reminders.info(ctx, "daily_err", "기록 실패", e.message ?: "오류")
            } finally {
                pr.finish()
            }
        }
    }
}
