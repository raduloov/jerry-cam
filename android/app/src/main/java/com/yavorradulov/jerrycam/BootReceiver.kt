package com.yavorradulov.jerrycam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings Jerry Cam back after a reboot or an app update. Android only lets a camera service start
 * from the foreground, so this opens the setup screen (allowed with "display over other apps"),
 * or otherwise asks someone at home to tap a notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled(context)) return
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            CamService.postAlert(context, R.string.notify_restart)
        }
    }
}
