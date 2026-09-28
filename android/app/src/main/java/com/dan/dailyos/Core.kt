package com.dan.dailyos

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Reminders.createChannels(this)
    }
}

/** 로컬 설정/세션 저장소 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("dailyos", Context.MODE_PRIVATE)
    }

    fun str(key: String): String? = sp.getString(key, null)
    fun put(key: String, v: String?) { sp.edit().putString(key, v).commit() }
    fun long(key: String): Long = sp.getLong(key, 0L)
    fun putLong(key: String, v: Long) { sp.edit().putLong(key, v).commit() }
    fun bool(key: String): Boolean = sp.getBoolean(key, false)
    fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).commit() }

    val deviceId: String
        get() = str("device_id") ?: UUID.randomUUID().toString().also { put("device_id", it) }

    var deviceName: String
        get() = str("device_name") ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)
        set(v) = put("device_name", v)

    val loggedIn: Boolean get() = str("refresh_token") != null

    fun clearSession() {
        sp.edit().remove("access_token").remove("refresh_token").remove("expires_at")
            .remove("user_id").remove("items_cache").remove("pending").commit()
    }
}

fun today(): String = LocalDate.now().toString()

class HttpError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")
class NotLoggedIn : IOException("로그인이 필요합니다")

/** Supabase REST / Auth 최소 클라이언트 (외부 라이브러리 없이 HttpURLConnection 사용) */
object Supa {
    private val base get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key get() = BuildConfig.SUPABASE_KEY

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()
    val userId: String get() = Prefs.str("user_id") ?: throw NotLoggedIn()

    private fun http(
        method: String, path: String, body: String? = null,
        bearer: String? = null, headers: Map<String, String> = emptyMap()
    ): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer ${bearer ?: key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ── Auth ─────────────────────────────────────
    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email).put("password", password).toString()
        saveSession(JSONObject(http("POST", "/auth/v1/token?grant_type=password", body)))
    }

    private fun saveSession(r: JSONObject) {
        Prefs.put("access_token", r.getString("access_token"))
        Prefs.put("refresh_token", r.getString("refresh_token"))
        Prefs.putLong("expires_at", System.currentTimeMillis() / 1000 + r.optLong("expires_in", 3600))
        Prefs.put("user_id", r.getJSONObject("user").getString("id"))
    }

    @Synchronized
    fun token(): String {
        val refresh = Prefs.str("refresh_token") ?: throw NotLoggedIn()
        val access = Prefs.str("access_token")
        if (access != null && System.currentTimeMillis() / 1000 < Prefs.long("expires_at") - 120) return access
        try {
            val body = JSONObject().put("refresh_token", refresh).toString()
            saveSession(JSONObject(http("POST", "/auth/v1/token?grant_type=refresh_token", body)))
        } catch (e: HttpError) {
            if (e.code in 400..401) { Prefs.clearSession(); throw NotLoggedIn() }
            throw e
        }
        return Prefs.str("access_token")!!
    }

    // ── REST ─────────────────────────────────────
    fun select(table: String, query: String): JSONArray =
        JSONArray(http("GET", "/rest/v1/$table?$query", bearer = token()).ifBlank { "[]" })

    fun upsert(table: String, rows: Any, onConflict: String) {
        http(
            "POST", "/rest/v1/$table?on_conflict=${enc(onConflict)}", rows.toString(), token(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    fun rpc(fn: String, args: JSONObject): String =
        http("POST", "/rest/v1/rpc/$fn", args.toString(), token())

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ── 도메인 모델 ───────────────────────────────────
data class Item(
    val id: String, val name: String, val kind: String, val unit: String, val target: Double?,
    val times: List<String>, val days: Set<Int>, val sort: Int, val startDay: String,
    val catName: String, val catColor: String, val catSort: Int
) {
    val isNumber get() = kind == "number"
    fun scheduledOn(date: LocalDate): Boolean =
        days.contains(date.dayOfWeek.value) && startDay <= date.toString()
    fun isDone(log: Log?): Boolean {
        if (log == null) return false
        if (!isNumber) return log.done
        val v = log.value ?: return false
        return if (target == null) v > 0 else v >= target
    }
}

data class Log(val itemId: String, val done: Boolean, val value: Double?)
data class Limit(val pkg: String, val label: String, val minutes: Int)

fun fmtNum(d: Double?): String = when {
    d == null -> "0"
    d == Math.floor(d) -> d.toLong().toString()
    else -> String.format("%.1f", d)
}

/** 데이터 접근 + 오프라인 대기열 */
object Repo {
    private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
    private fun JSONObject.s(k: String): String = if (isNull(k) || !has(k)) "" else getString(k)

    private fun parseItems(arr: JSONArray): List<Item> {
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cat = o.optJSONObject("categories")
            val times = o.optJSONArray("reminder_times") ?: JSONArray()
            val days = o.optJSONArray("days") ?: JSONArray("[1,2,3,4,5,6,7]")
            out += Item(
                id = o.getString("id"), name = o.s("name"), kind = o.s("kind"), unit = o.s("unit"),
                target = o.dbl("target"),
                times = (0 until times.length()).map { times.getString(it) },
                days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                sort = o.optInt("sort"), startDay = o.s("start_day").ifBlank { "2000-01-01" },
                catName = cat?.s("name")?.ifBlank { null } ?: "기타",
                catColor = cat?.s("color")?.ifBlank { null } ?: "#868E96",
                catSort = cat?.optInt("sort") ?: 999
            )
        }
        return out.sortedWith(compareBy({ it.catSort }, { it.catName }, { it.sort }, { it.name }))
    }

    fun fetchItems(): List<Item> {
        val arr = Supa.select(
            "items",
            "select=id,name,kind,unit,target,reminder_times,days,sort,start_day,categories(name,color,sort)&active=eq.true"
        )
        Prefs.put("items_cache", arr.toString())
        return parseItems(arr)
    }

    fun cachedItems(): List<Item> =
        try { parseItems(JSONArray(Prefs.str("items_cache") ?: "[]")) } catch (e: Exception) { emptyList() }

    fun fetchLogs(day: String): Map<String, Log> {
        val arr = Supa.select("logs", "select=item_id,done,value&day=eq.$day")
        val m = HashMap<String, Log>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            m[o.getString("item_id")] = Log(o.getString("item_id"), o.optBoolean("done"), o.dbl("value"))
        }
        return m
    }

    // ── 쓰기 (네트워크 실패 시 대기열에 저장) ──
    fun setDone(itemId: String, day: String, done: Boolean) =
        runOrQueue(JSONObject().put("op", "done").put("item", itemId).put("day", day).put("done", done))

    fun addValue(itemId: String, day: String, delta: Double) =
        runOrQueue(JSONObject().put("op", "add").put("item", itemId).put("day", day).put("delta", delta))

    fun setValue(itemId: String, day: String, value: Double, target: Double?) =
        runOrQueue(
            JSONObject().put("op", "set").put("item", itemId).put("day", day).put("value", value)
                .put("done", if (target == null) value > 0 else value >= target)
        )

    private fun exec(op: JSONObject) {
        val item = op.getString("item"); val day = op.getString("day")
        when (op.getString("op")) {
            "done" -> Supa.upsert(
                "logs", JSONObject().put("user_id", Supa.userId).put("item_id", item).put("day", day)
                    .put("done", op.getBoolean("done")).put("device", Prefs.deviceName)
                    .put("updated_at", java.time.OffsetDateTime.now().toString()), "item_id,day"
            )
            "set" -> Supa.upsert(
                "logs", JSONObject().put("user_id", Supa.userId).put("item_id", item).put("day", day)
                    .put("value", op.getDouble("value")).put("done", op.getBoolean("done"))
                    .put("device", Prefs.deviceName)
                    .put("updated_at", java.time.OffsetDateTime.now().toString()), "item_id,day"
            )
            "add" -> Supa.rpc(
                "add_log_value", JSONObject().put("p_item", item).put("p_day", day)
                    .put("p_delta", op.getDouble("delta")).put("p_device", Prefs.deviceName)
            )
        }
    }

    /** true = 서버 반영 완료, false = 오프라인이라 대기열에 저장 */
    @Synchronized
    fun runOrQueue(op: JSONObject): Boolean {
        return try {
            exec(op); true
        } catch (e: HttpError) {
            throw e
        } catch (e: NotLoggedIn) {
            throw e
        } catch (e: IOException) {
            val q = JSONArray(Prefs.str("pending") ?: "[]")
            q.put(op)
            Prefs.put("pending", q.toString())
            false
        }
    }

    @Synchronized
    fun flushPending() {
        val q = JSONArray(Prefs.str("pending") ?: "[]")
        if (q.length() == 0) return
        val remain = JSONArray()
        for (i in 0 until q.length()) {
            val op = q.getJSONObject(i)
            try { exec(op) } catch (e: HttpError) { /* 서버가 거부한 요청은 버림 */ } catch (e: IOException) { remain.put(op) }
        }
        Prefs.put("pending", remain.toString())
    }

    fun pendingCount(): Int = JSONArray(Prefs.str("pending") ?: "[]").length()

    // ── 기기 / 사용량 ──
    fun registerDevice() {
        Supa.upsert(
            "devices", JSONObject().put("user_id", Supa.userId).put("device_id", Prefs.deviceId)
                .put("name", Prefs.deviceName).put("last_seen", java.time.OffsetDateTime.now().toString()),
            "user_id,device_id"
        )
    }

    fun uploadUsage(day: String, usage: List<Usage.Entry>) {
        if (usage.isEmpty()) return
        val rows = JSONArray()
        val now = java.time.OffsetDateTime.now().toString()
        usage.take(40).forEach {
            rows.put(
                JSONObject().put("user_id", Supa.userId).put("device_id", Prefs.deviceId).put("day", day)
                    .put("package", it.pkg).put("label", it.label).put("minutes", it.minutes).put("updated_at", now)
            )
        }
        Supa.upsert("app_usage", rows, "user_id,device_id,day,package")
    }

    /** 모든 기기 합산 사용량 (분) */
    fun usageTotals(day: String): Map<String, Int> {
        val arr = Supa.select("app_usage", "select=package,minutes&day=eq.$day")
        val m = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            m.merge(o.getString("package"), o.optInt("minutes"), Int::plus)
        }
        return m
    }

    fun fetchLimits(): List<Limit> {
        val arr = Supa.select("usage_limits", "select=package,label,daily_limit_min&order=label")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Limit(o.getString("package"), o.s("label"), o.optInt("daily_limit_min", 60))
        }
    }
}
