package com.dan.dailyos

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * AnkiDroid 연동 (AnkiDroid가 공개한 ContentProvider API 사용)
 * - 덱 목록: content://com.ichi2.anki.flashcards/decks
 * - 노트 검색: content://com.ichi2.anki.flashcards/notes  (selection = Anki 검색어)
 */
object Anki {
    const val PKG = "com.ichi2.anki"
    const val PERM = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val AUTH = "com.ichi2.anki.flashcards"
    private val DECKS: Uri = Uri.parse("content://$AUTH/decks")
    private val NOTES: Uri = Uri.parse("content://$AUTH/notes")

    data class Deck(val id: Long, val name: String, val learn: Int, val review: Int, val new: Int)

    data class Stats(
        val total: Int, val remainingNew: Int, val newToday: Int, val reviewedToday: Int,
        val mature: Int, val dueLeft: Int, val newYesterday: Int, val reviewedYesterday: Int
    )

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun decks(ctx: Context): List<Deck> {
        val out = ArrayList<Deck>()
        ctx.contentResolver.query(DECKS, null, null, null, null)?.use { c ->
            val iId = c.getColumnIndex("deck_id")
            val iName = c.getColumnIndex("deck_name")
            val iCount = c.getColumnIndex("deck_count")
            while (c.moveToNext()) {
                var l = 0; var r = 0; var n = 0
                if (iCount >= 0) try {
                    val a = JSONArray(c.getString(iCount)); l = a.optInt(0); r = a.optInt(1); n = a.optInt(2)
                } catch (e: Exception) { }
                out += Deck(if (iId >= 0) c.getLong(iId) else 0L, if (iName >= 0) c.getString(iName) else "?", l, r, n)
            }
        }
        return out.sortedBy { it.name }
    }

    /** Anki 검색어에 맞는 노트 수 */
    fun count(ctx: Context, query: String): Int =
        ctx.contentResolver.query(NOTES, arrayOf("_id"), query, null, null)?.use { it.count } ?: -1

    fun deckQuery(names: List<String>): String =
        names.joinToString(" OR ", "(", ")") { "\"deck:" + it.replace("\"", "\\\"") + "\"" }

    fun collect(ctx: Context, names: List<String>): Stats {
        val q = deckQuery(names)
        val newToday = count(ctx, "$q introduced:1")
        val revToday = count(ctx, "$q rated:1")
        val deckList = try { decks(ctx) } catch (e: Exception) { emptyList() }
        val tracked = deckList.filter { d -> names.any { it == d.name } }
        val dueLeft = if (tracked.isNotEmpty()) tracked.sumOf { it.learn + it.review } else count(ctx, "$q is:due")
        return Stats(
            total = count(ctx, q),
            remainingNew = count(ctx, "$q is:new"),
            newToday = newToday,
            reviewedToday = revToday,
            mature = count(ctx, "$q prop:ivl>=21"),
            dueLeft = dueLeft,
            newYesterday = maxOf(0, count(ctx, "$q introduced:2") - newToday),
            reviewedYesterday = maxOf(0, count(ctx, "$q rated:2") - revToday)
        )
    }

    /** 폰의 Anki 통계를 서버에 올림 (오늘 + 어제 확정값) */
    fun upload(ctx: Context, s: AppSettings): Stats? {
        if (!installed(ctx) || !hasPermission(ctx) || s.ankiDecks.isEmpty()) return null
        val st = collect(ctx, s.ankiDecks)
        val day = LocalDate.parse(today())
        val now = java.time.OffsetDateTime.now().toString()
        val required = Metrics.requiredNew(st.remainingNew, st.newToday, day, s.ankiTarget)
        val rows = JSONArray()
        rows.put(
            JSONObject().put("user_id", Supa.userId).put("day", day.toString())
                .put("decks", s.ankiDecks.joinToString(", "))
                .put("new_studied", st.newToday).put("reviewed", st.reviewedToday)
                .put("remaining_new", st.remainingNew).put("total_cards", st.total)
                .put("mature", st.mature).put("due_left", st.dueLeft)
                .put("required_new", required).put("updated_at", now)
        )
        Supa.upsert("anki_daily", rows, "user_id,day")
        // 어제: 새 카드/복습 수만 확정 (다른 칸은 그대로)
        if (st.newYesterday > 0 || st.reviewedYesterday > 0) {
            Supa.upsert(
                "anki_daily", JSONArray().put(
                    JSONObject().put("user_id", Supa.userId).put("day", day.minusDays(1).toString())
                        .put("new_studied", st.newYesterday).put("reviewed", st.reviewedYesterday)
                        .put("updated_at", now)
                ), "user_id,day"
            )
        }
        publishDeckNames(ctx)
        return st
    }

    /** 웹 설정 화면에서 고를 수 있도록 덱 이름 목록을 설정에 기록 (바뀌었을 때만) */
    private fun publishDeckNames(ctx: Context) {
        val names = decks(ctx).map { it.name }.filter { !it.contains("::") }
        val joined = names.joinToString("\n")
        if (Prefs.str("anki_published") == joined) return
        SettingsStore.update { it.put("anki_available", JSONArray(names)) }
        Prefs.put("anki_published", joined)
    }
}
