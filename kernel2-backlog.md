# kernel2 backlog

Queues: implementing, designing, awaiting ruling, next up, parked. Each item carries
its own context so it reads without the linked docs; the docs carry the full designs.
Done work is removed once acked. Last update: 2026-08-17.

## Done, awaiting your ack (removed from the file once acked)

(empty: everything acked has been removed)

## Implementing now

## Next up

### bench-harness has outgrown a scratch script; give it a real project shape

Context: `kyo-kernel2/.claude/skills/kernel/bench-harness/` is the program that enforces
the measurement protocol the kernel skill states. It is now 3,526 lines across 17 files
(the tool, a JMH parser, a LogCompilation parser, a bytecode reader, a statistics module,
a store, a six-subcommand CLI) with 193 checks in five suites. It started as the "prefer
this over hand-written bash" helper the skill points at, and it is past that. Your words:
"I guess this is becoming a real project and we need a real project structure with sbt,
tests, etc?"

What it has: a scala-cli project (`project.scala`, three published `io.getkyo` RC6
dependencies), one flat directory holding sources and tests together, and five test
entrypoints spelled `object FooTest { def main(args: Array[String]) }` over a hand-written
`check(name, cond, detail)` that prints a line and throws `AssertionError`.

What that costs, concretely:

- **The first failing check ends the suite.** `check` throws, so a run reports the failure
  it hit and nothing about the 60 checks after it. Every red run is a partial picture, and
  a fix-and-rerun loop discovers failures one at a time.
- **There is no selective run.** No `testOnly`, no tags. The container-ish suites
  (`QaEndToEnd`, `QaGuards`) need a live worktree and minutes; the four fast ones need
  neither. They are separated today only by me remembering which is which.
- **Nothing runs it but me.** No CI, no `sbt test`, no pre-commit hook. The suite is green
  because I ran it by hand five minutes ago, which is not a property of the repository.
- **Sources and tests share a directory.** The repo's 1:1 naming rule is satisfied
  (`Store.scala`/`BenchTest.scala` is the one exception, and it is a real one) but there is
  no `src/main` / `src/test` split, so a `main` that measures and a `main` that self-checks
  are peers.

The constraint that shapes every option: **the harness must not depend on the repo's own
kyo.** It builds against published RC6 artifacts precisely because the repo does not
compile as a whole while the kernel migration is in flight, and because a measurement tool
that breaks when the kernel breaks is backwards, it is needed most exactly then. Whatever
shape it takes must keep that inversion.

Two shapes, both keeping that:

1. **An sbt subproject** (`bench-harness` or under `tools/`), depending on published
   `io.getkyo` artifacts and on no repo module, with `src/main` / `src/test` and munit or
   the repo's own `kyo-test`. Buys `sbt bench-harness/test`, CI, and the same tooling the
   rest of the repo has. Costs: it must move out of the skill payload directory, which
   breaks SKILL.md's "beside this file" reference and the self-locating `Roots.harness`;
   and adding a project to a build that does not currently compile as a whole needs care
   that its aggregate does not drag the kernel in.
2. **Stay scala-cli, add the missing pieces**: `//> using test.dep` for munit, a
   `src/main`/`src/test` split, tags separating the fast suites from the worktree-bound QA
   mains, and a `scripts/` entry so CI or a hook can run it. Keeps the tool inside the
   skill it belongs to and keeps the fast startup that makes it usable mid-investigation.
   Does not get it into `sbt test`.

My read: (2) first, because the collect-all-failures reporting and the fast/slow split are
the two defects actually biting, and both are available without moving anything. (1) is the
right end state once the kernel migration lands and the build compiles as a whole, at which
point the "it must not depend on the repo" constraint can be re-examined rather than
assumed.

Open question, one line: **scala-cli plus a real test framework now, or an sbt subproject
now, or both in that order?**

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

Design re-verified against the live code (2026-08-13) and explained in console. It
holds, and its two halves are one mechanism rather than two: typing the context answer
as `Maybe[A]` makes the definedness probe an ordinary read, and typing the find-miss
fallback at the operation's output (`unhandled: O[X]` replacing `Defaulted.default:
Any`) is what lets that probe answer `Absent` when no handler is installed. Together
they delete the `undefined` AnyRef sentinel, the identity comparison against it, and
the `Any` typing. Cost to weigh when you rule: the operation type of every context
effect changes, so `Local`/`Env` in kyo-prelude move with it when the stack ports.

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
