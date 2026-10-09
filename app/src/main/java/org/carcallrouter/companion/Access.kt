package org.carcallrouter.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object Access {
    val runtimePermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_NUMBERS)

    fun runtimeGranted(context: Context) =
        runtimePermissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun bluetoothGranted(context: Context) =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun authorizationLocal() = "cmd appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_ONGOING_CALLS allow"

    fun authorizationAdb() = "adb shell ${authorizationLocal()}"

    fun revocationAdb() = "adb shell cmd appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_ONGOING_CALLS default"
}
