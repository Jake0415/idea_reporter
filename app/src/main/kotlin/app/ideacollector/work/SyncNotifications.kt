package app.ideacollector.work

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.ideacollector.MainActivity
import app.ideacollector.R
import app.ideacollector.data.SyncNews

internal class SyncNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun publish(news: SyncNews) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        news.cards?.let { count ->
            notify(1, context.getString(R.string.notification_cards, count))
        }
        news.trackId?.let { id ->
            val link = android.net.Uri.Builder().scheme("ideacollector").authority("track").appendPath(id).build()
            notify(4, context.getString(R.string.tracking_news, news.updates), link)
        }
        if (news.updateRequired) notify(3, context.getString(R.string.notification_update))
        if (news.analysis) notify(2, context.getString(R.string.notification_analysis))
    }

    private fun notify(id: Int, title: String, link: android.net.Uri? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = link
            if (link == null) putExtra("openToday", true)
        }
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    private companion object { const val CHANNEL = "sync_results" }
}

fun requestNotifications(activity: Activity, requestCode: Int) {
    if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
}
