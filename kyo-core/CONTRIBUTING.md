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

The type rules are the root guide's "Types" section. One module-local exception: a
private hot path may fill a raw array and hand it to `Chunk.fromNoCopy`, trimming a
partial buffer with `java.util.Arrays.copyOf`. The handover skips the defensive copy
`Chunk.from` makes, so the array must not be touched after it is wrapped.

---

## Safe-by-default tier

Every public API is in the safe tier. The unsafe tier (`*.Unsafe`) exists for
integrators and performance-critical bridging.

Safe-tier methods delegate into their `Unsafe` counterpart through `Sync.Unsafe.defer`;
that is the standard step and needs no comment. A `// Unsafe:` comment is required
where code steps outside that pattern: an `import AllowUnsafe.embrace.danger`, or a
bridge that evaluates or completes something the safe tier cannot express. The comment
says why the site cannot stay in the safe tier.

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
`Thread.sleep`, `Future.await`, `CountDownLatch.await`, a wait inside `synchronized`,
or any other blocking primitive. Use Async suspension instead:

- Wait for channel space: `Channel.put` / `Channel.take`
- Wait for a count to reach zero: `Latch.await`
- Wait for elapsed time: `Clock.sleep`
- Join a forked fiber: `Fiber.get`
- Synchronize a group of fibers: `Gate.pass`

A `synchronized` block is tolerated only when it guards a short constant-time update
and never waits inside the lock.

The one blocking wait in the module is the log drain's `CountDownLatch.await` in the
JVM and Native shutdown hook (`jvm-native/.../internal/LogPlatformSpecific.scala`): it
runs on a raw hook thread where no fiber suspension is reachable, and it is bounded by
`Log.asyncLogging.shutdownDrainBudget`. A new wait needs the same justification in a comment at the site.

`IOPromise.block` parks the calling thread through `LockSupport.park`; it is what
`Fiber.block` runs on. The js-wasm `LockSupport` stub (`js-wasm/src/main/scala/kyo/AsyncStubs.scala`)
exists so that shared code links on JS and Wasm, and it throws when `block` is reached
there, since a single-threaded platform cannot park the one thread it has.

### Adding a new concurrent primitive

The opaque-type-over-`Unsafe` shape, the `Sync.Unsafe.defer` and `Abort.get` bridge,
and the `init`/`initWith`/`use`/`initUnscoped` factory chain are the root guide's
"Unsafe Boundary" section ("The Two-Tier API Pattern" and "Closeable Resource
Pattern"). `Channel` and its companion are the reference implementation in this module.

A suspending operation tries the non-suspending Unsafe path first and parks only when
that path reports it cannot proceed:

```scala
def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
    Sync.Unsafe.defer {
        self.offer(value).foldError(
            {
                case true  => ()
                case false => self.putFiber(value).safe.get
            },
            Abort.error
        )
    }
```

The fast path allocates no fiber, so an uncontended `put` costs one Unsafe call.

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
exclusive to one platform, `js-wasm/` for the JS and Wasm side. Test placement follows
the root guide's "Platform-Conditional Tests" section.

Platform code takes one of three shapes:

- A `<Type>PlatformSpecific` split holds only the part of a shared type that differs,
  and shared code calls into it. `AsyncPlatformSpecific` in `jvm-native/` adds
  `fromCompletionStage`, which JS and Wasm cannot express.
- A type that exists on one platform only has no shared counterpart.
  `StreamCompression` in `jvm/` wraps `java.util.zip`.
- A JDK stub supplies a class the platform lacks so shared code links.
  `hubsStubs.scala` in `native/` supplies `CopyOnWriteArraySet`, and `AsyncStubs.scala`
  in `js-wasm/` supplies `LockSupport`.

### The OsSignal pattern as a template

`OsSignal` (`shared/src/main/scala/kyo/internal/OSSignal.scala`) extends
`OsSignalPlatformSpecific` and defines the `Handler` type with its `Handler.Noop`
fallback. Each platform leaf defines `OsSignalPlatformSpecific` with a `handle: Handler`
(files named `OSSignalPlatformSpecific.scala`): JVM uses `sun.misc.Signal` via
reflection with a `Noop` fallback on missing classes, Native uses POSIX signals, JS-Wasm
is `Noop`. A kyo-core internal that needs a per-platform implementation follows this
template; OS capabilities for applications belong in kyo-system.

---

## Test patterns

The test base class is the root guide's "Framework" section.

### Deterministic concurrency testing

The root guide's "Deterministic Tests" section is the rule: no real-clock assertions,
virtual time through `Clock.withTimeControl`, and rendezvous through `Latch`,
`Channel`, and `Fiber.get` rather than sleeps.

### Primitive lifecycles in tests

Prefer `use` in tests: it closes the primitive when the block ends. `init` and
`initWith` register the close on the test's own scope, which the base handles, so the
primitive stays open until the whole test ends.

```scala
Channel.use[Int](10) { c =>
    c.put(1).andThen(c.take)
}
```

Use `initUnscoped` or `initUnscopedWith` when the test itself owns the lifecycle,
for example to close the primitive at a chosen point and assert on what follows.

---

## Pre-submission checklist (kyo-core-specific)

- [ ] New concurrent primitives follow the root two-tier and closeable-resource patterns, with `Channel` as the reference.
- [ ] Every `import AllowUnsafe.embrace.danger` and every non-standard bridge carries a `// Unsafe:` comment.
- [ ] New platform-specific code is in the narrowest tree that fits (`shared/` first, then `jvm-native/`, then `jvm/`, `native/`, or `js-wasm/`).
- [ ] No `Thread.sleep`, wait inside `synchronized`, or other blocking primitive on a path a fiber can reach; a lock guards only a short constant-time update.
- [ ] A change to fiber completion, interrupt delivery, or `Scope` closing keeps the invariants in "Scope and fiber lifecycle", and the tests covering fiber completion, interrupts, and scope close pass.
- [ ] Concurrency tests use `Latch`, `Channel`, or `Clock.withTimeControl` for determinism, not real-time sleeps.
- [ ] A change to the `async.concurrency.default` flag keeps the loud failure for malformed values.
