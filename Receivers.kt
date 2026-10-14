package com.example.courtsessions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sessions", "تذكير الجلسات", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "sessions")
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(i.getStringExtra("title"))
            .setContentText(i.getStringExtra("body"))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(i.getIntExtra("n", 0), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Store.all(c).forEach { Scheduler.schedule(c, it) }
    }
}
