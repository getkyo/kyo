# Bracket as an effect: expressivity against kernel2 as it stands

Read at worktree HEAD `c30c02ee28` (branch `worktree-effervescent-painting-backus`), which contains
`90d1a4c285` (handleFirst as a fourth handler kind, handleCatching by composition). Old-kernel
citations are against `origin/main`. Every claim about existing code carries a file:line citation
naming its codebase. This is an expressivity analysis; no source file was edited and no build was run.

Scope exclusion honored throughout: fibers, scheduling, interruption, preemption masking, and
`IOTask` are out of scope. Section 8 marks the two places where an integration concern would attach,
and designs for neither.

---

## 1. Executive summary

A bracket with the fixed contract is **not** expressible over kernel2's current algebra, and the
boundary sits in a more specific place than "the discard path is hard". Release obligations can be
made to *survive* a discard: one stateful registry region installed below every bracket keeps its
state across a truncation, because a state update replaces that cell and path-copies the cells above
it (worktree `kyo-kernel2/.../internal/Eval.scala:58-61, 284-316`, pinned by
`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:328-334`) while a truncating
`done` continues at `node.prev`, which is that same surviving cell (`internal/Eval.scala:68-69, 85-86`).
What cannot be expressed is running them **at** the discard. Between the truncation and the surviving
registry cell the algebra offers a bracket exactly one observable event, its own region's exit, and
that event is strictly later than the truncating region's exit and everything composed after it. So
the contract splits three ways. Normal completion, release before downstream, and innermost-first
nesting are tier (a) today, as a region whose exit arrow is the release (worktree
`kyo-kernel2/.../ArrowEffect.scala:186-221` with `internal/KyoInternal.scala:111-124`, which makes
composition after a region land after its exit). Deferred release, running at a registry region's exit
rather than at the discard, is tier (b) plus one restored signature, since kernel2's stateful region
exposes its state to its clause and never to its exit where `origin/main` had
`done: (State, A) => B` (`origin/main kyo-kernel/.../ArrowEffect.scala:530-536`); that tier is exactly
the guarantee level the old kernel shipped. In-band release on a discard is tier (c), as is every
throw that leaves the evaluator by the Java stack: a throw raised inside a nested region's body is
pinned as deliberately not covered by `Effect.catching`
(`kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:573-582`), which means nested
brackets do not compose on the throw path at any tier below (c). Separately, a `Handler.Cont` or
`Handler.First` clause that never invokes the continuation it was handed is not merely unobservable
but **undecidable** at evaluation time, because the continuation is a closure the clause may call
zero, one, or many times, at any later point (`internal/Eval.scala:88-93, 94-102`). The minimal kernel
assist that closes the decidable part is a **per-cell disposal hook run by the evaluator over the
cells a truncation discards, plus one catch site at the evaluator's own boundary**; that assist is the
essential content of both sibling designs, and everything else they carry is convenience.

**Boundary statement (the primary output) is section 5.**

---

## 2. What kernel2 already gives a bracket for free, and what it does not

Three facts about the current evaluator decide most of this analysis. Each is a property of the
representation, not of a mechanism, which is why they hold without anything maintaining them.

**2.1 Composition after a region lands after the region's exit.** `Kyo.Handled.map` chains onto the
*exit* arrow, not onto the region's value (worktree `kyo-kernel2/.../internal/KyoInternal.scala:111-124`;
the same shape for `HandledState` at `:196-211` and `HandledFirst` at `:155-168`). So if the release
is a region's exit, `bracket(...).map(downstream)` runs release before downstream structurally. This
is the fix for defect class 1 of the drive-era prototype, recorded at `kernel2-finalizer-design.md:45-48`
("With the region extent defined implicitly as 'the whole cont chain', extension lands inside the
region"). In kernel2 that failure mode is unrepresentable.

**2.2 A discarded cell is a cell, and a discarded continuation step is not.** Consider the two
places a release can be attached. Attached as a plain `map` step, the release lives inside
`kyo.cont` of some suspension (worktree `kyo-kernel2/.../internal/KyoInternal.scala:56-77`); when a
handler answers that suspension with `done`, the continuation is dropped and there is no object the
evaluator can inspect, because an `Arrow` is an opaque function (worktree `kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala:11-27`).
Attached as a region's exit, the release lives on a `Handlers` cell (worktree
`kyo-kernel2/.../internal/Handlers.scala:15-44`), and at the moment of a truncation the victims are
exactly the cells between the current spine `hs` and the answering cell `node`: an enumerable,
walkable list. **Any assist at all requires the bracket to be a cell (or a node the evaluator
interprets); as a continuation step it is not merely unhandled, it is unreachable.**

**2.3 The truncation arms consult nothing.** The five truncation sites, all in worktree
`kyo-kernel2/.../internal/Eval.scala`:

| site | line | what it does |
|---|---|---|
| `LoopState` clause returns done | 68-69 | `loop(resume(node.exit, Nested.lift(done)), node.prev)` |
| `Loop` clause returns done | 85-86 | same |
| pending `LoopState` clause settles to done | 159-160 | `resume(node.exit, Nested.lift(done))` |
| pending `Loop` clause settles to done | 183-184 | same |
| `Cont` / `First` clause declines to resume | 88-93, 94-102 | the crossed cells live only inside the `cont` closure |

In every one of them the spine goes from `hs` to `node.prev` (or to `node`) in one step. Nothing
walks the difference. `kyo-kernel2/CONTRIBUTING.md:98` states this as intended semantics: "The
discarded cells' exits never run, which is exactly the semantics that `done` skips the inner scopes'
remainders", pinned by `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:100-112`
("a done climbs past an inner scope without running its remainder") and `:395-410` ("a done from an
outer region discards multiple inner scopes").

---

## 3. The candidate encodings

### 3.1 Encoding 1 (best of the library encodings): a region whose exit is the release

The maximal thing existing machinery expresses. Written against the current signatures
(`ArrowEffect.handleLoopWith` at worktree `kyo-kernel2/.../ArrowEffect.scala:132-161`,
`Effect.catching` at `kyo-kernel2/.../kernel/Effect.scala:15-20`). Uncompiled sketch:

```scala
// kyo-kernel2, kernel/Effect.scala (sketch)

// the internal effect: sealed and private[kernel], following the old kernel's
// Defer (origin/main kyo-kernel/.../internal/KyoInternal.scala:81). No user can
// construct or handle it, so its meaning is fixed by the one handler below.
sealed private[kernel] trait Bracket extends ArrowEffect[Const[Unit], Const[Unit]]

private[kyo] inline def bracket[A, B, S](
    inline acquire: => A < S
)(
    inline release: (A, Maybe[Throwable]) => Any < S
)(
    inline use: A => B < S
)(using inline _frame: Frame): B < S =
    acquire.map { resource =>
        ArrowEffect.handleLoopWith(Tag[Bracket],
            Effect.catching(use(resource)) { ex =>
                release(resource, Maybe(ex)).andThen((throw ex): B)
            }
        )(
            // never raised: the region exists to own an exit arrow
            [X] => _ => Loop.continue(())
        )(
            b => release(resource, Maybe.Absent).andThen(b)
        )
    }
```

Semantics this delivers, each traced to the evaluator:

- **Normal completion.** `use` settles, the settle arm pops through the cell's exit
  (worktree `kyo-kernel2/.../internal/Eval.scala:129-133`), the exit is the release-then-value arrow.
  The exit's *result* is looped at `node.prev` (`:132`), so effects raised by the release resolve
  against the handlers outside the bracket, which is the correct position for a release.
- **Release before downstream.** By 2.1. Structural.
- **Innermost-first under nesting.** Popping is following `prev` (`kyo-kernel2/CONTRIBUTING.md:86-94`),
  so nested bracket cells exit in reverse entry order. Structural.
- **A settled `use` still releases.** The `*With` variants apply `cont` strictly when the handled
  value is not a computation (worktree `kyo-kernel2/.../ArrowEffect.scala:156-159`). The non-`With`
  `handleLoop` returns the value untouched (`:124-127`), so the release must be the exit, never an
  identity-exit region with a trailing `map`.
- **Throws in `use`'s own chain.** Covered: the guard rewraps each resumed continuation
  (worktree `kyo-kernel2/.../kernel/Effect.scala:26-52`), pinned green at
  `kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:524-534` and `:551-556`.

What it does not deliver, and why:

- **Throws raised inside a nested region's body.** `guarded` rebuilds a `Kyo.Handled` as
  `Handled(kyo.value, kyo.handler, guard(kyo.exit))` (worktree `kyo-kernel2/.../kernel/Effect.scala:64-65`):
  the region's *value* is not guarded. Its body evaluates in the flat loop with no Java frame of the
  guard on the stack, so the throw leaves `evalLoop` entirely; `Eval.apply` has no `try`
  (worktree `kyo-kernel2/.../internal/Eval.scala:16-22`). Pinned as intended at
  `ArrowEffectTest.scala:573-582` ("a throw inside a region nested in the computation is not recovered").
  **Consequence: nested brackets do not compose on the throw path.** Every bracket is a region, so
  an inner bracket's rethrow escapes the outer bracket's guard, and the outer release does not run.
  A bracket that only works at depth one is not a bracket.
- **Every discard shape.** By 2.3.
- **Exactly once.** A `Cont` continuation crossing the cell rebuilds it per call
  (worktree `kyo-kernel2/.../internal/Eval.scala:91-92, 111-114, 270-282`), so the exit runs once per
  shot against one `acquire`. A once-guard is needed, matching `kernel2-finalizer-design.md:214-216`
  (Amendment 3).

**Observation that matters for the architecture question:** in Encoding 1 the effect is vestigial.
The `Bracket` operation is never raised; the tag exists only to give `handleLoopWith` something to
key on. Strip it and the encoding is `use.map(release)` with the region kept solely for its exit.
So the honest statement of the library tier is: **the part of bracket that existing machinery
expresses is not an effect at all, it is one region with a release exit. Making it an ArrowEffect
adds a tag and a cell that every inner `find` walks past, and buys no capability here.** The effect
earns its place only in the registry shape of 3.2, where the operation is what carries a release
obligation out of arbitrary depth to a cell that survives a discard.

### 3.2 Encoding 2: the registry effect, and how far it actually reaches

The shape where the effect is real, and the one that decides the boundary. `Bracket` becomes
`ArrowEffect[Const[Bracket.Op], Const[Any]]` with `Op` = `Push(release)` and `Drain`, answered by one
stateful region installed at the boundary:

```scala
// sketch
ArrowEffect.handleLoop(Tag[Bracket], Stack.empty, v)(
    [X] => (op, st) => op match
        case Op.Push(rel) => Loop.continue(st.push(rel), ())
        case Op.Drain     => Loop.continue(Stack.empty, st.runAll)
)
```

This is the shape `Scope` already has on `origin/main`: a `ContextEffect` whose value registers
finalizers (`origin/main kyo-core/.../Scope.scala:51, 67, 145`), with the actual guarantee delegated
to one `Sync.ensure` at the boundary (`origin/main kyo-core/.../Scope.scala:134`), which itself
lowers to `Safepoint.ensure` (`origin/main kyo-core/.../Sync.scala:111`). **Registration is
expressible as an effect; execution at the right moment is not, and the existing library proves it
by delegating.**

**What this encoding does reach, and it is more than it first appears.** The registry's state survives
a truncation. A push raised anywhere inside `use` resolves by `find` to the registry cell, the clause
returns `Loop.continue(newState, ())`, and the evaluator rebuilds that one cell and path-copies every
cell above it, including the cell of the handler that will later truncate
(worktree `kyo-kernel2/.../internal/Eval.scala:58-61, 284-316`; pinned as "an interior stateful answer
preserves the state of a stateful region above it", `EvalTest.scala:328-334`). When the truncating
handler answers `done`, the loop continues at `node.prev` (`internal/Eval.scala:68-69, 85-86`), which
is the chain that ends in that updated registry cell. **The releases pushed above the truncation are
still in the surviving registry's state.** This is the one place the effect encoding is not vestigial:
registration from arbitrary depth without threading is exactly what an ArrowEffect buys.

Two things then block it, one fixable by a parity restoration and one not:

1. **A stateful region's exit cannot read its state**, so nothing can drain what survived.
   `handleLoopWith`'s exit is `cont: A => B < S3` (worktree `kyo-kernel2/.../ArrowEffect.scala:193`)
   and the settle arm resumes it with the value only (`internal/Eval.scala:133`). The old kernel had
   `done: (State, A) => B < (S & S2)` on the stateful `handleLoop`
   (`origin/main kyo-kernel/.../ArrowEffect.scala:530-536`); kernel2 dropped it. Restore it and the
   drain is one line in the registry's `done`. Without it the only pure trigger is a `Drain` operation
   raised by the computation, which the truncation skipped by construction. It can also be written
   with a mutable accumulator held by the handler object, which is what `origin/main`'s
   `Scope.Finalizer` is (`origin/main kyo-core/.../Scope.scala:145`), at the cost of being shared
   across every rebuild of the cell: wrong under multi-shot resumption, whose pinned semantics are
   capture-time state (`EvalTest.scala:286`), and wrong across park and resume.
2. **Even with the restoration, the drain runs late.** The registry's exit fires when the value
   settles at the registry cell, which is after the truncating region's exit and after every arrow
   composed onto it: in `Bracket.run(Abort.run(bracket(...)).map(g))`, `g` is chained onto the `Abort`
   region's exit (`internal/KyoInternal.scala:111-124`) and therefore runs before the drain. That
   violates the contract clause "Release runs BEFORE anything composed after the region, on every path"
   (`kernel2-finalizer-design.md:25-28`). No placement of the registry fixes it, because the registry
   must sit below every bracket, hence below every handler that can discard one.

Verdict: registration is tier (b); **deferred** execution at the registry's own exit is tier (b) plus
the restoration in 1, which is precisely the guarantee level the old kernel shipped (section 5.1);
**in-band** execution at the discard is tier (c). The restoration in 1 is worth a ruling on its own
merits regardless of which bracket architecture wins (section 9.5).

### 3.3 Encoding 3 (brief): the cooperating-discard protocol

Expressible today, and rejected on doctrine. Every handler that can discard raises a bracket
operation before it truncates:

```scala
// sketch: a discarding handler cooperating
Bracket.mark.map { m =>
    ArrowEffect.handleLoop(tag, v)(
        [X] => input => Bracket.drainTo(m).andThen(Loop.done(result(input)))
    )
}
```

This works mechanically. A clause that suspends before deciding runs at `node.prev`
(worktree `kyo-kernel2/.../internal/Eval.scala:74-76, 174-185`; `kyo-kernel2/CONTRIBUTING.md:99`), so
the drain resolves against the registry below and completes *before* the `done` reaches
`node.exit`, giving release-before-downstream on the discard path with correct innermost-first order.

It is still the wrong answer. The property "release ran" becomes a proof obligation discharged by
hand at every handler in the ecosystem, including handlers kyo does not own, and a `Cont` clause that
simply returns without raising anything (the `Choice.drop` shape, `origin/main kyo-prelude/.../Choice.scala:88, 98-103`,
where `Kyo.foreach` over an empty input never calls `cont`) silently opts out. That is exactly the
category `kyo-kernel2/CONTRIBUTING.md:33` rules against: "A property held by a dedicated mechanism
is ... a proof obligation discharged by hand at every edit, forever, and the mechanism is a habitat
for bugs."

### 3.4 Encodings considered and eliminated quickly

- **Universal interposition** (a bracket cell with a maximal tag so `find` resolves to it first, then
  re-raises the operation outward): impossible. A `Handler` clause receives `I[X]` and, for `Cont`,
  the continuation; it never receives the operation's `Tag` (worktree `kyo-kernel2/.../internal/Handler.scala:14-32`),
  so it cannot reconstruct the suspension to re-raise. And a re-raise from a `Loop` or `Cont` clause
  resolves back to the same cell, since the clause body runs at `node` (`kyo-kernel2/CONTRIBUTING.md:100, 110`).
- **`handleFirst` as the bracket region**: the cell leaves the spine as soon as it answers once
  (worktree `kyo-kernel2/.../internal/Eval.scala:99-102`), so it has no exit left for the rest of `use`.
  Its `done` clause does fire on settle at `n.prev` (`:134-138`), which makes it a usable
  end-of-evaluation hook for Encoding 2, but it cannot read state either (`.../ArrowEffect.scala:232`).
- **`handlePartial` driving `use`**: it parks at region nodes by design (worktree
  `kyo-kernel2/.../ArrowEffect.scala:270-283`, `kyo-kernel2/CONTRIBUTING.md:81`), so it cannot drive
  through a `use` that contains any handler, and its residual is the scheduler's concern anyway.
- **Discard-as-throw** (make every short-circuit unwind as an exception so `catching` sees it):
  changes every handler's semantics and still hits the nested-region hole of 3.1.

---

## 4. Discard-shape verdict table

Tier (a) = expressible as a library over the public algebra; (b) = expressible with a reserved
internal effect plus existing evaluation machinery; (c) = requires new kernel machinery. All
`Eval.scala`, `Effect.scala`, `Handler.scala`, `Handlers.scala`, `ArrowEffect.scala` citations are the
worktree `kyo-kernel2` sources at HEAD `c30c02ee28`.

| # | exit shape | expressible | tier | evaluator path that decides it |
|---|---|---|---|---|
| 1 | `use` completes normally | yes | a | settle arm pops through the cell's exit, `Eval.scala:129-133`; `map` chains onto the exit, `internal/KyoInternal.scala:111-124` |
| 2 | `use` completes as a settled value with no suspension | yes | a | `handleLoopWith` applies `cont` strictly, `ArrowEffect.scala:156-159`; the non-`With` form does not, `:124-127` |
| 3 | throw in `use`'s own continuation chain | yes | a | `Effect.catching` guard rewraps each resumed step, `kernel/Effect.scala:26-52`; pinned `ArrowEffectTest.scala:524-534` |
| 4 | throw after an inner region's exit | yes | a | the guard wraps region exits, `kernel/Effect.scala:64-74`; pinned `ArrowEffectTest.scala:551-556` |
| 5 | throw inside a nested region's **body** (includes any nested bracket) | **no** | c | `guarded` does not wrap `kyo.value`, `kernel/Effect.scala:64-65`; `Eval.apply` has no `try`, `Eval.scala:16-22`; pinned negative `ArrowEffectTest.scala:573-582` |
| 6 | `Handler.Loop` clause answers `Loop.done` (Abort short-circuit shape) | in band **no**; deferred yes | c in band, b deferred (needs 9.5) | `loop(resume(node.exit, done), node.prev)`, `Eval.scala:85-86`; pinned `EvalTest.scala:100-112, 395-410`; the registry cell below survives, `Eval.scala:58-61, 284-316` |
| 7 | `Handler.LoopState` clause answers `Loop.done` | in band **no**; deferred yes | c in band, b deferred | `Eval.scala:68-69` |
| 8 | a clause that suspends first, then settles to `done` | in band **no**; deferred yes | c in band, b deferred | `Eval.scala:159-160` (stateful), `:183-184` (stateless) |
| 9 | `Handler.Cont` clause returns without invoking `cont` (`Choice.drop` shape) | **no**, and undecidable | c + a semantic ruling | the crossed cells live only inside the closure `o => rebuild(hsAll, node, ...)`, `Eval.scala:88-93`; the closure may be called later, from anywhere |
| 10 | `Handler.First` clause returns without invoking `cont` | **no**, and undecidable | c + a semantic ruling | `Eval.scala:94-102`; the cell is off the spine before the clause's result runs |
| 11 | `Handler.Cont` clause invokes `cont` n>1 times crossing the bracket cell | runs release n times per one acquire | a (wrong semantics) | `rebuild` re-enters the cells per call, `Eval.scala:91-92, 111-114, 270-282` |
| 12 | unhandled suspension under `eval` | **no** | c | `throw new IllegalStateException`, `Eval.scala:49`; the whole spine is lost with no catch |
| 13 | `Eval.partial` yields a residual holding open bracket cells | not a discard: the cells are **in** the residual | a | `rebuild(hs, Empty, v)`, `Eval.scala:50, 270-282`. Whether the residual is then dropped is the fiber's concern (out of scope; R-B1 attaches here) |
| 14 | budget `Defer` between `acquire` settling and the bracket cell existing | nothing is lost inside an evaluation | a | the non-partial loop consumes the `Defer` and steps, `Eval.scala:103-108`; under `partial` it parks and the residual still carries the pending step, `:104-105` |

"Deferred" in rows 6 to 8 means the surviving registry region of 3.2 drains at its own exit, which is
after the truncating region's exit and after everything composed onto it. That is the old kernel's
guarantee level, and it requires the state-carrying exit of 9.5. "In band" means before anything
composed after the discarding region, which is what the fixed contract asks for
(`kernel2-finalizer-design.md:25-28`, `kernel2-backlog.md:18-20`).

Rows 6, 7 and 8 are one shape (a clause answering done) reached through four code paths, which is the
signal `kyo-kernel2/CONTRIBUTING.md:144` names: several failures sharing one mechanism means the
representation, not the patch count, is what should change. Rows 9 and 10 are a genuinely different
shape and are discussed separately in 5.2.

---

## 5. The boundary, stated exactly

### 5.1 The property that is not expressible

> **Knowing which releases are outstanding at a discard is expressible; running them at the discard is
> not. A stateful registry cell below every bracket survives a truncation with its pushes intact, so
> the set is recoverable. But the only event the algebra gives a bracket after a truncation is the
> settling of its own region's exit, and every such event is strictly later than the truncating
> region's exit and than every arrow composed onto it. Therefore no composition of `suspend`,
> `suspendWith`, the `handle` family, `Effect.catching`, the `Handlers` stack, and `Eval` can run a
> release before the discarding region's downstream, because interposing there requires reading the
> cells between `hs` and `node` at the instant the evaluator replaces the spine with `node.prev`, and
> nothing in the algebra runs at that instant.**

The argument in four steps, each grounded:

1. A release attached to a continuation is unreachable (2.2): arrows are opaque
   (`kyo/Arrow.scala:11-27`), and the continuation is dropped intact by the done arms
   (`internal/Eval.scala:68-69, 85-86`).
2. A release attached to a cell above the truncating cell is discarded with the cell, and the arms
   walk nothing between `hs` and `node` (`internal/Eval.scala:68-69, 85-86, 159-160, 183-184`), a
   property `kyo-kernel2/CONTRIBUTING.md:98` states as deliberate and `EvalTest.scala:100-112, 395-410`
   pin.
3. A release recorded in the state of a cell *below* the truncating cell does survive, because state
   updates rebuild that cell and path-copy the cells above it (`internal/Eval.scala:58-61, 284-316`,
   pinned `EvalTest.scala:328-334`) and the truncation continues at `node.prev`. So the set is
   knowable. This is why the registry encoding of 3.2 is worth taking seriously and why the old
   kernel's thread-local store (`origin/main kyo-kernel/.../internal/Safepoint.scala:101-105`, fed by
   `ensuring` at `:157-166`, deleted in kernel2 per `isolate-kernel2-design.md:25`) has a pure
   replacement here.
4. What has no replacement is the *timing*. The surviving cell's only observable moment is its own
   exit, reached after the truncating cell's exit and after every arrow chained onto it
   (`internal/KyoInternal.scala:111-124`, `internal/Eval.scala:132-133`). There is no user-reachable
   event between `resume(node.exit, done)` and the value arriving at the registry. Making one exist is
   Assist A.

Note which *contract clause* this blocks, and which it does not. On `origin/main`, `Safepoint.ensure`
runs the finalizer in band on exactly two paths: normal settle
(`origin/main kyo-kernel/.../internal/Safepoint.scala:188-190`) and a throw caught by `ensuring`
(`:161-164`). A handler discard was covered only by the fiber-level interceptor list, that is, at
fiber end. So the deferred tier reachable in kernel2 today (3.2 plus the restoration of 9.5) is the
old kernel's guarantee, and the clause that needs Assist A is **new strength relative to the old
kernel**, which `kernel2-finalizer-design.md:200-204` already flags. The boundary above blocks a
guarantee kyo has never shipped, not a regression.

### 5.2 The part that is not merely inexpressible but undecidable

Rows 9 and 10 are a different kind of negative and should not be folded into the same ask. When a
`Cont` or `First` clause is invoked, the evaluator hands it `o => rebuild(hsAll, node, ...)`
(`internal/Eval.scala:91-92, 97-98`) and continues with the clause's result. Whether the crossed cells
will be re-entered is decided by arbitrary user code, possibly after the clause's result has settled,
possibly never, possibly many times. There is no instant at which the evaluator can soundly conclude
"discarded":

- at clause invocation: premature, `Choice.run` calls `cont` per branch during the clause body
  (`origin/main kyo-prelude/.../Choice.scala:98-103`);
- at clause-result settle: sound only under the added assumption that continuations are consumed
  within the clause body's evaluation. That assumption holds for `Choice.run` and fails for stored
  continuations, the case `kernel2-finalizer-design.md:163-167` already names ("multi-shot resumption,
  `Batch`-style stored continuations");
- never: leaves `Choice.drop` inside a bracket leaking, which is what the old kernel did (the fiber
  drained it at fiber end).

So for rows 9 and 10 the deliverable is a **semantic ruling**, not a mechanism (section 9.2). No
assist can be correct without first fixing which of the three the contract means.

### 5.3 The minimal kernel assist

Two pieces, both small, both structural. Neither is designed here; they are stated at the precision
needed to size the sibling designs.

**Assist A: a per-cell disposal hook, run by the evaluator over the cells a truncation discards.**
A cell gains a way to say "I own a disposal obligation", and the four done arms
(`internal/Eval.scala:68-69, 85-86, 159-160, 183-184`) walk from `hs` to `node` before continuing,
running the obligations of the cells that carry one, innermost-first, with the outcome derived from
the truncating value. The walk has the same shape as `rebuild` (`internal/Eval.scala:270-282`), which
already walks exactly that segment, so the recursion carrier question that
`kyo-kernel2/CONTRIBUTING.md:115-121` demands an answer to is already answered: a flat `@tailrec` walk
over `prev`, like `rebuild` and `replace`.

Three things this assist must **not** be:

- It must not be "run the discarded cells' exits". Exits are typed `A => B` for the inner region's
  own result type (`internal/Handlers.scala:17, 26, 39`), and the truncating value has the outer
  handler's type. Running them is type-incorrect and semantically wrong: skipping inner remainders is
  the point of `done` (`kyo-kernel2/CONTRIBUTING.md:98`). A disposal hook is not an exit; it consumes
  an outcome, not the region's result.
- It must not be a store, neither the threaded one of `kernel2-finalizer-design.md:118-156` (which
  the `Kyo.Bracket` ruling already superseded, `iotask-kernel2-integration-r2.md:846-849`) nor the
  in-spine registry of 3.2. The set is the discarded segment of the spine, which the evaluator holds
  in a register at the moment it truncates, so nothing has to accumulate or reconcile it. That is the
  structural answer `kyo-kernel2/CONTRIBUTING.md:33` asks for, and it is the reason to prefer Assist A
  over the registry even though the registry reaches the deferred tier: the registry makes "the
  outstanding set is correct" a property maintained by push and pop discipline, and Assist A makes it
  a corollary of where the cells are.
- It must not need handler cooperation. Assist A gives R-B3 (`iotask-kernel2-integration-r2.md:897-909`)
  with no change to `Abort`, `Choice`, or any user handler.

**Assist B: one catch site that runs the disposal hooks of the cells it unwinds.** Row 5 and row 12
are not reachable by Assist A because they leave the evaluator by the Java stack, not by a spine
replacement. Two placements exist, and they are not equivalent:

- **B1, at the evaluator's boundary**: a `try` in `evalLoop` (or in `Eval.apply`,
  `internal/Eval.scala:16-22`) that on a non-fatal throw walks the standing spine, runs the disposal
  hooks innermost-first, and rethrows. This is exactly R-B2
  (`iotask-kernel2-integration-r2.md:884-895`). It also incidentally fixes a smaller live gap:
  `Eval.apply` currently loses its `Safepoint.restore` when a throw escapes (`:16-22` has no
  `finally` around `evalLoop`, against `save` at `internal/Safepoint.scala:143-147`).
- **B2, by extending `Effect.catching` to guard region bodies**: `guarded` would recurse into
  `kyo.value` (`kernel/Effect.scala:64-74`). This changes the pinned boundary of `catching`
  (`ArrowEffectTest.scala:573-582`) for every user, and it would still not cover row 12.

B1 is the smaller and the more structural of the two, and it is the one R-B2 actually requires.

**What is left over after A and B.** Rows 9, 10 and 11 (continuation drop and multi-shot). These are
value questions, not machinery questions, and section 9 raises them as rulings.

### 5.4 What this says about the two sibling designs

Stated without having read them (they did not exist in the tree at the time of writing; `ls
bracket-*.md` at HEAD `c30c02ee28` returns nothing):

- The **essential** content of any winning design is: (i) the release lives on something the
  evaluator can enumerate at truncation time, and (ii) the four done arms plus one catch site consult
  it. Any design that provides both closes rows 5, 6, 7, 8 and 12.
- Everything else in either design is **convenience**: where the acquire lives, whether the resource
  is carried by the node or by a closure, how the outcome is shaped, whether the release may suspend.
  Those affect ergonomics, allocation, and the fiber integration, not expressivity.
- The dedicated-node and dedicated-handler-kind architectures differ on exactly one expressivity-
  relevant axis: **whether the bracket participates in `Handlers.find`**. A cell participates
  (`internal/Handlers.scala:46-56` tests `tag <:< handler.tag` per cell), which costs every operation
  inside `use` whose handler is outside the bracket one `Tag.<:<` per bracket in scope. A node does
  not participate, but then it must be given a position in the spine anyway to be enumerable at
  truncation, which is the design question the node track owns.
- The bar either design has to clear is set by 3.2, not by zero. The deferred tier is reachable with
  one restored signature and no new kernel machinery, so what a sibling design must justify is
  specifically the step from deferred to in band (rows 6 to 8), plus the throw paths (rows 5 and 12),
  plus whatever ruling 9.2 lands on for rows 9 and 10. If the answer to 9.1 is that deferred release
  is enough, neither sibling design is needed for the discard path at all, and only Assist B remains.

---

## 6. Assessment of the internal-effect pattern for kernel2

### 6.1 How the old kernel's row absorption actually worked

`sealed private[kernel] trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]`
(`origin/main kyo-kernel/.../internal/KyoInternal.scala:81`). The absorption is not a type-level
trick in the effect declaration; it is in the node's constructor.
`abstract private[kernel] class KyoDefer[A, S] extends KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, S]`
(`:85`) pins the node's row slot `S` to the *user's* row, with `Defer` appearing only as the third
type argument, which is not part of `Kyo[A, S]`. So `Effect.deferInline` returns `A < S`
(`origin/main kyo-kernel/.../Effect.scala:68-78`) and the effect is invisible in every user signature.
Its meaning comes from the evaluator answering it by tag (`origin/main kyo-kernel/.../Pending.scala:407-411`,
`if kyo.tag =:= Tag[Defer]`), and it survives handler crossings because every handle loop rebuilds
foreign suspensions and recurses (`origin/main kyo-kernel/.../ArrowEffect.scala:137-142`). The
visibility (`sealed private[kernel]`) buys the soundness of the lie: since no user can write a
handler for `Defer`, the constructor's claim that the row does not contain it cannot be falsified.

### 6.2 The same move is available in kernel2, and is already used

kernel2's `ArrowEffect.suspend` returns `O[X] < E` (worktree `kyo-kernel2/.../ArrowEffect.scala:17-27`),
but a hand-written node may pin its row freely, and one already does:
`ContextEffect.suspend(tag, default)` constructs a `Kyo.Suspend[Const[Unit], Const[A], E, Any, A, Any]`
and returns `A < Any` (worktree `kyo-kernel2/.../kernel/ContextEffect.scala:38-48`), with the
sentinel probe at `:86-93` doing the same. So "kernel2 reserves internal effects" needs no new
machinery; it is one constructor site plus a `sealed private[kernel]` trait.

### 6.3 What it costs here that it did not cost there

Dispatch changed shape. In the old kernel, a suspension was tested by the handle loop it was standing
in, one tag comparison per crossing, and `Defer` had its own arm in `eval`. In kernel2 dispatch is
`Handlers.find`, a walk of the spine with a `<:<` per cell
(worktree `kyo-kernel2/.../internal/Handlers.scala:46-56`), and a **miss costs the full walk**
(`internal/Eval.scala:43-50`). `Tag.<:<` is `fastPathEqual` then a structural `checkTypes`
(`kyo-data/shared/src/main/scala/kyo/Tag.scala:106-107`), where `fastPathEqual` is a reference check
then a memoized-hash `String` compare (`kyo-data/.../Tag.scala:173-184`); a non-matching cell fails
the fast path and pays `checkTypes`. So an internal effect in kernel2 sits in one of two cost slots:

- **the find-miss slot**, which is free on hits. kernel2 already uses it for `Kyo.Defaulted`: the miss
  arm does a class test on `kyo.root` (`internal/Eval.scala:44-47`), costing nothing when a handler is
  found. An internal effect whose answer needs no cell can ride this slot at no hot-path cost.
- **a cell**, which costs one `<:<` on every `find` walk that passes it. This is the slot a bracket
  needs, because a bracket needs an exit and exits live on cells.

That is the cost statement: **an internal effect is cheap in kernel2 exactly when it needs no cell,
and a bracket needs a cell.**

### 6.4 What Defer-as-node in kernel2 says about where bracket belongs

kernel2 moved `Defer` out of the effect algebra into the node algebra: `Kyo.Defer` is a node shape
with its own `map` fusion (worktree `kyo-kernel2/.../internal/KyoInternal.scala:80-104`) and its own
evaluator arm (`internal/Eval.scala:103-108`), and it has no tag, no cell, and no participation in
`find`. The rule that migration encodes:

> An effect's meaning comes from a cell some handler put on the spine. A node's meaning comes from the
> evaluator. A kernel concern that must be interpreted where no handler exists is a node.

Bracket's discard obligation is interpreted precisely where no handler exists, in the truncation arms,
so by that rule bracket is not an effect. But bracket also needs something that runs when a value
settles through it, which nodes do not have and cells do. **Bracket is the first kernel concern
requiring both halves, which is why neither existing category fits and why the question was worth
splitting into three tracks.** The effect track's contribution is to fix what each half is worth. The
effect half is not worthless: in the registry encoding of 3.2 it is what lets a release be registered
from arbitrary depth without threading, and that is what carries rows 6 to 8 to the deferred tier.
But it is also not sufficient at any depth, because the timing the contract asks for is decided in
the truncation arms, where no tag is consulted and no clause runs.

---

## 7. Costs of the effect encoding, if it were taken

Qualitative, since no benchmark was run (`kyo-kernel2/CONTRIBUTING.md:168` requires a JMH A/B against
a frozen baseline for anything touching `Eval.scala`, `Kyo.scala`, or `Arrow.scala`, which is not this
document's tier).

**Per bracket, Encoding 1 (region with release exit):**

- one object at the handle site, serving as handler, region node, and exit arrow together
  (worktree `kyo-kernel2/.../ArrowEffect.scala:136-155`, whose comment states "one allocation");
- one `Handlers.Node` cell at region entry (`internal/Eval.scala:116`);
- the `Effect.catching` guard, which is the dominant cost: `guarded` allocates one `Arrow.Transform`
  per guarded step and re-guards recursively at every resumption
  (`kernel/Effect.scala:26-52`), so a `use` of n suspension points pays n guard allocations. The
  source itself flags this: "TODO this seems an expensive workaround for something that should be
  handled in Eval or Arrow?" (`kernel/Effect.scala:27`);
- zero suspension round trips, because the effect is never raised.

**Per bracket, Encoding 2 (registry):** add one suspension round trip per registration (a `Kyo.Suspend`
allocation, a full `find` walk, a clause invocation, and a `Loop.Continue` outcome box), plus one
`replace` path-copy of every cell above the registry cell per push
(`internal/Eval.scala:61, 284-316`). The path-copy is proportional to spine depth, which makes the
registry the most expensive of the three encodings on the hot path.

**Ongoing, for any cell-shaped bracket:** one extra cell on the `find` walk for every operation raised
inside `use` whose handler lives outside the bracket (`internal/Handlers.scala:46-56`), each paying one
failed `Tag.<:<`. Operations answered by a handler *inside* `use` stop before reaching the bracket cell
and pay nothing. Compared qualitatively to the sibling shapes: a node-shaped bracket avoids the `find`
participation entirely and pays instead in whatever the evaluator does to keep it enumerable; a
handler-kind-shaped bracket pays the `find` cost but reuses the cell machinery, `rebuild`, `replace`,
and the residual reification (`internal/Eval.scala:50, 270-282`) with no new node type in `map`.

The one place the effect encoding is cheaper than either sibling: it changes nothing in `Eval.scala`,
so it needs no JMH gate. That is also exactly why it reaches only the deferred tier, since nothing it
can write runs at the truncation.

---

## 8. Where an integration concern would attach (noted, not designed)

Two attachment points only, both explicitly out of scope here:

1. **The residual (row 13).** `Eval.partial` reifies the standing regions into the value
   (`internal/Eval.scala:50, 270-282`), so a bracket cell inside a parked remainder is data the
   remainder carries. R-B1 (`iotask-kernel2-integration-r2.md:865-870`) asks for an entry that runs a
   discarded remainder's outstanding releases; that entry would walk this reified structure. Nothing in
   this document's boundary blocks it, and the cell-based encodings make the walk possible in principle
   for the same reason Assist A is possible: the obligations are enumerable.
2. **The acquire-to-cell gap.** Inside a pure evaluation the gap costs nothing (row 14): the non-partial
   loop consumes budget `Defer`s and steps (`internal/Eval.scala:103-108`), and under `partial` the park
   happens with the pending step still in the residual (`:104-105`), so re-evaluating the residual runs
   it. The gap only bites when a residual is *abandoned* in the window where the resource exists and the
   cell does not, which is the fiber path. The structural implication for whichever design wins is one
   sentence: **the cell should be created by the same step that delivers the resource**, so no residual
   can exist in which the resource is held and the obligation is not enumerable. This document does not
   design that.

---

## 9. Open questions that genuinely require a maintainer ruling

**9.1 Is the in-band discard clause wanted?** Section 5.1 shows it is new strength: `origin/main`'s
`Safepoint.ensure` runs the finalizer in band only on normal settle
(`origin/main kyo-kernel/.../internal/Safepoint.scala:188-190`) and on a caught throw (`:161-164`);
handler discards were covered at fiber end by the interceptor list (`:101-105, 157-166`).
`kernel2-finalizer-design.md:200-204` raises the same point. If the answer is that the deferred tier
of 3.2 is enough, rows 6, 7 and 8 stop needing Assist A, the whole discard question collapses to the
signature restoration of 9.5 plus Assist B plus the fiber's abandonment entry, and neither sibling
design is needed for it. The backlog states the fixed contract as "release always executes on settle,
unwind, and discard" (`kernel2-backlog.md:18-20`), so the presumption is yes, but this is the single
ruling with the largest effect on how much kernel machinery is justified.

**9.2 What does a dropped continuation mean (rows 9, 10)?** Three candidates: (a) never released in
band, drained at the evaluation or fiber boundary, which is the old kernel's actual behavior;
(b) released when the clause's result settles, sound only if continuations are consumed within the
clause body, unsound for stored continuations; (c) released at the earliest discard with re-acquire on
resumption, which is not a bracket. This is a semantics ruling and must precede any mechanism.

**9.3 Multi-shot: release per shot or once?** A `Cont` continuation crossing a bracket cell rebuilds it
per call (`internal/Eval.scala:91-92, 111-114`), so the natural behavior is release-per-shot against one
acquire, which is wrong for a resource and right for a scope. The alternative, a once-guard
(`kernel2-finalizer-design.md:214-216`), makes the first shot's exit release and later shots run inside
a released resource. Neither is obviously correct; `Choice.run` over a bracketed `use`
(`origin/main kyo-prelude/.../Choice.scala:98-103`) is the concrete case to rule on.

**9.4 Assist B placement: evaluator boundary catch, or `Effect.catching` reaching into region bodies?**
5.3 recommends the boundary catch (B1), which is what R-B2 requires
(`iotask-kernel2-integration-r2.md:884-895`) and which also closes row 12 and the `Safepoint.restore`
leak at `internal/Eval.scala:16-22`. Choosing B2 instead changes a pinned public boundary
(`ArrowEffectTest.scala:573-582`) and still leaves row 12 open. Related standing item: the placement
TODO already recorded at `kernel/Effect.scala:27` and task #59 in `kernel2-backlog.md:91`.

**9.5 Restore the stateful region's state-carrying exit?** `origin/main kyo-kernel/.../ArrowEffect.scala:530-536`
has `done: (State, A) => B < (S & S2)`; kernel2's stateful `handleLoopWith` exit is `A => B < S3`
(`kyo-kernel2/.../ArrowEffect.scala:193`) and the settle arm resumes it with the value only
(`internal/Eval.scala:133`). This is the single item that decides whether the deferred tier exists at
all: with it, the registry of 3.2 drains what survived a truncation in one line of user code and kyo
reaches the old kernel's guarantee with no evaluator change; without it, the only trigger is a `Drain`
operation the truncation already skipped, or a mutable accumulator that breaks under multi-shot
(`EvalTest.scala:286`) and park-and-resume. It is also a plain parity gap worth deciding on its own
merits regardless of which bracket architecture wins.

**9.6 Does a release raised at a region exit belong outside the region?** Today an exit's result is
looped at `node.prev` (`internal/Eval.scala:132-133`), so a release raising an effect is answered by the
handlers outside the bracket, never by handlers installed inside `use`. That is almost certainly the
intent (a release should see `Sync`, not a `Var` region that has already closed), but the contract does
not state it and the bracket primitive will pin it either way.
