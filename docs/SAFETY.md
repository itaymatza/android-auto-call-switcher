# Safety and privacy boundaries

## Eligibility checks

The service makes no route request unless it can establish all of the following: protected Telecom authorization, required runtime permissions, exactly one live active call, a SIM-backed phone account, a non-emergency telephone number classification, a configured target uniquely resolved to a current Telecom Bluetooth endpoint, and a connected target in the HFP profile. Automatic mode additionally requires the master toggle and a verified projection-host state.

Calls are rejected when they are emergency or emergency-callback calls, external or self-managed calls, conferences, non-SIM calls, calls with a hidden or non-telephone handle, calls with unavailable emergency classification, or sessions containing multiple calls. The app does not replace the default dialer, manage media profiles, modify A2DP, disconnect Bluetooth, record audio, read contacts, read call logs, or send network telemetry.

## Bounded behavior

A fresh automatic session has ten seconds to collect evidence. Outgoing and incoming calls remain passive until ACTIVE, then settle for 300–900 ms. The policy submits at most one target request and observes fresh target HFP samples for up to four seconds, requiring samples spanning 250 ms. Request acceptance and the displayed endpoint never prove physical speaker or microphone selection.

A pre-existing displayed-target/other-owner mismatch requires reconciliation before that single target request. The alternative must be the sole positive HFP audio owner, uniquely resolved using fresh live identity, with stable samples spanning 500 ms. A configured competitor restricts the allowed owner. One reconciliation command must produce the expected endpoint and a strictly newer matching HFP sample within 2.5 seconds. Unresolved or stale evidence cannot authorize a command. No failed target request is retried. See [the full contract and platform limits](ROUTING-RELIABILITY-2026-10-20.md).

Safety-relevant callback edges are latched before deferred evaluation. Therefore, confirmed projection loss, confirmed target HFP loss, a call hold, a second call, a conference child, a settings change during the session, service teardown, or user pause cancels further automatic requests for that session. Temporary unknown projection/HFP evidence and transient Telecom endpoint-list gaps are not treated as confirmed loss: the transaction waits, stale endpoints are never submitted, and evaluation resumes only from fresh callback evidence within the bounded window. `onCallEndpointRequested()` is never interpreted as verified user intent, because the callback does not identify its requester. Call-start activity can extend settling; an external request interrupts reconciliation, while after the single target request no further automatic command is possible. The per-call target-request budget prevents a routing fight. Protected handset, speaker, and
wired route callbacks during automatic routing latch suspension even if a later callback changes
the displayed route back before evaluation.

## Authorization safety

Run the ADB AppOps command only for the exact installed application ID. Verify that Android reports `MANAGE_ONGOING_CALLS: allow`, then disable USB debugging and Wireless debugging when setup is complete. Do not leave an unknown computer authorized for debugging. The grant can be revoked as documented in [AUTHORIZATION.md](AUTHORIZATION.md).

A generic Bluetooth or Companion Device Manager association is not equivalent to this authorization, and the app must not report it as successful Telecom access.

## Public-source hygiene

Bluetooth addresses and device names can be personal data in context. Keep real settings local; do not commit exported settings, diagnostic logs, screenshots, issue attachments, or test output that identifies people or hardware. Do not commit signing keys, certificates, credentials, or APKs. Generated content and common sensitive file types are ignored, but review staged changes before every push.
