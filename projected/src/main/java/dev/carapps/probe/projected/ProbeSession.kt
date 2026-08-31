package dev.carapps.probe.projected

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class ProbeSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = RootScreen(carContext)
}
