package org.carcallrouter.companion.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHearingAid
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only, foreground-only profile inspection. Does not authorize a routing request. */
@SuppressLint("MissingPermission")
class CallDevicePreflight internal constructor(
    private val context: Context,
    queryWorker: Executor = worker,
    leGroupWorker: Executor = groupWorker,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private var registered = false
    private var closed = false

    private data class Groups(
        val members: Map<String, Int?>,
        val leads: Map<Int, String?>,
    )

    private var groupSnapshot: Groups? = null
    private var groupSampledAt = 0L
    private val groupExpire = Runnable { changed() }
    private val groupQuery =
        SingleFlightQuery<Groups>(
            leGroupWorker,
            Executor { handler.post(it) },
            SystemClock::elapsedRealtime,
        ) { result, _, started ->
            groupSampledAt = SystemClock.elapsedRealtime()
            groupSnapshot =
                if (Access.bluetoothGranted(context) && groupSampledAt - started <= MAX_QUERY_AGE_MS) result.getOrNull() else null
            handler.removeCallbacks(groupExpire)
            handler.postDelayed(groupExpire, MAX_OBSERVATION_AGE_MS + 1)
            changed()
        }

    fun leGroup(target: String?): LeAudioGroupConnection? {
        if (SystemClock.elapsedRealtime() - groupSampledAt > MAX_OBSERVATION_AGE_MS) return null
        val groups = groupSnapshot ?: return null
        return LeAudioGroupConnection.resolve(target, groups.members, groups.leads)
    }

    private val query =
        SingleFlightQuery<Map<Int, Set<String>?>>(
            queryWorker,
            Executor { handler.post(it) },
            SystemClock::elapsedRealtime,
        ) { result, _, started ->
            sampledAt = SystemClock.elapsedRealtime()
            snapshot =
                if (Access.bluetoothGranted(context) && SystemClock.elapsedRealtime() - started <= MAX_QUERY_AGE_MS) {
                    result.getOrNull() ?: unknownConnections()
                } else {
                    unknownConnections()
                }
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, MAX_OBSERVATION_AGE_MS + 1)
            changed()
        }
    private var snapshot: Map<Int, Set<String>?> = emptyMap()
    private var sampledAt = 0L
    val connections: Map<Int, Set<String>?>
        get() =
            if (snapshot.isNotEmpty() &&
                SystemClock.elapsedRealtime() - sampledAt > MAX_OBSERVATION_AGE_MS
            ) {
                unknownConnections()
            } else {
                snapshot
            }
    private val expire = Runnable { changed() }

    private val listener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                handler.post {
                    if (closed) {
                        runCatching { adapter?.closeProfileProxy(profile, proxy) }
                    } else {
                        query.invalidate()
                        groupQuery.invalidate()
                        groupSnapshot = null
                        snapshot = unknownConnections()
                        proxies[profile] = proxy
                        changed()
                        refresh()
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                handler.post {
                    if (!closed) {
                        query.invalidate()
                        groupQuery.invalidate()
                        groupSnapshot = null
                        proxies.remove(profile)
                        snapshot = unknownConnections()
                        changed()
                        refresh()
                    }
                }
            }
        }
    private val poll =
        object : Runnable {
            override fun run() {
                if (closed) return
                refresh()
                handler.postDelayed(this, 3_000)
            }
        }
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Ignore broadcast extras; query authenticated profile services.
                refresh()
            }
        }

    fun start() {
        if (!Access.bluetoothGranted(context)) {
            snapshot = unknownConnections()
            changed()
            return
        }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            registered = true
            PROFILES.forEach { adapter?.getProfileProxy(context, listener, it) }
            handler.post(poll)
        } catch (_: RuntimeException) {
            snapshot = unknownConnections()
            changed()
        }
    }

    fun refresh() {
        if (closed) return
        if (!Access.bluetoothGranted(context)) {
            query.invalidate()
            groupQuery.invalidate()
            groupSnapshot = null
            snapshot = unknownConnections()
            changed()
            return
        }
        val currentProxies = proxies.toMap()
        query.submit {
            PROFILES.associateWith { profile ->
                runCatching { currentProxies[profile]?.connectedDevices?.map { it.address.uppercase() }?.toSet() }.getOrNull()
            }
        }
        val le = currentProxies[BluetoothProfile.LE_AUDIO] as? BluetoothLeAudio
        if (le == null) {
            groupQuery.invalidate()
            groupSnapshot = null
        } else {
            groupQuery.submit {
                val devices = le.connectedDevices
                val members = devices.associate { it.address.uppercase() to runCatching { le.getGroupId(it) }.getOrNull() }
                val leads =
                    members.values.filterNotNull().filter { it >= 0 }.distinct().associateWith { group ->
                        runCatching { le.getConnectedGroupLeadDevice(group)?.address?.uppercase() }.getOrNull()
                    }
                Groups(members, leads)
            }
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacks(poll)
        handler.removeCallbacks(expire)
        handler.removeCallbacks(groupExpire)
        query.close()
        groupQuery.close()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        proxies.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
        proxies.clear()
        snapshot = emptyMap()
        groupSnapshot = null
    }

    companion object {
        val PROFILES = setOf(BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID)
        private const val MAX_QUERY_AGE_MS = 750L
        private const val MAX_OBSERVATION_AGE_MS = 10_000L

        private fun unknownConnections(): Map<Int, Set<String>?> = PROFILES.associateWith { null }

        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-preflight-query").apply { isDaemon = true }
            })

        // Optional LE group queries cannot block classic profile inspection.
        private val groupWorker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-le-group-query").apply { isDaemon = true }
            })
    }
}
