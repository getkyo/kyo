# Eval redesign, step one: encapsulate the drive stack

Read-only design, written from the working tree while the owner reworks `Eval.scala`. Nothing here is
built. Paths are relative to `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/`.

Owner's framing, verbatim: "we'll redesign Eval. First, let's try to encapsulate things in Stack as
the TODOs indicate", and "also consider how we can increase safety of the stack methods. both
statically and at runtime". Standing rules from the same exchange: "do NOT introduce major changes
without validating with me", "Do NOT make changes without thinking of safety", and no new evaluator
machinery in the shape of `ContinueAnswer` or `Parked` ("You keep thinking of this as a VM-like
execution when you should be thinking of COMPOSITION").

## 1. What Eval asks of the stack today

Every stack use in `Eval` is one of six operations, and the raw index arithmetic is the same three
loops written five times.

| operation | sites (Eval.scala at `c2d5db5072`) | what it is |
|---|---|---|
| find the handler for a tag, read it typed | `find(tag, base)` + `stack(i).asInstanceOf[Handle]` (:136-138) | locate a region |
| is there a region inside this one | `while j < top && !marked(j)` twice (:66-67, :142-143) | does the interior contain a region |
| fold a plain segment into one arrow | `var k = Arrow[Any]; k = stack(m).chain(k)` three times (:50-54, :77-81, :148-152) | reify a plain continuation |
| park a segment as a value | `copyEntries/Tags/States(from)` + `truncate` + `Arrow.Eval(...)` three times (:69-74, :156-160, :123-127) | reify a segment containing regions |
| region state | `state(i)`, `setState(i, _)` (:174, :178) | stateful currency |
| drop a region | `truncate(i)` on done (:171, :183) | the region ends |

Two facts fall out.

- **The three park sites differ only by one index.** `outcome` copies from `i+1` and truncates to `i`
  (handler removed, rebuilt later by `region`); `handleCont` copies from `i+1` and truncates to `i+1`
  (handler stays installed). That one index is the whole clause-scope fork (`CLAUSE-SCOPE-DESIGN.md`
  §4.7) and nothing in the code names it.
- **The three spans are only ever produced together and consumed together**, by `Arrow.Eval`, whose
  own TODO says it should be called `Park`.

The stack has one more consumer, `EffectTrace.Builder.entries(stack, base)`, which walks entries from
the top down to the drive's base.

## 2. The Stack API

**A cut of the stack is a value.** That is what turns index moves into composition:

```scala
// a reified slice of the drive stack, in stack order: complete, immutable, replayable
final private[kyo] class Segment private[internal] (
    private[internal] val entries: Span[Arrow[?, ?, ?]],
    private[internal] val tags: Span[AnyRef],
    private[internal] val states: Span[AnyRef]
):
    def size: Int = entries.size
object Segment:
    val empty: Segment = new Segment(Span.empty, Span.empty, Span.empty)

// Arrow.Eval becomes what its TODO asks, with one field where it had three
final private[kyo] class Park[+A, +B, -S](val segment: Segment, val value: A < S) extends Defer[Any, B, S]
```

**Two index kinds, because an entry and a boundary are different things:**

```scala
private[internal] object Stack:
    opaque type Slot = Int      // an entry, obtained only from find or topSlot
    opaque type Base = Int      // a boundary: everything at or above it
    object Slot:
        val Missing: Slot = -1
    extension (s: Slot)
        def isMissing: Boolean = s < 0
        def at: Base    = s       // the boundary that includes the entry
        def above: Base = s + 1   // the boundary just above it
```

**The instance surface**, each removal paired with what it yields:

```scala
final private[internal] class Stack:
    // drive boundary
    def enter(): Base                        // the new floor; the previous one lives in the drive's local
    def exit(prev: Base): Unit               // reset to the floor, restore prev
    // queries
    def find(t: Tag[Any]): Slot              // down to the floor; Slot.Missing if none
    def isRegion(s: Slot): Boolean
    def hasRegionAbove(s: Slot): Boolean
    def plainBase: Base                      // the boundary above the innermost region (dump's scan)
    def regionAt(s: Slot): Handle[Nothing, Any, Any, Any, Any]
    def stateAt(s: Slot): Any
    def setStateAt(s: Slot, v: Any): Unit
    def topSlot: Slot
    // push
    def pushCont(a: Arrow[?, ?, ?]): Unit
    def pushRegion(h: Handle[...]): Unit     // derives tag and initial state from h.handler itself
    def restore(seg: Segment): Unit
    def pop(): Arrow[Any, Any, Any]
    // remove, returning what was removed
    def fold(b: Base): Arrow[Any, Any, Any]  // compose [b, top) into one arrow, then truncate
    def cut(b: Base): Segment                // reify [b, top) as a value, then truncate
    def detachInterior(s: Slot): Segment     // cut(s.above): the handler stays installed
    def detachRegion(s: Slot): Segment       // cut(s.above), then drop the handler entry
    def dropRegion(s: Slot): Unit            // truncate to s.at, discarding
```

Gone from the surface: `apply(Int)`, `marked(Int)`, `state(Int)`, `setState(Int)`, the tag/state
`push` overloads, `truncate(Int)`, `copyEntries/Tags/States`, `pushAll(3 spans)`, and the `base`
parameter everywhere (the floor replaces it, including for `EffectTrace.entries`).

## 3. Eval afterwards

`dump` and the three copy blocks disappear:

```scala
val next = s0.tail.chain(stack.fold(stack.plainBase))              // was s0.tail.chain(dump())

def reify(v: Any): Any =                                            // partial's park
    if stack.size == floor then v else Park(stack.cut(floor), v.asInstanceOf[Any < Any])
```

`outcome`'s head, the fork now named:

```scala
val marked  = stack.hasRegionAbove(slot)
val segment = if marked then stack.detachRegion(slot) else Segment.empty
val body    = if marked then resume else resume.chain(stack.fold(slot.at))   // the fold drops the handler too
def region(regionHandler: Handler[...], payload: Any) =
    new Handle[...]:
        def v = Park(segment, Identity(payload.asInstanceOf[Any < Any], body))
```

`handleCont`'s arm, the `i + 1 == top` case collapsing because an empty fold is `Identity` and
`chain(Identity) eq this`:

```scala
case hc: Handler.HandleCont[...] =>
    if stack.hasRegionAbove(slot) then
        val seg = stack.detachInterior(slot)                                    // handler stays
        hc.run(s.input, o => `<`.fromArrow(Park(seg, whole(o))))
    else hc.run(s.input, whole.chain(stack.fold(slot.above)))
```

The rest is one name each: `stack.dropRegion(slot)` on done, `stack.stateAt(slot)` / `setStateAt`,
`stack.restore(p.segment)` for a `Park`, `stack.pushRegion(h)` replacing the handler match at
:218-223 (the match moves into Stack, so a stateful region can never be pushed without its state
slot), and the drive's `finally stack.exit(prev)`.

## 4. Safety: static

- **No raw `Int` reaches the arrays.** A `Slot` comes only from `find`/`topSlot`; a `Base` only from
  `enter`, `plainBase`, or `slot.at/above`. The off-by-one that is the clause-scope fork becomes
  `detachRegion` vs `detachInterior`, two names, at the one place each is meant.
- **The three spans cannot be split.** `Segment` is the only currency between `cut` and
  `restore`/`Park`; its constructor is `private[internal]` and only `cut` calls it, so lengths agree
  by construction.
- **Region-only accessors take a `Slot` that `find` produced**, and `pushRegion(h)` derives tag and
  state from the `Handle` itself, so tag/entry mismatch and a stateful region without a state slot
  are unrepresentable.
- **The floor removes the parameter every consumer had to thread correctly**: `find`, `fold`, `cut`,
  `reify`, `EffectTrace.entries` all stop at the stack's own floor; nested drives save and restore it
  in a local, the way `Safepoint.save/restore` already works.
- One cast survives, `regionAt`'s array-element re-typing, in the sanctioned category (the skill's
  "array element re-typing at the storage boundary (Stack)"), and now in exactly one line.

## 5. Safety: runtime

Cheap, on the cold paths only; the hot pair `pushCont`/`pop` gets nothing:

- `cut`/`fold`/`dropRegion`/`detach*`: `if b < floor then bug(...)`, so no drive can ever truncate
  into an outer drive's frames (drives share the thread's stack; today nothing prevents it).
- `regionAt`/`stateAt`/`setStateAt`: reject `Slot.Missing` and a non-region slot.
- `exit(prev)`: asserts the stack is at the floor after reset (a leaked entry is a bug, today silently
  truncated by the `finally`).
- `restore(seg)`: one `ensureCapacity(top + seg.size)` instead of a per-element check (also faster).
- Optionally a `Stack.checks` `StaticFlag` (the mechanism `Safepoint.period` already uses), off by
  default, enabling the heavier invariant walk under tests: every tagged entry is a `Handle`, states
  are null off stateful regions, `find`'s answer equals a linear scan.

## 6. Order, each step green on a clean build and measured on the rows it reaches

1. `Segment` + `Park` (rename, one field): pure refactor; rows `emittingClausesPayRegionRebuild`,
   `statefulAnswersPaySuccessor`.
2. `Slot`/`Base`, `find`, `regionAt`, `stateAt`, `pushRegion(h)`: dispatch is touched, so every
   `handleLoop` row.
3. `fold`/`cut`/`detach*`/`dropRegion`/`plainBase`; `dump` and the copy blocks go: same rows plus
   `handleLoopFusesContinuation`.
4. The floor and the runtime guards: all rows, since `find` and the loop's `finally` are touched.

Nothing above changes what the drive *does*; it only names what it does, which is the precondition
for the redesign that follows (the `cur` currency, and where a pending answer runs).

## 7. What the redesign that follows has to settle (recorded, not designed here)

- `var cur: Any` is the `<` union erased: a raw settled value, a `Nested` (a computation held as
  data), or a bare `Arrow` (a computation to run). Every hand-off in the file is that same erased slot
  (`dispatchInline`'s return, `whole(x)`, `Identity(x, body)`, `region(_, payload)`, `Continue._1`,
  `outcome`'s `v`, `reify(v)`), and which shape a line holds is a convention, not a type. The owner:
  "The root of these issues is the extreme unsafety of the code making it impossible to nest/unnest
  stuff properly."
- Where a pending answer runs. `kyo-kernel2/CONTRIBUTING.md` rule 7 already records the semantics:
  "A continue whose answer is itself pending runs at `node`: inside the handler's own cell, so a
  re-raise of the scope's effect is answered by the same handler. Note the asymmetry with 6: a
  pending clause *outcome* runs outside its own region, a pending *answer* runs inside it." That is
  the T reading of `CLAUSE-SCOPE-DESIGN.md` §4.1; `EvalTest.scala:501` contradicts it and should
  become the old kernel2's stateful successor form unless the owner overrules rule 7.
- Under that reading the missing piece is the operation's continuation with the interior parked, as
  a value (`k`): the drive builds it today only as the `handleCont` resume lambda at :160,
  `o => Arrow.Eval(entries, tags, states, whole(o))`, and `region`'s rebuild is the same thing with
  the payload already known. Whether that value should exist, or whether `Park` in continuation
  position should deliver its input into its value, is the owner's call.
