# 03b User escalations (closed)

Feature: kyo-browser jsonrpc-port
Pass: `flow-resolve-open --pass apply`

This file records value-underdetermined escalations that required user/supervisor ratification (per `feedback_dont_offer_options`: one defensible answer, yes/no, never a menu).

Total escalations: 1
Open: 0
Closed: 1

---

## Escalation 1 — Q-002 (WS-connect failure surfacing)

- **Status**: RATIFIED
- **Type**: value-underdetermined
- **Source**: `02-design.md` §9.A (lines 663-718), §15 Open questions (lines 1219-1226), §15 closing remark (lines 1276-1278); `01-exploration.md` RI-002 (lines 593-604).
- **Question**: Should the kyo-browser port handle async WS-connect failures by (b) issuing a kyo-browser-side `Browser.getVersion` probe call inside `CdpBackend.initUnscoped` that recovers `Closed` to `BrowserSetupException`, rather than (a) modifying `JsonRpcHttpTransport.webSocket` to synchronously await ws-open before returning?
- **Design recommendation**: option (b) — kyo-browser-side probe.
- **User/supervisor answer**: option (b). Approved.
- **Supervisor rationale**:
  - `feedback_no_backcompat`: a just-shipped public API in `kyo-jsonrpc-http` is not perturbed for a single consumer's need. Threading a sync-connect promise through `JsonRpcHttpTransport.webSocket` would mutate a cross-module public API for one caller. The principle decides directly.
  - Typing layer fit: `BrowserSetupException` belongs to kyo-browser; placing the recovery inside `CdpBackend.initUnscoped` keeps the failure typing local to the layer that owns it.
  - Cost: ~5-15ms one-time on the happy path. Acceptable for a browser launch and gives stronger liveness proof (TCP+WS handshake plus one CDP round-trip) than the current `connectReady` gate alone.
- **Rejected alternative**: option (a) — modify `JsonRpcHttpTransport.webSocket` to synchronously await ws-open before returning. Rejected because it perturbs a cross-module public API for one consumer's need and conflicts with the existing async-open design.
- **If-yes action (taken)**: keep the design's §9.A probe implementation in `CdpBackend.initUnscoped`. No edits to `JsonRpcHttpTransport.webSocket`.
- **If-no action (NOT taken)**: would have re-opened §9.A to modify `JsonRpcHttpTransport.webSocket` and remove the probe block.
- **Blocks now unblocked**: Phase 01 wiring of `CdpBackend.initUnscoped` probe block (design §9.A pseudocode lines 695-707).
- **Design patch**: §15 Q-002 status updated to "RATIFIED (option b, kyo-browser-side probe, per no-backcompat)". §15 closing "either/or" remark (lines 1276-1278) removed.

---

No further escalations. Plan is open-question-free.
