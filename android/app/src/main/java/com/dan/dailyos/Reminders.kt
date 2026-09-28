package com.dan.dailyos

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.concurrent.thread

object Reminders {
    const val CH_ROUTINE = "routine"
    const val CH_LIMIT = "limit"
    const val CH_INFO = "info"
    const val NOTIF_ID = 7
    const val KEY_VALUE = "value"

    const val A_FIRE = "com.dan.dailyos.FIRE"
    const val A_SNOOZE_FIRE = "com.dan.dailyos.SNOOZE_FIRE"
    const val A_DONE = "com.dan.dailyos.DONE"
    const val A_ADD = "com.dan.dailyos.ADD"
    const val A_SNOOZE = "com.dan.dailyos.SNOOZE"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ROUTINE, "루틴 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "정해진 시간의 루틴 체크 알림" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIMIT, "사용량 경고", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "앱 사용 한도 초과 경고" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "기록 확인", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun parseTime(s: String): LocalTime? = try {
        val p = s.trim().split(":"); LocalTime.of(p[0].toInt(), p[1].toInt())
    } catch (e: Exception) { null }

    /** 다음 알림 시각 하나만 AlarmManager에 등록 (울리면 그 다음 것을 다시 등록) */
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1000, Intent(ctx, AlarmReceiver::class.java).setAction(A_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!Prefs.loggedIn) { am.cancel(pi); return }
        val now = LocalDateTime.now()
        var best: LocalDateTime? = null
        for (item in Repo.cachedItems()) for (ts in item.times) {
            val t = parseTime(ts) ?: continue
            for (d in 0..7) {
                val date = LocalDate.now().plusDays(d.toLong())
                val at = date.atTime(t)
                if (at.isAfter(now) && item.scheduledOn(date)) {
                    if (best == null || at.isBefore(best)) best = at
                    break
                }
            }
        }
        if (best == null) { am.cancel(pi); return }
        Prefs.put("next_alarm", best.toString())
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(A_FIRE)
            .putExtra("time", "%02d:%02d".format(best.hour, best.minute))
            .putExtra("day", best.toLocalDate().toString())
        val pi2 = PendingIntent.getBroadcast(
            ctx, 1000, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(am, best.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi2)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pi)
    }

    fun snooze(ctx: Context, itemId: String, day: String, minutes: Int = 30) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(A_SNOOZE_FIRE)
            .putExtra("item", itemId).putExtra("day", day)
        val pi = PendingIntent.getBroadcast(
            ctx, itemId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(am, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    /** 알림 시각 도래: 해당 시각 항목 중 아직 안 한 것만 알림 */
    fun fire(ctx: Context, time: String?, day: String, onlyItem: String? = null) {
        var items = Repo.cachedItems()
        try { items = Repo.fetchItems() } catch (e: Exception) { }
        val date = LocalDate.parse(day)
        val due = items.filter {
            if (onlyItem != null) it.id == onlyItem
            else it.scheduledOn(date) && it.times.any { t -> parseTime(t)?.let { p -> "%02d:%02d".format(p.hour, p.minute) } == time }
        }
        if (due.isEmpty()) return
        val logs: Map<String, Log> = try { Repo.fetchLogs(day) } catch (e: Exception) { emptyMap() }
        for (item in due) if (!item.isDone(logs[item.id])) notifyItem(ctx, item, logs[item.id], day)
    }

    private fun actionPi(ctx: Context, action: String, item: Item, day: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(ctx, ActionReceiver::class.java).setAction(action)
            .putExtra("item", item.id).putExtra("day", day).putExtra("name", item.name)
            .putExtra("unit", item.unit).putExtra("target", item.target ?: -1.0)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, (action + item.id).hashCode(), intent, flags)
    }

    fun notifyItem(ctx: Context, item: Item, log: Log?, day: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat)
        val b = Notification.Builder(ctx, CH_ROUTINE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(safeColor(item.catColor))
            .setSubText(item.catName)
            .setContentTitle(item.name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setGroup("routine")

        if (item.isNumber) {
            val cur = fmtNum(log?.value)
            val tgt = item.target?.let { "/${fmtNum(it)}" } ?: ""
            b.setContentText("오늘 $cur$tgt${item.unit} — 추가한 만큼 입력하세요")
            val ri = RemoteInput.Builder(KEY_VALUE).setLabel("추가할 수 (${item.unit.ifBlank { "숫자" }})").build()
            b.addAction(
                Notification.Action.Builder(icon, "기록하기", actionPi(ctx, A_ADD, item, day, mutable = true))
                    .addRemoteInput(ri).build()
            )
        } else {
            b.setContentText("완료했으면 체크하세요")
            b.addAction(Notification.Action.Builder(icon, "완료 ✓", actionPi(ctx, A_DONE, item, day)).build())
        }
        b.addAction(Notification.Action.Builder(icon, "30분 뒤", actionPi(ctx, A_SNOOZE, item, day)).build())
        nm.notify(item.id, NOTIF_ID, b.build())
    }

    fun cancel(ctx: Context, itemId: String) =
        ctx.getSystemService(NotificationManager::class.java).cancel(itemId, NOTIF_ID)

    /** 다른 폰(또는 PC)에서 이미 완료한 항목의 알림 제거 */
    fun dismissCompleted(ctx: Context, items: List<Item>, logs: Map<String, Log>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val byId = items.associateBy { it.id }
        for (sbn in nm.activeNotifications) {
            if (sbn.id != NOTIF_ID) continue
            val tag = sbn.tag ?: continue
            val item = byId[tag]
            if (item == null || item.isDone(logs[tag])) nm.cancel(tag, NOTIF_ID)
        }
    }

    fun info(ctx: Context, tag: String, title: String, text: String, channel: String = CH_INFO, timeoutMs: Long = 6000) {
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        ctx.getSystemService(NotificationManager::class.java).notify(tag, 99, b.build())
    }

    fun safeColor(hex: String): Int = try { Color.parseColor(hex) } catch (e: Exception) { Color.parseColor("#3B5BDB") }
}

/** 알림 시각 도래 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        val pr = goAsync()
        thread {
            try {
                val day = intent.getStringExtra("day") ?: today()
                when (intent.action) {
                    Reminders.A_FIRE -> Reminders.fire(ctx, intent.getStringExtra("time"), day)
                    Reminders.A_SNOOZE_FIRE -> Reminders.fire(ctx, null, day, onlyItem = intent.getStringExtra("item"))
                }
            } catch (e: Exception) {
            } finally {
                if (intent.action == Reminders.A_FIRE) Reminders.scheduleNext(ctx)
                pr.finish()
            }
        }
    }
}

/** 알림의 버튼 (완료 / 숫자 기록 / 30분 뒤) */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        val itemId = intent.getStringExtra("item") ?: return
        val day = intent.getStringExtra("day") ?: today()
        val name = intent.getStringExtra("name") ?: ""
        val unit = intent.getStringExtra("unit") ?: ""
        val pr = goAsync()
        thread {
            try {
                when (intent.action) {
                    Reminders.A_DONE -> {
                        Reminders.cancel(ctx, itemId)
                        val ok = Repo.setDone(itemId, day, true)
                        if (!ok) Reminders.info(ctx, itemId, "오프라인 저장", "$name — 연결되면 자동으로 동기화돼요")
                    }
                    Reminders.A_ADD -> {
                        val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Reminders.KEY_VALUE)?.toString()
                        val v = raw?.trim()?.replace(",", "")?.toDoubleOrNull()
                        Reminders.cancel(ctx, itemId)
                        if (v == null) {
                            Reminders.info(ctx, itemId, "숫자를 입력해주세요", "$name — 앱에서 다시 기록할 수 있어요")
                        } else {
                            val ok = Repo.addValue(itemId, day, v)
                            val total = try { Repo.fetchLogs(day)[itemId]?.value } catch (e: Exception) { null }
                            val tgt = intent.getDoubleExtra("target", -1.0)
                            val msg = if (total != null)
                                "오늘 ${fmtNum(total)}${if (tgt >= 0) "/" + fmtNum(tgt) else ""}$unit" +
                                    (if (tgt >= 0 && total >= tgt) " — 목표 달성 🎉" else "")
                            else if (ok) "+${fmtNum(v)}$unit 기록됨" else "+${fmtNum(v)}$unit 오프라인 저장"
                            Reminders.info(ctx, itemId, name, msg)
                        }
                    }
                    Reminders.A_SNOOZE -> {
                        Reminders.cancel(ctx, itemId)
                        Reminders.snooze(ctx, itemId, day)
                    }
                }
            } catch (e: Exception) {
                Reminders.info(ctx, itemId, "기록 실패", e.message ?: "오류")
            } finally {
                pr.finish()
            }
        }
    }
}

/** 재부팅 / 앱 업데이트 후 알람·동기화 재등록 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        Reminders.scheduleNext(ctx)
        Daily.scheduleNext(ctx)
        SyncJob.schedule(ctx)
    }
}
