# Contributing to kyo-ffi

Module-specific guide for kyo-ffi. Read the repository-root
[CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming
rules, type vocabulary, test patterns, and the unsafe-boundary tiers that apply
across all of Kyo. This document records only what is specific to kyo-ffi: why the
whole module is the unsafe tier, its throwing error model, the thread-blocking
substrate beneath `@Ffi.blocking`, its cross-platform layout, and its test
pattern.

## What kyo-ffi is

kyo-ffi is the low-level foreign-function-interface layer: it turns a Scala trait
extending `Ffi` into platform-specific native bindings (JVM Panama, Scala Native
`@extern`, JS koffi) generated at build time. It sits at the Applications layer of
the stack and is the substrate kyo-net and other native integrations build on. The
binding surface is deliberately the unsafe tier: methods carry `(using
AllowUnsafe)`, return bare values (or `Fiber.Unsafe` for `@Ffi.blocking`), and
surface failure by throwing. The caller bridges into a `< (Async & Abort)`
computation itself.

## The unsafe-only surface

The FFI binding layer IS the unsafe tier. `Buffer`, `StructLayout`, and the
generated `{Trait}Impl` classes perform raw off-heap memory access against pinned
Panama segments, malloc'd Native pointers, and JS typed arrays. These are the
canonical bridging boundary the root guide sanctions for `AllowUnsafe`: there is no
safe-tier wrapper because the operation IS the boundary.

Each `AllowUnsafe.embrace.danger` import carries an `// Unsafe:` comment stating
the specific bridge it opens (the raw read/write, the arena allocation, the mmap).
The hot path (`Buffer.get`/`set`, the non-generic `getLong`/`setLong`,
`getInt`/`setInt`, `getShort`/`setShort`, `getDouble`/`setDouble`,
`getFloat`/`setFloat`, and `getByte`/`setByte` accessor pairs, the `StructLayout`
field accessors) is gated by cheap pre-checks (`checkOpen`, `checkIndex`) BEFORE
the unsafe access; the comment is zero-cost and the generated instruction stream
is unchanged.

Typed-failure bridging happens at the user's call site, not inside the module: a
binding call that may fail is wrapped in `Abort.catching[FfiLoadError]` (or the
relevant subtype) to lift the thrown error into the effect row.

## Error model

kyo-ffi surfaces failure by THROWING, not by returning a typed `Abort`/`Result`.
This is a deliberate, ratified deviation from kyo's totality convention:

- `FfiLoadError` is a `sealed abstract class ... extends RuntimeException` with
  `LibraryNotFound` / `AbiMismatch` / `Unsupported` / `ImplNotFound` leaves.
  `Ffi.load[T]` throws a subtype on a documented load failure.
- `Buffer.get`/`set` on a closed buffer throw `IllegalStateException`; an
  out-of-range index throws `IndexOutOfBoundsException`. These mirror
  `scala.Array`/JDK semantics so the hot path stays zero-overhead: a `Result`
  return would allocate on every element access.

Rationale: the throwing model keeps `get`/`set` allocation-free and
branch-predictable on the performance-critical path. Converting it to `Abort[E]`
would put an allocation and a row-widening on every off-heap access. The boundary
where typed failure resumes is the user's call site: bridge with
`Abort.catching` / `Abort.get`.

Honesty note (visibility ladder): `Ffi.load` also lets a
`java.lang.IllegalStateException` ESCAPE uncaught on the JVM when the generated
impl class lacks a public nullary constructor (`computeIfAbsent` propagates the ISE
thrown inside `FfiReflect.instantiate`; `FfiReflectCore.instantiate` wraps only the
class-not-found `None` case into `ImplNotFound`). The `Ffi.load` `@throws` scaladoc
and README name BOTH `FfiLoadError` and `java.lang.IllegalStateException`.

## Thread-blocking substrate

The no-blocking rule bans thread parking by semantic intent. kyo-ffi has a SMALL,
NAMED set of sanctioned blocking primitives, each on the OS-thread / carrier-thread
substrate BENEATH the fiber layer, never on a scheduler-managed fiber:

1. **The `@Ffi.blocking` carrier-thread park.** A `@Ffi.blocking` C downcall runs
   synchronously on (and parks) the carrier thread on JVM and Native; the
   scheduler's blocking monitor recognises the parked carrier and drains its queue,
   so no fiber starves. This is the design floor, equivalent to kyo-net's bounded
   kernel readiness wait. Do not replace it with a non-parking alternative.
2. **`GuardDrainSupport.parkNanos`** (`jvm-native`, `LockSupport.parkNanos`) is the
   guard-drain wait: `GuardCore.drainInFlight` spins `SpinBudget` iterations via
   `onSpinWait`, then parks in 1ms quanta until in-flight retained callbacks drain
   or the timeout elapses. This runs on the closing thread, not a fiber.
3. **`NativeLoader.synchronized`** (jvm + native) guards a one-shot
   `platformChecked` init latch, double-checked. It is module-level init, not
   fiber coordination; the cost is one monitor enter once per process.
4. **`GuardRegistry`** (jvm) wraps a `WeakHashMap`-backed set in
   `Collections.synchronizedSet`: `WeakHashMap` is not thread-safe and the registry
   is the precondition for the `Cleaner`-based leak detector. The cost is one
   monitor enter per open/close, negligible next to the Panama arena allocate it
   bookends.
5. **`NativeLeakDetector`** (native) uses `slotsBuf.synchronized` for slot
   bookkeeping (a future multi-threaded Scala Native could race retained-claim
   calls) and a daemon `Thread.sleep(SweepIntervalMs)` sweep loop. Scala Native 0.5
   lacks `ReferenceQueue` integration, so the sweep is the only available
   leak-detection mechanism; it runs on a dedicated daemon thread, never a fiber.

Each site carries a rationale comment at the call site tying it to this substrate.
A change to any of these primitives updates this section.

## Cross-platform layout

Source defaults to `shared/src`. A `jvm/`, `js-wasm/`, `native/`, or `jvm-native/`
leaf is used only when a platform primitive has no cross-platform Kyo wrapper:

- `jvm/`: Panama (`MemorySegment`, `Arena`), `java.lang.ref.Cleaner` leak
  detection, JVM reflection (`FfiReflect`).
- `native/`: Scala Native pointers, the sweep-based `NativeLeakDetector`.
- `js-wasm/`: koffi async dispatch, `Uint8Array` buffers, the `node:fs` mmap
  facade; the no-op `GuardDrainSupport` (single-threaded, nothing to park). Shared
  by the JS and Wasm backends, both linked as `ModuleKind.ESModule` (the wasm
  backend forces it; the js backend selects it to match, so `require` is absent on
  both and the browser gate behaves identically). The koffi and node-builtin facades
  are `@JSImport` module ids (`koffi`, `node:fs`) rather than a
  `js.Dynamic.global.require`, which has no `require` global under ESM. koffi is
  imported as a DEFAULT import, not a namespace import: a namespace import of the
  CommonJS koffi addon yields an empty binding under Node's ESM interop. The same
  facades also link under a CommonJS consumer (kyo-stats-machine's js axis), where
  a default import of a CommonJS module likewise binds `module.exports`.
- `jvm-native/`: JVM and Native SHARE, JS/Wasm diverge. `GuardDrainSupport`/
  `BlockingBridge` use `LockSupport.parkNanos` / carrier-thread parking, present on
  JVM and Native but absent on JS/Wasm. This is established kyo precedent (kyo-core,
  kyo-data, kyo-net, kyo-scheduler all use `jvm-native/`).

## Config is a compile-time TASTy literal

`Ffi.Config` fields are read at BUILD time by the code generator's `FfiInspector`,
which extracts the super-constructor arguments from a binding companion's TASTy.
The matchers are STRUCTURAL (they wildcard the factory selector and unwrap the
varargs `Repeated` shape), so they read kyo's opaque collection literals directly:

- `scratchSize: Maybe[Int] = Absent` (kyo idiom, `Maybe` over `Option`).
  `Present(n)` reads as `Apply(_, List(Literal(IntConstant)))` via the existing
  integer extractor; the absent literal `Absent` reads as `Ident("Absent")` /
  `Select(_, "Absent")`.
- `headers: Chunk[String] = Chunk.empty` (kyo idiom, `Chunk` over `Seq`).
  `Chunk("a", "b")` reads as the same `Apply(_, args) + Typed(Repeated(...))` shape
  the string-sequence extractor already unwraps.
- `symbols: Map[String, String]` and `packedStructs: Set[String]` STAY `Map`/`Set`:
  these are the correct kyo types for an unordered key-value map and a membership
  set, and no purer opaque kyo type exists. They are the idiomatic end state.

The codegen's internal spec model (`ConfigSpec`, `TraitSpec`) may keep stdlib
`Option`/`Seq`: it is private codegen, decoupled from the public surface.

After editing a binding trait or `Ffi.Config` field, run a full `ffiClean`: the
FFI codegen cache keys generated `{Trait}Impl.scala` on source hash and serves
stale output otherwise.

## Tests

The guard / native-loader / leak-detector concurrency suite is THREAD-LEVEL: the
SUT is OS-thread locking, daemon sweeps, and errno thread-locality, not
fiber coordination. So `CountDownLatch` / `CyclicBarrier` for a deterministic
thread-level rendezvous is valid and SANCTIONED in this suite (it observes a real
thread event). What is NOT allowed is a fixed `Thread.sleep` standing in for a
readiness or parking WITNESS ("the closer reached the drain park", "the daemon
swept", "the race window opened"): replace it with an event latch or a bounded
thread-state poll (`isParked` = `WAITING || TIMED_WAITING`) that observes the genuine
event. A `Thread.sleep` that REMAINS is the elapsed duration the SUT itself
measures (a brief simulated callback body inside a stress test), documented as
such.

## Pre-submission checklist (kyo-ffi-specific)

- [ ] Every `AllowUnsafe.embrace.danger` import has an `// Unsafe:` comment.
- [ ] No new typed-failure conversion of the throwing error model; new failure
      paths throw an `FfiLoadError` subtype.
- [ ] Any new blocking primitive is on the carrier/OS-thread substrate, carries a
      site rationale, and is added to "Thread-blocking substrate" above.
- [ ] No fixed `Thread.sleep` as a readiness witness in a new test.
- [ ] After a binding/Config edit, a full `ffiClean` was run before validating.
- [ ] No phase/campaign codes or change-relative wording in any comment.
