import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.TestQueue
import org.carcallrouter.companion.ProjectionMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ProjectionMonitorTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(task: Runnable) {
            tasks.add(task)
        }

        fun run() {
            tasks.removeFirst().run()
        }
    }

    private lateinit var context: Context
    private lateinit var worker: Worker
    private lateinit var monitor: ProjectionMonitor
    private val observed = mutableListOf<Boolean?>()

    @Before fun setup() {
        TestQueue.reset()
        context = Context()
        worker = Worker()
        observed.clear()
        monitor = ProjectionMonitor(context, worker) { observed.add(it) }
    }

    private fun start() {
        monitor.start()
        TestQueue.runReady()
    }

    private fun complete() {
        worker.run()
        TestQueue.runReady()
    }

    private fun status() = monitor.diagnosticFields().toMap()["projection_status"]

    private fun broadcast() {
        context.receiver!!.onReceive(context, Intent("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"))
        TestQueue.runReady()
    }

    @Test fun providerAndCursorAccessRunOnlyInWorker() {
        val cursor = Cursor()
        var read = false
        cursor.onRead = { read = true }
        context.contentResolver.read = { cursor }
        start()
        assertEquals(0, context.contentResolver.queries)
        assertFalse(read)
        worker.run()
        assertTrue(read)
        assertTrue(cursor.closed)
        assertTrue(observed.isEmpty())
        TestQueue.runReady()
        assertEquals(listOf(true), observed)
    }

    @Test fun disconnectedAndUnknownStatesNeverAuthorize() {
        for (state in listOf(0, 1, 8)) {
            setup()
            context.contentResolver.read = { Cursor(state) }
            start()
            complete()
            assertEquals(if (state == 8) null else false, observed.single())
        }
    }

    @Test fun emptyProviderAndMissingColumnStayUnknown() {
        for (cursor in listOf(null, Cursor(column = -1), Cursor(hasRow = false))) {
            setup()
            context.contentResolver.read = { cursor }
            start()
            complete()
            assertNull(observed.single())
            cursor?.let { assertTrue(it.closed) }
        }
    }

    @Test fun cursorFailureStillClosesAndFailsClosed() {
        val cursor = Cursor().apply { onRead = { throw SecurityException() } }
        context.contentResolver.read = { cursor }
        start()
        complete()
        assertTrue(cursor.closed)
        assertNull(observed.single())
        assertEquals("ERROR", status())
    }

    @Test fun delayedPositiveCannotBecomeFreshEvidence() {
        context.contentResolver.read = {
            TestQueue.sleepFor(751)
            Cursor()
        }
        start()
        complete()
        assertNull(observed.single())
        assertEquals("STALE", status())
    }

    @Test fun ownerDeliveryDelayAlsoInvalidatesPositive() {
        context.contentResolver.read = { Cursor() }
        start()
        worker.run()
        TestQueue.sleepFor(751)
        TestQueue.runReady()
        assertNull(observed.single())
        assertEquals("STALE", status())
    }

    @Test fun broadcastsInvalidateInFlightPositiveAndRequery() {
        context.contentResolver.read = { Cursor() }
        start()
        broadcast()
        assertEquals(listOf<Boolean?>(null), observed)
        assertEquals(1, worker.tasks.size)
        complete()
        assertEquals(listOf<Boolean?>(null), observed)
        assertEquals(1, worker.tasks.size)
        context.contentResolver.read = { Cursor(0) }
        complete()
        assertEquals(listOf(null, false), observed)
    }

    @Test fun repeatedRefreshesCannotQueueBehindStuckRead() {
        start()
        repeat(20) {
            TestQueue.advanceTo(TestQueue.now + 500)
            monitor.requestRefresh()
            TestQueue.runReady()
        }
        assertEquals(1, worker.tasks.size)
        assertTrue(observed.isEmpty())
    }

    @Test fun closeDropsLateResultAndUnregistersReceiver() {
        val cursor = Cursor()
        context.contentResolver.read = { cursor }
        start()
        monitor.close()
        complete()
        assertTrue(cursor.closed)
        assertTrue(observed.isEmpty())
        assertNull(context.receiver)
        monitor.requestRefresh()
        TestQueue.runReady()
        assertTrue(worker.tasks.isEmpty())
    }

    @Test fun saturatedProcessWorkerFailsClosedWithoutGrowingQueue() {
        monitor = ProjectionMonitor(context, Executor { throw RejectedExecutionException() }) { observed.add(it) }
        start()
        assertNull(observed.single())
        assertEquals("ERROR", status())
        assertEquals(false, monitor.diagnosticFields().toMap()["projection_query_pending"])
    }
}
