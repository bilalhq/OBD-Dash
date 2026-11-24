package com.obddash

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds the single OBD connection so the phone UI and Android Auto share it. */
class ObdApp : Application() {
    val controller: ObdController by lazy { ObdController(this) }

    override fun onCreate() {
        super.onCreate()
        // Keep a foreground service running whenever we're connected or (re)connecting,
        // so Android doesn't kill the process mid-trip.
        MainScope().launch {
            controller.state
                .map { it.conn == ConnState.Connected || it.conn == ConnState.Connecting }
                .distinctUntilChanged()
                // Skip the initial "idle": if the system is restarting a killed service, stopping
                // it here would race with it reconnecting.
                .drop(1)
                .collect { active ->
                    if (active) ObdService.start(this@ObdApp) else ObdService.stop(this@ObdApp)
                }
        }
    }
}
