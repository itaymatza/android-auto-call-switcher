import android.content.Context
import android.net.Uri
import android.os.TestQueue
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.carcallrouter.companion.core.CallSafety
import org.carcallrouter.companion.telecom.CellularClassifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class AsyncClassifierTest {
    private val context = Context()
    private val telecom = TelecomManager()
    private val telephony = TelephonyManager()
    private val call = Call(Call.Details())
    private val worker = ManualWorker()
    private var changes = 0
    private lateinit var classifier: CellularClassifier

    @Before fun setup() {
        TestQueue.reset()
        context.services[TelecomManager::class.java] = telecom
        context.services[TelephonyManager::class.java] = telephony
        PhoneNumberUtils.extracted = "5550100"
        classifier = CellularClassifier(context, worker) { changes++ }
    }

    @After fun close() {
        classifier.close()
        TestQueue.reset()
    }

    private fun complete() {
        worker.runOne()
        TestQueue.runReady()
    }

    @Test fun pendingCannotPublishBeforeWorkerAndOwnerComplete() {
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        assertEquals(0, telecom.queries)
        worker.runOne()
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        TestQueue.runReady()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
        assertEquals(1, changes)
    }

    @Test fun anotherCallCannotBorrowPendingOrVerifiedEvidence() {
        classifier.assess(call)
        val another = Call(Call.Details())
        classifier.assess(another)
        complete()
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(another))
        complete()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(another))
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
    }

    @Test fun changedHandleInvalidatesPendingCompletion() {
        classifier.assess(call)
        requireNotNull(call.details).handle = Uri("tel", "5550200")
        classifier.assess(call)
        complete()
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        complete()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
    }

    @Test fun newScopeFlagsOverridePendingAndCachedAcceptanceImmediately() {
        classifier.assess(call)
        complete()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
        TestQueue.advanceTo(250)
        classifier.assess(call)
        requireNotNull(call.details).properties = Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL
        val expected = CallSafety.Assessment.Unsafe("Network-identified emergency call")
        assertEquals(expected, classifier.assess(call))
        complete()
        assertEquals(expected, classifier.assess(call))
    }

    @Test fun stateOnlyDetailsReplacementRetainsFreshAccountAndHandleEvidence() {
        classifier.assess(call)
        complete()
        call.details = Call.Details()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
        assertEquals(0, worker.size)
        assertEquals(1, telecom.queries)
    }

    @Test fun cachedEvidenceExpiresDuringSleep() {
        classifier.assess(call)
        complete()
        TestQueue.sleepFor(CellularClassifier.MAX_EVIDENCE_AGE_MS + 1)
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        complete()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
    }

    @Test fun delayedOwnerCompletionCannotTurnOldReadIntoFreshEvidence() {
        classifier.assess(call)
        worker.runOne()
        TestQueue.sleepFor(CellularClassifier.MAX_EVIDENCE_AGE_MS + 1)
        TestQueue.runReady()
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        assertEquals("STALE", classifier.diagnosticFields().toMap()["call_safety_quality"])
    }

    @Test fun revokeRegrantCannotRestoreOldCompletion() {
        classifier.assess(call)
        worker.runOne()
        context.runtimeGranted = false
        assertTrue(classifier.assess(call) is CallSafety.Assessment.Unsafe)
        context.runtimeGranted = true
        TestQueue.runReady()
        assertEquals(CallSafety.Assessment.Pending, classifier.assess(call))
        complete()
        assertEquals(CallSafety.Assessment.Safe, classifier.assess(call))
    }

    @Test fun permissionLossAtCompletionIsRejectedWithoutRefresh() {
        classifier.assess(call)
        worker.runOne()
        context.runtimeGranted = false
        TestQueue.runReady()
        assertEquals("PERMISSION_MISSING", classifier.diagnosticFields().toMap()["call_safety_quality"])
        assertTrue(classifier.assess(call) is CallSafety.Assessment.Unsafe)
    }

    @Test fun closeAndRemovalDiscardLateResults() {
        classifier.assess(call)
        classifier.remove(call)
        complete()
        assertEquals(0, changes)
        classifier.assess(call)
        classifier.close()
        complete()
        assertEquals(0, changes)
        assertTrue(classifier.assess(call) is CallSafety.Assessment.Unsafe)
    }

    @Test fun repeatedEvaluationCannotQueueBehindStalledRead() {
        repeat(100) { assertEquals(CallSafety.Assessment.Pending, classifier.assess(call)) }
        assertEquals(1, worker.size)
        assertEquals(0, telecom.queries)
    }

    @Test fun workerRejectionFailsClosedAndDiagnosticsHaveNoHandleOrNumber() {
        classifier.close()
        classifier = CellularClassifier(context, Executor { throw RejectedExecutionException() }) { changes++ }
        assertTrue(classifier.assess(call) is CallSafety.Assessment.Unsafe)
        val exported = classifier.diagnosticFields().toMap().toString()
        assertFalse(exported.contains("5550100"))
        assertFalse(exported.contains("tel:"))
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
