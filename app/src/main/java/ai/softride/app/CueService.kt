package ai.softride.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps cueing and TTS running even when the screen is covered (e.g. driving restriction overlay). */
class CueService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "멀미 예보", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("소프트라이드")
            .setContentText("뒷좌석 멀미 예보가 켜져 있어요")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        (application as SoftRideApp).engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_REPLAY, false) == true) (application as SoftRideApp).engine.startReplay()
        if (intent?.getBooleanExtra(EXTRA_NAVI_PROBE, false) == true) (application as SoftRideApp).engine.requestTestRoute()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as SoftRideApp).engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "cues"
        const val EXTRA_REPLAY = "replay"
        const val EXTRA_NAVI_PROBE = "navi_probe"
    }
}
