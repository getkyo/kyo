# kyo-core contributor guide

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-core`. Read the root `CONTRIBUTING.md` first; everything there
applies here, and this file extends it with module-local rules.

File, process, environment, and OS capabilities live in `kyo-system`; see `kyo-system/CONTRIBUTING.md` for those conventions.

---

## Architecture overview

`kyo-core` is the runtime effect module. It provides:

- **Concurrency**: `Async`, `Fiber`, `Promise`, `Channel`, `Queue`, `Hub`, `Latch`, `Gate`, `Meter`, `Admission`, `Exchange`, `Signal`
- **Time and scheduling**: `Clock` (with `Clock.Deadline` and `Stopwatch`), `Retry`, and the `Timeout` error; `Duration` itself is in `kyo-data`
- **Streams**: `StreamCoreExtensions` (the stream operators that need fibers: parallel mapping, merging, broadcast, `groupedWithin`); `Stream`, `Pipe`, and `Sink` live in `kyo-prelude`
- **Shared state**: `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef`, `LongAdder`, `DoubleAdder`, `Cache`
- **Ambient services**: `Console`, `Log`, `Random`, `SecureRandom`, `UUIDGenerator`
- **Application entry**: `KyoApp`
- **Resource management**: `Scope`, `Sync`, `Sync.Unsafe`
- **Observability**: `Stat`

Every API in this module is cross-platform (JVM, Scala.js, Wasm, Scala Native) unless it lives in a
`jvm/`, `jvm-native/`, `native/`, or `js-wasm/` source tree. There is no separate `js/` tree:
`js-wasm/` serves both JavaScript and Wasm.

---

## Kyo primitives mandate

Use Kyo types throughout `kyo-core`:

| Use this   | Not this             |
|------------|----------------------|
| `Maybe`    | `Option`             |
| `Result`   | `Either` / `Try`     |
| `Chunk`    | `List` / `Seq`       |
| `Span`     | `Array` (public ADT) |

Raw `java.util.Arrays.copyOf` / `java.lang.System.arraycopy` are permitted
inside performance-critical private implementation paths, because `Chunk` does not
expose a fast-arraycopy path for partial buffer slices.

---

## Safe-by-default tier

Every public API is in the safe tier. The unsafe tier (`*.Unsafe`) exists for
integrators and performance-critical bridging.

Safe-tier methods delegate into their `Unsafe` counterpart through `Sync.Unsafe.defer`;
that is the standard step and needs no comment. A `// Unsafe:` comment is required
where code steps outside that pattern: an `import AllowUnsafe.embrace.danger`, or a
bridge that evaluates or completes something the safe tier cannot express. The comment
names which safe-tier contract the site is bridging. kyo-core itself uses
`AllowUnsafe.embrace.danger` in its runtime internals (the `Scope` finalizer, the
scheduler's `Finalizers`), and each such site carries the marker.

---

## Concurrency model

### Async vs Sync in the effect row

`Sync` is a type-level marker meaning "this computation has side effects and the
current fiber runs it to completion without parking." `Async` (`Async.scala`) is
`Async.Join & Sync`: it means the current fiber may park.

A `Sync`-only row does not mean the scheduler is untouched. `Fiber.init` and
`Fiber.initUnscoped` return a `Sync` row and schedule a new fiber. What `Sync`
guarantees is that the calling fiber itself does not suspend. `Fiber.block` is the
one sanctioned exception: a `Sync` operation that parks the calling thread, for
bridging into code that must have a value synchronously.

### The no-blocking rule in kyo-core

Code reachable from a fiber must not block a thread. Forbidden on those paths:
`Thread.sleep`, `synchronized`, `Future.await`, `CountDownLatch.await`, or any
other blocking primitive. Use Async suspension instead:

- Wait for channel space: `Channel.put` / `Channel.take`
- Wait for a count to reach zero: `Latch.await`
- Wait for elapsed time: `Clock.sleep`
- Join a forked fiber: `Fiber.get`
- Synchronize a group of fibers: `Gate.pass`

A few sites outside fiber paths hold a short lock or wait with a timeout, each for a
reason a fiber primitive cannot serve: the time-control queue in `Clock`, the
diagnostics registries, OS-signal callbacks on Native, and the log drain at JVM
shutdown. A new one needs the same justification in a comment at the site.

`IOPromise.block` parks the calling thread through `LockSupport.park`; it is what
`Fiber.block` runs on. The js-wasm `LockSupport` stub (`js-wasm/src/main/scala/kyo/AsyncStubs.scala`)
exists so that shared code links on JS and Wasm, and it throws when `block` is reached
there, since a single-threaded platform cannot park the one thread it has.

### Adding a new concurrent primitive

Follow the four-layer pattern used by `Channel`, `Queue`, and `Gate`.

**Layer 1: opaque type aliased to Unsafe.** The public type is the safe surface and
the underlying value is the Unsafe object:

```scala
opaque type Foo[A] = Foo.Unsafe[A]
```

**Layer 2: safe-tier extension block.** Each public operation delegates into Unsafe
via `Sync.Unsafe.defer`. Non-suspending operations use `Abort.get(self.method())`
to surface typed failures. Suspending operations try the non-suspending path first
and fall back to `self.fooFiber().safe.get`, which converts `Fiber.Unsafe` to
`Fiber` and parks the current fiber only when it must:

```scala
def offer(v: A)(using Frame): Boolean < (Abort[Closed] & Sync) =
    Sync.Unsafe.defer(Abort.get(self.offer(v)))

def put(v: A)(using Frame): Unit < (Abort[Closed] & Async) =
    Sync.Unsafe.defer {
        self.offer(v).foldError(
            {
                case true  => ()
                case false => self.putFiber(v).safe.get
            },
            Abort.error
        )
    }
```

**Layer 3: Unsafe tier.** A `sealed abstract class Unsafe[A]` whose methods take
`(using AllowUnsafe)` and return bare values or `Fiber.Unsafe` for suspending
operations. Include `def safe: Foo[A] = this` so the Unsafe object is addressable
as the safe opaque type. Prefix the class and its companion with the standard
warning comment used by `Channel.Unsafe`:

```scala
/** WARNING: Low-level API meant for integrations, libraries, and
  * performance-sensitive code. See AllowUnsafe for more details. */
sealed abstract class Unsafe[A] extends Serializable:
    def safe: Foo[A] = this
```

**Layer 4: init pattern.** Provide `init` and `initWith`; `init` delegates to
`initWith(identity)`. `initWith` is `inline`, constructs the Unsafe object inside
`Sync.Unsafe.defer`, and registers cleanup with `Scope.ensure`. Provide `use` for a
bracket that closes the primitive when a block ends without putting `Scope` in the
row, and `initUnscoped` / `initUnscopedWith` for callers that manage the lifecycle
themselves. `Channel`'s companion is the reference implementation. The Scope-managed
`init` is the default: users reach for it first and it must never leak resources.

`Hub` is a `final class` rather than an opaque type because it owns a `Channel` plus
a long-running broadcast `Fiber`. Use a `final class` when the primitive owns a fiber
or more than one Unsafe object. `Latch` is a `final case class` wrapping its Unsafe
value and has no scoped lifecycle, since a latch holds nothing to release.

### The concurrency default flag

`Async.defaultConcurrency` caps the number of fibers that collection operations
(`Async.foreach`, `Async.collect`, and their variants) fork when the caller passes no
`concurrency`. It is backed by the `StaticFlag` `async.concurrency.default`
(`Async.scala`, from `kyo-config`), which resolves the system property
`kyo.async.concurrency.default`, then the environment variable
`KYO_ASYNC_CONCURRENCY_DEFAULT`, then the default of twice the available processors.
On Scala.js, Wasm, and Scala Native only the environment variable takes effect.

The flag is read once, when `Async` initializes. A malformed or invalid value fails
there with a `kyo-config` flag exception naming the flag, so a misconfigured value
fails loud at startup rather than silently falling back to the default. Keep that
property when changing the path.

---

## Scope and fiber lifecycle

These are the module's load-bearing invariants. The user-facing statement is the
"Resource safety" section of `README.md`; this is how the code upholds it.

**A fiber's result arrives after its finalizers.** An interrupted `IOTask` takes the
interrupt without completing its promise; the promise stays pending while the task
releases what it holds, and completes once that is done (`scheduler/IOTask.scala`,
the `Status` states and `abandon`). Anything that joins a fiber therefore observes it
released. `Fiber.interruptAwait` is the public form of that wait. An interrupt is a
CAS on the task's status word, and the parent's link to a child is registered before
the child is scheduled, so an interrupt cannot miss a child.

**`Scope.run` delivers its result after the drain.** `Scope.run` closes its
`Finalizer` through `Sync.ensure(finalizer.close)`, so the close runs where the kernel
ends the region: in place when the body ends, and once after the last branch under a
handler that resumes more than once. It then waits for the drain before re-raising the
body's result (`Scope.scala`, `run`). The drain runs on a detached fiber behind an
uninterruptible promise, so an interrupt at a caller's wait cannot stop the finalizers
halfway.

**Nesting and forks.** A nested `Scope.run` joins its enclosing scope as a child
(`Finalizer.addChild`), closed before the enclosing scope's own finalizers. Inside a
fork the enclosing scope is seen through `Finalizer.forked`: registrations still land
on it, but a `Scope.run` opened there is a root, since the fork can outlive the scope.
`Fiber.init` gives the fiber a finalizer of its own and registers one release on the
enclosing scope that interrupts the fiber, waits for it, and closes that finalizer.

**Registration on a closed scope.** `Finalizer.ensureUnsafe` on a closed scope logs a
warning, runs the release detached, and throws `Closed`, so the resource is released
rather than leaked and its user learns it has none.

**Unowned acquisitions.** `Scope.runUnowned` (`private[kyo]`) is for `initUnscoped`-style
entry points: it releases only if the acquisition is abandoned before its value is
delivered, and hands a delivered value over with nothing registered.

**Finalizer storage.** The scheduler's `Finalizers` (`scheduler/Finalizers.scala`) holds
none, one, or an `ArrayDeque` of finalizers, so the common case of zero or one allocates
no collection.

---

## Platform-split discipline

Source defaults to `shared/src`. Use a platform tree only when a JVM, Native, or JS
primitive has no cross-platform Kyo wrapper: `jvm-native/` when JVM and Native share
behavior that JS and Wasm cannot express, `jvm/` or `native/` when the behavior is
exclusive to one platform, `js-wasm/` for the JS and Wasm side. Never move a test into
a platform tree to avoid a cross-platform failure; fix the failure instead.

### What lives where

**`jvm-native/`**: `AsyncPlatformSpecific` (`fromCompletionStage` and
`fromCompletableFuture`), `KyoAppPlatformSpecific` and `KyoAppRunnerPlatform` (the OS
exit hook), `LogPlatformSpecific`, `ConsolePlatformSpecific`, and
`scheduler/SchedulerDiagnostics`.

**`jvm/`**: `OSSignalPlatformSpecific` (installs handlers via `sun.misc.Signal`
through reflection, with a `Handler.Noop` fallback when the class is absent),
`StreamCompression` (deflate and gzip via `java.util.zip`), and
`SecureRandomPlatformSpecific`.

**`native/`**: `OSSignalPlatformSpecific` (POSIX `signal()`), stubs for Java classes
absent on Native such as `CopyOnWriteArraySet`, and `SecureRandomPlatformSpecific` with
its `java.security.SecureRandom` shim.

**`js-wasm/`**: `AsyncPlatformSpecific` (empty; `CompletionStage` does not exist
there), `AsyncStubs.scala` (the `LockSupport` stub), `OSSignalPlatformSpecific`
(`Handler.Noop`), `KyoAppPlatformSpecific`, `KyoAppRunnerPlatform`, `LogPlatformSpecific`,
`ConsolePlatformSpecific`, Node-backed console input (`CoreNodeFs`, `NodeLineReader`), `SecureRandomPlatformSpecific` with its
`java.security.SecureRandom` shim, `SchedulerDiagnostics`, and stubs and service-loader
implementations for JVM classes that do not exist on JS.

The scheduler's `IOTaskPlatformSpecific` and `IOPromisePlatformSpecific` exist on every
platform: they supply the atomic access to the task status word and the promise state
field, which each platform implements differently.

### The OsSignal pattern as a template

`OsSignal` (`shared/src/main/scala/kyo/internal/OSSignal.scala`) defines the
abstract shape and the `Handler.Noop` fallback. Three platform leaves implement
`OSSignalPlatformSpecific`: JVM uses `sun.misc.Signal` via reflection with a `Noop`
fallback on missing classes, Native uses POSIX signals, JS-Wasm is `Noop`. New OS
capabilities should follow this same three-leaf pattern.

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

The root guide's "Deterministic Tests" section is the rule: no real-clock assertions,
virtual time through `Clock.withTimeControl`, and rendezvous through `Latch`,
`Channel`, and `Fiber.get` rather than sleeps.

### Primitive lifecycles in tests

Prefer `use` in tests: it closes the primitive when the block ends and leaves no
`Scope` in the row. `init` and `initWith` register the close on the enclosing scope,
so a test using them runs under `Scope.run`.

```scala
Channel.use[Int](10) { c =>
    c.put(1).andThen(c.take)
}
```

Use `initUnscoped` or `initUnscopedWith` when the test itself owns the lifecycle,
for example to close the primitive at a chosen point and assert on what follows.

### Where platform-specific tests may live

A test goes in a platform tree only when it tests behavior that exists only on that
platform, and it sits in the narrowest tree that has the behavior. Everything else
belongs in `shared/src/test/scala/kyo/` and must pass on every platform.

---

## Pre-submission checklist (kyo-core-specific)

- [ ] New concurrent primitives follow the four-layer pattern: opaque type, safe-tier extension block, `sealed abstract class Unsafe`, and `init`/`initWith`/`use`.
- [ ] Every `import AllowUnsafe.embrace.danger` and every non-standard bridge carries a `// Unsafe:` comment.
- [ ] New platform-specific code is in the narrowest tree that fits (`shared/` first, then `jvm-native/`, then `jvm/`, `native/`, or `js-wasm/`).
- [ ] No `Thread.sleep`, `synchronized`, or blocking primitive on a path a fiber can reach.
- [ ] A change to fiber completion, interrupt delivery, or `Scope` closing keeps the invariants in "Scope and fiber lifecycle", and the tests pinning them pass.
- [ ] Tests extend `kyo.test.Test`, not raw ScalaTest.
- [ ] Concurrency tests use `Latch`, `Channel`, or `Clock.withTimeControl` for determinism, not real-time sleeps.
- [ ] A change to the `async.concurrency.default` flag keeps the loud failure for malformed values.
