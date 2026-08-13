# ContextEffect without a carried default: one optional read, answered Absent

Proposal. Resolves the TODO at `KyoInternal.scala:38` ("no, this is not
acceptable, we need to fully review") and the sentinel it forced into
`ContextEffect`. Public signatures return to the old kernel's, verbatim where
the kernel allows it.

## 1. What the old kernel does

Context values live in a map the evaluator threads, so both questions a context
effect asks are ordinary map queries at the point of use.

The read, with the mandatory form defined as the defaulted form:

```scala
inline def suspendWith[A, E <: ContextEffect[A], B, S](tag)(f): B < (E & S) =
    suspendWith(tag, bug("Unexpected pending context effect: " + tag.show))(f)

inline def suspendWith[A, E <: ContextEffect[A], B, S](tag, default: => A)(f): B < S =
    new KyoDefer[B, S]:
        def apply(v: Unit, context: Context)(using Safepoint) =
            f(context.getOrElse(tag, default).asInstanceOf[A])
```

The layered handle, which never suspends to ask whether an outer value exists:

```scala
inline def handle[A, E, B, S](tag, ifUndefined: A, ifDefined: A => A)(v): B < S =
    def handleLoop(v) = v match
        case kyo: KyoSuspend[...] =>
            new KyoContinue(kyo):
                def apply(v, context) =
                    val updated =
                        if !context.contains(tag) then context.set(tag, ifUndefined)
                        else context.set(tag, ifDefined(context.get(tag)))
                    handleLoop(kyo(v, updated))
        case kyo => kyo.unsafeGet
    handleLoop(v)
```

Two primitives, both free: `getOrElse` for "value or default", `contains` for
"is one provided".

## 2. What kernel2 does today, and why it is worse

kernel2 deleted the map. Provision is a handler region on the `Handlers` spine,
so "is a value provided" is `hs.find(tag)`, which only the evaluator can ask.
Both old-kernel primitives had to be rebuilt on that, and each grew a wart.

`getOrElse` became a value carried on the suspension, typed `Any`:

```scala
private[kyo] trait Defaulted:            // KyoInternal.scala
    self: Suspend[?, ?, ?, ?, ?, ?] =>
    def default: Any                     // the TODO

case d: Kyo.Defaulted =>                 // Eval.scala, find-miss arm
    resume(kyo.cont, Nested.lift(d.default))
```

`contains` became a probe suspension carrying a unique object as its default,
recovered by identity comparison:

```scala
private val undefined = new AnyRef {}    // ContextEffect.scala

private inline def probe[A, E](tag) =
    new Kyo.Suspend[Const[Unit], Const[A], E, Any, Any, Any] with Kyo.Defaulted:
        def default = undefined

probe(tag).map { outer =>
    if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined
    else ifDefined(outer.asInstanceOf[A])
}
```

So one mechanism (`Defaulted`) serves two different questions, is typed `Any`,
and the second question is answered by an ad-hoc sentinel.

## 3. Proposal

Stop carrying anything. The evaluator already knows the answer to `contains`,
so let it say so, once, with a constant: an optional suspension whose tag has no
region is answered `Absent`. Everything else is ordinary code in
`ContextEffect`, exactly as the old kernel's `getOrElse` and `contains` were
ordinary code over its map.

### Kernel

```scala
// KyoInternal.scala: replaces Defaulted, no members
private[kyo] trait Optional:
    self: Suspend[?, ?, ?, ?, ?, ?] =>

// Eval.scala, find-miss arm
case _: Kyo.Optional =>
    loop(resume(kyo.cont, Nested.lift(Absent)), hs)
```

`Maybe` is already used inside the kernel (`ArrowEffect.handlePartial`), so this
adds no dependency. It never reaches a user-facing signature: the wire encoding
lives entirely inside `ContextEffect`, whose declared operation type is
unchanged (`ArrowEffect[Const[Unit], Const[A]]`).

### ContextEffect

One internal primitive, four public methods on the old kernel's signatures.

```scala
// the only new construct: a read whose tag may have no region
@nowarn("msg=anonymous")
private inline def optional[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(
    using inline _frame: Frame
): Maybe[A] < Any =
    new Kyo.Suspend[Const[Unit], Const[A], E, Any, Maybe[A], Any] with Kyo.Optional:
        def tag   = effectTag
        def input = ()
        def frame = _frame
        // identity: the wire value is already the Maybe encoding, either a
        // provision's Maybe(value) or the evaluator's Absent
        def cont  = Arrow[Any].asInstanceOf[Arrow[A, Maybe[A], Any]]

// provision encodes, so a context value that is itself Absent stays
// distinguishable from "no region provided one"
private inline def provision[...](tag, v)(f) = ... Loop.continue(Maybe(value)) ...
```

```scala
// mandatory read: the old kernel's definition, a defaulted read whose default bugs
inline def suspendWith[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E]
)(
    inline f: A => B < S
)(using inline frame: Frame): B < (E & S) =
    suspendWith(effectTag, bug("Unexpected pending context effect: " + effectTag.show))(f)

// defaulted read: getOrElse, as in the old kernel, over the evaluator's answer
inline def suspendWith[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E],
    inline default: => A
)(
    inline f: A => B < S
)(using inline frame: Frame): B < S =
    optional[A, E](effectTag).map(m => f(m.getOrElse(default)))

inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(
    using inline frame: Frame
): A < E = suspendWith(effectTag)(identity)

inline def suspend[A, E <: ContextEffect[A]](
    inline effectTag: Tag[E],
    inline default: => A
)(using inline frame: Frame): A < Any = suspendWith(effectTag, default)(identity)

// layered handle: contains-or-not, now an honest Maybe instead of a sentinel
inline def handle[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E],
    inline ifUndefined: A,
    inline ifDefined: A => A
)(v: B < (E & S))(using inline frame: Frame): B < S =
    provision(effectTag, v)([X] => _ =>
        optional[A, E](effectTag).map { outer =>
            Loop.continue(Maybe(if outer.isEmpty then ifUndefined else ifDefined(outer.get)))
        })

inline def handle[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E],
    inline value: A
)(v: B < (E & S))(using inline frame: Frame): B < S =
    handle(effectTag, value, _ => value)(v)
```

Deleted: `Kyo.Defaulted` and its `Any` member, the `undefined` sentinel, the
identity comparison, and the by-name thunk captured as a field by every
defaulted suspension.

## 4. Signature parity

Checked against `origin/main:kyo-kernel/.../ContextEffect.scala` by extracting
every signature from both files.

| method | old kernel | kernel2 today | proposed |
|---|---|---|---|
| `suspend(tag)` | `A < E` | same | same |
| `suspendWith(tag)(f)` | `B < (E & S)`, `f: Safepoint ?=> A => B < S` | same minus `Safepoint ?=>` | same |
| `suspend(tag, default)` | `A < Any`, param `default` | same, param renamed `_default` | back to `default` |
| `suspendWith(tag, default)(f)` | `B < S` | same, param renamed `_default` | back to `default` |
| `handle(tag, value)(v)` | `B < S`, delegates to the 3-arg form | same shape, separate impl | delegates again |
| `handle(tag, ifUndefined, ifDefined)(v)` | `B < S` | same | same |

Two differences remain and neither is this proposal's to fix: kernel2 dropped
`Safepoint` value parameters kernel-wide, and `ContextEffect[+A]` extends
`ArrowEffect[Const[Unit], Const[A]]` rather than `Effect` (deliberate: context
effects became ordinary arrow effects). The `_default` rename disappears here,
because with no member named `default` on the suspension the parameter can take
its old name back, restoring named-argument parity.

## 5. Soundness

The encoding must distinguish "provided the value `Absent`" from "provided
nothing", which matters for `Local[Maybe[B]]`:

- provision answers `Maybe(value)`. `Maybe(Absent)` is `Present(Absent)`, a real
  wrapper, so it is not the bare `Absent` the evaluator answers.
- the evaluator answers bare `Absent` on a find miss.
- readers decode with `getOrElse` / `isEmpty`.

`Maybe(v)` is the identity for every non-nested value, so the encode allocates
only for a context effect whose value type is itself `Maybe`-shaped.

Known constraint, unchanged by this proposal: a user may install a raw
`ArrowEffect` handler over a context tag (possible since `ContextEffect` became
an `ArrowEffect`), and such a handler answers an unencoded value. Reads then
misdecode only if that value is `Absent`-shaped. The isolation work already
treats raw handlers over context tags as outside the supported path (they never
mix in `Provision` and are never transplanted).

## 6. Cost

Per defaulted read: one `getOrElse` (a type test) added; one captured by-name
field removed from the suspension. Per provision: one `Maybe(...)` encode (a
type test). Per mandatory read: one decode added, where today there is none.

`Local.get` and `Env.get` are hot, and `ArrowEffectBytecodeTest` pins this
module's allocation shape, so this must be measured before landing:
`userTypesSkipKernelWrapping`, `idleHandlerAddsNothing`, and the
`ContextEffect` bytecode pins, plus the full fixture board.

## 7. Files

| file | change |
|---|---|
| `internal/KyoInternal.scala` | `Defaulted` → `Optional` (no members); TODO deleted |
| `internal/Eval.scala` | find-miss arm answers `Absent`; `Detached` arm unchanged |
| `ContextEffect.scala` | one `optional` primitive; four methods on old signatures; `undefined` deleted; provision encodes |
| `ContextEffectTest.scala` | probe-behavior tests follow the new decode |
| `kyo-kernel2/CONTRIBUTING.md` | the `ContextEffect` and find-miss descriptions |

## 8. Open questions for you

1. **Unhandled mandatory read.** Old kernel: `bug("Unexpected pending context
   effect: ...")`, which this proposal restores. kernel2 today: the evaluator's
   `IllegalStateException("unhandled suspension: ...")`. The old message names
   the effect and is better, but it moves the failure out of the evaluator.
   Confirm the old behavior is what you want.
2. **`Detached`.** It stays a separate marker because its answer is computed
   (`transplant(hs, child)`), not a constant. It could share one marker with a
   `def unhandled(hs: Handlers)` member, at the cost of putting a member back on
   the trait. Proposal keeps them separate; say if you prefer the merge.
3. **`bug` availability.** The old kernel calls `kyo.bug`; kernel2's kernel does
   not currently depend on it. If that dependency is unwanted, the mandatory
   read throws the same message directly.
