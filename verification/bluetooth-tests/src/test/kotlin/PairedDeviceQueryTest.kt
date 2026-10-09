import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.TestQueue
import org.carcallrouter.companion.ui.PairedDeviceQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PairedDeviceQueryTest {
    private val context = Context()
    private val work = ArrayDeque<Runnable>()
    private val results = mutableListOf<List<PairedDeviceQuery.Device>?>()
    private val query = PairedDeviceQuery(context, Executor { work.add(it) }) { results.add(it) }

    @Before fun reset() = TestQueue.reset()

    private fun complete() {
        work.removeFirst().run()
        TestQueue.runReady()
    }

    @Test fun namesAndPairedDeviceReadsStayOffOwnerAndReturnImmutableChoices() {
        var reads = 0
        context.manager.adapter.readBonded = {
            reads++
            setOf(BluetoothDevice("02:00:00:00:00:01", "Name", "Alias"), BluetoothDevice("02:00:00:00:00:02", null))
        }
        assertTrue(query.load())
        assertEquals(0, reads)
        assertFalse(query.load())
        complete()
        assertEquals(1, reads)
        assertEquals(listOf("Alias", "Unnamed Bluetooth device"), results.single()!!.map { it.label })
        TestQueue.advanceTo(3000)
        assertEquals(1, results.size)
        query.close()
    }

    @Test fun timeoutKeepsOneWorkerSlotAndDiscardsLateChoices() {
        query.load()
        TestQueue.advanceTo(3000)
        assertNull(results.single())
        assertFalse(query.load())
        complete()
        assertEquals(1, results.size)
        assertTrue(query.load())
        complete()
        assertEquals(emptyList<PairedDeviceQuery.Device>(), results.last())
        query.close()
    }

    @Test fun permissionLossCannotReturnUsableChoices() {
        query.load()
        work.removeFirst().run()
        context.bluetoothGranted = false
        TestQueue.runReady()
        assertNull(results.single())
        query.load()
        complete()
        assertNull(results.last())
        query.close()
    }

    @Test fun closingScreenDropsCallbacksAndDeadline() {
        query.load()
        query.close()
        complete()
        TestQueue.advanceTo(5000)
        assertTrue(results.isEmpty())
        assertFalse(query.load())
    }

    @Test fun saturatedWorkerReportsFailureOnceWithoutTimeoutDuplicate() {
        val rejected = PairedDeviceQuery(context, Executor { throw RejectedExecutionException() }) { results.add(it) }
        assertTrue(rejected.load())
        assertNull(results.single())
        TestQueue.advanceTo(5000)
        assertEquals(1, results.size)
        rejected.close()
    }
}
