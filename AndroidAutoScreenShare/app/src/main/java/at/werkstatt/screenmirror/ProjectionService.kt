package at.werkstatt.screenmirror

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import at.werkstatt.screenmirror.core.MirrorAudioBridge
import at.werkstatt.screenmirror.core.MirrorEngine
import at.werkstatt.screenmirror.core.Prefs

/**
 * Haelt die Bildschirmaufnahme am Leben.
 *
 * Reihenfolge ist wichtig: ab Android 14 muss der Foreground-Service mit dem Typ
 * `mediaProjection` bereits laufen, bevor `getMediaProjection()` aufgerufen wird.
 */
class ProjectionService : Service() {

    private var audioBridge: MirrorAudioBridge? = null

    override fun onCreate() {
        super.onCreate()
        MirrorEngine.attach(applicationContext)
        // Wenn der Nutzer die Freigabe im Systemdialog beendet, beenden wir auch den Service.
        MirrorEngine.onProjectionEnded = { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAudioBridge()
                MirrorEngine.onProjectionStopped()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startAsForeground()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.w(TAG, "Ungueltige Freigabe-Daten erhalten")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val manager = getSystemService(MediaProjectionManager::class.java)
                val projection = try {
                    manager?.getMediaProjection(resultCode, resultData)
                } catch (t: Throwable) {
                    Log.e(TAG, "getMediaProjection fehlgeschlagen", t)
                    null
                }
                if (projection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                MirrorEngine.onProjectionStarted(projection)
                startAudioBridge(projection)
                return START_NOT_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        MirrorEngine.onProjectionEnded = null
        stopAudioBridge()
        MirrorEngine.onProjectionStopped()
        super.onDestroy()
    }

    /** Ton nur mitspiegeln, wenn gewuenscht UND RECORD_AUDIO erteilt ist. */
    private fun audioEnabled(): Boolean =
        Prefs.shareAudio(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAudioBridge(projection: MediaProjection) {
        if (!audioEnabled()) {
            Log.i(TAG, "Audio-Bruecke aus (Einstellung/Berechtigung)")
            return
        }
        audioBridge = MirrorAudioBridge(projection).also { it.start(useGuidanceChannel = true) }
    }

    private fun stopAudioBridge() {
        audioBridge?.stop()
        audioBridge = null
    }

    private fun startAsForeground() {
        createChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Nur wenn Audio wirklich mitlaeuft, den Mikrofon-Typ deklarieren - sonst wirft
        // startForeground ab Android 14 (fehlende RECORD_AUDIO-Berechtigung fuer den Typ).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audioEnabled()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            type,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val TAG = "ProjectionService"
        private const val CHANNEL_ID = "screen_mirror"
        private const val NOTIFICATION_ID = 4711

        private const val ACTION_START = "at.werkstatt.screenmirror.START"
        private const val ACTION_STOP = "at.werkstatt.screenmirror.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProjectionService::class.java).setAction(ACTION_STOP)
            // Bewusst startService statt startForegroundService: laeuft der Service gar nicht
            // mehr, muesste er sonst binnen Sekunden startForeground() aufrufen - genau das
            // wollen wir beim Beenden nicht.
            try {
                context.startService(intent)
            } catch (t: IllegalStateException) {
                Log.w(TAG, "Stop-Intent konnte nicht zugestellt werden", t)
                MirrorEngine.onProjectionStopped()
            }
        }
    }
}
