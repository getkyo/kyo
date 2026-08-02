# Audit: `Closed` exceptions in kyo-net — resource identity ↔ creation-frame coherence

## Protocol (validated)

`Closed(resource, createdAt, details)` renders `"<resource> created at <createdAt> is closed. <details>"` — one claim: *this named resource, born at this frame, is closed*. Judge `resource` and `createdAt` **together** as one description of the true resource.

1. Identify the resource actually being reported closed (the true resource).
2. Trace `createdAt`: it MUST be the frame where the true resource was created. `summon[Frame]` binds to the nearest enclosing `given Frame`/`using Frame`; a method's `using Frame` param is the throw-site/caller frame, NOT a creation frame.
3. Global-shared exception: if the true resource is process-global/internally-created (the process-shared transport driver), `Frame.internal` is its correct creation frame. A per-connection/fd/listener is NOT global-shared.

Verdict per site: **COHERENT** (arg1 names the true resource AND arg2 is that resource's creation frame) or **DEFECTIVE** (state what is wrong + the correct pair).

Key structural fact: **no handle/connection/listener in kyo-net captures a creation frame** (`NioHandle.init`, `PosixHandle`, etc. take no `Frame`). So a per-connection `Closed` has no captured connection-creation frame to use — the fix requires capturing the user's connect/accept frame onto the handle. Today per-connection sites pass either the driver's throw-site `summon` or `Frame.internal`; neither is the connection's birth.

---

## NioIoDriver.scala — 15 sites. `label="NioIoDriver[sel=…]"`; `handleLabel(h)="channel=…"`.

Frame binding: `summon[Frame]` → the method's `using Frame` (AMBIENT) unless the method declares `given Frame = Frame.internal` (INTERNAL).

| line | method | true resource | arg1 | arg2 binds to | verdict |
|---|---|---|---|---|---|
| 280 | awaitRead | a connection (handle, upgrade detach) | driver ✗ | ambient ✗ | **DEFECTIVE** both — resource→connection; frame→that connection's creation frame |
| 328 | armRead | a connection (superseded read arm) | driver ✗ | ambient ✗ | **DEFECTIVE** both |
| 335 | armRead | a connection (registerRead failed) | driver ✗ | ambient ✗ | **DEFECTIVE** both |
| 443 | applyUpgradeArm | a connection (upgrade) | driver ✗ | internal(437) ✗ | **DEFECTIVE** both — internal is wrong for a per-connection resource |
| 652 | deliverStagedToArm | a connection | driver ✗ | internal(639) ✗ | **DEFECTIVE** both |
| 738 | failConsumedUpgradeRead | a connection | driver ✗ | internal(737) ✗ | **DEFECTIVE** both |
| 794 | stopUpgradeProducer | a connection | driver ✗ | internal(788) ✗ | **DEFECTIVE** both |
| 809 | awaitWritable | a connection (registerWrite failed) | driver ✗ | ambient ✗ | **DEFECTIVE** both |
| 836 | awaitConnect | a connection (registerConnect failed) | driver ✗ | ambient ✗ | **DEFECTIVE** both |
| 857 | awaitAccept (stub) | NONE — "not supported" op; nothing is closed | driver ✗ | ambient ✗ | **DEFECTIVE** — misuse of `Closed` for an unsupported-op error (should be an unsupported/illegal-op exception, not `Closed`) |
| 964 | cleanupPending | a connection (handle teardown from cancel/closeHandle) | driver ✗ | ambient ✗ | **DEFECTIVE** both |
| 1047 | cleanupAccept | a listener (server channel) | driver ✗ | ambient ✗ | **DEFECTIVE** both — resource→listener |
| 1068 | close() | the driver (global-shared) | driver ✓ | ambient ✗ | **DEFECTIVE** frame — resource correct; createdAt should be explicit `Frame.internal` (driver is global-shared), not the ambient caller frame |
| 1174 | awaitAccept(server) | a listener (registerAccept failed) | driver ✗ | ambient ✗ | **DEFECTIVE** both — resource→listener |
| 1868 | dispatchConnect | a connection (connect refused) | driver ✗ | internal(1853) ✗ | **DEFECTIVE** both — the reported case; resource→connection, frame→connection creation frame |

**NioIoDriver: 0 coherent / 15 defective.** 13 per-connection-or-listener (resource names the driver, should name the connection/listener; frame is neither the connection's birth). 1 whole-driver close (`:1068`, resource OK, frame should be explicit `Frame.internal`). 1 misuse (`:857`, `Closed` used for an unsupported-op error).

## NioTransport.scala — 2 sites
_pending_

## PollerIoDriver.scala — 24 sites. `label="PollerIoDriver"`; `handleLabel(h)="fd=…/…"`.

`given Frame=Frame.internal` only in `write`/`writeRaw` (:911,:969) — no `Closed` there, so **all 24 sites bind `summon[Frame]` to the ambient `using Frame`.**

**resource-OK / frame-DEFECTIVE (4)** — genuinely report the driver, which IS closed at that point (createdAt should be explicit `Frame.internal`, is ambient):
- :728 awaitRead, :857 armSocketWritable, :881 awaitAccept — all under `if terminal.get()` ("fd=X driver closed": the op is rejected because the driver went terminal; the driver is the true closed resource).
- :1466 close() — the genuine whole-driver close ("driver closed").

**resource-DEFECTIVE + frame-DEFECTIVE (20)** — a per-connection failure (detail names the fd) reported under the driver label; resource→the connection/fd, frame→that connection's creation frame:
- :738, :761 (detached for upgrade) · :899 (stale accept fd) · :1236 (fd canceled) · :1550 (stale event fd) · :1616 (error/RST fd) · :1656, :1659, :1710, :1728 (stale/closed-handle read fd) · :1856 (recv failed fd) · :1912 (upgrade detach) · :2074, :2101, :2151, :2161 (TLS recv/engine fd) · :2173 (stale writable fd) · :2360 (register on closing handle fd) · :2424 (register failed fd) · :2509 (fd closed)

**Poller: 0 coherent / 24 defective** (4 frame-only on genuine driver closes; 20 resource+frame per-connection).

## IoUringDriver.scala — 20 sites. `label="IoUringDriver"`; `handleLabel(h)="fd=…/…"`.

`given Frame=Frame.internal` at `write`:499 (no Closed), `complete`:1774 (covers :1939-:2087), and `completeMultishot`:2104 (no Closed). Rest AMBIENT.

**resource-OK / frame-DEFECTIVE (4)** — genuinely report the driver (closed), createdAt should be explicit `Frame.internal`, is ambient:
- :247 submitDeferredRecv, :420 submitConnect, :474 submitAccept — all gated `if closedFlag.get()` ("driver closed").
- :1282 teardownRing — whole-driver close ("driver closed").

**resource-DEFECTIVE + frame-DEFECTIVE (16)** — per-connection/listener under the driver label:
- ambient frame: :204, :228 (upgrade detach) · :272 (fd closed) · :297 (exclusive-use violation) · :336 (recv negative length) · :481 (listener closed → resource should be the listener) · :898 (fd canceled) · :1123 (connection closed)
- **:436 reclassified to Class 3** (misuse): "awaitConnect on a handle with no connectTarget" — an illegal-op error; nothing is closed. So IoUring Class-1 = 15.
- internal frame but per-connection (internal is wrong for a per-connection resource): :1939 (buffer-role mismatch) · :1958 (ownership mismatch) · :1989 (fatal TLS record) · :2024 (TLS engine read failed) · :2055 (read errno) · :2078 (connect errno) · :2087 (accept errno)

**IoUring: 0 coherent / 20 defective** (4 frame-only on genuine driver closes; 16 resource+frame).

## JsIoDriver.scala — 5 sites. `label="JsIoDriver"`; `handleLabel(h)="socket#…"`. All AMBIENT.

**resource+frame DEFECTIVE (4)** — per-connection (one socket): :67 (socket destroyed) · :105 (socket destroyed) · :128 (socket closed/error before writable) · :159 (socket#N canceled).
**misuse DEFECTIVE (1)**: :98 awaitAccept "not supported on JsIoDriver" — nothing closed (unsupported-op stub, like Nio :857).

**JsIoDriver: 0 coherent / 5 defective.**

## Transport interrupt-cause sites — NioTransport (2), JsTransport (3), PosixTransport (5), ReadPump (1) = 11

A DISTINCT defect class: `Result.Panic(Closed("<Transport/ReadPump>", summon[Frame], "<...before deadline / settled / interrupted / disarmed>"))` used as the **interrupt cause** passed to `timer.interruptDiscard(...)` (or a cleanup arm). **Nothing is closed** — `Closed` is misused as a cancellation/interrupt signal; the detail describes why a deadline timer was disarmed (the op completed in time), not a closed resource. Rendered it would read "NioTransport … is closed. handshake completed before deadline" — nonsensical.

- NioTransport: :1275 (handshake completed before deadline) · :1312 (connect completed before deadline)
- JsTransport: :397 (connect completed) · :456 (handshake settled) · :1070 (upgrade settled)
- PosixTransport: :512 (connect completed) · :613 (connect interrupted before completion) · :729 (handshake settled) · :1292 (handshake completed) · :1719 (upgrade settled)
- ReadPump: :164 (grace disarmed by progress)

**All 11 DEFECTIVE (misuse):** the right cause is an interrupt/cancellation signal (e.g. `Interrupted`), not `Closed`. These are internal (not user-surfaced), but the protocol takes no dismissals: `Closed` here names a resource that is not closed. (`ReadPump` :164's `summon[Frame]` binds to `disarm`'s own `using Frame` param (ambient), not `Frame.internal`; the misuse stands regardless.)

## Handle / reader files (added after opus validation — initially missed: multi-line `Closed(` defeated the first grep)

**BlockingReaderDriver.scala — 3 sites.** `label="BlockingReaderDriver"`, all in `awaitRead` (per-connection reads), ambient frame:
- :46 "read failed fd=X errno=Y" · :65, :80 "read fiber failed fd=X" — **DEFECTIVE both** (Class 1): resource→connection, frame→connection creation frame.

**JsHandle.scala — 1 site.** :98 `Closed(driver.label, summon[Frame], "socket error")` — per-connection socket error; **DEFECTIVE both** (Class 1).

**PosixHandle.scala — 2 sites.** :754, :783 `kyo.Closed("PosixHandle", Frame.internal, s"fd=X/Y closed …")` — per-connection handle close. Resource = the literal class name `"PosixHandle"` (not the specific connection); createdAt = an **explicit `Frame.internal`**. **DEFECTIVE both** (Class 1): the resource should name the specific connection, and `Frame.internal` is the wrong creation frame for a per-connection resource (needs the user connect/accept frame). NOTABLE: these already pass an *explicit* frame — proving the fix is not just "pass explicit instead of summon" but "pass the *connection's creation* frame."

---

# Consolidated conclusion (81 sites — corrected after opus validation)

**81 `Closed(...)` sites across 11 files; 0 fully coherent.** (Independently re-counted; opus-validated.) Three defect classes:

1. **Per-connection failure mislabeled (the reported bug's class) — 58 sites.** A single connection/fd/listener failure builds `Closed(<driver label OR "PosixHandle">, …)`, so it renders "<Driver> is closed" (or "PosixHandle is closed") while the driver is alive. The detail already names the true resource (`channel=X`/`fd=X`/`socket#N`/`server channel=X`), contradicting arg1. Frame is also wrong: the throw-site `summon` OR an explicit `Frame.internal` (PosixHandle), never the connection's creation frame — and **no handle captures a creation frame** (`NioHandle.init`/`PosixHandle`/`JsHandle` take no `Frame`), so the fix requires threading the user's connect/accept frame onto the handle. Nio 13, Poller 20, IoUring 15, JsIoDriver 4, BlockingReaderDriver 3, PosixHandle 2, JsHandle 1.

2. **Genuine driver close, but ambient frame (9 sites).** `close()`/terminal-guarded sites correctly name the driver, but pass `summon[Frame]` (the caller's frame) instead of an explicit `Frame.internal` (driver is global-shared). Nio :1068; Poller :728,:857,:881,:1466; IoUring :247,:420,:474,:1282.

3. **`Closed` misused where nothing is closed (14 sites).** Unsupported/illegal-op stubs (Nio :857, Js :98-driver, IoUring :436) and the 11 transport/ReadPump timer-interrupt causes. The type is wrong: an illegal op / a timer-disarm / an interrupt is not a closure.

**Note the earlier skew this corrects:** judging the frame alone rated the `given Frame=Frame.internal` per-connection sites (e.g. Nio :1868, IoUring :1939-:2087) as "frame OK" — but coupled to the true resource (a per-connection object, not global-shared), `Frame.internal` is the wrong creation frame there. Resource and frame are one judgment.

## Fix directions (traced feasibility)

**The correct user creation frame is available at every handle-creation site and is currently DROPPED.** Public API captures it: `Transport.connect(...)(using …, Frame)` and `Transport.listen(...)(using …, Frame)`. It stays in scope down to handle creation but is not passed on:
- NIO connect: user frame live at `NioHandle.init` (NioTransport `:223/:265/:279`); accept: `acceptAllPending(...)(using …, Frame)` (from `listen`) live at `:457`. `NioHandle.init` (`:131`) takes no `Frame` → dropped.
- `PosixHandle` / `JsHandle` same shape (classes with `val` fields, factory `init`). `JsHandle.init` already takes `using Frame`.

**Frame threading MUST be an explicit `createdAt: Frame` parameter, NOT `using Frame`.** `NioHandle.init` has ~6 call sites (connect ×3, accept, upgrade re-handle ×2), several under a `given Frame = Frame.internal` or other ambient frame; an implicit would silently capture the wrong one (the exact bug class this fixes). An explicit param forces each call site to pass the deliberate creation frame and makes it reviewable.

- **Class 1 (~55 per-connection):** add `val createdAt: Frame` to each handle (`NioHandle`/`PosixHandle`/`JsHandle`), explicit `createdAt: Frame` param on their `init`/`initTls`, threaded from the connect/accept/listen user frame at each creation call site. Then per-connection `Closed` names the connection (its `handleLabel`/fd/socket id) with `handle.createdAt`. Also reconsider whether connect-refused / RST should be `NetConnectException` / `NetConnectionClosedException` rather than `Closed`.
- **Class 2 (9 driver closes):** pass explicit `Frame.internal` (driver is global-shared).
- **Class 3 (13 misuses):** stop using `Closed` — unsupported-op → an unsupported/illegal-op exception; timer-interrupt cause → an interrupt/`Interrupted` cause.

Scope: 3 handle types (field + explicit init param), all handle-creation call sites (thread the deliberate frame), ~55 Class-1 rewrites, 9 Class-2, 13 Class-3. Large but mechanical and uniform.
