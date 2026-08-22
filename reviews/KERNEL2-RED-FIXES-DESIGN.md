# kernel2 red-findings fix design

Held-out design for the eight pinned-red kernel defects. Static analysis only: nothing was
executed, no source was edited. Designed against the current working-tree sources (the
stop-function parameter is gone, polls are `Safepoint.stopped(slot)`, stops are consumed once in
`Eval.partial`'s finally); all file:line citations are from that state, not from the audit's.

The eight reproductions that define done, none of which may change:

| finding | test | file:line |
|---|---|---|
| F1 | "a release that throws during unwind does not lose the failure or the recovery" | EffectTest.scala:875 |
| F1b | "a recovery that throws surfaces its own failure with the original suppressed" | EffectTest.scala:892 |
| F2 | "abandoning a slice parked in the acquire window still releases" | EffectTest.scala:859 |
| F7 | "a release that throws during abandonment does not silence the others" | EffectTest.scala:948 |
| F4 | "a clause throw meets the same scopes on every dispatch path" | ArrowEffectTest.scala:1907 |
| F6b | "a throwing release in a nested eval does not disarm the enclosing slice" | ArrowEffectTest.scala:1932 |
| F6 | "a throwing release on the completing path leaves the caller's safepoint state intact" | ArrowEffectTest.scala:1951 |
| F8 | "concurrent attach on a shared exception instance stays bounded and non-throwing" | EffectTraceTest.scala:348 |

---

## Cross-finding structure: throwing releases in three loops

F1 (the unwind walk, `Stack.scala:329`), F7 (`Eval.finalizeResources`, `Eval.scala:645`), and the
already-guarded boundary drain (`Stack.drainFinalizers`, `Stack.scala:378`) are the three loops
that run releases; F6 is not a loop defect but an ordering defect around the third. The question
posed: one shared guarded-release helper, or three treatments?

**Verdict: three separate treatments, kept textually parallel, sharing one convention.** The
guarded call itself is one line per site (`try f.run(outcome) catch ...`); everything that differs
is the bookkeeping, and the bookkeeping differs in kind, not degree:

- **unwind** always has a failure in flight: a release's throw is suppressed onto it and the walk
  continues with the same failure. A *recovery's* throw (F1b) is not suppression at all: it
  replaces the in-flight failure, with the original suppressed onto the replacement, and the walk
  continues asking about the new one. No end-throw bookkeeping exists because the walk's exit
  already carries the failure.
- **drainFinalizers** has a failure *sometimes*: suppress onto it when present, otherwise hold the
  first throw and deliberately rethrow it after the loop (the completing path's contract,
  `Stack.scala:368-373`). Already implemented; F6 is about its caller, and it gains only the
  identity guard below.
- **finalizeResources** never has a failure in flight (abandonment is not a failure): first throw
  held, later ones suppressed onto it, first rethrown after every release was attempted.

A shared helper would have to take `(finalizer, outcome, primary: Maybe[Throwable])` and return
the updated primary, and the drain's call site would still need the failure-versus-first
conditional around it, so the helper removes one line and adds indirection at all three. The
kernel's own rule (three similar lines over a premature abstraction; the `*With` overloads kept
textually parallel) decides this.

**The shared convention, applied at every suppression site old and new:** never self-suppress.
`Throwable.addSuppressed` throws `IllegalArgumentException` when handed the exception itself, and
a release that rethrows the failure it was told about (a plausible shape: a release inspecting its
`Result.Panic` and rethrowing) would turn the guard into a new unguarded throw. Every
`x.addSuppressed(y)` in these paths becomes `if y ne x then x.addSuppressed(y)`. Sites:
the two new catches in `unwind`, the new catch in `finalizeResources`, and the two existing
`addSuppressed` calls in `drainFinalizers` (`Stack.scala:393` and `:396`), which carry the same
latent hazard today.

All of this is cold-path code: no fix in this group touches the settled map path, the answers
loops' per-answer path, or per-suspension dispatch.

---

## F1 / F1b — a throwing release or recovery during unwind

### Root cause

- `Stack.scala:329-338` (`unwind`): `case f: Finalizer[?, ?] => f.run(Result.panic(ex))` and
  `case r: Recover[?, ?] => out = r.panic(ex)` are both unguarded. `Finalizer.run` and
  `Recover.panic` both execute user code (`Finalizer.scala:41`, `Effect.catching`'s `recover`
  runs eagerly inside `panic`, `Eval.scala:60-70`). A throw from either escapes `unwind`
  mid-walk.
- `Eval.scala:606-623` (the retry loop): `stack.unwind(ex)` runs inside the `catch`; an exception
  thrown *by the unwind* escapes with `failure` still `Absent` and without `splice`.
- `Eval.scala:625-631` (the finally): `drainFinalizers(Absent)` then labels the remaining releases
  `Finalizer.Abandoned` instead of the real failure.

Consequences reproduced by the two tests: the walk stops (a standing `Catching` never answers),
the original failure is replaced unsuppressed, and the drain mislabels.

### Proposed fix

`Stack.unwind` becomes a walk that always completes and either returns the answering recovery's
computation or throws the failure the walk ended holding. The `Maybe` return goes away: the
"unanswered" exit becomes a throw, so the caller always learns which failure actually leaves
(it is `ex` itself, or the failure of a recovery that threw while answering).

```scala
/** Unwinds to the innermost recovery that answers, releasing everything it passes on the way.
  *
  * (keep the first two paragraphs of the existing doc)
  *
  * A release that throws never stops the walk: its failure is suppressed onto the one being
  * unwound, which stays the one the recoveries are asked about. A recovery that throws replaces
  * it: the recovery's failure is what the computation is now leaving with, the original travels
  * on it as a suppressed exception, and the remaining scopes are asked about the new one. When
  * no recovery answers, the walk throws the failure it ended holding rather than returning, so
  * the caller records the failure that actually leaves.
  */
def unwind(ex: Throwable): Any =
    var current         = ex
    var out: Maybe[Any] = Maybe.empty
    while out.isEmpty && !isEmpty do
        pop() match
            case f: Finalizer[?, ?] =>
                try f.run(Result.panic(current))
                catch
                    case t: Throwable => if t ne current then current.addSuppressed(t)
            case r: Recover[?, ?] =>
                try out = r.panic(current)
                catch
                    case t: Throwable =>
                        if t ne current then t.addSuppressed(current)
                        current = t
            case _ => ()
    end while
    out match
        case Present(a) => a
        case Absent     => throw current
end unwind
```

The eval's retry-loop catch (`Eval.scala:613-623`) shrinks to match the new contract:

```scala
catch
    case ex: Throwable =>
        // the unwind answers with a recovery's computation or throws the failure the walk
        // ended with: `ex` itself, or a throwing recovery's own failure with the one it
        // replaced suppressed on it. Either way every release the walk passed has run
        curr =
            try stack.unwind(ex).asInstanceOf[Any < Nothing]
            catch
                case fail: Throwable =>
                    failure = Present(fail)
                    // every throw that carries frames has already had them reconstructed at
                    // the site that ran the user code, so this only rewrites the exception's
                    // own trace
                    EffectTrace.splice(fail)
                    throw fail
```

### Why the fix lives in `unwind`, not in the eval

The eval cannot distinguish a release's throw (suppress onto the in-flight failure, keep it) from
a recovery's throw (replace the in-flight failure) from outside: the entry kind is known only at
the `pop()`. Restarting `unwind(ex2)` from the eval on any throw was considered and rejected for
exactly that reason: it forces one policy onto both kinds and gets at least one wrong.

### Alternatives considered and rejected

- **Keep `Maybe[Any]` and throw only when the failure was replaced.** Preserves the current
  common-path shape, but gives the same logical case (unanswered walk) two exit protocols, and the
  eval must then duplicate the record-splice-throw block for both. The uniform throw is one
  protocol and makes the eval's catch *shorter* than today. Cost of the uniform shape: one extra
  throw/catch of an already-constructed exception on the plain unanswered path; no
  `fillInStackTrace` is involved (the exception already exists), and the path is cold.
- **Return `Result[Nothing, Any]` (`Panic` for the failure).** Workable, allocation on a cold path
  is irrelevant, but it adds a second currency to a member whose caller immediately destructures
  it into exactly the throw the uniform shape performs directly.
- **Catch only `NonFatal` from a release.** Rejected: `drainFinalizers` guards with `Throwable`
  today, and the kernel's fatal-error rule is about recoveries not *answering* fatals (which
  `Recover.panic` already enforces by declining), not about a release's failure aborting its
  siblings. The two loops obey the same law. A recovery that throws a fatal error is handled
  correctly by the switch: `current` becomes the fatal, later recoveries decline it, finalizers
  are told it, and it escapes, which is the fatal-propagation rule.

### Safety argument

- **F1 shape**: body throws, release throws during unwind. The release's failure is suppressed
  onto "body" (`suppressed == List("release")`), the walk continues, the standing `Catching`
  answers "body" with `-1`. `Finalizer.run` sets its flag before running, so the boundary drain's
  later encounter with the same finalizer is a no-op: no double release.
- **F1b shape**: recovery throws. The replacement carries the original suppressed
  (`ex.getSuppressed.exists(_ eq original)`), the walk continues with the replacement, the
  bracket's finalizer (still standing above the popped scope? no: standing *below* the throwing
  `Catching` in that test) is told `Result.panic(fromRecovery)` because the walk now carries the
  replacement, and the unanswered exit throws it. `failure` records it, so the boundary drain
  labels any side-array remainder with the real failure instead of `Abandoned` (the third defect
  in the audit's F1 list, fixed by the same change).
- **Rethrow-identity**: a recovery that rethrows the same instance (`catching(v)(e => throw e)`,
  pinned green in EffectTraceTest:330) takes the `t eq current` branch: no self-suppression, no
  behavior change.
- **Hostile axes**: multi-shot and replay are untouched (unwind consumes stack entries exactly as
  before; re-entering a re-pushed scope re-pushes its entry). Park/abandonment: unwind runs only
  inside a live eval, never against a park. Cross-thread: the stack is single-threaded by
  construction.

### Performance

Cold path only (the failure lane). Zero effect on settled maps, answers loops, or dispatch. The
per-entry `try` adds exception-table rows, not instructions. `Result.panic(current)` stays
per-finalizer as today (it cannot be hoisted across a possible switch; the path is cold).

### Intrusiveness

- `Stack.scala`: `unwind` body and doc (~15 lines changed).
- `Eval.scala`: the retry-loop catch (~10 lines changed, net smaller).
- No other caller of `unwind` exists (verified: no test or source references it outside
  `Eval.apply`).

### Tests that flip

EffectTest:875 (F1), EffectTest:892 (F1b). Existing greens preserved: EffectTraceTest:330
(rethrow-same-instance), EffectTest:905/914/925/935 (release-outcome family).

---

## F2 — the acquire-to-binding window is parkable; an abandoned park there leaks

### Root cause

- `Effect.scala:109-117`: `bracket` is `acquire.map { resource => new Binding ... }`. The map's
  arrow (`Pending.scala:23-39`) is budget-gated: with a stop pending the budget is drained
  (`Safepoint.resolve`, `Safepoint.scala:94`), so `shouldDefer` fires and the step that would
  build the `Binding` defers instead of running.
- `Eval.scala:436-443` (the Defer arm): the park guard is
  `armed && !v.isInstanceOf[Binding[?, ?, ?, ?]] && Safepoint.stopped(slot)`. It refuses to park
  only when the payload already *is* the Binding node. In the reproduction the parked shape is
  `Defer(1, Id, Id)` with the binding-building map arrow sitting as a *stack entry* above: the
  resource (the settled `1`) exists, no `Finalizer` was registered, and the park's finalizers
  span is empty.
- `Eval.scala:645-655` (`finalizeResources`): releases only what `park.finalizers` holds, so the
  abandonment releases nothing. The invariant claimed at `Effect.scala:102-103` and
  `Eval.scala:432-434` ("no slice can end between the resource existing and the scope that owes
  it being installed") does not hold: the eval never stops in front of a Binding *node*, but it
  stops in front of the step that builds one.

### Proposed fix

Three coordinated pieces. The equation is unchanged (`bracket = acquire, then bind`); what changes
is that the bind step becomes *visible* to the park guard and *ungated* on delivery, and the
Binding install becomes the park point the refused park is deferred to.

**(1) A recognizable, ungated bind step.** New arrow kind in `Arrow.scala`, beside `Region`
(taxonomy home; it is an arrow-classification with evaluator obligations, exactly what the
`Step`/`Region` doc block there describes). It is a `Step` and not a `Region`, so the pos-1
fast-path gates and `dump`'s folds treat it exactly as they treat the map arrow it replaces:

```scala
/** The step a bracket's pending acquire settles into: applying it is what turns the resource
  * into the scope that owes its release, so the eval must never end a slice between the two.
  * The eval's park guard refuses to park a settled value about to flow into one of these, and
  * the apply below runs without the budget gate: with a stop pending the budget stays drained,
  * so a gated apply would defer against that refusal forever. The body allocates one node and
  * runs no user code, which is what makes skipping the gate safe.
  */
abstract private[kyo] class BindingStep[A, B, -S] extends TransformBase[A, B, S]:
    // re-abstracted: the settled arm below calls it, and the inherited delegation through
    // `this(v, id)` would recurse
    override def apply(v: A): B < S
    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        v match
            case kyo: Kyo[A, S2] @unchecked => Effect.defer(kyo, this, next)
            case _                          => next(apply(Nested.unnest(v)), Arrow.id)
end BindingStep
```

**(2) `Effect.bracket` stops going through `map`.** A settled acquire binds immediately (the
resource exists at construction time; the Binding node is the value, and the existing
`!v.isInstanceOf[Binding]` guard plus piece (3) protect it from there). A pending acquire settles
into the `BindingStep`:

```scala
val acq = acquire // bound once: `acquire` is inline and two reads would build it twice
acq match
    case kyo: Kyo[A, S] @unchecked =>
        Effect.defer(
            kyo,
            new BindingStep[A, B, S]:
                def frame = _frame
                override def apply(resource: A): B < S =
                    new Binding[A, Nothing, B, S]:
                        def frame                  = _frame
                        def tag                    = Absent
                        val bound                  = Maybe((_: Maybe[A]) => resource)
                        override val release       = Maybe(_release)
                        def resume(held: Maybe[A]) = _use(resource)
        )
    case _ =>
        val resource = Nested.unnest(acq)
        new Binding[A, Nothing, B, S]:
            def frame                  = _frame
            def tag                    = Absent
            val bound                  = Maybe((_: Maybe[A]) => resource)
            override val release       = Maybe(_release)
            def resume(held: Maybe[A]) = _use(resource)
end match
```

The two Binding constructions are textual copies, kept parallel (the `*With` precedent). The
settled arm is strictly better than today's: it removes even the construction-time deferral the
gated map could take.

**(3a) The park guard refuses a park whose settled payload is about to flow into a
`BindingStep`.** The receiver of a settled Defer payload is: `contA`'s first link if `contA` is
not `Id`, else `contB`'s first link, else the top stack entry's first link. First links are
resolved structurally (`Chain` delivers through `a`, `AndThen` through `t`; every site that
defers a settled value preserves the first link there: `Chain.apply` defers with itself or with
`(a, b)`, `AndThen` never defers whole, the park rebuild preserves `contA`/`contB`, `nextAnswer`
rebuilds preserve `contA`):

```scala
// Eval object, near the dispatches
/** The first link a delivered value reaches: a chain delivers through its head, a folded run
  * through its first step, anything else is itself the receiver. */
@tailrec private def receiver(a: Arrow[?, ?, ?]): Arrow[?, ?, ?] =
    a match
        case c: Chain[?, ?, ?, ?]         => receiver(c.a)
        case c: Arrow.AndThen[?, ?, ?, ?] => receiver(c.t)
        case _                            => a
```

```scala
// nested in Eval.apply, beside `park`
/** Whether a slice may end in front of this deferral. Consulted only with a stop already
  * pending. It may not when the payload is a binding, or a settled value whose first receiver
  * is the step that binds it: either way a resource would exist that no drain can find, so the
  * eval carries it the one step further into the scope that owes it, which parks itself. */
def parkable(v: Any, contA: Arrow[?, ?, ?], contB: Arrow[?, ?, ?]): Boolean =
    !v.isInstanceOf[Binding[?, ?, ?, ?]] && {
        v.isInstanceOf[Kyo[?, ?]] || {
            val r =
                if !(contA eq Arrow.Id) then receiver(contA)
                else if !(contB eq Arrow.Id) then receiver(contB)
                else if !stack.isEmpty then receiver(stack.entry(0))
                else Arrow.Id
            !r.isInstanceOf[BindingStep[?, ?, ?]]
        }
    }
```

The Defer arm reorders so the structural tests sit behind the poll:

```scala
val v = kyo.value
if armed && Safepoint.stopped(slot) && parkable(v, kyo.contA, kyo.contB) then
    park(Effect.defer[a, b, A, S](v, kyo.contA, kyo.contB))
else ...
```

A *pending* payload always parks (the resource does not exist until the acquire settles; a
resource settling later inside that evaluation re-decides at its own Defer arm, where the stack
and conts are visible again). A composed park inside the payload stays the holder's problem, per
`finalizeResources`' documented contract.

**(3b) The Binding arm becomes the deferred park point.** Without this the refused park makes the
reproduction *complete* instead of parking (`p.evalNow.isEmpty` would fail): the resource flows
into the `BindingStep` unconditionally, the Binding installs, and a settled `use` runs to the end.
The slice must instead end right after the scope is installed, which is the first instant a park
is sound again. In the Defer/Binding arm (`Eval.scala:529-546`), after the finalizer is pushed:

```scala
if kyo.bound.isDefined then
    stack.push(kyo)
    val held = stack.state[v](0)
    kyo.release.foreach { release =>
        val fin = new Finalizer(release, held.getOrElse(bug("bound value missing")))
        stack.pushFinalizer(fin)
        stack.push(fin)
    }
    // a stop that was refused in front of the binding is honored here: the scope is installed
    // and its release is where a drain can find it, so the slice may end. Only a binding that
    // owes a release refuses the earlier park, so only that one owes this one; the resume
    // stays unread and runs on the slice that comes back
    if armed && kyo.release.isDefined && Safepoint.stopped(slot) then
        park(Effect.deferInline(kyo.resume(held)).asInstanceOf[Any < Nothing])
    else loop(kyo.resume(held))
```

Gating on `kyo.release.isDefined` keeps plain context bindings (no release) exactly on today's
path, so no existing ContextEffect park position moves.

### Why this closes the window (safety argument)

Walk of every stop placement in the reproduction and its neighbors:

- Stop lands before the acquire's payload runs: the payload is pending or the Defer's payload is
  a Kyo; the park is allowed, the resource does not exist, nothing is owed.
- Stop lands as the payload settles (the reproduction): `parkable` sees the settled resource with
  `entry(0)` (or `contA`) resolving to the `BindingStep`, refuses; delivery through the settled
  arm applies the step *unconditionally* (no budget gate, so no defer-against-refusal livelock,
  which is why (1) and (3a) must ship together); the Binding installs entry plus finalizer; (3b)
  parks with the finalizer in the snapshot. Abandonment releases.
- Stop lands after the guard's poll read but before the Binding installs: the settled arm and the
  `BindingStep` apply have no park points; the next decision point is (3b)'s poll, which sees it.
- Stop lands after (3b)'s poll: the use is running; every subsequent deferral parks with the
  finalizer already in the side array and snapshot (the existing green tests at EffectTest:773,
  791, 809 pin exactly this).
- Effectful acquire (`Defer(suspend, BindingStep)`): the step is folded or popped as the fast-path
  `k` exactly as the map arrow was (it is a plain `Step`, not a `Region`, so the pos gates are
  unchanged). An answers-loop bail resuspends with the step inside the rebuilt suspension's cont:
  payload pending, park allowed, resource not made. A settled clause answer delivers through
  `k` inline (`AndThen` peels, `BindingStep` applies ungated) into the Binding arm, whose poll
  parks. A drained delivery that re-defers puts the step (or a chain headed by it) in `contA`,
  which `receiver` resolves.
- Multi-shot/replay: the `BindingStep` is pure construction (closes over the resource only in the
  settled-acquire arm, where it is a value); replaying a captured continuation containing it
  builds a fresh Binding whose finalizer is registered at install, the same discipline as today.
- Cross-thread: the poll reads the slot without consuming (`Safepoint.stopped`), so the guard,
  the Binding-arm poll, and the answers-loop bail all see one pending stop consistently; it is
  consumed once at the slice boundary as before.

### Alternatives considered and rejected

- **Have `finalizeResources` discover the resource in the parked value.** Impossible: the
  resource lives in the step's closure, structurally opaque.
- **Refuse to park on any settled payload.** Livelock: with a pending stop the budget stays
  drained, every gated apply re-defers the same settled payload, and the refusal loops forever.
  The ungated `BindingStep` apply is what breaks this cycle, narrowly, for the one step that
  needs it.
- **Resume the park in a special "run to the binding then abort" mode on abandonment.** A new
  evaluator mode and a violation of the abandonment contract (abandonment must not run the use).
- **Marking via a new Kyo node instead of an arrow kind.** The skill's "new node kind" signal was
  weighed: this is not an evaluator node and not new semantics; it is the existing combinator's
  existing step made identifiable, sibling to the `Region` classification that already exists for
  exactly this kind of obligation ("its presence among the entries is what makes it work").
- **Testing only `contA` for the marker.** Misses the reproduction itself (the step is a stack
  entry there, `contA` is `Id`) and the folded shapes (`AndThen`/`Chain` heads); hence the
  receiver walk over `contA`/`contB`/`entry(0)`.

### Performance

- Unarmed (full) evals: unchanged; `armed` still short-circuits first.
- Armed slices, steady state (no stop pending): the guard becomes `armed && stopped(slot)` before
  any type test, which *removes* the per-deferral `isInstanceOf[Binding]` from the not-stopped
  path (it moves inside `parkable`, behind the poll). One volatile-array read per deferral, as
  today.
- Stop pending (cold, at most once per slice): `parkable` costs a few type tests and a bounded
  head walk.
- The Binding arm gains `armed && release.isDefined && stopped` per bracket install: two field
  reads and, only in armed slices, the slot read. Binding installs are per-bracket, not per-map;
  and for full evals it is a single short-circuited boolean.
- `bracket` construction: pending arm allocates Defer + BindingStep (parity with today's Defer +
  map arrow); settled arm allocates the Binding only (parity, minus the gated-defer case today
  could take). Inline expansion grows by the duplicated Binding body; compile-time note, no
  runtime cost. No new implicit-lift summons are introduced (all calls are typed kernel calls);
  the clean-batch-build check applies as it does to any kernel edit.

### Intrusiveness

- `Arrow.scala`: new `BindingStep` (~15 lines) beside `Region`.
- `Effect.scala`: `bracket` body (~30 lines changed), comment updated to describe the step.
- `Eval.scala`: Defer-arm guard (~5 lines), `parkable` nested def (~15 lines), `receiver`
  (~8 lines), Binding-arm poll (~5 lines), plus the amended arm comment.

### Tests that flip

EffectTest:859. Existing greens preserved and re-argued above: EffectTest:680-829 (bracket and
park family, including the cross-thread abandonment at :727), the every-step slice walk, and
ArrowEffectTest's park pins.

---

## F4 — clause-throw scope semantics (both rulings designed)

### Root cause

When a handler clause throws, which scopes standing between the suspension and the handler answer
the failure depends on the dispatch path:

- `dispatchLoop` / `dispatchLoopState` general paths (`Eval.scala:156-160`, `:286-290`): the
  clause runs with the interior entries standing; the unwind meets an interior `Catching` first,
  so it answers (`viaLoop == -1`).
- All fast paths and answers loops (`Eval.scala:190-193`, `:233-236`, `:326-331`;
  `Handler.scala:151-156`, `:172-178`, `:254-261`, `:279-287`, `:352-358`, `:400-404`): the cell's
  `cont` is re-pushed on throw, restoring the interior; same answer.
- `HandlerCont` general path (`Eval.scala:465-469`): `stack.dump(pos)` folds the interior into `k`
  *before* `h.run(kyo.input, k)`; the catch does not re-push, so the interior is skipped and the
  throw escapes to the exterior (`viaCont == -2`).

The pinned test asserts the two families agree; the ruling (KERNEL2-CLAUSE-THROW-RULING.md) picks
which value they agree on. Both fixes follow, complete, so either ruling executes immediately.

### Option A — interior recoveries answer clause throws (loop behavior becomes law)

One edit. The `HandlerCont` general path re-attaches the folded continuation before rethrowing,
which is precisely what every other path already does:

```scala
// Eval.scala:465-469
val k = stack.dump[OX[CX], AX, EX & SX](pos)
val next =
    try h.run(kyo.input, k)
    catch
        case ex: Throwable =>
            stack.push(k)
            attachThrow(ex, kyo, Arrow.id[Any], stack)
loop(next)
```

`push` flattens the chain, so the interior `Catching`, finalizers, bindings, and any nested
stateful handler (re-wrapped by `dump` with its live state as `initialState`, re-installed by
`put` from exactly that) come back as entries; the unwind then treats them as the loop family
does. `attachThrow`'s `next` argument becomes `Arrow.id` because `k`'s frames are now on the
stack the trace walks (passing `k` too would double them).

Correctness notes:

- A clause that already applied `cont(x)` before throwing composed a value that the throw
  discards; re-pushing `k` cannot double-run anything: entries are re-installed, not applied, and
  a finalizer that already ran (a clause that internally evaluated the continuation) is
  CAS-guarded.
- Re-entering a recovery scope is sound by its own design ("re-entering the scope re-pushes its
  entry, and each entry can fail and recover", `Eval.scala:41-43`).
- Known residual under A, documented rather than fixed here: the suspended-clause lanes
  (`clauseSuspended`, `clauseSuspendedLoop`) fold `k` into the deferred outcome, so a throw
  raised later, while the clause's own effects are being answered, still bypasses interior
  recoveries. Making that lane A-conformant would mean re-attaching `k` from inside a value
  position, a genuinely new mechanism. The pinned test does not reach it; it is the strongest
  standing argument for Option B.

Cost: zero hot-path effect (the throw lane only). Intrusiveness: one site, ~2 lines.
Tests: ArrowEffectTest:1907 flips (`-1 == -1`); :1921 stays green (no interior regions there);
EffectTest:914 stays green (the re-pushed `k` is the bracket step, no finalizer exists yet).

### Option B — clause throws escape the region (cont-general behavior becomes law)

The law: a clause's throw unwinds past interior entries; interior *finalizers release during the
escape with the failure*, interior *recoveries are passed over*; the handler entry itself stays
for the normal unwind (so `handleCatching`'s own `Recover` still covers its clause, per its doc,
`ArrowEffect.scala:333-338`).

**(B1) A targeted escape walk on `Stack`:**

```scala
/** Pops `n` entries for a failure that must leave the region rather than be answered inside
  * it: releases run with the failure, recoveries are passed over, and a release that throws is
  * suppressed onto the failure rather than replacing it. What stands below the popped run is
  * left for the normal unwind to consult. */
def escape(n: Int, ex: Throwable): Unit =
    var i = n
    while i > 0 && !isEmpty do
        i -= 1
        pop() match
            case f: Finalizer[?, ?] =>
                try f.run(Result.panic(ex))
                catch case t: Throwable => if t ne ex then ex.addSuppressed(t)
            case _ => ()
end escape
```

(The guarded call is F1's convention; recoveries and plain steps fall to `case _` and are
discarded.)

**(B2) The general loop paths escape before rethrowing.** `dispatchLoop` (`Eval.scala:157-160`)
and `dispatchLoopState` (`:288-290`): the trace is attached *before* the escape pops the entries
it describes, then the interior is escaped, then the throw:

```scala
catch
    case ex: Throwable =>
        EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
        stack.escape(pos, ex)
        throw ex
```

**(B3) The `HandlerCont` general path escapes the folded continuation** instead of discarding it
(today it discards; the interior finalizers must now release during the escape rather than at
eval end with `Abandoned`):

```scala
catch
    case ex: Throwable =>
        EffectTrace.attach(ex, kyo, k, stack)
        val before = stack.size
        stack.push(k)
        stack.escape(stack.size - before, ex)
        throw ex
```

(`push` flattens; the size delta is exactly the entries `k` carried; the escape consumes exactly
those and leaves the handler for the unwind.)

**(B4) The fast paths and answers loops must distinguish two throw kinds.** A `handle[...]`
throw is the clause failing (escape); a `k(...)` throw inside the loop is the *region body*
failing (interior participates, exactly today's re-push). The cell gains one more meaning of
`kind` on the exception lane: `Out.ClauseThrew` (`inline def ClauseThrew: Int = 4` in
`Handler.Out`, `Handler.scala:385-391`; the new sites use the named constants, retiring part of
the audit's N5 literal drift):

- `answersLoop` clause catch (`Handler.scala:153-156`), `answersLoopState` clause catch
  (`:257-261`), `answersCont` clause catch (`:352-358`): set `out.kind = Out.ClauseThrew` (state
  commit unchanged where present).
- The `k(...)` catches (`:174-178`, `:281-287`) keep `out.kind = Out.Answered` as today.
- The generic bodies `HandlerCont.answers` (`:400-404`), `HandlerLoop.answers` (`:425-427`),
  `HandlerLoopState.answers` (`:470-472`) wrap their `run`/`answer` delegation in
  `try ... catch { case ex => out.kind = Out.ClauseThrew; throw ex }` so a throw through the
  generic path carries the right lane.

The three fast dispatch catches (`Eval.scala:190-193`, `:233-236`, `:326-331`) then branch:

```scala
case ex: Throwable =>
    // (state commit first, in the stateful dispatch, as today)
    if (out.cont ne null) && !(out.cont eq Arrow.Id) then
        if out.kind == Out.ClauseThrew then
            val before = stack.size
            stack.push(out.cont)
            stack.escape(stack.size - before, ex)
        else stack.push(out.cont)
    attachThrow(ex, kyo, out.cont, stack)
```

The escape on fast paths is not merely defensive: the loop's `k` evolves through `nextAnswer`
from decomposed deferrals whose `contA` can be a dumped, region-bearing chain, so a clause throw
mid-loop can hold interior finalizers in `k`.

Correctness notes for B:

- The pinned test: both families give `-2` (interior `Catching` passed over on every path,
  exterior answers); :1921 unchanged (`-2 == -2`, no interior regions).
- `handleCatching` self-coverage preserved: the escape stops above the handler entry; the unwind
  pops it next and its `Recover` answers, keeping `Abort.run { throw ... }` semantics.
- EffectTest:914 stays green (escape pops the bracket step; no finalizer exists).
- The "third continuation application threw" green test stays green: that throw is a `k(...)`
  throw, `kind == Answered`, re-push as today.
- Known residual under B, symmetric to A's: a throw during a *suspended* clause's own effect
  processing has the folded `k` inside a discarded value; its finalizers reach the side-array
  drain (with the eval's real failure when nothing answers, with `Abandoned` when an outer
  recovery answers within the same eval). Bounded, documented.

Cost: cold (throw lanes) plus one `out.kind` write per throw; the inline answers templates grow
by one catch arm each (compile-size note). Intrusiveness: `Stack.scala` (+escape, ~15 lines),
`Eval.scala` (5 catch sites), `Handler.scala` (6 catch/wrap sites plus the constant); ~40-50
lines total.

### Comparison for the ruling's executor

A is one line and freezes the majority behavior, at the price of the recovery-surface objection
recorded in the ruling (an interior recovery answers the handler's bugs) and the suspended-clause
residual cutting *against* its own law. B matches the clause-scope law the signatures already
state, at ~25x the edit surface, with its residual cutting *with* its law. Both keep every listed
green test green; the pinned test flips under either. This document takes no position beyond the
ruling doc's recorded recommendation (B); both are ready.

---

## F6 / F6b — the eval's finally: a drain throw skips `Safepoint.restore`

### Root cause

`Eval.scala:625-631`: three sequential statements,

```scala
stack.drainFinalizers(failure)
Stack.release(stack)
Safepoint.restore(slot, saved)
```

`drainFinalizers` deliberately throws on the completing path when a release failed
(`Stack.scala:400`, correct per its contract); that throw skips the other two. Skipping
`Stack.release` costs a pooled stack; skipping `Safepoint.restore` leaves the slot holding the
inner eval's state, so an enclosing armed slice loses its armed bit and stops honoring stops
(F6b's reproduction), and the caller's budget/depth state is corrupted generally (F6's).

### Proposed fix and the exact ordering

```scala
finally
    // before the stack is pooled, and outside the catch above, so a release still runs when
    // the eval is leaving on an exception. One whose use completed already ran through its
    // arrow and is a no-op here.
    //
    // the drain deliberately throws on the completing path when a release failed, so the two
    // statements that must run on every exit stand in their own finally: the caller's
    // safepoint state comes back whatever the drain does, and the stack is pooled emptied
    // either way
    try stack.drainFinalizers(failure)
    finally
        Stack.release(stack)
        Safepoint.restore(slot, saved)
end try
```

Position of each element:

- **`drainFinalizers` first, in the outer try**: it needs the live side array, which
  `Stack.release` → `clear()` wipes; and it must be able to throw its completing-path failure
  outward, which an inner-finally position would let it do while the other two still run.
- **`Stack.release` before `restore`, both in the inner finally**: the relative order is kept
  from today (minimal diff); neither can throw (`clear` and `restore` are array stores), so the
  order between them is not load-bearing. Both being *inside* the inner finally is the fix: they
  run on the normal exit, on the unwind-failure exit, and on the drain-throw exit alike.
- The drain's loop already guards each release, so `pending` reaches 0 before its deliberate
  end-throw; the pooled stack is empty on every path.

No drain semantics change: a completing eval whose release failed still surfaces that failure
(the documented "silence there is invisible resource loss" rule), it just no longer takes the
caller's slice state with it.

### Alternatives considered and rejected

- **Make `drainFinalizers` never throw and return the failure for the caller to throw after
  restore.** Same effect, larger signature change, and it moves the contract ("a release that
  cannot run is a real error") out of the member that documents it; the callers in `StackTest`
  pin the throwing contract directly.
- **Reorder restore before the drain.** The drain's releases run nested evals that save/restore
  the slot themselves, so the order is observationally equivalent there; but drain-first is
  today's order and keeps the diff minimal.

### Performance

The nested finally adds exception-table structure, no instructions on the happy path. `Eval.apply`
is never inlined (documented at `Eval.scala:90-97`), so code-size is irrelevant.

### Intrusiveness

`Eval.scala`, ~4 lines.

### Tests that flip

ArrowEffectTest:1951 (F6), ArrowEffectTest:1932 (F6b). StackTest's drain pins are untouched (the
drain itself is unchanged).

---

## F7 — a throwing release during abandonment aborts the remaining releases

### Root cause

`Eval.scala:645-655`: `finalizeResources`' loop is unguarded:

```scala
var i = p.finalizers.size
while i > 0 do
    i -= 1
    p.finalizers(i).foreach(_.run(Result.panic(Finalizer.Abandoned)))
```

One throwing release aborts the walk; the releases below it (outer resources) never run. Same
shape as F1's unwind defect at a different site, and a direct violation of the drain's documented
rule ("a release that throws never stops the ones after it").

### Proposed fix

The drain's completing-path bookkeeping, verbatim in shape (abandonment has no failure in flight,
so: first throw held, later ones suppressed onto it, first rethrown once every release was
attempted):

```scala
case p: Park[?, ?] =>
    // backwards, since index zero is the outermost: a resource acquired inside another is
    // released before it.
    //
    // guarded the way the stack's drain is: a release that throws never stops the ones after
    // it. There is no failure in flight to attach to, so the first failure is rethrown once
    // every release was attempted, with the later ones suppressed on it
    val outcome                 = Result.panic[Nothing, Nothing](Finalizer.Abandoned)
    var first: Maybe[Throwable] = Absent
    var i                       = p.finalizers.size
    while i > 0 do
        i -= 1
        try p.finalizers(i).foreach(_.run(outcome))
        catch
            case ex: Throwable =>
                first match
                    case Present(fst) => if ex ne fst then fst.addSuppressed(ex)
                    case _            => first = Present(ex)
    end while
    first.foreach(throw _)
```

The `outcome` is hoisted (it was rebuilt per finalizer; the drain hoists its own). The identity
guard is the F1 convention; alongside this change, `drainFinalizers`' two existing
`addSuppressed` sites (`Stack.scala:393`, `:396`) gain the same guard.

Rethrowing rather than swallowing is deliberate and matches the drain: the test tolerates a
propagated `IllegalStateException` (it catches it) and asserts only that every release was
attempted; silence would be invisible resource loss.

### Safety, performance, intrusiveness

Abandonment is terminally cold. Cross-thread: `Finalizer.run` is CAS-gated, so an abandonment
racing a resume (EffectTest:970, green) is unchanged: whichever path wins the CAS runs the
release once, and a throwing loser cannot occur (the loser does nothing). `Eval.scala` only,
~14 lines. Flips EffectTest:948; keeps :809/:831/:848/:970 green (no-throw abandonments take the
same walk with an empty catch).

---

## F8 — the first-attach race on a shared exception

### Root cause

`EffectTrace.scala:150-156` (`carrierOf`) is check-then-act:

```scala
find(ex) match
    case Maybe.Present(carrier) => carrier
    case Maybe.Absent =>
        val carrier = new EffectTrace
        ex.addSuppressed(carrier)
        carrier
```

Two threads racing the first attach on one shared exception instance both observe `Absent` and
both `addSuppressed`, leaving two carriers where the invariant is exactly one. `splice` reads only
the first, so the second's frames are dead weight and the reproduction's
`count(_.isInstanceOf[EffectTrace]) == 1` fails.

### Proposed fix

Make find-or-add atomic under the same monitor the JDK already uses for the suppression list
(`addSuppressed` and `getSuppressed` both synchronize on the exception):

```scala
private def carrierOf(ex: Throwable): EffectTrace =
    // the find and the add must be one step: two threads racing the first attach on a shared
    // exception would otherwise both add a carrier. `addSuppressed` and `getSuppressed`
    // already synchronize on the exception, so this takes the same monitor they do, held a
    // few instructions longer; nothing user-written runs inside it
    ex.synchronized {
        find(ex) match
            case Maybe.Present(carrier) => carrier
            case Maybe.Absent =>
                val carrier = new EffectTrace
                ex.addSuppressed(carrier)
                carrier
    }
```

This is a concession in the kernel's fixed shape: justification (no CAS exists on the JDK
suppression list; a lock-free retry cannot *remove* a losing carrier, so "exactly one" is
unreachable without mutual exclusion), minimal scope (find-or-add only; the builder fill and
`installInto` stay outside), protection (the monitor is the one `addSuppressed` itself takes, so
lock ordering cannot invert: no other kernel code holds an exception's monitor while taking
another lock, `getSuppressed`/`addSuppressed` re-enter it harmlessly, and the critical section
allocates one stackless `EffectTrace` and runs no user code), pinning test (the reproduction).
The no-blocking rule is about effectful code suspending; this is the same class of
JDK-internal monitor use the trace machinery already relies on, on a path only taken while an
exception is being thrown. On JS the monitor is a no-op (single-threaded); on Native it works.

Out of the fix's scope, deliberately: the `installInto` append race and the `physical`
memoization race stay last-writer-wins. Both are bounded by construction (each writer's result is
`readLength + itsBudget <= MaxFrames`; the reproduction asserts boundedness and carrier count,
not append atomicity), and `reconstruct`'s enclosing try already guarantees the non-throwing
half. Serializing whole reconstructions on the exception's monitor was considered and rejected:
it widens the critical section around the builder walk (arbitrary node graphs) for no asserted
property.

### Performance

`carrierOf` runs only inside `reconstruct`, i.e. only while an exception is crossing an attach
site: cold by definition. Uncontended monitor enter/exit is nanoseconds; contention requires two
threads throwing the same exception instance simultaneously, which is the pathological case the
fix exists for.

### Intrusiveness

`EffectTrace.scala`, ~5 lines. Flips EffectTraceTest:348; the single-threaded trace suite is
unaffected (re-entrant monitor, same results).

---

## Implementation order

1. **F1/F1b** (`Stack.unwind` + the eval's retry catch). Highest severity; establishes the
   guarded-release and identity-guard conventions the later fixes reuse; flips EffectTest:875
   and :892.
2. **F6/F6b** (the eval's finally). Two-line, same file as F1's second half; with F1 in place the
   drain now receives the *real* final failure, and with F6 the drain's own throw can no longer
   corrupt the caller's slot. Flips ArrowEffectTest:1951 and :1932.
3. **F7** (`finalizeResources`) plus the `drainFinalizers` identity-guard hardening. Independent,
   small, same conventions. Flips EffectTest:948.
4. **F8** (`carrierOf` monitor). Fully independent file. Flips EffectTraceTest:348.
5. **F2** (`BindingStep`, `bracket`, park guard, Binding-arm park). The largest change; lands
   after the exception-lane fixes so its new park position is exercised against the corrected
   unwind/drain behavior. Flips EffectTest:859.
6. **F4** (after the ruling). Option A is one line in `Eval.scala`; Option B builds directly on
   F1's guarded-release idiom (`escape` is its sibling) and should land after F2 so its
   fast-path escape sees `BindingStep` as an ordinary step. Flips ArrowEffectTest:1907, keeps
   :1921 green.

Interactions to hold in mind while executing:

- **F1 ↔ F6**: after F1 the unwind guarantees the failure the eval records is the one actually
  leaving; F6 guarantees the drain's completing-path throw cannot skip the restore. Together they
  close the audit's "drain told Absent / restore skipped" chain end to end.
- **F1 ↔ F4-B**: `escape` is `unwind`'s finalizer arm with recoveries passed over; it must use
  the same guarded call and identity check, and its throws suppress onto the clause's failure.
- **F2 ↔ F4**: `BindingStep` is a plain `Step`, so the pos-1 fast gates, `dump` folds, and (under
  B) the escape walk treat it as they treat any step; under A a re-pushed `k` containing one is
  re-installed as an ordinary entry. No coupling beyond that.
- **F2 ↔ F6**: the new Binding-arm park ends the slice with the finalizer in the side array and
  the snapshot; the parking eval's own drain still runs nothing (the stack is cleared into the
  park), which the F6 restructure preserves.
- Every kernel edit here is subject to the clean-batch-build verification
  (`sbt --batch 'kyo-kernel2JVM/clean' 'kyo-kernel2JVM/compile'`); none of the fixes introduces a
  new implicit-lift summon, but F2 touches `Arrow.scala` and `Effect.scala`, both in the fragile
  equilibrium's blast radius.
