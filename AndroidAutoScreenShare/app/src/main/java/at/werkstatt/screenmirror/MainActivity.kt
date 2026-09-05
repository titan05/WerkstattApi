package at.werkstatt.screenmirror

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.werkstatt.screenmirror.core.MirrorEngine
import at.werkstatt.screenmirror.core.Prefs
import at.werkstatt.screenmirror.ui.theme.MirrorTheme

/**
 * Steuerung am Handy: Freigabe starten/stoppen und sehen, woran es gerade haengt.
 */
class MainActivity : ComponentActivity() {

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
        enableEdgeToEdge()

        MirrorEngine.attach(applicationContext)

        setContent {
            MirrorTheme {
                val state by MirrorEngine.state.collectAsStateWithLifecycle()

                MirrorScreen(
                    state = state,
                    onStart = ::onStartClicked,
                    onStop = { ProjectionService.stop(this) }
                )
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
}

/** Farbe, Icon und Texte pro Phase an einer Stelle - der Screen bleibt so lesbar. */
private data class PhaseLook(
    val icon: ImageVector,
    val title: Int,
    val detail: Int,
    val pulsing: Boolean = false
)

@Composable
private fun lookFor(phase: MirrorEngine.Phase): PhaseLook = when (phase) {
    MirrorEngine.Phase.IDLE -> PhaseLook(
        Icons.Default.StopScreenShare, R.string.status_idle, R.string.status_idle_detail
    )
    MirrorEngine.Phase.WAITING_FOR_CAR -> PhaseLook(
        Icons.Default.DirectionsCar, R.string.status_waiting_car, R.string.status_waiting_car_detail
    )
    MirrorEngine.Phase.WAITING_FOR_PHONE -> PhaseLook(
        Icons.Default.PhoneAndroid, R.string.status_waiting_phone, R.string.status_waiting_phone_detail
    )
    MirrorEngine.Phase.MIRRORING -> PhaseLook(
        Icons.Default.ScreenShare, R.string.status_mirroring, R.string.status_mirroring_detail,
        pulsing = true
    )
    MirrorEngine.Phase.PAUSED_WHILE_DRIVING -> PhaseLook(
        Icons.Default.PauseCircle, R.string.status_paused, R.string.status_paused_detail
    )
}

@Composable
private fun containerFor(phase: MirrorEngine.Phase): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (phase) {
        MirrorEngine.Phase.IDLE ->
            scheme.surfaceContainer to scheme.onSurface
        MirrorEngine.Phase.WAITING_FOR_CAR, MirrorEngine.Phase.WAITING_FOR_PHONE ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        MirrorEngine.Phase.MIRRORING ->
            scheme.primaryContainer to scheme.onPrimaryContainer
        MirrorEngine.Phase.PAUSED_WHILE_DRIVING ->
            scheme.errorContainer to scheme.onErrorContainer
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MirrorScreen(
    state: MirrorEngine.State,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            StatusCard(state.phase)

            Spacer(Modifier.height(20.dp))

            PrimaryAction(
                active = state.projectionActive,
                onStart = onStart,
                onStop = onStop
            )

            Spacer(Modifier.height(20.dp))

            ParkedOnlyCard()

            Spacer(Modifier.height(20.dp))

            Text(
                stringResource(R.string.hints),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusCard(phase: MirrorEngine.Phase) {
    val look = lookFor(phase)
    val (targetContainer, targetContent) = containerFor(phase)

    // Federnd statt linear, damit der Phasenwechsel spuerbar ist.
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "statusContainer"
    )
    val content by animateColorAsState(targetContent, label = "statusContent")

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    look.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(look.title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = content
                )
                if (look.pulsing) {
                    Spacer(Modifier.width(10.dp))
                    LivePulse(color = content)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(look.detail),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.78f)
            )
        }
    }
}

/** Kleiner atmender Punkt - zeigt auf einen Blick, dass gerade wirklich uebertragen wird. */
@Composable
private fun LivePulse(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Spacer(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun PrimaryAction(active: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    // Ein Button, der die Rolle wechselt - kein Paar aus aktiv/ausgegraut.
    val container by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "actionContainer"
    )
    val content = if (active) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimary

    Button(
        onClick = { if (active) onStop() else onStart() },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        Icon(
            if (active) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (active) R.string.action_stop else R.string.action_start),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ParkedOnlyCard() {
    val context = LocalContext.current
    var parkedOnly by remember { mutableStateOf(Prefs.parkedOnly(context)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.setting_parked_only),
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = parkedOnly,
                    onCheckedChange = { checked ->
                        parkedOnly = checked
                        Prefs.setParkedOnly(context, checked)
                        if (!checked) MirrorEngine.setDrivingPaused(false)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setting_parked_only_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Der Guard ist fail-open. Solange das so ist, gehoert der Hinweis
            // sichtbar in die Karte und nicht in eine Fussnote.
            if (parkedOnly) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.setting_parked_only_caveat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
