package com.example.courtsessions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class Session(
    val id: Int, val caseNo: String, val client: String, val court: String,
    val circuit: String, val date: String, val time: String, val notes: String
) {
    fun start(): Long = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time.ifBlank { "09:00" }))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun title() = listOf("جلسة", if (caseNo.isNotBlank()) "قضية $caseNo" else "", client)
        .filter { it.isNotBlank() }.joinToString(" - ")

    fun place() = listOf(court, circuit).filter { it.isNotBlank() }.joinToString(" - ")

    fun details() = listOf(
        if (caseNo.isNotBlank()) "رقم القضية: $caseNo" else "",
        if (client.isNotBlank()) "الموكل: $client" else "",
        if (place().isNotBlank()) "المحكمة: ${place()}" else "",
        if (notes.isNotBlank()) "ملاحظات: $notes" else ""
    ).filter { it.isNotBlank() }.joinToString("\n")

    fun body(k: Int) = (if (k == 0) "غداً" else "بعد ساعتين") + " - " + time.ifBlank { "09:00" } +
        (if (place().isNotBlank()) " - " + place() else "")
}

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("jl", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Session> {
        val a = JSONArray(sp(c).getString("s", "[]"))
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Session(o.getInt("id"), o.getString("case"), o.getString("client"), o.getString("court"),
                o.getString("circuit"), o.getString("date"), o.getString("time"), o.getString("notes"))
        }
    }

    private fun write(c: Context, l: List<Session>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("case", it.caseNo).put("client", it.client)
                .put("court", it.court).put("circuit", it.circuit).put("date", it.date)
                .put("time", it.time).put("notes", it.notes))
        }
        sp(c).edit().putString("s", a.toString()).apply()
    }

    fun add(c: Context, s: Session) { val l = all(c); l.add(s); write(c, l) }
    fun remove(c: Context, id: Int) = write(c, all(c).filter { it.id != id })
}

object Scheduler {
    private fun pending(c: Context, s: Session, k: Int): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java)
            .putExtra("n", s.id * 2 + k).putExtra("title", s.title()).putExtra("body", s.body(k))
        return PendingIntent.getBroadcast(c, s.id * 2 + k, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(c: Context, s: Session) {
        val am = c.getSystemService(AlarmManager::class.java)
        val start = s.start()
        listOf(0 to 24 * 3600_000L, 1 to 2 * 3600_000L).forEach { (k, before) ->
            val at = start - before
            if (at > System.currentTimeMillis()) {
                val pi = pending(c, s, k)
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancel(c: Context, s: Session) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c, s, 0)); am.cancel(pending(c, s, 1))
    }
}

object Parser {
    private const val AR = "٠١٢٣٤٥٦٧٨٩"
    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    private val MONI = mapOf("يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5, "يونيو" to 6,
        "يوليو" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
        "شباط" to 2, "اذار" to 3, "نيسان" to 4, "ايار" to 5, "حزيران" to 6, "تموز" to 7, "ايلول" to 9)
    private val WD = mapOf("الاحد" to 0, "الاثنين" to 1, "الاتنين" to 1, "الثلاثاء" to 2,
        "الاربعاء" to 3, "الخميس" to 4, "الجمعه" to 5, "السبت" to 6)
    private val STOP = setOf("في", "يوم", "الساعه", "ساعه", "بتاريخ", "غدا", "اليوم", "بعد", "قضيه", "دعوي",
        "رقم", "دائره", "للموكل", "الموكل", "موكل", "موكلي", "و", "ثم", "ملاحظه", "ملاحظات", "القادم", "الجاي")

    fun parse(raw: String): Map<String, String> {
        val sb = StringBuilder()
        for (c in raw) {
            val a = AR.indexOf(c); val b = FA.indexOf(c)
            sb.append(if (a >= 0) '0' + a else if (b >= 0) '0' + b else c)
        }
        val t = sb.toString().replace(Regex("[\\u064B-\\u0652\\u0640]"), "").trim()
        val n = t.map { when (it) { 'أ', 'إ', 'آ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; else -> it } }.joinToString("")
        val T = t.split(Regex("\\s+")); val N = n.split(Regex("\\s+"))
        val r = mutableMapOf<String, String>()
        fun stop(x: String) = x in STOP || x.firstOrNull()?.isDigit() == true ||
            Regex("^[بل]?محكمه").containsMatchIn(x) || x in WD || x in MONI
        fun take(i: Int, max: Int): String {
            val o = mutableListOf<String>(); var j = i
            while (j < N.size && o.size < max && !stop(N[j])) { o.add(T[j]); j++ }
            return o.joinToString(" ")
        }
        for (i in N.indices) {
            val x = N[i]
            if (!r.containsKey("case") && Regex("^(ال)?(قضيه|دعوي|ملف)$").matches(x)) {
                var j = i + 1; if (N.getOrNull(j) == "رقم") j++
                val y = N.getOrNull(j); if (y != null && y[0].isDigit()) r["case"] = T[j]
            } else if (!r.containsKey("case") && x == "رقم" && N.getOrNull(i + 1)?.firstOrNull()?.isDigit() == true) {
                r["case"] = T[i + 1]
            } else if (!r.containsKey("client") && Regex("^(للموكل|الموكل|موكل|موكلي|للسيد|السيد|للسيده|السيده)$").matches(x)) {
                r["client"] = take(i + 1, 3)
            } else if (!r.containsKey("court") && Regex("^[بل]?محكمه").containsMatchIn(x)) {
                r["court"] = (T[i].replace(Regex("^[بل](?=محكم)"), "") + " " + take(i + 1, 3)).trim()
            } else if (!r.containsKey("circuit") && x == "دائره") {
                r["circuit"] = take(i + 1, 2)
            }
        }
        Regex("ملاحظ\\S*").find(n)?.let { r["notes"] = t.substring(it.range.first).replace(Regex("^ملاحظ\\S*\\s*"), "") }

        val n2 = r["case"]?.let { n.replace(it, " ") } ?: n
        val today = LocalDate.now()
        fun fix(y: Int?, m: Int, d: Int): LocalDate? = try {
            var x = LocalDate.of(y ?: today.year, m, d)
            if (y == null && x.isBefore(today)) x = x.plusYears(1); x
        } catch (e: Exception) { null }
        val rel = Regex("بعد\\s+(\\d+)\\s*(يوم|ايام|اسبوع|اسابيع)").find(n2)
        val num = Regex("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})(?:\\s*[/\\-.]\\s*(\\d{2,4}))?").find(n2)
        val mon = Regex("(\\d{1,2})\\s+(" + MONI.keys.joinToString("|") + ")(?:\\s+(\\d{4}))?").find(n2)
        val wd = N.firstOrNull { it in WD }
        val d: LocalDate? = when {
            Regex("بعد\\s+غد").containsMatchIn(n2) -> today.plusDays(2)
            Regex("(^|\\s)غدا(\\s|$)").containsMatchIn(n2) -> today.plusDays(1)
            Regex("(^|\\s)اليوم(\\s|$)").containsMatchIn(n2) -> today
            rel != null -> today.plusDays(rel.groupValues[1].toLong() * (if (rel.groupValues[2].startsWith("اس")) 7 else 1))
            Regex("بعد\\s+اسبوع").containsMatchIn(n2) -> today.plusDays(7)
            num != null -> {
                var y = num.groupValues[3].toIntOrNull(); if (y != null && y < 100) y += 2000
                fix(y, num.groupValues[2].toInt(), num.groupValues[1].toInt())
            }
            mon != null -> fix(mon.groupValues[3].toIntOrNull(), MONI[mon.groupValues[2]]!!, mon.groupValues[1].toInt())
            wd != null -> {
                var k = (WD[wd]!! - today.dayOfWeek.value % 7 + 7) % 7; if (k == 0) k = 7
                today.plusDays(k.toLong())
            }
            else -> null
        }
        if (d != null) r["date"] = d.toString()

        val tm = Regex("(?:الساعه|ساعه)\\s*(\\d{1,2})(?::(\\d{2}))?(?:\\s*(?:و\\s*(نصف|ربع)|الا\\s+(ربع)))?\\s*(صباحا|مساء|ظهرا|عصرا|ليلا|ص|م)?").find(n2)
        if (tm != null) {
            val g = tm.groupValues
            var h = g[1].toInt(); var mi = g[2].toIntOrNull() ?: 0; val mer = g[5]
            if (g[3] == "نصف") mi = 30 else if (g[3] == "ربع") mi = 15 else if (g[4].isNotEmpty()) { h -= 1; mi = 45 }
            if (mer.startsWith("ظهر")) { if (h < 7) h += 12 }
            else if (mer.startsWith("مساء") || mer.startsWith("عصر") || mer.startsWith("ليل") || mer == "م") { if (h < 12) h += 12 }
            else if (mer.isEmpty() && h in 1..6) h += 12
            if (h in 0..23) r["time"] = "%02d:%02d".format(h, mi)
        }
        return r
    }
}
