import android.content.Context
import android.os.TestQueue
import android.telecom.TelecomManager
import org.carcallrouter.companion.telecom.AuthorizationMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class AuthorizationMonitorTest {
    private val context = Context()
    private val worker = ManualWorker()
    private var grant = true
    private var failure = false
    private var changes = 0
    private lateinit var monitor: AuthorizationMonitor

    @Before fun setup() {
        TestQueue.reset()
        monitor = AuthorizationMonitor(context, worker, { if (failure) throw SecurityException() else grant }) { changes++ }
    }

    @After fun close() {
        monitor.close()
        TestQueue.reset()
    }

    private fun complete() {
        worker.runOne()
        TestQueue.runReady()
    }

    @Test fun grantRequiresWorkerAndOwnerPublication() {
        assertNull(monitor.sample())
        worker.runOne()
        assertNull(monitor.sample())
        TestQueue.runReady()
        assertEquals(true, monitor.sample())
    }

    @Test fun denialAndQueryFailureCannotBecomeGrant() {
        grant = false
        monitor.sample()
        complete()
        assertEquals(false, monitor.sample())
        TestQueue.advanceTo(250)
        failure = true
        monitor.sample()
        complete()
        assertNull(monitor.sample())
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["authorization_quality"])
    }

    @Test fun expiredGrantAndStalledResultAreUnknown() {
        monitor.sample()
        complete()
        TestQueue.sleepFor(751)
        assertNull(monitor.sample())
        worker.runOne()
        TestQueue.sleepFor(751)
        TestQueue.runReady()
        assertNull(monitor.sample())
        assertEquals("STALE", monitor.diagnosticFields().toMap()["authorization_quality"])
    }

    @Test fun revokeRegrantRejectsInFlightOldGrant() {
        monitor.sample()
        worker.runOne()
        context.runtimeGranted = false
        assertEquals(false, monitor.sample())
        context.runtimeGranted = true
        TestQueue.runReady()
        assertNull(monitor.sample())
        complete()
        assertEquals(true, monitor.sample())
    }

    @Test fun permissionLossAtCompletionIsNotAValidObservation() {
        monitor.sample()
        worker.runOne()
        context.runtimeGranted = false
        TestQueue.runReady()
        assertEquals("PERMISSION_MISSING", monitor.diagnosticFields().toMap()["authorization_quality"])
        assertFalse(requireNotNull(monitor.sample()))
    }

    @Test fun closeDropsCompletionAndRefreshCannotQueueBehindStall() {
        repeat(100) { assertNull(monitor.sample()) }
        assertEquals(1, worker.size)
        monitor.close()
        complete()
        assertEquals(0, changes)
        assertFalse(requireNotNull(monitor.sample()))
    }

    @Test fun refreshRechecksPermissionWithoutLosingFreshGrantInTheMeantime() {
        monitor.sample()
        complete()
        TestQueue.advanceTo(250)
        grant = false
        assertTrue(requireNotNull(monitor.sample()))
        complete()
        assertFalse(requireNotNull(monitor.sample()))
    }

    @Test fun realProtectedApiBoundaryIsWorkerOnlyAndRechecksDenial() {
        monitor.close()
        val telecom = TelecomManager()
        context.services[TelecomManager::class.java] = telecom
        monitor = AuthorizationMonitor(context, worker) { changes++ }
        assertNull(monitor.sample())
        assertEquals(0, telecom.authorizationQueries)
        complete()
        assertEquals(true, monitor.sample())
        TestQueue.advanceTo(250)
        telecom.authorized = false
        monitor.sample()
        complete()
        assertEquals(false, monitor.sample())
        assertEquals(2, telecom.authorizationQueries)
    }

    @Test fun absentTelecomAndApiFailureDoNotGrant() {
        monitor.close()
        monitor = AuthorizationMonitor(context, worker) { changes++ }
        monitor.sample()
        complete()
        assertEquals(false, monitor.sample())
        val telecom = TelecomManager().apply { authorizationFailure = SecurityException() }
        context.services[TelecomManager::class.java] = telecom
        TestQueue.advanceTo(250)
        monitor.sample()
        complete()
        assertNull(monitor.sample())
    }

    @Test fun processWorkerSaturationCannotGrantOrQueue() {
        monitor.close()
        monitor = AuthorizationMonitor(context, Executor { throw RejectedExecutionException() }, { true }) { changes++ }
        assertNull(monitor.sample())
        assertEquals("ERROR", monitor.diagnosticFields().toMap()["authorization_quality"])
    }

    @Test fun explicitVerificationDiscardsCachedAndInFlightOldGrant() {
        monitor.sample()
        complete()
        assertEquals(true, monitor.sample())
        TestQueue.advanceTo(250)
        monitor.sample()
        worker.runOne()
        grant = false
        monitor.requestRefresh()
        assertNull(monitor.sample())
        TestQueue.runReady()
        assertNull(monitor.sample())
        complete()
        assertEquals(false, monitor.sample())
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
