package com.dan.dailyos

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ⚠️ 계산 규칙은 web/metrics.js 와 동일하게 유지

data class Weights(val jp: Double, val ex: Double, val sleep: Double, val phone: Double, val rec: Double)

data class AppSettings(
    val ankiDecks: List<String>,
    val ankiTarget: String,
    val reviewWarn: Int,
    val sleepGoal: Int,
    val walkGoal: Int,
    val pushupGoal: Int,
    val w: Weights
)

object SettingsStore {
    private val DEFAULT = JSONObject(
        """{"anki_decks":["N1 4월교재_단어장","N1_4월교재_문제파일"],"anki_target":"2026-10-31",
           "anki_review_warn":250,"sleep_goal_min":420,"walk_goal_min":60,"pushup_goal":20,
           "weights":{"jp":35,"ex":20,"sleep":30,"phone":10,"rec":5}}"""
    )

    fun parse(data: JSONObject?): AppSettings {
        val d = data ?: JSONObject()
        fun <T> pick(k: String, f: (JSONObject, String) -> T): T = if (d.has(k) && !d.isNull(k)) f(d, k) else f(DEFAULT, k)
        val decksArr = pick("anki_decks") { o, k -> o.getJSONArray(k) }
        val w = d.optJSONObject("weights") ?: JSONObject()
        val dw = DEFAULT.getJSONObject("weights")
        fun wv(k: String) = if (w.has(k)) w.optDouble(k, dw.getDouble(k)) else dw.getDouble(k)
        return AppSettings(
            ankiDecks = (0 until decksArr.length()).map { decksArr.getString(it) },
            ankiTarget = pick("anki_target") { o, k -> o.getString(k) },
            reviewWarn = pick("anki_review_warn") { o, k -> o.getInt(k) },
            sleepGoal = pick("sleep_goal_min") { o, k -> o.getInt(k) },
            walkGoal = pick("walk_goal_min") { o, k -> o.getInt(k) },
            pushupGoal = pick("pushup_goal") { o, k -> o.getInt(k) },
            w = Weights(wv("jp"), wv("ex"), wv("sleep"), wv("phone"), wv("rec"))
        )
    }

    fun cachedRaw(): JSONObject = try { JSONObject(Prefs.str("settings_cache") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun cached(): AppSettings = parse(cachedRaw())

    fun fetchRaw(): JSONObject {
        val arr = Supa.select("user_settings", "select=data")
        val data = if (arr.length() > 0) arr.getJSONObject(0).optJSONObject("data") ?: JSONObject() else JSONObject()
        Prefs.put("settings_cache", data.toString())
        return data
    }

    fun fetch(): AppSettings = parse(fetchRaw())

    /** 최신 설정을 받아 일부 키만 바꿔 저장 (웹에서 바꾼 다른 키는 유지) */
    fun update(mutate: (JSONObject) -> Unit) {
        val data = fetchRaw()
        mutate(data)
        Supa.upsert(
            "user_settings",
            JSONObject().put("user_id", Supa.userId).put("data", data)
                .put("updated_at", java.time.OffsetDateTime.now().toString()),
            "user_id"
        )
        Prefs.put("settings_cache", data.toString())
    }
}

data class AnkiDay(
    val newStudied: Int?, val reviewed: Int?, val remainingNew: Int?, val total: Int?,
    val mature: Int?, val dueLeft: Int?, val requiredNew: Int?
)

data class HealthDay(
    val walkMin: Double?, val walkKm: Double?, val pushups: Int?,
    val painAm: Int?, val painPm: Int?, val noteAm: String?, val notePm: String?
)

data class SleepResult(val start: Int, val end: Int?, val minutes: Int?, val status: String)

data class Score(val total: Int, val jp: Double, val ex: Double, val sleep: Double, val phone: Double, val rec: Double)

data class DayData(
    val day: String, val anki: AnkiDay?, val health: HealthDay?, val sleep: SleepResult?,
    val phoneUsed: Int, val phoneLimit: Int, val score: Score?
)

object Metrics {
    private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else getInt(k)
    private fun JSONObject.dblOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else getDouble(k)
    private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)

    fun fmtClock(m: Int?): String = if (m == null) "–" else "%02d:%02d".format(m / 60, m % 60)
    fun fmtHours(m: Int?): String = if (m == null) "–" else "${m / 60}시간 ${m % 60}분"

    /** 수면: 07:00 이전 시작한 사용 중 가장 늦은 끝 → 그 뒤 처음 시작한 사용 */
    fun computeSleep(sessions: List<IntArray>, nowMin: Int?): SleepResult? {
        if (sessions.isEmpty()) {
            return if (nowMin != null && nowMin < 720) SleepResult(0, null, null, "pending") else null
        }
        val s = sessions.map { intArrayOf(max(0, it[0]), min(720, it[1])) }.filter { it[1] >= it[0] }.sortedBy { it[0] }
        val pre = s.filter { it[0] < 420 }
        val start = if (pre.isEmpty()) 0 else pre.maxOf { it[1] }
        val post = s.filter { it[0] >= 420 && it[0] > start }
        if (post.isEmpty()) {
            return if (nowMin != null && nowMin < 720) SleepResult(start, null, null, "pending")
            else SleepResult(start, null, null, "unknown")
        }
        val end = post[0][0]
        return SleepResult(start, end, end - start, "ok")
    }

    fun requiredNew(remainingNew: Int, studiedToday: Int, day: LocalDate, target: String): Int {
        val start = remainingNew + studiedToday
        val t = try { LocalDate.parse(target) } catch (e: Exception) { return 0 }
        val days = ChronoUnit.DAYS.between(day, t) + 1
        if (days <= 0) return start
        return ceil(start.toDouble() / days).toInt()
    }

    fun score(s: AppSettings, anki: AnkiDay?, health: HealthDay?, sleep: SleepResult?, used: Int, limit: Int): Score {
        val jp = if (anki != null) {
            val req = anki.requiredNew ?: 0
            val newPart = if (req <= 0) 1.0 else min((anki.newStudied ?: 0).toDouble() / req, 1.0)
            val rev = anki.reviewed ?: 0
            val left = anki.dueLeft
            val revPart = when {
                left == null -> if (rev > 0) 1.0 else 0.0
                rev + left == 0 -> 1.0
                else -> rev.toDouble() / (rev + left)
            }
            0.6 * newPart + 0.4 * revPart
        } else 0.0
        val walk = health?.walkMin ?: 0.0
        val push = (health?.pushups ?: 0).toDouble()
        val ex = 0.6 * min(walk / s.walkGoal, 1.0) + 0.4 * min(push / s.pushupGoal, 1.0)
        val sl = if (sleep?.minutes != null) min(sleep.minutes.toDouble() / s.sleepGoal, 1.0) else 0.0
        val ph = if (limit <= 0) 1.0 else if (used <= limit) 1.0 else max(0.0, 1.0 - (used - limit).toDouble() / limit)
        val rec = (if (health?.painAm != null) 0.5 else 0.0) + (if (health?.painPm != null) 0.5 else 0.0)
        val total = s.w.jp * jp + s.w.ex * ex + s.w.sleep * sl + s.w.phone * ph + s.w.rec * rec
        return Score(total.roundToInt(), jp, ex, sl, ph, rec)
    }

    fun parseAnki(o: JSONObject) = AnkiDay(
        o.intOrNull("new_studied"), o.intOrNull("reviewed"), o.intOrNull("remaining_new"),
        o.intOrNull("total_cards"), o.intOrNull("mature"), o.intOrNull("due_left"), o.intOrNull("required_new")
    )

    fun parseHealth(o: JSONObject) = HealthDay(
        o.dblOrNull("walk_min"), o.dblOrNull("walk_km"), o.intOrNull("pushups"),
        o.intOrNull("pain_am"), o.intOrNull("pain_pm"), o.strOrNull("note_am"), o.strOrNull("note_pm")
    )

    fun fetchAnki(day: String): AnkiDay? {
        val a = Supa.select("anki_daily", "select=*&day=eq.$day")
        return if (a.length() > 0) parseAnki(a.getJSONObject(0)) else null
    }

    fun fetchHealth(day: String): HealthDay? {
        val a = Supa.select("health_log", "select=*&day=eq.$day")
        return if (a.length() > 0) parseHealth(a.getJSONObject(0)) else null
    }

    /** 기간 전체를 한 번에 불러와 날짜별로 묶고 점수까지 계산 */
    fun loadRange(from: LocalDate, to: LocalDate, s: AppSettings, limits: List<Limit>): Map<String, DayData> {
        val f = from.toString(); val t = to.toString()
        val anki = HashMap<String, AnkiDay>()
        Supa.select("anki_daily", "select=*&day=gte.$f&day=lte.$t").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { anki[it.getString("day")] = parseAnki(it) }
        }
        val health = HashMap<String, HealthDay>()
        Supa.select("health_log", "select=*&day=gte.$f&day=lte.$t").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { health[it.getString("day")] = parseHealth(it) }
        }
        val nights = HashMap<String, MutableList<IntArray>>()
        Supa.select("night_activity", "select=day,sessions&day=gte.$f&day=lte.$t").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val list = nights.getOrPut(o.getString("day")) { ArrayList() }
                val ss = o.optJSONArray("sessions") ?: JSONArray()
                for (j in 0 until ss.length()) ss.getJSONArray(j).let { list += intArrayOf(it.getInt(0), it.getInt(1)) }
            }
        }
        val used = HashMap<String, Int>()
        val limitTotal = limits.sumOf { it.minutes }
        if (limits.isNotEmpty()) {
            val pk = limits.joinToString(",") { it.pkg }
            Supa.select("app_usage", "select=day,minutes&day=gte.$f&day=lte.$t&package=in.(${Supa.enc(pk)})").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { used.merge(it.getString("day"), it.optInt("minutes"), Int::plus) }
            }
        }
        val out = LinkedHashMap<String, DayData>()
        val logicalToday = LocalDate.parse(today())
        val calToday = LocalDate.now()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        var d = from
        while (!d.isAfter(to)) {
            val k = d.toString()
            val sleep = if (d.isAfter(calToday)) null
            else computeSleep(nights[k] ?: emptyList(), if (d == calToday) nowMin else null)
            val sc = if (d.isAfter(logicalToday)) null
            else score(s, anki[k], health[k], sleep, used[k] ?: 0, limitTotal)
            out[k] = DayData(k, anki[k], health[k], sleep, used[k] ?: 0, limitTotal, sc)
            d = d.plusDays(1)
        }
        return out
    }
}
