# Optimization candidates: allocation and object lifetime

Analysis only. No source was edited, no build or benchmark was run.

Sources read: `kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/*.scala`,
`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/ProtoKernelBench.scala`,
`kyo-kernel2/.claude/skills/kernel/SKILL.md`, `qa-artifacts/qa-alloc.txt`,
`qa-artifacts/qa-jmh.json`, `qa-artifacts/qa-jit.txt`, `qa-artifacts/e2e2.log`,
`proto2-jit-analysis.md`, `proto-backlog.md`.

## 1. What the capture actually says

The capture is a single row: `qa-alloc.txt:3` runs
`ProtoKernelBench.nestedPayloadsUnwrapInMaps` only. Provenance: JDK 25.0.3, JMH 1.37,
`-f 1`, 5 warmup + 5 measurement iterations (`qa-alloc.txt:13-23`). Measured drift band on
this machine this session: 4.0% (`qa-artifacts/e2e2.log`, "drift 3.95%").

Exact numbers in hand:

| field | value | source |
|---|---|---|
| score | 5.839 us/op | `qa-artifacts/qa-jmh.json` primaryMetric |
| `gc.alloc.rate.norm` | 32,080.04 B/op | `qa-artifacts/qa-jmh.json` secondaryMetrics |
| `gc.alloc.rate` | 5,238.7 MB/s | same |
| flat allocation table | 4 classes, 19,017,986,638 B | `qa-alloc.txt:110053-110058` |

Flat table verbatim (`qa-alloc.txt:110055-110058`):

```
  9510041893   50.01%    18139  kyo.kernel.proto.Nested
  9477011812   49.83%    18076  kyo.kernel.bench.ProtoKernelBench$$anon$95
    16777184    0.09%       32  kyo.kernel.proto.Arrow$Bind
    14155749    0.07%       27  kyo.kernel.bench.ProtoKernelBench$$anon$51
```

Reading note that matters for what follows: every row's bytes divided by its samples is
exactly 524,287, so the bytes column is samples times the sampler interval, a proportional
estimate of bytes and not an object count. The two 0.0x% rows are 32 and 27 samples and are
at the sampler's resolution floor; do not read their byte figures as exact.

### The per-iteration model

`NarrowDepth = 1000` (`ProtoKernelBench.scala:190`), so `loop` runs 1001 times per op
(`ProtoKernelBench.scala:112-117`). Splitting the exact 32,080.04 B/op by the flat table's
percentages:

- `Nested`: 50.01% = 16,043 B/op = 16.03 B per iteration, one 16-byte object per iteration.
- `anon$95`: 49.83% = 15,985 B/op = 15.97 B per iteration, one 16-byte object per iteration.
- `Arrow$Bind` + `anon$51`: 0.16% = 51 B/op total, consistent with the roughly two safepoint
  parks per op that a 1000-deep descent produces against the 512 budget
  (`Safepoint.scala:31`, `Safepoint.scala:44`).

So: two 16-byte objects per loop iteration, plus a park pair about twice per op. The whole
32 KB/op is accounted for.

### Correction to the brief's attribution

The brief calls `ProtoKernelBench$$anon$95` "the benchmark's own lambda". It is not a lambda
and it is not benchmark logic. It is the kernel's `Arrow.Suspend` node, created by the inline
`ArrowEffect.suspend` (`ArrowEffect.scala:17-27`) and therefore emitted as an anonymous class
into the benchmark's own class file at `ask` (`ProtoKernelBench.scala:195`). The inlining log
shows the construction chain at `qa-jit.txt:4211-4222` (the log interleaves compiler threads,
so the frames are quoted here in nesting order rather than file order):

```
4211  @ 44   kyo.kernel.bench.ProtoKernelBench$::ask (15 bytes)   inline (hot)
4214  @ 8    kyo.kernel.bench.ProtoKernelBench$$anon$95::<init> (10 bytes)   inline (hot)
4215  @ 6    kyo.kernel.proto.Arrow$Suspend::<init> (5 bytes)   inline (hot)
4217  @ 1    kyo.kernel.proto.Arrow$Defer::<init> (5 bytes)   inline (hot)
4218  @ 1    kyo.kernel.proto.Arrow$Step::<init> (5 bytes)   inline (hot)
```

Likewise `anon$51` is the map's `Arrow.Transform` (`Pending.scala:42-46`), allocated in the
lifted `arrow$40` on the park arm; the capture's own stack shows it
(`qa-alloc.txt`, stack `[0] ...anon$51 / [1] ...arrow$40 / [2] ...run$39`).

Consequence for attribution: 100% of this row's bytes are kernel node shapes expanded at the
benchmark's call sites, not 50%. SKILL.md's warning about measuring the benchmark
(SKILL.md:285-287) does not apply to the byte total here; it still applies to the `Int`
boxing that shows in the CPU profile but not in the allocation table.

### Why both objects are on the heap at all

Both allocations and both consumptions land in one hot compiled unit. The inlining log has
`boxed (5 bytes) inline (hot)`, `Nested::nest (26 bytes) inline (hot)`,
`Nested$::apply (9 bytes) inline (hot)`, `Nested::<init> (10 bytes) inline (hot)`,
`run$39 (112 bytes) inline (hot)` and `Nested::unnest (21 bytes) inline (hot)` all nested
under the same compilation (`qa-jit.txt:4224-4233`). A box created and read out a few
instructions later inside one compiled unit is the textbook scalar-replacement case, and it
is not being scalar-replaced.

The escape is at `Pending.scala:55`:

```scala
case v =>
    val res  = Nested.unnest[A](v)
    val slot = Safepoint.get()
    if !Safepoint.enter(slot) then
        Arrow.Bind(v, arrow.chain(next))
    else
        ...
```

`v` (the freshly built `Nested`) is stored into an `Arrow.Bind` on the park arm. C2's escape
analysis is flow-insensitive: one reachable store to a heap field makes the object
GlobalEscape on every path. The park arm is reachable here, and the capture proves it:
`Arrow$Bind` is in the flat table (`qa-alloc.txt:110057`). `anon$95` follows, because it is
reachable only through `v.value`.

This mechanism is already recorded as measured in the sibling design
(`proto2-jit-analysis.md:66-73`):

> a probe firing 1/512 makes the rescue branch profile-reachable at every fused site and
> escape analysis dies (eager rows went 0.0 to 81.2 and 164.7 B/op)

and again as a rule (`proto2-jit-analysis.md:205-206`):

> Rescue triggers must be profile-never-taken (depth or structure), never sampled counters;
> a 1/512 branch in hot code destroys escape analysis.

The current park trigger is a depth guard (`Safepoint.scala:49-56`, budget 512 at
`Safepoint.scala:31`), which is profile-never-taken in shallow code (that is why
`fusionAllocatesNothing` has historically held 0.00 B/op, `proto-backlog.md:68`) but is
genuinely taken on a 1000-deep row.

## 2. Direct answer: is the `Nested` boxing removable in principle?

Two questions are being run together, and they have opposite answers.

**Is a box required for this value? Yes, inherent, and not to the opaque alias as such but to
the erasure of the union it hides.** `opaque type <[+A, -S] = A | Arrow[Any, A, S] | Nested[A]`
(`Pending.scala:11`) erases to `Object`, and the evaluator dispatches on the runtime class:
`case c: Chain`, `case b: Bind`, `case s: Suspend`, `case m: SuspendWith`, `case h: Handle`,
`case e: Arrow.Eval`, `case a: Arrow`, `case settled` (`Eval.scala:194-226`). A payload that
is itself an `Arrow` is therefore indistinguishable from a suspension unless it is tagged.
`Boxed` (`Pending.scala:9`, extended by `Arrow` at `Arrow.scala:10` and by `Nested` at
`Pending.scala:14`) is that tag channel, and `Nested.nest` boxes exactly and only `Boxed`
values (`Pending.scala:22-25`). On this row the lifted value genuinely is a suspension
(`boxed(ask)`, `ProtoKernelBench.scala:115` and `:204`), so the tag is load-bearing: strip it
and `Eval.scala:201` dispatches the payload as a suspension instead of delivering it as data.
SKILL.md records exactly this as a closed concession (SKILL.md:137): "the `Boxed` marker
closes the channel: a computation used as data is always `Nested`-wrapped, so the evaluator
cannot mistake a payload for a suspension".

The *count* is also already minimal under the contract. Nest once at the lift emission
(`CanLift.scala:63`), carry opaquely, unnest once at delivery (`Pending.scala:52`). One box,
one unbox, per value. There is no redundant box to delete.

**Must that box be heap-allocated? No, and this is where the 16 KB/op lives.** The box's
lifetime on this row is a handful of instructions inside a single compiled unit
(`qa-jit.txt:4224-4233`). It is on the heap only because the park arm at `Pending.scala:55`
stores the incoming reference into an `Arrow.Bind`, and that arm is profile-reachable
(`Arrow$Bind` in the flat table). Nothing about the opaque encoding requires that; it is a
property of what the cold arm chooses to store and of C2 having no partial escape analysis.

**What the evidence cannot settle.** Nothing in this capture proves C2 *would* scalar-replace
after the escape is removed. `res` at `Pending.scala:52` is `phi(v.value, v)` because
`unnest`'s fallthrough arm returns its argument (`Pending.scala:27-31`), so a naive edit can
re-alias `v` into the escaping set and change nothing. The only thing that settles it is
running candidate C1 and reading `gc.alloc.rate.norm`. Stating "the boxing is inherent" as a
conclusion today would be exactly the substituted rationale SKILL.md:171-173 forbids.

Short form: the box is inherent, the allocation is not, and the difference is worth 16,043
B/op on this row.

## 3. Candidates, ranked by expected value

### C1. Park from the payload, not from the incoming union

**Hypothesis.** The `Nested` is heap-allocated only because the park arm stores the incoming
union reference into `Arrow.Bind`; if the park arm builds its own box from the unnested
payload instead, the incoming reference stops escaping and C2 scalar-replaces it.

**Mechanism.** Split the strict arm on `Nested` so the payload has no phi with the union, and
have the park arm construct a fresh box. Present shape at `Pending.scala:47-63`:

```scala
case v =>
    val res  = Nested.unnest[A](v)
    val slot = Safepoint.get()
    if !Safepoint.enter(slot) then
        Arrow.Bind(v, arrow.chain(next))
```

`v` reaches `Arrow.Bind`'s `value` field (`Arrow.scala:91-94`), which is a heap store, so the
object allocated at `Pending.scala:24` is GlobalEscape. Splitting into a `case v: Nested[?]`
arm whose `res` is `v.value` (no phi) and whose park arm stores a freshly built box leaves
`v`'s only remaining uses as a type test and a field load. The rebuild round-trips exactly:
`nest` boxes only `Boxed` (`Pending.scala:23-24`) and `v` being a `Nested` means `v.value` is
`Boxed`, so the rebuilt box is representation-identical to `v`.

Scope is eight sites, all textual copies of the same arm: `Pending.scala:55` (map), `:82`
(flatMap), `:108` (andThen), `:134` (unit), `:287` (flatten), and `ArrowEffect.scala:91`
(handleContWith), `:161` (handleLoopWith), `:234` (handleLoopStateWith).

**Predicted signal.** `gc.alloc.rate.norm` on `nestedPayloadsUnwrapInMaps` falls from
32,080.04 B/op to roughly 16,040 B/op, and `kyo.kernel.proto.Nested` leaves the allocation
flat table entirely. If it falls below 200 B/op instead, `anon$95` was freed with it and the
result is better than predicted; that is still a confirmation. JMH score on that row down,
direction only, magnitude not predicted (the row also spends on `Integer` boxing that this
does not touch).

**Falsifier.** `gc.alloc.rate.norm` on that row stays at 32,080 +/- sampling noise, or
`kyo.kernel.proto.Nested` is still in the flat table with a comparable share. Either means the
escape survived (most likely re-aliased through the rebuilt box) and the hypothesis is dead as
stated.

**Target rows.** Moves: `nestedPayloadsUnwrapInMaps` only. Must not move on B/op: the other
fourteen. `boxed` (`ProtoKernelBench.scala:204`) is the suite's only lift whose type is
abstract and therefore its only lift routed to the macro's runtime-test arm
(`CanLift.scala:63`); every other lift in the class is `Int` or `Unit` and takes the bare-cast
arm (`CanLift.scala:54`, `:63`). Must not move on score beyond the 4.0% band: all fourteen,
and a regression there is the expected failure mode, not a surprise.

**Cost and risk.** The strict arm is the hottest and most-copied body in the kernel and it is
expanded at every user `map`. Adding a branch grows it; `run$39` is 112 bytes today and gets
`inline (hot)` (`qa-jit.txt:4231`), and the hot budget is about 325 bytes, so there is room,
but SKILL.md:250-256 makes this a design property to check, not an assumption. Eight copies
must stay textually parallel (SKILL.md:140). The invariant at risk is the representation
contract's "nest exactly once" (SKILL.md:55): a rebuilt box at the park is a second nest of
the same value, and it must be proven equivalent, not assumed. Hostile axes to pin: double
nesting (`A < S < S2` parked mid-path), multi-shot application of a parked `Bind`, and a park
whose payload is itself a `Nested`.

**Gated constructs.** Yes, leads with this. It needs an explicit `Nested(...)` spelling inside
`object <`, which SKILL.md:106-108 forbids without sign-off ("no explicit nest spellings
standing in for the lift"). It also needs a `case v: Nested[?]` typed pattern (cast-ladder
step 2, SKILL.md:76-77, allowed) and an erasure-forced `asInstanceOf` on `v.value`
(ladder 4, erasure-forced, SKILL.md:79-80). No new `inline`. No public API change.

### C2. `Nested` as a plain final class, constructed with `new`

**Hypothesis.** `Nested` is a case class whose entire generated surface is dead, and its
companion `apply` puts a module load on the lift's hot path that `@static def nest` was
introduced to avoid.

**Mechanism.** `final private[proto] case class Nested[+A](value: A) extends Boxed`
(`Pending.scala:14`). The only construction in the codebase is `Nested(v)` at
`Pending.scala:24`; the only consumptions are the type test plus `.value` at
`Pending.scala:29` and the `Render` given at `Implicits.scala:58` (verified by grep across
the proto package). Nothing uses `unapply`, `copy`, `equals`, `hashCode`, `toString`, or the
`Product` members. Construction currently routes through the companion, which the inlining log
shows as a distinct frame (`qa-jit.txt:4212`, `:4227`): `kyo.kernel.proto.Nested$::apply
(9 bytes)`. A companion `apply` is a `getstatic Nested$.MODULE$` plus a virtual call, which is
the module load SKILL.md:141 records `@static` as existing to keep off expansion sites, and
`nest` is `@static` (`Pending.scala:22`) for exactly that reason.

**Predicted signal.** Three named, all in the same direction:
`PrintInlining` loses the `kyo.kernel.proto.Nested$::apply (9 bytes)` line under
`Nested::nest`; `Nested::nest`'s reported size drops from 26 bytes (`qa-jit.txt:4071`) to
about 20; and the tier-1 verdict `Nested::nest (26 bytes) failed to inline: callee uses too
much stack` (`qa-jit.txt:4071`, `:4185`) flips to `inline`. `javap -p -c` on `Nested.class`
plus `Nested$.class` shrinks by the eight synthetic members.
`gc.alloc.rate.norm` on every row: unchanged. That is the prediction, not a hope; a plain
class and a case class have identical instance layout.

**Falsifier.** `Nested::nest` still reports 26 bytes in the inlining log, which would mean the
companion call was never in `nest`'s body; or any row's `gc.alloc.rate.norm` moves, which
would mean the layout assumption is wrong.

**Target rows.** Score may move on `nestedPayloadsUnwrapInMaps` only, and it should move by
less than the 4.0% drift band, meaning this candidate's honest claim is a JIT-log claim and a
`javap` claim, not a score claim. Must not move: all fifteen on B/op, all fourteen others on
score.

**Cost and risk.** Near zero. `value` stays a `val` field so covariance is unaffected. The one
thing to check is that dropping `Product with Serializable` from `Nested`'s supertypes breaks
nothing: `Nested` is `private[proto]` so no user code can see it, and the sibling protection
that does depend on a supertype is `Continue extends nothing but Serializable`
(SKILL.md:136, `Loop.scala:144-146`), which is a different class.

**Gated constructs.** None. No `inline`, no new cast, no public API change.

### C3. Move the park trigger out of the delivery arm into the chain structure

**Hypothesis.** As long as the park decision is a branch inside the delivery arm, every
sufficiently deep row pays escape analysis for every allocation that arm touches; moving the
trigger into the value's structure removes the branch and restores scalar replacement for
both objects, not just the box.

**Mechanism.** This is the design already measured in the sibling
(`proto2-jit-analysis.md:74-77`): "Resumed chains: a segment boundary node spliced every 512
elements at optimize time. The boundary's `run` returns `Defer(v, cont)`, unwinding the
segment and bouncing through the trampoline. Sampling lives in the chain structure, not in hot
code, so there is no branch to poison." Applied here, `Safepoint.get()`/`Safepoint.enter`/
`Safepoint.exit` (`Pending.scala:53-59`) leave the strict arm entirely and the arm becomes the
unconditional `step.head(f(res), step.tail)` delivery. With no store of `v` and no reachable
cold branch in the arm, both the `Nested` at `Pending.scala:24` and the `Suspend` at
`ArrowEffect.scala:23` are NoEscape.

**Predicted signal.** `gc.alloc.rate.norm` on `nestedPayloadsUnwrapInMaps` falls from
32,080 B/op to under 200 B/op, and the flat table loses both `kyo.kernel.proto.Nested` and
`ProtoKernelBench$$anon$95`, retaining only the boundary-node allocations. On
`deepRecursionPaysRescuesOnly` and `fusionPastBudgetPaysRescuesOnly`, `gc.alloc.rate.norm`
falls by the park pair per park. The sibling's recorded magnitude for the same mechanism is
0.0 versus 81.2 and 164.7 B/op (`proto2-jit-analysis.md:69`).

**Falsifier.** `gc.alloc.rate.norm` on `nestedPayloadsUnwrapInMaps` unchanged, which would
mean the escape was never the park branch; or `fusionAllocatesNothing` and `evalFixedOverhead`
stop reporting their current floors, which would mean the boundary node leaked into shallow
code and the design costs where it must cost nothing.

**Target rows.** Moves: `nestedPayloadsUnwrapInMaps`, plus the two rows named for paying
rescues, `deepRecursionPaysRescuesOnly` and `fusionPastBudgetPaysRescuesOnly`. Must not move:
`fusionAllocatesNothing` and `evalFixedOverhead`, the rows that never park today, which must
keep their current floors exactly. A move there is the design leaking into shallow code.

**Cost and risk.** Much the largest of the five. It wants a boundary node, and SKILL.md:22-23
names wanting a new node kind as the signal you are off the path, which makes this a design
conversation before it is an edit. It changes where stack safety comes from, so the whole
hostile set (100k-deep maps, interruption via `Safepoint.stop`, `Eval.partial`'s arm-and-park
protocol at `Eval.scala:18-27`) has to be re-pinned. It is listed here because it is the only
candidate with a recorded prior measurement of its exact mechanism and because it is the only
one that reaches the second 16 KB/op.

**Gated constructs.** Yes, leads with this. A new node kind and a change to the preemption
protocol are both owner decisions. No public API change to `<` or `ArrowEffect` is implied,
but `Safepoint`'s surface (`Safepoint.scala:111-135`) changes shape.

### C4. Restore the safepoint budget when delivery throws

**Hypothesis.** `Safepoint.exit` is not guarded, so every exception that unwinds through a
delivery permanently consumes park budget for that thread; enough of them turn every
subsequent `map` into a parking `map`, which is an allocation cliff of one `Bind` plus one
`Transform` per delivery.

**Mechanism.** `Pending.scala:54` enters the safepoint and `Pending.scala:59` exits it, with
no `try`/`finally` between them; same shape at `Pending.scala:86`, `:112`, `:138`, `:291` and
`ArrowEffect.scala:95`, `:165`, `:238`. On the normal path the budget is rebuilt as the park
propagates upward, because each enclosing frame reaches its `Safepoint.exit`. On the exception
path those frames are skipped. `depths` is a per-thread static cell (`Safepoint.scala:23-27`),
so the deficit persists. `Eval.partial` brackets with `save`/`restore`
(`Eval.scala:22-25`) but restores the already-drifted outer value; `Eval.apply`
(`Eval.scala:12-13`) brackets nothing, and the pure-composition descent that the benchmark
performs (`Eval(loop(0))`, `ProtoKernelBench.scala:116`) runs entirely above `Eval.apply`, so
there is no enclosing kernel boundary for it at all. `Safepoint.reset`
(`Safepoint.scala:131-132`) exists, is public on the object, and has no production caller
anywhere in the module (grep over `kyo-kernel2` finds it only in tests and in a commented-out
block at `Eval.scala:137` of the non-proto sibling), which reads like the healer that was
written and never wired.

Once the budget reaches zero the degradation is total, not gradual: `enterPark` writes
`drained` = `(self & Armed) | DepthGuard` (`Safepoint.scala:59`, `:114-117`), so the next
`enter` computes `s2 = DepthGuard - 1`, fails the guard test again, and parks. Every delivery
parks until something restores the state.

**Predicted signal.** Needs one new probe row, since none of the fifteen throws. Add a row
that runs the `fusionAllocatesNothing` body after a `@Setup` that throws N times through a
mapped computation on the same thread. Prediction with the current code: that row's
`gc.alloc.rate.norm` rises from the 0.00 B/op that the fused shape holds today
(`proto-backlog.md:68`) to roughly 40 B per delivery, and `kyo.kernel.proto.Arrow$Bind` plus
the map `Transform` appear in that row's allocation flat table. With the fix: the row stays at
0.00 B/op and the flat table stays empty.

**Falsifier.** The pre-conditioned probe row reports 0.00 B/op unchanged before the fix. That
would mean something restores the budget that this reading missed, and the defect does not
exist.

**Target rows.** Moves: only the new probe row. Must not move: all fifteen existing rows, none
of which throws. A change in any of them means the fix reached the non-throwing path, which is
the thing to avoid.

**Cost and risk.** This is a defect, not a tuning knob, and it should be reproduced before it
is fixed (SKILL.md's reproduce-first rule, and the root guide's). Two fix shapes with different
costs: a `try`/`finally` in all eight delivery arms is correct and pays hot-path bytecode in
the kernel's most size-sensitive body, which risks C2's `inline (hot)` verdict on `run$39`
(`qa-jit.txt:4231`); wiring `Safepoint.reset` at a drive boundary is free on the hot path but
only bounds the leak to one composition rather than eliminating it. The second is not
obviously sufficient, since the benchmark's own descent shows compositions that never touch a
drive boundary. `Safepoint.reset` is also an unexercised production path today, so wiring it
means owning whatever it was written for and never checked against.

**Gated constructs.** None for the `Safepoint.reset` wiring. The `try`/`finally` shape adds no
gated construct either, but it must be measured against the inlining log before it is
accepted, so it carries the same "method size is a design property" obligation
(SKILL.md:250-256).

### C5. Widen the lift emission's static bare-cast arm

**Hypothesis.** `CanLift.liftImpl` proves "cannot be `Boxed`" only for `Nothing`, `AnyVal`,
`String`, and final classes; sealed hierarchies whose leaves are all non-`Boxed` are equally
provable, and each site so proven loses both the runtime `Boxed` test and the possibility of a
`Nested` allocation.

**Mechanism.** `CanLift.scala:52-63`:

```scala
def isValue   = wide <:< TypeRepr.of[AnyVal] || wide <:< TypeRepr.of[String]
def isSafeFinalClass =
    sym.isClassDef && sym.flags.is(Flags.Final) && !sym.flags.is(Flags.Trait) &&
        !(wide <:< TypeRepr.of[Boxed])
...
if isNothing || isValue || isSafeFinalClass then '{ $v.asInstanceOf[A < S] } else '{ Nested.nest[A, S]($v) }
```

`Boxed` is `private[kyo]` (`Pending.scala:9`) and is extended only by `Arrow`
(`Arrow.scala:10`) and `Nested` (`Pending.scala:14`), so a sealed hierarchy whose leaves are
all outside that set admits no `Boxed` inhabitant, exactly as a final class does. A
non-final case class (Scala case classes are not final by default) currently falls to the
runtime arm and pays the `Boxed` test at every lift.

**Predicted signal.** `javap -c` at a new probe site: a lift of a value whose type is a sealed
non-kyo hierarchy emits `checkcast` where it currently emits
`invokestatic kyo/kernel/proto/Nested.nest`. On the fifteen benchmark rows: nothing moves, on
either `gc.alloc.rate.norm` or score. That null prediction is the falsifiable part.

**Falsifier.** The probe site still emits `invokestatic ... Nested.nest` after the change, or
any of the fifteen rows moves beyond the 4.0% band, which would mean the widened analysis
reached code it was not supposed to reach.

**Target rows.** Moves: none of the fifteen. Every lift in `ProtoKernelBench.scala` is `Int`,
`Unit`, or the deliberately abstract `boxed[A]` (`:204`), and none of those changes class under
the widened analysis. This candidate buys nothing on this suite and is ranked last for that
reason; its value is in real user code, where sealed and enum-shaped payloads are common.

**Cost and risk.** The analysis is soundness-critical: SKILL.md:87-88 categorizes the
bare-cast arm as "macro-emitted under analysis", and SKILL.md:65-68 records that a wrong
judgement here compiles clean and fails nine nesting tests. Getting `sealed` right means
walking every leaf transitively and rejecting any hierarchy that is open at any level or that
has a kyo-internal member. The pinning tests are the existing nesting suite plus one new case
per admitted shape.

**Gated constructs.** Yes. It edits the lift's macro (`CanLift.scala:45-65`), and the bare-cast
arm it widens is itself the closed-set concession SKILL.md:87-88 names, so a new admission rule
is an owner decision. It adds no `inline` and no public API change; the `asInstanceOf` it emits
already exists at `CanLift.scala:63` and only fires at more sites.

## 4. Ranking and why

1. **C1** first. It targets the exact 16,043 B/op the capture localizes, it is eight
   mechanical edits to one repeated arm, and one `gc.alloc.rate.norm` reading settles it
   either way. Cheap and decisive.
2. **C2** second, only because it is nearly free and its three predicted signals are all
   JIT-log or `javap` facts that cannot come back ambiguous. Low ceiling, and its honest claim
   is that it changes no score.
3. **C4** third. It is a defect rather than a speedup, its blast radius is every `map` on a
   thread that has seen a throw, and it needs a reproduction before anything else.
4. **C3** fourth by expected value and first by ceiling. It is the only candidate that reaches
   the second 16 KB/op, and the only one whose mechanism already has a measured prior, but it
   is a design change to preemption and wants a conversation before an edit.
5. **C5** last. Correct, cheap, sound to want, and worth nothing on this suite.

## 5. Considered and dropped

- **Remove the `Frame` field from `Arrow.Suspend`** (`ArrowEffect.scala:24`,
  `Arrow.scala:107`). `anon$95` measures 16 B/op per instance; under the default 12-byte
  object header a one-reference instance and a zero-field instance are both 16 bytes after
  alignment, so the field costs nothing to carry. Predicted saving: zero. Dropped for having
  no signal. It would become an 8 B/op saving under `-XX:+UseCompactObjectHeaders`, which this
  run did not use (`qa-alloc.txt:14`, VM options are `--add-opens` only).
- **Cache the `Suspend` node per call site.** `ask` is a non-inline `def`
  (`ProtoKernelBench.scala:195`), so its `Suspend` captures the `Frame` parameter and the
  expansion is not closed; no kernel-side hoist can make it a constant. Making `ask` inline is
  a benchmark change, not a kernel one, and it would be measuring the benchmark.
- **Return the loop payload bare instead of a `Continue` node.** Settled, with three failed
  designs and their numbers recorded in the source (`Loop.scala:127-152`, including "the
  stored box costs Loop's driver 16 extra bytes and 2.9x time per settled iteration") and in
  SKILL.md:136. Raising it again without new evidence would be relitigating a settled
  question (SKILL.md:343-345).
- **Merge `Stack`'s three parallel copies into one array** (`Stack.scala:63-70`, called at
  `Eval.scala:60-62`, `:114-116`, `:147-149`). Reading the guards, the copies fire only when a
  marked entry sits above the handler index, which the two-handler shape of
  `emittingClausesPayRegionRebuild` does not appear to produce (the `outcome` call at
  `Eval.scala:60` would take the `noEntries` sentinel). Predicted signal is therefore probably
  "nothing moves", which is not a candidate. It needs an allocation capture of that row before
  it can be proposed honestly, and no such capture exists in `qa-artifacts/`.
- **Shrink `Stack::truncate` so it inlines** (52 bytes, `failed to inline: callee is too
  large`, `qa-jit.txt:4455`). Its only hot caller is inside `Eval$::loop`, which never inlines
  anyway (SKILL.md:260-262), so the failure costs one static call per drive. SKILL.md:465-468
  also records that making a delivery entry small enough to inline measured slower on its own
  row. Dropped as low value with a recorded counterexample.

## 6. Open, not settled

- Whether C2's escape analysis actually fires once `v` stops escaping is unknown. C1 is the
  experiment; no reading of the code settles it.
- Whether any row other than `nestedPayloadsUnwrapInMaps` allocates a `Nested` is derived from
  the emission rule (`CanLift.scala:52-63`) plus the benchmark sources, not from a capture.
  Only this row was profiled. C1's "must not move" list is that derivation's falsifier.
- C4 is a reading of the code, not a measurement. It has not been reproduced. Treat it as an
  unverified defect report until a failing probe exists.
