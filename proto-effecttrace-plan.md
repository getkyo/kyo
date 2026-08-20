# Porting EffectTrace to `kyo.proto`

Implementation-ready plan. Supersedes `proto-effecttrace-port-proposal.md`, which came back REWORK from
`reviews/EFFECTTRACE-PORT-REVIEW.md`; the old proposal stays in the tree for the record and nothing here
depends on it. Every one of the review's 22 findings is answered in section 8.

Written against the working tree at `f329501024` plus two changes that landed in the proto while this
was being written: **the `lower` removal** (explicit matches plus `<.unsafeGet`, now in `Eval.scala` and
`ArrowEffect.scala`) and **`Eval.apply` becoming `inline`** (`Eval.scala:29-30`). All line numbers below
are from those sources as they now stand: `Eval.scala` is 157 lines, `ArrowEffect.scala` is 228 lines,
`Pending.scala` exposes `unsafeGet` (`Pending.scala:287`) and no `lower`. Where the review quoted earlier
line numbers, this plan re-derives from the current file rather than carrying the quote. The inline drive
is not cosmetic for this port; section 2.6 is what it changes.

---

## 0. What is being ported, and the one structural fact that shapes all of it

`kyo.kernel.internal.EffectTrace` (265 lines) reconstructs the effect-level frames of a failure at the
boundary the exception crosses, from the failing value plus the drive stack, and carries them as a
suppressed exception. Nothing is recorded while a computation runs. The carrier class, `MaxFrames = 64`,
`reconstruct`, `carrierOf`, `find`, `splice`, `installInto`, `frame`, `region`, the cap discipline, and
the two error rules (a fatal error is returned unmodified; a non-fatal failure of the walk itself is
dropped) all transfer byte-for-byte. `Frame`'s API is `kyo-data` and unchanged.

What does not transfer is the walk and the wiring, for one reason:

> **The proto's drive drains its stack into folded arrows on every application.** `Eval.scala:137`, `:139`
> and `:141` each call `stack.dump()`, which nulls the entries it folds and advances `head`
> (`Stack.scala:106`, `:118`). At the moment a user function throws, the frames near the failure are in
> the folded arrow and in the popped entry, **not** on the stack. Any design that attaches only at the
> boundary sees the residue, not the failure. This is the review's F10 and it is why this plan has no
> staging: the six inner attach sites are the port, and only the *measurement* is staged.

The second structural fact, which the first proposal missed entirely:

> **Six kernel constructions mint a node that is its own continuation.** `suspendWith` sets `cont = this`
> (`ArrowEffect.scala:33,37`); `handleContWith`, `handleLoopWith`, `handleLoopStateWith` set `cont = this`
> (`:139,148`; `:173,182`; `:208,218`); the drive's two emitting-clause adapters set `contA = this`
> (`Eval.scala:53,57` and `:89,93`). A walk that re-enters such a slot as a node does not terminate, and
> the cap cannot save it, because those nodes emit `Frame.internal` (`Eval.scala:55,91`) which
> `Builder.frame` skips (`EffectTrace.scala:160`), so `size` never grows and `full` is never true. This is
> a recurrence of a bug the project already paid for: `WORK.md:628-630`, "`drain`'s SuspendWith arm pushed
> `m.tail`, which is `this` for a Defer, so it re-enqueued itself".

Section 1 makes termination structural rather than defensive.

---

## 1. The walk

### 1.1 The rule that makes it correct and terminating: position determines role

A node in this kernel can be two things at once. The drive tells the two apart by **position**, and the
walk must do the same:

- A **value position** (`Kyo.Defer.value`, `Kyo.Handle.value`, and the `curr` the drive matches at
  `Eval.scala:34`) holds a computation the drive will take apart. Walk it as a **node**: its structure is
  pending.
- An **arrow position** (a `Stack` entry, a half of an `Arrow.Chain`, a `cont` / `contA` / `contB` slot,
  the `next` a delivery folds) holds something the drive will only ever *apply* as an arrow
  (`Arrow.apply(v, next)`). Walk it as an **arrow**: it contributes its frame, and for a `Handler` its
  region label, and nothing else. Its node payload, if it has one, is behind the drive, not ahead of it.

Two consequences, both load-bearing:

1. **Termination is structural.** Every self-referential slot listed in section 0 is a *continuation*
   slot, so it is pushed in the arrow role, where it emits one frame and stops. There is no identity
   guard to forget and no cap to rely on.
2. **No stale frames.** A `Kyo.Handle with Arrow.Transform` reached as a stack entry does not re-walk its
   region body (which the drive already ran) and does not emit a second region label (the handler is its
   own stack entry, pushed at `Eval.scala:127`).

The walk's termination is exactly as strong as the drive's and no weaker: a cyclic *value* position (a
`Kyo.Defer` whose `value` is itself) hangs `Eval.scala:38` before any exception exists for the walk to
describe. Documented, not defended against.

### 1.2 The worklist type and the two matches

```scala
    /** A value-position node. An arrow-position node is walked as an arrow, which is what makes a
      * self-referential continuation slot (ArrowEffect.scala:37, :148, :182, :218; Eval.scala:57, :93)
      * emit one frame and stop instead of re-enqueueing itself forever.
      */
    final private class Node(val kyo: Kyo[?, ?])

    private type Item = Arrow[?, ?, ?] | Node
```

`Item` is the honest union: the underlying `<` union is `A | Kyo[A, S] | Nested[A]` (`Pending.scala:10`),
and of those only `Kyo` ever needs a worklist slot, wrapped to carry its role. Both matches below are
**exhaustive without a catch-all**: `Item` is a two-arm union whose `Arrow` arm subsumes `Arrow.Id`
(`Arrow.scala:93`, `private[Arrow]`, so it could never have been named), and `Kyo` is `sealed abstract`
with exactly three children (`KyoInternal.scala:9,13,19,26`). This is the property the first proposal
claimed for the wrong reason (F3); it is real here because the scrutinee is typed, not `Any`.

### 1.3 `drain`

```scala
        @tailrec private def drain(): Unit =
            if work.isEmpty then ()
            else if full then
                dropped += work.size
                work.clear()
            else
                work.removeHead() match
                    case n: Node =>
                        n.kyo match
                            case s: Kyo.Suspend[IX, OX, EX, ?, ?, ?] @unchecked =>
                                // the operation's own site, then whatever it answers into
                                frame(s.frame)
                                push(s.cont)
                            case h: Kyo.Handle[?, ?, ?, ?, ?] =>
                                // mirrors Eval.scala:125-128 exactly: cont, then the region label,
                                // then the body, so the body drains first and the label follows it
                                push(h.cont)
                                push(h.handler)
                                pushValue(h.value)
                            case d: Kyo.Defer[?, ?, ?, ?] =>
                                // mirrors Eval.scala:35-38
                                push(d.contB)
                                push(d.contA)
                                pushValue(d.value)
                    case h: Handler[?, ?, ?, ?] =>
                        // BEFORE the Arrow arm: Handler extends Arrow.Transform (Handler.scala:7),
                        // and a folded continuation can contain inner handlers (Stack.scala:116)
                        region(h.tag)
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b)
                        push(c.a)
                    case a: Arrow[?, ?, ?] =>
                        frame(a.frame)
                end match
                drain()
            end if
        end drain
```

Notes on each arm, with the fact that forces it:

- **Arm order.** `Node` first: a fused node reached in a value position must take the node arm, never the
  `Arrow` arm. `Handler` before `Chain` and before the general `Arrow` arm: `Handler` is an
  `Arrow.Transform` (`Handler.scala:7`) and reaches the walk both as a stack entry (`Eval.scala:127`) and
  inside a folded arrow, because `dump` keeps folding past a handler when it started below one
  (`Stack.scala:116`). Matching it as a plain arrow would print a source position where a region label
  belongs.
- **`push` order is reverse emission order.** `push` prepends and `drain` takes from the head, so the
  slot pushed last drains first. `Kyo.Defer`'s pushes read bottom-up exactly like `Eval.scala:36-38`.
- **`Arrow.Id` needs no arm.** It lands on the general `Arrow` arm and emits `Frame.internal`, which
  `frame` skips (`EffectTrace.scala:160`). It is still guarded at `push` for budget reasons (F9, below).
- **`Loop.Continue` / `Continue2` get no arm.** Under the wiring in section 2 no attach site is ever
  handed an outcome: the clause sites pass the `Kyo.Suspend`, the delivery sites pass the popped `Arrow`,
  and the only outcome-typed value slot in the kernel is the emitting adapter's `value`, which holds the
  *clause* (a `Kyo`, `Eval.scala:56,92`), never a settled `Continue`. Adding the arms would be dead code
  on a cold path. **Decision: do not add them.** If a future site passes `curr` at `Eval.scala:130`, the
  two arms in `pushValue` are the fix, and this paragraph is the note that says so.
- **`Kyo.Suspend`'s type pattern.** A type pattern cannot spell a wildcard at a higher kind, so the walk
  reuses the drive's device: three abstract members on the object, copied from `Eval.scala:19-21`.

```scala
    // the drive's spelling for the erased operation types (Eval.scala:19-21)
    private type IX[_]
    private type OX[_]
    private type EX <: ArrowEffect[IX, OX]
```

### 1.4 `pushValue`, `push`, and the entry points

```scala
        /** A value position. It holds exactly the `<` union (Pending.scala:10): a settled value, a node,
          * or a `Nested` payload. An `Arrow` is not an arm of that union and never appears here.
          */
        @tailrec private def pushValue(v: Any): Unit =
            v match
                case n: Nested[?] => pushValue(n.value)
                case k: Kyo[?, ?] => push(new Node(k))
                case _            => ()

        private def push(item: Item): Unit =
            if !((item: AnyRef) eq Arrow.Id) then
                if work.size == MaxFrames then
                    // the innermost pending steps are the ones a reader looks at first,
                    // so a full worklist gives up its outermost entry, not the new one
                    discard(work.removeLast())
                    dropped += 1
                end if
                discard(work.prepend(item))
            end if
        end push

        /** A value-position node: the drive was about to run it. */
        def node(k: Kyo[?, ?]): Unit =
            push(new Node(k))
            drain()

        /** An arrow-position item: a drive-stack entry, or a continuation the drive folded. */
        def arrow(a: Arrow[?, ?, ?]): Unit =
            push(a)
            drain()
```

`Nested` here is `kyo.proto.Nested` (`Nested.scala:5`), a different class from the
`kyo.kernel.internal.Nested` the old file imports at `EffectTrace.scala:8`. `Nested.lift` wraps only
`Kyo | Nested` payloads (`Nested.scala:9-12`), so the recursion depth is the user's nesting depth; it is
`@tailrec` so it costs no Java stack regardless.

The `(item: AnyRef)` ascription on the `eq` is there because `Item` is a union and its least upper bound
is `Object`; if the compiler accepts the bare `item eq Arrow.Id`, drop the ascription (cast ladder rung 1,
`SKILL.md:74-76`). `Arrow.Id` names the `private[kyo] object Id` (`Arrow.scala:106`), visible from
`kyo.proto` and already spelled this way at `Stack.scala:34` and `:48`.

### 1.5 `entries`: the proto stack indexes the other way

```scala
        /** The pending continuation held on the drive stack, innermost first. Index 0 is the entry the
          * drive would apply next: `push` decrements `head` (Stack.scala:39,43), `pop` reads at `head`
          * (:66), `find` scans upward from 0 (:83-91), and `dump` folds `pos-1` down to 0 so that entry 0
          * ends up leftmost in the chain (:97-119). Entries the cap keeps the sweep from reaching are
          * counted as dropped.
          */
        def entries(stack: Stack): Unit =
            val n = stack.size
            @tailrec def loop(i: Int): Unit =
                if i < n then
                    if full then dropped += n - i
                    else
                        arrow(stack.entry(i))
                        loop(i + 1)
            loop(0)
        end entries
```

This is F6. The old kernel's `push` appends at `top` (`kyo/kernel/internal/Stack.scala:28-31`), so high
index is innermost there and its sweep counts **down** from `size - 1` (`EffectTrace.scala:191-198`). The
proto is the mirror image, so the sweep **ascends** and the drop accounting becomes `n - i`. The first
proposal copied the descending loop, which would have emitted outermost-first and failed the ordering
assertions at `EffectTraceTest.scala:99-102`, `:157-162`, `:211-217`.

Each entry goes through `arrow`, i.e. push-and-drain per entry, not push-all-then-drain: pushing a large
stack wholesale would hit the worklist cap and evict real frames before any of them were emitted.

No null is ever read. `dump` nulls the slots it folds and then advances `head` past them
(`Stack.scala:106,118`), and `pop` does the same (`:68-70`), so every index in `0 until size` is live at
the time the catch runs, which is before the `finally` releases the stack (`Eval.scala:151-153`).

### 1.6 `isPlumbing`

```scala
    /** The kernel's own frames, which say only that a computation was being evaluated.
      *
      * The per-site `Arrow.Transform` a user's `map` mints is an anonymous class in the user's own
      * compilation unit carrying the user's line numbers, so it is the most informative physical frame
      * present and is never filtered. That is also why the `Effect` test is exact: the ported suite is
      * `kyo.proto.EffectTraceTest`, and a `startsWith("kyo.proto.Effect")` would filter the suite's own
      * frames along with the kernel's.
      */
    private def isPlumbing(e: StackTraceElement): Boolean =
        val cls = e.getClassName
        cls.startsWith("kyo.proto.Eval") ||
        cls.startsWith("kyo.proto.Arrow") ||        // Arrow, Arrow$Chain, Arrow$Id, ArrowEffect
        cls.startsWith("kyo.proto.Stack") ||
        cls.startsWith("kyo.proto.Handler") ||
        cls.startsWith("kyo.proto.Safepoint") ||
        cls.startsWith("kyo.proto.Nested") ||
        cls.startsWith("kyo.proto.Loop") ||
        cls.startsWith("kyo.proto.Pending$package") || cls.startsWith("kyo.proto.$less") ||
        cls == "kyo.proto.Effect" || cls.startsWith("kyo.proto.Effect$")
    end isPlumbing
```

**The inline drive weakens this list, and the plan does not paper over it.** With `Eval.apply` inline
(`Eval.scala:30`), the drive's own frames no longer appear under `kyo.proto.Eval`: `loop` expands into the
caller and its physical frames carry the *user's* class name. The `kyo.proto.Eval` entry above therefore
filters nothing at a call site that expanded the drive, and the physical remainder of a spliced trace is
noisier than it was. There is no correct fix inside `isPlumbing`: filtering by a mangled local-method
name would be guesswork, and filtering by the user's own class would delete the frames the whole design
exists to keep. **Raised, not executed** (`SKILL.md:342-345`): if the noise matters, the decision is
about `inline` on `apply`, which is the owner's in-flight change and a public-surface and compile-cost
call (`SKILL.md:468-473`), not a patch here.

This is F16 with the exactness added. `Pending.scala` declares a top-level `opaque type <` and `object <`
(`Pending.scala:10,12`), which compile to `kyo.proto.Pending$package` and an encoded `kyo.proto.$less$`,
not to `kyo.proto.Pending`; both entries are defensive, since every member of that file is `inline` and
expands into the caller. `Safepoint` is on every delivery path (`Pending.scala:28-34`) and was missing
from the first proposal's list.

### 1.7 File placement and visibility

`kyo-kernel2/shared/src/main/scala/kyo/proto/EffectTrace.scala`, package `kyo.proto`, with:

```scala
final class EffectTrace extends Exception(null, null, false, false):
    private[kyo] var elements: Array[StackTraceElement]        = EffectTrace.noElements
    private[kyo] var dropped: Int                              = 0
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent
    ...

object EffectTrace:
    def attach(...): Unit = ...
    def splice(ex: Throwable): Unit = ...
    // everything else private
```

The package is forced, and not by exhaustivity (F3): `Arrow.Chain` is `private[proto]`
(`Arrow.scala:77`), and the new `Stack.entry` is `private[proto]` (section 3).

**The class and object are public, unlike the old file's `private[kyo]` (`EffectTrace.scala:22,36`), and
that is a decision `Eval.apply` being `inline` forces.** An inline method's body is re-typechecked at
every expansion site, including sites outside package `kyo`, so every symbol the drive names has to be
reachable there. The rest of the proto package is already public for the same reason (`Stack`,
`Safepoint`, `Handler`, `Kyo`, `Arrow`), and `attach` / `splice` are the only two members the drive
names. The carrier's mutable fields stay `private[kyo]`: no inline method touches them, `installInto`
writes them from inside the object, and the ported suite in `kyo.proto` reads them.

If `Eval.apply` stops being `inline`, narrow both to `private[kyo]` and match the old file. The existing
`kyo.kernel.internal.EffectTrace` is not touched; the two coexist.

### 1.8 Why this shape is composition, not machinery

Worth stating, because `SKILL.md:12-17` reads a walker over private arrays as evaluator machinery unless
the equation is on the page (F19). The reconstruction is a **fold over the pending continuation of the
composed value**. It introduces no node kind, no combinator, and no evaluator behaviour: each arm of
`drain` is the read-only image of a drive arm, one for one.

| walk arm | drive arm it mirrors |
|---|---|
| `Node`/`Kyo.Defer`: `push(contB); push(contA); pushValue(value)` | `Eval.scala:36-38` |
| `Node`/`Kyo.Handle`: `push(cont); push(handler); pushValue(value)` | `Eval.scala:126-128` |
| `Node`/`Kyo.Suspend`: `frame(s.frame); push(s.cont)` | `Eval.scala:40` plus the dispatch |
| `Handler`: `region(h.tag)` | the handler entry `find` resolves, `Eval.scala:41-44` |
| `Chain`: `push(c.b); push(c.a)` | `Stack.fill`, `Stack.scala:52-63` |
| `entries` ascending from 0 | `dump`'s fold, `Stack.scala:97-119` |

The walk's only original content is the ordering rule (innermost first) and the cap.

---

## 2. The wiring in `kyo/proto/Eval.scala`

### 2.1 The constraint

`Eval.scala:33` is `@tailrec def loop(curr: Any < Nothing)`. **A `try` around a `loop(...)` call is a
compile error**, not a silent loss of stack safety (F5). Every site therefore takes the same shape:
compute the folded continuation into a `val`, run the user call inside a `try` that binds its result, then
tail-call `loop` outside the `try`. The tests that would catch a mistake here are `EvalTest.scala:55`
("a long map tower evaluates in bounded stack") and `:61` ("deep recursion through map pays rescues
only"); `deepRecursionPaysRescuesOnly` is a JMH row, not a pin.

### 2.2 The six sites, and why each is needed

| # | line | user code that can throw | value passed | folded arrow passed |
|---|---|---|---|---|
| S1 | `:42` | `bug` on an unhandled suspension | `kyo` (node) | `Arrow.id` |
| S2 | `:46` | a `handleCont` clause | `kyo` (node) | `k = dump(pos)` |
| S3 | `:48` | a `handleLoop` clause | `kyo` (node) | `Arrow.id` (no dump yet) |
| S4 | `:84` | a `handleLoopState` clause | `kyo` (node) | `Arrow.id` (no dump yet) |
| S5 | `:135` | a stateful region's `done` | `h` (arrow) | `Arrow.id` (no dump) |
| S6 | `:137` | a region's `done` | `h` (arrow) | `tail = dump()` |
| S7 | `:139` | a folded `Chain` application | `c` (arrow) | `tail = dump()` |
| S8 | `:141` | any arrow application: this is where a user `map` body throws | `head` (arrow) | `tail = dump()` |

Plus the boundary at `:149-154`, which only splices.

Every path out of `loop` that enters user code is in that table. `stack.dump`, `Stack.push`, `find`,
`Safepoint.*` and `Tag.show` are kernel code; a throw from one of them arrives with no frames and is
spliced, which is correct.

S5 through S8 pass the **popped entry**, in the arrow role, because `stack.pop()` at `:133` has already
removed it: it is the innermost pending thing and nothing else holds it. This is the same choice the old
kernel made at `kyo/kernel/internal/Eval.scala:273,280,287,298`.

S6/S7/S8 additionally pass `tail`, because `stack.dump()` has already consumed those entries off the stack
(`Stack.scala:106,118`) and the sweep can no longer reach them. This is the whole of F10.

The three clause sites pass the `Kyo.Suspend`. At S3/S4 no dump has happened, so the stack still holds
everything; at S2 the dump has happened and `k` carries it. In all three the suspension's own `cont` is
either `Arrow.id` (`ArrowEffect.scala:22`, dropped by `Stack.push` at `Stack.scala:34` and by `Builder.push`) or the
node itself (`ArrowEffect.scala:37`, one frame in the arrow role), so passing the node duplicates nothing.
A user subclass of the public `Kyo.Suspend` (`KyoInternal.scala:19`) with a third continuation shape would
see its continuation's frames twice, once from the node and once from the stack; that is cosmetic, it is
on a path the kernel never mints, and it is recorded here rather than guarded.

### 2.3 The two `attach` overloads

```scala
    /** The drive was about to run `node`: the node, everything it composes, the continuation the drive
      * folded, and the drive stack are all pending.
      */
    def attach(ex: Throwable, node: Kyo[?, ?], next: Arrow[?, ?, ?], stack: Stack): Unit =
        reconstruct(ex) { builder =>
            builder.node(node)
            builder.arrow(next)
            builder.entries(stack)
        }

    /** The drive applied `entry` with `next` folded behind it. `entry` is walked in its arrow role: the
      * drive already took it apart onto the stack, so its node payload, if it has one, is behind the
      * failure rather than ahead of it.
      */
    def attach(ex: Throwable, entry: Arrow[?, ?, ?], next: Arrow[?, ?, ?], stack: Stack): Unit =
        reconstruct(ex) { builder =>
            builder.arrow(entry)
            builder.arrow(next)
            builder.entries(stack)
        }
```

Overload resolution is unambiguous at every site: the static type at S1-S4 is
`Kyo.Suspend[IX, OX, EX, CX, A, S]`, which is not an `Arrow`, and at S5-S8 it is `Handler`,
`Arrow.Chain` or `Arrow`, none of which is a `Kyo`. No call site has a static type that is both.

### 2.4 The edits, site by site

**S1** (`Eval.scala:42`):

```scala
                    if pos < 0 then
                        try bug(s"unhandled suspension: ${kyo.tag}")
                        catch
                            case ex: Throwable =>
                                EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                throw ex
                    else
```

**S2** (`Eval.scala:45-46`):

```scala
                            case h: HandlerCont[IX, OX, EX, AX, ?, S] @unchecked =>
                                val k = stack.dump[OX[CX], AX, EX & S](pos)
                                val next =
                                    try h.run(kyo.input, k)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, kyo, k, stack)
                                            throw ex
                                loop(next)
```

**S3** (`Eval.scala:47-48`, the head of the arm; the `out match` body below it is unchanged):

```scala
                            case h: HandlerLoop[IX, OX, EX, AX, BX, S] @unchecked =>
                                val out =
                                    try h.run(kyo.input)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                            throw ex
                                out match
                                    case clause: Kyo[Loop.Outcome[OX[CX] < (EX & S), BX], S] @unchecked =>
                                        ...unchanged...
                                    case o =>
                                        ...unchanged...
```

**S4** (`Eval.scala:82-84`): identical shape around `h.run(s, kyo.input)`, binding `val out` and matching
it afterwards.

**S5** (`Eval.scala:134-135`):

```scala
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val next =
                                    try h.apply(s.getOrElse(h.initialState), r.asInstanceOf[AX])
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, h, Arrow.id[Any], stack)
                                            throw ex
                                loop(next)
```

**S6** (`Eval.scala:136-137`):

```scala
                            case h: Handler[EX, AX, BX, S] @unchecked =>
                                val tail = stack.dump[BX, Any, EX & S]()
                                val next =
                                    try h(curr.asInstanceOf[AX < (EX & S)], tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, h, tail, stack)
                                            throw ex
                                loop(next)
```

**S7** (`Eval.scala:138-139`):

```scala
                            case c: Arrow.Chain[Any, ?, Any, EX & S] @unchecked =>
                                val tail = stack.dump[Any, Any, EX & S]()
                                val next =
                                    try c(curr, tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, c, tail, stack)
                                            throw ex
                                loop(next)
```

**S8** (`Eval.scala:140-141`):

```scala
                            case head =>
                                val tail = stack.dump[Any, Any, EX & S]()
                                val next =
                                    try head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, head, tail, stack)
                                            throw ex
                                loop(next)
```

`Arrow` is contravariant in its input (`Arrow.scala:7`), so `Arrow[Any, Any, EX & S]` conforms wherever
`Arrow[X, C, EX & S]` is wanted for the capture `X` at S7 and S8. The explicit type arguments on
`dump(pos)` at S2 are the ones `Eval.scala` itself used before the `lower` removal inlined them. If the
compiler rejects a spelling, ascribe; do not cast (`SKILL.md:72-78`).

**The boundary** (`Eval.scala:149-154`):

```scala
        try
            loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
        catch
            case ex: Throwable =>
                EffectTrace.splice(ex)
                throw ex
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
```

**Decision on the review's open question (F22): the boundary does not attach.** With S1-S8 wired, every
throw that carries frames has already had them reconstructed, and a boundary attach would sweep the
residual stack a second time, appending a duplicate tail to the carrier. The boundary's only job is
`splice`, which rewrites the exception's own trace with the synthesized frames leading. The stack is
still live in the `catch`, because `Stack.release` runs in the `finally`, which executes after it; that
part of the first proposal's reasoning was right, its conclusion was not.

A nested drive (a second `Eval` inside a map body, `EffectTraceTest.scala:92-103`) works by accumulation:
the inner drive's attaches and splice run first, the exception crosses into the outer drive's S8 catch,
and the outer attach appends. `carrier.physical` is cached at the first splice
(`EffectTrace.scala:96-101`), so the second splice re-splices against the original physical trace rather
than against an already-spliced one.

### 2.5 What `inline def apply` changes for this wiring

`Eval.apply` is `inline` as of `Eval.scala:29-30`, so the drive, `loop`, and everything section 2.4 adds
are expanded into every `Eval(...)` call site. Four consequences, none of which change the design and all
of which the implementor has to hold:

1. **Visibility.** Handled in §1.7: `EffectTrace` and its two entry points are public.
2. **Code size is now per call site.** Eight `try` regions and eight `val`s multiply by the number of
   `Eval(...)` sites in the whole codebase, not by one. The module already carries bytecode-shape pins
   (`kyo-kernel2/jvm/src/test/scala/kyo/kernel/PendingBytecodeTest.scala` and
   `ArrowEffectBytecodeTest.scala`) and there is a `kyo-compile-bench` module; both are named in the
   verification plan for this reason.
3. **`Eval$::loop` no longer exists as a JIT unit.** `SKILL.md:260-262` records the drive at ~1500 bytes
   reporting `inlining prohibited by policy`, which is where the first proposal's "the try regions cannot
   change an inlining verdict" argument came from. With an inline drive that reasoning does not transfer:
   the expanded `loop` is a local method in the caller, with the caller's budget. **The argument is
   therefore withdrawn and replaced by the measurement**; the `PrintInlining` rung
   (`SKILL.md:238`) is part of the bracket rather than optional, and the verdict is read for the expanded
   `loop` in the benchmark class, not for `kyo.proto.Eval`.
4. **`@tailrec` still holds after expansion**, so the compile-error property of section 2.1 is unchanged:
   a `try` around a `loop(...)` call fails the build at the definition site, before any expansion.

None of this argues for or against `inline` on `apply`; that is the owner's in-flight change and its own
decision (`SKILL.md:468-473`). What the port owes it is honesty about which of its own claims depended on
the drive being a separate method.

---

### 2.6 One thing found in passing, deliberately not fixed here

`Stack.borrow()` runs at `Eval.scala:31`, but the `try/finally` that releases it starts at `:149`, after
`Safepoint.get()` and `Safepoint.save(slot)` at `:147-148`. A throw from either leaks the borrowed stack
back to nothing (the pool never sees it again). That is a pre-existing defect unrelated to this port; the
fix is to move `Stack.borrow()` inside the `try` or widen the `try` to `:147`. **Recorded, not executed**:
it touches the drive's entry and belongs in its own change with its own reasoning.

---

## 3. The new accessor

One addition to `kyo/proto/Stack.scala`, beside `state(i)` (`:74`) and `handler(i)` (`:80`):

```scala
    private[proto] def entry(i: Int): Arrow[?, ?, ?] = entries((head + i) & mask)
```

Justification: the sweep needs an indexed read of a stack slot without knowing its kind. `handler(i)`
casts to `Handler` and `state(i)` reads the parallel array; neither can serve. The spelling
`(head + i) & mask` is copied verbatim from both, so the three read the same way and a change to the
ring-buffer indexing breaks or fixes all three together.

Scope: `private[proto]` rather than public, because the walk is the only consumer and a raw entry read is
not something the kernel wants to offer. This is narrower than `handler`/`state`, which are public for
historical reasons; if a later change makes those `private[proto]` too, `entry` is already correct.

**No other visibility change is needed.** `Arrow.Chain` (`Arrow.scala:77`), `Arrow.Transform`
(`:68`), `Arrow.Id` the object (`:106`), `Handler` and its `tag` (`Handler.scala:7-8`), `Kyo` and its three
children (`KyoInternal.scala:9-30`), `Nested.value` (`Nested.scala:5`), `Stack.size` (`Stack.scala:20`)
and `Frame.internal` are all already reachable from a file in package `kyo.proto`.

---

## 4. The test port

Source: `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala`, 300 lines, 15 live
cases plus 3 commented.

**The corpus is currently red** (F12): `kyo.kernel.internal.Eval.apply` is `???`
(`kyo/kernel/internal/Eval.scala:21,24`), so every case throws `NotImplementedError` and
`intercept[RuntimeException]` does not catch an `Error`. Porting it is **authoring tests, not
transcribing green ones**, and every expectation below is derived from the proto's own shapes.

Targets:

- `kyo-kernel2/shared/src/test/scala/kyo/proto/EffectTraceTest.scala` (the portable corpus and the new
  pins).
- `kyo-kernel2/jvm-native/src/test/scala/kyo/proto/EffectTracePhysicalTest.scala` (the cases that assert
  the shape of the *physical* stack trace).

### 4.1 Fixture

```scala
package kyo.proto

import kyo.Const
import kyo.Tag
import kyo.discard
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

class EffectTraceTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // the fused node: the operation carries its own continuation (ArrowEffect.scala:33,37)
    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    def dropSay[A, S](v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue((): Unit < Any), a => a)
```

`answerAsk` and `dropSay` are the row-generic forms from `EvalTest.scala:21-22`; the old suite's
non-generic `answerAsk`/`answerSay` and generic `answerAskIn`/`dropSay` collapse into one pair each,
because the proto's `handleLoop` is already row-generic. `carrier`, `methods`, `classes`, `Boom`,
`innerStep`, `outerStep`, `stepA`, `stepB`, `deepChain` port verbatim (`EffectTraceTest.scala:33-57`).

### 4.2 Cases that port directly

Expectations unchanged; only the package, the effect definitions and the handler constructor change.

| old | case | why it holds in the proto |
|---|---|---|
| `:59` | a throw in a handler clause carries the suspension and its region | S3 fires with the stack intact at `[mapArrow, handler]`; `node(kyo)` emits the `ask` site, the sweep emits the `map` frame then the `Ask` region. The `getStackTrace.head` assertion at `:71` moves to the physical file (4.5). |
| `:74` | a throw in a continuation frame names its site | S8 with `head = boomAt`'s arrow. |
| `:84` | an unhandled suspension arrives enriched | S1; the node role emits `ask`. |
| `:92` | nested drives accumulate their regions innermost first | inner drive attaches `[map, Ask]`, outer drive attaches `[map, Say]`; regions come out Ask before Say. |
| `:105` | `NoStackTrace` keeps its carrier and its empty stack | `splice` skips a `NoStackTrace` (`EffectTrace.scala:91`). |
| `:114` | a chain past the cap reports the drop | the 100-deep tower exceeds `MaxFrames = 64`. |
| `:129` | a fatal error passes through untouched | `reconstruct`'s `NonFatal` test (`:74`). |
| `:146` | name the call site's callee and the enclosing definition | `map` is an inline extension in both kernels; `calleeName` is `map`, `callerName` is `innerStep`, `className` becomes `kyo.proto.EffectTraceTest`. |
| `:164` | skip the internal frame placeholder | `frame` skips `Frame.internal` (`:160`); the emitting adapters and `Arrow.Chain`/`Arrow.Id` all carry it. |
| `:170` | a suspension boundary the physical stack cannot cross | **the effect-frame half ports; the physical half moves** (4.5). |
| `:211` | one element per handler tag, innermost first | traced in full below. |
| `:219` | names each region exactly once | each handler is one stack entry (`Eval.scala:127`) and is emitted once. |
| `:234` | a fatal error keeps its original stack trace | S8 attach is a no-op on a fatal. |
| `:246` | the cap stops at exactly the cap | `deepChain(200)` folds 199 entries into a chain; emission stops at 64. |
| `:254` | bounds a chain far deeper than the Java stack | `deepChain(1000000)`; `dump()`'s `reach` boundary (`Stack.scala:124`, `Safepoint.period()/2`) leaves the rest on the stack, and `entries` counts it all as dropped. |
| `:274` | the carrier renders the frames as a message | `innerStep` and `outerStep` are both emitted. |

Worked trace for `:211`, since it is the one that exercises arm order, the ascending sweep and the folded
chain at once. Program `dropSay(answerAsk(1)(outerStep(ask).map(v => say("x").map(_ => v))))`. At the
throw the stack is `[AskH, SayH]`, `head` is `innerStep`'s arrow, and `tail` is
`Chain(outerStepArrow, gArrow)` folded by `dump()` stopping at the first handler (`Stack.scala:124`). The
walk emits `innerStep`, then `outerStep`, then the outer `map`, then `Ask`, then `Say`: Ask before Say,
each once.

### 4.3 Cases whose expectations must be re-derived

**`:139` "are carried through a drive" and `:157` "run innermost first": drop the `ask` assertion, and add
a fused sibling.**

The old kernel's `ask.map(f)` fuses into a single `SuspendWith` node that carries both the operation's
frame and the continuation, and that node is what the drive applies at delivery, so `ask` rides along in
the trace. The proto's `map` over a suspension does not fuse: it mints `Kyo.Defer{value = ask, contA =
mapArrow}` (`Pending.scala:25-26`, `Effect.scala:15-22`). By the time the map body throws, the suspension
has been answered and is behind the drive; it is not part of the pending continuation. Asserting `ask`
there would be asserting a property of the old node layout.

What the proto pins instead, in two cases:

```scala
        "are carried through a drive" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            assert(methods(ex).contains("innerStep"))
            assert(methods(ex).contains("outerStep"))
            // `ask` is answered before the map body runs, so the suspension is behind the drive,
            // not ahead of it. The fused form below is where the operation's own frame is pinned.
        }

        "a fused suspension carries the operation's own frame" in {
            def fusedStep: Int < Ask                 = askWith(_ => throw new Boom)
            def aroundFused(v: Int < Ask): Int < Ask = v.map(_ + 1)
            val ex = intercept[Boom](Eval(answerAsk(1)(aroundFused(fusedStep))))
            val ms = methods(ex)
            assert(ms.contains("fusedStep"))
            assert(ms.contains("aroundFused"))
            assert(ms.indexOf("fusedStep") < ms.indexOf("aroundFused"))
            assert(classes(ex).exists(_.startsWith("askWith @ ")))
        }
```

`suspendWith` pushes its own node as its continuation (`Eval.scala:40` with `ArrowEffect.scala:37`), so at
delivery `stack.pop()` returns the node itself and S8 passes it as an arrow: one frame, the operation's
own site, no re-entry. This case is simultaneously the re-derived coverage and the F13 pin for a
self-referential `Kyo.Suspend`.

`:157` becomes `assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))`; the fused case above carries
the ordering assertion that involved `ask`.

**`:195` "carries the steps after a budget rescue": re-derive the rescue.**

The old case builds it with the by-name `Effect.defer` (`kyo/kernel/Effect.scala:11`), which the proto
does not have. The proto mints a rescue Defer at `Pending.scala:29-30` when `Safepoint.enter` fails, which
a strict recursion deeper than `Safepoint.period()` produces:

```scala
        "carries the steps after a budget rescue" in {
            def boomHere: Int < Any = (0: Int < Any).map(_ => (throw new Boom): Int)
            def deep(i: Int): Int < Any =
                if i == 0 then boomHere else (0: Int < Any).map(_ => deep(i - 1))
            val ex = intercept[Boom](Eval(deep(600)))
            assert(methods(ex).contains("deep") || methods(ex).contains("boomHere"))
        }
```

The first 512 levels run strictly during argument evaluation and park into `Effect.defer(v, arrow, next)`;
`Eval` then resumes them, and the remaining levels run inside the drive to the throw, which lands in S8
with `head` carrying the user's `map` frame.

### 4.4 The case that cannot be ported

**`:188` "a Defer bounce carries the deferred site".** It calls `Effect.defer[Int, Any](throw new Boom)`
and asserts `classes(ex).exists(_.startsWith("defer @ "))`. The proto's `Effect` has only
`defer(v, next)` and `defer(v, a, b)` (`Effect.scala:9,15`), and `Kyo.Defer` declares no `frame`
(`KyoInternal.scala:13-17`), so there is nothing to produce a `defer @ ` element. **Port it commented,
with the note**, per the standing rule recorded in `WORK.md:176-180` that cases for APIs this kernel lacks
are kept as commented code so the specification survives in the file that would implement it.

Adding a by-name `Effect.defer` to the proto to make the case pass is **not in scope and should not be
done**: a new combinator is a surface decision (`SKILL.md:24-28`), and adding one to satisfy a ported test
is the inverted order. If the proto wants it, it wants it for composition reasons, and that is its own
item.

The two `Effect.catching` cases already commented at `:285-298` stay commented, unchanged.

### 4.5 The physical-trace cases move out of `shared`

`kyo-kernel2` cross-builds JVM, JS, Native and Wasm (`build.sbt:772-803`), and the corpus lives in
`shared/src/test`. Assertions about the *physical* stack trace are not portable: Scala.js method names are
mangled and its `getStackTrace` is source-map shaped. The module already carries a
`jvm-native/src/test/scala` tree, wired by `CrossType.Full`'s partially-shared directories (confirmed in
`.bloop/kyo-kernel2JVM-test.json`, which lists
`kyo-kernel2/jvm-native/src/test/scala`), with `SafepointUnstartedThreadTest.scala` as precedent.

New file `kyo-kernel2/jvm-native/src/test/scala/kyo/proto/EffectTracePhysicalTest.scala`, carrying:

- the physical half of `:170`: `assert(!raw.exists(_.getMethodName == "around"))` alongside the effect
  frames, which is the point of the case (the effect trace names what the physical trace cannot).
- `:226` "the synthesized frames lead the spliced trace": `es.take(cs.length).sameElements(cs)` depends on
  `setStackTrace` fidelity and on a non-empty physical remainder.
- the `getStackTrace.head.getFileName` assertion lifted out of `:59`.

The file name keeps the `EffectTrace` prefix, per the test-naming rule in `CLAUDE.md`.

**Verify rather than assume the Native half.** If Scala Native's physical traces do not carry Scala method
names for these frames, the file moves to `jvm/src/test` and the reason is recorded. Deciding that from a
run is part of the task, not a reason to skip it.

### 4.6 New pins the port owes

Five cases, all in the shared file, three of them demanded by F13.

**(a) A fused suspension.** Section 4.3's "a fused suspension carries the operation's own frame".
Pins `ArrowEffect.scala:37`.

**(b) A fused region.** Pins `ArrowEffect.scala:148/182/218` (`cont = this` on a `Kyo.Handle with
Arrow.Transform`) in the value role, which is where the ordering matters:

```scala
        "a fused region names the body, then the region, then the region's continuation" in {
            val fused: Int < Any =
                ArrowEffect.handleLoopWith(Tag[Ask], innerStep(ask))(
                    [C] => _ => Loop.continue(1: Int < Any),
                    a => a
                )(_ + 1)
            val ex = intercept[Boom](Eval(fused))
            val els = carrier(ex).get.elements.toList
            assert(els.exists(_.getMethodName == "innerStep"))
            assert(els.exists(_.getMethodName == "handle"))
            assert(els.indexWhere(_.getMethodName == "innerStep") <
                els.indexWhere(_.getMethodName == "handle"))
        }
```

**(c) An emitting clause.** A clause that suspends produces the self-referential `Kyo.Defer with
Arrow.Transform` adapter (`Eval.scala:53-69`), which the drive pushes as a stack entry at `:37`:

```scala
        "a throw under an emitting clause walks without looping" in {
            val v: Int < Any =
                dropSay(
                    ArrowEffect.handleLoop(Tag[Ask], innerStep(ask))(
                        [C] => _ => say("e").map(_ => Loop.continue(1: Int < Any)),
                        a => a
                    )
                )
            val ex = intercept[Boom](Eval(v))
            assert(methods(ex).contains("innerStep"))
            assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
        }
```

The assertion that matters most is that the case terminates at all: under the first proposal's arm order
this program is an infinite loop inside a catch with an exception in flight. The `<internal>` assertion
pins that the adapter contributes `Frame.internal` and is skipped.

**Accepted limit, recorded here (F15):** a throw *inside* an emitting clause's own body carries the
clause's frames and the outer stack but **not** the region it serves, because the drive pops the handler
for the clause's duration (`Eval.scala:51`, `:87`). That removal is the semantics, not an oversight: the
clause's row places it outside its own region (`SKILL.md:35-41`). The case above asserts what does appear
rather than pretending the label is recoverable.

**(d) Multi-shot replay** (F14). `EvalCaptureTowerTest.scala:46-53` applies a captured continuation twice:

```scala
        "a throw in the second application of a multi-shot capture names each region once" in {
            val r: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], innerStep(ask))(
                    [C] => (_, cont) => cont(1).map(_ => cont(2)),
                    a => a
                )
            val ex = intercept[Boom](Eval(r))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
        }
```

**(e) A failure of the walk itself.** This is the case the old suite could not write, commented out at
`EffectTraceTest.scala:261-272` because the old kernel's `Arrow.Suspend` could not be subclassed from a
test. **The proto makes it possible for free**: `Kyo.Suspend` is a public abstract class
(`KyoInternal.scala:19`), so a throwing subclass compiles from a `kyo.proto` test.

```scala
        "a failure of the walk itself leaves the original failure travelling" in {
            // a node whose frame cannot be read: describing a failure must never replace the
            // failure being described
            val unreadable =
                new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any, Int, Any]:
                    def tag   = Tag[Ask]
                    def input = ()
                    def frame = throw new IllegalStateException("frame read failed")
                    def cont  = Arrow.id[Int]
            val ex = intercept[Throwable](Eval((unreadable: Int < Ask).asInstanceOf[Int < Any]))
            assert(ex.getMessage.contains("unhandled suspension"))
            assert(carrier(ex).toList.flatMap(_.elements.toList).isEmpty)
        }
```

The unhandled-suspension path (S1) is what reaches `frame` in the node role, so the bad read happens
inside the walk, `reconstruct`'s inner catch swallows it (`EffectTrace.scala:80`), and the original
failure travels with an empty carrier. Un-commenting this is a genuine win of the port and it is the
pinning test the swallow-all concession has always lacked (F20).

---

## 5. Verification

### 5.1 Compile and suite

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

sbt --batch 'kyo-kernel2JVM/clean' 'kyo-kernel2JVM/compile'    # the clean batch build, mandatory
sbt 'kyo-kernel2JVM/test'
sbt 'kyo-kernel2JS/test' 'kyo-kernel2Native/test' 'kyo-kernel2Wasm/test'
```

The **clean batch build is not optional** (F18, `SKILL.md:123-124`). The module compiles in a fragile
macro-suspension equilibrium and incremental green is not clean green; a new summon inside a core,
inlined-from file deepens the cascade until dotty crashes with `StaleSymbolException`, and only on the
clean build. `EffectTrace.scala` summons no `CanLift` evidence and does no lifting, and the `Eval.scala`
edits add no `map` or conversion, so no new suspension is expected; expected is not verified.

Because the drive is `inline` (§2.5), the wiring's cost is also a code-size and compile-time cost at
every `Eval(...)` site. Run the module's own bytecode-shape pins and record their numbers before and
after:

```sh
sbt 'kyo-kernel2JVM/testOnly kyo.kernel.PendingBytecodeTest kyo.kernel.ArrowEffectBytecodeTest'
```

If a pinned size moves, that is a result to report with the benchmark table, not a pin to re-baseline.

Record the JS/Native/Wasm baseline **before** the change. If a platform is already red for unrelated
reasons, the comparison is baseline-to-after, and the pre-existing failures are reported, not skipped.

### 5.2 The rows the change reaches

`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/YetAnotherProtoBench.scala` defines **15 rows**:
`evalFixedOverhead`, `fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`,
`uncachedValuesPayBoxingOnly`, `deepRecursionPaysRescuesOnly`, `suspensionBaseline`,
`suspensionFusesContinuation`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`,
`nestedPayloadsUnwrapInMaps`, `statefulAnswersPaySuccessor`, `idleHandlerAddsNothing`,
`trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`, `continuationBodiesFuse`.

**All 15 are mandatory** (F21, `SKILL.md:178-191`). The wiring touches the drive head, every delivery arm,
the clause arms and the stateful arms, so every row exercises changed code. A hand-picked subset is for
hunting a mechanism and never becomes the evidence.

### 5.3 The precedent this change has to answer

`SKILL.md:490-491` records the project **deleting** hot-loop state that existed only to enrich an
exception at one boundary, with no test pinning it. The answer, stated plainly so a reviewer does not have
to reconstruct it:

- **This change adds no state to the hot loop.** No variable is written per iteration; nothing is
  maintained for the cold consumer. The reconstruction reads only what the drive already holds at the
  moment of the throw.
- **What it does add on the non-throwing path is two things**: an exception-table region per delivery arm,
  and the hoist of `stack.dump()` from an argument position into a `val`. The dump was already computed
  exactly once per delivery; the hoist is a register-level change, not a semantic or allocation one.
- `EffectTrace.attach` is a plain `def`, not `inline`, so each site gains a try region plus one static
  call and nothing expands. **The stronger claim the first proposal wanted here is withdrawn** (§2.5):
  `SKILL.md:260-262` records `Eval$::loop` never inlining, but with `Eval.apply` inline there is no
  `Eval$::loop`; the expanded drive is a local method in the caller with the caller's budget. Whether a
  verdict moves is what `PrintInlining` answers, not what this paragraph does.
- **The precedent is answered by measurement, not by this paragraph.** If a row regresses beyond the drift
  band, the state of the work is unfinished (`SKILL.md:159-176`), and the response is a named mechanism
  from the ladder, not a rationale.

### 5.4 The bracket

Run it through `kyo-kernel2/.claude/skills/kernel/bench-harness/`, not hand-written bash
(`SKILL.md:352-365`). Declare a **three-sha chain**, because two shas attribute nothing
(`SKILL.md:376-379`):

1. base (pre-port `HEAD`),
2. T2 (`EffectTrace.scala` added, `Stack.entry` added, nothing wired),
3. T3 (the six sites wired).

Step 1→2 must be flat by construction; if it is not, the file addition moved something and that is the
first thing to explain. Step 2→3 is the wiring's number, attributable because it is the only change in
that step.

Protocol: throwaway worktree (`git worktree add --detach`), design flips with
`git restore --source=<sha> --worktree`, never `git checkout <sha> -- <paths>`. Full class at `-f 1` on
both legs to screen, then `-f 3` on any row outside the band. The A/A null over the control legs is a
blocker if dirty. Report in the standard table: every row, score **and error** for both legs, delta,
status marker, sorted by delta, with `gc.alloc.rate.norm` beside the score. A flagged row with no
follow-up line is an unfinished report.

**Nothing about the wiring is removed on a bad number without the user's explicit call.** If a site's cost
is real, the options (moving the try out of line, narrowing which sites attach) are presented with both
mechanisms and the numbers, and the decision is the user's (`SKILL.md:195-205`).

---

## 6. Task list

Each task is one commit. "Must not break" names what a reviewer checks before moving on.

**T1. `Stack.entry`.**
- Changes: `kyo/proto/Stack.scala`, one `private[proto] def entry(i: Int)` beside `handler(i)` (`:80`).
- Verifies: `sbt --batch 'kyo-kernel2JVM/clean' 'kyo-kernel2JVM/compile'`.
- Must not break: nothing else; the method is unreferenced at this point. Confirm no existing call site
  was renamed or re-scoped.

**T2. `kyo/proto/EffectTrace.scala`, unwired.**
- Changes: one new file: carrier class, `MaxFrames`, `attach` x2, `reconstruct`, `splice`, `find`,
  `carrierOf`, `isPlumbing`, `Node`, `Item`, `Builder` with `frame` / `region` / `push` / `pushValue` /
  `node` / `arrow` / `entries` / `drain` / `installInto`.
- Verifies: clean batch build; `-Xprint-suspension` shows no new suspended file.
- Must not break: `kyo.kernel.internal.EffectTrace` is untouched; `kyo-kernel2JVM/test` is unchanged from
  its T1 result.

**T3. The wiring.**
- Changes: `kyo/proto/Eval.scala`, the eight edits of section 2.4 plus the boundary splice.
- Verifies: clean batch build (a `try` around a tail call is a compile error, so the build is the first
  gate); `sbt 'kyo-kernel2JVM/testOnly kyo.proto.*'`.
- Must not break: `EvalTest.scala:55` ("a long map tower evaluates in bounded stack") and `:61` ("deep
  recursion through map pays rescues only") stay green, which is the `@tailrec` proof at runtime;
  `EvalCaptureTowerTest`, `ArrowEffectTest` and `PendingTest` stay green.

**T4. The shared corpus.**
- Changes: `kyo-kernel2/shared/src/test/scala/kyo/proto/EffectTraceTest.scala` with the 4.2 cases, the 4.3
  re-derived cases, and the 4.4 commented case with its note.
- Verifies: `sbt 'kyo-kernel2JVM/testOnly kyo.proto.EffectTraceTest'`.
- Must not break: every re-derived expectation carries the one-line reason it changed, in the test file,
  not only here.

**T5. The pins.**
- Changes: the five cases of section 4.6, same file.
- Verifies: same command. The emitting-clause case is the one that hangs forever if the arm roles are
  wrong; run it with a timeout the first time.
- Must not break: the walk-failure case must fail for the right reason before it passes. Confirm by
  temporarily removing `reconstruct`'s inner catch and seeing the `IllegalStateException` replace the
  original failure; restore, then it passes.

**T6. The platform partition.**
- Changes: `kyo-kernel2/jvm-native/src/test/scala/kyo/proto/EffectTracePhysicalTest.scala`; the three
  physical assertions leave the shared file.
- Verifies: `sbt 'kyo-kernel2JVM/test' 'kyo-kernel2JS/test' 'kyo-kernel2Native/test' 'kyo-kernel2Wasm/test'`.
- Must not break: the shared file must pass on all four platforms. If Native fails on the physical
  assertions, the file moves to `jvm/src/test` and the commit message records why.

**T7. The bracket.**
- Changes: none in the tree; results into `bench-results/` and the report.
- Verifies: the standard table over all 15 rows, both legs, same session, plus the A/A null.
- Must not break: the tree is committed and clean before the bracket runs, and the bracket runs in a
  throwaway worktree.

---

## 7. Concessions, in the required shape

`SKILL.md:128-132`: justification, minimal scope, protective measure, pinning test. Four, none of which
the first proposal declared (F20).

| concession | justification | scope | protection | pin |
|---|---|---|---|---|
| the `Item` union worklist and its two type patterns | the walk must hold both node kinds and arrow kinds; `Any` would give up every static claim | one private type alias inside `Builder` | both matches are exhaustive without a catch-all: `Item` has two arms and `Kyo` is sealed with three children | any arm regression is a compile error, so the pin is the build |
| `private[proto] def Stack.entry` | the sweep needs an indexed read that is neither `handler(i)` nor `state(i)` | one method, package-private, same indexing spelling as its two siblings | narrower than the public siblings; no consumer outside the walk | `EffectTraceTest` region-ordering cases, which read the stack through it |
| `Node`, one allocation per value-position node | it is what carries the role the drive infers from position; the alternatives (two deques, a parallel role array) cost more and read worse | one private class, allocated only while an exception is already in flight, bounded by the worklist cap | the arrow role allocates nothing, and the arrow role is the common one | the fused-suspension, fused-region and emitting-clause pins |
| the swallow-all rule inside `reconstruct` | an exception raised while describing a failure would replace the failure, which is strictly worse than describing nothing | one `catch case failure if NonFatal(failure) => ()` (`EffectTrace.scala:80`) | fatal errors never enter the walk at all (`:74`) | **new**: the walk-failure case of 4.6(e), which the proto makes writable for the first time |

---

## 8. The review's 22 findings

| # | verdict | resolution |
|---|---|---|
| F1 | advisory nit | Fixed: `Handler` precedes `Chain` and the general `Arrow` arm, and the `Kyo.Handle` shadowing the nit points at is handled by the position rule, not by arm order (§1.1, §1.3). |
| F2 | advisory | Fixed: hybrid nodes do carry frames; the `Node` arm runs before the `Arrow` arm so no node arm is dead, and a hybrid in an arrow position emits its frame through the general `Arrow` arm (§1.1-1.3). |
| F3 | blocking | Fixed: worklist typed `Arrow[?, ?, ?] | Node`; **both** matches are exhaustive with no catch-all, which is more than the finding asked for. The `kyo/proto/` placement is justified by `Arrow.Chain` and `Stack.entry` being `private[proto]`, not by exhaustivity (§1.2, §1.7). |
| F4 | advisory | Fixed: `entry(i)` is documented as the untyped sibling of `handler(i)` (`Stack.scala:80`) and `state(i)` (`:74`), same `(head + i) & mask` spelling; no base index exists (§3). |
| F5 | advisory | Fixed: stated as a **compile error**, with `EvalTest.scala:55` and `:61` named as the runtime pins and `deepRecursionPaysRescuesOnly` correctly labelled a JMH row (§2.1). |
| F6 | blocking | Fixed: `entries` ascends from 0, drop accounting is `n - i` (§1.5). |
| F7 | blocking | Fixed: `pushValue` handles exactly the `<` union (`Nested`, `Kyo`, settled), `push` and the worklist are re-typed, and the innermost suspension frame is emitted through the `Node` role (§1.4). |
| F8 | advisory / would block at Stage 2 | **Not applicable under this wiring, deliberately**: no attach site is handed a `Loop.Outcome`, and the only outcome-typed value slot holds the clause, a `Kyo`. Adding the arms would be dead code. The note in §1.3 records where the fix goes if a site ever passes `curr`. |
| F9 | advisory | Fixed: one `Arrow.Id` guard in `push`, so it can never evict a real frame at the cap (§1.4). |
| F10 | blocking | Fixed: the staging is gone. Six inner attach sites, chosen precisely because the drive drains the stack into folded arrows (§0, §2.2). |
| F11 | blocking | Fixed: `:188` ported **commented** with the reason (no by-name `Effect.defer`, `Kyo.Defer` carries no frame) and an explicit refusal to add the combinator for a test; `:195` re-derived onto the budget-rescue path (§4.3, §4.4). |
| F12 | advisory | Accepted and acted on: the corpus is red because `kyo/kernel/internal/Eval.scala:21,24` is `???`. Every ported expectation in §4 is derived from the proto's shapes, and §4.3 lists the ones that change. |
| F13 | blocking | Fixed structurally, not defensively: position determines role, so a self-referential continuation slot is walked as an arrow and stops. The invariant is stated at the match, and three pins cover `suspendWith`, `handleLoopWith` and the emitting-clause adapter (§1.1, §4.6a-c). |
| F14 | advisory | Fixed: a pin on a throw in the second application of a multi-shot capture, asserting each region is named once (§4.6d). |
| F15 | advisory | **Accepted risk, documented**: a throw inside an emitting clause's body carries no region label, because the drive pops the handler for the clause's duration (`Eval.scala:51`, `:87`) and that removal is the semantics of a clause living outside its own region. The pin asserts what does appear (§4.6c). |
| F16 | advisory | Fixed: `isPlumbing` gains `Safepoint`, `Loop`, `Pending$package`, `$less`, and an **exact** `Effect` test, which matters because the ported suite is `kyo.proto.EffectTraceTest` and a prefix match would filter its own frames (§1.6). |
| F17 | blocking | Fixed: physical-trace cases move to `kyo-kernel2/jvm-native/src/test/scala/kyo/proto/EffectTracePhysicalTest.scala`; the tree is wired (verified in `.bloop/kyo-kernel2JVM-test.json`) with `SafepointUnstartedThreadTest` as precedent, and the Native half is verified by a run rather than assumed (§4.5). |
| F18 | advisory | Fixed: the clean batch build is the first gate of T1, T2 and T3, with the reason it exists (§5.1). |
| F19 | advisory | Fixed: §1.8 argues the shape, with a table mapping every walk arm to the drive arm it mirrors. No node kind, no combinator. |
| F20 | blocking | Fixed: §7 declares four concessions in the required shape, and the swallow-all rule finally gets its pinning test, which the proto makes writable because `Kyo.Suspend` is a public abstract class (§4.6e). |
| F21 | blocking | Fixed: whole class, all 15 rows named, both variants, same session, `-f 1` screen then `-f 3` confirm, three-sha chain, A/A null, harness-driven, plus the bytecode-shape pins. The `SKILL.md:490-491` precedent is cited and answered on its own terms. The review's closing note in the proposal's favour (the drive never inlines, so a try region is free) is **withdrawn**: `Eval.apply` is now `inline` and `Eval$::loop` no longer exists as a JIT unit (§2.5, §5.2-5.4). |
| F22 | blocking | Fixed by the finding's own option (1): no staging, the six attaches land, the boundary splices only, and the measurement is what gets staged. §2.4 records the decision and the reason a boundary attach would double-count (§2.4). |

---

## 9. Judgement calls made here, and what decided them

1. **No staging.** F10 and F22 both point at it; the deciding fact is that `dump()` truncates on every
   delivery, so a boundary-only attach is not a weaker version of the port, it is a different and mostly
   empty feature.
2. **Position determines role, rather than an identity guard.** An identity guard closes F13 and leaves
   the stale-value bug (a hybrid folded into a chain by `dump` re-walking its consumed body) open. The
   position rule closes both, removes the guard, and makes both matches exhaustive.
3. **No `Loop.Continue` arms.** Unreachable under this wiring; dead code on a cold path is still dead
   code. Recorded where the fix goes.
4. **`ask` leaves the "carried through a drive" assertion, and a fused sibling replaces it.** The old
   assertion pinned an artifact of the old node layout. Deleting it silently would be weakening a test;
   replacing it with a case that pins the proto's fused node is stronger coverage than the original.
5. **`:188` is not ported and no combinator is added for it.** A by-name `Effect.defer` is a surface
   decision. Raised, not executed.
6. **`Stack.entry` is `private[proto]`, narrower than its public siblings.** Minimal scope; the siblings'
   width is historical.
7. **The boundary does not attach.** With S1-S8 wired it would append a duplicate tail.
8. **`kyo.proto.EffectTrace` is public, not `private[kyo]`.** Forced by `Eval.apply` being `inline`:
   an inline body is re-typechecked at every expansion site, including outside package `kyo`. Narrow it
   again if the drive stops being inline.
9. **The claim that the try regions cannot move an inlining verdict is withdrawn, not restated.** It
   rested on `Eval$::loop` existing as its own JIT unit, which an inline drive removes. `PrintInlining`
   is promoted from optional to part of the bracket.
10. **The `Stack.borrow()` leak at `Eval.scala:31` is recorded, not fixed here.** It is real and unrelated;
   it touches the drive's entry and deserves its own change.
