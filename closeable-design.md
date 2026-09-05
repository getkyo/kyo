# kyo.Closeable: interface exploration

Status: exploration, no decision taken. Companion to `hierarchical-scopes-design.md` section 13,
which defers this question rather than answering it.

The question is whether there should be a `kyo.Closeable`, whether it belongs in the kernel, and
what its interface is. There is no such type today.

## 1. What has to be expressible

Every existing close in the tree, with the three axes it varies on: whether it carries a reason,
what it returns, and whether it has a wait.

| site | signal | reason | result | wait | "already closed" reported by |
|---|---|---|---|---|---|
| `Bracket` release (`Bracket.scala:83`) | `(A, Maybe[Throwable]) => Unit` | yes | `Unit` | none | `Cell`'s CAS, internal |
| `Scope.Finalizer.close` (`Scope.scala:202`) | `Maybe[Error[Any]] => Unit < Sync` | yes | `Unit` | `await: Unit < Async` | `queue.close()` returning `Absent` |
| `Scope.ensure` finalizer (`Scope.scala:157`) | `Maybe[Error[Any]] => Any < (Async & Abort[Throwable])` | yes | `Any` | n/a | n/a |
| `Channel.close` (`Channel.scala:218`) | `Unit < Sync` | no | `Maybe[Seq[A]]` | `closeAwaitEmpty` (see below) | `Maybe` being `Absent` |
| `Queue.close` (`Queue.scala:123`) | `Unit < Sync` | no | `Maybe[Seq[A]]` | `closeAwaitEmpty` | `Maybe` |
| `Hub.close` (`Hub.scala:98`) | `Unit < Sync` | no | `Maybe[Seq[A]]` | none | `Maybe` |
| `Meter.close` (`Meter.scala:83`) | `Unit < Sync` | no | `Boolean` | none | `Boolean` |
| `java.lang.AutoCloseable` | `close(): Unit` | no | `Unit` | none | nothing, and it throws |

**A correction to make before building on this.** `Channel.closeAwaitEmpty` and
`Queue.closeAwaitEmpty` are not the wait half of this interface. They wait until consumers have
drained the collection, not until resources have been released. The naming pair is a precedent for
how to spell the two operations; the semantics are not the same thing, and treating them as the
same would be a mistake.

## 2. The layering constraint decides most of it

`Maybe`, `Result` and `Frame` are `kyo-data`. `AllowUnsafe` is `kyo-config`, a root module below
everything. `Sync` and `Async` are `kyo-core`. `kyo-kernel` sits between data and prelude and has
no effect row for side effects at all, which is exactly why `Bracket`'s release is a bare function:
it runs on the abandonment path, where nothing is installed to answer for an effect.

So:

- an interface whose `close` is `Unit < Sync` cannot live in the kernel;
- an interface with `await: Unit < Async` cannot live in the kernel or in prelude;
- an interface the kernel can define carries no effect row at all.

That rules out most of the shapes before taste enters. It does not, however, rule out expressing
the *wait*: a callback is a function and carries no row, so the constraint bites on `Async` rather
than on waiting itself. Shape D below takes that route.

## 3. Four candidate interfaces

### A. Kernel, no row

```scala
trait Closeable:
    def close(reason: Maybe[Throwable]): Unit
```

Matches `Bracket`'s release exactly. Cannot express `Scope`'s async finalizers and has no wait, so
`Scope` cannot use it for membership, which was the whole motivation.

### B. Core, rowed pair

```scala
trait Closeable:
    def close(using Frame): Unit < Sync
    def closeAwait(using Frame): Unit < Async
```

Matches `Finalizer`. Cannot be in the kernel. `Channel`, `Queue`, `Hub` and `Meter` do not fit,
because their close returns data.

### C. Row-polymorphic

```scala
trait Closeable[-S]:
    def close(using Frame): Unit < S
```

The kernel could define it and core instantiate it at `Sync`. But it does not give the
signal/wait split that the phased close requires, and `Closeable[Async]` would let a phase-1
signal block, which is the specific thing the phases exist to prevent.

### D. One type below core, with the wait as a callback

`Async` cannot go below core, but a callback is a function and carries no row at all. That is
enough to express the wait, and it removes the need for a second trait:

```scala
trait Closeable:
    def close(reason: Maybe[Throwable]): Unit
    def onClosed(f: Maybe[Throwable] => Unit): Unit
```

`kyo-core` then derives the safe form rather than declaring a new type, by registering a promise
completion as the listener:

```scala
extension (self: Closeable)
    def closeAwait(reason: Maybe[Error[Any]])(using Frame): Unit < Async =
        Promise.init[Nothing, Unit].map { p =>
            Sync.Unsafe.defer(self.onClosed(_ => discard(p.unsafe.completeUnit())))
                .andThen(self.close(...))
                .andThen(p.get)
        }
```

This is the existing tier convention, not a new idea. `IOPromise.onComplete(f: Result[E, A] => Any)`
is a raw callback with no row (`IOPromise.scala:158`), `Fiber.Unsafe.onComplete` takes
`AllowUnsafe` (`Fiber.scala:455`), and `Fiber.onComplete` is `Unit < Sync` (`Fiber.scala:284`). The
callback is the raw tier and `closeAwait` is the safe tier built on it, exactly as CONTRIBUTING's
"unsafe tier mirrors safe tier" rule describes.

It also still matches the phase structure, which is what shape B and C fail at: phase 1 calls
`close` on every member without waiting on any, and phase 2 waits on each. And it matches the
kernel constraint the hierarchy design already states, that on the abandonment path a scope can
fire its close but cannot await.

#### The callback contract

Four things have to be pinned, and the first is the one that would actually bite.

1. **Registering after the close has already completed fires immediately.** Phase 2 registers on a
   child that may have closed between phase 1 and phase 2. A lost wakeup there hangs the parent,
   which is the worst failure this design can produce, and it is invisible in any test where the
   child happens to be slower than the loop.
2. **Multiple listeners.** A scope can be awaited by its parent and by a held handle at the same
   time, which is what "beyond parent/child" requires. A single-slot callback would silently drop
   one of them.
3. **`onClosed` fires on close completion, not on the signal arriving.** These are two different
   events (phase 1 received versus phase 3 finished) and the parent wants the second. Naming it
   `onClosed` rather than `onClose` is deliberate.
4. **The listener runs on whoever completed the close.** So it must not block, and a listener that
   throws must not corrupt the closer or prevent the remaining listeners from running.

Points 1 and 2 are both already solved by `IOPromise`, which is the reason to model this on it
rather than invent a listener list.

## 4. Where the signal half belongs, and it is probably not the kernel

The signal half needs only `Maybe`. It has no kernel dependency, so `kyo-data` can hold it and both
kernel and core can see it.

The test for putting it in the kernel is whether the kernel *consumes* it. Today the only candidate
is `Cell`, which is `private[kyo]` and already has the shape as a function field
(`fin: Maybe[Throwable] => Unit`, `Bracket.scala:43`). A trait whose only kernel use is to name a
function the kernel already passes is a vocabulary word, not a capability, and the kernel's own rule
is that a new type must earn itself against the ones already there.

So the honest position:

- if the goal is one vocabulary across the stack, `kyo-data` is the correct home;
- `kyo-kernel` is correct only if `Bracket.apply` changes to take a `Closeable` instead of a
  `(A, Maybe[Throwable]) => Unit`. That is a real proposal, but it needs its own justification, and
  it costs an allocation per bracket unless `Cell` is made to implement the interface directly.

I would put it in `kyo-data` unless someone names a kernel consumer.

## 5. The result value should be discarded

`Channel.close` returns `Maybe[Seq[A]]`, `Meter.close` returns `Boolean`, `Finalizer.close` returns
`Unit`. A common `close: Unit` throws that away.

The site that consumes the interface does not want it. Phase 1 signals and reads nothing. So the
discarding form is the right one, and types with informative closes keep their own richer `close`
and gain a `Closeable` view alongside it.

Making it `Closeable[R]`, generic in the result, buys nothing at the only site that consumes the
interface and infects every membership set with a type parameter it immediately discards.

## 6. Idempotence belongs to the type, not to each implementer

Every close in the survey reports "already closed" somehow, and `Cell` enforces exactly-once with a
CAS. Under the hierarchy, phase 1 and the abandonment path can both fire the same close, so an
implementer that gets this wrong produces a double release rather than a leak.

A bare trait puts that burden on every implementer. `Cell` shows the alternative, which is to own
the guard in the type:

```scala
abstract class Closeable extends AtomicBoolean:
    protected def onClose(reason: Maybe[Throwable]): Unit
    final def close(reason: Maybe[Throwable]): Unit =
        if compareAndSet(false, true) then onClose(reason)
```

That is `Cell` generalized, and it makes the invariant true by construction rather than by every
implementer remembering. The cost is that it is a class, so a type cannot be a `Closeable` and
something else, which matters for `Channel` and `Queue`, which already have supertypes. A trait
carrying its own `private var closed` plus an atomic field reference is the alternative and is worth
weighing against it.

This is a genuine fork and I do not think it can be settled without deciding question 4 first, since
a `kyo-data` home makes the class form cheaper to adopt than a kernel home would.

## 7. What must not be a Closeable

**`Fiber`.** Interruption is a signal with no release guarantee: `Fiber.interrupt` is
`Boolean < Sync` and says nothing about whether the fiber's resources have been freed. Giving
`Fiber` a `Closeable.Awaitable` instance would promise backpressure the fiber does not provide. The
scope a fiber runs in is the closeable; the fiber is not. This is the boundary that keeps
`interruptAwait` a scope operation rather than a fiber one.

## 8. What adopting it would change

- `Scope.acquire` is pinned to `java.lang.AutoCloseable` (`Scope.scala:107`), whose `close()` is
  synchronous and throws. It would accept a kyo-native `Closeable` as well, gaining a reason and an
  async close.
- `Scope`'s membership entries become `Closeable.Awaitable` rather than specifically child scopes,
  which is the "beyond parent/child" generalization.
- `Bracket.apply`'s release could become a `Closeable`, which is the open question in section 4.

## 8b. Safe and unsafe tiers

The interface should be tiered the way every other kyo resource type is, with the unsafe tier
gated by `AllowUnsafe`:

```scala
// kyo-core
abstract class Closeable:
    /** Signals the close and returns. Does not wait for anything to be released. */
    def close(reason: Maybe[Error[Any]])(using Frame): Unit < Sync

    /** Signals the close and waits until everything it owns has been released. */
    def closeAwait(reason: Maybe[Error[Any]])(using Frame): Unit < Async

    def unsafe: Closeable.Unsafe
end Closeable

object Closeable:
    abstract class Unsafe:
        def close(reason: Maybe[Throwable])(using AllowUnsafe): Unit
        def onClosed(f: Maybe[Throwable] => Unit)(using AllowUnsafe): Unit
        def closed(using AllowUnsafe): Boolean
        def safe: Closeable
    end Unsafe
end Closeable
```

This does more than satisfy the convention. Section 3D put `onClosed` on the single type, and a raw
callback is unidiomatic as a safe kyo API while being exactly right as an unsafe one. The tiering
puts it where callbacks already live in this codebase.

### It also settles the module question, mechanically

The safe tier returns `Unit < Sync` and `Unit < Async`, so it must be `kyo-core`. A companion object
must be declared in the same file as its trait, so `Closeable.Unsafe` is `kyo-core` too.

So **`Closeable` is not in the kernel**, and `Bracket` keeps its `(A, Maybe[Throwable]) => Unit`.
This supersedes section 4's tentative preference for `kyo-data`: that reasoning assumed a single
untiered type, and tiering removes the option.

The one route back to a kernel-visible version is the shape `Fiber` already uses: a separate
low-level type at the bottom (`IOPromise`, `Fiber.scala:43`) that the core tiers are views over.
Applied here it means a row-free closeable in the kernel with `Closeable` and `Closeable.Unsafe` as
core views of it. That is worth its weight only if `Bracket.apply` consumes it, which turns open
question 3 from incidental into the decision that drives the packaging.

### The reason type disagrees between the tiers, and the tree already disagrees with itself

The unsafe tier above takes `Maybe[Throwable]` and the safe tier takes `Maybe[Error[Any]]`, and that
is not a slip. It reflects a split that exists in the code today:

- the kernel bracket release is `(A, Maybe[Throwable]) => Unit`, deliberately, because only panics
  reach the path it runs on;
- `Scope.ensure` and `Finalizer.close` take `Maybe[Error[Any]]`, which carries typed failures too.

"Unsafe tier mirrors safe tier" says the two should correspond, so either they differ and the bridge
converts lossily in one direction (a typed failure has no `Throwable` to hand down), or one of the
two is wrong. This has to be decided rather than absorbed, and it is now open question 4.

## 8c. Naming: what consolidates to `close`, and what does not

`close` should be the one word for "signal, do not wait", and phase 1 should not know what kind of
thing it is signalling. That much consolidates, and `Closeable.close` is the carrier.

`Fiber.interrupt` does not, and the test that decides it is what `close(Absent)` would mean:

- for a resource it is meaningful and ordinary, "end this, no failure";
- for a fiber it is meaningless, because a running computation cannot be made to succeed from
  outside. `interrupt` always injects a failure, and the fiber completes with
  `Panic(Interrupted(frame))` (`Fiber.scala:458`); the overload takes the error explicitly.

So the two do not share an honest signature, and a shared name would conceal that one of them can
only fail its target. It would also put `Fiber` in the vocabulary of the type section 7 excludes it
from, inviting the exact misread that exclusion exists to prevent: a reader who sees `fiber.close`
looks for `fiber.closeAwait` and expects it to wait for release.

The consolidation to take:

- `close` and `closeAwait` are what users see, on scopes and resources;
- `interrupt` stays the fiber-level mechanism that a scope's phase 1 invokes;
- `Fiber.init` registering `fiber.interruptDiscard` as a scope signal is the bridge, and it reads
  correctly: the scope closes, and closing it interrupts what it owns.

The shape similarity that motivates the question is real and worth stating, since it is what
`Closeable` captures: `Fiber.interrupt`, `Meter.close` and `Channel.close` are all idempotent
signals that report whether this call was the one that fired. Sharing a shape does not require
sharing a name.

## 8d. Relation to the hierarchical scope protocol

`Closeable` is the type of a membership entry in `hierarchical-scopes-design.md`. The mapping:

| protocol step | member used |
|---|---|
| `derive` / `fork` link a child | the child is constructed as a `Closeable` |
| phase 1, signal every member | `close(reason)`, no waiting |
| phase 2, wait for every member | `onClosed` listeners, aggregated into `closeAwait` |
| unlink on completion | the child registers `onClosed` on itself |
| recursion into a child scope | a scope is a `Closeable`, so phase 1 on it runs that child's own phased close |

The important direction is that the protocol constrains the interface rather than the reverse. Five
of the six items in the section 3D contract exist only because the protocol needs them:

- the **signal/wait split** is forced: if `close` always waited, phase 1 would run depth-first and a
  scope with two children would wait the first out before signalling the second;
- **firing `onClosed` immediately when already closed** is required because phase 2 registers after
  phase 1 and a child can finish in between, which is the lost-wakeup hang;
- **multiple listeners** are required because a child is awaited by its parent's phase 2, by its own
  unlink listener, and by any held handle, at the same time;
- **idempotence** is required because phase 1 and the abandonment path can both fire the same close;
- **the reason type** (open question 4) is a question only because phase 1 propagates the parent's
  reason downward, where the kernel's abandonment path has only a `Throwable` to offer.

What the interface gives back is the generalization the hierarchy design asks for in its section 13:
membership stops meaning "child scopes" and starts meaning "closeables I own", so a resource with an
async close can be a member directly rather than through a wrapper scope, and held handles become
expressible.

One deliberate misalignment: `Scope.Finalizer` would be a `Closeable`, but the kernel's `Cell`
cannot be, since section 8b places `Closeable` in core. The two bracketing mechanisms stay typed
differently, which is consistent with them already differing on purpose: `Bracket` forks inert,
`Scope` forks live.

## 9. Recommendation

Shape D, tiered per section 8b: `Closeable` and `Closeable.Unsafe` both in `kyo-core`, the unsafe
tier gated by `AllowUnsafe` and carrying the `onClosed` callback, the safe tier carrying `close` and
`closeAwait`. Result discarded. Idempotence owned by the base rather than promised in scaladoc.
`Fiber` explicitly excluded.

The kernel does not get `Closeable` under this recommendation. Reopening that means adopting the
`IOPromise` shape, and it should be reopened only by answering open question 3 first.

## 10. Open questions

1. **Does `close` carry the reason?** `Bracket` and `Finalizer` do; `Channel`, `Queue`, `Hub` and
   `Meter` do not. Carrying it means every existing close needs an adapter that ignores it. Not
   carrying it means `Bracket` cannot be expressed in terms of the interface at all. I lean to
   carrying it, because the reason is what separates release from discharge, and that distinction
   was just built into the kernel deliberately.
2. **Trait or abstract class** for the idempotence guard, per section 6.
3. **Does `Bracket.apply` consume it?** With the tiering in section 8b this is now the decision that
   drives the packaging, not a detail: if the answer is no, `Closeable` is core-only and the kernel
   keeps its function; if yes, the low-level type has to exist separately in the kernel with the two
   core tiers as views over it, which is a materially larger change.
4. **Which reason type?** `Maybe[Throwable]` in the unsafe tier and `Maybe[Error[Any]]` in the safe
   tier mirrors the split that exists today, but breaks the rule that the tiers correspond, since a
   typed failure has no `Throwable` to hand down. See section 8b.
