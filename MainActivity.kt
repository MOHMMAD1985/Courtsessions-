package com.example.courtsessions

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

class MainActivity : Activity() {
    private val keys = listOf("case", "client", "court", "circuit", "date", "time", "notes")
    private fun ed(k: String) = findViewById<EditText>(resources.getIdentifier("f_$k", "id", packageName))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.mic).setOnClickListener { listen() }
        findViewById<Button>(R.id.parse).setOnClickListener { analyze() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.perms).setOnClickListener { ensurePerms(true) }
        ed("date").setOnClickListener {
            val n = LocalDate.now()
            DatePickerDialog(this, { _, y, m, d -> ed("date").setText("%04d-%02d-%02d".format(y, m + 1, d)) },
                n.year, n.monthValue - 1, n.dayOfMonth).show()
        }
        ed("time").setOnClickListener {
            TimePickerDialog(this, { _, h, m -> ed("time").setText("%02d:%02d".format(h, m)) }, 9, 0, true).show()
        }
        ensurePerms(false)
        render()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        try { startActivityForResult(i, 7) } catch (e: ActivityNotFoundException) {
            toast("الإملاء الصوتي غير متاح. ثبّت تطبيق Google أو اكتب النص.")
        }
    }

    @Deprecated("simple flow")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 7 && res == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (t != null) { findViewById<EditText>(R.id.tx).setText(t); analyze() }
        }
    }

    private fun analyze() {
        val raw = findViewById<EditText>(R.id.tx).text.toString()
        if (raw.isBlank()) return
        val r = Parser.parse(raw)
        keys.forEach { k -> r[k]?.let { ed(k).setText(it) } }
        val miss = listOf("case" to "رقم القضية", "date" to "التاريخ", "time" to "الوقت")
            .filter { ed(it.first).text.isBlank() }.map { it.second }
        findViewById<TextView>(R.id.status).text =
            if (miss.isEmpty()) "تم. راجع الحقول ثم اضغط حفظ." else "لم أجد: ${miss.joinToString("، ")}. أكملها يدوياً."
    }

    private fun current(): Session? {
        val v = keys.associateWith { ed(it).text.toString().trim() }
        if (v["date"]!!.isEmpty()) { toast("حدد تاريخ الجلسة أولاً"); return null }
        val id = ((System.currentTimeMillis() / 1000) % 1_000_000_000).toInt()
        return Session(id, v["case"]!!, v["client"]!!, v["court"]!!, v["circuit"]!!, v["date"]!!, v["time"]!!, v["notes"]!!)
    }

    private fun save() {
        val s = current() ?: return
        Store.add(this, s); Scheduler.schedule(this, s); render()
        toast("حُفظت الجلسة وجُدولت التذكيرات (قبل يوم وقبل ساعتين)")
    }

    private fun addToCalendar(s: Session) {
        val st = s.start()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, s.title())
            .putExtra(CalendarContract.Events.EVENT_LOCATION, s.place())
            .putExtra(CalendarContract.Events.DESCRIPTION, s.details())
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, st)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, st + 3600_000L)
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast("لا يوجد تطبيق تقويم") }
    }

    private fun ensurePerms(force: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        askExact(force)
    }

    private fun askExact(force: Boolean) {
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            val p = getSharedPreferences("jl", MODE_PRIVATE)
            if (force || !p.getBoolean("asked", false)) {
                p.edit().putBoolean("asked", true).apply()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                return
            }
        }
        if (force) toast("الإذونات مفعّلة")
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        askExact(false)
    }

    private fun render() {
        val box = findViewById<LinearLayout>(R.id.list); box.removeAllViews()
        val all = Store.all(this).sortedBy { it.date + it.time }
        if (all.isEmpty()) { box.addView(TextView(this).apply { text = "لا توجد جلسات بعد." }); return }
        all.forEach { s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            row.addView(TextView(this).apply { text = s.title(); textSize = 17f })
            row.addView(TextView(this).apply { text = "${s.date}  ${s.time}  ${s.place()}" })
            val btns = LinearLayout(this).apply { gravity = Gravity.START }
            btns.addView(Button(this).apply { text = "إلى التقويم"; setOnClickListener { addToCalendar(s) } })
            btns.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener { Scheduler.cancel(this@MainActivity, s); Store.remove(this@MainActivity, s.id); render() }
            })
            row.addView(btns); box.addView(row)
        }
    }
}
