# Contributing to kyo-compiler

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers the global Kyo conventions (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, the test framework, the `AllowUnsafe` discipline). Defer to the root guide for those; this file covers only what is specific to kyo-compiler.

**The headline invariant:** every op is cancellable by interrupting the calling fiber, and there is no `cancel` method. This is the load-bearing design property of the module, not a convenience. The `Compiler` surface deliberately carries no cancel lever (the six ops of `Compiler` in `Compiler.scala`), and `CompilerTest` pins that absence with a compile-fail witness (`typeCheckFailure("c.cancel()")`, `CompilerTest` "no cancel/spawn/local member and lsp4j surface type rejects (compile-fail)"). Cancellation is the ordinary Kyo mechanism: interrupt the fiber running the op. Every backend, present and future, must translate that fiber interrupt into real cancellation of the underlying work, never a leaked running future or a stranded pending request. Internalise both halves before touching anything in this module.

kyo-compiler is JVM-only. The whole module lives under `kyo-compiler/jvm/src/main/scala/kyo/`; there is no `shared` tree (`kyo-compiler` in `build.sbt`, `crossProject(JVMPlatform)`). It depends on `kyo-core`, `kyo-aeron` and `kyo-system` and pulls in `scala3-presentation-compiler` (the same definition). Tests `fork := true` and pass `--enable-native-access=ALL-UNNAMED`, since the pool's shared media driver is kyo-aeron's C driver reached through Panama.

---

## Architecture overview

The module is a per-config, warm, cancellable driver over the published Scala 3 presentation compiler (`scala.meta.pc`, impl `dotty.tools.pc`). It adapts that compiler's lsp4j-shaped results to neutral, serializable, offset-based shapes and serves them through one of two interchangeable backends.

| Layer | Types | Purpose |
|-------|-------|---------|
| Public surface | `Compiler` (the six ops), `Compiler.Pool`, the neutral result types, `Compiler.Config` / `Toolchain` / `Pool.Settings`, the `CompilerException` hierarchy | The backend-agnostic handle, its configuration and its failures; the only public API |
| Pool | `CompilerPool` | Single-flight per-config resolution, the global compile cap, per-instance op serialization, the stuck-op reclaim, idle eviction with close-on-evict, backend selection |
| Backends | `Backend`, `LocalBackend`, `SpawnBackend`, `Instance`, `TransportError` | One config's live compiler, in this JVM or in a forked worker |
| Wire | `Wire`, `Request`, `Response`, `Envelope` | The lsp4j to neutral-ADT adapter and the serializable op/reply shapes |
| Worker | `kyo.internal.CompilerWorker`, `WorkerFlags`, `WorkerConfig`, `WorkerServer` | The forked-worker JVM entry point, its `-D` flags and its aeron serve loop |

Only the `Compiler` abstract class, the `Compiler.Pool` abstract class, the result/config types nested in `object Compiler`, and the `CompilerException` hierarchy are public. The internals live in `kyo.internal` as `private[kyo]` or `private[internal]`, apart from the worker's main object `CompilerWorker`. The caller never names a backend, never sees an lsp4j or `scala.meta.pc` type, and never holds a wire type.

---

## The public surface

### The `Compiler` handle

`Compiler` is the per-config handle, an abstract class (`Compiler.scala`). It carries exactly six ops, each in the row `< (Async & Abort[CompilerException])`:

| Op | Result | Empty / absent encoding |
|----|--------|--------------------------|
| `compile(uri, text)` | `Chunk[Diagnostic]` | empty `Chunk` = clean |
| `completions(uri, text, offset)` | `Chunk[Completion]` | empty `Chunk` = none |
| `hover(uri, text, offset)` | `Maybe[Hover]` | `Absent` = none |
| `signatureHelp(uri, text, offset)` | `Maybe[Signature]` | `Absent` = none |
| `symbol(uri, text, offset)` | `Maybe[SymbolInfo]` | `Absent` = none |
| `didClose(uri)` | `Unit` | drops the pc's per-uri cache |

A handle is bound to exactly one `(toolchain, classpath, scalacOptions)` configuration and cannot serve a file from another config (the `Compiler` scaladoc). Document text is passed per call (uri + text [+ offset]); the handle stores no buffer and relies on the pc's content-keyed typecheck cache (the same scaladoc). `didClose` is fire-and-forget but stays typed so a comms failure surfaces rather than being dropped (the `Compiler.didClose` scaladoc).

Offsets everywhere are UTF-16 code-unit offsets into `text`; line/column mapping is the caller's concern, which keeps the surface neutral (the `Compiler` scaladoc).

### The `Compiler.Pool` manager

`Compiler.Pool.init(settings)` returns a `Pool < (Async & Scope)` (`Async` because opening the pool starts the shared `AeronDriver`, a suspending handshake); on scope close every live instance is closed and every spawned worker JVM is force-killed (`Compiler.Pool.init`). `pool.compiler(config)` is a `Sync` resolve that returns a thin per-config view (`Compiler.Pool.compiler`). Creation is lazy: the view holds no instance, and the backend is built on the first op (`CompilerPool.view`, and `CompilerPoolTest` "Pool.compiler is a Sync resolve with no instance created").

`Pool.Settings` (`Compiler.Pool.Settings`):

| Field | Default | Meaning |
|-------|---------|---------|
| `isolate` | `true` | the default backend choice; `true` forks workers, `false` uses the in-JVM Local path |
| `maxConcurrentCompiles` | `4` | the global cap on concurrent pc requests across all instances |
| `maxLiveCompilers` | `16` | the live-instance bound before LRU eviction |
| `idleEviction` | `5.minutes` | how long an unused instance stays warm before idle eviction |
| `readyTimeout` | `30.seconds` | how long a forked worker has to answer its readiness probe before it counts as failed to start |
| `stuckTimeout` | `1.minute` | how long one op may run before the pool reclaims the instance as stuck |

### Config types and the three-state `isolate`

`Toolchain(scalaVersion, compilerClasspath)` is the version plus the resolved pc classpath. kyo-compiler does not resolve those JARs; a caller that wants coursier resolution composes it above and passes the paths (the `Compiler.Toolchain` scaladoc).

`Config(toolchain, classpath, scalacOptions, sourceRoots, isolate: Maybe[Boolean] = Absent)` (`Compiler.Config`). The per-config `isolate` is three-state: `Absent` means use the pool default, `Present(true)`/`Present(false)` override it. The pool resolves it with `config.isolate.getOrElse(settings.isolate)` (`CompilerPool.backendFor`; `CompilerTest` "Settings.default and Config.isolate three-state default"). `sourceRoots` is the set of source directories the pc resolves symbols against beyond the compiled classpath; it is wired into the pc's `-sourcepath` (see the LocalBackend and worker sections below).

### Neutral result and wire types

The result ADTs (`Span`, `Severity`, `Diagnostic`, `Completion` + `Completion.Kind`, `Hover`, `Signature` + `Signature.Param`, `SymbolInfo` + `SymbolInfo.Kind`) all `derive CanEqual, Compiler.AsMessage` (the result types nested in `object Compiler`). `AsMessage[A] = Schema[A]` is the kyo-schema wire codec alias (`Compiler.AsMessage`), so a result type carries a `Schema` and rides the kyo-aeron `Topic` transport directly, whose `publish` and `stream` take `Schema[A]`. `Uri` is an opaque type over `String` with a `given Schema[Uri]`, derived by `transform` over the `String` schema, that keeps it opaque through serialization (`Compiler.Uri`). It is declared in the object `Uris` and exported into `Compiler`: declared in `Compiler`'s own template it would make `String` and `Uri` the same type there, and every result type's schema derivation would be refused at its first `String` field.

Failures are the `CompilerException` hierarchy on every op's `Abort` row: two operation super-types, `CompilerInitializationFailure` (a per-config compiler could not start) and `CompilerOperationFailure` (a request against a live compiler failed), over leaf case classes with typed fields. A `KyoException` carries a `Frame` and cannot derive a `Schema`, so the companion hand-writes its `Compiler.AsMessage` codec; over the worker IPC wire it carries the rendered stack and rebuilds the cause (`CompilerException.scala`).

---

## The headline invariant: cancel by interrupt, no cancel method

### Half one: there is no cancel lever

The `Compiler` abstract class exposes the six ops and nothing else. There is no `cancel`, no `cancelAll`, no per-op timeout on the surface. Cancellation is achieved the way it is achieved everywhere in Kyo: interrupt the fiber that is running the op. `CompilerTest` enforces this at compile time (`c.cancel()` must not type-check, `CompilerTest` "no cancel/spawn/local member and lsp4j surface type rejects (compile-fail)").

### Half two: a backend must make the interrupt bite

Every `Backend.run` carries the contract that a fiber interrupt during `run` cancels the underlying op end to end (the `Backend` scaladoc). The two backends honour it differently:

- **Local.** Each pc method returns a `java.util.concurrent.CompletableFuture`. `LocalBackend.bridge`, a `private[kyo]` method on the `LocalBackend` companion object, awaits it through `Async.fromCompletableFuture`, whose `Sync.ensure` finalizer calls `cf.cancel(true)` when the awaiting fiber is interrupted. That finalizer is the reason for `fromCompletableFuture`: `Async.fromCompletionStage` alone does not propagate a fiber interrupt to the future (the `LocalBackend` scaladoc). The pc params carry a `NoopCancelToken` (`Wire.NoopCancelToken`): cancellation is driven by cancelling the future, not through the token lever. No scheduled executor is supplied to the pc, so it never schedules a forced thread stop (the `LocalBackend` scaladoc). `LocalBackendTest` "the real bridge cancels its underlying pc future on a fiber interrupt" proves `cf.cancel(true)` fires on interrupt.

- **Spawn.** The worker process keeps running, but an interrupt unregisters the pending reply in the `kyo.Exchange` and the backend stays usable for a superseding op. `SpawnBackendTest` "interrupting a Spawn op releases its pending entry and the backend stays usable for a superseding op" drives `run` through an in-memory Exchange, interrupts op 1 (which surfaces as an interrupt, not a completion), then feeds op 2's reply to prove the exchange is still usable.

Because ops serialize per instance (next section), the interrupted op was the only one touching that pc, and a superseding op simply takes the mutex next. **Any new backend or op must preserve both halves: never add a cancel method, and always make a fiber interrupt cancel the underlying op rather than leak a running future or a stranded pending request.**

---

## The pool

`CompilerPool` is the implementation of the `Compiler.Pool` abstract class (`CompilerPool.scala`). It owns a close-on-evict `Cache[Config, Promise[Instance, Abort[CompilerException]]]` that is also the single-flight create registry, the global compile-cap `Meter`, the one shared embedded `AeronDriver` every Spawn worker connects to, and a monotonic stream-id counter that hands each spawned worker a unique stream-id base.

### Op path: per-instance mutex inside the global semaphore

Every op runs as `instance.mutex.run(globalSemaphore.run(instance.backend.run(request)))` under `Async.timeout(stuckTimeout)` (`CompilerPool.run`). Two invariants fall out:

- **Per-instance serialization.** Each `Instance` carries its own mutex `Meter` (`CompilerPool.create`), so at most one op at a time touches a given pc. This is mandatory, not an optimization: even read-only queries mutate the pc's lazy denotations (the `Compiler` scaladoc). `CompilerPoolTest` "per-instance serialization: two concurrent ops on the same instance never overlap" asserts `maxOverlap == 1`.
- **Global concurrency cap.** A single shared semaphore `Meter` sized `maxConcurrentCompiles` bounds concurrent pc requests across all instances (`CompilerPool.init`, applied in `CompilerPool.run`). `CompilerPoolTest` "global compile cap" asserts the in-flight count never exceeds the cap, while "cross-config parallelism" confirms distinct configs do run their backends simultaneously up to that cap.

A `Response.Failed(error)` from the backend surfaces as `Abort.fail(error)`; a closed meter maps to `CompilerClosedException` and a panic to `CompilerExecutionException` (`CompilerPool.run`).

### Stuck-op reclaim

A normal pc op is sub-second, so an op that outlasts `Pool.Settings.stuckTimeout` is an instance whose cooperative cancel was defeated. On that timeout the inner fiber is interrupted, which releases the per-instance mutex and the global permit; the instance is removed from the cache, so its close-on-evict finalizer reclaims it (Spawn force-kills the worker, Local makes a best-effort `shutdown`); and the op fails with `CompilerUnresponsiveException(stuckTimeout)`. The next op on that config recreates the instance (`CompilerPool.run`). A caller's own interrupt aborts the timeout instead of firing it, so the ordinary cancel path runs and nothing is reclaimed. `CompilerPoolTest` "stuck-op reclaim: a hung op times out, the instance is evicted and closed, and the config recreates" drives a backend whose op never returns under `Clock.withTimeControl`.

### Resolve path: single-flight create

`resolve` uses the instance cache as its own single-flight registry: on a miss the first op inserts a fresh `Promise` for the config and creates the instance, and concurrent first-ops find that `Promise` and await it, so exactly one instance is built. A create failure or interrupt removes the entry, so the next op retries instead of awaiting a failed `Promise` (`CompilerPool.resolve`). `CompilerPoolTest` "single-flight create" drives N concurrent first-ops and asserts one instance serves all N.

### Eviction: close on every path

The instance cache is built with `Cache.initWithFinalizer(maxLiveCompilers, expireAfterAccess = idleEviction)`, whose finalizer awaits the evicted entry's `Promise` and closes the instance it holds; an entry whose create failed holds nothing to close (`CompilerPool.init`). **Backends are closed on every eviction path** because the finalizer is the single close site: LRU overflow past `maxLiveCompilers`, idle expiry past `idleEviction`, and the stuck-op removal all run `Instance.close`, which closes the per-instance mutex in a `Sync.ensure` around `backend.close` (`Instance.close`). `CompilerPoolTest` "pool eviction closes the Instance via the Cache finalizer" forces an eviction and waits for the close signal; which instance the cache evicts is its CLOCK policy (the slot order follows `Config.hashCode`, so it moves with the toolchain version), so the leaf asserts that exactly the evicted instance is closed and the survivor still serves, for either victim. "one instance per config ... eviction triggers recreate" confirms a later op on an evicted config recreates rather than failing.

This is the treat-closed-as-recreate contract: a returned `Compiler` whose instance was concurrently evicted re-resolves on its next op, and the caller holds no stale-handle obligation (the `Compiler.Pool` scaladoc).

### Backend selection: isolate plus the version-match rule

`backendFor` computes `effectiveIsolate = isolate || config.toolchain.scalaVersion != CompilerPool.ownVersion`; if true it builds a `SpawnBackend`, otherwise a `LocalBackend` (`CompilerPool.backendFor`). `ownVersion` is the version of the scala3-compiler jar on the classpath, read from its `compiler.properties` through `dotty.tools.dotc.config.Properties.versionNumberString` (`CompilerPool.ownVersion`), so it never restates the build's Scala version. So the in-JVM Local path is reachable only on a version-matched opt-out; a toolchain pinned to a different version forces a forked worker regardless of `isolate`. `CompilerPoolTest` "effectiveIsolate: version-mismatch with isolate=false routes to SpawnBackend" is the routing leaf, and it is the only pool test that uses a real driver.

---

## The backends

`Backend` is the internal abstract class: `run(request): Response < (Async & Abort[CompilerException])` and `close: Unit < (Async & Abort[Throwable])` (`Backend.scala`). `Instance(backend, mutex)` is the cache value; its `close` calls `backend.close` (`Instance.scala`).

### LocalBackend (the version-matched fast path)

`LocalBackend` drives `dotty.tools.pc.ScalaPresentationCompiler` directly (`LocalBackend.scala`). `init` instantiates the pc once via `newInstance`, passing the config's Scala version (as the build-target id), classpath, and the options list, and logs any throw and fails with `CompilerStartException(scalaVersion, cause)`, so a pc that cannot start surfaces on the caller's first op (`LocalBackend.init`). The options list is the config's `scalacOptions` plus, when `Config.sourceRoots` is non-empty, a trailing `-sourcepath <roots>` (the roots joined with the platform path separator) so the pc resolves symbols whose definitions live in a source root but not on the compiled classpath; `optionsList` appends it and an empty `sourceRoots` adds nothing (`LocalBackend.optionsList`). `run` dispatches the six ops to `pc.didChange` / `complete` / `hover` / `signatureHelp` / `definition` / `didClose`, adapting each result through `Wire` (`LocalBackend.run`). `didClose` is the one op that is not bridged through a future: it is a `Sync.defer(pc.didClose(...))`, and like the others it normalizes the uri through `Wire.toAbsoluteUri` (the same method). `close` calls `pc.shutdown()` (`LocalBackend.close`).

`LocalBackendTest` drives a real pc built from jars on the test classpath, one leaf each for: diagnostics with `Error` severity for type and syntax errors and none for a clean buffer, real type-checked completions, a symbol that resolves only when `Config.sourceRoots` is wired into `-sourcepath`, the interrupt-cancel path, an exceptionally-completed pc future mapped to a typed `CompilerExecutionException` rather than an escaped panic, a pc throw surfacing as a typed `CompilerExecutionException` rather than an escaped throw, and `didClose` followed by a successful recompile.

### SpawnBackend (the forked worker)

`SpawnBackend` drives a per-config worker JVM over an aeron request/response session (the `SpawnBackend` scaladoc). It holds the `Process`, the `Aeron` client, and a `kyo.Exchange[Request, Response, Nothing, TransportError]`. `init` spawns the worker, connects the aeron client to the pool's shared medium, wires the Exchange over two `aeron:ipc` streams, then runs a readiness probe (`SpawnBackend.init`).

Load-bearing details:

- **Distinct stream-ids off a pool-owned counter.** The request and reply ride distinct aeron `stream-id`s, per direction and per config: `reqStreamId = streamIdBase * 2`, `respStreamId = streamIdBase * 2 + 1` (`SpawnBackend.init`). The `streamIdBase` is a unique per-worker value the pool allocates from a monotonic `CompilerPool.streamIdCounter.getAndIncrement()` and threads into `init` (`CompilerPool.streamIdCounter`, drawn in `CompilerPool.backendFor`). The even/odd split per direction means a host never reads its own request back; the distinct base per config means two configs' workers never cross-talk on the one shared medium. The base comes from the counter rather than `config.hashCode` because a 32-bit hashCode could collide and silently mis-route one worker's reply to another host's request (the `SpawnBackend.init` scaladoc). The host computes the ids and threads them to the worker through `-D` properties so both sides agree across the process boundary (`SpawnBackend.spawnWorker`, read back through `WorkerFlags` in `CompilerWorker.scala`). `SpawnBackendTest` "one shared driver per pool: two Spawn workers both reach it" gives the two workers distinct bases (0, 1), sends distinguishable buffers, and asserts no cross-talk.

- **Bounded readiness probe with interrupt-safe brackets.** `ready` runs a cheap, idempotent `DidClose` probe under `Async.timeout(readyTimeout)` (`Pool.Settings.readyTimeout`, 30 seconds by default); a failure or timeout becomes `CompilerWorkerReadyException(scalaVersion, readyTimeout)` (`SpawnBackend.ready`). A worker that cannot start (for example an unusable classpath whose publication never sees a subscriber) thus fails as a `CompilerInitializationFailure` rather than hanging forever on the caller's first real op. A failure to launch the process or connect its aeron client is the other initialization leaf, `CompilerWorkerSpawnException(scalaVersion, cause)` (`SpawnBackend.init`, `SpawnBackend.spawnWorker`). **The process and the aeron client are each acquired in a `Sync.acquireReleaseWith` bracket whose release force-kills the partial worker or closes the client unless a `started` flag was set after the probe succeeded**, so a failure OR an interrupt anywhere in init (an IDE routinely cancels a cold start) leaks no aeron conductor thread or orphaned JVM, and `close` owns both only once init succeeds (`SpawnBackend.init`). `init` also takes a defaulted, test-only `onSpawn: Process => Unit` seam fired once the kill is armed and just before the probe (a no-op at the real call site), so a test can interrupt mid-probe and assert the partial worker dies. `CompilerPoolTest`'s version-mismatch leaf exercises the failure path: an empty-classpath worker cannot load `kyo.internal.CompilerWorker`, so the op fails with a `CompilerInitializationFailure`. `SpawnBackendTest` "interrupting init during the readiness probe force-kills the partial worker (no orphaned process)" and "an interrupt landing before the kill is armed does not orphan the worker JVM" exercise the interrupt path.

- **A transport break maps to `CompilerTransportException`, never a hang.** A `TransportError`, a closed session, or a panic from the exchange maps to `CompilerTransportException` at the backend boundary, and a broken session fails every pending op (`SpawnBackend.run`). `transportErrors` collapses any `Topic` publish or stream exception into one typed `TransportError` and re-raises a panic unchanged, so its live `Throwable` reaches the `CompilerTransportException` cause (`SpawnBackend.transportErrors`). `SpawnBackendTest` "no thread leak after a kill: close kills the worker and every later op fails with a typed CompilerTransportException" proves no op after `close` hangs, and "a worker-comms failure surfaces didClose as a typed CompilerTransportException; a live worker yields Closed" covers the `didClose` path specifically.

- **close releases all three resources and confirms the worker died.** `close` runs `exchange.close`, then closes the aeron client, then `process.destroyForcibly`, then waits for the exit (bounded by `SpawnBackend.exitTimeout`) and polls `isAlive` (`SpawnBackend.close`). The poll exists because Windows completes the exit wait slightly before `isAlive` turns false, and a closed backend must not leave a worker holding the shared medium files (`SpawnBackend.awaitWorkerExit`). The pool's close-on-evict finalizer drives `close` on eviction.

- **Module-opener flags are forwarded.** `moduleArgs` copies the parent JVM's `--add-opens` / `--add-exports` / `--add-modules` / `--enable-native-access` flags to the worker so its pc reaches the same internal modules the host opened (`SpawnBackend.moduleArgs`).

`SpawnBackendTest` also runs a parity leaf comparing Local and Spawn results for the same buffer over a real embedded `AeronDriver` ("a fixed buffer yields equal results on Local and Spawn (parity)").

---

## The wire

`Wire` is the only place lsp4j and `scala.meta.pc` types appear; they never reach a `Request` / `Response` / `Envelope` case or any public signature (the `Wire` scaladoc). The same adapter runs in the in-process backend and in the worker host, so both produce identical neutral results for one buffer (the parity test above is the proof).

The neutral wire types (`Request.scala`, `Response.scala`, `Envelope.scala`):

- `Request` and `Response` are one case per op, both `derive Compiler.AsMessage`. Neither carries a correlation id: the request/reply id correlation belongs to the transport, owned by `kyo.Exchange`. A worker-side typed failure travels in-band as `Response.Failed`, distinct from a transport break.
- `Envelope` is the id-carrying frame `kyo.Exchange` sends both directions: `Req(id, request)` host to worker, `Resp(id, response)` worker to host. `Topic.publish[Envelope]` / `Topic.stream[Envelope]` carry it natively.

Adapter rules a contributor must keep:

- **Total over null and `Either` sides.** Every lsp4j reader returns a documented default rather than throwing: `eitherText`, `paramLabel`, `diagnosticCode`, `severity`, `completionKind`, `hoverMarkdown`, `markedString`, and `rangeToSpan` all handle `null` and both sides of an lsp4j `Either` (those `Wire` methods). `WireTest` "adapter maps null/missing lsp4j fields to Absent and full fields to expected neutral values" pins this.
- **The diagnostics opt-in.** `compileParams` overrides `shouldReturnDiagnostics()` to `true`; the interface default is `false`, under which `didChange` returns an empty list even for a buffer with errors (`Wire.compileParams`). `CompilerWorkerTest` confirms the worker-side path sets it too.
- **Absolute-uri normalization.** The pc silently returns empty results for relative URIs, so `toAbsoluteUri` makes a bare filename absolute (`"Main.scala"` becomes `file:///Main.scala`) and parses an already-schemed string as-is (`Wire.toAbsoluteUri`). Every op routes its uri through this, including `didClose`.
- **Offset mapping.** `LineIndex` precomputes each line's starting offset once and maps an lsp4j line/character position to a UTF-16 offset, clamping a past-end position to the buffer length and a null position to 0 (`Wire.LineIndex`). `WireTest` "LineIndex maps line/character positions to UTF-16 offsets, including CRLF and tab" covers CRLF, tabs, empty lines, and out-of-range clamping.
- **Symbol naming.** `simpleName` and `symbolKind` map a SemanticDB-style symbol such as `scala/collection/Seq#` to a simple name and a neutral kind by its suffix (`()` method, `#` type, `.` term, unsuffixed path package), `Wire.simpleName` and `Wire.symbolKind`.

---

## The worker

`kyo.internal.CompilerWorker` is the forked-worker entry point, launched as `java -cp <worker + scala3-pc:vN> kyo.internal.CompilerWorker` (the `CompilerWorker` scaladoc in `CompilerWorker.scala`). It extends `KyoApp`, so the process boundary is its containment boundary and there is no hand-rolled top-level catch (the same scaladoc). Because no user frame exists at a process main and `Frame` cannot derive in `package kyo`, the object roots its `Frame` at `Frame.internal` with a private given. The host passes the per-worker settings as `-D` properties keyed by `WorkerFlags`' fully qualified names (`kyo.internal.WorkerFlags.<name>`), each a `StaticFlag` read once at startup (`WorkerFlags`).

- `WorkerConfig.fromEnv` parses the per-config `Compiler.Config` from the `-D` system properties the host passed, splitting the classpath and the source roots on the platform separator (the same separator the host joined with) so the worker's pc gets the same `-sourcepath` the in-process backend would, and sets `isolate = Present(false)` because the worker always hosts the in-process Local path (`WorkerConfig.fromEnv`).
- `WorkerServer.serve` builds a `LocalBackend` over that config, connects an aeron client to the host's existing shared medium (the host's driver directory, not a second driver), and runs the serve loop as `Topic.stream[Envelope].foreach`, publishing an id-matched `Envelope.Resp` for each `Envelope.Req` (`WorkerServer.serve`).
- **A transport break ends the worker rather than being swallowed.** A `Closed` medium or unclearable backpressure propagates out of the serve loop, ends it, and stops the worker, so the host's Exchange observes a broken session and fails its pending op with a typed `TransportError` instead of hanging on a silently dropped reply; the break is logged, not discarded (`WorkerServer.serve`, `WorkerServer.publish`). The host then force-kills and respawns.
- A backend failure or panic for one request is different: it is reported in-band as a `Response.Failed` frame (a panic as `CompilerExecutionException`) and the serve loop keeps running (`WorkerServer.respond`).

`CompilerWorkerTest` drives the exact worker-side path (`WorkerConfig.fromEnv` then a `LocalBackend`) to verify the diagnostics opt-in survives the round trip.

---

## Unsafe and bridging boundaries

This module has two bridging boundaries; each must stay annotated and resource-owned.

- **The process spawn.** `Command(args*).inheritStderr.unsafe.spawn()` runs inside `Sync.Unsafe.defer` and is spawned outside any `Scope`, so its lifetime is owned by `SpawnBackend.close` (and the pool's close-on-evict finalizer), not by an enclosing scope close (`SpawnBackend.spawnWorker`). The `// Unsafe:` comment states exactly that contract. A launch failure maps to `CompilerWorkerSpawnException`.
- **The pc future bridge.** `LocalBackend.bridge` (a `private[kyo]` method on the `LocalBackend` companion object) lifts a pc `CompletableFuture` into `Async` with `Abort.catching` around `Sync.defer` + `Async.fromCompletableFuture`, folding both a synchronous throw and an exceptionally-completed future (unwrapping its `CompletionException`) into a typed `CompilerExecutionException` rather than letting either escape as a panic (`LocalBackend.bridge`). The `cf.cancel(true)` finalizer inside `Async.fromCompletableFuture` is the cancellation half of the headline invariant; do not swap it for `Async.fromCompletionStage` when refactoring the bridge.

Per the root guide, prefer the safe tier; reach for an unsafe or bridging site only at a justified boundary and keep the resource owned by an explicit `close`.

---

## Conventions

### Reliability: never leak a resource

Reliability is a primary goal: a resource you acquire must never leak. Close everything you open, even on a failure or an interrupt, via `Sync.ensure` or `Scope`, never a bare close on the happy path only. A leaked process, file, socket, or `Meter` is a bug, not a nuisance. The discipline is load-bearing here: `Instance.close` closes both the backend and its per-instance mutex `Meter`, the pool's close-on-evict finalizer reclaims every evicted instance, and `SpawnBackend.close` force-kills the worker process. When you add a resource, own its close the same way.

### JVM-only, no shared tree

The module is `crossProject(JVMPlatform)` (`kyo-compiler` in `build.sbt`), so all source and tests live under `kyo-compiler/jvm/`. There is no cross-platform discipline to maintain here, but tests `fork := true`; a test that drives a real worker also loads kyo-aeron's native shim, so it wants `--enable-native-access=ALL-UNNAMED` to stay warning-free.

### Kyo types and totality

Use the Kyo primitives throughout (`Maybe` not `Option`, `Result` not `Either`, `Chunk` not `List`/`Seq`). The `Wire` adapters are the seam where lsp4j's nullable, `Either`-shaped Java types are made total: every reader returns a default instead of throwing, and the pc's `Optional` results become `Maybe`. Keep that totality when extending the adapter.

### Visibility

Only the `Compiler` abstract class, `Compiler.Pool`, the result/config types nested in `object Compiler`, and the `CompilerException` hierarchy are public. The internals in `kyo.internal` are `private[kyo]` or `private[internal]`, except the worker's main object `CompilerWorker`. Do not widen these; `CompilerTest` pins that the surface rejects a leaked lsp4j type and a backend method (`pool.spawn`, `c.cancel`), in its "no cancel/spawn/local member and lsp4j surface type rejects (compile-fail)" leaf.

### Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-compilerJVM/test'

# A single test class
sbt 'kyo-compilerJVM/testOnly kyo.SpawnBackendTest'
```

Building automatically runs scalafmt. Re-read any file you edit after building; formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, and `using`-clause ordering.

---

## Testing patterns

All tests extend the module's `kyo.test.Test[Any]` base, never ScalaTest directly. The suites split by surface, 1:1 with their source files:

| Test | What it grounds | How |
|------|-----------------|-----|
| `CompilerTest` | the public surface | result-ADT MsgPack round-trips, `Uri` opacity, the three-state `isolate` default, and compile-fail witnesses that no `cancel`/`spawn`/lsp4j surface exists |
| `WireTest` | the adapter | pure `Wire` tests: `LineIndex` offsets, null / `Either` handling, hover / signature / symbol adapters, request/response/envelope round-trips, and a corrupt-envelope decode raising a contained throw |
| `LocalBackendTest` | the in-JVM pc | a real pc built from classpath jars: diagnostics, completions, hover, signature help, `sourceRoots` `-sourcepath` resolution, interrupt-cancel, exceptional-future and pc-throw as `CompilerExecutionException`, didClose-then-recompile |
| `CompilerWorkerTest` | the worker-side path | `WorkerConfig.fromEnv` + `LocalBackend`, proving the diagnostics opt-in survives |
| `CompilerPoolTest` | pool policy | seeds the instances cache with stub backends (`makePool`, null driver) to exercise single-flight, per-instance serialization, the global cap, cross-config parallelism, isolation, lazy create, eviction-close, and the stuck-op reclaim, without spawning |
| `SpawnBackendTest` | the forked worker | real cross-process round-trips over an embedded `AeronDriver` via `withDriver`: parity, kill-and-no-leak, comms failure as `CompilerTransportException`, shared-driver no-cross-talk, interrupts during init, plus an in-memory-Exchange interrupt leaf |

Two patterns are worth copying when you add a test:

- **Stub-backend pool tests.** `CompilerPoolTest.makePool` constructs a real `CompilerPool` with a fresh semaphore and stream-id counter but a caller-supplied instances cache pre-seeded with stub `Backend`s; the `AeronDriver` is `null` because `SpawnBackend.init` is never reached when stubs are seeded (`CompilerPoolTest.makePool`). Use this to test pool policy deterministically. Reach for `makePoolWithDriver` (a real shared driver) only when a leaf must drive a real `SpawnBackend`, as the version-mismatch routing leaf does.
- **Real-driver worker tests.** `SpawnBackendTest.withDriver` runs a body against a fresh embedded `AeronDriver` closed on scope exit, and `SpawnBackendTest.fullClasspath` builds the worker's `-cp` from the test JVM's classpath so the spawned worker can load `kyo.internal.CompilerWorker`, kyo-aeron, and the pc. The interrupt leaf instead wires `run` through a controlled in-memory `Exchange` with a throwaway child process for a real `Process` handle, so it never opens a real aeron session.

Per the root guide: every test asserts a concrete value (the suites here compare full neutral values, not just non-emptiness), keeps its reproducing assertions named with the source it covers, and leaves no orphan or scratch test behind.

---

## Decision checklist: before adding a new X

1. **New `Compiler` op.** Add it to the `Compiler` abstract class (row `< (Async & Abort[CompilerException])`), add a `Request` and `Response` case (both `derive Compiler.AsMessage`), add the dispatch arm to `LocalBackend.run`, add the `Wire` adapter (keeping lsp4j/`scala.meta.pc` inside `Wire`), and add the `CompilerPool` view arm. The worker serves it automatically because it drives the same `Backend`. Add a `WireTest` round-trip and a `LocalBackendTest` leaf. Do not add a cancel lever.

2. **New result or wire type.** `derive CanEqual, Compiler.AsMessage`, keep it neutral and offset-based (UTF-16 code units, no lsp4j or `java.net.URI`), and add it to the round-trip tests in `CompilerTest` / `WireTest`.

3. **New backend.** Implement `Backend.run` and `Backend.close`. Confirm a fiber interrupt during `run` cancels the underlying op end to end (the headline invariant), and that `close` releases every resource the backend owns on every path, including a readiness or init failure. Report a start failure as a `CompilerInitializationFailure` leaf. If it forks a process or opens a transport, route any transport break to a typed `CompilerTransportException` and never let an op hang.

4. **Touching the `Wire` adapter.** Keep every reader total over `null` and both `Either` sides. No lsp4j or `scala.meta.pc` type may escape `Wire` into a `Request`/`Response`/`Envelope` case or a public signature. Preserve the `shouldReturnDiagnostics` opt-in and the absolute-uri normalization.

5. **Changing the spawn transport.** Keep request and reply on distinct aeron `stream-id`s, distinct per direction (even/odd) and per config (a unique base from the pool's monotonic counter, never a hashCode that could collide), and keep the host computing them and threading them to the worker. Never swallow a transport break in the serve loop; it must end the worker so the host sees a clean break, not a hang. Keep the readiness probe bounded and its failure-or-interrupt path force-killing the partial worker and closing the aeron client (the `Sync.acquireReleaseWith` brackets keyed on the `started` flag), transferring ownership to `close` only on success.

6. **New unsafe or bridging site.** Annotate it with a `// Unsafe:` comment naming the contract it bridges. If it spawns a process or opens a resource unscoped, an explicit `close` (driven by the pool's eviction finalizer) must own its lifetime.

7. **New pool policy.** Decide whether it belongs on `Pool.Settings` or `Config`, respect the three-state `isolate` (`Absent` = pool default), and keep the op path as per-instance mutex inside the global semaphore. Test it with a stub-backend `makePool` rather than spawning a real worker.

8. **New failure.** Add a leaf case class under the operation trait that matches what the caller was doing (`CompilerInitializationFailure` or `CompilerOperationFailure`), with typed fields and a message built from them. Add its tag to both directions of the hand-written `AsMessage` codec in the `CompilerException` companion: the decoder's fallback arm rebuilds any unknown tag as `CompilerClosedException`, so a missing arm silently changes the failure a worker reports. Add it to the `Response.Failed` round-trip in `WireTest`.
