package cc.huhm.splinklez.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.R

/** Notification channels and the one place that posts notifications. */
object Notifier {
    const val CH_EQ_ALERT = "eq_alert"
    const val CH_EQ_REPORT = "eq_report"
    const val CH_WEATHER = "weather"
    const val CH_JP_EEW = "jp_eew"
    const val CH_JP_SERVICE = "jp_service"
    const val CH_GENERAL = "general"
    const val CH_COMMUNITY = "community"
    const val CH_SENSOR = "sensor"
    const val CH_SENSOR_SERVICE = "sensor_service"
    const val CH_ENGAGE = "engage"

    /** Intent extra telling [MainActivity] which screen to open. */
    const val EXTRA_OPEN = "spz_open"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarm = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val event = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_EQ_ALERT, "強震（震度 5 弱以上）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "你所在縣市震度達 5 弱以上的地震報告。夜間勿擾時段也會響。"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 900)
                    setSound(alarmSound, alarm)
                },
                NotificationChannel(CH_EQ_REPORT, "地震報告", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "中央氣象署地震報告，你所在縣市震度達門檻時通知。"
                    enableVibration(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), event)
                },
                NotificationChannel(CH_WEATHER, "颱風與天氣警特報", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "颱風警報（你的縣市在警戒區時）與豪大雨等特報。"
                    enableVibration(true)
                },
                NotificationChannel(CH_JP_EEW, "旅日模式：日本緊急地震速報", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "日本氣象廳緊急地震速報，經 Wolfx Project 轉送（非官方）。"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
                    setSound(alarmSound, alarm)
                },
                NotificationChannel(CH_JP_SERVICE, "旅日模式：常駐連線", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "旅日模式開啟時的常駐通知（系統規定要顯示）。"
                    setShowBadge(false)
                },
                NotificationChannel(CH_SENSOR, "家用感測器偵測到晃動", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "你登記的家用感測器（舊手機）偵測到晃動。僅供個人參考，非官方地震資訊。"
                    enableVibration(true)
                },
                NotificationChannel(CH_SENSOR_SERVICE, "家用感測模式：常駐", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "這支手機當作感測器時的常駐通知（系統規定要顯示）。"
                    setShowBadge(false)
                },
                NotificationChannel(CH_COMMUNITY, "社區：有人提到你與公告", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Communityeom 裡有人 @ 你，或頻道發布公告。"
                },
                NotificationChannel(CH_ENGAGE, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "早安天氣、新聞重點、明日天氣、下雨與閃電提醒、排程任務結果。每天最多 3 則，22:00–07:00 不送。"
                },
                NotificationChannel(CH_GENERAL, "其他通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "測試通知與 App 更新。"
                },
            ),
        )
    }

    fun canPost(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun openAppIntent(ctx: Context, open: String = "alerts", req: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN, open)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(
        ctx: Context,
        channel: String,
        tag: String,
        title: String,
        body: String,
        silent: Boolean,
        urgent: Boolean,
        link: String? = null,
        ongoing: Boolean = false,
        open: String = "alerts",
    ) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val id = tag.hashCode()
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFFB3261E.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(silent && !urgent)
            .setOngoing(ongoing)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(ctx, open, id))
        if (link != null && link.startsWith("https://")) {
            val view = PendingIntent.getActivity(ctx, id + 1, Intent(Intent.ACTION_VIEW, Uri.parse(link)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "看報告", view)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(tag, 1, b.build())
        } catch (_: SecurityException) {
        }
    }
}
