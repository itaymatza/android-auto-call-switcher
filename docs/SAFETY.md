# Safety and privacy boundaries

## Eligibility checks

The service makes no route request unless it can establish all of the following: protected Telecom authorization, required runtime permissions, exactly one live active call, a SIM-backed phone account, a non-emergency telephone number classification, a configured target uniquely resolved to a current Telecom Bluetooth endpoint, and a connected target in the HFP profile. Automatic mode additionally requires the master toggle and a verified projection-host state.

Calls are rejected when they are emergency or emergency-callback calls, external or self-managed calls, conferences, non-SIM calls, calls with a hidden or non-telephone handle, calls with unavailable emergency classification, or sessions containing multiple calls. The app does not replace the default dialer, manage media profiles, modify A2DP, disconnect Bluetooth, record audio, read contacts, read call logs, or send network telemetry.

## Bounded behavior

A fresh automatic session has up to ten seconds to collect authoritative evidence. During outgoing
dialing or after an incoming call becomes active, the policy waits at least 300 ms for Android Auto's call-start routing to settle;
observed activity can extend that wait up to 900 ms. It then makes at most one target request per dialing/answer phase, with a maximum of two automatic
target requests per call, and observes each transaction for up to four seconds. There is no retry,
reassertion, or call-long route ownership. Telecom's displayed endpoint and accepted request result
are diagnostic only; success requires the exact configured HFP device to own SCO continuously for
the confirmation interval. A timeout may still be followed by late SCO confirmation, but it never
authorizes another request. Endpoint disappearance, external cancellation, unknown errors, and
runtime exceptions stop the transaction.

If target is not confirmed and the final evidence proves the specific Samsung regression—Telecom
still displays target while the configured Android Auto endpoint owns SCO—the app may make one
additional request for that Android Auto endpoint. Its only purpose is to restore truthful selector
state so the user can choose target manually. It is forbidden when the SCO owner or endpoint identity
is unknown, when another route is displayed, or after one recovery attempt; it never retries target.

Safety-relevant callback edges are latched before deferred evaluation. Therefore, confirmed projection loss, confirmed target HFP loss, a call hold, a second call, a conference child, a settings change during the session, service teardown, or user pause cancels further automatic requests for that session. Temporary unknown projection/HFP evidence and transient Telecom endpoint-list gaps are not treated as confirmed loss: the transaction waits, stale endpoints are never submitted, and evaluation resumes only from fresh callback evidence within the bounded window. `onCallEndpointRequested()` is never interpreted as verified user intent, because the callback does not identify its requester. Call-start activity can extend settling; an external request after the target attempt suppresses selector recovery while HFP verification continues. The per-phase target-request budget prevents a routing fight. Protected handset, speaker, and
wired route callbacks during automatic routing latch suspension even if a later callback changes
the displayed route back before evaluation.

## Authorization safety

Run the ADB AppOps command only for the exact installed application ID. Verify that Android reports `MANAGE_ONGOING_CALLS: allow`, then disable USB debugging and Wireless debugging when setup is complete. Do not leave an unknown computer authorized for debugging. The grant can be revoked as documented in [AUTHORIZATION.md](AUTHORIZATION.md).

A generic Bluetooth or Companion Device Manager association is not equivalent to this authorization, and the app must not report it as successful Telecom access.

## Public-source hygiene

Bluetooth addresses and device names can be personal data in context. Keep real settings local; do not commit exported settings, diagnostic logs, screenshots, issue attachments, or test output that identifies people or hardware. Do not commit signing keys, certificates, credentials, or APKs. Generated content and common sensitive file types are ignored, but review staged changes before every push.
