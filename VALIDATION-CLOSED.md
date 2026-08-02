# Validation of `AUDIT-CLOSED.md` — independent re-derivation

Scope: independently re-verify every `Closed(...)` construction the audit classifies, against the
source. VALIDATE-ONLY (no source edits, no edits to `AUDIT-CLOSED.md`). All line numbers are as of the
current working tree.

## Headline verdict

The audit is **substantially correct for the 8 files it analyzed**: all 75 per-site verdicts stand, every
frame-binding (ambient vs `Frame.internal`) resolves as the audit states, the driver-close vs
per-connection split is right, all 13 Class-3 misuse claims hold, and the traced-fix feasibility claim is
accurate. The `0 fully coherent` headline is correct and survives every correction below.

It needs **three corrections**, one of them substantive:

1. **COMPLETENESS (substantive).** The audit's stated scope is "every `Closed(...)` exception construction
   in kyo-net" with a global tally of "~75 sites". The true count is **81**. Six genuine `kyo.Closed`
   constructions in **three files the audit never opened** are omitted: `BlockingReaderDriver.scala` (3),
   `JsHandle.scala` (1), `PosixHandle.scala` (2). All six are DEFECTIVE by the audit's own protocol, so the
   `0 coherent` headline is unaffected, but the total and the per-class tallies are wrong.
2. **CLASSIFICATION (minor).** IoUring `:436` ("awaitConnect on a handle with no connectTarget") is filed
   as Class-1 per-connection; it is really a Class-3 misuse (nothing is closed; `Closed` used for an
   illegal-op error, exactly parallel to the awaitAccept-not-supported stubs the audit already calls
   Class-3). Verdict DEFECTIVE is unchanged; the class label and the class counts shift.
3. **FACTUAL nits (harmless).** IoUring has a third `given Frame = Frame.internal` (`:499`, in `write`) the
   audit's IoUring section omits — it covers no `Closed` site, zero verdict impact. And ReadPump `:164`'s
   parenthetical "resolves to `Frame.internal` via its callers" is imprecise (it binds to `disarm`'s own
   `using Frame`, which is `Frame.internal` only on the `onExpiry`→`armTimer`→`disarm` path); moot for a
   misuse.

---

## 1. Per-file `Closed(` counts — re-grepped independently

Method-name / type-position exclusions applied: `isPeerClosed`, `isClosed`, `failClosed(` (a local def in
NioTransport at 933/944/945/982, a phantom of the `Closed(` regex), `: Closed`, `Abort[Closed]`,
`catch _: Closed`.

| file | audit | my count | match |
|---|---|---|---|
| NioIoDriver.scala | 15 | 15 (280,328,335,443,652,738,794,809,836,857,964,1047,1068,1174,1868) | ✓ |
| PollerIoDriver.scala | 24 | 24 (728,738,761,857,881,899,1236,1466,1550,1616,1656,1659,1710,1728,1856,1912,2074,2101,2151,2161,2173,2360,2424,2509) | ✓ |
| IoUringDriver.scala | 20 | 20 (204,228,247,272,297,336,420,436,474,481,898,1123,1282,1939,1958,1989,2024,2055,2078,2087) | ✓ |
| JsIoDriver.scala | 5 | 5 (67,98,105,128,159) | ✓ |
| NioTransport.scala | 2 | 2 (1275,1312) | ✓ |
| JsTransport.scala | 3 | 3 (397,456,1070) | ✓ |
| PosixTransport.scala | 5 | 5 (512,613,729,1292,1719) | ✓ |
| ReadPump.scala | 1 | 1 (164) | ✓ |
| **8-file total** | **75** | **75** | ✓ |

**No phantom and no missed site *within the 8 listed files*.** But a repo-wide sweep
(`grep -rn --include='*.scala' 'Closed(' kyo-net` minus method names and tests) returns **81** constructions.
The extra 6 live in three files the audit does not list (see §6).

---

## 2. Frame bindings — re-resolved

`summon[Frame]` binds to the nearest enclosing `given Frame` / method `using Frame`.

- **NioIoDriver.** Only these methods declare `given Frame = Frame.internal`: `applyUpgradeArm`
  (437→covers 443), `deliverStagedToArm` (639→652), `failConsumedUpgradeRead` (737→738),
  `stopUpgradeProducer` (788→794), `dispatchConnect` (1853→1868). Every other `Closed` site binds the
  method's `using Frame` (AMBIENT). Matches the audit exactly.
- **PollerIoDriver.** The only `given Frame` are `inline given Frame = Frame.internal` at 911 (`write`) and
  969 (`writeRaw`); neither method contains a `Closed`. So **all 24** sites bind AMBIENT. Matches the audit.
- **IoUringDriver.** `given Frame = Frame.internal` at **499** (`write`), **1774** (`complete`),
  **2104** (`completeMultishot`). 1774 lexically encloses 1939/1958/1989/2024/2055/2078/2087 (INTERNAL);
  2104 and 499 enclose no `Closed`. All other sites AMBIENT. The audit correctly maps 1939-2087 to internal
  but **omits the 499 given** — harmless (no `Closed` there), noted for accuracy.
- **JsIoDriver.** No `given Frame`; all 5 AMBIENT. Matches.
- **Transports / ReadPump.** All bind the enclosing method's `using Frame` (AMBIENT), except the ReadPump
  `:164` note above.

---

## 3. Per-site re-derivation — CONFIRMATIONS

For each site I read the enclosing method, identified the resource actually being reported closed, and
compared to the audit. Load-bearing evidence quoted.

### NioIoDriver (15) — all match (0 coherent / 15 defective)

- **Per-connection, DEFECTIVE both** — 280, 328, 335 (`awaitRead`/`armRead`, all key off a single `handle`,
  detail = `handleLabel(handle)`), 443, 652 (upgrade arm/staged-delivery on one `handle`, internal frame),
  738, 794 (upgrade detach on one `handle`, internal), 809 (`awaitWritable` registerWrite failed), 836
  (`awaitConnect` registerConnect failed), 964 (`cleanupPending` — per-`handle` teardown from
  cancel/detach/closeHandle; `val closed = Closed(label,…,"${handleLabel(handle)} closed")` fails that
  handle's read/write/connect promises), 1868 (`dispatchConnect` connect refused, internal). ✓
- **Per-listener, DEFECTIVE both** — 1047 (`cleanupAccept(serverChannel)`, detail `server channel=…`), 1174
  (`awaitAccept(serverChannel,…)` registerAccept failed; the detail string does not name the channel but the
  enclosing method operates on a `serverChannel`, so the true resource is the listener). ✓
- **Driver-close, resource-OK / frame-DEFECTIVE** — **1068** (`close()`), guarded by
  `if closedFlag.compareAndSet(false, true)` (1064) and sweeping the whole `pendingReads` map; arg1=driver
  is correct, frame ambient. The driver IS genuinely closed here. ✓
- **Misuse (Class-3), DEFECTIVE** — **857** (`awaitAccept(handle,…)` stub). Scaladoc 852-855: "IoDriver
  contract stub… Fails loudly so an accidental caller gets an immediate error." Nothing is closed. ✓

### PollerIoDriver (24) — all match (0 coherent / 24 defective)

- **Driver-close, resource-OK / frame-DEFECTIVE (4)** — **728** (`awaitRead`), **857** (`armSocketWritable`),
  **881** (`awaitAccept`) each fire only under `if terminal.get()` (727/856/880), detail `"fd=X driver
  closed"`. `terminal` is set as the FIRST step of the poll-loop terminal exit (`terminal.set(true)` at 694,
  doc at 120-130), so when it is true the driver is tearing down / dead. The true closed resource is the
  driver; the fd in the detail is context. **1466** (`close()`) is the genuine whole-driver close
  (`closeReason = closed`, `closedFlag.compareAndSet`). All four: arg1 correct, frame should be
  `Frame.internal`, is ambient. ✓
- **Per-connection / fd, DEFECTIVE both (20)** — 738, 761 (upgrade detach), 899 (`dispatchAccept` stale
  recycled fd), 1236 (`deregisterFds` — per-`handle` cancel, fails that handle's read/write/accept/
  backpressure promises with `"fd=X/Y canceled"`), 1550 (`drainReady` stale-event orphan op), 1616
  (`dispatchError` — a per-fd RST/HUP, `if soError(fd) != 0`), 1656/1659 (`dispatchRead` stale / closed
  handle), 1710 (`finishDispatch`), 1728 (`rearmOwned`) — all "read on closed handle fd=X" for a handle
  whose `endDispatch()` observed the close, 1856 (`dispatchReadPlain` recv errno), 1912
  (`failConsumedUpgradeRead`), 2074/2101/2151/2161 (`dispatchReadTls` recv/engine per-fd), 2173
  (`dispatchWritable` stale writable), 2360 (`applyRegistration` "register on closing handle" —
  `if handle.isClosing()`), 2424 (`dispatchCmd` backend register rc<0), 2509 (`dispatchCmd` OpDeregister
  `if fdClosing`). None is a whole-driver sweep — each targets one handle/fd. ✓

### IoUringDriver (20) — all match on verdict; one class refinement (see §4)

- **Driver-close, resource-OK / frame-DEFECTIVE (4)** — **247** (`submitDeferredRecv`), **420**
  (`submitConnect`), **474** (`submitAccept`) each under `if closedFlag.get()` (246/418/470), detail
  "driver closed". `closedFlag` is set by `close()` (1171) or the terminal exit (1657). **1282**
  (`teardownRing`, `if teardownDone.compareAndSet(false,true)`) is the whole-driver teardown, sweeping
  `pending`. arg1 correct, frame ambient. ✓
- **Per-connection, ambient frame (8, plus 436 reclassified)** — 204, 228 (upgrade detach), 272
  ("fd=X closed", readBuffer already closed), 297 (exclusive-use violation — note it calls
  `closeHandle(handle)` at 298, so the connection IS closed → per-connection is right), 336 (recv negative
  length rejected → per-fd read failure), 481 ("listener closed", `else if handle.isClosing()` → resource
  is the listener), 898 (`cancel`, filters `op.handle.id.packed == handle.id.packed` → per-connection, not a
  driver sweep), 1123 (`closeNow` "connection closed", filters by handle id). ✓  (**436** is per the audit
  in this group; I move it to Class-3 — §4.)
- **Per-connection, internal frame (7)** — 1939 (buffer-role mismatch, then `closeHandle(h)`), 1958
  (ownership mismatch, then `closeHandle(h)`), 1989 (fatal TLS record), 2024 (TLS engine read throw), 2055
  (read errno), 2078 (connect errno), 2087 (accept errno). All lexically under `complete`'s
  `given Frame = Frame.internal` (1774); each is one connection/listener; `Frame.internal` is the wrong
  creation frame for a per-connection resource. ✓

### JsIoDriver (5) — all match (0 coherent / 5 defective)

- Per-connection (one socket), DEFECTIVE both — 67, 105 ("socket destroyed"), 128 ("socket closed/error
  before writable", inside `completeFailure` → binds `awaitWritable`'s using Frame), 159 (`cancel`,
  "socket#N canceled"). ✓
- Misuse (Class-3), DEFECTIVE — **98** (`awaitAccept` "not supported on JsIoDriver"; scaladoc "fail fast so
  a caller that accidentally uses this path gets an immediate error"). Nothing closed. ✓

### Transport / ReadPump interrupt-cause misuses (11) — all match (Class-3)

Every one passes `Result.Panic(Closed(...))` (or `Result.panic`) as an interrupt/cancellation cause;
nothing is closed. Verified in context:

- NioTransport **1275** (`armHandshakeDeadline`, `timer.interruptDiscard(… "handshake completed before
  deadline")` on `connPromise.onComplete`), **1312** (`armConnectDeadline`, `disarm()`, "connect completed
  before deadline"). ✓
- JsTransport **397** ("connect completed"), **456** ("handshake settled"), **1070** ("upgrade settled") —
  all `deadline/timer.interruptDiscard` disarms fired from `promise.onComplete`. ✓
- PosixTransport **512** ("connect completed"), **729** ("handshake settled"), **1292** ("handshake
  completed"), **1719** ("upgrade settled") — deadline disarms. **613** is a *slightly different shape*:
  `writablePromise.completeDiscard(Result.panic(Closed("PosixTransport",…, "connect interrupted before
  completion")))` forwards an external interrupt to the parked writable arm (comment 607-611). Still nothing
  closed — a cancellation signal — so Class-3 holds; rendered it would read "PosixTransport … is closed.
  connect interrupted before completion", and the transport is alive. ✓
- ReadPump **164** (`disarm`, "grace disarmed by progress"): interrupts the grace timer fiber when the
  consumer made progress. Nothing closed. ✓

---

## 4. DISAGREEMENTS

Only one site where my verdict *classification* differs. The COHERENT/DEFECTIVE verdict is the same
(DEFECTIVE) in every case; the difference is the defect **class**, which changes the audit's tallies.

| site | audit verdict / class | my verdict / class | evidence |
|---|---|---|---|
| **IoUringDriver.scala:436** | DEFECTIVE, **Class-1 (per-connection, "no connectTarget")** | DEFECTIVE, **Class-3 (misuse — nothing closed)** | Lines 434-436: `case Absent =>` for `handle.connectTarget`, comment "No stashed connect target: the handle was not created for a client connect. **Fail loudly** rather than submit a bad SQE", then `Closed(label, …, "awaitConnect on a handle with no connectTarget")`. No `closeHandle`, no fd/handle close; the handle is merely mis-configured. This is a categorical illegal-op error, exactly the shape of the awaitAccept-not-supported stubs (Nio :857, Js :98) the audit itself files as Class-3. Rendered "IoUringDriver … is closed. awaitConnect on a handle with no connectTarget" — the "is closed" claim is nonsensical, the Class-3 signature. |

Impact if accepted: IoUring Class-1 16 → 15; global Class-3 misuses 13 → 14; the consolidated
"unsupported-op stubs" set gains this handle-level illegal-op.

Two neighbours I examined and **agree with the audit** (kept as Class-1), for the record:
- IoUring **:297** (exclusive-use violation) — calls `closeHandle(handle)` at 298, so the connection is
  genuinely closed; per-connection is correct.
- IoUring **:336** (recv negative-length rejected) — a per-fd read failure that tears the connection's read
  down, consistent with the other "recv failed" per-connection sites; per-connection is defensible.

No disagreement on any driver-close site (all 9 are genuinely under a `closedFlag`/`terminal`/`teardown`
guard and sweep or reject at driver scope) and no per-connection site is secretly a whole-driver sweep
(each `cancel`/`cleanup`/`deregister` targets one `handle`, filtering by handle id where it iterates a map).

---

## 5. FEASIBILITY of the traced fix — CONFIRMED (not overstated)

Claim under test: the user creation frame is in scope at every handle-creation site, but is dropped because
the handle types capture no frame.

- **NIO.** `NioTransport.connect` (201, `using AllowUnsafe, Frame`) creates the handle at 223/265/279;
  `listen` (347, `using … Frame`) → `acceptAllPending` (437, `using AllowUnsafe, Frame`) creates it at 457.
  `NioHandle.init` (131) and `initTls` (136) take **`(using AllowUnsafe)` only — no `Frame`**. `NioHandle`
  (class 36-43: `channel, readBufferSize, tls, peerCloseGrace`) has **no `createdAt` field**. Audit
  citations `:223/:265/:279`, `:457`, `init :131` are all accurate. ✓  (Aside: `NioHandle.UpgradeHandoff.
  Waiter` carries a per-waiter `frame: Frame`, but that is a parked-read frame, not the handle's birth — it
  does not contradict "no handle captures a creation frame".)
- **Posix.** `connect` (254) → `connectImpl` (423, `)(using AllowUnsafe, Frame)`), `listen` → `listenImpl`
  (943, `)(using AllowUnsafe, Frame)`), and the accept `acceptAll` (1079, `using AllowUnsafe, Frame`) all
  carry a user Frame; `PosixHandle.socket` is created at 451/1015/1135 inside them. `PosixHandle.socket`
  (651) takes **`(using AllowUnsafe)` only**. `PosixHandle` (class 27-…) has **no `createdAt` field**. ✓
- **JS.** `JsHandle.init` (58) **already takes `(using AllowUnsafe, Frame)`**, as the audit says, but
  `JsHandle` (class 16: `val socket, val id`) **stores no frame** — the frame is passed and dropped. ✓

The audit did not overstate feasibility anywhere; a deliberate user Frame is genuinely available at every
handle-creation call site and is dropped.

---

## 6. COMPLETENESS GAP — 6 `Closed(...)` sites the audit never analyzed

The audit's global claim ("every `Closed(...)` exception construction in kyo-net", "~75 sites") is
incomplete. kyo-net contains **81** constructions; the following six, in three files outside the audit's
8-file scope, were not analyzed. All six are DEFECTIVE under the audit's own protocol (a per-connection
resource named by a driver/type label and given an ambient or `Frame.internal` createdAt).

| file:line | enclosing / trigger | true resource | arg1 | arg2 binds | verdict (my derivation) |
|---|---|---|---|---|---|
| BlockingReaderDriver.scala:46 | `awaitRead`, read(2) errno<0 | one connection (fd) | `label` = "BlockingReaderDriver" ✗ | ambient (awaitRead using Frame) ✗ | DEFECTIVE both — Class-1 (per-connection read failure) |
| BlockingReaderDriver.scala:65 | `awaitRead`, inline read fiber Failure | one connection (fd) | driver label ✗ | ambient ✗ | DEFECTIVE both — Class-1 |
| BlockingReaderDriver.scala:80 | `awaitRead`, pending read fiber Failure | one connection (fd) | driver label ✗ | ambient ✗ | DEFECTIVE both — Class-1 |
| JsHandle.scala:98 | permanent "error" listener in `init` | one socket (connection) | `driver.label` ✗ | `init`'s using Frame | DEFECTIVE resource — Class-1 (per-connection "socket error"); note arg2 here is the init frame |
| PosixHandle.scala:754 | `freeResources`, backpressure park | one connection (fd=X/Y) | `"PosixHandle"` (type name, not the connection) ✗ | `Frame.internal` (explicit) ✗ | DEFECTIVE both — per-connection resource, `Frame.internal` is wrong for a non-global resource |
| PosixHandle.scala:783 | `freeResources`, queued recv | one connection (fd=X/Y) | `"PosixHandle"` ✗ | `Frame.internal` (explicit) ✗ | DEFECTIVE both — per-connection, `Frame.internal` wrong |

Evidence highlights:
- `BlockingReaderDriver.label` = `"BlockingReaderDriver"` (25); no `given Frame` between `awaitRead` (31) and
  any of 46/65/80, so all bind ambient. Each fails a single handle's read promise with `"read failed
  fd=…"` / `"read fiber failed fd=…"` — the same Class-1 shape as Poller `:1856`.
- `JsHandle.scala:98`: `pending.completeDiscard(Result.fail(Closed(driver.label, summon[Frame], "socket
  error")))` — a per-socket error mislabeled with the driver label.
- `PosixHandle.scala:754/783` are distinct in flavour: arg1 is the literal `"PosixHandle"` (a type name, not
  the driver and not the specific connection) and arg2 is an explicit `Frame.internal`. Under the protocol
  that is still DEFECTIVE (a per-connection resource is not global-shared, so `Frame.internal` is the wrong
  createdAt, and the arg1 does not identify the specific connection). Nearby line 769 uses
  `NetConnectionClosedException` (correctly not a `Closed`), so it is out of scope.

Corrected tallies (audit → corrected):
- Total `Closed(...)` constructions: ~75 → **81**.
- Class-1 (per-connection): ~55 → **~60** (audited 52 minus IoUring :436 reclassified = 51, plus the 6
  omitted, of which 5 are Class-1 and 1 (JsHandle:98) is Class-1-on-resource = 51 + 6 = **57 clearly
  per-connection**; the audit's "~55" is already approximate — the point is it grows, not shrinks).
- Class-2 (driver-close): **9** (unchanged; none omitted).
- Class-3 (misuse): 13 → **14** (adding IoUring :436).
- Fully coherent: **0** (unchanged — the six omitted sites are all defective).

---

## 7. FINAL VERDICT

**The audit is NOT correct as-is; it is correct-but-incomplete and needs the following precise corrections.**

Within its 8-file scope the audit is accurate: 75/75 sites re-derived to the same COHERENT/DEFECTIVE
verdict, all frame bindings resolve as stated, the resource-OK driver-close vs per-connection split is right
(all 9 driver-close sites are under a real `closedFlag`/`terminal`/`teardown` guard; no per-connection site
is a disguised whole-driver sweep), all 13 Class-3 misuse claims hold, and the traced-fix feasibility claim
(user frame available at every handle-creation site, dropped by frameless handle types) is confirmed for
NIO, Posix, and JS.

Corrections a human should apply to `AUDIT-CLOSED.md`:

1. **Add the three omitted files (6 sites), all DEFECTIVE.** `BlockingReaderDriver.scala:46,65,80` (Class-1
   per-connection read failures); `JsHandle.scala:98` (Class-1 per-connection "socket error");
   `PosixHandle.scala:754,783` (per-connection handle-close; arg1 the literal `"PosixHandle"`, arg2 an
   explicit `Frame.internal` that is wrong for a non-global resource). Update the global total from
   "~75" to **81** and the class tallies accordingly.
2. **Reclassify IoUring `:436`** from Class-1 (per-connection) to **Class-3 (misuse — nothing closed)**,
   grouping it with the awaitAccept-not-supported stubs. IoUring Class-1 16→15; global Class-3 13→14.
3. **Fix two harmless factual nits:** note the third `given Frame = Frame.internal` at IoUring `:499`
   (`write`, no `Closed` — no impact); and drop / correct the ReadPump `:164` "resolves to `Frame.internal`
   via its callers" parenthetical (it binds to `disarm`'s own `using Frame`, `Frame.internal` only on the
   `onExpiry`→`armTimer`→`disarm` path).

None of these changes the audit's central finding: **0 of the `Closed(...)` sites in kyo-net are fully
coherent**, and the per-connection/driver/misuse three-class structure and the fix directions are sound.
