package at.werkstatt.screenmirror.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import at.werkstatt.screenmirror.core.MirrorEngine

class MirrorSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        MirrorEngine.attach(carContext.applicationContext)
        return MirrorScreen(carContext)
    }
}
