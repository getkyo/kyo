# kernel2 backlog

Queues: implementing, designing, awaiting ruling, next up, parked. Each item carries
its own context so it reads without the linked docs; the docs carry the full designs.
Done work is removed once acked. Last update: 2026-08-13.

## Done, awaiting your ack (removed from the file once acked)

### Loop bare constructors: rechecked from scratch on your pushback, the `< Any` return is load-bearing

Your question ("were you just lazy to fix the uses?") deserved evidence, not the
agent's claim, so I reran the experiment on the current tip: constructors flipped to
origin/main's exact bare shape, module compiled. 64 sites go red (2 main, 62 test),
and every single one is the settled-answer clause a downstream handler author writes:

    ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(21))

    Found:    Loop.Outcome[A, O]           where A >: (21 : Int)
    Required: Loop.Outcome[Int < (Ask & Any & (Any & Say)), Int] < Any

The failure is not fixable at the uses in any acceptable way: the fix each site needs
is an ascription naming the slot's exact effect row (`21: Int < (Ask & Any & (Any &
Say))` above), which no user can be asked to write and which breaks the moment a row
changes. The two main-source sites (ContextEffect.handle) can take ascriptions; the
62 test sites are stand-ins for every future handleLoop clause.

Why origin/main gets away with bare constructors and kernel2 cannot: a protocol
difference, not inference luck. The old kernel's handleLoop clause receives an
explicit continuation and continues with the already-pending whole computation
(`Loop.continue(cont(input))`), so the argument's type is the slot's type verbatim
and bare inference lands it. Kernel2's Loop handler kind answers the operation
directly, and the common answer is a settled value into a pending slot
(`Outcome[O[X] < (E & S & S2), A] < S2`). Settled-into-pending is a lift conversion,
and a conversion blocks expected-type propagation into `continue`'s type parameter:
A infers from the argument as `Int`, and the invariant Outcome rejects `Continue[Int]`
where `Continue[Int < row]` is required. The `< Any` return is the inference vehicle
that avoids the conversion: a `<`-shaped return unifies with the slot directly, so A
solves from the expected type and the settled argument conforms. At runtime the value
is the bare Continue; the `< Any` is erased currency, zero cost.

Alternatives checked and rejected: covariant Outcome/Continue (sound but useless,
`Int <: Int < S` is not subtyping outside Pending.scala's opacity scope, both
kernels); retyping the Loop-kind slot to settled answers with effects riding the
clause row (pessimizes every pending-answer site with an extra map per answer inside
the handler hot loop); a macro reading the expected type (not accessible to inline
defs, and would put a macro expansion in every loop site, the compile-time cost this
campaign just removed). What `58d523b805` landed otherwise stands: dead lift on
`continue` removed, load-bearing lift on `done` pinned, accessors to vals, TODO
deleted. If you want a different tradeoff here (for example accepting the extra map
to get bare constructors), that is a protocol ruling on the Loop handler kind, and I
have the experiment set up to measure it.

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
