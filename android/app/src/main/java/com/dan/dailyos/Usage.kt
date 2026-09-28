package com.dan.dailyos

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** 폰의 앱별 포그라운드 사용 시간 계산 (UsageEvents 기반 — 디지털 웰빙과 같은 방식) */
object Usage {
    data class Entry(val pkg: String, val label: String, val minutes: Int)

    // UsageEvents.Event 상수 (minSdk 26 호환을 위해 숫자로 사용)
    private const val RESUMED = 1          // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
    private const val PAUSED = 2           // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
    private const val SCREEN_OFF = 16      // SCREEN_NON_INTERACTIVE
    private const val STOPPED = 23         // ACTIVITY_STOPPED
    private const val SHUTDOWN = 26        // DEVICE_SHUTDOWN

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun collect(ctx: Context, day: LocalDate): List<Entry> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(System.currentTimeMillis(), dayEnd)
        if (end <= start) return emptyList()

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val seen = HashSet<String>()
        val total = HashMap<String, Long>()

        fun close(pkg: String, t: Long) {
            val s = open.remove(pkg)
            if (s != null) total.merge(pkg, t - s, Long::plus)
            else if (!seen.contains(pkg)) total.merge(pkg, t - start, Long::plus) // 자정 이전부터 켜져 있던 앱
            seen.add(pkg)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                RESUMED -> { if (!open.containsKey(pkg)) open[pkg] = t; seen.add(pkg) }
                PAUSED, STOPPED -> if (open.containsKey(pkg) || !seen.contains(pkg)) close(pkg, t)
                SCREEN_OFF, SHUTDOWN -> open.keys.toList().forEach { close(it, t) }
            }
        }
        open.keys.toList().forEach { close(it, end) }

        val pm = ctx.packageManager
        val self = ctx.packageName
        return total.filter { it.key != self && it.value >= 60_000 }
            .map { (pkg, ms) -> Entry(pkg, label(pm, pkg), (ms / 60_000).toInt()) }
            .sortedByDescending { it.minutes }
    }

    /**
     * 수면 계산용: day 0시~12시 사이 "앱이 화면 앞에 있던" 구간 목록 [시작분, 끝분] (0시 기준)
     * 잠금화면만 켜진 경우(알림 등)는 앱이 포그라운드로 오지 않으므로 사용으로 치지 않음
     */
    fun nightSessions(ctx: Context, day: LocalDate): List<IntArray> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = start + 12 * 3600_000L
        val end = minOf(System.currentTimeMillis(), windowEnd)
        if (end <= start) return emptyList()

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        val open = HashSet<String>()
        val seen = HashSet<String>()
        var activeStart = -1L
        val raw = ArrayList<LongArray>()

        fun closeAll(t: Long) {
            if (open.isNotEmpty() && activeStart >= 0) raw += longArrayOf(activeStart, t)
            open.clear(); activeStart = -1L
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                RESUMED -> {
                    if (open.isEmpty()) activeStart = t
                    open.add(pkg); seen.add(pkg)
                }
                PAUSED, STOPPED -> {
                    if (open.remove(pkg)) {
                        if (open.isEmpty()) { raw += longArrayOf(activeStart, t); activeStart = -1L }
                    } else if (!seen.contains(pkg) && raw.isEmpty() && open.isEmpty()) {
                        raw += longArrayOf(start, t) // 0시 이전부터 쓰던 앱
                    }
                    seen.add(pkg)
                }
                SCREEN_OFF, SHUTDOWN -> closeAll(t)
            }
        }
        closeAll(end)

        // 분 단위로 바꾸고 1분 이내 간격은 이어 붙임
        val mins = raw.map { intArrayOf(((it[0] - start) / 60_000).toInt(), ((it[1] - start + 59_999) / 60_000).toInt()) }
            .sortedBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (m in mins) {
            val last = merged.lastOrNull()
            if (last != null && m[0] <= last[1] + 1) last[1] = maxOf(last[1], m[1]) else merged += m.copyOf()
        }
        return merged
    }

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}
