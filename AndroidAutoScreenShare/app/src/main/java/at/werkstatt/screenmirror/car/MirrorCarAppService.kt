package at.werkstatt.screenmirror.car

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Einstiegspunkt fuer Android Auto. Der Host (Android-Auto-App bzw. Desktop Head Unit)
 * bindet sich an diesen Service.
 */
class MirrorCarAppService : CarAppService() {

    // Die Allowlist stammt aus der Car App Library und ist genau fuer diesen Zweck gedacht.
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) {
            // Erlaubt das Testen mit der Desktop Head Unit und sideloadeten Hosts.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = MirrorSession()
}
