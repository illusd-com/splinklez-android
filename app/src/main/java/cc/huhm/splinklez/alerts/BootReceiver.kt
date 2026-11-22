package cc.huhm.splinklez.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.huhm.splinklez.sensor.SensorService

/** Re-arms the opt-in foreground services after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            JapanEewService.apply(context)
            SensorService.apply(context)
        }
    }
}
