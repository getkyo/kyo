# Derivation: hierarchical scopes

Base `c67b5981db`. Builds membership; does **not** deliver the guarantee. Read "What this does not do"
before the rest, because it is the point of the review.

## The equation

Today `Scope.run` is one registry with no relationships:

```scala
val f = Finalizer.init(parallelism)
handleInheritable(Tag[Scope], f, _ => f)(v)          // fork = parent => parent
```

After, the same shape with the three hooks made real:

```scala
ContextEffect.handle(Tag[Scope])(
    derive = outer => { outer.foreach(_.addChild(f)); f },
    fork   = parent => parent.newChild(),
    join   = (parent, _, child) => { child.closeUnsafe(Absent); parent }
)(v)
```

`derive` links a nested run into the scope it is nested in. `fork` gives a crossing a scope of its
own, already in membership. `join` closes that scope when the crossing returns. Nothing reports
upward: a parent reaches down through its membership.

## The node

`Finalizer` keeps its finalizer queue and completion promise and gains a children set. No parent
field: the link lives in the parent's set, so a node holds no mutable state beyond its collections,
and `derive` stays the constant function it already was, which matters because the abandonment walk
calls it with `Absent` on a region that was never installed (`Eval.scala:538`).

Closing a scope closes its children, waits for them, and only then runs its own finalizers.

Membership ends when a child has finished **releasing**, not when its extent ends: `newChild`
registers the removal on the child's close promise. A child dropped at the end of its extent would
still be running its finalizers, and its parent would stop waiting for releases that had not
happened.

## What this does not do, which is the whole point of the review

**Closing a scope does not stop the computation running under it.** The hierarchy gives a parent a
handle on a child's *resources*; nothing ends the child's *work*. Consequences, all measured:

- **D3 is not fixed.** `ScopeInterruptTest`'s pin stays pending, and its own message says why:
  "Fiber.init registers an interrupt rather than an awaited release, so the scope exits while the
  child still holds its resource." The child scope closes immediately while the fiber runs on, so the
  parent's await is vacuous.
- **D2 is not fixed.** All three `StreamCoreExtensionsTest` pins stay pending: "the producer fibers
  are spawned unscoped and nothing interrupts them when the consumer stops".
- **One regression.** `StreamCoreExtensionsTest` "combinator › groupedWithin › scope" fails on a
  close-count mismatch, because closing a child's scope from above releases resources out from under
  work that is still running.

The unresolved question is a single one: **what ends the computation under a scope being closed?**
Membership cannot answer it, and every symptom above is that same gap.

## Evidence

| suite | result |
|---|---|
| `kyo-kernelJVM` | 1477 passed, 0 failed. Kernel untouched. |
| `ScopeTest` | 51 passed, 0 failed |
| `ScopeInterruptTest` | 4 passed, 0 failed, 1 pending (D3, unchanged) |
| `StreamCoreExtensionsTest` | 183 passed, **1 failed**, 3 pending (D2, unchanged) |

JVM only. `Scope.scala` is shared source, so JS and Native are unexercised.

## Surface

Changed: `kyo-core/shared/src/main/scala/kyo/Scope.scala` only.

`Finalizer.Awaitable` is collapsed into `Finalizer`, since children have to be closable and awaitable
and the state type is `Finalizer`. It was referenced only inside `Scope.scala` and in one line of
`kyo-core/README.md`, which needs updating and has not been.

Not changed: the kernel; `Scope.ensure`/`acquireRelease`/`acquire`; `Fiber.interrupt`; `Bracket`.

## A refuted premise, recorded so it is not retried

The first attempt had a forked child **report upward** that its extent had ended, which needs `done`
or `release` forwarded to a forked region. That is wrong, and two existing pins say so:

- `IsolateTest` "an isolate cycle fires done once, for the region the user installed, with the joined
  state" — a forked copy is not a region the user installed.
- `BracketTest` "a bracket does not cross into an isolated child", whose comment states that the
  forked copy is inert and "carries no obligation and no refusal".

A forked region is deliberately not a lifecycle participant. The kernel change that attempt made is
fully reverted.

## Open decisions

1. **What ends the computation under a closing scope.** Blocking; see above.
2. Whether a child's finalizer failure propagates or is logged. Recommendation: log, as `Scope.run`
   already does for its own (`Scope.scala:210-212`).
3. The per-crossing cost. Every crossing now allocates a `Finalizer` and mutates a set. Not measured.
4. `Fiber.initUnscoped` is documented as deliberately unparented, yet it crosses, so `fork` gives it
   a scope its parent will close. Whether an unscoped spawn should be a member at all is a semantics
   question.
