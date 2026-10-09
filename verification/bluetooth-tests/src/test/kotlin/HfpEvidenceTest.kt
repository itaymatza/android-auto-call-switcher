import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.TestQueue
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.telecom.HfpMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class HfpEvidenceTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()

        fun run() = tasks.removeFirst().run()

        fun complete() {
            run()
            TestQueue.runReady()
        }

        override fun execute(command: Runnable) {
            tasks.add(command)
        }
    }

    private val address = "AA:BB:CC:DD:EE:01"
    private lateinit var context: Context
    private lateinit var critical: Worker
    private lateinit var labels: Worker
    private lateinit var power: Worker
    private lateinit var device: BluetoothDevice
    private lateinit var proxy: BluetoothHeadset
    private lateinit var monitor: HfpMonitor
    private var changes = 0

    @Before fun setup() {
        TestQueue.reset()
        RouterLog.events.clear()
        changes = 0
        context = Context()
        critical = Worker()
        labels = Worker()
        power = Worker()
        device = BluetoothDevice(address, "Private name", "Private alias")
        proxy =
            BluetoothHeadset().apply {
                devices = listOf(device)
                audio = setOf(address)
            }
        monitor = HfpMonitor(context, critical, labels, power) { changes++ }
    }

    @After fun close() {
        monitor.close()
        TestQueue.reset()
    }

    private fun start() {
        monitor.start()
        context.manager.adapter.connect(BluetoothProfile.HEADSET, proxy)
        TestQueue.runReady()
    }

    private fun broadcast(action: String = BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
        context.receiver!!.onReceive(context, Intent(action))
    }

    @Test fun namesAndPowerCannotBlockFreshExactScoEvidence() {
        start()
        critical.complete()
        assertTrue(monitor.known)
        assertEquals(setOf(address), monitor.audioConnected)
        assertFalse(monitor.labelsKnown)
        assertEquals(1, labels.tasks.size)
        assertEquals(1, power.tasks.size)
        repeat(8) {
            TestQueue.sleepFor(250)
            monitor.refresh()
            critical.complete()
            assertTrue(monitor.known)
            assertEquals(setOf(address), monitor.audioConnected)
        }
        assertEquals(1, labels.tasks.size)
        assertEquals(1, power.tasks.size)
    }

    @Test fun stalledPowerCannotBlockCompleteLiveIdentity() {
        start()
        critical.complete()
        labels.complete()
        assertTrue(monitor.labelsKnown)
        assertEquals(setOf("Private name", "Private alias"), monitor.deviceLabels[address])
        assertEquals(1, power.tasks.size)
        assertTrue(RouterLog.events.none { "Private name" in it.second || "Private alias" in it.second || address in it.second })
    }

    @Test fun nameAndAliasErrorsDoNotPoisonScoAndCannotMapIdentity() {
        for (alias in listOf(false, true)) {
            if (alias) device.readAlias = { throw SecurityException() } else device.readName = { throw SecurityException() }
            start()
            critical.complete()
            labels.complete()
            assertTrue(monitor.known)
            assertEquals(setOf(address), monitor.audioConnected)
            assertFalse(monitor.labelsKnown)
            assertTrue(monitor.deviceLabels.isEmpty())
            assertEquals("ERROR", monitor.diagnosticFields().toMap()["hfp_labels_quality"])
            monitor.close()
            setup()
        }
    }

    @Test fun powerErrorsAreDiagnosticAndCannotEraseIdentity() {
        context.manager.adapter.readState = { throw SecurityException() }
        start()
        critical.complete()
        labels.complete()
        power.complete()
        assertTrue(monitor.known)
        assertTrue(monitor.labelsKnown)
        assertTrue(RouterLog.events.any { it.first == "BLUETOOTH_ADAPTER_SNAPSHOT" && "state=UNKNOWN" in it.second })
    }

    @Test fun broadcastDropsAlreadyReadScoAndRequeriesWhenItsSlotDrains() {
        start()
        critical.run()
        broadcast()
        proxy.audio = emptySet()
        assertFalse(monitor.known)
        TestQueue.runReady()
        assertFalse(monitor.known)
        assertEquals(1, critical.tasks.size)
        assertTrue(labels.tasks.isEmpty())
        critical.complete()
        assertTrue(monitor.known)
        assertTrue(monitor.audioConnected.isEmpty())
        assertTrue(RouterLog.events.any { it.first == "HFP_RESULT_IGNORED" })
    }

    @Test fun cachedEvidenceIsClearedByEveryRelevantEvent() {
        for (action in listOf(
            BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothDevice.ACTION_NAME_CHANGED,
            BluetoothDevice.ACTION_ALIAS_CHANGED,
        )) {
            start()
            critical.complete()
            labels.complete()
            broadcast(action)
            assertFalse(monitor.known)
            assertTrue(monitor.connected.isEmpty())
            assertTrue(monitor.audioConnected.isEmpty())
            assertTrue(monitor.deviceLabels.isEmpty())
            assertNull(monitor.sampledAt)
            monitor.close()
            setup()
        }
    }

    @Test fun oldLabelAndPowerResultsCannotSurviveAnEvent() {
        start()
        critical.complete()
        labels.run()
        power.run()
        broadcast(BluetoothDevice.ACTION_ALIAS_CHANGED)
        device.readAlias = { "New alias" }
        TestQueue.runReady()
        assertFalse(monitor.labelsKnown)
        assertTrue(RouterLog.events.none { it.first == "BLUETOOTH_ADAPTER_SNAPSHOT" })
        critical.complete()
        labels.complete()
        power.complete()
        assertEquals(setOf("Private name", "New alias"), monitor.deviceLabels[address])
    }

    @Test fun obsoleteErrorsAlsoTriggerFreshReadAfterProxyReplacement() {
        proxy.read = { throw SecurityException() }
        start()
        val replacement =
            BluetoothHeadset().apply {
                devices = listOf(device)
                audio = setOf(address)
            }
        context.manager.adapter.connect(BluetoothProfile.HEADSET, replacement)
        TestQueue.runReady()
        critical.complete()
        assertFalse(monitor.known)
        assertEquals(1, critical.tasks.size)
        critical.complete()
        assertTrue(monitor.known)
        assertTrue(proxy in context.manager.adapter.closed)
    }

    @Test fun repeatedEventsCannotQueueBehindAStuckRead() {
        start()
        repeat(50) { broadcast() }
        assertFalse(monitor.known)
        assertEquals(1, critical.tasks.size)
        critical.complete()
        assertEquals(1, critical.tasks.size)
        critical.complete()
        assertTrue(monitor.known)
    }

    @Test fun permissionLossInOptionalCompletionInvalidatesCachedAndInFlightSco() {
        start()
        critical.complete()
        monitor.refresh()
        context.bluetoothGranted = false
        labels.complete()
        context.bluetoothGranted = true
        assertFalse(monitor.known)
        assertNull(monitor.sampledAt)
        critical.complete()
        assertFalse(monitor.known)
        critical.complete()
        assertTrue(monitor.known)
    }

    @Test fun powerCompletionAlsoInvalidatesRevokedPermissionEvidence() {
        start()
        critical.complete()
        context.bluetoothGranted = false
        power.complete()
        context.bluetoothGranted = true
        assertFalse(monitor.known)
        assertNull(monitor.sampledAt)
    }

    @Test fun audioGetterFailureCannotPublishPartialPositives() {
        val other = BluetoothDevice("AA:BB:CC:DD:EE:02")
        proxy.devices = listOf(device, other)
        proxy.readAudio = { if (it === other) throw SecurityException() else true }
        start()
        critical.complete()
        assertFalse(monitor.known)
        assertTrue(monitor.audioConnected.isEmpty())
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
    }

    @Test fun labelAndScoFreshnessExpireIndependentlyIncludingSleep() {
        start()
        critical.complete()
        labels.complete()
        TestQueue.sleepFor(750)
        assertTrue(monitor.known)
        assertTrue(monitor.labelsKnown)
        TestQueue.sleepFor(1)
        assertFalse(monitor.known)
        assertTrue(monitor.audioConnected.isEmpty())
        monitor.refresh()
        critical.complete()
        assertTrue(monitor.known)
        assertFalse(monitor.labelsKnown)
        assertTrue(monitor.deviceLabels.isEmpty())
        assertEquals("STALE", monitor.diagnosticFields().toMap()["hfp_labels_quality"])
    }

    @Test fun lateOptionalResultCannotBePublishedAsFreshIdentity() {
        start()
        critical.complete()
        labels.run()
        TestQueue.sleepFor(751)
        monitor.refresh()
        critical.run()
        TestQueue.runReady()
        assertTrue(monitor.known)
        assertFalse(monitor.labelsKnown)
        assertEquals("STALE", monitor.diagnosticFields().toMap()["hfp_labels_quality"])
    }

    @Test fun unnamedOrPartiallyNamedDevicesCannotProveMultiDeviceIdentity() {
        device.readName = { null }
        device.readAlias = { " " }
        proxy.devices = listOf(device, BluetoothDevice("AA:BB:CC:DD:EE:02", "Other"))
        start()
        critical.complete()
        labels.complete()
        assertTrue(monitor.known)
        assertFalse(monitor.labelsKnown)
    }

    @Test fun membershipChangeWithoutBroadcastRejectsPreviousLabels() {
        start()
        critical.complete()
        proxy.devices = listOf(BluetoothDevice("AA:BB:CC:DD:EE:02", "Other"))
        monitor.refresh()
        critical.run()
        labels.run()
        // Deliver the newer critical completion first, then the older metadata result.
        TestQueue.runReady()
        assertFalse(monitor.labelsKnown)
        labels.complete()
        assertTrue(monitor.labelsKnown)
        assertEquals(setOf("AA:BB:CC:DD:EE:02"), monitor.deviceLabels.keys)
    }

    @Test fun saturatedWorkersFailIndependentlyAndBoundWork() {
        val rejected = Executor { throw RejectedExecutionException() }
        monitor = HfpMonitor(context, critical, rejected, rejected) { changes++ }
        start()
        critical.complete()
        assertTrue(monitor.known)
        assertFalse(monitor.labelsKnown)
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["hfp_labels_quality"])
        monitor.close()
        monitor = HfpMonitor(context, rejected, labels, power) { changes++ }
        start()
        assertFalse(monitor.known)
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
    }

    @Test fun closeClearsPositiveEvidenceAndSuppressesAllLateLogging() {
        start()
        critical.complete()
        labels.complete()
        monitor.refresh()
        critical.run()
        power.run()
        monitor.close()
        val before = RouterLog.events.size
        val callbacks = changes
        TestQueue.runReady()
        assertEquals(before, RouterLog.events.size)
        assertEquals(callbacks, changes)
        assertFalse(monitor.known)
        assertTrue(monitor.connected.isEmpty())
        assertTrue(monitor.deviceLabels.isEmpty())
        assertNull(monitor.sampledAt)
        assertEquals("CLOSED", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
        monitor.start()
        monitor.refresh()
        monitor.close()
        assertTrue(critical.tasks.isEmpty())
        assertNull(context.receiver)
    }

    @Test fun startWithoutPermissionAndRefreshWithoutProxyStayUnknown() {
        context.bluetoothGranted = false
        monitor.start()
        assertEquals("PERMISSION_MISSING", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
        context.bluetoothGranted = true
        monitor.refresh()
        assertEquals("PROXY_UNAVAILABLE", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
        assertFalse(monitor.known)
        assertTrue(critical.tasks.isEmpty())
    }
}
