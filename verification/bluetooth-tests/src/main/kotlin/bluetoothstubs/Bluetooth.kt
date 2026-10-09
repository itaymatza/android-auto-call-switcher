@file:Suppress("UNUSED_PARAMETER")

package android.bluetooth

import android.content.Context

data class BluetoothDevice(
    val address: String,
    val name: String? = "Device",
    val alias: String? = null,
)

interface BluetoothProfile {
    val connectedDevices: List<BluetoothDevice>

    interface ServiceListener {
        fun onServiceConnected(
            profile: Int,
            proxy: BluetoothProfile,
        )

        fun onServiceDisconnected(profile: Int)
    }

    companion object {
        const val HEADSET = 1
        const val HEARING_AID = 21
        const val LE_AUDIO = 22
    }
}

open class BluetoothHeadset : BluetoothProfile {
    var devices = emptyList<BluetoothDevice>()
    var audio = emptySet<String>()
    var read: (() -> List<BluetoothDevice>)? = null
    override val connectedDevices get() = read?.invoke() ?: devices

    fun isAudioConnected(device: BluetoothDevice) = device.address in audio

    companion object {
        const val ACTION_CONNECTION_STATE_CHANGED = "headset.connection"
        const val ACTION_AUDIO_STATE_CHANGED = "headset.audio"
    }
}

class BluetoothLeAudio : BluetoothProfile {
    var devices = emptyList<BluetoothDevice>()
    var groups = emptyMap<String, Int>()
    var leads = emptyMap<Int, BluetoothDevice>()
    override val connectedDevices get() = devices

    fun getGroupId(device: BluetoothDevice) = groups[device.address] ?: -1

    fun getConnectedGroupLeadDevice(group: Int) = leads[group]

    companion object {
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED = "le.connection"
    }
}

class BluetoothHearingAid : BluetoothProfile {
    override val connectedDevices = emptyList<BluetoothDevice>()

    companion object {
        const val ACTION_CONNECTION_STATE_CHANGED = "hearing.connection"
    }
}

class BluetoothManager {
    val adapter = BluetoothAdapter()
}

class BluetoothAdapter {
    var state = 12
    val listeners = mutableMapOf<Int, BluetoothProfile.ServiceListener>()
    val closed = mutableListOf<BluetoothProfile>()

    fun getProfileProxy(
        context: Context,
        listener: BluetoothProfile.ServiceListener,
        profile: Int,
    ): Boolean {
        listeners[profile] = listener
        return true
    }

    fun closeProfileProxy(
        profile: Int,
        proxy: BluetoothProfile,
    ) {
        closed.add(proxy)
    }

    fun connect(
        profile: Int,
        proxy: BluetoothProfile,
    ) {
        listeners.getValue(profile).onServiceConnected(profile, proxy)
    }

    fun disconnect(profile: Int) {
        listeners.getValue(profile).onServiceDisconnected(profile)
    }

    companion object {
        const val ACTION_STATE_CHANGED = "adapter.state"
    }
}
