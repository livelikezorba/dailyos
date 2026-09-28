package com.dan.dailyos

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.time.LocalDate
import kotlin.concurrent.thread

/** 15분마다: 대기열 전송 → 루틴 갱신 → 알림 정리 → 사용량 업로드 → 한도 경고 */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Prefs.init(this)
        thread {
            try { run(this) } catch (e: Exception) { }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 42

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        @Synchronized
        fun run(ctx: Context) {
            if (!Prefs.loggedIn || !Supa.configured) return
            Repo.flushPending()
            try { Repo.registerDevice() } catch (e: Exception) { }

            val items = Repo.fetchItems()
            Reminders.scheduleNext(ctx)
            val day = today()
            Reminders.dismissCompleted(ctx, items, Repo.fetchLogs(day))

            if (Usage.hasPermission(ctx)) {
                val t = LocalDate.now()
                // 어제 값도 한 번 더 올려서 자정 직전 사용분까지 확정
                try { Repo.uploadUsage(t.minusDays(1).toString(), Usage.collect(ctx, t.minusDays(1))) } catch (e: Exception) { }
                Repo.uploadUsage(day, Usage.collect(ctx, t))
                checkLimits(ctx, day)
            }
            Prefs.putLong("last_sync", System.currentTimeMillis())
        }

        private fun checkLimits(ctx: Context, day: String) {
            val limits = Repo.fetchLimits()
            if (limits.isEmpty()) return
            val totals = Repo.usageTotals(day)
            for (l in limits) {
                val used = totals[l.pkg] ?: 0
                val level = when {
                    used >= l.minutes -> 100
                    used >= l.minutes * 0.8 -> 80
                    else -> 0
                }
                if (level == 0) continue
                val key = "warned_${day}_${l.pkg}_$level"
                if (Prefs.bool(key)) continue
                Prefs.putBool(key, true)
                val title = if (level == 100) "⛔ ${l.label} 한도 초과" else "⚠️ ${l.label} 한도 80%"
                Reminders.info(
                    ctx, "limit_${l.pkg}", title,
                    "오늘 두 폰 합계 ${used}분 / 한도 ${l.minutes}분", Reminders.CH_LIMIT, timeoutMs = 0
                )
            }
        }
    }
}
