# Contributing to kyo-net

Module-specific guide for kyo-net. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary, test patterns, and the unsafe-boundary tiers that apply across all of Kyo. This document records only what is specific to kyo-net: its unsafe-only surface, its event-loop and TLS-serialization model, its cross-platform layout, and its build and test mechanics.

## What kyo-net is

kyo-net is the low-level network transport: TCP, Unix-domain sockets, stdio, and TLS, over a completion-based I/O driver that unifies poll-based platforms (Linux epoll/io_uring, macOS/BSD kqueue, JVM NIO selector) with the callback-based JS runtime (Node `net`/`tls`). It sits below kyo-http, which is its primary consumer. The byte-stream and protocol layers live in kyo-http; kyo-net stops at the socket and the TLS engine.

## The unsafe-only surface

kyo-net has no pending-effect (`<`) types in its main source: not in the public surface, not in the internals. Every async result is a `Fiber.Unsafe`, every entry point is gated by `AllowUnsafe`, and orchestration never re-enters the effect system. This is the binding invariant of the module; the rest of this section is how it holds.

### Public types

The public package `kyo.net` exposes eight types. Three of them, `Transport`, `Connection`, and `Listener`, are the surface; the rest are data (`NetAddress`, `NetTlsConfig`, `TransportConfig`) plus the platform entry point (`NetPlatform`). The error vocabulary is the sealed `NetException` hierarchy (`NetException extends kyo.Closed`), so the error channel is the precise transport-exception type while still being a `Closed`. Its leaves include `NetConnectException`, `NetConnectTimeoutException`, `NetDnsResolutionException`, `NetBindException`, `NetTlsHandshakeException`, and the STARTTLS leaves. `NetConnectTimeoutException(host, port, timeout)` is the typed leaf the transport's own connect-deadline produces (see "Connect deadline" below); kyo-http maps it to its own connect-timeout type, so a regression that drops the typed leaf or substitutes the generic `NetConnectException` breaks that mapping.

`TransportConfig` is a plain `case class`: tune it with `copy(...)`, not per-field setter methods (the four `channelCapacity(v)` / `readChunkSize(v)` / `ioPoolSize(v)` / `handshakeTimeout(v)` delegates were removed). Collections on the internal selection surface are `Chunk`, not `Seq`: `IoBackend.select` / `selectAndBuild` and `TlsProvider.selectFor` take `Chunk`, and `Connection.detachForUpgrade` returns `Maybe[Chunk[Span[Byte]]]`.

The surface is the abstract classes directly, each gated by `AllowUnsafe`:

- `NetPlatform.transport: Transport` is the bootstrap accessor (lazily initialized, shared for the process lifetime).
- `Transport` exposes `connect`, `connectUnix`, `stdio`, `listen`, `listenUnix`, `upgradeToTls`, `close`. The connecting and listening methods return `Fiber.Unsafe[Connection, Abort[NetException]]` / `Fiber.Unsafe[Listener, Abort[NetException]]` (`NetException` extends `Closed`, so the error channel is the precise transport-exception type).
- `Connection` exposes `inbound`/`outbound` as `Channel.Unsafe[Span[Byte]]`, plus `isOpen`, `close()`, `detachForUpgrade()`, and `serverCertificateHash`. A write is `outbound.offer`/`outbound.put`; a read drains `inbound`. There is no separate `write` method.
- `Listener` exposes `port`, `host`, `address`, `close()`.
- The `listen` handler is `Connection => Unit` (no pending type). It is invoked once per accepted connection, fire-and-forget, on the accept carrier. A handler that needs async work spawns its own carrier with `Fiber.Unsafe.init` and returns immediately. A long-running synchronous handler stalls accepts; that contract is the caller's.

`Transport`, `Connection`, and `Listener` are plain `abstract class`es: there is no opaque alias and no safe-tier wrapper, since the whole module is unsafe. The internal `*Impl extends X` classes subclass these directly. Do not add a safe-tier extension block to them.

When adding or changing a public entry point, keep the effect row empty, return a `Fiber.Unsafe` for anything async, and mirror the existing using-clause shape `(using AllowUnsafe, Frame)`.

### Consuming a Fiber.Unsafe

Never call `.safe.get` on a `Fiber.Unsafe` in main source: it re-enters the effect system. Consume results one of two ways:

- `IOPromise.onComplete` (`fiber.onComplete { ... }`) when the fiber may be genuinely pending. The callback fires on completion; when the fiber is already complete, `onComplete` runs the callback inline on the calling thread.
- `done()` then `poll()` to extract a value from an already-completed fiber without scheduling. This is the JVM/Native inline-completion case for `@Ffi.blocking` downcalls (see the loop model below). `poll()` is the non-parking peek; it is not `.block`.

The same rule governs internals: a `@Ffi.blocking` downcall returns a `Fiber.Unsafe`, and the caller consumes it via `onComplete` or `done()`/`poll()`, never `.safe.get`, `.block`, or `blockNow`.

### No orchestration thread-blocking

kyo-net orchestration must not park an OS thread. Specifically, none of the following appear in main source:

- `.safe.get` (re-enters the effect system)
- `.block` / `blockNow` (`LockSupport.park`)
- `synchronized`
- `Thread.sleep`, `CountDownLatch`, `Future.await`

There is exactly one sanctioned OS-thread park, the bounded kernel readiness wait: the driver carrier parks inside `epoll_wait` / `kevent` / `kyo_uring_wait_cqe_timeout` / NIO `Selector.select`, run inline as the `@Ffi.blocking` mechanism (the JVM selector wait is the NIO backend's equivalent floor). This is the scheduler's blocking-I/O floor; the `BlockingMonitor` samples per-thread CPU time, detects the parked carrier, and drains its queue to other workers, so no carrier is starved. "Zero orchestration thread-block" is fully achieved; "no OS thread ever parks" is not, because the kernel readiness wait is the design floor. This is categorically distinct from the forbidden parks above and is the only one. There is no `LockSupport.parkNanos` and no spin-backoff anywhere in the module.

The JVM-NIO selector cancelled-key race (a re-registration of a channel whose `SelectionKey` was just cancelled, e.g. the STARTTLS `upgradeToTls` re-register after `detachForUpgrade`) is handled WITHOUT a park: `NioIoDriver.registerChannel` keeps its synchronous fast path, and only when `channel.register` throws `CancelledKeyException` (the cancelled key has not been flushed from the selector's cancelled-key set yet) it enqueues the handle on a `pendingRegistrations` queue, wakes the poll carrier, and returns success. The poll carrier drains that queue at the top of `pollOnce`, AFTER `select()` has flushed the cancelled-key set, completing the registration with the interest reconstructed from the pending-op maps (the same source-of-truth invariant `rebuildSelector` uses). An `awaitX` armed during the deferred window records its interest in the pending-op map and `registerInterest` reports deferred-success rather than failing. The registration is the selector owner's job, never the calling carrier's.

### Carrier spawning

Every fire-and-forget carrier in kyo-net is spawned with `Fiber.Unsafe.init` (kyo-core), not `Sync.Unsafe.evalOrThrow { Fiber.initUnscoped { ... } }`. `Fiber.Unsafe.init` is the unsafe spawn primitive: it schedules a thunk on its own carrier without re-entering the effect system, returns the `Fiber.Unsafe` directly, and its carrier runs with an empty effect context (so `Local` bindings observe their defaults, not the spawner's), captures the current trace for diagnostics, is cooperatively interruptible, and turns a thrown exception in the thunk into a `Result.Panic`. Mark each spawn site with a `// Unsafe:` comment. The primitive is backed by `kyo.kernel.internal.Trace.saved`, the `private[kyo]` trace-capture helper.

### Mutable state in the unsafe tier

The module holds mutable state in the unsafe tier, but with a fixed discipline.

- **Atomics are the kyo unsafe tier, not raw JDK atomics.** Counters, flags, and once-guards use `AtomicLong.Unsafe` / `AtomicInt.Unsafe` / `AtomicBoolean.Unsafe` (kyo-core), not `java.util.concurrent.atomic.*` directly. These opaque aliases forward 1:1 to the same JDK atomic, so the volatile/CAS semantics are identical; the point is one consistent unsafe vocabulary. They are created at construction where there is no ambient `AllowUnsafe`, so each field init carries the documented `AtomicX.Unsafe.init(...)(using AllowUnsafe.embrace.danger)` bridge with a `// Unsafe:` rationale (the runtime get/CAS all run under the caller's `AllowUnsafe`, since the public methods are `AllowUnsafe`-only).
- **A `var` that crosses carriers is `@volatile`.** A field written on one carrier and read on another (a transport-installed `upgradeFn` / `certHashFn` / `closeReasonFn`, an origin flag, a probed-availability cache, a parked-read promise) is `@volatile`. A bare `var` is allowed only when it is confined to a single carrier (a loop accumulator, a field touched only on the per-driver engine-FIFO worker, a write-once address set before the pumps start); state that bridge as a comment at the declaration. Cross-fiber state that needs atomic read-modify-write uses an `Atomic*`, never a bare `var`.
- **`null` is not a sentinel in kyo code.** Absence is `Maybe` (`Absent`), failure is `Result` / `Abort`. The only `null` in main source is a foreign-API boundary: a JS interop value, a JDK API that returns or accepts `null` (`SSLContext.init`, `KeyStore.load`, `SelectionKey`, `getPeerCertificates`), or a native queue `poll()`. Each such site reads `null` only to translate it immediately into a `Maybe`.
- **Concurrent collections are the one documented exception.** kyo has no concurrent-map or array-of-atomics type, so a `ConcurrentHashMap` (the connection pool's per-host map, the DNS cache, the NIO pending-op maps), a `ConcurrentLinkedQueue` (a driver change/engine queue), and the connection pool's `AtomicLongArray` ring stay raw `java.util.concurrent`. Each is retained behind a concurrent-collection audit comment at the declaration naming why no kyo equivalent fits (a hot `AllowUnsafe`-only path with no `< S` wrapper, a per-slot lazy-published ring). Do not introduce a new raw concurrent type without the same documented justification.

## Architecture

kyo-net is layered. Each layer below depends only on the ones above it.

### Transport, driver, engine

- A **Transport** (`PosixTransport` shared, `NioTransport` JVM, `JsTransport` JS) owns connection setup: DNS resolution, socket creation, the accept loop, the TLS handshake drive, and connection wiring. It produces `Connection` values and starts their pumps.
- An **IoDriver** (`internal/transport/IoDriver.scala`, the contract) does completion-based I/O: it performs the read internally and completes the caller's `Promise.Unsafe` with the bytes, rather than signalling readiness and letting the caller read. The contract methods are `start`, `awaitRead`, `awaitWritable`, `awaitConnect`, `awaitAccept`, `write`, `cancel`, `closeHandle`, `close`, and `submitEngineOp`. Implementations: `PollerIoDriver` (shared; epoll on Linux, kqueue on macOS/BSD), `IoUringDriver` (shared; Linux io_uring), `BlockingReaderDriver` (shared; the regular-file / stdin-from-file fallback for fds that cannot be polled), `NioIoDriver` (JVM NIO selector floor), `JsIoDriver` (JS Node).
- A **TlsEngine** wraps the platform TLS implementation; the transport drives the handshake and the read/write encrypt/decrypt steps through it. On JVM the engine is `JdkSslEngine` (`javax.net.ssl`). On JVM and Native the native engine is the single `NativeSslEngine[B <: SslLibBindings]`: one 1:1 forwarding wrapper that drives any memory-BIO backend through the shared `SslLibBindings` type. The two concrete backends, `BoringSslBindings` (bundled BoringSSL, the primary) and `OpenSslBindings` (system OpenSSL, the Native fallback), declare the same 21-method intersection and bind their own prefixed C symbols. There is no per-backend engine class; an engine method that one backend cannot honor is a design defect, since the shared type is the cross-backend denominator.

### Backend and TLS selection

Both the I/O backend and the TLS provider are chosen at startup through one shared `IoBackend.select`: a forced `-D` property name wins if its entry is available (and aborts `Closed` if forced-but-unavailable, never a silent fall-through), otherwise the highest-priority available entry wins.

- `-Dkyo.net.backend` selects the I/O backend: `io_uring` (priority 30, Linux), `epoll`/`kqueue` (20), `nio` (10, the JVM floor), `node` (JS).
- `-Dkyo.net.tls` selects the TLS provider: `boringssl` (priority 30, the primary), `openssl` (Native system fallback), `jdk` (priority 10, the JVM `SSLEngine` floor).

Adding a backend or provider is one entry in the platform `registered` list; the `select` logic never changes.

### The poll-loop zero-pending model

Poll and reap loops (the `PollerIoDriver` epoll/kqueue loop, the `IoUringDriver` CQE-reap loop) run as a single `@tailrec` synchronous loop on one carrier spawned once with `Fiber.Unsafe.init`. Each iteration issues the bounded `@Ffi.blocking` kernel wait, then:

- If the wait fiber is `done()` (the JVM/Native inline-completion case), extract the result with `poll()`, dispatch the ready events synchronously, and continue the loop as a tail jump (no stack growth).
- If the wait fiber is genuinely pending (the JS case), register `onComplete` to re-enter the loop from the runtime completion and return.

This avoids the trap that a naive `pollFiber.onComplete { drain(); pollStep() }` self-recursion would grow the stack one frame per iteration on JVM/Native, because `onComplete` fires inline when the fiber is already complete and there is no trampoline in that path. Accept re-arms, handshake steps, and read-dispatch re-arms use plain `onComplete` instead, because they are completed by the driver carrier (one frame per readiness event), not self-recursively.

### Read path, write path, and backpressure

The two directions are deliberately asymmetric, and the asymmetry is load-bearing.

- **Reads** use synchronous non-blocking downcalls `recvNow` / `acceptNow` (mirroring the existing `sendNow`) on fds set `O_NONBLOCK`. A read returns bytes, `0` (EOF), or `EAGAIN` inline. On `EAGAIN` the driver re-arms one-shot read interest through its serial change worker; the next read fires from the next poll cycle. It never busy-spins.
- **Writes** keep the JS=`sendNow` / JVM-Native=`@Ffi.blocking send` split. On JVM/Native the `@Ffi.blocking send` inline-completes and is consumed via `done()`/`poll()`; routing it through a plain synchronous `send` instead deadlocks the surrounding write/handshake fibers. When a write cannot drain (socket buffer full), the driver buffers the unsent ciphertext per handle and arms writable interest (`awaitWritable`); the flush resumes on the next writable readiness event in a progress-bounded loop, never a busy-spin.

When changing the write path, do not convert the `@Ffi.blocking send` to a plain synchronous `send`.

### TLS engine serialization

A native `SSL` (or a JVM `SSLEngine`) is not safe to touch from two carriers at once. kyo-net serializes every engine op for a connection through a per-driver single-owner FIFO: `submitEngineOp` enqueues a thunk and `drainEngineOps` runs the queue one op at a time on one dedicated worker carrier (spawned via `Fiber.Unsafe.init`). Read decrypt, write encrypt, handshake steps, the certificate-hash computation, and engine `free` all go through the FIFO, so no two of them run concurrently on one engine. Distinct connections stay independent (the FIFO drains them in interleaved order; each op is atomic). Only the bare `recv`/`send` syscalls stay outside the FIFO.

This single-owner structure is what replaced per-engine locking: there is no `synchronized` in the TLS engines. The default `IoDriver.submitEngineOp` runs the op directly on the calling carrier, which is correct for the already-single-owner drivers (`NioIoDriver`, `JsIoDriver`, `BlockingReaderDriver`); `PollerIoDriver` and `IoUringDriver` override it with the FIFO.

`serverCertificateHash` does not touch the live engine on the caller's carrier. The leaf certificate is fixed for a connection's lifetime, so its SHA-256 (RFC 5929 tls-server-end-point) is computed once at handshake completion, before `start()` launches the pumps and therefore before any concurrent engine op can exist, and is then served from a cache. The cache returns `Absent` after the connection closes.

### Reused-buffer ownership

kyo-net keeps several off-heap buffers alive across multiple operations to avoid per-operation allocation: per-handle buffers (recv staging, encrypt/decrypt drain, flush/send mirror, accumulator) and per-driver buffers (poll/arm scratch). Each reused buffer MUST have a single declared owner, named in a comment at the declaration site (for example `// owned by this handle`, `// owned by the poll carrier`). The ownership rules are:

- **One owner, one free.** A reused buffer is freed exactly once. Per-handle buffers are freed in `freeResources`. Per-driver buffers are freed at the owning carrier's terminal exit (the tail of its `@tailrec` poll/reap loop). Neither is freed in an async completion handler (a send/recv CQE reap, an `onComplete` callback). Freeing a reused buffer on a CQE reap while the handle still owns it is a use-after-free; closing the send-mirror buffer in a send-completion handler is the canonical mistake to avoid.
- **Grow correctly.** When a reused buffer must grow (the incoming data exceeds its current capacity), close the old buffer first, then allocate the larger one, then replace the field. Never let the old pointer escape once `close` has been called on it.
- **Document the in-flight guard.** Reuse of a buffer across calls is only safe when at most one operation that touches that buffer is in flight for the owning handle or driver at a time. The in-flight guard (for example, the send single-in-flight guard that prevents a second outbound write until the first completes) is the mechanism that makes the reuse safe. Document the guard at the buffer declaration and at any call site that checks it.
- **No reused state in shared singletons.** Per-driver mutable state (for example, a poll-timeout value computed per iteration) must live on the per-driver scratch structure, not in an `object` backend or any other shared singleton. A field in a shared singleton is raced across every driver or carrier that calls into that singleton concurrently. Per-handle mutable state lives on the handle, never on a shared driver field.

### TLS parity across drivers

The encrypt and decrypt steps for a TLS connection are shared across all I/O drivers through `TlsEngineIo`. Every driver, including io_uring, goes through the same engine calls in the same order as the poller driver; the wire bytes are therefore identical regardless of which driver is active. This parity is a correctness property, not a performance choice: a driver that encrypts or decrypts differently from the poller produces a broken TLS stream.

When adding or modifying a driver that handles TLS connections:

- Consult `handle.tls` on both the read path and the write path. A driver that reads or writes raw bytes without checking `handle.tls` first will pass TLS connections through as plaintext.
- Share the engine call sequence with the poller driver via `TlsEngineIo` rather than reimplementing it. Do not inline encrypt/decrypt logic into a driver; the shared path is the specification.
- Route all engine calls through `submitEngineOp` (the per-driver single-owner FIFO described above). This rule applies equally to io_uring, which uses the same FIFO as the poller.

### DNS

Hostname resolution blocks (`getaddrinfo` on Native via the `kyo_net_resolve` shim, `java.net.InetAddress` on JVM). It runs on a dedicated carrier spawned with `Fiber.Unsafe.init` and bound `@Ffi.blocking`, so the `BlockingMonitor` compensates for the parked worker. The result is consumed via `onComplete`; the orchestration around it carries no pending type.

### Connect deadline

The transport arms its own connect deadline so a client connect to an unreachable peer fails with a typed timeout instead of hanging until the OS gives up. When `handshakeTimeout` is finite, `connect` schedules a `Clock.live.unsafe.sleep(timeout)` timer (a timer fiber on the clock executor, never a blocked carrier) and fails the connect promise with `NetConnectTimeoutException(host, port, timeout)` if the OS connect has not completed first. The deadline arm and the OS outcome race on the same promise through `completeDiscard` (at-most-once), so they are mutually exclusive: a deadline that fires after the connect already completed is a no-op, and a connect that completes after the deadline already failed the promise is a no-op, with the winner interrupting the loser's timer. `Duration.Infinity` (the default) arms no timer and preserves the prior caller-composes-via-`Async.timeout` behavior. The deadline arm is the ONLY producer of the typed timeout leaf: this is the close-cause discrimination, so a deadline-fired close surfaces `NetConnectTimeoutException` while an OS-failure close (refused/unreachable/reset) surfaces the generic `NetConnectException`. The server side mirrors this for TLS with a per-connection handshake deadline that reaps a stalled handshake (a slowloris guard).

## Cross-platform layout

Source and tests default to `shared/`. A file lives under `jvm/`, `js/`, or `native/` only when it uses a primitive that has no cross-platform Kyo wrapper:

- `shared/`: the public surface, `PosixTransport`, the poll/reap drivers (`PollerIoDriver`, `IoUringDriver`, `BlockingReaderDriver`), the poller backends, `HostResolver`, the `TlsProvider` selection logic, and the FFI binding traits whose impls every platform generates.
- `jvm/`: `NioTransport`, `NioIoDriver`, `JdkSslEngine` (`javax.net.ssl`), the JVM `SystemResolver` (`java.net.InetAddress`).
- `js/`: `JsTransport`, `JsIoDriver` (Node `net`/`tls`).
- `native/`: the Native `SystemResolver` and `ResolveBindings` (the `getaddrinfo` shim).
- `jvm-native/`: the native TLS stack shared by JVM and Native but not JS: the unified `NativeSslEngine`, the shared `SslLibBindings` type with its `BoringSslBindings` / `OpenSslBindings` concrete backends, the `SslLibProvider` base, and the `BoringSslProvider` / `SystemOpenSslProvider` providers.

The native TLS state machine itself lives in C, deduplicated into one `kyo_ssl_common.h` (the two-memory-BIO handshake and record layer). Each shim (`kyo_net_boringssl.c`, `kyo_net_openssl.c`) includes that header with its own `KYO_SSL_PREFIX` so the shared functions token-paste to its export prefix (`kyo_bssl_*` / `kyo_ossl_*`); each `.c` adds only its library headers, its load probe, and the thin exported extern wrappers. The two header copies are byte-identical by construction; when changing the native TLS state machine, edit the common header, not one shim.

The same unsafe `IoDriver` contract is honored by every driver, and `recvNow`/`acceptNow`/`sendNow` are bound on every platform. Cross-platform tests live in `shared/src/test`; keep new tests there unless they exercise genuinely platform-specific behavior.

## Build and test

Set the standard JVM options before building (see the root guide). Building auto-formats; re-read edited files afterward.

```sh
# JVM
sbt 'kyo-net/test'
sbt 'kyo-net/testOnly kyo.net.internal.posix.PollerIoDriverTest'

# JS and Native
sbt 'kyo-netJS/test'
sbt 'kyo-netNative/test'
```

### FFI binding changes need a full clean

The `kyo-ffi-plugin` source generator (`ffiGenerate`) is `FileFunction.cached` and caches generated `*Impl.scala` keyed on the binding source. When a binding trait changes (for example `SocketBindings` gaining a method), `sbt 'kyo-net/ffiClean'` alone does not clear the binding-trait TASTy in the `classes/` and `ffi-tasty/` scratch directories, so codegen can emit impls without the new method and the change silently fails to generate (surfacing later as a JS/Native compile gap). After editing a binding trait, run a full clean (`clean`, removing the stale `*.class`/`.tasty` and the `ffi-tasty/` scratch and the `ffiGenerate` streams cache) so codegen re-bootstraps from the current trait. Adding a method to a binding trait requires updating the recording decorators in `RecordingDecorators.scala` (which delegate every method to the real binding component); that update is a required consequence of the trait change, not scope drift.

### TLS test variants

The production TLS path is BoringSSL, which is built and staged per host by `kyo-net/build/boringssl/build-boringssl.sh`. When the staged tree is absent the build links a stub that reports BoringSSL unavailable, so TLS falls back to the JVM `jdk` floor or the Native `openssl` provider.

- Run the Linux suite in a container matching CI with `scripts/sbt-linux.sh '<sbt-command>'`. Set `STAGE_BORINGSSL=1` to build and stage the vendored BoringSSL inside the container and exercise the production TLS path; leave it unset to keep runs fast on the non-TLS surface.
- Force a specific TLS provider with `-Dkyo.net.tls=jdk` (the pure-JDK `SSLEngine` floor), `-Dkyo.net.tls=boringssl`, or `-Dkyo.net.tls=openssl`. A forced-but-unavailable provider aborts `Closed` rather than falling through, which is how the JDK-floor variant is exercised against the same suite.
- io_uring tests use a REAL ring gated on `assumeUring`, which probes the ring at the production depth `max(256, ioPoolSize*64)`. The gate cancels cleanly (a real `TestCanceledException`) where the ring cannot init. In the local container, `podman run --privileged` relaxes seccomp but does NOT lift the cgroup `io_uring.max` cap, so the production-depth ring fails and the gate cancels locally. On native Linux CI, `sbt` runs directly on the runner (no podman, no cgroup cap), the production-depth ring inits, and the io_uring tests execute for real. There are no `FakeUring` or other io_uring fakes; all io_uring logic is exercised through the real ring on CI.

### Test doubles policy

kyo-net tests exercise REAL components. The only test doubles are recording decorators that delegate every behavioral method to a real component and only observe (call counts, buffer identity, ordering). The canonical home for these decorators is `RecordingDecorators.scala` (`jvm-native/src/test`). The decorators are:

- `RecordingSocketBindings`: delegates all 17 `SocketBindings` methods to a real socket binding; records close counts, send/recv buffer identity, and two one-shot pre-delegate hooks.
- `RecordingIoUringBindings`: delegates all 18 `IoUringBindings` methods to a real io_uring ring; records buffer identity, submitted keys, and real CQE-reap latches.
- `RecordingPollerBackend`: delegates all `PollerBackend` methods to a real epoll/kqueue backend; records poll invocations and pre-poll latches.
- `RecordingTlsEngine`: delegates all 9 `TlsEngine` methods to a real TLS engine; records call counts, buffer identity, free count, and two one-shot re-entrant latches.
- `RecordingIoDriver`: delegates all `IoDriver` methods to a real driver; records spans, offsets, and two controlled fields (see below).
- `RecordingLog`: delegates all `Log.Unsafe` methods to a real logger; records info-call count.

There are exactly four controlled single-result injections over otherwise-real components. Each forces one value that a real peer cannot produce on cue:

1. `RecordingIoDriver.throwOnStart` (IoDriverPoolTest): causes one `start()` call to throw, exercising the pool's start-resilience path. Real `IoDriver.start()` has no deterministic failure mode.
2. `RecordingIoDriver.labelOverride` (PosixTransportTest): overrides the pure identity accessor `label` to force the `selectDriver` non-poller branch. The `label` accessor has no behavioral side effects.
3. `RecordingPollerBackend.syntheticErrorFd` (PollerIoDriverErrorEventTest): injects one synthetic error-flag scratch entry for one fd via a one-shot CAS; the real `getsockopt(SO_ERROR)` then runs on the real fd.
4. `SendErrorInjectingUring` (IoUringDriverTest): overrides `kyo_uring_cqe_res` to return a negative error code for exactly one CQE via a one-shot CAS; all other ring operations delegate to the real ring.

These injections live entirely in the test tree, over real components; they are not affordances on production code. A handshake-engine free count is NOT among them: `HandshakeEngineFreeTest` no longer injects a handshake engine (that required a production engine-override seam, now removed). It instead asserts the observable consequence of correct teardown, that file descriptors do not accumulate across a soak of many real failing/succeeding handshakes (lowest-free-fd probe), with a real BoringSSL/OpenSSL engine on every path so a double-free would crash the process.

Any other class in the test tree that `extends` one of `IoDriver`, `TlsEngine`, `SocketBindings`, `IoUringBindings`, `KqueueBindings`, `PollerBackend`, or `Log.Unsafe`, other than these recording decorators and four injections, is a regression. There is no dedicated CI lint for this property; it is maintained by review against this list.

Determinism in kyo-net tests comes from latching on real events: `Promise.Unsafe` and `Fiber` completion, recording-decorator one-shot hooks, the engine FIFO barrier, channel state, and kernel-guaranteed behavior such as `SO_LINGER` RST and `SHUT_WR` EOF. The root CONTRIBUTING.md mentions `untilTrue` as an option for async waiting; kyo-net tests do not use `untilTrue`, because it retries on a 10ms timer (timing-based). Use real-event latches instead.

io_uring and kqueue tests gate on `assumeUring` / `assumeKqueue` and cancel cleanly where the primitive is unavailable.

### No test code in production source

Production `src/main` contains ZERO test affordances. A test never reaches into production through a seam added for it. Concretely, none of the following appear in `src/main`:

- test-named factories (`forTesting`, `forBackend`, `forBindings`) that construct a component with caller-supplied internals;
- default-`Absent` override parameters that production always passes `Absent` (for example an `engineOverride` or `rawResolverOverride` on a constructor) and that exist only so a test can pass `Present`;
- observation seams (a `var onBackpressurePark`, a `def readPumpForTest`, a `lastPutFiber` field) that exist only for a test to latch on or interrupt an internal event;
- test-only methods (a `clearCache` that "no production path calls").

How tests construct and observe instead:

- **Construct via the genuine dependency constructor.** Production builds a component through its real `init(...)`, which calls the component's own constructor with real dependencies. That constructor is `private[posix]` (or the package's equivalent), and the test tree wraps it with a helper (`TestDrivers.forBackend`/`forBindings`, `TestTransports.forTesting`) so a test can supply a recording decorator over a real component in place of one dependency. The helper lives in the test tree; the production factory does not exist.
- **Observe behavior, not internals.** A test asserts the user-visible consequence (bytes delivered in order, the connection fails `Closed`, the descriptor count returns to baseline), not an internal counter exposed through a seam. Where the only thing a seam observed was a private counter (a `free()` count, a park event), the test moves to the observable consequence: a leak shows up as resource accumulation across a soak (a lowest-free-fd probe that climbs under an fd leak; a real native engine that crashes on a double-free), and a backpressure park shows up as no byte loss under a slow consumer.
- **Inject the unproducible via the test-tree constructor, never a production seam.** When a test needs a value a real peer cannot produce on cue (a one-shot `EINTR`, a short `io_uring_submit`), it passes a recording/injecting decorator through the same `private[posix]` constructor the production `init` uses. The injection is in the test double, not in production.

Removing test code from production is not optional cleanup; production that carries a test seam is incomplete. The recording-decorator and four-injection lists above enumerate every legitimate test double, all in the test tree.

### Backend test consistency

A behavioral guarantee is stated ONCE, for every backend. The public surface (`Transport`, `Connection`, `Listener`) is identical across posix (the JVM default over Panama FFI, and the Native default), the pure-JDK NIO floor (JVM), and Node (JS), so a behavioral test belongs in `shared/src/test` and runs against `NetPlatform.transport`. On each platform the shared suite exercises the SELECTED DEFAULT: posix on JVM, posix on Native, Node on JS. Because the NIO floor and the Linux epoll driver are never the default (`NetPlatform.transport` picks io_uring/kqueue first; see `IoBackendPlatform`), the SAME shared suite is additionally run against them by forced-backend CI legs (`-Dkyo.net.backend=nio` and `-Dkyo.net.backend=epoll`, in `build.yml`), so every I/O path (io_uring, epoll, kqueue, NIO, Node) runs the identical behavioral contract. Do NOT assume the shared suite covers a non-default backend implicitly: adding a new selectable backend means adding its forced-backend leg (this is exactly the gap that once hid a NIO-only TLS version-downgrade). This is how round-trip, connect burst, backpressure no-loss/order, connect-refused, peer-close, TLS round-trip and version pin, mutual TLS, STARTTLS upgrade, hostname verification, and certificate-hash introspection are asserted (`ConnectionTest`, `ConnectBurstTest`, `TransportBackpressureTest`, `TransportLifecycleTest`, `TransportTlsTest`, `TransportMutualTlsTest`, `TransportStartTlsTest`, `TransportTlsHostnameTest`, `TransportTlsIntrospectionTest`).

A platform-specific test (`jvm-native`, `jvm`, `js`, `native`) is justified only when it exercises a backend INTERNAL that exists on that backend alone: epoll/io_uring/kqueue/selector/Node-fd mechanics, an FFI binding, the native TLS engine's feed/drain, a poller's rearm. The bar matches source placement (see "Cross-platform layout"): "it tests a real loopback over this driver" is NOT a justification when the same guarantee is statable through the public `Transport`; the driver-level test is justified only when it asserts something about the driver's own mechanism that the public surface does not expose. When a behavioral guarantee is currently tested on one backend only, the default is to lift it to a shared `NetPlatform.transport` test, keeping the backend-specific test only for the internal mechanism it uniquely covers. TLS behavioral parity is the hardest case (cross-platform certificate fixtures differ, and Node terminates TLS itself); any backend that legitimately diverges there carries an in-line comment with the motivation, not a silent gap.

A few behavioral contracts genuinely cannot be stated through `NetPlatform.transport` and are validated per-backend with a recorded motivation (each is a deliberate exception, not a silent fork): a setting fixed at transport CONSTRUCTION with no public config factory (the server `handshakeTimeout` deadline, which `NetPlatform.transport` always builds with the default config, so each backend constructs its own transport AND tunes the deadline/assertion to its timing; a single shared timing would either weaken the strong disarm proof or flake on the slow backend); a unit under test that needs a REAL driver instance while mocks are disallowed (`IoDriverPool`, whose pool logic is driver-agnostic but cannot be constructed without a platform driver, so it is tested per-platform over real drivers); a capability one backend cannot provide (the `CloseReason` Clean/Truncated distinction, which Node's `tls.TLSSocket` abstracts away); and a per-DRIVER assertion the default-selected driver does not isolate (poller-driver TLS when io_uring is the default).

### Pre-existing naming deviations

NOTE: the kyo-net test tree contains a number of test files whose names predate this codebase's FooTest-matches-a-source convention (Rule 8c). These are pre-existing and should not be mass-renamed as a drive-by:

- Concept-named aspect tests (for example `ChangeFifoOrderingTest`, `CloseDuringBackpressuredFlushTest`, `EngineFifoSingleOwnerTest`, `HandshakeEngineFreeTest`, `IoUringEngineFifoFreeOrderingTest`, `IoUringTlsEncryptionTest`, `IoUringTlsWriteOrderingTest`, `RearmSurvivorsTest`, `StartTlsUpgradeCloseRaceTest`, `WritableArmedCoalesceTest`, `WriteBackpressureConservationTest`, and others): named after the behavior under test rather than the source file. They are legitimate aspect splits but do not follow the `SourceNameAspectTest` pattern. A mass rename does not belong in an unrelated change; record them here so the deviation is acknowledged and not repeated in new code.

New test files must follow Rule 8c: a test suite's name prefix must match a source file under `src/main/`, or be an aspect split of one (e.g. `PollerIoDriverWriteRaceTest` for `PollerIoDriver.scala`). New concept-named orphans are not permitted.

## Pre-submission checklist (kyo-net)

In addition to the root checklist:

- [ ] No pending-effect type (`<`) in any main-source code line.
- [ ] No `.safe.get`, `.block`, `blockNow`, `synchronized`, `Thread.sleep`, `CountDownLatch`, or `Future.await` in main source.
- [ ] Every async result is a `Fiber.Unsafe` consumed via `onComplete` or `done()`/`poll()`.
- [ ] Every carrier spawned via `Fiber.Unsafe.init`, with a `// Unsafe:` rationale at the site.
- [ ] Every TLS engine op for a connection routed through `submitEngineOp`.
- [ ] New code defaults to `shared/`; a platform split is justified by a primitive with no cross-platform wrapper.
- [ ] After any binding-trait edit, validated on a clean JS and Native build.
- [ ] No new OS-thread park beyond the sanctioned kernel readiness wait (`epoll_wait` / `kevent` / `kyo_uring_wait_cqe_timeout` / NIO `Selector.select`); no `LockSupport.parkNanos` and no spin-backoff.
- [ ] Every reused off-heap buffer has a single declared owner (named in a comment), is freed exactly once at the correct site (`freeResources` for per-handle, terminal carrier exit for per-driver), and is never freed in a CQE reap or `onComplete` callback.
- [ ] No reused or per-driver mutable state placed in a shared `object` or singleton; per-driver state lives on the per-driver scratch, per-handle state on the handle.
- [ ] Any driver that serves TLS consults `handle.tls` on both the read path and the write path, and shares the encrypt/decrypt steps with the poller via `TlsEngineIo`.
- [ ] Atomics use `AtomicLong.Unsafe` / `AtomicInt.Unsafe` / `AtomicBoolean.Unsafe` (not raw `java.util.concurrent.atomic.*`); each construction-time init carries the `AllowUnsafe.embrace.danger` bridge with a `// Unsafe:` rationale.
- [ ] Every cross-carrier `var` is `@volatile` (or an `Atomic*`); a bare `var` is single-carrier-confined with the confinement stated at the declaration.
- [ ] No `null` as a kyo sentinel: absence is `Maybe`, failure is `Result` / `Abort`; `null` appears only at a foreign-API boundary and is translated to `Maybe` at the site.
- [ ] Any new raw `java.util.concurrent` collection carries a concurrent-collection audit comment naming why no kyo equivalent fits.
- [ ] A native TLS engine method exists for every backend (`SslLibBindings` intersection); the native TLS state machine is edited in `kyo_ssl_common.h`, not one shim.
