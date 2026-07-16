# Contributing to kyo-system

Module-specific guide for kyo-system. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries global conventions, the test framework, AllowUnsafe tiers, safe-to-unsafe bridging, non-blocking concurrency, visibility tiers, and platform-conditional test gates. This document records only what is specific to kyo-system.

**The headline invariant:** the negative capability law. A write op left in a program passed to a read-only runner keeps `PathWrite` undischarged, so the ascribed read-only residual does not compile [`Path.scala:579-581`]. Filesystem mutation boundaries are enforced in the type system: read-only contexts reject write programs at the call site, not at runtime [`Path.scala:63-66`]. Internalize this before touching runners, service backends, or overlay combinators.

---

## Architecture overview

kyo-system compiles as a four-platform `crossProject` (JVM, JS, Native, Wasm) that depends only on `kyo-core` [`../build.sbt:695-699`]. `kyo-eventlog` is an inbound consumer of kyo-system, not an outbound dependency from kyo-system's side [`../build.sbt:671-675`]. Public APIs must compile on all four platforms [`../build.sbt:695-698`].

kyo-system owns five capabilities:

| Capability | Safe type | Unsafe abstract class | Effect row |
|---|---|---|---|
| File paths and I/O | `Path` (opaque); capabilities `PathRead` / `PathWrite` | `Path.Unsafe` | `PathRead` / `PathWrite` (discharged by runners); residual `Sync & Abort[FileException]`; `Scope` on streaming ops |
| OS process launch | `Command` (opaque) | `Command.Unsafe` | `Sync`, `Async`, `Abort[CommandException]`, `Scope` |
| Running process handle | `Process` (opaque) | `Process.Unsafe` | `Sync`, `Async`, `Scope` |
| System environment | `System` (abstract class) | `System.Unsafe` | `Sync`, `Abort[E]` |
| Stream-to-file sinks | `StreamFileExtensions` (object) | (delegates to `Path.WriteHandle` via `PathWrite` ops) | `Scope`, `PathWrite`, `Sync`, `Abort[FileException]` |

`object Path extends PathPlatformSpecific`, wiring the shared API to platform factories [`Path.scala:79`]. `StreamFileExtensions` lives in kyo-system because it couples to `Path` and `PathWrite` capability ops; kyo-core keeps the platform-neutral stream combinators [`StreamFileExtensions.scala:5-10`].

### Source layout

```
kyo-system/
  shared/src/main/scala/kyo/
    Path.scala                    # opaque type, PathRead/PathWrite capabilities, Service SPI, runners, combinators
    HostService.scala             # private[kyo] host delegate + root-confined backend
    InMemoryService.scala         # private[kyo] AutoCommit virtual backend
    OverlayService.scala          # private[kyo] ManualCommit copy-on-write overlay + ForwardingLowerService
    CommitConflict.scala          # CommitConflict, Conflict, Resolution value types
    Command.scala                 # opaque type, safe tier, abstract Unsafe
    Process.scala                 # opaque type, safe tier, abstract Unsafe, ExitCode export
    System.scala                  # abstract class, live impl, Local, platform import
    StreamFileExtensions.scala    # stream-to-file sinks
    FileException.scala           # sealed hierarchy with marker traits
    CommandException.scala        # sealed hierarchy for pre-launch failures
    internal/PathDirectories.scala  # shared OS directory resolution (private[kyo])

  jvm-native/src/main/scala/kyo/
    PathJvmNative.scala           # toJava extension (JVM + Native only)
    internal/PathPlatformSpecific.scala   # NioPathUnsafe backed by java.nio.file.Path
    internal/ProcessPlatformSpecific.scala
    internal/SystemPlatformSpecific.scala # delegates to java.lang.System

  js-wasm/src/main/scala/kyo/
    internal/PathPlatformSpecific.scala   # Node.js @JSImport facades (NodeFs, NodePath)
    internal/ProcessPlatformSpecific.scala
    internal/SystemPlatformSpecific.scala # Node process.env / process.platform

  shared/src/test/scala/kyo/
    PathTest.scala, PathCapabilityTest.scala, PathTransactionTest.scala
    PathConfinementTest.scala, InMemoryServiceTest.scala
    OverlayServiceTest.scala, OverlayServiceCommitTest.scala, OverlayServiceRecoveryTest.scala
    CommitConflictTest.scala
    CommandTest.scala, ProcessTest.scala, SystemTest.scala, FileExceptionTest.scala

  jvm/src/test/scala/kyo/
    PathJvmTest.scala             # JVM-only: toJava, JFiles.createSymbolicLink
    PathConfinementJvmTest.scala  # JVM-only: Service.host(root) symlink escape
```

Virtual filesystem backends (`InMemoryService`, `OverlayService`) live in `shared/src/main` as `private[kyo]` objects/classes in dedicated source files, not in platform trees [`InMemoryService.scala:5`, `InMemoryService.scala:53`, `OverlayService.scala:20`, `OverlayService.scala:366`]. Shared OS directory logic lives in `shared/src/main/internal`; both platform `PathPlatformSpecific` classes extend it [`PathDirectories.scala:5-8`].

### End-to-end read call flow

1. `path.read` suspends `Path.Op.Read` under `Tag[PathRead]` via `ArrowEffect.suspend` [`Path.scala:1085-1086`].
2. A runner (`Path.run`, `Path.runReadOnly`, or `runWith`) discharges the capability via `ArrowEffect.handle` and routes the op through `dispatch` to the installed `Service` [`Path.scala:600-603`, `Path.scala:816-818`].
3. `HostService.read` bridges `path.unsafe.read()` through `Sync.Unsafe.defer(Abort.get(...))` [`HostService.scala:35-37`].
4. Platform I/O executes in `NioPathUnsafe` (JVM/Native, backed by `java.nio.file.Path`) or the Node.js-backed class (JS/Wasm, via `@JSImport` facades) [`jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala:24-25`, `js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala:13-15`].
5. At the unsafe tier, `read()` returns `Result[FileReadException, String]` with `(using AllowUnsafe, Frame)` [`Path.scala:1536`].

---

## The negative capability law (headline invariant)

### Goal

Filesystem I/O is tracked as separate read and write capabilities so mutation boundaries are visible in the effect row before a runner executes anything [`Path.scala:49-54`]. A computation that only reads carries `< PathRead`; a computation that writes carries `< PathWrite`, which subtypes `PathRead` [`Path.scala:63-66`]. The negative capability law is the compile-time gate: if a program contains a write op but the caller ascribes a read-only runner residual, `PathWrite` remains undischarged and the program does not compile [`Path.scala:579-581`, `Path.scala:607-608`].

### Mechanisms

**Separate capabilities over one op family.** `PathRead` and `PathWrite` are both `ArrowEffect` instances over a single reified `Path.Op` enum [`Path.scala:61`, `Path.scala:77`, `Path.scala:130-136`]. One shared op family serves both capabilities because a class cannot extend `ArrowEffect` twice with different inputs, and `PathWrite <: PathRead` inherits `PathRead`'s input constructor [`Path.scala:130-136`]. Read-group cases suspend under `Tag[PathRead]`; write-group cases suspend under `Tag[PathWrite]` [`Path.scala:137-157`].

**Subtyping collapses mixed rows.** Because `PathWrite <: PathRead`, a write-capable context also satisfies read operations; a mixed read-plus-write program's row collapses to `PathWrite` [`Path.scala:63-66`]. Write presence is always visible at the type level.

**Safe methods hide Sync and Abort until discharge.** Safe extension methods suspend via `ArrowEffect.suspend` and carry only `PathRead` or `PathWrite` on the row, not `Sync` or per-op `Abort` [`Path.scala:1085-1086`, `Path.scala:1292-1293`]. `Sync` and the `Abort[FileException]` umbrella are folded into the capability and become visible only after a runner discharges it [`Path.scala:49-52`].

**Runners discharge and expose backend effects.** Runners call `ArrowEffect.handle`, dispatch each op to the installed `Service`, and expose the backend effect `S` on the residual [`Path.scala:600-603`, `Path.scala:611-613`]:

| Runner | Discharges | Input row | Residual |
|---|---|---|---|
| `Path.run` / `runWith(service)` | `PathWrite` (covers reads via subtyping) | `A < (PathWrite & S2)` | `A < (S & Abort[FileException] & S2)` |
| `Path.runReadOnly` / `runReadOnlyWith(service)` | `PathRead` only | `A < (PathRead & S2)` | `A < (S & Abort[FileException] & S2)` |

Both default to `Service.host`; residuals carry `Sync & Abort[FileException] & S` [`Path.scala:566-571`, `Path.scala:576-577`, `Path.scala:590-591`].

### When each runner fires

- **`Path.run` / `runWith`:** use when the program may read and write. Discharges both capabilities against the service backend [`Path.scala:566-571`].
- **`Path.runReadOnly` / `runReadOnlyWith`:** use when the program must not mutate the filesystem. Discharges read capability only [`Path.scala:576-577`]. A write op in the program keeps `PathWrite` undischarged and fails to compile (the negative capability law) [`Path.scala:579-581`].
- **`Path.runWith(service)` / `runReadOnlyWith(service)`:** use when testing or running against a non-host backend (`Service.inMemory`, `Service.overlay`, or a custom `Service[S]`) [`Path.scala:600-603`, `InMemoryServiceTest.scala:7-10`].

### Decision rule

Before ascribing a runner residual, ask: does this program contain any write-group op (`write`, `append`, `mkDir`, `move`, `tempDir`, etc.)? If yes, you must use `Path.run` or `runWith`, not `runReadOnly`. Passing a write program to `Path.runReadOnly` fails at compile time with an undischarged `PathWrite`, not at runtime [`PathCapabilityTest.scala:29-38`]. The compiler error mentions `PathWrite` [`PathCapabilityTest.scala:29-38`].

Compile-time row ascriptions in `PathCapabilityTest` prove the law: a green compile is the assertion [`PathCapabilityTest.scala:11-17`]:

```scala
val readOnly: String < PathRead                      = somePath.read
val writer: Unit < PathWrite                         = somePath.write("x")
val mixed: String < PathWrite                        = somePath.read.map(s => otherPath.write(s).andThen(s))
val readRuns: String < (Sync & Abort[FileException]) = Path.runReadOnly(readOnly)  // compiles
// val bad = Path.runReadOnly(writer)  // does NOT compile: PathWrite undischarged
```

### Config knobs (capability-related)

| Knob | Values | Effect |
|---|---|---|
| Runner | `run`, `runReadOnly`, `runWith`, `runReadOnlyWith` | Which capability tag is discharged |
| Default service | `Service.host` | Host filesystem backend with `AutoCommit` disposition |
| Custom service | `Service.inMemory`, `Service.overlay(lower)`, custom `Service[S]` | Virtual or confined backends; backend effect `S` rides the residual |
| Service disposition | `AutoCommit`, `ManualCommit`, `CommitOnSuccess` (reserved) | When staged writes become visible (see Service SPI section) |

---

## Safe and unsafe two-tier coupling (all four capabilities)

Every public operation in kyo-system is backed by two structurally coupled tiers. This applies to `Path`, `Command`, `Process`, and `System`, not only Path.

**Safe tier:** opaque types (`Path`, `Command`, `Process`) or abstract class (`System`) track I/O in the effect system. For Path, safe extension methods suspend under `ArrowEffect` capabilities rather than calling `Sync.Unsafe.defer` directly [`Path.scala:1030-1031`]. For Command, Process, and System, safe-tier methods lift through `Sync.Unsafe.defer`.

**Unsafe tier:** `Path.Unsafe`, `Command.Unsafe`, `Process.Unsafe`, `System.Unsafe` are abstract classes where platform-specific I/O executes. Each public capability is an opaque type alias over its `Unsafe` class, exposing `.unsafe` to reach the platform tier [`Path.scala:45`, `Command.scala:47`, `Process.scala:40`]. Effectful unsafe methods take `(using AllowUnsafe, Frame)` and return typed `Result` errors at the unsafe tier, not `Abort` [`Path.scala:1501-1502`, `Path.scala:1536-1537`]. `Command.Unsafe.spawn` returns `Result[CommandException, Process.Unsafe]` so pre-launch failures stay typed before safe-tier lifting [`Command.scala:239-240`]. Pure builder methods on `Command` mutate the wrapped `Unsafe` value and return `.safe` without performing I/O [`Command.scala:171`].

**Coupling rule:** every abstract method added to an `Unsafe` class requires a matching safe-tier lift (Path: extension method + `Op` case + service dispatch + platform impl in both leaves; Command/Process/System: safe-tier method). Adding an abstract method to `Path.Unsafe` without a matching safe-tier lift breaks the structurally coupled tiers [`Path.scala:45`, `Command.scala:47`, `Process.scala:40`].

**Production unsafe bridging:** every call to an `Unsafe` method inside kyo-system production code must be wrapped in `Sync.Unsafe.defer(...)` and annotated with a `// Unsafe:` comment naming the safe-tier contract being bridged [`HostService.scala:17-18`, `StreamFileExtensions.scala:25`]. Do not introduce `import AllowUnsafe.embrace.danger` in kyo-system sources; all unsafe execution passes through `Sync.Unsafe.defer`. `HostService` lifts `Result`-returning unsafe ops with `Sync.Unsafe.defer(Abort.get(...))` and pairs each call with a `// Unsafe:` comment [`HostService.scala:35-37`]. Inspection unsafe ops that return plain values omit `Abort.get` inside `Sync.Unsafe.defer` [`HostService.scala:17-18`].

**Target convention (not yet universal):** `Command` and `System` safe-tier lifts use `Sync.Unsafe.defer` but do not yet carry `// Unsafe:` comments, unlike `Path.HostService` [`Command.scala:67-68`, `System.scala:89`]. New lifts on Path backends, `StreamFileExtensions`, `InMemoryService`, and `OverlayService` must follow the annotated pattern.

| Return shape at safe tier | Lift pattern | Example |
|---|---|---|
| `A < PathRead` or `A < PathWrite` | `ArrowEffect.suspend(Tag[...], Path.Op.X(...))` | `path.read` [`Path.scala:1085-1086`] |
| `A < (Sync & Abort[E])` from `Result`-returning unsafe | `Sync.Unsafe.defer(Abort.get(unsafe.op()))` + `// Unsafe:` | `HostService.read` [`HostService.scala:35-37`] |
| `A < Sync` from plain-value unsafe | `Sync.Unsafe.defer(unsafe.op())` + `// Unsafe:` | `HostService.exists` [`HostService.scala:17-18`] |
| `Process < (Sync & Scope & Abort[CommandException])` | `Sync.Unsafe.defer { ... }` (comments pending) | `Command.spawn` [`Command.scala:67-68`] |

---

## Path capability model (supporting detail)

### Service SPI and factories

`Path.Service[S]` is the pluggable backend SPI. It is effect-polymorphic in `S` (the backend's own effect, exposed on a runner residual); methods take no `(using Frame)` because a service captures its `Frame` at construction; the umbrella `Abort[FileException]` is the uniform error row [`Path.scala:204-207`, `Path.scala:215-219`].

| Factory | Class | Disposition | Effect `S` | Role |
|---|---|---|---|---|
| `Service.host` | `HostService` | `AutoCommit` | `Sync` | Default host backend; delegates every op to `Path.Unsafe` [`Path.scala:288`, `HostService.scala:13-14`] |
| `Service.host(root)` | rooted `HostService` | `AutoCommit` | `Sync` | Resolves `root.realPath` at construction; rejects ops whose canonical path escapes the confinement root [`HostService.scala:9-11`, `HostService.scala:155-158`] |
| `Service.inMemory` | `InMemoryService` | `AutoCommit` | `Sync` | Immutable node tree keyed by `Path.parts` behind one `AtomicRef`, advanced by optimistic CAS [`Path.scala:298-302`, `InMemoryService.scala:53-55`] |
| `Service.overlay(lower)` | `OverlayService` | `ManualCommit` | inherits `S` from lower | Copy-on-write over `lower`; reads fall through, writes stage in upper layer; scope-managed auto-rollback [`Path.scala:304-308`, `OverlayService.scala:363-374`] |

**Disposition** governs when staged bytes become visible [`Path.scala:182-199`]:

| Disposition | Contract | Used by |
|---|---|---|
| `AutoCommit` | Each successful write is durable immediately | `Service.host`, `Service.inMemory` |
| `ManualCommit` | Writes stage until explicit `CommitHandle.commit` | `Service.overlay` |
| `CommitOnSuccess` | Writes stage during enclosing run, commit on success | Reserved; not used by shipped factories |

**Overlay internals (summary).** Staged writes are recorded as `WriteOp` journal entries, distinct from the read/write op-family partition in `Path.Op` [`OverlayService.scala:6-17`]. Upper-layer state uses `Upper` variants: staged `Entry`, deletion `Whiteout`, directory-hiding `OpaqueDir` [`OverlayService.scala:22-28`]. First observation of a lower path records a `Path.Stamp` in the read-set; commit compares stamps against live lower to detect divergence [`Path.scala:897-907`, `OverlayService.scala:436-442`]. Overlay commit machinery uses a self-contained binary intent log with no `kyo-eventlog` dependency to avoid circular module edges [`OverlayService.scala:55-58`].

**Commit strategies** on `CommitHandle[S]` (extends `Service[S]`) [`Path.scala:937-964`]:

| Method | Behavior | Residual includes `Abort[CommitConflict]`? |
|---|---|---|
| `commit` | Validates read-set; aborts `CommitConflict` if stamps diverged; lower untouched on conflict | Yes |
| `commitOverwrite` | Replays unconditionally (last-writer-wins); no conflict check | No [`Path.scala:958`] |
| `commitWith(resolve)` | Validates, calls `resolve` per conflict to obtain a `Resolution`, replays resolved entries | Yes (unless resolve handles all) |
| `rollback` | Discards staged writes without touching lower | No |

`CommitConflict`, `Conflict`, and `Resolution` are public types in `CommitConflict.scala` [`CommitConflict.scala:3-9`, `CommitConflict.scala:28-33`, `CommitConflict.scala:47-51`]. `Conflict.ancestor` carries `Maybe[Path.Stamp]`, not `Maybe[Path.Entry]`, because the read-set records only a stamp at observation [`CommitConflict.scala:16-26`]. `Resolution` has four cases: `KeepOurs`, `KeepTheirs`, `Write(entry)`, `Remove` [`CommitConflict.scala:47-51`]. Non-conflicting staged entries replay regardless of per-conflict resolution choices [`CommitConflict.scala:44-45`].

**Overlay traps:**

- Calling `commit` or `rollback` on a virtual overlay after `PathWrite` is discharged leaves path operations unresolved at runtime [`Path.scala:708-710`]. `Path.virtual` commit outside an ambient `PathWrite` scope leaves suspensions unresolved; `evalNow` returns absent [`PathTransactionTest.scala:167-176`].
- `Path.Stamp.Kind.Absent` stamps a path that was observed but happened to not exist at that moment; `Maybe.Absent` on `Conflict.ancestor` means the path was never read through the overlay at all [`Path.scala:926-931`].

### Overlay combinators (transaction, sandbox, virtual)

These combinators share an overlay bootstrap that casts `Sync` off the residual so combinator return rows stay locked without `Sync`, the same way `exists`/`read` hide `Sync` behind suspend/dispatch [`Path.scala:616-621`].

| Combinator | Return row | Disposition on success |
|---|---|---|
| `Path.transaction` | `A < (PathWrite & Abort[CommitConflict] & S)` | Commits overlay; rolls back on `Abort[CommitConflict]` [`Path.scala:671`, `Path.scala:659-665`] |
| `Path.sandbox` | `A < (PathWrite & S)` | Always rolls back; never surfaces `CommitConflict` [`Path.scala:686-693`] |
| `Path.virtual` | `(A, Service.Overlay[Sync]) < (PathWrite & S)` | Caller must commit or rollback within ambient `PathWrite` scope [`Path.scala:701-712`] |

Overlay combinator residuals must not require `Sync`: compile-time row ascriptions prove `sandbox`/`transaction`/`virtual` preserve caller tail `S` without widening to `Sync` [`PathCapabilityTest.scala:19-27`]. `Abort[String]` is used as a service effect that does not subsume `Sync`; a widened `Sync & PathWrite & S` return would fail to ascribe [`PathCapabilityTest.scala:19-27`].

Overlay combinators forward lower I/O back through `ArrowEffect.suspend` so the ambient `PathWrite` handler stays transparent [`OverlayService.scala:1508-1509`, `OverlayService.scala:1523-1524`]. `ForwardingLowerService` re-suspends lower I/O as `PathRead`/`PathWrite` so exceptions propagate through the outer handler, not inside the overlay bootstrap handler [`Path.scala:406-408`, `OverlayService.scala:1507-1600`].

All three require an ambient `PathWrite` scope (an enclosing `runWith` or `run`) [`Path.scala:659-665`, `Path.scala:686-693`].

---

## Cross-platform discipline

Public APIs compile on JVM, JS, Native, and Wasm [`../build.sbt:695-698`].

| Concern | JVM + Native | JS + Wasm |
|---|---|---|
| Path I/O | `NioPathUnsafe` in `jvm-native/internal/PathPlatformSpecific.scala`, backed by `java.nio.file.Path` [`jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala:24-25`] | Node.js `@JSImport` facades (`NodeFs`, `NodePath`) confined to `js-wasm/internal/PathPlatformSpecific.scala` [`js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala:13-15`] |
| OS directories | Both platform `PathPlatformSpecific` classes extend `internal/PathDirectories.scala` [`PathDirectories.scala:5-8`] | Same shared trait |
| JVM interop | `PathJvmNative.toJava` in `jvm-native/` (precise `java.nio.file.Path`, no cast) [`PathJvmNative.scala:6-13`] | Absent (`java.nio.file.Path` unavailable on Scala.js) |
| Process/Command | `ProcessPlatformSpecific.makeCommand` is the single construction entry point [`jvm-native/src/main/scala/kyo/internal/ProcessPlatformSpecific.scala:526-528`] | Platform-specific impl in `js-wasm/` |
| System | JVM/Native delegates to `java.lang.System` [`jvm-native/src/main/scala/kyo/internal/SystemPlatformSpecific.scala:5-7`] | JS uses `process.env` and `process.platform` fallbacks [`js-wasm/src/main/scala/kyo/internal/SystemPlatformSpecific.scala:13-19`, `js-wasm/src/main/scala/kyo/internal/SystemPlatformSpecific.scala:26-42`] |

For global cross-platform placement rules (when code belongs in `shared/` vs platform trees, visibility tiers), defer to the root [CONTRIBUTING.md](../CONTRIBUTING.md).

JVM-only tests requiring `java.nio.file` APIs (`toJava`, symlink creation) live in `jvm/src/test` [`PathJvmTest.scala:5-7`]. Confinement symlink-escape tests are JVM-only; the missing-parent arm stays cross-platform in `PathConfinementTest` [`PathConfinementJvmTest.scala:6-11`, `PathConfinementTest.scala:3-7`].

---

## Conventions

### Error hierarchies

- `FileException` and marker traits (`FileReadException`, `FileWriteException`, `FileFsException`): each concrete exception implements only the traits of operations that can actually raise it [`FileException.scala:12-13`, `FileException.scala:62-64`]. Reserve `FileIOException` for low-level I/O not covered by more specific subtypes [`FileException.scala:93-94`].
- `CommandException`: pre-launch failures for `Command`.

### Return-type discriminator (Path operations)

| If the operation... | Safe-tier return type | Suspension tag |
|---|---|---|
| Reads (exists, read, list, walk, stat, ...) | `A < PathRead` | `Tag[PathRead]` |
| Mutates (write, append, mkDir, move, tempDir, ...) | `A < PathWrite` | `Tag[PathWrite]` |
| Runs against host after runner discharge | `A < (Sync & Abort[FileException])` | (capability discharged) |
| Runs against custom service `S` | `A < (S & Abort[FileException])` | (capability discharged) |

Safe-tier Path extension methods never call `Sync.Unsafe.defer` directly; they suspend under the capability tag [`Path.scala:1030-1031`]. Lifting happens in `HostService` and platform `Unsafe` implementations.

### Scope-managed handles

`StreamFileExtensions` acquires write handles via capability ops in a `Scope`; on failure the partially-written file is removed (delete-on-close) [`StreamFileExtensions.scala:5-10`]. Handle cleanup at the safe tier uses `Sync.Unsafe.defer` with an inline `// Unsafe:` comment on the release line [`StreamFileExtensions.scala:25`].

### System ambient instance

`System` is an abstract class with a `Local[System]` ambient instance initialized via `Local.init(live)` [`System.scala:41-42`, `System.scala:96`]; platform I/O is isolated in `SystemPlatformSpecific` [`System.scala:9`].

---

## Extension recipes

### Adding a new Path operation (11 steps)

1. **Add a `Path.Op` case**, choosing read-group (`Tag[PathRead]`) or write-group (`Tag[PathWrite]`) [`Path.scala:130-165`].
2. **Add the abstract method to `Path.Unsafe`** with the most specific `Result[File*Exception, A]` error type [`Path.scala:1569-1570`].
3. **Add the safe-tier extension method** that suspends under the correct capability tag via `ArrowEffect.suspend` [`Path.scala:1323-1332`].
4. **Add the method to `Path.Service[S]`** with umbrella `Abort[FileException]` [`Path.scala:247-248`].
5. **Wire `HostService`** to bridge `Path.Unsafe` through `Sync.Unsafe.defer(Abort.get(...))` with a `// Unsafe:` comment [`HostService.scala:35-37`].
6. **Add a `dispatch` case** so runners route the new `Op` to the active service [`Path.scala:842-843`].
7. **Implement on `NioPathUnsafe`** in `jvm-native/` using `java.nio.file.*` [`jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala:183-194`].
8. **Implement on the Node.js-backed class** in `js-wasm/` using `NodeFs` / `NodePath` facades [`js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala:297-308`].
9. **Implement on `InMemoryService`** (optimistic CAS over immutable tree state) [`InMemoryService.scala:149-170`].
10. **Implement on `OverlayService`** (stage into upper map and append to journal) [`OverlayService.scala:743-753`].
11. **Add cross-platform test leaves** in `shared/src/test/scala/kyo/PathTest.scala` [`PathTest.scala:676-685`].

Do not lift safe-tier extension methods with `Sync.Unsafe.defer(Abort.get(...))` directly; that is the pre-capability model. Safe-tier Path methods suspend under `ArrowEffect`.

### Adding a new FileException leaf

1. Extend `FileException`; put the human-readable message in the leaf constructor; mix in exactly the marker traits whose operations can raise it [`FileException.scala:12-13`, `FileException.scala:62-64`].
2. Update exhaustive match tests in `FileExceptionTest.scala` so the compiler catches missing subtypes [`FileExceptionTest.scala:22-24`].
3. Reserve `FileIOException` for low-level I/O not covered by more specific subtypes [`FileException.scala:93-94`].

### Adding a new Path.Service backend

1. Implement `Path.Service[S]`; declare the correct `Disposition` (`AutoCommit` for immediate backends, `ManualCommit` for staged backends) [`Path.scala:215-216`, `InMemoryService.scala:53-55`, `OverlayService.scala:374`].
2. Methods take no `(using Frame)`; capture frame at construction [`Path.scala:206-207`].
3. Every method returns `A < (S & Abort[FileException])` with the umbrella error row.
4. For host-backed variants: bridge each op to `Path.Unsafe` inside `Sync.Unsafe.defer(Abort.get(...))` with `// Unsafe:` comments; register a factory on `Path.Service` [`Path.scala:288`, `HostService.scala:13-14`].
5. For in-memory backends: immutable state behind `AtomicRef` with optimistic CAS; expose via `Path.Service.inMemory` [`Path.scala:298-302`, `InMemoryService.scala:53-55`].
6. For copy-on-write overlays: wrap lower in `OverlayService.init`, scope-register state for auto-rollback, return `Path.Service.Overlay[S]` [`OverlayService.scala:44-51`, `Path.scala:304-306`].
7. Test with `Path.runWith(service)(...)` [`InMemoryServiceTest.scala:7-10`, `InMemoryServiceTest.scala:12-17`].

### Resolving overlay commit conflicts with commitWith

1. Seed lower, read through overlay to stamp read-set, stage writes, diverge lower [`OverlayServiceCommitTest.scala:168-198`].
2. Call `commitWith` with a per-conflict `resolve` function returning a `Resolution` [`Path.scala:960-961`].
3. Apply resolution per path:
   - `KeepOurs`: replay overlay's staged entry, discarding live lower value [`CommitConflict.scala:39-40`].
   - `KeepTheirs`: skip path in replay, keeping live lower unchanged [`CommitConflict.scala:40-41`].
   - `Write(entry)`: replace both staged and live with supplied `Path.Entry` [`CommitConflict.scala:41-42`].
   - `Remove`: delete path in live lower during replay [`CommitConflict.scala:42-43`].
4. Inside `OverlayService.commitWith`, each `Resolution` case rebuilds upper state and journal before replay [`OverlayService.scala:1372-1404`].

---

## Testing

kyo-system uses `.withKyoTest` (kyo test runner classpath and `kyo.test.runner.SbtFramework` on JVM) with no module-specific `fork` or `parallelExecution` overrides [`../build.sbt:695-705`, `../project/WithKyoTest.scala:37-45`]. For the test framework base class, compile-time test helpers, and non-blocking concurrency rules, defer to the root [CONTRIBUTING.md](../CONTRIBUTING.md).

### Test file naming and aspect splits

Every test file shares a name prefix with a source file. When one source needs multiple test files, split by aspect keeping the source as prefix:

| Source | Base test | Aspect tests |
|---|---|---|
| `Path.scala` (capability model) | `PathTest.scala` | `PathCapabilityTest.scala` (compile-time row laws), `PathTransactionTest.scala` (transaction/sandbox/virtual combinators) |
| `Path.scala` (confinement) | `PathConfinementTest.scala` (cross-platform) | `PathConfinementJvmTest.scala` (symlink escape, JVM-only) |
| `InMemoryService.scala` | `InMemoryServiceTest.scala` | (1:1) |
| `OverlayService.scala` | `OverlayServiceTest.scala` (base COW semantics) | `OverlayServiceCommitTest.scala` (conflict/commit machinery), `OverlayServiceRecoveryTest.scala` (crash recovery) |
| `CommitConflict.scala` | `CommitConflictTest.scala` (value-type round trips) | (1:1) |

Create a new `{Source}{Aspect}Test` when the base `*Test.scala` would grow unwieldy; fold single-concern tests into the base file otherwise.

### Capability law tests

`PathCapabilityTest` proves compile-time capability rows via top-level `val` ascriptions and `typeCheckErrors` [`PathCapabilityTest.scala:11-17`, `PathCapabilityTest.scala:29-38`]. Negative capability law tests assert the compiler error mentions `PathWrite` [`PathCapabilityTest.scala:29-38`]. Row-guard `val` ascriptions lock streaming, walk, `tempDir`, `tail`, and sink method rows [`PathCapabilityTest.scala:41-57`]. Overlay combinator rows prove `Sync` must not appear in the residual [`PathCapabilityTest.scala:19-27`]. Runtime checks exercise host service via `Path.run` inside `Scope.run`, asserting concrete values and exception subtypes [`PathCapabilityTest.scala:59-79`].

### Overlay and transaction tests

Transaction combinator tests use `Path.Service.inMemory` and `Path.runWith(lower)` for deterministic lower inspection; no `Thread.sleep` anywhere [`PathTransactionTest.scala:5-9`]. `PathTransactionTest` locks the public sandbox row positively and proves transaction adds `Abort[CommitConflict]` via `typeCheckErrors` [`PathTransactionTest.scala:90-106`].

Overlay tests build via `Path.Service.inMemory` then `Path.Service.overlay(lower)`, exposing both overlay and lower [`OverlayServiceTest.scala:7-17`]. Copy-on-write semantics: write through `Path.runWith(ov)`, inspect lower via `Path.runWith(lower)` before commit [`OverlayServiceTest.scala:163-176`]. Stamp correctness avoids mtime flakiness by forcing `File→Absent` conflicts via lower removal, not re-write [`OverlayServiceTest.scala:208-212`].

`OverlayServiceCommitTest` creates conflicts by observing through overlay, staging writes, then mutating lower out-of-band with different-size content [`OverlayServiceCommitTest.scala:5-12`]. `commitOverwrite` must not carry `Abort[CommitConflict]` in its effect row; the test proves this by calling it without `Abort.run[CommitConflict]` [`OverlayServiceCommitTest.scala:115-127`]. `WriteOpLog.decode` failure modes are tested as pure `Result` matches without a service fixture [`OverlayServiceCommitTest.scala:305-338`].

### Recovery tests

`OverlayServiceRecoveryTest` injects crashes via `private[kyo]` hooks on `OverlayService` (default no-ops) [`OverlayService.scala:382-393`]. Recovery tests distinguish intentional hook crashes from real file errors using a `SyntheticCrash` exception type and `attemptCrash` [`OverlayServiceRecoveryTest.scala:28-50`]. Every crash point has paired `"in-memory:"` and `"host:"` test leaves in `shared/src/test` [`OverlayServiceRecoveryTest.scala:7-11`]. Host recovery tests use `Path.Service.host(root)` with a scoped `tempDir` as the rooted lower [`OverlayServiceRecoveryTest.scala:65-82`].

### In-memory service tests

`InMemoryServiceTest` obtains a fresh service per test via `Path.Service.inMemory.map` [`InMemoryServiceTest.scala:7-10`]. Service-level `Abort` from `Path.runWith` propagates outside the inner scope; `Abort.run` must wrap `Path.runWith`, not sit inside it [`InMemoryServiceTest.scala:95-100`]. Concurrency tests synchronize fibers with `Latch.init`/`gate.release` instead of sleeps [`InMemoryServiceTest.scala:143-156`].

### Deterministic timing

Overlay and transaction test suites default to in-memory lowers for cross-platform determinism [`PathTransactionTest.scala:9`]. Never use blocking primitives in tests; use `Async`-based suspension (root [CONTRIBUTING.md](../CONTRIBUTING.md)).

---

## Decision checklist

Run through this list before submitting a kyo-system change.

1. **New Path operation.** Is there a `Path.Op` case (read-group or write-group)? Is the abstract method on `Path.Unsafe` with `(using AllowUnsafe, Frame)` and the most specific `Result[File*Exception, A]`? Is there a safe-tier extension method that suspends via `ArrowEffect.suspend` (not `Sync.Unsafe.defer`)? Is there a `Path.Service[S]` method and a `dispatch` case? Is `HostService` wired with `Sync.Unsafe.defer(Abort.get(...))` and a `// Unsafe:` comment? Are there concrete implementations in `jvm-native/` and `js-wasm/`, plus `InMemoryService` and `OverlayService`? Is there a cross-platform test leaf in `shared/src/test`?

2. **New streaming Path operation.** Does it return a `Stream` that carries `Scope`? Is the OS handle acquired with `Scope.acquireRelease`? Is `tail` the only method that adds `Async`?

3. **New FileException leaf.** Does it mix in exactly the marker traits whose operations can actually raise it? Is its message string on the leaf itself? Is `FileIOException` used only when no more specific variant exists?

4. **New Path.Service backend.** Does it implement `Path.Service[S]` with the correct `Disposition`? Are methods frame-free (frame captured at construction)? Does every method return `A < (S & Abort[FileException])`? For host backends, is each op bridged through `Sync.Unsafe.defer` with `// Unsafe:` comments? Is there a factory registered on `Path.Service`? Are there tests via `Path.runWith(service)(...)`?

5. **Overlay commitWith resolution handler.** Does the resolve function return a `Resolution` per conflict? Are all four cases (`KeepOurs`, `KeepTheirs`, `Write`, `Remove`) handled where needed? Do non-conflicting staged entries still replay regardless of per-conflict choices?

6. **Runner choice (negative capability law).** Does a write op appear anywhere in the program? If yes, are you using `Path.run` or `runWith`, not `runReadOnly`? Does the ascribed residual match the discharged capability?

7. **Overlay combinator usage.** Does the combinator run inside an ambient `PathWrite` scope? For `virtual`, will commit/rollback run before `PathWrite` is discharged? Is the return row ascribed without `Sync` (`PathWrite & S`, transaction adds `Abort[CommitConflict]`, sandbox does not)?

8. **Unsafe tier change.** Is every new `Unsafe` abstract method matched by a safe-tier lift on all four capabilities that need it? Are platform stubs present in every platform leaf?

9. **Production unsafe bridging.** Is every production `Unsafe` call wrapped in `Sync.Unsafe.defer` with a `// Unsafe:` comment? Is `embrace.danger` absent from main sources?

10. **New dependency from kyo-system.** kyo-system depends on `kyo-core` only [`../build.sbt:695-699`]. No outbound edges to other kyo modules.

11. **New test.** Does it extend `kyo.test.Test[Any]`? Does it assert concrete values? Does it live in `shared/src/test` unless it genuinely requires a JVM-only API? Is it folded into the matching `*Test.scala` for the source it covers (or a justified `{Source}{Aspect}Test` split)? Does it include at least one edge case?
