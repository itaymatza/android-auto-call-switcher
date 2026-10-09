package org.carcallrouter.companion

import android.app.Application

class RouterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RouterLog.initialize(this)
        RouterLog.event(
            "PROCESS_START",
            "version=${BuildConfig.VERSION_NAME}; sdk=${android.os.Build.VERSION.SDK_INT}; " +
                "${ProcessDiagnostics.snapshot(this)}",
        )
    }
}
