package at.werkstatt.screenmirror

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.werkstatt.screenmirror.core.MirrorEngine
import at.werkstatt.screenmirror.core.Prefs
import at.werkstatt.screenmirror.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Steuerung am Handy: Freigabe starten/stoppen und sehen, woran es gerade haengt.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, result.resultCode, data)
            } else {
                Toast.makeText(this, R.string.toast_consent_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Ohne Benachrichtigung laeuft der Service trotzdem - nur unsichtbar.
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MirrorEngine.attach(applicationContext)

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener { ProjectionService.stop(this) }

        binding.parkedOnlySwitch.isChecked = Prefs.parkedOnly(this)
        binding.parkedOnlySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setParkedOnly(this, checked)
            if (!checked) MirrorEngine.setDrivingPaused(false)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorEngine.state.collect { render(it) }
            }
        }
    }

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, R.string.toast_no_projection, Toast.LENGTH_LONG).show()
            return
        }
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun render(state: MirrorEngine.State) {
        val (title, detail) = when (state.phase) {
            MirrorEngine.Phase.IDLE -> R.string.status_idle to R.string.status_idle_detail
            MirrorEngine.Phase.WAITING_FOR_CAR -> R.string.status_waiting_car to R.string.status_waiting_car_detail
            MirrorEngine.Phase.WAITING_FOR_PHONE -> R.string.status_waiting_phone to R.string.status_waiting_phone_detail
            MirrorEngine.Phase.MIRRORING -> R.string.status_mirroring to R.string.status_mirroring_detail
            MirrorEngine.Phase.PAUSED_WHILE_DRIVING -> R.string.status_paused to R.string.status_paused_detail
        }
        binding.statusTitle.setText(title)
        binding.statusDetail.setText(detail)
        binding.startButton.isEnabled = !state.projectionActive
        binding.stopButton.isEnabled = state.projectionActive
    }
}
