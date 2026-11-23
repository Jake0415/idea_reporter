package app.ideacollector.work

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.ideacollector.data.SyncNews

internal class SyncNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "동기화 결과", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun publish(news: SyncNews) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        news.cards?.let { count ->
            val detail = if (news.updates > 0) "추적 업데이트 ${news.updates}건" else null
            notify(1, "오늘 카드 ${count}개", detail)
        }
        if (news.updateRequired) notify(3, "앱 업데이트 필요", null)
        if (news.analysis) notify(2, "분석 결과가 도착했습니다", null)
    }

    private fun notify(id: Int, title: String, detail: String?) {
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .build())
    }

    private companion object { const val CHANNEL = "sync_results" }
}

fun requestNotifications(activity: Activity, requestCode: Int) {
    if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
}
