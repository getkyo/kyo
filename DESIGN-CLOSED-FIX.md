# Design: fixing the 81 `kyo.Closed(...)` sites in kyo-net

Companion to `AUDIT-CLOSED.md` (the validated inventory). This document specifies the target state: the new exception types, the explicit
creation-frame threading, the per-class and per-site replacements for all 81 sites, and the compatibility impact. It proposes; it changes no
source.

---

## 1. Grounding: the contracts and precedents this design is built on

Every element of the design below is anchored to an existing pattern in the codebase. Citations are file:line in this worktree.

**C1. The `Closed` contract** (`kyo-core/shared/src/main/scala/kyo/Closed.scala:5`):
`final class Closed(resource: String, createdAt: Frame, details: String = "")(using Frame)` renders
`"<resource> created at <createdAt> is closed. <details>"`. One claim: this named resource, born at this frame, is closed.
kyo-core resources satisfy it by capturing an `initFrame` at creation and passing it as `createdAt`:
`Queue.scala:557` (`Closed("Queue", initFrame)`), `Channel.scala:424` (`Unsafe.init(...)(using initFrame: Frame, ...)`) and
`Channel.scala:492` (`Closed("Channel", initFrame, "zero-capacity")`). kyo-net handles capture no such frame today; that is the structural
gap this design closes.

**C2. The `NetException` house style** (`kyo-net/shared/src/main/scala/kyo/net/NetException.scala`):
- Sealed abstract families (`NetException:21`, `NetConnectionException:45`, `NetTlsException:105`, `NetCapabilityException:134`), all
  `(using Frame)`, all extending `KyoException(message, cause)`.
- `final case class` leaves carrying structured fields only (host, port, path, provider, backend, operation), message rendered in this one
  file, cause as `String | Throwable = ""` suffixed via `NetException.suffix` (`:34-36`). "A backend constructs a leaf from structured data
  alone ... and never authors failure prose at the call site" (`:18-19`).
- `NetErrno(code)` (`:42`) is the structured cause for a raw OS error number.
- `NetConnectionClosedException(operation, cause)` (`:78-102`) shows the typed-operation-enum leaf shape (`Operation(val label: String)`
  embedded into the message).
- `NetStdioUnsupportedException()` (`:156-157`), `NetSocketOptionUnsupportedException(option)` (`:142-143`) and
  `NetBackendUnavailableException(backend, cause)` (`:146-153`) show the `NetCapabilityException` unsupported-operation leaf shape.
- The header doc (`:10-13`) fixes the seam contract: `NetException` is DISJOINT from `Closed`; transport failures travel
  `Abort[NetException]`, genuine channel/resource closes travel `Abort[Closed]`. The design preserves this at the public seam.

**C3. The explicit-frame precedents** (the design directive "explicit `createdAt: Frame`, never `using Frame`" is already kyo-net practice
in two places):
- `PosixHandle.UpgradeHandoff.Waiter(promise: Promise.Unsafe[Span[Byte], Abort[NetException]], frame: Frame)`
  (`PosixHandle.scala:628-632`), failed at `PosixHandle.scala:769` with `NetConnectionClosedException(Operation.Upgrade)(using fr)`: an
  internal promise whose failure leaf is constructed with an explicitly carried frame, applied via `(using fr)` so the rendered position is
  the frame captured at park time, not the throw site.
- `Connection.upgradeFn: Maybe[(NetTlsConfig, Frame) => ...]` (`transport/Connection.scala:54`): an explicit `Frame` value parameter in an
  internal function signature.

**C4. The interrupt-cause precedent** (`kyo-core/shared/src/main/scala/kyo/Interrupted.scala:3-6`):
`final case class Interrupted(at: Frame, by: Maybe[String] = Absent)` with the `apply(at, by: String)` helper. kyo interrupts fibers with
`interruptDiscard(Result.Panic(Interrupted(frame)))`: `Fiber.scala:326`, `Cache.scala:231`, `Exchange.scala:387,397`, and with a reason
string at `KyoAppInterrupts.scala:14` (`Interrupted(Frame.internal, s"Interrupt Signal: $signal")`). This is the canonical cause for a
disarmed/superseded timer, exactly the Class-3 interrupt sites.

**C5. The driver read contract that is already written but not implemented**
(`transport/IoDriver.scala:35-48` and `transport/ReadOutcome.scala:23-30`):
the `awaitRead` scaladoc specifies `Success(ReadOutcome.Failed(cause))` for "a typed hard error" and reserves `Failure(Closed)` for "the
read was cancelled (detach/close)". `ReadOutcome.Failed(cause: NetException)` exists (`ReadOutcome.scala:29`) and `ReadPump.onComplete`
already consumes it (`ReadPump.scala:67-69`: logs the cause, tears down), but **no production site constructs it** (verified: zero
producers). Every read-path hard error today violates this contract by failing the promise with `Closed`. The design activates the
documented contract instead of inventing a new channel.

**C6. Union `Abort` rows are established kyo practice**: `Command.scala:249`
(`Fiber.Unsafe[Unit, Abort[CommandException | ExitCode]]`), `StreamCoreExtensions.scala:380,488,600,716,1077` (`Abort[E | Closed]`),
`Async.scala:167` (`Abort[E | Timeout]`). Used below for the two driver rows that genuinely carry both closures and non-closure failures.

**C7. The misuse precedent** (`PosixHandle.scala:825-839`): the `NoDriver` sentinel treats a driver op invoked on an unbound handle as a
programming error and surfaces it loudly (`UnsupportedOperationException`), not as a typed transport failure. The three Class-3 stub sites
are the same category (guards on paths production never routes), so they surface as panics carrying a typed capability leaf.

**C8. `KyoException` frame rendering** (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:23-61`): the `(using Frame)` a leaf is
constructed with is the position the dev-mode render shows. Hence for every new per-connection leaf the construction site passes
`(using handle.createdAt)`, the C3 precedent, so the user sees their own connect/listen call site.

---

## 2. Design overview: three moves

1. **Capture the creation frame on every handle** as an explicit `createdAt: Frame` constructor field and factory parameter (never
   `using`), threaded from the public `Transport.connect/listen/stdio/upgrade` entry points (which all already take `using Frame`:
   `Transport.scala:38-124`). Accepted connections carry the LISTEN frame; a JS upgrade re-handle carries the ORIGINAL connection's frame;
   NIO/posix upgrades reuse the same handle so the frame survives automatically.

2. **Say what actually happened, per site.**
   - A genuine close/cancel/detach/stale of a connection or listener stays `Closed`, but names the connection/listener and carries
     `handle.createdAt` (Class 1, 39 sites).
   - A hard I/O error on the read path becomes `Success(ReadOutcome.Failed(<leaf>))` per the C5 contract (12 sites).
   - A connect/accept OS failure becomes a typed `NetException` failure on a widened promise row (3 sites).
   - A driver invariant breach becomes a `Result.Panic(<leaf>)`: defects travel the panic channel (4 sites).
   - A genuine driver close keeps the driver as resource and passes explicit `Frame.internal` (Class 2, 9 sites).
   - An unsupported/illegal driver op panics with a capability leaf (3 sites); a timer-disarm cause becomes
     `Result.Panic(Interrupted(frame, reason))` (11 sites) (Class 3).

3. **Widen exactly two driver promise rows** (`awaitConnect`, `awaitAccept`) from `Abort[Closed]` to `Abort[Closed | NetException]`,
   because those are the only rows whose real failure vocabulary contains non-closures (connect refused, accept errno). The read and
   writable rows keep `Abort[Closed]`: the read row already has its typed hard-error vehicle (`ReadOutcome.Failed`), and every writable-row
   failure is a genuine closure.

What the user sees after the fix, for the originally reported bug (connect refused on NIO):
today: `connect to h:p failed: NioIoDriver[sel=..] created at ...NioIoDriver.scala:1853 is closed. finishConnect failed for channel=N: Connection refused`.
after: `connect to h:p failed: connection channel=N connect failed: java.net.ConnectException: Connection refused`, with the leaf's frame
rendering the user's own `Transport.connect` call site.

---

## 3. New and changed exception types

Both new types live in `NetException.scala` (the hierarchy is sealed, so same-file is mandatory). Proposed visibility `private[net]`
(they are driver-internal vocabulary; users keep interacting with the existing public leaves that wrap them). See Open Decision D2.

### 3.1 `NetConnectionIoException`: a driver-layer I/O failure on one connection or listener

Mirrors `NetConnectionClosedException` (`NetException.scala:78-102`) exactly: same family (`NetConnectionException:45`), same
typed-operation-enum shape, same cause handling, message rendered here and nowhere else.

```scala
/** An I/O operation on a single connection or listener failed at the driver layer: the OS rejected a connect, a recv returned an error
  * number, a TLS record could not be processed, or an accept failed. `resource` is the driver's label for the specific fd/channel/socket
  * (never the driver itself), `operation` (a [[NetConnectionIoException.Operation]]) names what failed, and `cause` carries the underlying
  * [[NetErrno]] or engine `Throwable`. A consumer branches on the typed fields, never on message text.
  *
  * Construction sites pass the connection's creation frame explicitly, `(using handle.createdAt)`, so the rendered position is the user's
  * `connect`/`listen` call, not a driver internal (the same discipline as the upgrade-handoff waiter failure in `PosixHandle`).
  *
  * Internal to the driver seam: it reaches a caller only as the `cause` inside a public leaf (for example [[NetConnectException]] built by
  * the transport's connect wrap) or inside a [[kyo.net.internal.transport.ReadOutcome.Failed]] the read pump logs at teardown.
  */
final private[net] case class NetConnectionIoException(
    resource: String,
    operation: NetConnectionIoException.Operation,
    cause: String | Throwable = ""
)(using Frame) extends NetConnectionException(s"$resource ${operation.label} failed${NetException.suffix(cause)}", cause)

private[net] object NetConnectionIoException:
    /** The driver-layer operation that failed. `label` is the lowercase name embedded in the rendered message, preserving the
      * "<resource> <label> failed" shape.
      *
      *   - [[Connect]]: the OS reported the non-blocking connect failed (refused, unreachable, reset, errno from the connect CQE).
      *   - [[Receive]]: a recv/read returned an error (errno, a failed read fiber, a socket error event).
      *   - [[Decrypt]]: the TLS engine could not process received ciphertext (a thrown engine op, a fatal record).
      *   - [[Accept]]: the accept operation itself failed with an error number (the listener may still be open).
      */
    enum Operation(val label: String) derives CanEqual:
        case Connect extends Operation("connect")
        case Receive extends Operation("receive")
        case Decrypt extends Operation("TLS decrypt")
        case Accept  extends Operation("accept")
    end Operation
end NetConnectionIoException
```

Cause discipline per producing site (no call-site prose beyond what already exists as a structured value):
- errno sites pass `NetErrno(code)` (`NetException.scala:42`), e.g. rendered `"fd=42/42 receive failed: errno=104"`.
- engine-throw sites pass the caught `Throwable`.
- the four invariant-breach sites pass their existing diagnostic string as the cause (they are Panic-channel defects whose message is the
  diagnostic; the sites already `Log.error` the same text today, see IoUringDriver:295, :1949, :1966).

### 3.2 `NetDriverUnsupportedException`: an illegal/unsupported driver operation

Mirrors the `NetCapabilityException` leaves (`NetException.scala:142-143`, `:146-153`, `:156-157`): family "the transport/connection does
not support the requested operation" (`:133`), structured fields only.

```scala
/** An [[kyo.net.internal.transport.IoDriver]] operation was invoked on a backend or handle that cannot serve it: the NIO and JS drivers'
  * `awaitAccept(handle)` stubs (those transports drive their own accept seams), or an io_uring `awaitConnect` on a handle created without
  * a connect target. Always a programming error inside kyo-net's own wiring, never an environmental failure, so producers deliver it as a
  * `Result.Panic` on the pending promise (the promise-completing analog of the `NoDriver` sentinel's loud `UnsupportedOperationException`).
  */
final private[net] case class NetDriverUnsupportedException(backend: String, operation: String)(using Frame)
    extends NetCapabilityException(s"'$operation' is not supported by I/O backend '$backend'")
```

`backend` is the driver `label` (`IoDriver.scala:191`); `operation` is a short operation identifier ("awaitAccept",
"awaitConnect: no connect target"), not prose.

### 3.3 Changed rows on the `IoDriver` seam

`transport/IoDriver.scala`:

```scala
def awaitConnect(handle: Handle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit
def awaitAccept(handle: Handle, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit
```

(union-row precedent C6). Failure vocabulary, to be written into the scaladoc:
- `Failure(Closed)`: the connection/listener/driver is closed or the op was cancelled (loop-stop semantics for the accept loop, unchanged).
- `Failure(NetConnectionIoException)`: the OS reported the operation failed; nothing was closed by the driver.
- `Panic(NetDriverUnsupportedException)`: the call itself was illegal on this backend/handle.

`awaitRead`, `awaitReadHandshake`, `awaitWritable` keep `Abort[Closed]`; the `awaitRead` scaladoc bullet list (`IoDriver.scala:38-45`)
becomes true rather than aspirational, and gains a note that `Failed` carries `NetConnectionIoException` with the connection's creation
frame.

**No existing type changes shape.** `Closed`, `NetConnectException`, `NetConnectionClosedException` and its `Operation` enum,
`ReadOutcome`, `NetErrno` are all reused as-is. In particular the earlier idea of adding `Operation.Connect` to
`NetConnectionClosedException` is NOT needed: under the union rows, driver-closed/cancelled connects legitimately stay `Closed`.

---

## 4. Frame threading design

Rule, stated once and applied everywhere: **the creation frame is an explicit `createdAt: Frame` value parameter on every handle
constructor, every handle factory, and every private transport helper between a public entry point and a handle creation. No handle
factory takes `using Frame`.** Each public transport entry point binds it once (`val createdAt = summon[Frame]`) from its own
`using Frame` (which is the user's call frame, per the `Transport` trait, `Transport.scala:38-124`) and passes it explicitly. This is the
audit's finding made policy: several creation sites sit under `given Frame = Frame.internal` scopes, so an implicit would silently
capture the wrong frame; an explicit parameter makes every choice reviewable.

### 4.1 `NioHandle` (`jvm/.../NioHandle.scala`)

- Class: add `val createdAt: Frame` as the last constructor parameter (`NioHandle private (channel, readBufferSize, tls, peerCloseGrace, createdAt)`).
- Factories (`:131`, `:136`):
  `def init(channel: SocketChannel, bufferSize: Int, peerCloseGrace: Duration, createdAt: Frame)(using AllowUnsafe): NioHandle`
  `def initTls(channel: SocketChannel, bufferSize: Int, engine: SSLEngine, peerCloseGrace: Duration, createdAt: Frame)(using AllowUnsafe): NioHandle`
  (`initTls` has no production callers, only tests: NioHandleTest, NioHandleEngineGateTest, NioEngineOwnershipTest. Signature updated for
  uniformity; the dead-in-production status is flagged in section 7 as an observation.)
- Call sites (all in `NioTransport.scala`), with the deliberate frame each must pass:

| site | context | frame passed |
|---|---|---|
| :223 | `connect`, immediate connect | `createdAt` bound at `connect` entry (user connect frame) |
| :265 | private `awaitConnect`, `tryFinishConnect` | new `createdAt: Frame` param on `awaitConnect`; callers :231 (`connect`) and :1351 (`connectUnix`) pass their entry frame |
| :279 | private `awaitConnect`, pending-connect handle | same param |
| :457 | `acceptAllPending` (plain accept) | `listener.createdAt` (see NioListener below): the LISTEN frame |
| :609 | `awaitConnectThenTls` | new `createdAt: Frame` param, from `connectTls` entry (:497) |
| :740 | `startTlsHandshake`, `existingHandle.getOrElse` fresh-handle branch | new `createdAt: Frame` param; fresh TLS connect passes the `connectTls` frame; the STARTTLS path passes `existingHandle` so no creation happens (same handle spans the upgrade, `NioHandle.scala:40-42`) |
| :1176 | `acceptAllPendingTls` | `listener.createdAt` (listenTls frame) |
| :1346 | `connectUnix`, immediate connect | `connectUnix` entry frame |

- **NIO listener frame carrier**: `NioListener` (`NioTransport.scala:1721`) gains a `createdAt: Frame` field, set at its three
  construction sites (:370 `listen`, :1120 `listenTls`, :1384 `listenUnix`) from the entry frame. It feeds:
  - accepted-connection handles (:457, :1176 above),
  - the two server-channel driver seams, which have no handle to carry a frame:
    `NioIoDriver.awaitAccept(serverChannel, promise, createdAt: Frame)` (`:1168`) and
    `NioIoDriver.cleanupAccept(serverChannel, createdAt: Frame)` (`:1046`), called from the accept pump
    (`startAcceptLoopWith`, which receives `listener`) and the listener close (`NioTransport.scala:1749`).
- **NIO connect identity carrier**: `pendingConnects` (`NioIoDriver.scala:80-81`) currently maps `SocketChannel -> promise`, so
  `dispatchConnect` (:1852) has no handle and no frame. Its value type becomes a small case class holding `(promise, handle)` (the
  `ReadArmCell` naming pattern, `NioHandle.scala:18`), giving :1868 both `handleLabel(handle)` and `handle.createdAt`.
- NIO stdio (`NioTransport.scala:173`) creates no `NioHandle` (it goes through `NioStdioConnection.open`), and has no audited site: no change.

### 4.2 `PosixHandle` (`shared/.../posix/PosixHandle.scala`)

- Class: add `val createdAt: Frame` constructor parameter (alongside the other identity fields, `:27-34`).
- Factories:
  `def socket(fd: Int, bufSize: Int, connectTarget: Maybe[(Buffer[Byte], Int)], createdAt: Frame)(using AllowUnsafe): PosixHandle` (`:651`)
  `def stdio(bufSize: Int, createdAt: Frame)(using AllowUnsafe): PosixHandle` (`:671`)
- Call sites (all in `PosixTransport.scala`):

| site | context | frame passed |
|---|---|---|
| :214 | `stdio` | `stdio` entry frame (:208 `using Frame`) |
| :451 | `connectImpl` (client connect, carries `connectTarget`) | new `createdAt: Frame` param threaded `connect`(:254)/`connectTls`(:263)/`connectUnix`(:310) -> `connectResolving`(:279) -> `connectImpl`(:423) |
| :1015 | `startAcceptLoop` (the listen-fd handle) | new `createdAt: Frame` param threaded `listen`(:870)/`listenTls`(:876)/`listenUnix`(:916) -> `listenResolving`(:888) -> `listenImpl`(:943) -> `startAcceptLoop`(:1007) |
| :1135 | `handleAccepted` (accepted client fd) | same listen frame, threaded `startAcceptLoop` -> `handleAccepted`(:1120): accepted connection carries the LISTEN frame |

- posix STARTTLS upgrades are in-place (no new handle; `upgradeToTls` :1543 re-drives the same fd), so the original frame survives with no
  extra work. Verified: `PosixHandle.socket`/`stdio` have exactly the four production call sites above.

### 4.3 `JsHandle` (`js-wasm/.../JsHandle.scala`)

- Class: add `val createdAt: Frame` to the constructor (`:16`).
- Factory (`:58`): `def init(socket: js.Dynamic, driver: IoDriver[JsHandle], createdAt: Frame)(using AllowUnsafe): JsHandle`.
  The current `using Frame` (which exists only to feed the `:98` error-listener `Closed`) is REMOVED: the factory takes no implicit frame,
  per the directive.
- Call sites (all in `JsTransport.scala`):

| site | context | frame passed |
|---|---|---|
| :474 | `connectSocket` connect callback | new `createdAt: Frame` param on `connectSocket`(:405), bound at `connect`(:118)/`connectTls`(:134) entries |
| :573 | `listenServer` "connection" callback | new `createdAt: Frame` param on `listenServer`(:542), bound at `listen`(:127)/`listenTls`(:199) entries: accepted connection carries the LISTEN frame |
| :664 | `connectUnix` connect callback | `connectUnix`(:632) entry frame |
| :710 | `stdio` | `stdio`(:697) entry frame |
| :795 | `listenUnix` "connection" callback | `listenUnix`(:772) entry frame |
| :1080 | STARTTLS upgrade re-handle (`upgradeToTls` builds a NEW JsHandle over the tls socket) | `handle.createdAt` of the ORIGINAL connection's handle: the upgraded connection keeps its birth frame, mirroring `newHandle.peerCloseGrace = handle.peerCloseGrace` on the next line (:1081) |

**Confirmation of the no-implicit rule**: after this change, no handle constructor or factory has `using Frame`; `summon[Frame]` never
appears in any handle-creation or `Closed`/leaf-identity position; each of the 18 creation call sites above names its frame explicitly.

---

## 5. Per-class fix specification

### Class 1 (58 sites): per-connection/listener failures, four vehicles by what actually happened

Resource-string convention: connection sites use `s"connection ${handleLabel(handle)}"` (renders "connection fd=42/42",
"connection channel=123", "connection socket#7"); listener sites use `s"listener ${handleLabel(handle)}"` (posix) or
`s"listener channel=${serverChannel.hashCode()}"` (NIO server channel); the two mixed sweeps that fail reads, writables and accepts with
one value (Poller :1236, :2509) use the bare `handleLabel(handle)` since the handle may be either. Details drop the now-redundant handle
label (the resource names it), keeping the event description ("detached for upgrade", "canceled", "stale read event", ...).

- **1a. Genuine closure/cancellation (39 sites)**: `Closed(<resource>, handle.createdAt, <detail>)`. The `(using Frame)` throw-site
  parameter of `Closed` stays whatever is ambient (it only affects the dev-render header position, C8); the contractual pair is
  arg1+arg2.
- **1b. Read-path hard error (12 sites)**: complete the read promise with
  `Result.succeed(ReadOutcome.Failed(NetConnectionIoException(<resource>, <op>, <cause>)(using handle.createdAt)))`, activating the C5
  contract. `ReadPump` handles it today (`ReadPump.scala:67-69`: debug-log plus teardown), and the transports' handshake-read matches
  either handle `Failed` or fall through wildcard failure branches (`NioTransport.scala:982`); every `ReadOutcome` match must be re-audited
  when the case goes live (the enum case already exists, so this is semantic review, not a compile break).
- **1c. Connect/accept OS failure (3 sites)**: fail the (now union-row) promise with
  `Result.fail(NetConnectionIoException(<resource>, Connect|Accept, NetErrno(...)|<Throwable>)(using handle.createdAt))`. The transports'
  wrap points (`NioTransport.connectFail:190`, `PosixTransport.connectFail:420`) already accept any `Throwable` cause, so the public leaf
  remains `NetConnectException`/`NetUnixConnectException` with an accurate embedded cause.
- **1d. Driver invariant breach (4 sites, all io_uring)**: `Result.Panic(NetConnectionIoException(<resource>, Receive, <diagnostic>)(using handle.createdAt))`.
  Defects travel the panic channel; `ReadPump`'s panic branch error-logs and tears down (`ReadPump.scala:73-75`), which is strictly better
  observability than today's silent `Failure(Closed)` branch. The sites keep their existing `Log.error` calls.

### Class 2 (9 sites): genuine driver close, explicit internal frame

`Closed(label, Frame.internal, <existing detail>)`: the resource (the process-shared driver) is already correct; the only change is the
explicit `Frame.internal` as `createdAt` instead of the ambient `summon[Frame]`. Details keep their fd mentions where present
("fd=42 driver closed"): the detail names the victim op, the resource names what closed.

### Class 3 (14 sites): `Closed` where nothing is closed

- **3 unsupported/illegal-op stubs**: `promise.completeDiscard(Result.Panic(NetDriverUnsupportedException(label, <op>)))`. Panic, not
  Failure: these are programming-error guards on paths production never routes (their own comments say "fails loudly", "fail fast",
  "misuse bug": NioIoDriver:852-855, JsIoDriver:97, IoUringDriver:434-435), the promise-completing analog of `NoDriver`'s throw (C7). This
  also means the two `awaitAccept` stubs need no row change to deliver a non-`Closed` error.
- **11 timer-interrupt causes**: `Result.Panic(Interrupted(summon[Frame], <existing detail as the by-reason>))` (C4). The `at` frame is the
  disarm site (who interrupted), which is what `Interrupted` documents ("Fiber interrupted at <at> by <by>"); the existing detail strings
  ("handshake completed before deadline", ...) become the `by` reason. `Interrupted` is kyo-core, already on kyo-net's classpath.
  PosixTransport :613 is a `completeDiscard(Result.panic(...))` rather than an `interruptDiscard`, and gets the same cause swap.

---

## 6. Mapping table: all 81 sites

Legend for "fix" column:
- `CLOSED(res, frame, detail)`: `Result.fail(Closed(res, frame, detail))` (or the site's existing completion vehicle).
- `FAILED(op, cause)`: `Result.succeed(ReadOutcome.Failed(NetConnectionIoException(res, op, cause)(using frame)))`.
- `NETFAIL(op, cause)`: `Result.fail(NetConnectionIoException(res, op, cause)(using frame))` on the widened row.
- `PANIC-IO(op, cause)`: `Result.Panic(NetConnectionIoException(res, op, cause)(using frame))`.
- `PANIC-UNSUP(op)`: `Result.Panic(NetDriverUnsupportedException(label, op))`.
- `PANIC-INT(reason)`: `Result.Panic(Interrupted(summon[Frame], reason))`.
- `conn` = `s"connection ${handleLabel(handle)}"`, `lst` = listener resource string, `h.f` = `handle.createdAt`,
  `L.f` = the listen frame (via `PosixHandle.createdAt` of the listen-fd handle, or `NioListener.createdAt`/the new explicit param).

### NioIoDriver.scala (15)

| line | class | fix |
|---|---|---|
| 280 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 328 | 1a | CLOSED(conn, h.f, "read arm replaced") |
| 335 | 1a | CLOSED(conn, h.f, "registerRead failed") |
| 443 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 652 | 1a | CLOSED(conn, h.f, "detached for upgrade during staged delivery") |
| 738 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 794 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 809 | 1a | CLOSED(conn, h.f, "registerWrite failed") |
| 836 | 1a | CLOSED(conn, h.f, "registerConnect failed") (row now union; `Closed` remains valid: the register fails only when the key/selector is dead) |
| 857 | 3 | PANIC-UNSUP("awaitAccept") (IoDriver-contract stub; NIO drives its own accept seam) |
| 964 | 1a | CLOSED(conn, h.f, "closed"), the one `closed` value swept over read arm, upgrade-handoff waiter, writables and connects for this handle |
| 1047 | 1a | CLOSED(s"listener channel=${serverChannel.hashCode()}", `createdAt` param from `NioListener.createdAt`, "closed") |
| 1068 | 2 | CLOSED(label, `Frame.internal`, "driver closed") (whole-driver close sweep; resource = driver is correct for every promise it fails) |
| 1174 | 1a | CLOSED(s"listener channel=${serverChannel.hashCode()}", `createdAt` param, "registerAccept failed") |
| 1868 | 1c | NETFAIL(Connect, e) with res = conn and frame = h.f, via the handle now stored in `pendingConnects`; transport wrap yields `NetConnectException(host, port, leaf)` |

### NioTransport.scala (2)

| line | class | fix |
|---|---|---|
| 1275 | 3 | PANIC-INT("handshake completed before deadline") |
| 1312 | 3 | PANIC-INT("connect completed before deadline") |

### PollerIoDriver.scala (24)

| line | class | fix |
|---|---|---|
| 728 | 2 | CLOSED(label, `Frame.internal`, s"fd=${handle.readFd} driver closed") |
| 738 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 761 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 857 | 2 | CLOSED(label, `Frame.internal`, s"fd=${handle.writeFd} driver closed") |
| 881 | 2 | CLOSED(label, `Frame.internal`, s"fd=${handle.readFd} driver closed") |
| 899 | 1a | CLOSED(lst, h.f, "stale accept event: fd recycled") (h is the stale registration's own listener handle) |
| 1236 | 1a | CLOSED(bare handleLabel(handle), h.f, "canceled") (sweeps read, writable, accept and backpressure promises: handle may be a connection or a listener) |
| 1466 | 2 | CLOSED(label, `Frame.internal`, "driver closed") |
| 1550 | 1a | CLOSED(conn or lst per map, h.f, "stale event: fd recycled") (h is the orphaned op's owner in each `foreach`) |
| 1616 | 1a | CLOSED(conn, h.f, s"error event errno=$err") for both the read and writable promises: a pending SO_ERROR (RST, failed connect) means the peer/OS killed the connection, the audit's "peer FIN/RST is a closure" case. Read handle from `pendingReads`; for the writable entry resolve the handle (extend `PendingWritable(promise, id)` at :173/:1448/:2398 to carry the handle, or look up `activeHandles.get(fd)`) |
| 1656 | 1a | CLOSED(conn, h.f, "stale read event: fd recycled") |
| 1659 | 1a | CLOSED(conn, h.f, "read on closed handle") |
| 1710 | 1a | CLOSED(conn, h.f, "read on closed handle") (`finishDispatch` close-race arm) |
| 1728 | 1a | CLOSED(conn, h.f, "read on closed handle") (`rearmOwned` close-race arm) |
| 1856 | 1b | FAILED(Receive, NetErrno(result.errorCode)) via `finishDispatch(..., Result.succeed(ReadOutcome.Failed(...)))` |
| 1912 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 2074 | 1b | FAILED(Receive, NetErrno(errno)) |
| 2101 | 1b | FAILED(Decrypt, e) |
| 2151 | 1b | FAILED(Decrypt, e) |
| 2161 | 1b | FAILED(Receive, NetErrno(result.errorCode)) |
| 2173 | 1a | CLOSED(conn, h.f, "stale writable event: fd recycled") (handle via the extended `PendingWritable` or `activeHandles`) |
| 2360 | 1a | CLOSED(conn for Read/Write kinds, lst for Accept kind, h.f, "register on closing handle") |
| 2424 | 1a | CLOSED(conn or lst per branch, h.f, "register failed") (the lazy `def closed` becomes a per-branch construction since the handle differs; rc<0 here is the close-race/EBADF shape) |
| 2509 | 1a | CLOSED(bare handleLabel(h), h.f, "closed") (fdClosing dereg sweep over read/writable/accept promises) |

### IoUringDriver.scala (20)

| line | class | fix |
|---|---|---|
| 204 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 228 | 1a | CLOSED(conn, h.f, "detached for upgrade") |
| 247 | 2 | CLOSED(label, `Frame.internal`, "driver closed") |
| 272 | 1a | CLOSED(conn, h.f, "closed before read arm") (buffer freed before the deferred arm ran) |
| 297 | 1d | PANIC-IO(Receive, msg) (exclusive-use violation: a structural discipline breach, aborts the connection; keeps its `Log.error`) |
| 336 | 1d | PANIC-IO(Receive, s"io_uring recv rejected: negative length ${handle.readBufferSize}") (C-boundary guard for a future signedness bug: a defect, not an environmental failure) |
| 420 | 2 | CLOSED(label, `Frame.internal`, "driver closed") (row now union; `Closed` remains the right value here) |
| 436 | 3 | PANIC-UNSUP("awaitConnect: no connect target") |
| 474 | 2 | CLOSED(label, `Frame.internal`, "driver closed") |
| 481 | 1a | CLOSED(lst, h.f, "listener closed") |
| 898 | 1a | CLOSED(conn, h.f, "canceled") (fed to `op.failPromise`; `failPromise(closed: Closed)` keeps its signature since `Closed` conforms to every op row) |
| 1123 | 1a | CLOSED(conn, h.f, "closed with parked operations") (same handle for every swept stalled op) |
| 1282 | 2 | CLOSED(label, `Frame.internal`, "driver closed") (`teardownRing` sweeps ops of ALL handles: driver resource is correct) |
| 1939 | 1d | PANIC-IO(Receive, msg) (buffer-role mismatch safety net) |
| 1958 | 1d | PANIC-IO(Receive, msg) (staging ownership mismatch safety net) |
| 1989 | 1b | FAILED(Decrypt, "fatal record") (environmental: the peer sent a record the engine rejected fatally) |
| 2024 | 1b | FAILED(Decrypt, e) |
| 2055 | 1b | FAILED(Receive, NetErrno(-res)) |
| 2078 | 1c | NETFAIL(Connect, NetErrno(-res)) with res/frame from the bound `PendingOp.Connect` handle (`case PendingOp.Connect(promise, h)`) |
| 2087 | 1c | NETFAIL(Accept, NetErrno(-res)) with res = lst, frame = h.f from the bound `PendingOp.Accept` handle. Note: the accept loop still reads any Failure as "stop re-arming" (behavior unchanged, see D5) |

### JsIoDriver.scala (5)

| line | class | fix |
|---|---|---|
| 67 | 1a | CLOSED(conn, h.f, "socket destroyed") (destroyed = RST or completed close: a closure) |
| 98 | 3 | PANIC-UNSUP("awaitAccept") (JS never runs the accept loop) |
| 105 | 1a | CLOSED(conn, h.f, "socket destroyed") |
| 128 | 1a | CLOSED(conn, h.f, reason) (reason strings "socket closed before writable" / "socket error before writable" kept) |
| 159 | 1a | CLOSED(conn, h.f, "canceled") |

### JsHandle.scala (1)

| line | class | fix |
|---|---|---|
| 98 | 1b | FAILED(Receive, <error description from the "error" event argument>) with res = conn, frame = the new `createdAt` param. A Node "error" is a hard receive error on a live read; the permanent listener today discards the error object (`(_: js.Dynamic)`), so bind it and pass its message string as the cause |

### BlockingReaderDriver.scala (3)

| line | class | fix |
|---|---|---|
| 46 | 1b | FAILED(Receive, NetErrno(result.errorCode)) with res = conn (stdio fd label), frame = h.f |
| 65 | 1b | FAILED(Receive, <e rendered as cause>) (the read fiber's failure; pass the Throwable if that is its type, else its string form) |
| 80 | 1b | FAILED(Receive, <e rendered as cause>) |

### PosixHandle.scala (2)

| line | class | fix |
|---|---|---|
| 754 | 1a | CLOSED(s"connection fd=${h.readFd}/${h.writeFd}", h.createdAt, "closed while a write was parked on backpressure") |
| 783 | 1a | CLOSED(s"connection fd=${h.readFd}/${h.writeFd}", h.createdAt, "closed while a recv was queued") |

### Timer-interrupt causes (11): NioTransport (2, listed above), JsTransport (3), PosixTransport (5), ReadPump (1)

| site | fix |
|---|---|
| JsTransport:397 | PANIC-INT("connect completed before deadline") |
| JsTransport:456 | PANIC-INT("handshake settled before deadline") |
| JsTransport:1070 | PANIC-INT("upgrade settled before deadline") |
| PosixTransport:512 | PANIC-INT("connect completed before deadline") |
| PosixTransport:613 | `writablePromise.completeDiscard(Result.panic(Interrupted(summon[Frame], "connect interrupted before completion")))` |
| PosixTransport:729 | PANIC-INT("handshake settled before deadline") |
| PosixTransport:1292 | PANIC-INT("handshake completed before deadline") |
| PosixTransport:1719 | PANIC-INT("upgrade settled before deadline") |
| ReadPump:164 | PANIC-INT("grace disarmed by progress") |

Tally: 39 CLOSED-rewrite + 12 FAILED + 3 NETFAIL + 4 PANIC-IO = 58 Class 1; 9 Class 2; 3 PANIC-UNSUP + 11 PANIC-INT = 14 Class 3.
Total 81; every audited site appears exactly once above.

---

## 7. Compatibility and blast radius

### Signatures that change (main sources)

1. **`IoDriver` interface** (`transport/IoDriver.scala:78,84`): `awaitConnect` and `awaitAccept` promise rows widen to
   `Abort[Closed | NetException]`. Overrides updated in `NioIoDriver` (the IoDriver-contract stub :857 and the NIO-private
   `awaitAccept(serverChannel, ...)` :1168, which additionally gains `createdAt: Frame`; its row can stay `Abort[Closed]` since NIO's
   server seam produces only closures), `PollerIoDriver`, `IoUringDriver`, `JsIoDriver`, `BlockingReaderDriver` (pure delegation),
   `NoDriver` (`PosixHandle.scala:825`). `awaitRead`/`awaitReadHandshake`/`awaitWritable`/`cancel`/`closeHandle`/`close` are unchanged.
   Scaladoc for all three await rows updated to state the failure vocabulary (section 3.3), and the `awaitRead` bullet list is now
   enforced.
2. **`PollerIoDriver.awaitConnect`** delegates to `armSocketWritable` (`:867-869`), which serves the writable row. The union-row promise is
   passed through a documented erased-boundary cast (`promise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]`), safe because
   `armSocketWritable` only ever completes success or `Closed`; this is the module's established cast-with-justification idiom
   (`NioTransport.scala:297-302`, `ReadPump.scala:36-46`).
3. **`PendingOp`** (`posix/PendingOp.scala:62-68`): `Connect` and `Accept` promise fields widen to the union row. `failPromise(closed: Closed)`
   keeps its signature (a `Closed` conforms to both rows). `Read` unchanged.
4. **Handles**: `NioHandle` (+`createdAt` field, `init`/`initTls` param), `PosixHandle` (+field, `socket`/`stdio` param, and
   `pendingAcceptPromise`'s type widens to the union row), `JsHandle` (+field, `init` gains the param and DROPS `using Frame`).
5. **`NioIoDriver` internals**: `pendingConnects` value type becomes `(promise, handle)` (for :1868); `awaitAccept(serverChannel,...)` and
   `cleanupAccept` gain `createdAt: Frame`.
6. **`PollerIoDriver` internals**: `PendingWritable(promise, id)` (`:173`) gains the handle (or :1616/:2173 resolve via `activeHandles`).
7. **Transports**: promise construction sites widen where they feed the changed rows: `NioTransport` :284, :614
   (`IOPromise[Closed, Unit]` becomes `IOPromise[Closed | NetException, Unit]`, casts and comments updated; the untyped
   `case Result.Failure(cause)` arms already bind the union and `connectFail(host, port, cause)` accepts any `Throwable`);
   `PosixTransport` :580 (`awaitConnectThen` writable/connect promise) and :1041 (accept promise). New explicit `createdAt: Frame`
   parameters on the private helpers listed in section 4 (NioTransport `awaitConnect`, `awaitConnectThenTls`, `startTlsHandshake`;
   PosixTransport `connectResolving`, `connectImpl`, `listenResolving`, `listenImpl`, `startAcceptLoop`, `handleAccepted`; JsTransport
   `connectSocket`, `listenServer`). `NioListener` gains `createdAt: Frame`.
8. **`NetException.scala`**: the two new `private[net]` leaves (section 3). No existing leaf changes.
9. **Unchanged on purpose**: `ReadOutcome` (the `Failed` case simply gains producers), `ReadPump`, `WritePump`, `ReadArmCell`,
   `PosixHandle.pendingReadPromise`/`QueuedRecv`, `JsHandle.pendingRead`, `NioHandle.UpgradeHandoff` (but see D7), the public `Transport`
   trait, `Connection`, `NetConfig`, and the `Closed` row of the public connection channels (the `NetException.scala:10-13` seam doc stays
   true verbatim).

### The `Abort[Closed]` contract question, answered precisely

The driver `await*` rows are `private[kyo]`/`private[net]` seams; no public API exposes them. The public contracts that mention `Closed`
(connection inbound/outbound channels) are kyo-core `Channel` rows and are untouched. Therefore: the row change is internal, mechanical,
and bounded to the files above plus tests. The read row deliberately does NOT change: its typed-error vehicle already exists
(`ReadOutcome.Failed`), and changing it would have rippled through every promise-vehicle in `ReadPump`, the handshake read paths, and
roughly 130 test files for no semantic gain.

### kyo-http

No impact. kyo-http's only kyo-net couplings are `kyo.net.internal.util.GrowableByteBuffer` (several files) and the public
`NetConnectException` mapping (`internal/client/HttpClientBackend.scala:59`), whose shape (`host`, `port`, cause) is unchanged. The
messages inside causes improve; no kyo-http code reads them.

### Tests

Three categories (representative, not exhaustive; the build will enumerate the rest):
- **Mechanical signature updates**: `RecordingDecorators` (test IoDriver decorators), `PosixTestSockets`, the test drivers in
  `IoDriverPoolTest`/`IoDriverPoolWrapRotationTest`, `JsIoDriverTest`, `BlockingReaderDriverTest`, and the `NioHandle.initTls`/`init`
  callers (`NioHandleTest`, `NioHandleEngineGateTest`, `NioEngineOwnershipTest`, `NioHandleReadArmTest`) which now pass a frame
  (`Frame.internal` or the test's ambient frame).
- **Assertion updates**: suites that assert `Closed` failures or message fragments on the changed sites, notably `NioIoDriverTest` (accept
  stub, cancel, connect paths), `PollerIoDriverConnectAcceptInterruptTest`, `PollerIoDriverErrorEventTest`, `IoUringDriverTest`,
  `IoUringListenerCloseRecycleTest`, `IoUringDriverNegativeLengthTest` and `IoUringExclusiveUseSqFullTest` (now Panic instead of Failure),
  `JsIoDriverAwaitWritableFailureTest`, `ConnectionReadOutcomeContractTest` (gains the now-live `Failed` case), transport TLS/timer suites
  that observe the interrupt causes (`NioTransportTlsCloseReasonTest`, handshake-timeout suites).
- **New regression tests (required by the repo's reproduce-first rule)**:
  1. Per backend, connect to a refused port and assert the `NetConnectException` cause names the connection (never the driver), does not
     claim the driver is closed, and renders the test's own connect call-site position (the creation frame).
  2. A recv hard error surfaces as `ReadOutcome.Failed(NetConnectionIoException(_, Receive, _))` (driver-level test per poller/io_uring).
  3. Driver `close()` failures render the driver resource with `Frame.internal`.
  4. Deadline disarm interrupts carry `Interrupted` panics, not `Closed`.
  5. The three stubs panic with `NetDriverUnsupportedException`.

### Adjacent findings surfaced by this analysis (not silently dropped)

- `NioHandle.initTls` (`NioHandle.scala:136`) has no production caller (tests only). Kept and updated here; flagging for a separate
  decision on removal.
- `NioHandle.UpgradeHandoff.Waiter` is `Abort[Closed]` (`NioHandle.scala:127`) while its posix dual is `Abort[NetException]` with a carried
  frame (`PosixHandle.scala:631`, failed at :769 with `NetConnectionClosedException(Operation.Upgrade)(using fr)`). See D7.
- The io_uring accept loop treats ANY accept-promise failure as "listener closed, stop re-arming" (`IoUringDriver.scala:470-474` doc), so a
  transient `ECONNABORTED`/`EMFILE` at :2087 permanently wedges the listener today. This design types the failure correctly but does not
  change the loop policy. See D5.

---

## 8. Open decisions

Genuine forks only; each with a recommendation. Everything not listed here is specified above.

**D1. Row treatment for `awaitConnect`/`awaitAccept`.**
(a) `Abort[Closed | NetException]` (RECOMMENDED): preserves the audit's Class-2 prescription at :420/:474/:881 (driver-closed stays
`Closed` with `Frame.internal`), preserves accept-loop stop-on-Failure semantics, and lets the three OS-failure sites carry a truthful
typed leaf. Union rows are established kyo practice (C6).
(b) Pure `Abort[NetException]`: forces the driver-closed connect/accept failures into a NetException leaf (e.g. adding
`Operation.Connect`/a listener-closed leaf), contradicting the audit's Class-2 fix and enlarging the public-ish vocabulary for no gain.
(c) Keep `Abort[Closed]` everywhere: the connect-refused site then still renders "... is closed" for a connection that never existed,
which is the reported bug's wording surviving with better identity. Rejected as not actually fixing the lie; listed because it is the
zero-ripple option.

**D2. Visibility of the two new leaves.** `private[net]` (RECOMMENDED): they are driver-seam vocabulary; users receive them only as causes
inside existing public leaves or in logs, and kyo locks public surface growth deliberately. Public alternative: uniform with the rest of
the file and allows user `Abort.recover` branching on them, at the cost of committing to them as API. There is no existing private leaf in
`NetException.scala` (nearest precedent: the `private[net]` helpers `show`/`suffix` in the same file), so the private variant is a new,
explicitly flagged pattern either way.

**D3. One I/O-failure leaf with an `Operation` enum vs distinct leaves per mode** (`NetReceiveFailedException`,
`NetConnectFailedException`, ...). Single leaf with enum (RECOMMENDED): mirrors `NetConnectionClosedException`'s existing shape exactly,
keeps the internal vocabulary to two types, and consumers branch on the typed `operation` field. Distinct leaves would mirror the
public-leaf style (`NetConnectException` vs `NetBindException`) but multiply internal types with identical shapes.

**D4. Vehicle for the four io_uring invariant guards (:297, :336, :1939, :1958).** `Result.Panic(NetConnectionIoException(...))`
(RECOMMENDED): they are defects (their comments call them structural violations and safety nets), and kyo's panic channel is the defect
channel; ReadPump error-logs panics. Alternative: `ReadOutcome.Failed(...)`, which keeps them on the typed-error path and downgrades the
pump log to debug. Either preserves the teardown; the choice is about defect taxonomy.

**D5. Accept-loop policy on transient accept errnos (io_uring :2087).** The typed `NetConnectionIoException(_, Accept, NetErrno)` makes a
transient/fatal distinction possible for the first time; actually re-arming on `ECONNABORTED`/`EMFILE` instead of wedging the listener is a
behavioral change beyond error identity. RECOMMENDED: fix it in this same campaign as its own phase with its own reproducing test (a
listener that survives an induced ECONNABORTED), since walking past a diagnosed availability defect contradicts the repo's own rules; but
it needs your explicit go-ahead because it changes observable server behavior, not just error rendering.

**D6. Resource-string convention.** `"connection <handleLabel>"` / `"listener <handleLabel>"` prefixes with bare `handleLabel` for the two
mixed sweeps (RECOMMENDED, specified throughout section 6). Alternative: bare `handleLabel` everywhere (never mislabels a listener as a
connection in shared sweeps, but reads worse in the common rendered message). Cosmetic, but it appears in every rendered failure, so
worth a deliberate pick.

**D7. Harmonize `NioHandle.UpgradeHandoff.Waiter` with its posix dual** (row `Abort[NetException]` plus carried frame, failed with
`NetConnectionClosedException(Operation.Upgrade)(using fr)` like `PosixHandle.scala:769`). Not one of the 81 sites (its failure flows
through :964's swept `closed` value), but the same defect family and the code is being touched anyway. RECOMMENDED: align in this
campaign; DEFAULT if declined: :964's corrected `Closed` (connection identity, creation frame) flows to the waiter, which is already a
strict improvement.
