# kernel2 backlog

Queues: implementing, designing, awaiting ruling, next up, parked. Each item carries
its own context so it reads without the linked docs; the docs carry the full designs.
Done work is removed once acked. Last update: 2026-08-13.

## Done, awaiting your ack (removed from the file once acked)

### handleLoop family at old-kernel parity (`4e09878e0e`)

Your ruling executed: the family matches origin/main verbatim except the sanctioned
no-cont clause. Three overloads under one name (stateless; stateful delegating to the
canonical with an identity done; canonical stateful with handle and done in one
parameter list, done: (State, A) => B < (S & S2) observing the final state, bypassed
by a clause's Loop.done whose slot now types as B). Both handleLoopWith variants are
gone, handleFirst regained the old parameter list, effectTag/handle/done replaced
_tag/f/cont, and both exit-protocol gaps from the consumer audit are closed with
acceptance tests (an Emit.run-shaped accumulator asserting the final state reaches
done, a Parse-shaped bypass asserting the clause-built B skips the transform, the
delegation pin, and an effectful done). The isolating measurement that preceded the
ruling: answering through a passed continuation costs nothing at depth one (80.3 vs
81.2 us, allocation byte-identical), and the consumer audit showed no handleLoop site
in the codebase uses cont for anything but immediate application, so the no-cont
clause keeps the depth-independent in-place answering at no expressiveness loss.

### One-macro lift: CanLift carries the lint, the emission is macro-free (`79043c80a4`)

Your direction ("a single CanLift macro that derives, and lift just takes the
evidence"), landed with the old kernel's factoring and kernel2's soundness. The
pending lint rides the NotGiven parameter, resolved where the conversion is written
and baked, so generic paths are waived and stay sound through the runtime box; the
single macro rejects kyo modules; the lift body is macro-free (erasedValue casts the
trivial shapes, CanLift.lift boxes the rest at runtime). Failures found and fixed on
the way, each by probe: covariant evidence let the negation solve through Nothing so
the lint never fired (invariance is load-bearing and documented); the opaque-Boolean
strategy literal did not survive inlining proxies; a plain compiletime.error trap
replaced the abortCastUnit macro because a failed nested evidence inside a conversion
candidate surfaces as a plain mismatch, so the trap must win the search to speak.
Deleted: LiftMacro.scala, liftUnit (Unit rides liftAnyVal), one macro of two.
liftInternal stays with its true justification recorded: the kernel's core files sit
in a bootstrap cycle with any macro-bearing evidence, so the module cannot summon its
own derivation. The computation-as-data pins compile un-annotated and now verify the
boxed runtime path. Suite green: 686 passed, 1 ignored.

Gates: the 903 negative dotc fixture passes; allocation byte-identical on all six
guard rows (your boxing concern is settled: zero new allocation anywhere). Timing
verdicts are deferred to a quiet machine: a concurrent 32G kyo-core compile
contaminated the window (error bars 5 to 50x the morning baselines). Open watches for
the quiet re-measure: userTypesSkipKernelWrapping (37.7 noisy vs 34.0; the one
plausible real effect is the deliberate trade of the emission macro's static cast for
the runtime Boxed test on final classes, and if it confirms, the static analysis
returns as an inline arm without reopening the design) and the liftAnyVal
question (config B, deleting it, runs the same fixtures to decide whether it earns
its place).

### Lifting out of the box for Loop.Outcome: four designs built and gated, the winner is bare done plus the currency-shaped continue

Your goal ("making liftings work out of the box should be a goal here if possible")
drove a full design sweep, each variant compiled against the whole module and gated,
three of them through the full suite:

Plain bare constructors (origin/main's shape) leave 64 sites red, every one the
settled-answer clause a handler author writes (`[X] => _ => Loop.continue(21)`). The
settled-into-pending step is a conversion, and a conversion blocks expected-type
propagation into the constructor's type parameter: the answer type infers from the
argument and the invariant Outcome rejects it. The per-site fix is an ascription
naming the slot's exact effect row, unwritable in user code. origin/main never hits
this because its clause protocol passes the already-pending continuation
(`Loop.continue(cont(input))`), so the argument's type is the slot's type verbatim.

Outcome-lifting conversions in the companion (your "why doesn't it auto-lift"
direction, made concrete: `Outcome[A, O]` converts to `Outcome[A < S, O] < S2`) fix
all the plain settled sites but break effectful clause bodies: at
`say("pre").map(_ => Loop.continue(41))` the conversion search cycles against the
lambda's own result-type inference, dotc reports cyclic errors and exceeds its
recursion limit, and inferred rows degrade (`Int < Ask` where `Int < Any` inferred
before). A protocol that pushes the compiler over its recursion limit on ordinary
user code is disqualified.

The row on the payload (`Continue[+A, -S]` storing `_1: A < S`, `Outcome[A, S, O]`)
is the principled variance encoding: every site infers by plain subtyping, no
conversion exists anywhere, and the FULL SUITE PASSED (682 green, all 64 sites
inferring with zero ascriptions). It died on the measurement: the payload field
erases to Object where the old `_1: A` specializes to a primitive at the inline
site, so the settled value's box is stored in the heap object and escape analysis
cannot remove it. JMH on a 100k countdown through Loop's driver: 16 to 32 B/op and
166.7 to 489.7 us (2.9x), with the handler answer rows at exact parity (79.6/80.0,
119.2/119.0). Loop.apply is the workhorse under the whole stack, so this is
disqualifying by the performance-by-nature bar.

The remaining lever, the old `>: A` lower bound on `<` (bare constructors would then
infer by subtyping at zero cost), stays dead on correctness: it makes a nested
computation conform with no boxing point, the settled-nested confusion the lift
macro exists to intercept, which is why it was dropped in the first place.

A fifth design, from your "just place an explicit lift" question, was probed after
the landing and is viable: declare Outcome and Continue covariant in the payload
(legal, output-only) and keep bare constructors. Under invariance an explicit lift
must name the slot's exact effect row, which is unwritable; under covariance the
uniform ascription `x: T < Any` conforms to every slot by row contravariance. The
probe compiled the main sources green (ContextEffect's two generic sites take
`value0: A < Any`) and ArrowEffectTest's 25 settled sites went to zero with the
single uniform annotation, including the map-final clause bodies that cycled the
conversions design. The cost is the annotation itself, at every settled answer, the
dominant clause shape. The probe worktree is kept if you rule for this shape.

There is also the deeper fork you raised: kernel2's handleLoop clause diverges from
the old kernel's by not receiving the continuation (the payload is the answer, not
`cont(input)`), which is what makes the evaluator answer in place
(handleLoopAnswersInPlace 137.2 to 80.0 us against the old kernel) and also what
creates the settled-into-pending sites in the first place. Ruling the signature back
to the old kernel's shape restores bare symmetric constructors with no help, and
costs a continuation closure per answered operation plus clause-driven re-entry.

What landed meanwhile: `done` is bare (`def done[A, O](v: O): Outcome[A, O] = v`,
the ordinary value lift reaches it and its boxing arm keeps a computation-as-data
payload from reading as a suspension; full suite green), and `continue` keeps the
`<`-shaped return, the one design where settled answers infer with no annotation at
zero runtime cost. The constructor comment and CONTRIBUTING rule 4 record the tested
design space; awaiting your ruling between the three shapes (as landed; covariant
bare with the uniform ascription; old-kernel handleLoop signature).

### Final board rerun after the fix: six rows lifted, the sweep now closes clean
FB give me the perf comparison tabels in the console with emoji indicaiton of better/neutral/regression
The fix reached everything that pays budget entry on the eager path, not just the two
guard rows: userTypes 53.1 to 34.0 (0.78x old), uncached 51.5 to 37.6 (0.51x),
fusionPastBudget 48.1 to 33.6 (0.69x), idleHandler 48.3 to 33.0 (0.68x),
inlineLimitCosts 265.6 to 228.2 (0.66x). Full final board in
compile-execution-analysis.md, section "The fused enter() regression". Standing:
thirteen time wins, deepRecursion at parity (0.99x, the row you set the bar on),
three tracked gaps under 1.10x (foreignCrossings 1.08x time against a 0.90x
allocation win; sharedHandler 1.09x, the megamorphic dispatch row, already improved
4 percent by the Handlers encapsulation; continuationBodiesFuse 1.10x, one extra
word per fused body), and the two fusion-after-suspension rows carrying the
structural fork you ruled acceptable at the stop-optimization decision (1.79x and
the RunOnly microrow). Compile fixtures unchanged: the new kernel wins 12 of 13
with Baseline at parity, MapChainDeep100 at 0.39x.

### handleLoop consumer audit: all 52 sites fit the resume/stop envelope; two exit-protocol gaps found

Every ArrowEffect.handleLoop site in the old-kernel stack was read in detail
(kyo-prelude: Stream 24, Pipe 8, Sink 4, Emit 3, Var, Poll, Check; kyo-core:
StreamCoreExtensions 5; kyo-ai: LLM 2; kyo-parse, kyo-http, kyo-combinators: 1
each). Clause classification:

Resume with a settled answer, possibly after decision effects on the clause row
(Poll/Emit/Async performed in the clause, then cont(settled)): 46 sites. In the
answer shape every one of these drops the cont parameter and gets shorter.

Stop: Stream.mapPartial's bare Loop.done; Parse's Loop.done((state, failure));
Check and LLM aborting through the clause row. Conditional truncation
(Stream.take/takeWhile/dropWhile substituting Kyo.unit for the continuation) is
the stop primitive spelled at the next emit and maps onto Loop.done directly,
with identical semantics (the segment between emits still runs either way).

No site lets cont escape: a grep across all nine consumer files plus the full
read found every cont applied inside its own clause. The continuation-as-value
handlers (Sink.zip weaving two polls, Poll.runFirst, Emit.runFirst) are
handleFirst sites in the old kernel too, and kernel2's handleFirst keeps that
cont-passing contract.

The two gaps, both in the exit protocol of the stateful variants, found by
checking kernel2's signatures against what the sites consume:

First, the old kernel's stateful handleLoop has done: (State, A) => B < (S & S2),
observing the final state at normal completion, and it may be effectful
(EmitCombinators' done emits the leftover buffer). Consumers: Emit.run and
runFold, Check.runChunk, Var.run, Stream.run, runFold and foldKyo, LLM, Parse.
Kernel2's stateful handleLoopWith exit is cont: A => B < S3, which never sees
the state, so none of these are expressible today. Fix: the stateful exit
becomes (State, A) => B < S3 and Eval's completion arm passes cell.state (the
live cell carries the current state; the completion arm already dispatches
per kind for First).

Second, the old kernel's Loop.done carries B and bypasses the done transform:
Parse's fatal arm constructs the final (state, failure) directly, with no A in
hand. Kernel2's done carries A and runs through the exit. Fix: the stateful
Outcome's done slot types as the exit's output and the clause-done path in Eval
resumes past the exit transform (the fused exit is an Arrow whose step
decomposes into head and tail).

Both fixes are Eval-and-signature extensions inside the existing kinds, no
architecture change and no continuation anywhere. With them, the answer-shaped
handleLoop supports every existing effect in the codebase.

## Implementing now

## Next up

## Parked (your call to revive)

### Bracket primitive

Context: acquire/use/release with the fixed contract: use fully interruptible; no
interruption between acquire finishing and use starting; release always executes
(normal completion, exception unwind, and discard of an interrupted fiber's
remainder) and runs to completion once started. Ruled: not served via Isolate
(release on a short-circuit that truncates the owning region is the inexpressible
core). IOTask adds R-B1 (discard entry synchronous, Unit), R-B2 (a throw escaping a
partial drive already ran what it unwound), R-B3 (a done-truncation runs the
discarded regions' releases).

Three designs on disk, judgment deferred by your call. `bracket-node-design.md`: a
`Kyo.Bracket` node that exists exactly when the resource exists; acquire chains into
one strict transform that reads the resource and allocates obligation and node with
no budget check between, so the gap closes by representation, no mask, effectful
acquire included; weakness: a captured continuation carrying a bracket segment is a
value fork. `bracket-handler-design.md`: a handler kind whose region state
accumulates obligations via a register op; Scope becomes a direct instance; needs a
mask region for the gap; weakness: taxes the handler-stack walk for every open
region's lifetime plus a round trip per resource. `bracket-effect-design.md` (the
probe): the contract is not expressible in the current algebra; the minimal kernel
assist is a per-cell disposal hook over truncation-discarded regions plus one catch
at the evaluator boundary; everything else in the siblings is convenience. When
revived, the ruling should decide that shared disposal walk explicitly (consumers:
bracket unwind, R-B3 truncation, enrichment frames).

### kyo-bench arena rows, old vs new kernel (task #8)

Context: kyo-bench holds the end-to-end arena benchmarks (the cross-framework rows).
Running them over both kernels is the test of whether the micro-board positions
matter in realistic workloads. Blocked regardless on the stack above the kernel
compiling against kernel2, so parking costs nothing today.

### Effect.catching as a region

Context: `Effect.catching(v)(recover)` is the kernel's failure-recovery combinator.
Its current mechanism, `guarded`, rewrites the computation as it runs: every resumed
step gets wrapped in a fresh guard arrow and every node that comes back is
re-allocated with a guarded exit, which is your TODO at Effect.scala:27 ("an
expensive workaround for something that should be handled in Eval or Arrow?"). The
analysis confirmed the cost (one guard allocation, one node re-allocation, and one
chain flatten per resumed step) and a semantic hole: the rewrite reaches region
exits but not region interiors, so a throw inside a nested handled region is not
recovered, which nothing in the API suggests.

Design: make the catching scope a region: a `Kyo.Caught` node holding value, recover,
and exit, entered like any region and popped through its exit; the failure path finds
the innermost catching region by walking the handler stack at the throw point.
Catching becomes one allocation; `guarded` and its per-node-kind arms are deleted;
the nested-interior hole closes because a nested region sits inside the catching
region on the same stack. A plain evaluator try cannot do this because a guarded
residual recovered in a second drive is pinned behavior: the scope must be a value
that parks and rebuilds, and a region is the only such structure.
(`kernel2-todos-design.md` section 1.)

Parked by your call ("not sure about this region thing"). If revived, it consumes the
same failure-path walk the bracket ruling decides.

### Defaulted redesign (optional context)

Context: `Kyo.Defaulted` is the optional-context mechanism: a suspension that carries
its own fallback, which the evaluator's find-miss arm answers with when no handler is
installed (`Local.get` works with no `Local.let` in scope because of it). Your TODO
at KyoInternal.scala:38 rejects it, and the diagnosis agrees: the fallback is typed
`Any`, the row claims an effect that may never dispatch to a handler, and the second
consumer (the definedness probe that `Env.run`'s union and `Local.let`'s merge need)
only exists because the old kernel's Context map, which answered "is there an outer
handler" with a map lookup, was deleted.

Design: split the two needs. Context answers carry their own definedness:
`ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[Maybe[A]]]`, so the probe
becomes an ordinary read. The optional fallback becomes a typed marker (`unhandled:
O[X]` on the suspension) replacing Defaulted in the find-miss arm, typed at the
operation's output instead of `Any`. (`kernel2-todos-design.md` section 3; parked
task #31's discussion input. Interacts with the ContextEffect isolation design in
flight: its capture reads change shape if this lands.)

### IOTask integration

Context: IOTask is the scheduler's task, driving a fiber's computation in preemptible
slices and owning interruption, completion, and the finalizer contract. The port to
kernel2 is designed in full and re-verified against the current kernel (r2): the
fiber boundary is a handler region installed outermost, so a parked re-raise leaves a
bare residual that `handlePartial` re-enters without nesting a second layer; the park
must be written as a pending outcome (the settled form, one character away, spins);
the field layout lands at baseline parity (32 bytes).

Status: parked. Its rulings when revived: R1 (a deadline parameter on `Eval.partial`,
without which single-threaded platforms cannot preempt a fiber that never suspends),
R2 (charge the settled-answer arm one budget step so answer-loops like `Async.Join`
over completed promises become preemptible; hot arm, JMH-gated), R3 (abandoned
releases run synchronously, Unit-returning; recommended), R4 (context stays a def on
a conditional subclass so the footprint trick survives), R5 (dispatchFirst; now in
implementation), R6 (below). (`iotask-kernel2-integration-r2.md`; summary
`backlog-sections/iotask.md`.)
