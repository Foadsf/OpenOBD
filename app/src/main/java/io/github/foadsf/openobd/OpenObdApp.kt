package io.github.foadsf.openobd

import android.app.Application
import io.github.foadsf.openobd.obd.Units

/** Holds the one [Session], so a connection survives rotation and activity restarts. */
class OpenObdApp : Application() {
    lateinit var session: Session
        private set

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        Units.imperial = session.prefs.imperial
    }
}
