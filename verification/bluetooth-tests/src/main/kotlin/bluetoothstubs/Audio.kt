package android.media

class AudioDeviceInfo(
    val id: Int = 1,
    val type: Int = TYPE_BLUETOOTH_SCO,
    val isSource: Boolean = true,
    val isSink: Boolean = true,
    val address: String = "AA:BB:CC:DD:EE:FF",
) {
    companion object {
        const val TYPE_BLUETOOTH_SCO = 7
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
        const val TYPE_BUILTIN_SPEAKER = 2
        const val TYPE_BUILTIN_EARPIECE = 1
    }
}

class AudioManager {
    var readMode: () -> Int = { MODE_IN_CALL }
    var readCommunication: () -> AudioDeviceInfo? = { AudioDeviceInfo() }
    var readDevices: () -> Array<AudioDeviceInfo> = { arrayOf(AudioDeviceInfo()) }
    var readAvailable: () -> List<AudioDeviceInfo> = { listOf(AudioDeviceInfo()) }
    var readMute: () -> Boolean = { false }
    val mode get() = readMode()
    val communicationDevice get() = readCommunication()
    val availableCommunicationDevices get() = readAvailable()
    val isMicrophoneMute get() = readMute()

    @Suppress("UNUSED_PARAMETER")
    fun getDevices(flags: Int) = readDevices()

    companion object {
        const val MODE_NORMAL = 0
        const val MODE_RINGTONE = 1
        const val MODE_IN_CALL = 2
        const val MODE_IN_COMMUNICATION = 3
        const val MODE_CALL_REDIRECT = 5
        const val GET_DEVICES_INPUTS = 1
        const val GET_DEVICES_OUTPUTS = 2
    }
}
