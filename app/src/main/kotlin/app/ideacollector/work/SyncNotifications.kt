package app.ideacollector.work

import android.app.Activity
import android.Manifest
import android.os.Build

fun requestNotifications(activity: Activity, requestCode: Int) {
    if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
}
