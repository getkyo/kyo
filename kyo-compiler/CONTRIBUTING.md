# Contributing to kyo-compiler

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers the global Kyo conventions (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, the test framework, the `AllowUnsafe` discipline). Defer to the root guide for those; this file covers only what is specific to kyo-compiler.

**The headline invariant:** every op is cancellable by interrupting the calling fiber, and there is no `cancel` method. This is the load-bearing design property of the module, not a convenience. The `Compiler` surface deliberately carries no cancel lever (`Compiler.scala:38-55`), and `CompilerTest` pins that absence with a compile-fail witness (`typeCheckFailure("c.cancel()")`, `CompilerTest.scala:103`). Cancellation is the ordinary Kyo mechanism: interrupt the fiber running the op. Every backend, present and future, must translate that fiber interrupt into real cancellation of the underlying work, never a leaked running future or a stranded pending request. Internalise both halves before touching anything in this module.

kyo-compiler is JVM-only. The whole module lives under `kyo-compiler/jvm/src/main/scala/kyo/`; there is no `shared` tree (`build.sbt:1188`, `crossProject(JVMPlatform)`). It depends on `kyo-core` and `kyo-aeron` (`build.sbt:1191`) and pulls in `scala3-presentation-compiler` (`build.sbt:1203`). Tests `fork := true` with `--add-opens` flags so the presentation compiler reaches the JDK internals it needs (`build.sbt:1195-1201`).

---

## Architecture overview

The module is a per-config, warm, cancellable driver over the published Scala 3 presentation compiler (`scala.meta.pc`, impl `dotty.tools.pc`). It adapts that compiler's lsp4j-shaped results to neutral, serializable, offset-based shapes and serves them through one of two interchangeable backends.

| Layer | Types | Purpose |
|-------|-------|---------|
| Public surface | `Compiler` (the six ops), `Compiler.Pool`, the neutral result types, `Compiler.Config` / `Toolchain` / `Pool.Settings`, `Compiler.CompilerError` | The backend-agnostic handle and its configuration; the only non-`private[kyo]` API |
| Pool | `CompilerPool` | Single-flight per-config resolution, the global compile cap, per-instance op serialization, idle eviction with close-on-evict, backend selection |
| Backends | `Backend`, `LocalBackend`, `SpawnBackend`, `Instance`, `TransportError` | One config's live compiler, in this JVM or in a forked worker |
| Wire | `Wire`, `Request`, `Response`, `Envelope` | The lsp4j to neutral-ADT adapter and the serializable op/reply shapes |
| Worker | `kyo.compiler.Worker`, `WorkerConfig`, `WorkerServer` | The forked-worker JVM entry point and its aeron serve loop |

Everything except the `Compiler` trait's six ops, the `Compiler.Pool` trait, and the result/config types nested in `object Compiler` is `private[kyo]`. The caller never names a backend, never sees an lsp4j or `scala.meta.pc` type, and never holds a wire type.

---

## The public surface

### The `Compiler` handle

`Compiler` is the per-config handle (`Compiler.scala:34-56`). It carries exactly six ops, each in the row `< (Async & Abort[Compiler.CompilerError])`:

| Op | Result | Empty / absent encoding |
|----|--------|--------------------------|
| `compile(uri, text)` | `Chunk[Diagnostic]` | empty `Chunk` = clean |
| `completions(uri, text, offset)` | `Chunk[Completion]` | empty `Chunk` = none |
| `hover(uri, text, offset)` | `Maybe[Hover]` | `Absent` = none |
| `signatureHelp(uri, text, offset)` | `Maybe[Signature]` | `Absent` = none |
| `symbol(uri, text, offset)` | `Maybe[SymbolInfo]` | `Absent` = none |
| `didClose(uri)` | `Unit` | drops the pc's per-uri cache |

A handle is bound to exactly one `(toolchain, classpath, scalacOptions)` configuration and cannot serve a file from another config (`Compiler.scala:14-15`). Document text is passed per call (uri + text [+ offset]); the handle stores no buffer and relies on the pc's content-keyed typecheck cache (`Compiler.scala:16-17`). `didClose` is fire-and-forget but stays typed so a comms failure surfaces rather than being dropped (`Compiler.scala:52-55`).

Offsets everywhere are UTF-16 code-unit offsets into `text`; line/column mapping is the caller's concern, which keeps the surface neutral (`Compiler.scala:28-29`).

### The `Compiler.Pool` manager

`Compiler.Pool.init(settings)` returns a `Pool < (Sync & Scope)`; on scope close every live instance is closed and every spawned worker JVM is force-killed (`Compiler.scala:93-100`). `pool.compiler(config)` is a `Sync` resolve that returns a thin per-config view (`Compiler.scala:82-89`). Creation is lazy: the view holds no instance, and the backend is built on the first op (`CompilerPool.scala:31-37`, and `CompilerPoolTest` "Pool.compiler is a Sync resolve with no instance created" `:482-509`).

`Pool.Settings` (`Compiler.scala:117-122`):

| Field | Default | Meaning |
|-------|---------|---------|
| `isolate` | `true` | the default backend choice; `true` forks workers, `false` uses the in-JVM Local path |
| `maxConcurrentCompiles` | `4` | the global cap on concurrent pc requests across all instances |
| `maxLiveCompilers` | `16` | the live-instance bound before LRU eviction |
| `idleEviction` | `5.minutes` | how long an unused instance stays warm before idle eviction |

### Config types and the three-state `isolate`

`Toolchain(scalaVersion, compilerClasspath)` is the version plus the resolved pc classpath. kyo-compiler does not resolve those JARs; a caller that wants coursier resolution composes it above and passes the paths (`Compiler.scala:128-134`).

`Config(toolchain, classpath, scalacOptions, sourceRoots, isolate: Maybe[Boolean] = Absent)` (`Compiler.scala:140-146`). The per-config `isolate` is three-state: `Absent` means use the pool default, `Present(true)`/`Present(false)` override it. The pool resolves it with `config.isolate.getOrElse(settings.isolate)` (`CompilerPool.scala:137`; `CompilerTest` "Config.isolate three-state default" `:108-120`). `sourceRoots` is the set of source directories the pc resolves symbols against beyond the compiled classpath; it is wired into the pc's `-sourcepath` (see the LocalBackend and worker sections below).

### Neutral result and wire types

The result ADTs (`Span`, `Severity`, `Diagnostic`, `Completion` + `Completion.Kind`, `Hover`, `Signature` + `Signature.Param`, `SymbolInfo` + `SymbolInfo.Kind`, `CompilerError`) all `derive CanEqual, Compiler.AsMessage` (`Compiler.scala:171-231`). `AsMessage[A] = ReadWriter[A]` is the upickle wire codec alias and mirrors kyo-aeron's `Topic.AsMessage`, so a result type rides the aeron transport directly (`Compiler.scala:148-152`). `Uri` is an opaque type over `String` with a `given ReadWriter[Uri]` that keeps it opaque through serialization (`Compiler.scala:154-163`), and there is a `given ReadWriter[Maybe[A]]` lifted from `Option` (`Compiler.scala:165-169`).

`CompilerError` has two leaves: `InitializationFailed(message)` (a backend that could not start) and `Fatal(message)` (an op-level failure surfaced in-band), `Compiler.scala:228-231`.

---

## The headline invariant: cancel by interrupt, no cancel method

### Half one: there is no cancel lever

The `Compiler` trait exposes the six ops and nothing else. There is no `cancel`, no `cancelAll`, no per-op timeout on the surface. Cancellation is achieved the way it is achieved everywhere in Kyo: interrupt the fiber that is running the op. `CompilerTest` enforces this at compile time (`c.cancel()` must not type-check, `CompilerTest.scala:103`).

### Half two: a backend must make the interrupt bite

Every `Backend.run` carries the contract that a fiber interrupt during `run` cancels the underlying op end to end (`LocalBackend.scala:8-14`). The two backends honour it differently:

- **Local.** Each pc method returns a `java.util.concurrent.CompletableFuture`. `LocalBackend.bridge`, a `private[kyo]` method on the `LocalBackend` companion object, wires `Sync.ensure(Sync.defer(discard(future.cancel(true))))` around the awaited future (`LocalBackend.scala:76-91`). The explicit `future.cancel(true)` finalizer is required because `Async.fromCompletionStage` alone does not propagate a fiber interrupt to the future (`LocalBackend.scala:37-41`). The pc params carry a `NoopCancelToken` (`Wire.scala:287-294`): cancellation is driven by cancelling the future, not through the token lever. No scheduled executor is supplied to the pc, so it never schedules a forced thread stop (`LocalBackend.scala:41`). `LocalBackendTest` "the real bridge cancels its underlying pc future on a fiber interrupt" proves `cf.cancel(true)` fires on interrupt (`:185-204`).

- **Spawn.** The worker process keeps running, but an interrupt unregisters the pending reply in the `kyo.Exchange` and the backend stays usable for a superseding op. `SpawnBackendTest` "interrupting a Spawn op releases its pending entry and the backend stays usable for a superseding op" drives `run` through an in-memory Exchange, interrupts op 1 (which surfaces as an interrupt, not a completion), then feeds op 2's reply to prove the exchange is still usable (`:94-153`).

Because ops serialize per instance (next section), the interrupted op was the only one touching that pc, and a superseding op simply takes the mutex next. **Any new backend or op must preserve both halves: never add a cancel method, and always make a fiber interrupt cancel the underlying op rather than leak a running future or a stranded pending request.**

---

## The pool

`CompilerPool` is the locked implementation of the `Compiler.Pool` trait (`CompilerPool.scala:14-29`). It owns a close-on-evict `Cache[Config, Instance]`, the global compile-cap `Meter`, the one shared embedded `MediaDriver` every Spawn worker connects to, a never-evicting per-config create-lock map (a `java.util.concurrent.ConcurrentHashMap`, each entry removed when its create completes), and a monotonic stream-id counter that hands each spawned worker a unique stream-id base.

### Op path: per-instance mutex inside the global semaphore

Every op runs as `instance.mutex.run(globalSemaphore.run(instance.backend.run(request)))` (`CompilerPool.scala:90`). Two invariants fall out:

- **Per-instance serialization.** Each `Instance` carries its own mutex `Meter` (`CompilerPool.scala:129`), so at most one op at a time touches a given pc. This is mandatory, not an optimization: even read-only queries mutate the pc's lazy denotations (`Compiler.scala:18`). `CompilerPoolTest` "per-instance serialization: two concurrent ops on the same instance never overlap" asserts `maxOverlap == 1` (`:183-246`).
- **Global concurrency cap.** A single shared semaphore `Meter` sized `maxConcurrentCompiles` bounds concurrent pc requests across all instances (`CompilerPool.scala:164`, applied at `:90`). `CompilerPoolTest` "global compile cap" asserts the in-flight count never exceeds the cap (`:248-326`), while "cross-config parallelism" confirms distinct configs do run their backends simultaneously up to that cap (`:401-480`).

A `Response.Failed(error)` from the backend surfaces as `Abort.fail(error)`; a closed meter or a panic maps to `CompilerError.Fatal` (`CompilerPool.scala:91-97`).

### Resolve path: single-flight create

`resolve` reads the cache; on a miss it takes the per-config create lock, re-checks the cache, and (as the winner) creates and inserts the instance, removing the create lock from the map once the create completes (`CompilerPool.scala:103-122`). Concurrent first-ops on one config serialize on that lock so exactly one instance is built. `CompilerPoolTest` "single-flight create" drives N concurrent first-ops and asserts one instance serves all N (`:91-138`).

### Eviction: close on every path

The instance cache is built with `Cache.initWithFinalizer(maxLiveCompilers, expireAfterAccess = idleEviction)(instance => instance.close)` (`CompilerPool.scala:165-168`). **Backends are closed on every eviction path** because the finalizer is the single close site: LRU overflow past `maxLiveCompilers` and idle expiry past `idleEviction` both run `Instance.close`, which delegates to `backend.close` (`LocalBackend.scala:22-25`). `CompilerPoolTest` "pool eviction closes the Instance via the Cache finalizer" forces an eviction and waits for the close signal (`:511-564`); "one instance per config ... eviction triggers recreate" confirms a later op on an evicted config recreates rather than failing (`:45-89`).

This is the treat-closed-as-recreate contract: a returned `Compiler` whose instance was concurrently evicted re-resolves on its next op, and the caller holds no stale-handle obligation (`Compiler.scala:82-88`).

### Backend selection: isolate plus the version-match rule

`backendFor` computes `effectiveIsolate = isolate || config.toolchain.scalaVersion != CompilerPool.ownVersion`; if true it builds a `SpawnBackend`, otherwise a `LocalBackend` (`CompilerPool.scala:136-141`). `ownVersion` is kyo's own Scala version, `"3.8.4"` (`CompilerPool.scala:157`). So the in-JVM Local path is reachable only on a version-matched opt-out; a toolchain pinned to a different version forces a forked worker regardless of `isolate`. `CompilerPoolTest` "effectiveIsolate: version-mismatch with isolate=false routes to SpawnBackend" is the routing leaf, and it is the only pool test that uses a real driver (`:140-181`).

---

## The backends

`Backend` is the internal interface: `run(request): Response < (Async & Abort[CompilerError])` and `close: Unit < (Async & Abort[Throwable])` (`LocalBackend.scala:15-17`). `Instance(backend, mutex)` is the cache value; its `close` calls `backend.close` (`LocalBackend.scala:19-25`).

### LocalBackend (the version-matched fast path)

`LocalBackend` drives `dotty.tools.pc.ScalaPresentationCompiler` directly (`LocalBackend.scala:34-43`). `init` instantiates the pc once via `newInstance`, passing the config's Scala version (as the build-target id), classpath, and the options list, and wraps any throw as `CompilerError.InitializationFailed` so a pc that cannot start surfaces on the caller's first op (`LocalBackend.scala:96-107`). The options list is the config's `scalacOptions` plus, when `Config.sourceRoots` is non-empty, a trailing `-sourcepath <roots>` (the roots joined with the platform path separator) so the pc resolves symbols whose definitions live in a source root but not on the compiled classpath; `optionsList` appends it and an empty `sourceRoots` adds nothing (`LocalBackend.scala:113-127`). `run` dispatches the six ops to `pc.didChange` / `complete` / `hover` / `signatureHelp` / `definition` / `didClose`, adapting each result through `Wire` (`LocalBackend.scala:45-62`). `didClose` is the one op that is not bridged through a future: it is a `Sync.defer(pc.didClose(...))`, and like the others it normalizes the uri through `Wire.toAbsoluteUri` (`LocalBackend.scala:61-62`). `close` calls `pc.shutdown()` (`LocalBackend.scala:64-65`).

`LocalBackendTest` drives a real pc built from jars on the test classpath: diagnostics with `Error` severity for type and syntax errors and none for a clean buffer (`:42-86`), real type-checked completions (`:88-109`), a symbol that resolves only when `Config.sourceRoots` is wired into `-sourcepath` (`:111-183`), the interrupt-cancel path (`:185-204`), an exceptionally-completed pc future mapped to a typed `Fatal` rather than an escaped panic (`:206-216`), a pc throw surfacing as a typed `CompilerError` rather than an escaped throw (`:218-260`), and `didClose` followed by a successful recompile (`:262-289`).

### SpawnBackend (the forked worker)

`SpawnBackend` drives a per-config worker JVM over an aeron request/response session (`SpawnBackend.scala:6-23`). It holds the `Process`, the `Aeron` client, and a `kyo.Exchange[Request, Response, Nothing, TransportError]`. `init` spawns the worker, connects the aeron client to the pool's shared medium, wires the Exchange over two `aeron:ipc` streams, then runs a readiness probe (`SpawnBackend.scala:57-96`).

Load-bearing details:

- **Distinct stream-ids off a pool-owned counter.** The request and reply ride distinct aeron `stream-id`s, per direction and per config: `reqStreamId = streamIdBase * 2`, `respStreamId = streamIdBase * 2 + 1` (`SpawnBackend.scala:66-67`). The `streamIdBase` is a unique per-worker value the pool allocates from a monotonic `CompilerPool.streamIdCounter.getAndIncrement()` and threads into `init` (`CompilerPool.scala:24`, `:139`). The even/odd split per direction means a host never reads its own request back; the distinct base per config means two configs' workers never cross-talk on the one shared medium. The base comes from the counter rather than `config.hashCode` because a 32-bit hashCode could collide and silently mis-route one worker's reply to another host's request (`SpawnBackend.scala:60-65`). The host computes the ids and threads them to the worker through `-D` properties so both sides agree across the process boundary (`SpawnBackend.scala:122-139`, read back in `Worker.scala:23-26`). `SpawnBackendTest` "one shared MediaDriver per pool: two Spawn workers both reach it" gives the two workers distinct bases (0, 1), sends distinguishable buffers, and asserts no cross-talk (`:209-243`).

- **Bounded readiness probe with an interrupt-safe finalizer.** `ready` runs a cheap, idempotent `DidClose` probe under `Async.timeout(readyTimeout)` (30 seconds); a failure or timeout becomes `InitializationFailed("worker failed to start: ...")` (`SpawnBackend.scala:104-113`). A worker that cannot start (for example an unusable classpath whose publication never sees a subscriber) thus fails as `InitializationFailed` rather than hanging forever on the caller's first real op. **The readiness step is wrapped in a `Sync.ensure` finalizer that force-kills the partial worker and closes the aeron client on a failure OR an interrupt during the up-to-30s probe** (an IDE routinely cancels a cold start), transferring ownership to `close` only once the probe succeeds, so a worker that never starts leaks no aeron conductor thread or orphaned JVM (`SpawnBackend.scala:80-91`). `init` also takes a defaulted, test-only `onSpawn: Process => Unit` seam fired once the kill is armed and just before the probe (a no-op at the real call site, `SpawnBackend.scala:57`), so a test can interrupt mid-probe and assert the partial worker dies. `CompilerPoolTest`'s version-mismatch leaf exercises the failure path: an empty-classpath worker cannot load `kyo.compiler.Worker`, so the probe surfaces a worker-start `InitializationFailed` (`:140-181`); `SpawnBackendTest` "interrupting init during the readiness probe force-kills the partial worker" exercises the interrupt path (`:245-271`).

- **Transport break maps to `Fatal`, never a hang.** A `TransportError` or a closed session maps to `CompilerError.Fatal` at the backend boundary, and a broken session fails every pending op (`SpawnBackend.scala:30-39`). `transportErrors` collapses a `Closed | Topic.Backpressured` break into one typed `TransportError` (`SpawnBackend.scala:218-223`). `SpawnBackendTest` "no thread leak after a kill" proves every op after `close` fails with a typed `Fatal` and none hangs (`:155-186`), and "a worker-comms failure surfaces didClose as a typed Fatal" covers the `didClose` path specifically (`:188-207`).

- **close releases all three resources.** `close` runs `exchange.close`, then `aeron.close()`, then `process.destroyForcibly` (`SpawnBackend.scala:41-44`). The pool's close-on-evict finalizer drives this on eviction.

- **Module-opener flags are forwarded.** `moduleArgs` copies the parent JVM's `--add-opens` / `--add-exports` / `--add-modules` / `--enable-native-access` flags to the worker so its pc reaches the same internal modules the host opened (`SpawnBackend.scala:149-163`).

`SpawnBackendTest` also runs a parity leaf comparing Local and Spawn results for the same buffer over a real embedded `MediaDriver` (`:55-92`).

---

## The wire

`Wire` is the only place lsp4j and `scala.meta.pc` types appear; they never reach a `Request` / `Response` / `Envelope` case or any public signature (`Wire.scala:57-63`). The same adapter runs in the in-process backend and in the worker host, so both produce identical neutral results for one buffer (`Wire.scala:61-62`; the parity test above is the proof).

The neutral wire types (`Wire.scala:11-55`):

- `Request` and `Response` are one case per op, both `derive Compiler.AsMessage`. Neither carries a correlation id: the request/reply id correlation belongs to the transport, owned by `kyo.Exchange`. A worker-side typed failure travels in-band as `Response.Failed`, distinct from a transport break.
- `Envelope` is the id-carrying frame `kyo.Exchange` sends both directions: `Req(id, request)` host to worker, `Resp(id, response)` worker to host. `Topic.publish[Envelope]` / `Topic.stream[Envelope]` carry it natively.

Adapter rules a contributor must keep:

- **Total over null and `Either` sides.** Every lsp4j reader returns a documented default rather than throwing: `eitherText`, `paramLabel`, `diagnosticCode`, `severity`, `completionKind`, `hoverMarkdown`, `markedString`, and `rangeToSpan` all handle `null` and both sides of an lsp4j `Either` (`Wire.scala:159-269`). `WireTest` "adapter maps null/missing lsp4j fields to Absent and full fields to expected neutral values" pins this (`:146-208`).
- **The diagnostics opt-in.** `compileParams` overrides `shouldReturnDiagnostics()` to `true`; the interface default is `false`, under which `didChange` returns an empty list even for a buffer with errors (`Wire.scala:65-83`). `WorkerTest` confirms the worker-side path sets it too (`:17-53`).
- **Absolute-uri normalization.** The pc silently returns empty results for relative URIs, so `toAbsoluteUri` makes a bare filename absolute (`"Main.scala"` becomes `file:///Main.scala`) and parses an already-schemed string as-is (`Wire.scala:98-108`). Every op routes its uri through this, including `didClose`.
- **Offset mapping.** `LineIndex` precomputes each line's starting offset once and maps an lsp4j line/character position to a UTF-16 offset, clamping a past-end position to the buffer length and a null position to 0 (`Wire.scala:296-311`). `WireTest` "LineIndex maps line/character positions to UTF-16 offsets, including CRLF and tab" covers CRLF, tabs, empty lines, and out-of-range clamping (`:90-144`).
- **Symbol naming.** `simpleName` and `symbolKind` map a SemanticDB-style symbol such as `scala/collection/Seq#` to a simple name and a neutral kind by its suffix (`()` method, `#` type, `.` term, unsuffixed path package), `Wire.scala:271-285`.

---

## The worker

`kyo.compiler.Worker` is the forked-worker entry point, launched as `java -cp <worker + scala3-pc:vN> kyo.compiler.Worker` (`Worker.scala:7-29`). It extends `KyoApp`, so the process boundary is its containment boundary and there is no hand-rolled top-level catch (`Worker.scala:13-16`). Because no user frame exists at a process main and `Frame` cannot derive in `package kyo`, the run roots its `Frame` at `Frame.internal` (`Worker.scala:18-20`).

- `WorkerConfig.fromEnv` parses the per-config `Compiler.Config` from the `-D` system properties the host passed, splitting the classpath and the source roots on the platform separator (the same separator the host joined with) so the worker's pc gets the same `-sourcepath` the in-process backend would, and sets `isolate = Present(false)` because the worker always hosts the in-process Local path (`Worker.scala:37-65`).
- `WorkerServer.serve` builds a `LocalBackend` over that config, connects an aeron client to the host's existing shared medium (the host's driver directory, not a second driver), and runs the serve loop as `Topic.stream[Envelope].foreach`, publishing an id-matched `Envelope.Resp` for each `Envelope.Req` (`Worker.scala:74-96`).
- **A transport break ends the worker rather than being swallowed.** A `Closed` medium or unclearable backpressure propagates out of the serve loop, ends it, and stops the worker, so the host's Exchange observes a broken session and fails its pending op with a typed `TransportError` instead of hanging on a silently dropped reply; the break is logged, not discarded (`Worker.scala:80-94`, `:119-124`). The host then force-kills and respawns.
- A backend failure or panic for one request is different: it is reported in-band as a `Response.Failed` frame and the serve loop keeps running (`Worker.scala:107-117`).

`WorkerTest` drives the exact worker-side path (`WorkerConfig.fromEnv` then a `LocalBackend`) to verify the diagnostics opt-in survives the round trip (`:17-53`).

---

## Unsafe and bridging boundaries

This module has two bridging boundaries; each must stay annotated and resource-owned.

- **The process spawn.** `Command(args*).inheritStderr.unsafe.spawn()` runs inside `Sync.Unsafe.defer` and is spawned outside any `Scope`, so its lifetime is owned by `SpawnBackend.close` (and the pool's close-on-evict finalizer), not by an enclosing scope close (`SpawnBackend.scala:140-146`). The `// Unsafe:` comment states exactly that contract. A launch failure maps to `InitializationFailed`.
- **The pc future bridge.** `LocalBackend.bridge` (a `private[kyo]` method on the `LocalBackend` companion object) lifts a pc `CompletableFuture` into `Async` with `Sync.defer` + `Sync.ensure(future.cancel(true))` + `Async.fromCompletionStage`, folding both a synchronous throw and an exceptionally-completed future into a typed `CompilerError.Fatal` rather than letting either escape as a panic (`LocalBackend.scala:76-91`). The `future.cancel(true)` finalizer is the cancellation half of the headline invariant; do not remove it when refactoring the bridge.

Per the root guide, prefer the safe tier; reach for an unsafe or bridging site only at a justified boundary and keep the resource owned by an explicit `close`.

---

## Conventions

### Reliability: never leak a resource

Reliability is a primary goal: a resource you acquire must never leak. Close everything you open, even on a failure or an interrupt, via `Sync.ensure` or `Scope`, never a bare close on the happy path only. A leaked process, file, socket, or `Meter` is a bug, not a nuisance. The discipline is load-bearing here: `Instance.close` closes both the backend and its per-instance mutex `Meter`, the pool's close-on-evict finalizer reclaims every evicted instance, and `SpawnBackend.close` force-kills the worker process. When you add a resource, own its close the same way.

### JVM-only, no shared tree

The module is `crossProject(JVMPlatform)` (`build.sbt:1188`), so all source and tests live under `kyo-compiler/jvm/`. There is no cross-platform discipline to maintain here, but tests `fork := true` and require the `--add-opens` JVM flags (`build.sbt:1195-1201`); a test that drives a real pc or a real worker needs them.

### Kyo types and totality

Use the Kyo primitives throughout (`Maybe` not `Option`, `Result` not `Either`, `Chunk` not `List`/`Seq`). The `Wire` adapters are the seam where lsp4j's nullable, `Either`-shaped Java types are made total: every reader returns a default instead of throwing, and the pc's `Optional` results become `Maybe`. Keep that totality when extending the adapter.

### Visibility

Only the `Compiler` trait, `Compiler.Pool`, and the result/config types nested in `object Compiler` are public. Everything else (`CompilerPool`, `Backend`, `LocalBackend`, `SpawnBackend`, `Instance`, `TransportError`, `Wire`, `Request`, `Response`, `Envelope`, `Worker`, `WorkerConfig`, `WorkerServer`) is `private[kyo]` or `private[compiler]`. Do not widen these; `CompilerTest` pins that the surface rejects a leaked lsp4j type and a backend method (`pool.spawn`, `c.cancel`), `:95-106`.

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
| `CompilerTest` | the public surface | result-ADT upickle round-trips, `Uri` opacity, the three-state `isolate` default, and compile-fail witnesses that no `cancel`/`spawn`/lsp4j surface exists |
| `WireTest` | the adapter | pure `Wire` tests: `LineIndex` offsets, null / `Either` handling, hover / signature / symbol adapters, request/response/envelope round-trips, and a corrupt-envelope decode raising a contained throw |
| `LocalBackendTest` | the in-JVM pc | a real pc built from classpath jars: diagnostics, completions, `sourceRoots` `-sourcepath` resolution, interrupt-cancel, exceptional-future-as-Fatal, pc-throw-as-typed, didClose-then-recompile |
| `WorkerTest` | the worker-side path | `WorkerConfig.fromEnv` + `LocalBackend`, proving the diagnostics opt-in survives |
| `CompilerPoolTest` | pool policy | seeds the instances cache with stub backends (`makePool`, null driver) to exercise single-flight, per-instance serialization, the global cap, cross-config parallelism, isolation, lazy create, and eviction-close, without spawning |
| `SpawnBackendTest` | the forked worker | real cross-process round-trips over an embedded `MediaDriver` via `withDriver`: parity, kill-and-no-leak, comms-failure-as-Fatal, shared-driver no-cross-talk, interrupt-init-during-readiness, plus an in-memory-Exchange interrupt leaf |

Two patterns are worth copying when you add a test:

- **Stub-backend pool tests.** `CompilerPoolTest.makePool` constructs a real `CompilerPool` with a fresh semaphore and create-locks but a caller-supplied instances cache pre-seeded with stub `Backend`s; the `MediaDriver` is `null` because `SpawnBackend.init` is never reached when stubs are seeded (`CompilerPoolTest.scala:24-30`). Use this to test pool policy deterministically. Reach for `makePoolWithDriver` (a real shared driver) only when a leaf must drive a real `SpawnBackend`, as the version-mismatch routing leaf does (`:35-43`).
- **Real-driver worker tests.** `SpawnBackendTest.withDriver` runs a body against a fresh embedded `MediaDriver` closed on scope exit (`:49-53`), and `fullClasspath` builds the worker's `-cp` from the test JVM's classpath so the spawned worker can load `kyo.compiler.Worker`, kyo-aeron, and the pc (`:14-22`). The interrupt leaf instead wires `run` through a controlled in-memory `Exchange` with a throwaway child process for a real `Process` handle, so it never opens a real aeron session (`:94-153`).

Per the root guide: every test asserts a concrete value (the suites here compare full neutral values, not just non-emptiness), keeps its reproducing assertions named with the source it covers, and leaves no orphan or scratch test behind.

---

## Decision checklist: before adding a new X

1. **New `Compiler` op.** Add it to the `Compiler` trait (row `< (Async & Abort[CompilerError])`), add a `Request` and `Response` case (both `derive Compiler.AsMessage`), add the dispatch arm to `LocalBackend.run`, add the `Wire` adapter (keeping lsp4j/`scala.meta.pc` inside `Wire`), and add the `CompilerPool` view arm. The worker serves it automatically because it drives the same `Backend`. Add a `WireTest` round-trip and a `LocalBackendTest` leaf. Do not add a cancel lever.

2. **New result or wire type.** `derive CanEqual, Compiler.AsMessage`, keep it neutral and offset-based (UTF-16 code units, no lsp4j or `java.net.URI`), and add it to the round-trip tests in `CompilerTest` / `WireTest`.

3. **New backend.** Implement `Backend.run` and `Backend.close`. Confirm a fiber interrupt during `run` cancels the underlying op end to end (the headline invariant), and that `close` releases every resource the backend owns on every path, including a readiness or init failure. If it forks a process or opens a transport, route any transport break to a typed `CompilerError.Fatal` and never let an op hang.

4. **Touching the `Wire` adapter.** Keep every reader total over `null` and both `Either` sides. No lsp4j or `scala.meta.pc` type may escape `Wire` into a `Request`/`Response`/`Envelope` case or a public signature. Preserve the `shouldReturnDiagnostics` opt-in and the absolute-uri normalization.

5. **Changing the spawn transport.** Keep request and reply on distinct aeron `stream-id`s, distinct per direction (even/odd) and per config (a unique base from the pool's monotonic counter, never a hashCode that could collide), and keep the host computing them and threading them to the worker. Never swallow a transport break in the serve loop; it must end the worker so the host sees a clean break, not a hang. Keep the readiness probe bounded and its failure-or-interrupt path force-killing the partial worker and closing the aeron client (the `Sync.ensure` finalizer), transferring ownership to `close` only on success.

6. **New unsafe or bridging site.** Annotate it with a `// Unsafe:` comment naming the contract it bridges. If it spawns a process or opens a resource unscoped, an explicit `close` (driven by the pool's eviction finalizer) must own its lifetime.

7. **New pool policy.** Decide whether it belongs on `Pool.Settings` or `Config`, respect the three-state `isolate` (`Absent` = pool default), and keep the op path as per-instance mutex inside the global semaphore. Test it with a stub-backend `makePool` rather than spawning a real worker.
