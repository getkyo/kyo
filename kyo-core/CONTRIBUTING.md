# kyo-core contributor guide

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-core`. Read the root `CONTRIBUTING.md` first; everything there
applies here, and this file extends it with module-local rules.

File and OS-signal capabilities live in `kyo-system`; see `kyo-system/CONTRIBUTING.md` for those conventions.

---

## Architecture overview

`kyo-core` is the primary effect module. It provides:

- **Concurrency**: `Async`, `Fiber`, `Channel`, `Queue`, `Hub`, `Latch`, `Barrier`, `Meter`, `Gate`, `Exchange`
- **Time and scheduling**: `Clock`, `Duration`, `Deadline`, `Retry`, `Timeout`
- **Streams**: `StreamCoreExtensions` (core-specific combinators; `Stream`, `Pipe`, and `Sink` live in `kyo-prelude`)
- **Atomic primitives**: `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef`, `Adder`
- **Environment and process**: `Console`, `Log`, `Signal` (reactive)
- **Application entry**: `KyoApp`, `KyoAppInterrupts`
- **Resource management**: `Scope`, `Sync`, `Sync.Unsafe`
- **Observability**: `Stat`

Every API in this module is cross-platform (JVM, Scala.js / Node, Scala Native)
unless it is in a `jvm/`, `jvm-native/`, `native/`, or `js-wasm/` source tree and
explicitly documented as platform-specific.

---

## Kyo primitives mandate

Use Kyo types throughout `kyo-core`:

| Use this   | Not this             |
|------------|----------------------|
| `Maybe`    | `Option`             |
| `Result`   | `Either` / `Try`     |
| `Chunk`    | `List` / `Seq`       |
| `Span`     | `Array` (public ADT) |

---

## Safe-by-default tier

Every public API is in the safe tier. The unsafe tier (`Sync.Unsafe`)
exists for integrators and performance-critical bridging only.
Every site that calls `AllowUnsafe` or `Sync.Unsafe.defer` must have a
`// Unsafe:` comment explaining which safe-tier contract it is bridging.

---

## Concurrency model

### Async vs Sync in the effect row

`Sync` (`Sync.scala:23`) is a type-level marker meaning "this computation has side
effects but runs to completion without parking or locking." `Async`
(`Async.scala:47`) extends `Sync` and means "this computation may park the current
fiber or involve the scheduler." Every method that can suspend a fiber must declare
`Async` in its pending effects. A method with only `Sync` in its row is guaranteed
to run to completion without scheduler involvement and without any possibility of
fiber requeuing.

This distinction is useful for reasoning about performance. A call chain showing
only `Sync` effects is free of scheduler interactions. Once `Async` appears, the
current fiber may park and another may run.

### The no-blocking rule in kyo-core

No kyo-core code may block a thread. Forbidden in non-stub shared or platform code:
`Thread.sleep`, `synchronized`, `Future.await`, `CountDownLatch.await`, or any
other blocking primitive. Use Async suspension instead:

- Wait for channel space: `Channel.put` / `Channel.take`
- Wait for a count to reach zero: `Latch.await`
- Wait for elapsed time: `Clock.sleep`
- Join a forked fiber: `Fiber.get`
- Synchronize a group of fibers: `Gate.enter` / `Barrier.await`

The js-wasm stub for `LockSupport` (`js-wasm/src/main/scala/kyo/AsyncStubs.scala`)
throws `UnsupportedOperationException` on any `park` or `unpark` call. That throw
is the enforcement mechanism: any accidental call to a blocking primitive that
reaches JS will fail loudly at runtime rather than silently no-op.

### Adding a new concurrent primitive

Follow the four-layer pattern used by `Channel`, `Queue`, and `Latch`.

**Layer 1: opaque type aliased to Unsafe.** The public type is the safe surface and
the underlying value is the Unsafe object:

```scala
opaque type Foo[A] = Foo.Unsafe[A]   // Channel.scala:58
```

**Layer 2: safe-tier extension block.** Each public operation delegates into Unsafe
via `Sync.Unsafe.defer`. Non-suspending operations use `Abort.get(self.method())`
to surface typed failures; suspending operations use `self.fooFiber().safe.get` to
convert `Fiber.Unsafe` to `Fiber` and park the current fiber:

```scala
def offer(v: A)(using Frame): Boolean < (Abort[Closed] & Sync) =
    Sync.Unsafe.defer(Abort.get(self.offer(v)))

def put(v: A)(using Frame): Unit < (Abort[Closed] & Async) =
    Sync.Unsafe.defer(self.putFiber(v).safe.get)
```

**Layer 3: Unsafe tier.** A `sealed abstract class Unsafe[A]` whose methods take
`(using AllowUnsafe)` and return bare values or `Fiber.Unsafe` for suspending
operations. Include `def safe: Foo[A] = this` so the Unsafe object is addressable
as the safe opaque type. Prefix the class and its companion with the standard
warning comment (`Channel.scala:391-392`, `Latch.scala:72`):

```scala
/** WARNING: Low-level API meant for integrations, libraries, and
  * performance-sensitive code. See AllowUnsafe for more details. */
sealed abstract class Unsafe[A] extends Serializable:
    def safe: Foo[A] = this
```

**Layer 4: init pattern.** Provide `init` and `initWith`; `init` delegates to
`initWith(identity)`. `initWith` is `inline`, constructs the Unsafe object inside
`Sync.Unsafe.defer`, and registers cleanup with `Scope.ensure`. Also provide
`initUnscoped` and `initUnscopedWith` for callers that manage lifecycle manually.
See `Channel.scala:325-389` for the full pattern. The Scope-managed `init` is the
default: users reach for it first and it must never leak resources.

`Hub` is a `final class` rather than an opaque type (`Hub.scala:22-26`) because it
aggregates a `Channel` plus a long-running broadcast `Fiber`. Use a `final class`
when the primitive holds composite state that does not map cleanly to a single Unsafe
object.

### The `kyo.async.concurrency.default` knob

`Async.defaultConcurrency` (`Async.scala:84-91`) caps the number of fibers that
collection operations (`Async.foreach`, `Async.collect`, and their variants) will
fork. The default is `Runtime.availableProcessors() * 2`. It can be overridden
per-call or globally via the `kyo.async.concurrency.default` JVM system property.

The property is read once at module initialization. A malformed value throws
`IllegalArgumentException` immediately with a message naming the property and the
offending string (`Async.scala:75-82`). A misconfigured knob should fail loud at
startup rather than silently fall back to the default.

`kyo.System` lives in `kyo-system`, which depends on `kyo-core`, so the init-time
read uses `java.lang.System.getProperty` directly (`Async.scala:87-88`). This is the
only sanctioned place in kyo-core where `java.lang.System` appears instead of
`kyo.System`. The `// Unsafe:` comment at that line names the reason.

---

## Platform-split discipline

Source defaults to `shared/src`. Use a platform-specific tree only when a JVM,
Native, or JS library primitive has no cross-platform Kyo wrapper.

### What lives where

**`jvm-native/`** (JVM and Native share, JS diverges):
- `AsyncPlatformSpecific`: `fromCompletionStage` and `fromCompletableFuture`
  bridging Java's `CompletionStage`/`CompletableFuture`; absent on JS.
- `KyoAppPlatformSpecific`: OS-level exit hook (`exit(code)`) via
  `KyoAppRunnerWithInterrupts`.
- `LogPlatformSpecific`: platform-specific log backend wiring.

**`jvm/`** (JVM only):
- `OsSignalPlatformSpecific`: installs signal handlers via `sun.misc.Signal` through
  reflection, with a graceful `Handler.Noop` fallback when the class is absent
  (`jvm/src/main/scala/kyo/internal/OSSignalPlatformSpecific.scala:18-34`).
- `IOPromisePlatformSpecific`: JVM-specific promise scheduling.
- `StreamCompression`: deflate/inflate via `java.util.zip`, which has no equivalent
  in Scala Native or JS standard libraries.

**`native/`** (Scala Native only):
- `OsSignalPlatformSpecific`: installs handlers via POSIX `signal()` using
  `scala.scalanative.posix.signal`.
- `hubsStubs.scala`: stub for `CopyOnWriteArraySet`, a Java class absent on Native.

**`js-wasm/`** (JS and WASM):
- `AsyncPlatformSpecific`: empty trait; `CompletionStage` does not exist on JS.
- `AsyncStubs.scala`: `LockSupport` stub that throws `UnsupportedOperationException`
  on any call; parking has no meaning on a single-threaded platform.
- `OsSignalPlatformSpecific`: `Handler.Noop`; JS has no OS signals.
- `hubsStubs.scala`: `CopyOnWriteArraySet` stub backed by `HashSet`.
- `addersStubs.scala`, `timersSubs.scala`: stubs for absent JVM classes.
- `VarHandle`, `JSServiceLoaderRegistry`, `ServiceLoader`: JS-specific
  implementations for services and memory-model primitives.

### The OsSignal pattern as a template

`OsSignal` (`shared/src/main/scala/kyo/internal/OSSignal.scala`) defines the
abstract shape and the `Handler.Noop` fallback. Three platform leaves implement
`OsSignalPlatformSpecific`: JVM uses `sun.misc.Signal` via reflection with a `Noop`
fallback on missing classes, Native uses POSIX signals, JS-WASM is `Noop`. New OS
capabilities should follow this same three-leaf pattern.

### Rule for placing new code

Place new code in `shared/` unless a specific external library or OS primitive is
required. Use `jvm-native/` when JVM and Native share behavior that JS cannot
express. Use `jvm/` or `native/` only when the behavior is exclusive to one
platform. Never move a test into a platform-specific tree to avoid a cross-platform
failure; fix the failure instead.

---

## Test patterns

### Test base class

All kyo-core tests extend `kyo.test.Test[Any]`, not ScalaTest directly:

```scala
class ChannelTest extends kyo.test.Test[Any]:
```

`kyo.test.Test` is provided by the `kyo-test` module. Do not mix in raw ScalaTest
traits.

### Deterministic concurrency testing

Never use `Thread.sleep` as a readiness witness in a test. Use Kyo's own
synchronization primitives to drive deterministic rendezvous:

- `Latch.init(n)` and `latch.await` to wait for n fibers to reach a point.
- `Channel.put` / `Channel.take` to pass a signal between fibers without real time.
- `Clock.run(...)` to control virtual time in tests for `Clock.sleep` or
  `Retry` backoff scenarios.
- `Fiber.get` to join a forked computation before asserting its result.

A test that depends on real elapsed time to avoid a race is a flaky test. Drive
events with latches and channels; let virtual clocks advance time.

### The initWith pattern in tests

Prefer the `initWith` and `use` factory variants over `init` followed by manual
`close`. The `initWith` variants register cleanup automatically via `Scope.ensure`
and keep the test body clean:

```scala
Channel.initWith[Int](10) { c =>
    c.put(1).andThen(c.take)
}
```

Use `initUnscoped` or `initUnscopedWith` only when the test explicitly exercises
post-close behavior and must observe that the channel is still open after the block.

### Where JVM-only tests may live

A test may go in `jvm/src/test/scala/kyo/` only when it tests genuinely JVM-only
behavior. Current examples: `StreamCompressionTest.scala` (tests `StreamCompression`
which wraps `java.util.zip`) and `GateJvmTest.scala` (JVM-specific scheduler
behavior). Every other test belongs in `shared/src/test/scala/kyo/` and must pass
on JVM, Scala.js, and Scala Native.

---

## Pre-submission checklist (kyo-core-specific)

- [ ] New concurrent primitives follow the four-layer pattern: opaque type, safe-tier extension block, `sealed abstract class Unsafe`, and `init`/`initWith`.
- [ ] Every `Sync.Unsafe.defer` bridging site carries a `// Unsafe:` comment.
- [ ] New platform-specific code is in the narrowest tree that fits (`shared/` first, then `jvm-native/`, then `jvm/`, `native/`, or `js-wasm/`).
- [ ] No `Thread.sleep`, `synchronized`, or blocking primitive in non-stub code.
- [ ] Tests extend `kyo.test.Test`, not raw ScalaTest.
- [ ] Concurrency tests use `Latch`, `Channel`, or `Clock` for determinism, not real-time sleeps.
- [ ] Any change to `Async.defaultConcurrency` or the `kyo.async.concurrency.default` path preserves the loud-failure behavior for malformed values (`Async.scala:75-82`).
