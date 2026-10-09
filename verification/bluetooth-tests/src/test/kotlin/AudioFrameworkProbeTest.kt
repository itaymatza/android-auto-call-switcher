import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.TestQueue
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.telecom.AudioFrameworkProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class AudioFrameworkProbeTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(task: Runnable) {
            tasks.add(task)
        }

        fun complete() {
            tasks.removeFirst().run()
            TestQueue.runReady()
        }
    }

    private lateinit var context: Context
    private lateinit var mode: Worker
    private lateinit var inventory: Worker
    private lateinit var probe: AudioFrameworkProbe

    @Before fun setup() {
        TestQueue.reset()
        RouterLog.events.clear()
        context = Context()
        mode = Worker()
        inventory = Worker()
        probe = AudioFrameworkProbe(context, mode, inventory)
    }

    @Test fun readsAreOffOwnerAndInventoryCannotDelayMode() {
        var reads = 0
        context.audioManager!!.readMode = {
            reads++
            AudioManager.MODE_IN_CALL
        }
        assertEquals("UNSAMPLED", probe.sample().quality)
        assertEquals(0, reads)
        mode.complete()
        val state = probe.sample()
        assertEquals("IN_CALL", state.mode)
        assertEquals("OBSERVED", state.quality)
        assertEquals("UNSAMPLED", state.diagnosticQuality)
        assertEquals("UNKNOWN", state.communicationDevice)
        assertEquals(1, inventory.tasks.size)
    }

    @Test fun eachOptionalFailureLeavesModeEvidenceIntact() {
        for (operation in 0..3) {
            setup()
            context.audioManager!!.apply {
                when (operation) {
                    0 -> readCommunication = { throw SecurityException() }
                    1 -> readDevices = { throw SecurityException() }
                    2 -> readAvailable = { throw SecurityException() }
                    3 -> readMute = { throw SecurityException() }
                }
            }
            probe.sample()
            mode.complete()
            inventory.complete()
            val state = probe.sample()
            assertEquals("IN_CALL", state.mode)
            assertEquals("OBSERVED", state.quality)
            assertEquals("ERROR", state.diagnosticQuality)
            assertEquals(
                listOf("communication_device", "device_inventory", "available_communication_devices", "microphone_mute")[operation],
                state.diagnosticFailedOperation,
            )
            assertEquals("UNKNOWN", state.communicationDevice)
            assertFalse(RouterLog.events.any { it.first == "AUDIO_DEVICE_INVENTORY" })
        }
    }

    @Test fun freshInventoryCannotRescueFailedMode() {
        context.audioManager!!.readMode = { throw SecurityException() }
        probe.sample()
        mode.complete()
        inventory.complete()
        val state = probe.sample()
        assertEquals("UNKNOWN", state.mode)
        assertEquals("ERROR", state.quality)
        assertEquals("mode", state.failedOperation)
        assertEquals("BLUETOOTH_SCO", state.communicationDevice)
        assertEquals("OBSERVED", state.diagnosticQuality)
    }

    @Test fun staleInventoryExpiresIndependentlyOfMode() {
        probe.sample()
        inventory.complete()
        TestQueue.sleepFor(751)
        mode.complete()
        val state = probe.sample()
        assertEquals("IN_CALL", state.mode)
        assertEquals("OBSERVED", state.quality)
        assertEquals("UNKNOWN", state.communicationDevice)
        assertEquals("STALE", state.diagnosticQuality)
    }

    @Test fun staleModeExpiresIndependentlyOfInventory() {
        probe.sample()
        mode.complete()
        TestQueue.sleepFor(751)
        inventory.complete()
        val state = probe.sample()
        assertEquals("UNKNOWN", state.mode)
        assertEquals("STALE", state.quality)
        assertEquals("BLUETOOTH_SCO", state.communicationDevice)
    }

    @Test fun slowAndOwnerDelayedResultsCannotPublishFreshEvidence() {
        for (diagnostic in listOf(false, true)) {
            for (delayOnOwner in listOf(false, true)) {
                setup()
                if (!delayOnOwner) {
                    if (diagnostic) {
                        context.audioManager!!.readCommunication = {
                            TestQueue.sleepFor(751)
                            AudioDeviceInfo()
                        }
                    } else {
                        context.audioManager!!.readMode = {
                            TestQueue.sleepFor(751)
                            AudioManager.MODE_IN_CALL
                        }
                    }
                }
                probe.sample()
                val worker = if (diagnostic) inventory else mode
                worker.tasks.removeFirst().run()
                if (delayOnOwner) TestQueue.sleepFor(751)
                TestQueue.runReady()
                val state = probe.sample()
                assertEquals("STALE", if (diagnostic) state.diagnosticQuality else state.quality)
                assertEquals("UNKNOWN", if (diagnostic) state.communicationDevice else state.mode)
                if (diagnostic) assertFalse(RouterLog.events.any { it.first == "AUDIO_DEVICE_INVENTORY" })
            }
        }
    }

    @Test fun repeatedPollingCannotQueueBehindEitherStuckWorker() {
        repeat(30) {
            probe.sample()
            TestQueue.sleepFor(500)
        }
        assertEquals(1, mode.tasks.size)
        assertEquals(1, inventory.tasks.size)
        assertEquals("UNKNOWN", probe.sample().mode)
    }

    @Test fun saturatedWorkersFailIndependentlyWithoutQueue() {
        val rejected = Executor { throw RejectedExecutionException() }
        probe = AudioFrameworkProbe(context, mode, rejected)
        probe.sample()
        mode.complete()
        assertEquals("IN_CALL", probe.sample().mode)
        assertEquals("ERROR", probe.sample().diagnosticQuality)
        probe.close()
        probe = AudioFrameworkProbe(context, rejected, inventory)
        probe.sample()
        inventory.complete()
        assertEquals("ERROR", probe.sample().quality)
        assertEquals("OBSERVED", probe.sample().diagnosticQuality)
    }

    @Test fun closeDropsBothLateResultsAndDisallowsCachedEvidence() {
        probe.sample()
        mode.tasks.removeFirst().run()
        inventory.tasks.removeFirst().run()
        probe.close()
        TestQueue.runReady()
        assertTrue(RouterLog.events.isEmpty())
        repeat(2) {
            val state = probe.sample()
            assertEquals("CLOSED", state.quality)
            assertEquals("CLOSED", state.diagnosticQuality)
            assertEquals("UNKNOWN", state.mode)
            assertEquals("UNKNOWN", state.communicationDevice)
        }
        assertTrue(mode.tasks.isEmpty())
        assertTrue(inventory.tasks.isEmpty())
        probe.close()
    }

    @Test fun closeClearsAlreadyPublishedPositiveEvidence() {
        probe.sample()
        mode.complete()
        inventory.complete()
        assertEquals("IN_CALL", probe.sample().mode)
        probe.close()
        assertEquals("CLOSED", probe.sample().quality)
        assertEquals("UNKNOWN", probe.sample().mode)
    }

    @Test fun missingManagerIsUnavailable() {
        context.audioManager = null
        probe = AudioFrameworkProbe(context, mode, inventory)
        probe.sample()
        mode.complete()
        inventory.complete()
        assertEquals("UNAVAILABLE", probe.sample().quality)
        assertEquals("UNAVAILABLE", probe.sample().diagnosticQuality)
    }

    @Test fun modesAndDeviceTypesRemainDiagnosticOnly() {
        val modes = listOf(2 to "IN_CALL", 3 to "IN_COMMUNICATION", 5 to "CALL_REDIRECT", 0 to "NORMAL", 1 to "RINGTONE", 99 to "OTHER")
        val devices = listOf(7 to "BLUETOOTH_SCO", 26 to "BLE_HEADSET", 27 to "BLE_SPEAKER", 2 to "SPEAKER", 1 to "EARPIECE", 99 to "OTHER")
        for (index in modes.indices) {
            setup()
            context.audioManager!!.readMode = { modes[index].first }
            context.audioManager!!.readCommunication = { AudioDeviceInfo(type = devices[index].first) }
            probe.sample()
            mode.complete()
            inventory.complete()
            assertEquals(modes[index].second, probe.sample().mode)
            assertEquals(devices[index].second, probe.sample().communicationDevice)
            val logged = RouterLog.events.single { it.first == "AUDIO_DEVICE_INVENTORY" }.second
            assertFalse(logged.contains("AA:BB:CC:DD:EE:FF"))
            assertTrue(logged.contains("physicalInputVerified=false; physicalOutputVerified=false"))
        }
    }

    @Test fun missingDevicesAndBlankAddressesAreExplicitAndInventoryIsDeduplicated() {
        context.audioManager!!.apply {
            readCommunication = { null }
            readDevices = { arrayOf(AudioDeviceInfo(address = "")) }
            readAvailable = { emptyList() }
        }
        probe.sample()
        mode.complete()
        inventory.complete()
        assertEquals("UNKNOWN", probe.sample().communicationDevice)
        TestQueue.sleepFor(250)
        probe.sample()
        mode.complete()
        inventory.complete()
        val events = RouterLog.events.filter { it.first == "AUDIO_DEVICE_INVENTORY" }
        assertEquals(1, events.size)
        assertTrue(events.single().second.contains("address=UNAVAILABLE"))
    }

    @Test fun pendingRefreshKeepsOnlyUnexpiredCachedEvidence() {
        probe.sample()
        mode.complete()
        inventory.complete()
        TestQueue.sleepFor(250)
        assertEquals("OBSERVED", probe.sample().quality)
        assertEquals(1, mode.tasks.size)
        assertEquals(1, inventory.tasks.size)
        TestQueue.sleepFor(500)
        assertEquals("OBSERVED", probe.sample().quality)
        assertEquals("OBSERVED", probe.sample().diagnosticQuality)
        TestQueue.sleepFor(1)
        assertEquals("STALE", probe.sample().quality)
        assertEquals("STALE", probe.sample().diagnosticQuality)
        assertEquals("UNKNOWN", probe.sample().mode)
        assertEquals("UNKNOWN", probe.sample().communicationDevice)
        assertEquals(1, mode.tasks.size)
        assertEquals(1, inventory.tasks.size)
    }

    @Test fun negativeObservationAgeFailsClosed() {
        probe.sample()
        mode.complete()
        inventory.complete()
        assertEquals("STALE", probe.sample(-1).quality)
        assertEquals("STALE", probe.sample(-1).diagnosticQuality)
        assertEquals("UNKNOWN", probe.sample(-1).communicationDevice)
    }
}
