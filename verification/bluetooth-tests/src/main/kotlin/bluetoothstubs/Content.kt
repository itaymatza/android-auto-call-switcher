@file:Suppress("UNUSED_PARAMETER")

package android.content

import android.bluetooth.BluetoothManager
import android.media.AudioManager

open class Context {
    val applicationContext: Context get() = this
    val contentResolver = ContentResolver()
    var bluetoothGranted = true
    val manager = BluetoothManager()
    var audioManager: AudioManager? = AudioManager()
    var receiver: BroadcastReceiver? = null

    fun <T> getSystemService(type: Class<T>): T? =
        when (type) {
            BluetoothManager::class.java -> type.cast(manager)
            AudioManager::class.java -> type.cast(audioManager)
            else -> null
        }

    fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        flags: Int,
    ) {
        this.receiver = receiver
    }

    fun unregisterReceiver(receiver: BroadcastReceiver) {
        check(this.receiver === receiver)
        this.receiver = null
    }

    companion object {
        const val RECEIVER_EXPORTED = 2
    }
}

abstract class BroadcastReceiver {
    abstract fun onReceive(
        context: Context,
        intent: Intent,
    )
}

class Intent(
    val action: String?,
)

class IntentFilter(
    action: String? = null,
) {
    fun addAction(action: String) = Unit
}
