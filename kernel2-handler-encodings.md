# kernel2 handler encodings: dispatch at the suspension point for every format

STATUS: IMPLEMENTED (commits 2f90323de3 pins and acquire temporality, 64a579dc37
core, plus the apply reshape). All six pins green, suite 635/635. Measured:
neverResumes1k 16ns/64B (a never-resuming operation plus one thousand
construction maps costs its one suspension), deepStop1k 2.77us/22.3KB (one
thousand post-resumption frames, nothing stacked on the operation),
stopConstructed1k 3.4us/14.1KB (the honesty row: pre-handler construction
unchanged), foreignBubbleUnderStop 1.98us/6.1KB (the miss-path baseline).
Reference rows hold except: state10 +16B per op (the per-call shadow entry, by
design), and contextRead100 24-26KB per op against 19.2KB before, with time
improved 15-25%; flagged, revisited with the E2b array carrier. The naming
section landed as proposed (Entry.Resume/Stop/Shadow, Rotate.stop/masked,
NeverResumed, dispatchLocal, and rewrap renamed rotate per the in-code note).


Design for the per-format handler encodings in kyo-kernel2, per your direction:
"you need to propagate a handler in Handlers so when the execution gets to a
suspension it has the handler to skip to", and for the stop format specifically,
"The goal here is not even producing the continuation!".

The document has five parts: a review of what each mechanism does today and
where it wastes work, the proposed encoding per handler kind with code sketches,
the compatibility analysis (pins, rotation, brackets, drive), open questions
with the measurements they need, and naming proposals. Identifiers that do not
exist yet appear in sketches as placeholders; every one of them is listed in the
naming section for your confirmation.

# 1. Review: the mechanisms today and where they waste work

## 1.1 The bubble point and what stacking costs

The single place suspensions bubble is the Kyo branch of the 3-arg apply
(Arrow.scala:111-124):

```scala
else if v.isInstanceOf[Kyo[?, ?]] then
    val kyo = v.asInstanceOf[Kyo[Any, Any]]
    if handlers.isEmpty then kyo.asInstanceOf[Kyo[A, S2]].map(self)
    else
        val answered = ArrowEffect.answerNow(kyo, context, handlers)
        if answered.asInstanceOf[AnyRef] eq null then kyo.asInstanceOf[Kyo[A, S2]].map(self)
        else self(answered.asInstanceOf[A < S2], context, handlers)
```

`kyo.map(self)` is the structural path: `Suspend.map` builds
`Continue(origin, cont.map(self))` (KyoInternal.scala:45-46), one `Continue`
node plus, once `cont` is nonempty, one `AndThen` node per frame crossed
(Arrow.scala:134-137). Roughly two allocations and 48-64 bytes per frame. For
a suspension that will resume, this is the honest price of a captured
continuation. For a suspension whose handler never resumes it, every one of
those nodes is dead on arrival.

## 1.2 handleStop: the exact dissatisfaction

`handleStop` (ArrowEffect.scala:325-343) discards `s.cont` at its matched arm,
so the loop itself never builds anything. But nothing on the way TO the loop
knows that. An operation of a stop-handled effect surfacing mid-chain takes
the structural path at the bubble point: each remaining frame in the executing
chain stacks a `Continue`/`AndThen` pair onto the suspension, each crossed
foreign handle loop rewraps it with a `Continue` plus a `Rotate` node
(ArrowEffect.scala:171-172), each crossed binding and guard does the same
(ContextEffect.scala:127-141, Effect.scala:42-53), and only then does the
suspension arrive at the stop loop's matched arm, which throws the whole
construction away (ArrowEffect.scala:333-335). A failure N frames deep costs
O(N) allocations to travel and then costs them again as garbage. The old
kernel has the identical behavior (kyo-kernel ArrowEffect.scala:137-142: every
foreign-suspension arm builds a `KyoContinue`), which is why a deep `Abort`
failure in kyo-prelude today allocates a chain instead of one node; `Abort.run`
is stop-shaped (its handle function is `[C] => (input, _) => input`,
kyo-prelude Abort.scala:219, the continuation ignored).

`handleStop` registers nothing in `Handlers`: its arm 2 uses `Rotate.plain`
(ArrowEffect.scala:337), which extends no parameter (ArrowEffect.scala:89-97).
The threaded parameter that exists precisely so a suspension point can know its
handler carries no trace of stop handlers today.

## 1.3 handleResume: three paths, one of them still structural

`handleResume` (ArrowEffect.scala:296-318) is the only format that registers:
`Rotate.handler` (ArrowEffect.scala:102-112) extends the parameter with
`new Handlers.Entry(h, context, handlers)` before running the contained chain,
and `answerNow` (ArrowEffect.scala:222-239) answers matching operations at the
point they surface. Three paths exist:

1. Operation at the fold's surface: the matched arm answers directly. No
   lookup, no allocation for the answer itself.
2. Operation surfacing inside a rotate re-entry: answered in place via the
   registered entry. This is the format working as designed.
3. Operation surfacing while the matched arm resumes the continuation
   (`s.cont(h.handle[x](s.input), context, handlers)` at
   ArrowEffect.scala:307): the ambient `handlers` does not contain `h`, so the
   operation bubbles structurally, stacking `Continue` nodes back to the loop.
   This is the TODO at ArrowEffect.scala:310. The answer to that TODO is in
   section 2.4: the handler should be registered there, with a precomputed
   extended parameter so registration costs nothing per operation, and the
   registration is not only an optimization but a correctness requirement once
   entries of the same tag can be visible from an outer scope (section 1.5).

Cost detail: `Rotate.handler` allocates one `Entry` per re-entry because the
entry captures the re-entry's scope pair. `Handlers.add` is `Chunk.append`,
which allocates an `Append` node per call (Chunk.scala:153-154).

## 1.4 rewrap: rotation paid for continuations that never run

`rewrap` (ArrowEffect.scala:163-205) is shared by every loop. Its Suspend arm
always rotates: `s.continue(rotated(s.cont))`, one `Continue` plus one `Rotate`
per crossed region. For a suspension whose innermost matching handler is
stop-format, the rotation preserves a region for a resumption that cannot
happen; the stop loop's matched arm discards the rotate node along with the
rest of the continuation. Same waste, one level coarser than frame stacking.

## 1.5 Registration discipline today: an innermost-wins hole

`Handlers.resolve` (Handlers.scala:36-45) scans innermost-last and its
predicate matches the handle loops' subtype guard. But only fun-format handlers
register, and only at rotate re-entries. Two consequences:

1. A non-registering handler of the same tag nested INSIDE a registered one is
   bypassed. Sketch: `handleResume(TagA)(outer) { ... handle(TagA)(inner) {
   op } ... }` where the inner ctl region contains a foreign suspension that
   parks and resumes. On resumption the inner region's chain runs under the
   outer entry (registered by the outer loop's `Rotate.handler`), an operation
   of TagA constructed inside that chain hits the bubble point, `answerNow`
   resolves it to the OUTER entry and answers in place, and the inner ctl
   handler, which is innermost and should win, never sees it. The suite's
   innermost-wins pin (ArrowEffectTest.scala:821) uses two ctl handlers with no
   foreign suspension, so it does not reach this path. I could not find a pin
   that does. This is a live defect in the current code, independent of any
   new encoding; the shadow discipline in section 2.5 closes it, and section 4
   lists the reproducing pins to write first.
2. The same shape with the roles reversed (registered handler inside,
   unregistered outside) is safe today only because the inner registration is
   appended after the outer one and the scan is innermost-last.

The registration discipline is therefore not a per-format nicety: once any
entry kind is visible in the ambient parameter, every format that does NOT
register must actively shadow its tag inside its own region, or the parameter
lies about who is innermost.

## 1.6 The carrier and the initial fold

`Handlers` is an opaque `Chunk[Entry]` (Handlers.scala:13), `Entry` carries
exactly a `ResumeHandler` plus the captured scope pair (Handlers.scala:22-26).
There is no room for another kind. The E2b experiment in
kernel2-rotation-handlers-design.md settled that the target representation is
one final array-backed class with reference-first tag comparison; the Chunk
carrier with `Append` nodes and O(depth) indexed access is the interim shape.

Every handle call starts its fold with `Context.empty, Handlers.empty`
(ArrowEffect.scala:286, 317, 342, 371, 459). Construction-time entries do the
same: `Pending.map` applies its freshly minted arrow with empty parameters
(Pending.scala:52). So the ambient parameter is populated only during chain
applications made by rotate re-entries, matched-arm resumptions, and the drive's
pops of chains that contain rotate steps. This bounds where any suspension-point
dispatch can act: construction that happens before a handler exists cannot be
helped by the parameter (section 2.6 shows what CAN help it, for effects that
never resume by type).

# 2. The proposed encodings

## 2.1 Summary table

| format | registers in Handlers | at the suspension point | continuation built |
|---|---|---|---|
| resume (fun) | resume entry: handler + captured scope pair; at rotate re-entries AND the matched arm's resumption | answered in place, continuation keeps running forward | never |
| stop (final ctl) | stop entry: tag only, one shared instance per handle call; at rotate re-entries | bare pass-through: the suspension returns unchanged, no stacking, no rotation; the loop's matched arm answers on arrival | never |
| ctl (handle) | shadow entry: tag only, one shared instance per handle call; at rotate re-entries and around the matched arm's resume closure, only when a same-tag entry is already visible | structural travel (the shadow forces it) | yes, by design |
| first | shadow entry, same as ctl, at rotate re-entries only; the resume closure deliberately does not shadow | structural travel | yes, once |
| loop | shadow entry, same as ctl, at rotate re-entries and around the resume closure; outcome computations run unshadowed | structural travel | yes, by design |
| Const[Nothing] output (any format) | nothing needed | statically skipped: no stacking, no rotation, regardless of registration | never, by type |

Bindings (`ContextEffect.handle`) and guards (`Effect.catching`) register
nothing; they are crossed, not consulted, and the stop pass-through is allowed
to skip their rotation (section 3.2).

## 2.2 The entry representation

`Handlers.Entry` becomes a small sealed hierarchy with the tag hoisted to the
base so `resolve` stays monomorphic on its hot read:

```scala
// names are placeholders, see the naming section
sealed abstract private[kyo] class Entry(val tag: Tag[Any])

object Entry:
    // per rotate re-entry and per handle call: captures the scope the handle
    // function runs at. Today's Entry, unchanged in content.
    final class Resume(
        val handler: ResumeHandler[?, ?, ?, ?],
        val entryContext: Context,
        val entryHandlers: Handlers
    ) extends Entry(handler.erasedTag)

    // one instance per handleStop call, allocated beside the loop and reused
    // by every rotate re-entry: a stop entry captures no scope because nothing
    // executes at the suspension point; the loop answers on arrival.
    final class Stop(tag0: Tag[Any]) extends Entry(tag0)

    // one instance per ctl/first/loop handle call, reused: means "this tag
    // travels structurally inside this region", masking outer entries of the
    // same tag so innermost-wins holds across formats.
    final class Shadow(tag0: Tag[Any]) extends Entry(tag0)
```

Allocation accounting versus today:

- Resume entries: unchanged (one per rotate re-entry; the capture is the
  point).
- Stop entries: one object per handleStop CALL, zero per re-entry; each
  re-entry pays only the `add` (the `Chunk.append` node today, a slot write in
  the E2b array carrier).
- Shadow entries: one object per handle call, and the `add` is conditional
  (section 2.5), so the common un-nested case pays one non-allocating resolve
  per re-entry and nothing else.

`resolve` changes only its field access (`entry.tag` instead of
`entry.handler.erasedTag`) and, per the TODO at Arrow.scala:122, its miss value
moves from `null` to `Maybe.Absent`; `Maybe` over an `AnyRef` is
allocation-free on both sides. Keep a flag on the benchmark run in case the
extra branch shows up; the null protocol is a one-line revert.

## 2.3 The stop skip: bare pass-through, and why not a finished value

Two candidate mechanisms were named in the brief.

**Bare pass-through (chosen).** At the bubble point, when the suspension's tag
resolves to a stop entry, return the suspension unchanged instead of
`kyo.map(self)`. The frames between the suspension and the handler never touch
it; it travels outward as an ordinary return value through the chain
applications already on the call stack, and the first handle loop it reaches
whose matched arm's tag guard fires is, by construction, the innermost matching
handler: a suspension cannot cross its own handler's loop, because the matched
arm fires before the rewrap arm. On arrival the EXISTING matched arm of
handleStop answers it. The mechanism is purely subtractive: no new node kind,
no change to any loop's answering logic, zero allocation on the path.

Per-frame cost, quantified against what it saves: the pass-through re-resolves
the tag at each frame's bubble event. But the bubble path ALREADY resolves at
every frame whose ambient parameter is nonempty (`answerNow` at
Arrow.scala:120 does exactly this and returns null on a miss). Registering
stop entries turns that same resolve from a miss (followed by two allocations)
into a hit (followed by a return). On the previously registered paths the
marginal cost is negative: identical lookup, minus roughly two allocations and
48-64 bytes per frame. The genuinely new cost appears elsewhere: regions that
never used to register now make the ambient nonempty, so FOREIGN suspensions
(an async park bubbling out through a stop region) lose the `handlers.isEmpty`
shortcut and pay a resolve miss per frame. A miss at depth d costs d
reference compares plus, on reference inequality, the structural `<:<`
fallback, which E2 measured at roughly 19-20ns per miss when the structural
comparison runs. That is the one regression vector this design creates, it is
bounded per park (a park crosses its frames once), and section 5 names the
benchmark row that guards it plus the two mitigations to measure (interned-tag
reference equality; a cheap kind pre-check before `<:<`).

**Answering at the suspension point, propagating a finished value (rejected).**
The stop handle function's result is at the HANDLER's types, not the local
frame's: it replaces the whole region's result, so the local continuation
cannot consume it. Propagating it outward requires a recognizable wrapper
node that every enclosing frame passes through and the owning loop unwraps,
which means: one allocation per stop, a new node kind in the Kyo hierarchy,
a target-identity field so nested same-tag handlers do not consume each
other's values, new arms in rewrap, the drive, and the finalization walk, and
the handle function executing at the operation site needs the entry to carry
the captured scope pair after all. Every one of those is a cost or a
complication the pass-through does not have, and the pass-through's per-frame
resolve is already paid on registered paths. The finished-value form buys
nothing back: both mechanisms end with the handle function running exactly
once at the handler's loop.

The dispatch at the bubble point, restructured (this also resolves the Maybe
TODO at Arrow.scala:122 and folds `answerNow`'s body into one kind switch, one
resolve per bubble event serving every kind):

```scala
else if v.isInstanceOf[Kyo[?, ?]] then
    val kyo = v.asInstanceOf[Kyo[Any, Any]]
    kyo match
        case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
            if s.neverResumes then kyo.asInstanceOf[B < (S & S2)]   // section 2.6
            else if handlers.isEmpty then kyo.asInstanceOf[Kyo[A, S2]].map(self)
            else
                handlers.resolve(s.erasedTag) match
                    case Maybe.Present(r: Entry.Resume) =>
                        // today's answerNow body: answer in place, continue forward
                        val w = r.handler.answer(s.input)
                        val k = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                        val answered =
                            if w.isInstanceOf[Kyo[?, ?]] then k(scoped(r)(w), context, handlers)
                            else k(defaultLift(w), context, handlers)
                        self(answered.asInstanceOf[A < S2], context, handlers)
                    case Maybe.Present(_: Entry.Stop) =>
                        // the skip: every frame from here to the handler is dead;
                        // the innermost matching loop's matched arm answers on arrival
                        kyo.asInstanceOf[B < (S & S2)]
                    case _ =>
                        // Absent, or a Shadow entry: structural travel
                        kyo.asInstanceOf[Kyo[A, S2]].map(self)
        case _ =>
            kyo.asInstanceOf[Kyo[A, S2]].map(self)   // Defer, Bracket: structural
```

The cast on the stop arm asserts that this arrow's output will never be
produced for this computation; the resolve hit is the justification, exactly
as the tag match justifies `answerNow`'s input cast today. Per the TODO at
Arrow.scala:35, this dispatch belongs in the internals object this branch
already plans to introduce, so `Context`/`Handlers` stop leaking through the
public `Arrow` surface; the encoding work should land on that layout.

Nested handlers of the same effect, both orders, under the pass-through:

- Stop inside resume (`handleResume(A){ handleStop(A){ op } }`): the stop
  entry is appended after the resume entry, the innermost-last scan returns
  it, the suspension passes through bare and the stop loop, which is the
  innermost enclosing loop on the call stack, answers. Correct.
- Resume inside stop: the resume entry is innermost, `answerNow` answers in
  place, the stop loop never sees the operation. Correct.
- An unregistered format inside a stop region is the case that NEEDS the
  shadow (section 2.5); without it the pass-through would skip live frames.

## 2.4 handleResume: the matched-arm registration (the TODO at ArrowEffect.scala:310)

The matched arm's resumption must run under a parameter that contains `h`.
Doing `handlers.add(...)` per operation would allocate on the hottest resume
path (resumeFused is 29ns/16B; an entry plus an append per op would double
its allocation), so the extension is precomputed once per loop invocation and
threaded through: the loop's scope pair is constant across its direct
self-calls, so one extension serves every operation the invocation answers.

```scala
def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    handle: [C] => I[C] => O[C] < (E & S & S2)
)(using frame: Frame): A < (S & S2) =
    val h = new ResumeHandler[I, O, E, S & S2](effectTag, handle, frame)
    // extended is the loop invocation's registration: handlers plus the entry
    // capturing this invocation's scope pair. The matched arm resumes under it,
    // so operations of E surfacing during the resumption are answered where
    // they surface instead of stacking Continue nodes back to the loop.
    def loop(v: A < (E & S & S2), context: Context, handlers: Handlers, extended: Handlers): A < (S & S2) =
        v match
            case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                loop(s.cont(h.handle[x](s.input), context, extended), context, handlers, extended)
            case k: Kyo[A, E & S & S2] @unchecked =>
                rewrap(k, [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.handler(chain, h, loop, frame), loop, context, handlers)
            case v =>
                v.asInstanceOf[A < (S & S2)]
        end match
    end loop
    val entry = new Entry.Resume(h, Context.empty, Handlers.empty)
    traced(frame)(loop(v, Context.empty, Handlers.empty, Handlers.empty.add(entry)))
end handleResume
```

`Rotate.handler` computes the same extension it computes today and passes it
into the loop instead of discarding it after the chain application:

```scala
def handler[X, M, S, A, S2](
    chain: Arrow[X, M, S],
    h: ResumeHandler[?, ?, ?, ?],
    loop: (M < S, Context, Handlers, Handlers) => A < S2,
    _frame: Frame
): Rotate[X, A, S2] =
    new Rotate[X, A, S2](chain):
        def frame = _frame
        def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
            val extended = handlers.add(new Entry.Resume(h, context, handlers))
            cont(loop(chain(defaultLift(v), context, extended), context, handlers, extended), context, handlers)
```

Two properties worth stating:

- Scope correctness is free at the matched arm: the ambient there IS the
  handler's scope, so an operation of E answered inside the resumption via the
  registered entry runs its handle function at the right scope with no
  `scoped` bracket. The `scoped`/`Rotate.at` machinery remains only for the
  answerNow path, where the operation site's scope differs from the entry's.
- This registration also closes the resume side of the innermost-wins hole:
  with the inner loop's entry appended innermost during its own resumption,
  an outer same-tag entry can no longer capture the operation.

Cost: one `Entry.Resume` and one `add` per handle call plus one per rotate
re-entry (the re-entry entry exists today; the per-call one is new and
trivial). Zero per operation. resumeFused must be re-run to confirm the
fourth loop parameter does not disturb the arm (it is one more register; the
direct self-call shape is unchanged).

## 2.5 The stop and shadow registrations, and the shadow rule

`handleStop` swaps `Rotate.plain` for a registering variant that appends the
per-call stop entry:

```scala
def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    handle: [C] => I[C] => A < (E & S & S2)
)(using frame: Frame): A < (S & S2) =
    val entry = new Entry.Stop(effectTag.erased)   // one per handle call, reused
    def loop(v: A < (E & S & S2), context: Context, handlers: Handlers): A < (S & S2) =
        v match
            case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                // unchanged: the continuation to this handler never runs, and with the
                // skip in place it was never built either
                loop(handle[x](s.input), context, handlers)
            case k: Kyo[A, E & S & S2] @unchecked =>
                rewrap(k, [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.stop(chain, entry, loop, frame), loop, context, handlers)
            case v =>
                v.asInstanceOf[A < (S & S2)]
        end match
    end loop
    traced(frame)(loop(v, Context.empty, Handlers.empty))
end handleStop

// in Rotate:
def stop[X, M, S, A, S2](
    chain: Arrow[X, M, S],
    entry: Entry.Stop,
    loop: (M < S, Context, Handlers) => A < S2,
    _frame: Frame
): Rotate[X, A, S2] =
    new Rotate[X, A, S2](chain):
        def frame = _frame
        def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
            cont(loop(chain(defaultLift(v), context, handlers.add(entry)), context, handlers), context, handlers)
```

The matched arm needs no registration: it applies no chain (there is no
continuation to resume), and the replacement computation's own chains register
through their rotates when they execute. The initial fold likewise applies no
chains for this format.

**The shadow rule.** Formats that need the built continuation (ctl, first,
loop) must not let an outer entry of their tag act inside their region.
Without a shadow, this program is wrong under the new encoding: an operation
of tag A surfaces inside a handleFirst(A) region that sits inside a
handleStop(A) region, after a foreign resumption; the ambient contains the
stop entry, the pass-through skips the frames between the operation and the
first loop, and handleFirst's matched arm then hands its handle function a
`resume` built from a continuation that is missing those frames. The same
shape with an outer handleResume mis-answers in place (that one is reachable
today, section 1.5). The rule:

- ctl, first, and loop regions register the per-call shadow entry at their
  rotate re-entries, and ctl and loop also apply their resume closures under
  it (deep formats re-handle through the resumed continuation, so their region
  includes it; handleFirst's resume runs AFTER the handler left, its result
  type says the tag is unhandled by this call, so its closure deliberately
  uses the plain ambient and outer entries rightly act there).
- The registration is conditional: `if handlers.resolve(tag).isEmpty then
  handlers else handlers.add(shadow)`. When no same-tag entry is visible there
  is nothing to mask, the parameter is passed through unchanged, and the
  common un-nested case (state10, stateMap10k run their loops with an empty
  ambient) pays one empty-check per re-entry and allocates nothing. Entries
  cannot appear in an ambient mid-flight (the parameter is copy-on-extend),
  so the condition evaluated at region entry stays valid for the region.
- handleLoop's outcome computations stay unshadowed: effects raised while the
  outcome is computed dispatch to outer handlers (the scaladoc contract at
  ArrowEffect.scala:377-379), and that includes same-tag entries.

`Rotate.plain` remains for any caller that neither registers nor shadows; the
four format loops move to the registering variants.

## 2.6 Effects that cannot resume by type: the static skip

An effect whose output is `Const[Nothing]` cannot be resumed by ANY handler:
a first or ctl handler receives a resume closure of type `Nothing => ...` it
cannot invoke, a fun handler's answer has type `Nothing < ...` and never
produces, and the boundary cannot fabricate a `Nothing` either. The suite
already carries such an effect (`Fail extends ArrowEffect[Const[String],
Const[Nothing]]`, ArrowEffectTest.scala:15, pinned at :881). For these
suspensions, building a continuation is wasted work unconditionally, before
any handler exists and regardless of registration.

Encoding: the inline `suspend` (ArrowEffect.scala:46-61) detects
`O[A] =:= Nothing` at compile time (an `inline`/`compiletime` match on the
output type) and mints the suspension with a never-resumes marker; the bubble
point checks the marker BEFORE the `handlers.isEmpty` shortcut (sketch in
2.3), and rewrap's Suspend arm checks it before rotating. Because
construction-time maps route through the same 3-arg apply
(Pending.scala:52 applies with empty parameters), `fail("x").map(f).map(g)`
returns the original suspension untouched: a deep failing chain costs one
node at construction, which neither kernel achieves today.

Boundaries of the static skip, each load-bearing:

- The skip lives at the bubble point and in rewrap ONLY. `Kyo.map` and
  `Suspend.map` are not touched: the drive attaches bracket finalizers by
  mapping `Finalize` steps onto escaping suspensions (Eval.scala:38, 50, 61),
  and those attachments must land on never-resuming suspensions too, so the
  release still runs when the boundary discards the remainder
  (`finalizeBracket`, Pending.scala:25-33, walking `Finalize` steps via
  Finalize.scala:62-94).
- Skipping rotation in rewrap is sound because nothing after the suspension
  can run: crossed guards can never fire post-resume, crossed bindings can
  never be read again, crossed handler regions can never see another
  operation. The computation ends at this suspension; the only live
  obligations are finalizers, which travel by drive attachment, not by frame
  stacking (section 3.3).
- Marker representation is a measurement question (section 5): a marker
  subclass of `Suspend` checked with `isInstanceOf` (one class-hierarchy test
  per bubble event) versus a `def neverResumes: Boolean` with a base-class
  false (virtual dispatch across the many anonymous Suspend subclasses risks a
  megamorphic site). The subclass check is the expected winner.

Recommendation attached to this design: kernel2's Abort-shaped effects should
declare `Const[Nothing]` output so the static skip applies. Stop is then a
property of the effect, checked by the compiler, and the dynamic stop entry
covers the effects that are resumable in principle but stop-handled at a
given site.

# 3. Compatibility analysis

## 3.1 Where each piece registers, and duplicate registration

| site | resume | stop | ctl / first / loop |
|---|---|---|---|
| handle call (initial fold) | one per-call entry backing the precomputed extension; the fold itself applies chains only at the matched arm, which uses it | per-call entry allocated; fold applies no chains | per-call shadow allocated; fold applies no chains except resume closures (ctl/loop: shadowed; first: deliberately not) |
| matched-arm resumption | under the precomputed extension (2.4) | no resumption exists | ctl/loop closures under the conditional shadow; first under plain ambient |
| rotate re-entry | `Rotate.handler`: fresh Resume entry capturing the re-entry scope (unchanged) | `Rotate.stop`: append the shared per-call entry | shadow-conditional append of the shared per-call entry |
| the drive | nothing: the drive's parameters are constants of the drive (Eval.scala:21-23); rotate steps inside popped chains re-establish every registration as they run, which is also why parked remainders need no registration state | same | same |

Duplicate registration cannot accumulate: every extension is a call-scoped
value built from the ambient at its site, and a re-entry's extension dies when
its chain application returns. A rotate step re-entered N times (multi-shot
replay) registers N times sequentially, never concurrently in one parameter.
The one overlap to verify with a pin: a foreign park INSIDE a matched-arm
resumption. The arm's extension contains the entry; the park escapes the
resumption (the extension pops with the call), the loop rewraps with
`Rotate.handler`, and the resume-time re-entry registers freshly from the
resume-time ambient. Exactly one registration is live at any point.

## 3.2 Rotation, rewrap, and the crossing elision

rewrap's Suspend arm gains the mirror of the bubble-point skip: if the
crossing suspension's tag resolves to a stop entry in the loop's ambient (or
the suspension carries the never-resumes marker), return it unchanged instead
of `s.continue(rotated(s.cont))`. The crossed region's rotation would be
discarded by the stop loop's matched arm anyway; eliding it saves the
`Continue` plus `Rotate` pair per crossed region. This composes with every
factory because it happens before any factory runs:

- `Rotate.plain`/`handler`/`stop` and the shadow variant: their steps are
  simply not installed on a dead continuation.
- `Rotate.binding` (ContextEffect.scala:127-141): a binding crossed by a stop
  suspension dies with the region; its value can never be read again. Matches
  the old kernel: a failure inside an Env scope reaches Abort.run with the
  binding gone.
- `Rotate.guard` (Effect.scala:42-53): a guard crossed by a stop suspension
  can never fire post-resume. Pinned by EffectTest.scala:46 ("handleStop
  discards the rest of the computation, dropping a guard inside it").
- `Rotate.at`: an effectful resume answer's remainder runs at the entry's
  captured scope; a stop suspension surfacing inside it resolves against
  `entryHandlers`, which contains every entry visible outside the resume
  handler, so the pass-through works inside scoped answers unchanged.

## 3.3 Brackets and finalizers

The bracket interactions hold under the skip because the handler's rotate
step always sits at the region boundary and finalizers always attach OUTSIDE
it:

- Stop region outside a bracket, operation during acquire: rewrap's Bracket
  arm folds the pending acquire ahead and rebuilds the bracket around the
  settled resource (ArrowEffect.scala:176-194). A stop during acquire reaches
  the loop with nothing acquired; the bracket was never rebuilt; release never
  runs. Pinned by EffectTest.scala:297. The skip changes only whether the
  dead frames inside acquire were stacked first.
- Stop during use: the loop's rotate is folded inside the rebuilt bracket's
  cont, so the suspension is answered before it can escape to the drive; the
  drive's bracket arm then sees a settled cont result and runs release
  normally (Eval.scala:52-64). Same with the skip: the bare suspension exits
  the chain application into the same loop call.
- Park while holding a resource, then a stop after resumption: at park time
  the escaping FOREIGN suspension is rewrapped by the loop (its rotate goes
  innermost) and the drive appends the `Finalize` step outside it
  (Eval.scala:50), so the resumed chain has the shape [handler rotate,
  Finalize]. A stop op during the resumed region is answered inside the
  rotate; the settled stop value then flows THROUGH the Finalize step, and
  release runs. The invariant making this safe for the skip: a `Finalize`
  step can never sit BETWEEN an operation and its handler's loop, because
  finalizers enter chains only at the drive, which is outside every loop, and
  the loop's rotate is installed at the moment of escape, innermost first.
  The skip therefore never skips a finalizer. Section 4 lists this as a pin
  to write (the closest existing pin is EffectTest.scala:245, release across
  a park, which does not combine the park with a stop).
- Never-resuming suspensions reaching the boundary with a `Finalize`-bearing
  cont (attached by the drive, untouched by the static skip): the boundary
  discards the remainder and `finalizeBracket` runs the releases. Unchanged.

## 3.4 The settled arms, handleFirst parking, handlePartial, multi-shot

- Settled arms: untouched in every loop.
- handleFirst: keeps structural travel via the shadow; its matched arm and
  `done` are unchanged; the parked-continuation protocol (the handle function
  holding `resume` as a value) is unchanged.
- handlePartial (ArrowEffect.scala:507-541): unchanged. It drives with
  `Handlers.empty` and the rotate steps in the remainder re-establish
  everything per slice; installed delimiters keep winning over the boundary
  clause (pinned at ArrowEffectTest.scala:1026) because containment is
  structural: a suspension with a live matching loop on the stack cannot
  reach the drive. A later round can seed the drive's parameter with
  boundary stop entries (the runtime's fiber-failure effect) so a failure
  skips to the scheduler boundary with zero stacking; that needs the
  section 5 measurement first and is listed as an open item.
- Multi-shot ctl: replayed continuations re-run their crossed rotate steps,
  each replay re-registering its region's entries from its own ambient
  (pinned at ArrowEffectTest.scala:536, 544, 557). The shadow entry rides the
  same steps.

## 3.5 Pins that must stay green, and pins to add

Existing (the semantic surface this design must not move):

- ArrowEffectTest.scala:821 nested same-effect innermost wins (fold-time,
  ctl/ctl); :809 innermost across effects; :796 deep through resumed
  continuations; :832 and :889 handlers travel with parked computations; :847
  handleFirst shallow; :881 the Const[Nothing] effect; :1026 installed
  delimiters over the boundary clause; :1041 re-installation on resumption;
  the multi-shot group at :536.
- EffectTest.scala:46 stop drops an inner guard; :245 release exactly once
  across a park; :297 stop during acquire discards the bracket; :316 answered
  during acquire completes the bracket; :334 ctl transform after resume in
  acquire.
- ContextEffectTest.scala:277, :285, :297 (handle-function scope), :192
  (inner binding shadows), :260 (multi-shot re-resolution).
- PendingTest / PendingSchedulerTest drive and slice behavior.

New pins to write BEFORE the encoding lands (reproduce-first; the first two
should fail on the current code):

1. Innermost-wins across formats, registered outer: ctl (and separately
   first, loop) of tag A inside handleResume of tag A, with a foreign park
   and resume inside the inner region, operation raised after the
   resumption. Expected: the inner handler answers. Believed red today
   (section 1.5); this is the shadow's reproducing pin and it is a live
   defect fix, not just encoding support.
2. The same shape with handleStop outside and handleFirst inside, asserting
   the first handler's `resume` replays the frames between the operation and
   the first loop (guards against the skip crossing an unregistered same-tag
   region).
3. Stop after a park while a resource is held: acquire, park inside use,
   resume, raise the stop op; assert release ran and the stop value is the
   result (the Finalize-position invariant, section 3.3).
4. Deep stop after resumption allocates no continuation: a behavioral proxy
   pin (the benchmark row carries the number; the pin asserts the value and,
   if worth it, an EffectTrace/toString shape check that the suspension
   arrived bare).
5. Const[Nothing] construction: `fail(msg).map(f)` returns a suspension whose
   cont is still the identity arrow (observable via the internal test
   surface), plus eval-level results unchanged.
6. Matched-arm registration: an operation of E raised inside the computation
   returned by the handle function's answer during a matched-arm resumption
   is answered by the same handler at the handler's scope (extends
   ContextEffectTest:277's discipline to the arm-1 path).

## 3.6 Stack safety

Unchanged by construction. The loops keep their direct self-calls in the
match arms (the recursion scalac optimizes; the constraint is stated at
ArrowEffect.scala:276-277). The pass-through adds returns, not calls: a
skipped frame returns a value up the existing stack. The bubble-point
dispatch replaces `answerNow`'s call with an inlined switch inside the same
`apply`; the one self-call there (`self(answered, ...)`) exists today. The
new loop parameter in handleResume does not change the call graph. No new
mutual recursion anywhere on the hot path.

# 4. Performance constraints and the board

No new allocation on the hot dispatch paths, by construction:

- resume matched arm: zero per operation (precomputed extension); one entry
  and one append per handle call and per rotate re-entry (re-entry cost
  exists today).
- stop: zero everywhere except one entry per handle call and one append per
  rotate re-entry; the skip path allocates nothing.
- ctl/first/loop: one shadow per handle call; per re-entry one non-allocating
  resolve, plus an append only when nested under a same-tag entry.
- bubble point: one resolve per bubble event (already paid when the ambient
  is nonempty today), one kind match.

Board rows to re-run against the reference (suspension 111ns/616B, state10
256ns/1,040B, stateMap10k ~519us/3.11MB, resumeFused ~29ns/16B,
contextRead100 ~6us/19,240B, loopSuspend1k 23.7us/179,904B): all of them;
the ones with a specific hypothesis are resumeFused (fourth loop parameter),
state10/stateMap10k (shadow-conditional check per re-entry), suspension
(bubble-point restructure), contextRead100 (unchanged code path, control).

New rows this design needs (proposals for your confirmation):

1. deepStop1k: handleStop over a program that parks on a foreign effect once,
   resumes, runs 1000 maps, then raises the stop operation. The design's
   headline row: allocation should collapse from O(N) Continue/AndThen pairs
   to approximately zero on the travel path.
2. stopConstructed1k: the same 1000-map chain built BEFORE the handle call,
   folded eagerly. Expected unchanged: construction that predates the handler
   is out of the parameter's reach. The honesty row.
3. neverResumes1k: a Const[Nothing] operation followed by 1000 construction
   time maps, handled with ctl. Expected O(1) construction under the static
   skip.
4. foreignBubbleUnderStop: a foreign operation bubbling through N frames
   inside a registered stop region (the resolve-miss regression vector,
   section 2.3). This row gates the miss-path mitigations.

# 5. Open questions and measurements

1. Miss-path cost with populated ambients: how expensive is the per-frame
   resolve miss for foreign suspensions under stop regions
   (foreignBubbleUnderStop row)? If the structural `<:<` fallback dominates,
   the two mitigations to measure are (a) relying on erased-tag reference
   equality for concrete effects (needs the interning guarantee checked
   against Tag's representation) and (b) an entry-kind pre-check that skips
   `<:<` for kinds that cannot match the bubbling tag. E2b's array-backed
   carrier replaces the Chunk `Append` chain in the same round.
2. Entry kind dispatch: sealed-class match (bimorphic to trimorphic call
   sites) versus an int kind field on the base class. Measure on the
   suspension and deepStop1k rows.
3. Maybe versus null on `resolve` per the Arrow.scala:122 TODO: expected
   free, flag on the board run, one-line revert if not.
4. Never-resumes marker representation: marker subclass with `isInstanceOf`
   versus a virtual `def` (megamorphic risk across anonymous Suspend
   classes). Measure on neverResumes1k and the unaffected rows.
5. Resume entry self-extension: should `entryHandlers` include the entry
   itself so operations of E inside an EFFECTFUL answer on the answerNow path
   are answered locally instead of bubbling structurally back to the loop
   (deep semantics are preserved either way; this is cost only)? Needs a
   lazy or mutable self-field on the entry; defer unless a workload shows the
   structural fallback in a profile.
6. Boundary stop entries: seeding handlePartial's drive parameter with the
   runtime's stop-shaped boundary effects so a fiber failure skips to the
   scheduler with zero stacking. Depends on question 1's numbers and on the
   runtime round; out of this encoding's first landing.
7. The innermost-wins reproduction (new pin 1): confirm it is red on the
   current code as section 1.5 predicts. If it is somehow green, find what
   masks it before trusting the shadow design's necessity argument (the skip
   correctness argument in 2.5 needs the shadow regardless, because the stop
   entry widens the reachable cases).

# 6. Naming proposals (for your confirmation; nothing here is settled)

1. `Handlers.Entry` becomes sealed with three kinds. Proposed names:
   `Entry.Resume` (today's entry, renamed by position), `Entry.Stop`,
   `Entry.Shadow`. Alternative for the third if "shadow" reads too much like
   context shadowing: `Entry.Structural`, matching the walkthrough's
   "structural travel".
2. The rotate factory for stop re-entries: `Rotate.stop`. The
   shadow-registering variant used by ctl/first/loop: either fold into
   `Rotate.plain` with an entry parameter, or a named factory; proposed
   name if separate: `Rotate.masked`.
3. The static marker on suspensions that cannot resume: proposed
   `Suspend#neverResumes` for the predicate; if the subclass representation
   wins, a name for the subclass is needed (proposal: `Suspend.Final`,
   though "final" collides with the modifier; alternative:
   `Suspend.NeverResumed`).
4. The bubble-point dispatch, if it becomes a named method on the planned
   internals object (absorbing `answerNow`): proposal: keep `answerNow` if
   the resume arm remains its core, or `dispatchLocal` if you prefer the
   name to cover all three outcomes (answer, skip, structural).
