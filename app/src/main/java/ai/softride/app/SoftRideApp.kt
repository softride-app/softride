package ai.softride.app

import ai.softride.app.engine.Engine
import android.app.Application

class SoftRideApp : Application() {
    val engine by lazy { Engine(this) }
}
