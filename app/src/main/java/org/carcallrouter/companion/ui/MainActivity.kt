package org.carcallrouter.companion.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.BuildConfig
import org.carcallrouter.companion.LegacyAssociationCleanup
import org.carcallrouter.companion.ProcessDiagnostics
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.R
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var settings: RouterSettings
    private lateinit var master: Switch
    private lateinit var readinessCard: View
    private lateinit var readinessEyebrow: TextView
    private lateinit var readinessTitle: TextView
    private lateinit var readinessMessage: TextView
    private lateinit var setupProgress: TextView
    private lateinit var lastCallResult: TextView
    private lateinit var deviceCompatibility: TextView
    private lateinit var masterHelp: TextView
    private lateinit var projectionValue: TextView
    private lateinit var permissionsState: TextView
    private lateinit var authorizationState: TextView
    private lateinit var targetValue: TextView
    private lateinit var competitorValue: TextView
    private lateinit var permissionsButton: Button
    private lateinit var authorizationButton: Button
    private lateinit var targetButton: Button
    private lateinit var routeNowButton: Button
    private lateinit var pauseButton: Button
    private lateinit var advancedToggle: Button
    private lateinit var advancedContent: View
    private lateinit var diagnosticsToggle: Button
    private lateinit var diagnosticsContent: View
    private lateinit var status: TextView
    private lateinit var logs: TextView

    private var syncing = false
    private var advancedVisible = false
    private var diagnosticsVisible = false
    private var monitor: ProjectionMonitor? = null
    private var preflight: CallDevicePreflight? = null
    private var projection: Boolean? = null
    private var lastPreflightState: Pair<String?, CallDeviceCompatibility>? = null
    private val statusListener: () -> Unit = { refresh() }
    private val logListener: () -> Unit = { logs.text = RouterLog.recentText() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logUiState("CREATED")
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        settings = RouterSettings(this)
        LegacyAssociationCleanup.run(this, settings)
        bindViews()
        logs.movementMethod = ScrollingMovementMethod()

        master.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            val setup = currentSetupState()
            if (checked && !setup.ready) {
                toast("Complete the required setup before enabling automatic routing.")
            } else {
                settings.enabled = checked
                RouterLog.event("MASTER_TOGGLE", "enabled=$checked")
            }
            refresh()
        }

        button(R.id.permissions) { explainAndRequestPermissions() }
        button(R.id.copy_adb) { authorizeCallRouting() }
        button(R.id.target) { chooseDevice(false) }
        button(R.id.competitor) { chooseDevice(true) }
        button(R.id.route_now) {
            val controller = SessionBridge.controller?.get()
            if (controller == null) {
                toast("Start a normal call first, then try again while parked.")
            } else {
                controller.routeNow()
            }
        }
        button(R.id.pause) {
            SessionBridge.controller?.get()?.pauseSession() ?: toast("No active call session to pause.")
        }
        button(R.id.refresh) {
            preflight?.refresh()
            refresh()
        }
        button(R.id.report_parked_test) { recordParkedTest() }
        button(R.id.advanced_toggle) {
            advancedVisible = !advancedVisible
            advancedContent.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            advancedToggle.setText(if (advancedVisible) R.string.hide_advanced else R.string.show_advanced)
        }
        button(R.id.diagnostics_toggle) {
            diagnosticsVisible = !diagnosticsVisible
            diagnosticsContent.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
            diagnosticsToggle.setText(if (diagnosticsVisible) R.string.hide_diagnostics else R.string.show_diagnostics)
        }
        button(R.id.battery_settings) { showBatterySettingsGuide() }
        button(R.id.export) { exportLog() }
        button(R.id.report_wrong_audio) {
            val controller = SessionBridge.controller?.get()
            if (controller != null) {
                controller.reportWrongAudio()
            } else {
                RouterLog.event(
                    "USER_AUDIO_REPORT",
                    "observation=wrong_audio; serviceBound=false; applies_to=recent_call; physical_audio_verified=false",
                )
            }
            toast("Audio problem marked in the diagnostic log")
        }
        button(R.id.view_releases) { openUrl(R.string.releases_url) }
        button(R.id.view_source) { openUrl(R.string.project_url) }
        button(R.id.report_issue) { openUrl(R.string.issues_url) }
        findViewById<TextView>(R.id.app_metadata).text =
            getString(
                R.string.about_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.APPLICATION_ID,
            )
        refresh()
    }

    private fun bindViews() {
        master = findViewById(R.id.master)
        readinessCard = findViewById(R.id.readiness_card)
        readinessEyebrow = findViewById(R.id.readiness_eyebrow)
        readinessTitle = findViewById(R.id.readiness_title)
        readinessMessage = findViewById(R.id.readiness_message)
        setupProgress = findViewById(R.id.setup_progress)
        lastCallResult = findViewById(R.id.last_call_result)
        deviceCompatibility = findViewById(R.id.device_compatibility)
        masterHelp = findViewById(R.id.master_help)
        projectionValue = findViewById(R.id.projection_value)
        permissionsState = findViewById(R.id.permissions_state)
        authorizationState = findViewById(R.id.authorization_state)
        targetValue = findViewById(R.id.target_value)
        competitorValue = findViewById(R.id.competitor_value)
        permissionsButton = findViewById(R.id.permissions)
        authorizationButton = findViewById(R.id.copy_adb)
        targetButton = findViewById(R.id.target)
        routeNowButton = findViewById(R.id.route_now)
        pauseButton = findViewById(R.id.pause)
        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedContent = findViewById(R.id.advanced_content)
        diagnosticsToggle = findViewById(R.id.diagnostics_toggle)
        diagnosticsContent = findViewById(R.id.diagnostics_content)
        status = findViewById(R.id.status)
        logs = findViewById(R.id.logs)
    }

    override fun onStart() {
        super.onStart()
        logUiState("STARTED")
        SessionBridge.observe(statusListener)
        RouterLog.observe(logListener)
        monitor =
            ProjectionMonitor(this) {
                projection = it
                refresh()
            }.also { it.start() }
        preflight = CallDevicePreflight(this) { refresh() }.also { it.start() }
        logs.text = RouterLog.recentText()
    }

    override fun onResume() {
        super.onResume()
        logUiState("RESUMED")
        refresh()
    }

    override fun onStop() {
        logUiState("STOPPED")
        SessionBridge.remove(statusListener)
        RouterLog.remove(logListener)
        monitor?.close()
        monitor = null
        preflight?.close()
        preflight = null
        super.onStop()
    }

    private fun logUiState(state: String) {
        ProcessDiagnostics.markUi(state, SessionBridge.controller?.get() != null)
    }

    private fun currentSetupState() =
        SetupState(
            runtimePermissionsGranted = Access.runtimeGranted(this),
            telecomAuthorized = Access.ongoingCalls(this),
            targetSelected = settings.targetAddress != null,
            automationEnabled = settings.enabled,
        )

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val setup = currentSetupState()
        syncing = true
        master.isChecked = settings.enabled
        // Keep the switch operable when an enabled setup loses a prerequisite,
        // so the user can always turn automation off.
        master.isEnabled = setup.ready || settings.enabled
        syncing = false

        val compatibility =
            CallDeviceCompatibility.resolve(
                settings.targetAddress,
                preflight?.connections ?: emptyMap(),
                BluetoothProfile.HEADSET,
                setOf(BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID),
            )
        val preflightState = settings.targetAddress to compatibility
        if (lastPreflightState != preflightState) {
            lastPreflightState = preflightState
            RouterLog.event(
                "DEVICE_PREFLIGHT",
                "target=${settings.targetAddress?.let(RouterLog::deviceId) ?: "NONE"}; " +
                    "compatibility=$compatibility; evidence=profile_connection_only; active_audio_verified=false",
            )
        }
        deviceCompatibility.setText(
            when (compatibility) {
                CallDeviceCompatibility.NO_TARGET -> R.string.compatibility_no_target
                CallDeviceCompatibility.CHECKING -> R.string.compatibility_checking
                CallDeviceCompatibility.CLASSIC_CONNECTED -> R.string.compatibility_classic
                CallDeviceCompatibility.OTHER_TRANSPORT_CONNECTED -> R.string.compatibility_other
                CallDeviceCompatibility.NOT_CONNECTED -> R.string.compatibility_disconnected
                CallDeviceCompatibility.UNKNOWN -> R.string.compatibility_unknown
            },
        )
        setupProgress.text =
            resources.getQuantityString(
                R.plurals.setup_progress,
                setup.completedSteps,
                setup.completedSteps,
                SetupState.REQUIRED_STEPS,
            )
        updateReadiness(setup)

        val lastSession = settings.lastSession
        lastCallResult.text =
            if (lastSession == null) {
                getString(R.string.last_call_none)
            } else {
                getString(
                    R.string.last_call_summary,
                    DateFormat.getDateTimeInstance().format(Date(lastSession.completedAt)),
                    getString(
                        when (lastSession.result) {
                            RouterSettings.Result.HFP_CONFIRMED -> R.string.last_call_hfp_confirmed
                            RouterSettings.Result.UNSTABLE -> R.string.last_call_unstable
                            RouterSettings.Result.FAILED -> R.string.last_call_failed
                            RouterSettings.Result.INCOMPLETE -> R.string.last_call_incomplete
                        },
                    ),
                    lastSession.phase,
                    lastSession.reason,
                    lastSession.confirmation,
                )
            }

        masterHelp.setText(
            when {
                !setup.ready -> R.string.master_help_locked
                settings.enabled -> R.string.master_help_active
                else -> R.string.master_help_ready
            },
        )
        projectionValue.setText(
            when (projection) {
                true -> R.string.projection_connected
                false -> R.string.projection_not_connected
                null -> R.string.projection_unknown
            },
        )

        permissionsState.setText(if (setup.runtimePermissionsGranted) R.string.permissions_complete else R.string.permissions_missing)
        permissionsButton.setText(
            if (setup.runtimePermissionsGranted) R.string.permissions_action_complete else R.string.permissions_action,
        )
        permissionsButton.isEnabled = !setup.runtimePermissionsGranted

        authorizationState.setText(
            if (setup.telecomAuthorized) R.string.authorization_complete else R.string.authorization_missing,
        )
        authorizationButton.setText(if (setup.telecomAuthorized) R.string.authorization_action_complete else R.string.authorization_action)
        authorizationButton.isEnabled = !setup.telecomAuthorized && setup.targetSelected

        targetValue.text =
            if (setup.targetSelected) {
                getString(R.string.target_selected, settings.targetName)
            } else {
                getString(R.string.target_missing)
            }
        targetButton.setText(if (setup.targetSelected) R.string.target_action_change else R.string.target_action)

        competitorValue.text =
            if (settings.competitorAddress == null) {
                getString(R.string.competitor_none)
            } else {
                getString(R.string.competitor_selected, settings.competitorName)
            }

        routeNowButton.isEnabled = setup.ready
        pauseButton.isEnabled = SessionBridge.controller?.get() != null

        val bound =
            if (settings.lastBound == 0L) {
                "Never observed"
            } else {
                DateFormat.getDateTimeInstance().format(Date(settings.lastBound))
            }
        status.text = "Version: ${BuildConfig.VERSION_NAME}\n" +
            "Android SDK: ${android.os.Build.VERSION.SDK_INT}\n" +
            "Runtime permissions: ${setup.runtimePermissionsGranted}\n" +
            "Telecom authorization: ${setup.telecomAuthorized}\n" +
            "Projection state: ${projection ?: "unknown"}\n" +
            "Last Telecom binding: $bound\n\n" +
            SessionBridge.status
    }

    private fun updateReadiness(setup: SetupState) {
        when (setup.phase) {
            SetupPhase.NEEDS_RUNTIME_PERMISSIONS ->
                setReadiness(
                    R.drawable.bg_status_warning,
                    R.color.warning,
                    R.string.setup_required,
                    R.string.readiness_permissions_title,
                    R.string.readiness_permissions_message,
                )
            SetupPhase.NEEDS_TELECOM_AUTHORIZATION ->
                setReadiness(
                    R.drawable.bg_status_warning,
                    R.color.warning,
                    R.string.setup_required,
                    R.string.readiness_authorization_title,
                    R.string.readiness_authorization_message,
                )
            SetupPhase.NEEDS_TARGET_DEVICE ->
                setReadiness(
                    R.drawable.bg_status_warning,
                    R.color.warning,
                    R.string.setup_required,
                    R.string.readiness_target_title,
                    R.string.readiness_target_message,
                )
            SetupPhase.READY ->
                setReadiness(
                    R.drawable.bg_status_ready,
                    R.color.success,
                    R.string.ready_label,
                    R.string.readiness_ready_title,
                    R.string.readiness_ready_message,
                )
            SetupPhase.ACTIVE ->
                setReadiness(
                    R.drawable.bg_status_active,
                    R.color.primary,
                    R.string.active_label,
                    R.string.readiness_active_title,
                    R.string.readiness_active_message,
                )
        }
    }

    private fun setReadiness(
        background: Int,
        accent: Int,
        eyebrow: Int,
        title: Int,
        message: Int,
    ) {
        readinessCard.setBackgroundResource(background)
        readinessEyebrow.setText(eyebrow)
        readinessEyebrow.setTextColor(getColor(accent))
        readinessTitle.setText(title)
        readinessMessage.setText(message)
    }

    private fun explainAndRequestPermissions() {
        if (Access.runtimeGranted(this)) {
            toast(getString(R.string.permissions_action_complete))
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.permissions_dialog_title)
            .setMessage(R.string.permissions_dialog_message)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                requestPermissions(Access.runtimePermissions, REQUEST_PERMISSIONS)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun authorizeCallRouting() {
        if (Access.ongoingCalls(this)) {
            toast(getString(R.string.authorization_action_complete))
            return
        }
        val targetAddress = settings.targetAddress
        if (targetAddress == null) {
            toast(getString(R.string.authorization_target_required))
            return
        }
        RouterLog.event("AUTH_GUIDE", "Protected AppOp is not granted; showing one-time ADB setup")
        showAuthorizationGuide()
    }

    private fun verifyCallAuthorization() {
        if (Access.ongoingCalls(this)) {
            RouterLog.event("AUTH_COMPLETE", "MANAGE_ONGOING_CALLS detected")
            refresh()
            toast(getString(R.string.authorization_success))
            return
        }
        RouterLog.event("AUTH_MISSING", "MANAGE_ONGOING_CALLS still unavailable after user verification")
        refresh()
        toast(getString(R.string.authorization_not_detected))
    }

    private fun showAuthorizationGuide() {
        val command = Access.authorizationAdb()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.authorization_dialog_title)
            .setMessage(getString(R.string.authorization_dialog_message, command))
            .setPositiveButton(R.string.copy_command) { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Call-routing authorization", command),
                )
                toast(getString(R.string.command_copied))
            }.setNeutralButton(R.string.verify_authorization) { _, _ -> verifyCallAuthorization() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBatterySettingsGuide() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: RuntimeException) {
                    toast("Battery settings are unavailable on this Android build.")
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun recordParkedTest() {
        val target = settings.targetAddress
        if (target == null) {
            toast(getString(R.string.authorization_target_required))
            return
        }
        val competitor = settings.competitorAddress
        val startedAt = System.currentTimeMillis()
        val sessionAtStart = settings.lastSession?.completedAt
        val answers = mutableListOf<String>()
        val questions =
            listOf(
                R.string.parked_test_speaker,
                R.string.parked_test_microphone,
                R.string.parked_test_navigation,
                R.string.parked_test_media,
            )
        val choices = arrayOf(getString(R.string.test_pass), getString(R.string.test_fail), getString(R.string.test_not_checked))
        val values = listOf("PASS", "FAIL", "NOT_CHECKED")

        fun ask(index: Int) {
            AlertDialog
                .Builder(this)
                .setTitle("${getString(R.string.parked_test_title, index + 1)}\n${getString(questions[index])}")
                .setItems(choices) { _, choice ->
                    answers.add(values[choice])
                    if (index < questions.lastIndex) {
                        ask(index + 1)
                    } else if (target != settings.targetAddress || competitor != settings.competitorAddress) {
                        toast(getString(R.string.parked_test_changed))
                    } else {
                        RouterLog.event(
                            "USER_PARKED_TEST",
                            "source=user_report; target=${RouterLog.deviceId(target)}; " +
                                "speaker=${answers[0]}; microphone=${answers[1]}; " +
                                "aa_navigation=${answers[2]}; aa_media_resumed=${answers[3]}; " +
                                "startedAt=$startedAt; sessionAtStart=${sessionAtStart ?: "NONE"}; " +
                                "sessionAtEnd=${settings.lastSession?.completedAt ?: "NONE"}; " +
                                "physical_audio_automatically_verified=false; universal_qualification=false",
                        )
                        toast(getString(R.string.parked_test_saved))
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.report_parked_test)
            .setMessage(R.string.parked_test_intro)
            .setPositiveButton(R.string.continue_action) { _, _ -> ask(0) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportLog() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "android-auto-call-switcher-${System.currentTimeMillis()}.txt")
            }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun openUrl(urlResource: Int) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlResource)))
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            toast(getString(R.string.link_unavailable))
        }
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice(competing: Boolean) {
        if (!Access.bluetoothGranted(this)) {
            explainAndRequestPermissions()
            return
        }
        try {
            val devices =
                getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bondedDevices
                    ?.filter { !competing || !it.address.equals(settings.targetAddress, true) }
                    ?.sortedWith(compareBy({ it.name ?: "" }, { it.address }))
                    ?: emptyList()
            val labels =
                devices
                    .map {
                        val suffix = it.address.takeLast(5)
                        "${it.alias ?: it.name ?: "Unnamed Bluetooth device"} • $suffix"
                    }.toMutableList()
            if (competing) labels.add(0, getString(R.string.competitor_none))
            if (labels.isEmpty()) {
                toast("No paired Bluetooth devices found. Pair the car or headset in Android settings first.")
                return
            }
            AlertDialog
                .Builder(this)
                .setTitle(if (competing) R.string.competitor_title else R.string.target_step_title)
                .setItems(labels.toTypedArray()) { _, index ->
                    if (competing && index == 0) {
                        settings.setCompetitor(null, null)
                    } else {
                        val device = devices[index - if (competing) 1 else 0]
                        if (competing) {
                            settings.setCompetitor(device.address, device.alias ?: device.name ?: "Unnamed")
                        } else {
                            settings.setTarget(device.address, device.alias ?: device.name ?: "Unnamed")
                        }
                        RouterLog.event(
                            "DEVICE_SELECTED",
                            "role=${if (competing) "competitor" else "target"}; id=${RouterLog.deviceId(device.address)}",
                        )
                    }
                    refresh()
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: RuntimeException) {
            RouterLog.event("DEVICE_SELECTION_ERROR", e.javaClass.simpleName)
            toast("Bluetooth access failed. Check permission and make sure Bluetooth is on.")
        }
    }

    private fun button(
        id: Int,
        action: () -> Unit,
    ) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (preflight != null) {
            preflight?.close()
            preflight = CallDevicePreflight(this) { refresh() }.also { it.start() }
        }
        refresh()
    }

    @Deprecated("Framework Activity compatibility callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                RouterLog.export(applicationContext, uri) { ok ->
                    toast(if (ok) "Log exported" else "Export failed")
                }
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val REQUEST_EXPORT = 20
    }
}
