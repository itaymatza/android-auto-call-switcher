import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.TestQueue
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.telecom.HfpMonitor
import org.carcallrouter.companion.ui.CallDevicePreflight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class BluetoothLifecycleTest {
    private val target = "AA:BB:CC:DD:EE:01"
    private val context = Context()
    private val worker = ManualWorker()
    private val groupWorker = ManualWorker()
    private val metadataWorker = ManualWorker()
    private val powerWorker = ManualWorker()
    private var changes = 0
    private val closeables = mutableListOf<AutoCloseable>()

    @Before fun reset() {
        TestQueue.reset()
        RouterLog.events.clear()
    }

    @After fun close() {
        closeables.forEach { it.close() }
        TestQueue.reset()
    }

    private fun headset() =
        BluetoothHeadset().apply {
            devices = listOf(BluetoothDevice(target))
            audio = setOf(target)
        }

    private fun hfp(proxy: BluetoothHeadset = headset()): HfpMonitor {
        val monitor = HfpMonitor(context, worker, metadataWorker, powerWorker) { changes++ }
        closeables.add(monitor)
        monitor.start()
        context.manager.adapter.connect(BluetoothProfile.HEADSET, proxy)
        TestQueue.runReady()
        return monitor
    }

    private fun preflight(): CallDevicePreflight {
        val monitor = CallDevicePreflight(context, worker, groupWorker) { changes++ }
        closeables.add(monitor)
        monitor.start()
        context.manager.adapter.connect(BluetoothProfile.HEADSET, headset())
        TestQueue.runReady()
        // The initial empty-proxy query was invalidated by the connected callback.
        worker.runOne()
        TestQueue.runReady()
        monitor.refresh()
        return monitor
    }

    @Test fun hfpPublishesOnlyAfterOwnerCompletion() {
        val monitor = hfp()
        assertFalse(monitor.known)
        worker.runOne()
        assertFalse(monitor.known)
        TestQueue.runReady()
        assertTrue(monitor.known)
        assertEquals(setOf(target), monitor.audioConnected)
    }

    @Test fun permissionLostBetweenReadAndCompletionCannotRestoreHfpEvidence() {
        val monitor = hfp()
        worker.runOne()
        context.bluetoothGranted = false
        TestQueue.runReady()
        assertFalse(monitor.known)
        assertTrue(monitor.connected.isEmpty())
        assertTrue(monitor.audioConnected.isEmpty())
        assertNull(monitor.sampledAt)
        assertEquals("PERMISSION_MISSING", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
    }

    @Test fun revokeRefreshThenRegrantCannotAcceptOldHfpCompletion() {
        val monitor = hfp()
        worker.runOne()
        context.bluetoothGranted = false
        monitor.refresh()
        context.bluetoothGranted = true
        TestQueue.runReady()
        assertFalse(monitor.known)
        monitor.refresh()
        worker.runOne()
        TestQueue.runReady()
        assertTrue(monitor.known)
    }

    @Test fun hfpProxyDisconnectClearsTimestampAndRejectsPendingCompletion() {
        val monitor = hfp()
        worker.runOne()
        TestQueue.runReady()
        monitor.refresh()
        context.manager.adapter.disconnect(BluetoothProfile.HEADSET)
        TestQueue.runReady()
        assertNull(monitor.sampledAt)
        worker.runOne()
        TestQueue.runReady()
        assertFalse(monitor.known)
        assertTrue(monitor.connected.isEmpty())
    }

    @Test fun replacedHfpProxyCannotPublishPreviousDevice() {
        val monitor = hfp()
        val replacement = BluetoothHeadset()
        context.manager.adapter.connect(BluetoothProfile.HEADSET, replacement)
        TestQueue.runReady()
        worker.runOne()
        TestQueue.runReady()
        assertFalse(monitor.known)
        monitor.refresh()
        worker.runOne()
        TestQueue.runReady()
        assertTrue(monitor.known)
        assertTrue(monitor.connected.isEmpty())
    }

    @Test fun slowHfpQueryAndServiceFailureRemainUnknown() {
        val proxy = headset()
        val monitor = hfp(proxy)
        proxy.read = {
            TestQueue.sleepFor(751)
            proxy.devices
        }
        worker.runOne()
        TestQueue.runReady()
        assertFalse(monitor.known)
        proxy.read = { throw SecurityException("permission lost") }
        monitor.refresh()
        worker.runOne()
        TestQueue.runReady()
        assertFalse(monitor.known)
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["hfp_sample_quality"])
    }

    @Test fun repeatedRefreshDoesNotQueueBehindPendingBinderRead() {
        val monitor = hfp()
        repeat(100) { monitor.refresh() }
        assertEquals(1, worker.size)
        monitor.close()
        val before = changes
        worker.runOne()
        TestQueue.runReady()
        assertEquals(before, changes)
        assertNull(context.receiver)
    }

    @Test fun lateProxyCallbackAfterCloseIsReleased() {
        val monitor = hfp()
        monitor.close()
        val late = headset()
        context.manager.adapter.connect(BluetoothProfile.HEADSET, late)
        TestQueue.runReady()
        assertTrue(late in context.manager.adapter.closed)
    }

    @Test fun preflightPermissionLossRejectsCompletedConnectionRead() {
        val monitor = preflight()
        worker.runOne()
        context.bluetoothGranted = false
        TestQueue.runReady()
        assertNull(monitor.connections[BluetoothProfile.HEADSET])
    }

    @Test fun preflightEvidenceExpiresDuringDeviceSleep() {
        val monitor = preflight()
        worker.runOne()
        TestQueue.runReady()
        assertEquals(setOf(target), monitor.connections[BluetoothProfile.HEADSET])
        TestQueue.sleepFor(10_001)
        assertNull(monitor.connections[BluetoothProfile.HEADSET])
    }

    @Test fun stalledLeGroupReadDoesNotBlockClassicInspection() {
        val monitor = preflight()
        val le =
            BluetoothLeAudio().apply {
                devices = listOf(BluetoothDevice(target))
                groups = mapOf(target to 7)
                leads = mapOf(7 to devices.single())
            }
        context.manager.adapter.connect(BluetoothProfile.LE_AUDIO, le)
        TestQueue.runReady()
        worker.runOne()
        TestQueue.runReady()
        monitor.refresh()
        worker.runOne()
        TestQueue.runReady()
        assertEquals(setOf(target), monitor.connections[BluetoothProfile.HEADSET])
        assertNull(monitor.leGroup(target))
        assertEquals(1, groupWorker.size)
        repeat(20) { monitor.refresh() }
        assertEquals(1, groupWorker.size)
        groupWorker.runOne()
        TestQueue.runReady()
        assertEquals(setOf(target), monitor.leGroup(target)?.members)
    }

    @Test fun permissionLossRejectsLeGroupCompletionAndCloseCancelsPolling() {
        val monitor = preflight()
        val le =
            BluetoothLeAudio().apply {
                devices = listOf(BluetoothDevice(target))
                groups = mapOf(target to 9)
                leads = mapOf(9 to devices.single())
            }
        context.manager.adapter.connect(BluetoothProfile.LE_AUDIO, le)
        TestQueue.runReady()
        groupWorker.runOne()
        context.bluetoothGranted = false
        TestQueue.runReady()
        assertNull(monitor.leGroup(target))
        monitor.close()
        val before = changes
        worker.runOne()
        TestQueue.advanceTo(30_000)
        assertEquals(before, changes)
        assertNull(context.receiver)
    }

    private class ManualWorker : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runOne() {
            tasks.remove().run()
        }
    }
}
