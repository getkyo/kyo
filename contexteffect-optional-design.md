# SUPERSEDED by threaded-context-design.md: ContextEffect and the handler-based provision were removed from kyo-kernel2 outright

# ContextEffect without a carried default: one optional read, answered Absent

Proposal. Resolves the TODO at `KyoInternal.scala:38` ("no, this is not
acceptable, we need to fully review") and the sentinel it forced into
`ContextEffect`. Public signatures return to the old kernel's, and every cast
in the mechanism is eliminated: the marker is typed, so the compiler checks
that the evaluator's answer fits the operation it answers.

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
Both old-kernel primitives were rebuilt on that, and each grew a wart.

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

probe(tag).map { outer =>
    if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined
    else ifDefined(outer.asInstanceOf[A])
}
```

One mechanism serves two different questions, is typed `Any`, and the second
question is answered by an ad-hoc sentinel.

## 3. Proposal

Stop carrying anything the caller supplies. The evaluator already knows the
answer to `contains`, so let it say so with a constant the type system checks:
an optional suspension whose tag has no region is answered `Absent`. The
default returns to being ordinary code in `ContextEffect`, exactly as the old
kernel's `getOrElse` was ordinary code over its map.

### The marker restricts the suspension, and carries nothing

A bare marker would be unsound: the evaluator would inject `Absent` into a
continuation typed to receive `O[X]`, and nothing checks that `O[X]` admits it.
The fix is not to give the marker a member, it is to constrain the suspension it
may be mixed into, so the continuation *is* a `Maybe` consumer by construction:

```scala
// KyoInternal.scala: replaces Defaulted
private[kyo] trait Optional[A]:
    self: Suspend[?, Const[Maybe[A]], ?, ?, ?, ?] =>
    // no members: the self-type is the proof. A suspension can only claim to be
    // optional if its continuation already accepts Maybe[A], which is what makes
    // the find-miss injection of Absent sound

// Eval.scala, find-miss arm
case _: Kyo.Optional[?] =>
    loop(resume(kyo.cont, Nested.lift(Absent)), hs)
```

Nothing is carried, nothing is allocated, and the evaluator answers one shared
constant. `Absent <: Maybe[Nothing] <: Maybe[A]`, so the injection typechecks
against every continuation the self-type permits.

### The effect's output gains room for absence

For the self-type to be satisfiable at the read site, the operation's output
must be the `Maybe`:

```scala
abstract class ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[Maybe[A]]]
```

`ArrowEffect[-I[_], +O[_]]` is covariant in `O` and `Maybe` is covariant, so
`ContextEffect[+A]` stays covariant. This is the only visible change to the
type, and it does not reach `ContextEffect`'s own API: all six methods stay in
terms of `A` (section 4). It is visible to one thing: a raw `ArrowEffect`
handler installed directly over a context tag, which the isolation work already
treats as outside the supported path.

### ContextEffect: one primitive, four methods, zero casts

```scala
// the only new construct: a read whose tag may have no region
@nowarn("msg=anonymous")
private inline def optional[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(
    using inline _frame: Frame
): Maybe[A] < Any =
    new Kyo.Suspend[Const[Unit], Const[Maybe[A]], E, Any, Maybe[A], Any] with Kyo.Optional[A]:
        def tag   = effectTag
        def input = ()
        def frame = _frame
        def cont  = Arrow[Maybe[A]]    // identity, no cast: O[X] is already Maybe[A]
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

// provision answers a Maybe, so the encode is checked rather than cast
private inline def provision[...](inline effectTag, v)(inline f) = ...
    Loop.continue(Maybe(value))

// layered handle: contains-or-not, an honest Maybe instead of a sentinel
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
identity comparison, the by-name thunk captured as a field by every defaulted
suspension, and both casts the untyped version needed (`cont` and the provision
answer).

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

No user-facing signature mentions `Maybe`. Two differences remain and neither is
this proposal's to fix: kernel2 dropped `Safepoint` value parameters
kernel-wide, and `ContextEffect` extends `ArrowEffect` rather than `Effect`
(deliberate). The `_default` rename disappears here, because with no member
named `default` on the suspension the parameter takes its old name back,
restoring named-argument parity.

## 5. Soundness

Two distinct claims, both now checked rather than asserted.

**The injection fits the continuation.** The marker's self-type is
`Suspend[?, Const[Maybe[A]], ?, ?, ?, ?]`, so a suspension cannot claim to be
optional unless its continuation already consumes `Maybe[A]`. The evaluator's
`Absent` fits every such continuation by subtyping, with no member on the
marker and no cast at the injection.

**Absence is distinguishable from a provided `Absent`.** This matters for
`Local[Maybe[B]]`: provision answers `Maybe(value)`, and `Maybe(Absent)` is
`Present(Absent)`, a real wrapper, so it is never the bare `Absent` the
evaluator answers. `Maybe(v)` is the identity for every non-nested value, so
the encode allocates only when the context value type is itself `Maybe`-shaped.

Known constraint, unchanged: a user may install a raw `ArrowEffect` handler over
a context tag, and such a handler answers whatever it likes. With the typed
output that handler's clause is now forced to produce a `Maybe[A]`, which is
strictly better than today, where it produces an `A` that reads misdecode.

## 6. Cost

Per defaulted read: one `getOrElse` (a type test) added; one captured by-name
field removed from the suspension. Per provision: one `Maybe(...)` encode (a
type test). Per mandatory read: one decode added, where today there is none.

`Local.get` and `Env.get` are hot and `ArrowEffectBytecodeTest` pins this
module's allocation shape, so this must be measured before landing:
`userTypesSkipKernelWrapping`, `idleHandlerAddsNothing`, the `ContextEffect`
bytecode pins, and the full fixture board.

## 7. Files

| file | change |
|---|---|
| `internal/KyoInternal.scala` | `Defaulted` → `Optional[A]`, no members, self-typed to a `Maybe`-consuming suspension; TODO deleted |
| `internal/Eval.scala` | find-miss arm answers `Absent`; `Detached` arm unchanged |
| `ContextEffect.scala` | output type gains `Maybe`; one `optional` primitive; four methods on old signatures; `undefined` deleted; provision encodes |
| `ContextEffectTest.scala` | probe-behavior tests follow the new decode |
| `kyo-kernel2/CONTRIBUTING.md` | the `ContextEffect` and find-miss descriptions |

## 8. Open questions for you

1. **Unhandled mandatory read.** Old kernel: `bug("Unexpected pending context
   effect: ...")`, which this proposal restores. kernel2 today: the evaluator's
   `IllegalStateException("unhandled suspension: ...")`. The old message names
   the effect; the current one keeps the failure in the evaluator. Confirm which
   you want.
2. **`Detached`.** It stays a separate marker: its answer is computed from the
   live spine (`transplant(hs, child)`), not a constant, and it is typed at the
   suspension's result rather than the operation's output. Merging both under
   one marker would need a `def unhandled(hs: Handlers)` member and would put
   the untyped `child: Any` back in the middle of it. Say if you want the merge
   anyway.
3. **`bug` availability.** The old kernel calls `kyo.bug`; kernel2's kernel does
   not currently depend on it. If that dependency is unwanted, the mandatory
   read throws the same message directly.
