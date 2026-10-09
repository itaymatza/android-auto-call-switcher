package org.carcallrouter.companion.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Reads paired devices and their labels off the UI thread; never queues behind a stuck read. */
class PairedDeviceQuery internal constructor(
    private val context: Context,
    queryWorker: Executor = worker,
    private val completed: (List<Device>?) -> Unit,
) : AutoCloseable {
    data class Device(
        val address: String,
        val label: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var expired = false
    private var pending = false
    private var closed = false
    private val timeout =
        Runnable {
            expired = true
            completed(null)
        }
    private val query =
        SingleFlightQuery<List<Device>>(queryWorker, Executor { handler.post(it) }, SystemClock::elapsedRealtime) { result, queued, _ ->
            pending = false
            handler.removeCallbacks(timeout)
            if (!expired) {
                completed(
                    result.getOrNull()?.takeIf {
                        Access.bluetoothGranted(context) && SystemClock.elapsedRealtime() - queued in 0..TIMEOUT_MS
                    },
                )
            }
        }

    @SuppressLint("MissingPermission")
    fun load(): Boolean {
        if (closed || pending) return false
        pending = true
        expired = false
        handler.postDelayed(timeout, TIMEOUT_MS)
        val submitted =
            query.submit {
                check(Access.bluetoothGranted(context))
                val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter)
                adapter.bondedDevices
                    .map {
                        Device(it.address, it.alias ?: it.name ?: "Unnamed Bluetooth device")
                    }.sortedWith(compareBy({ it.label }, { it.address }))
            }
        return submitted
    }

    override fun close() {
        closed = true
        handler.removeCallbacks(timeout)
        query.close()
    }

    companion object {
        private const val TIMEOUT_MS = 3_000L
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-device-picker").apply { isDaemon = true }
            })
    }
}
