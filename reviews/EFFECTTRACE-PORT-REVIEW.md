# Held-out review of proto-effecttrace-port-proposal.md (opus, 2026-08-19)

Reviewer: held-out, analysis only, ran nothing. Verbatim output.

**Verdict: REWORK.** The port is the right thing to do and most of the machinery does transfer, but
the proposal contains one outright inverted algorithm, one unspecified-and-load-bearing function,
and a staging decision whose Stage 1 is provably incapable of producing the frames its own test
corpus asserts. Two of its three "facts that drive this" are wrong or imprecise. The recommended
resolution to its open question is directionally necessary and materially insufficient.

Everything below is against `f329501024`.

## Part 1: the structural claims

**F1. "Handler extends Arrow.Transform, so the Handler arm must precede Transform" — CORRECT.**
`Handler.scala:7`. Handlers reach the walk as `Arrow`s (pushed as stack entries at `Eval.scala:132`).
Nit: the same shadowing applies to `Kyo.Handle` and the proposal does not notice (see F6).

**F2. "Kyo.Defer and Kyo.Handle carry no Frame" — TRUE OF THE DECLARATIONS, FALSE OF THE INSTANCES.
Advisory, but it invalidates the conclusion.** Most interesting nodes are also `Arrow.Transform` and
do carry a frame: `ArrowEffect.suspendWith` mints `Kyo.Suspend with Arrow.Transform` (`:33,34,37`);
`handleContWith`/`handleLoopWith`/`handleLoopStateWith` mint `Kyo.Handle with Arrow.Transform`
(`:143,152`; `:179,188`; `:216,226`); the emitting-clause adapter mints `Kyo.Defer with
Arrow.Transform` (`Eval.scala:52-57`, `:92-97`). Consequence the proposal misses: with `Transform`
above the `Kyo` arms, the `Defer`/`Suspend`/`Handle` arms are dead code for every hybrid node.

**F3. "Kyo and Arrow are sealed, so the match is exhaustiveness-checked" — WRONG. Blocking.**
(1) The scrutinee is `Any` with a catch-all: no exhaustivity analysis at all. (2) `Arrow.Id` is
`private[Arrow]` (`Arrow.scala:93`), so a catch-all is forced anyway. (3) `Arrow.Transform` is an
unsealed `private[kyo] trait` (`Arrow.scala:68`). The real reason to keep the file in `kyo/proto/`
is that `Arrow.Chain` is `private[proto]` (`Arrow.scala:77`) and `Loop.Continue._1` is
`private[proto]` (`Loop.scala:11`). Fix: type the worklist `Kyo[?, ?] | Arrow[?, ?, ?] | Nested[?]`
(the underlying union of `<`, `Pending.scala:10`), which recovers most of the property and keeps the
cast ladder at rung 2 instead of rung 4 (`SKILL.md:72-88`).

**F4. "The proto Stack has no apply and no base" — CORRECT, slightly imprecise.** Indexed accessors
`handler(i)` (`Stack.scala:80`) and `state(i)` (`:74`) exist and use the same `(head + i) & mask`
spelling, so `entry(i)` is consistent. No base is right (`Eval.scala:29`, pool `Stack.scala:187-193`).

**F5. "A try around a recursive call breaks stack safety, which the pins would catch" — IMPRECISE.**
`Eval.scala:31` is `@tailrec`, so it is a **compile error**, not a silent loss; no pin would ever run.
Also `deepRecursionPaysRescuesOnly` is a JMH row, not a pin; the stack-safety test is `EvalTest.scala:61`.

## Part 2: defects in the proposed code

**F6. The entries sweep walks the stack backwards. BLOCKING.** The two stacks index oppositely. Old
kernel: `push` appends at `top` (`kyo/kernel/internal/Stack.scala:28-31`), so high index = innermost,
and the old sweep counting down from `size - 1` is innermost-first (`EffectTrace.scala:191-198`).
Proto: `push` decrements `head` (`Stack.scala:39,43`), `pop` reads at `head` (`:66-70`), `find` scans
upward from 0 (`:85-91`), `dump` folds `pos-1` down to 0 (`:97-119`): **index 0 is innermost**. The
proposal's `loop(stack.size - 1)` emits outermost-first, failing `EffectTraceTest.scala:157-162`,
`:99-102`, `:211-217`. The `dropped` accounting is wrong for the same reason. Fix: ascend from 0.

**F7. Builder.value is never specified and does not work as inherited. BLOCKING.** Three of the six
arms call it, plus `entries`. The current `value` (`EffectTrace.scala:182-186`) matches only `Nested`
and `Arrow`; in the proto `Kyo.Defer.value` is `A < S` whose pending case is a **Kyo**, and
`Kyo.Handle.value` is declared `Kyo[A, E & S]` (`KyoInternal.scala:27`). `kyo.proto.Nested`
(`Nested.scala:5`) is a different class from the imported `kyo.kernel.internal.Nested`
(`EffectTrace.scala:8`), and `Nested.lift` only wraps `Kyo | Nested` payloads (`Nested.scala:11`).
Without a `Kyo` arm every `value(...)` falls to `case _ => ()` and the innermost suspension frame is
never emitted. `push`/`work` are `Arrow`-typed (`:207,148`) and must be re-typed too, so "push
transfers unchanged" is false.

**F8. Loop.Continue/Continue2 are unhandled and listed as "NOT touched". Advisory now, blocking at
Stage 2.** A clause's answer flows as a `Loop.Outcome` (`Eval.scala:48,72,88,114`); the payload lives
in `Continue._1`/`Continue2._2` (`Loop.scala:10-16`, `private[proto]`, readable from `kyo.proto`).
Any clause-site attach is handed a `Continue` and contributes nothing.

**F9. Arrow.Id burns worklist budget. Advisory.** `Effect.defer(v, next)` sets `contB = Arrow.id`
(`Effect.scala:13`) and `dump` builds `new Arrow.Chain(c, Arrow.id)` (`Stack.scala:110,114`). The
proposed arms push both halves unconditionally; `Id` falls to `case _` but `push` evicts the
outermost entry at `MaxFrames` and increments `dropped` (`EffectTrace.scala:207-215`), so it can
evict real frames. Guard `push` with `if !(a eq Arrow.Id)`. The denormalized marker itself is walked
correctly.

## Part 3: staging

**F10. Stage 1 cannot produce the frames its corpus asserts. BLOCKING.** The proposal says the
boundary attach "still walks the whole drive stack". **The proto's drive continuously drains the
stack into folded arrows**, so at a throw the stack no longer holds the frames near the failure.
Worked example (`EffectTraceTest.scala:139-144`): stack is `[innerArrow, outerArrow, H]`; the done
branch pops `innerArrow` and `dump()` folds `outerArrow` into `tail` (`Eval.scala:146`); the user
function throws; the boundary catch sees `[H]` only. One region label; `innerStep`, `outerStep`, `ask`
all gone (a bare `ask`'s `cont` is `Arrow.id`, discarded by `push` at `Stack.scala:34-35`).

Corpus triage under Stage 1: `:59` fails, `:74` fails, `:84` fails (stack empty, zero frames), `:92`
passes after F6, `:105` passes, `:114`/`:248`/`:254` likely pass, `:129`/`:234` pass, `:139`-`:167`
(4 cases) fail, `:170` fails, `:188`/`:195` cannot be ported (F11), `:211`/`:219` pass after F6,
`:226` passes if any frame exists, `:274` fails.

**This defect is already in the record.** `WORK.md:626-630`: "Five ported EffectTraceTest cases went
red: a throw inside a `map` over a suspension lost its `map` frames... the walker being handed the
suspension without its continuation." Stage 1 is a weaker version of a bug already found and fixed
once; `SKILL.md:342-345` says such an item "is raised, not executed".

**Right shape: there is no Stage 1.** Wire the (b) shape where the drive holds both the failing value
and the folded tail: `Eval.scala:142`, `:144`, `:146`, the clause site (`:45/48/88`), the `bug` site
(`:41`), plus the boundary `splice`. Same six-attach shape the old kernel used
(`kyo/kernel/internal/Eval.scala:197,241,273,280,287,298`) and for the same reason: its `dump()` also
truncated (`:62`). Stage the *measurement*, not the correctness.

**F11. "Ports by package, effect definitions, handler constructors" is wrong for two cases.
Blocking for the claim.** `EffectTraceTest.scala:189,196` call `Effect.defer[Int, Any](throw new
Boom)`; the old kernel has a by-name `Effect.defer` (`kyo/kernel/Effect.scala:11`), the proto's
`Effect` has only `defer(v, next)` / `defer(v, a, b)` (`Effect.scala:9,15`). `:192` also asserts a
`"defer @ "` frame, which requires `Defer` to carry a Frame. So `:188` has no counterpart; `:195` is
portable (a 600-deep tower drives `Safepoint.enter` to fail and mints `Effect.defer(v, arrow, next)`,
`Pending.scala:28-29`, whose `contA` carries the user frame).

**F12. The corpus is currently red. Advisory but important for planning.**
`kyo.kernel.internal.Eval.apply` is `???` (`Eval.scala:21,24`); every case throws `NotImplementedError`
and `intercept[RuntimeException]` does not catch an `Error`. Last green under a different evaluator
(`6a834b24de`). Porting it is authoring tests, not transcribing green ones.

## Part 4: hazards not named

**F13. Arm order is load-bearing for termination. BLOCKING (documentation + test).** Self-referential
nodes: `Eval.scala:52-57` and `:92-97` mint `Kyo.Defer with Arrow.Transform` where **contA = this**,
`frame = Frame.internal`, and the drive pushes them (`Eval.scala:36`); `ArrowEffect.scala:33,37`,
`:143,152`, `:179,188`, `:216,226` set **cont = this**. Under the proposal's literal order they match
`Transform` and terminate. Move the `Kyo` arms up (as F2 invites) and `push(d.contA)` re-enqueues the
same object forever, and unlike the old kernel it does not stop at the cap: `frame(Frame.internal)`
is skipped (`EffectTrace.scala:160`), so `size` never grows, `full` is never true, and `drain`
(`:217-253`) does not terminate: an infinite loop inside a catch with an exception in flight.
Recurrence of `WORK.md:628-630` ("`drain`'s SuspendWith arm pushed `m.tail`, which is `this` for a
Defer, so it re-enqueued itself"). Requirements: state the invariant at the match; pin a
`suspendWith` / `handleLoopWith` / emitting-clause walk (`SKILL.md:145-148`).

**F14. Multi-shot replay unaddressed. Advisory.** `EvalCaptureTowerTest.scala:47` applies a captured
continuation twice. The walk is idempotent over a DAG, but (a) a shared sub-arrow reached from two
slots emits its frames twice, interacting with "names each region exactly once"
(`EffectTraceTest.scala:219`); (b) `installInto` appends per boundary (`EffectTrace.scala:255-261`),
so N crossings accumulate N copies until the cap.

**F15. The emitting-clause path loses its region label. Advisory.** `Eval.scala:51` does
`discard(stack.pop())`, so the handler is off the stack for the whole clause evaluation.

**F16. `kyo.proto.Pending` is probably not a real class-name prefix. Advisory.** The file declares a
top-level `opaque type <` and `object <` (`Pending.scala:10,12`), compiling to
`kyo.proto.Pending$package` and `kyo.proto.$less$`. Also `kyo.proto.Safepoint` is missing from the
list though it is on every delivery path (`Pending.scala:27-32`), and `startsWith("kyo.proto.Effect")`
also matches `kyo.proto.EffectTrace` (harmless, accidental).

**F17. Cross-platform is not mentioned. Blocking as a placement decision.** `kyo-kernel2` cross-builds
JVM/JS/Native/Wasm (`build.sbt:773`) and the corpus lives in `shared/src/test`. Assertions on the
*physical* trace are not portable: `EffectTraceTest.scala:71` (`getStackTrace.head.getFileName`) and
`:226-232`. The module has a `jvm-native/src/test` tree for exactly this partition.

**F18. The clean batch build is not in the verification plan. Advisory.** `SKILL.md:104-126`,
`:123-124`: `sbt --batch 'kyo-kernel2JVM/clean' 'kyo-kernel2JVM/compile'`.

## Part 5: design fit

**F19. The shape is defensible; the proposal never argues it. Advisory.** The reconstruction is a fold
over the pending continuation of the composed value, adds no node kind and no combinator. Say so, or
a reviewer holding `SKILL.md:12-17` reads a walker over `Chain.a/b`, `Defer.contA/contB` and a private
array as pure evaluator machinery.

**F20. The concession shape is incomplete. Blocking under `SKILL.md:128-132`.** Three concessions,
none protected: the `Any` worklist (F3), the new `private[proto] def entry`, and the swallow-all
rule inside the walk, whose test is commented out at `EffectTraceTest.scala:261-272` because the old
kernel's `Arrow.Suspend` could not be subclassed from a test. **The proto fixes that for free:**
`Kyo.Suspend` is a public abstract class (`KyoInternal.scala:19`), so a throwing subclass compiles
from a `kyo.proto` test. Un-commenting that case is a genuine win of the port.

**F21. The Stage-2 measurement plan contradicts the skill. Blocking for Stage 2.** `SKILL.md:178-191`:
the unit of measurement is the whole class, both variants, same session; a hand-picked subset never
becomes the evidence. Attaches at the delivery and clause sites reach the drive head, so all 15 rows
are mandatory. Also `SKILL.md:490-491` records the project deleting hot-loop state that existed only
to enrich an exception at one boundary; that precedent should be cited and answered.

In the proposal's favour: the inline-expansion worry does not apply. `attach` is a plain `def`
(`EffectTrace.scala:73`), so the drive gains a try region plus a static call, and `Eval$::loop` never
inlines anyway (`SKILL.md:260-262`).

## Part 6: the open question

**F22. The recommended resolution is necessary and insufficient. Blocking.** Right that Stage 1
without a boundary attach renders nothing, right that `attach(ex, stack)` is the mechanical fix,
right that the stack is live in the catch (`Stack.release` is in the `finally`, `Eval.scala:157`).
But per F10 the result is region labels and little else. Two ways out, in order of preference:
(1) drop the staging and land the (b)-shape attaches, then measure the full class and remove what the
numbers reject; (2) keep Stage 1 but restate its deliverable as "regions and residual stack entries
only", with ~10 cases explicitly deferred rather than re-derived to weaker expectations. Either way
F6 and F7 must be fixed first.

## Summary

**Blocking:** F3, F6, F7, F10, F11, F13, F17, F20, F21, F22.
**Advisory:** F1 nit, F2, F4, F5, F8, F9, F12, F14, F15, F16, F18, F19.

The target design is right, the carrier and cap discipline transfer, and the Handler-before-Transform
observation is a real catch. What is misconceived is the staging: it treats "walks the drive stack" as
equivalent between the two kernels when the proto's drive drains that stack into folded arrows on
every application.
