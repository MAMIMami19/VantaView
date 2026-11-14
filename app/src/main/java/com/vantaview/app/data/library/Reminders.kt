// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.library

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vantaview.app.MainActivity
import com.vantaview.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** True while VantaView is on screen; then reminders show as a pop-up instead of a notification. */
object AppVisible { @Volatile var now = false }

/** Sets an alarm for each reminder so a notification can show when VantaView is closed. */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(r: Reminder, create: Boolean): PendingIntent? {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra("channelId", r.channelId).putExtra("channel", r.channelName)
            .putExtra("title", r.title).putExtra("start", r.start)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, r.key.hashCode(), i, flags)
    }

    fun schedule(r: Reminder) {
        if (r.alertAt < System.currentTimeMillis() - 60_000) return
        val p = pending(r, true) ?: return
        // Inexact is fine (no special permission needed); Android may run it a little late in deep sleep.
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.alertAt, p) }
    }

    fun cancel(r: Reminder) { pending(r, false)?.let { alarms.cancel(it); it.cancel() } }

    /** Alarms are lost when the device restarts; set them again when the app opens. */
    fun rescheduleAll(list: List<Reminder>) = list.forEach(::schedule)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppVisible.now) return // the pop-up in the app shows it
        val channelId = intent.getLongExtra("channelId", 0)
        val title = intent.getStringExtra("title").orEmpty()
        val channel = intent.getStringExtra("channel").orEmpty()
        val start = intent.getLongExtra("start", 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, channelId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAY_CHANNEL, channelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SimpleDateFormat("HH:mm", Locale.UK).format(Date(start))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Starts at $at on $channel. Tap to watch.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((channelId + start).toInt(), n) }
    }

    companion object { const val CHANNEL = "reminders" }
}
