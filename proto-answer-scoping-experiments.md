# Answer scoping in the proto loop arm: isolated experiments (2026-08-19)

Base: `461cf8e9ea` (pending loop clause evaluates with its region off the stack). Four variants built in
detached worktrees (`.claude/worktrees/exp-v1`, `exp-v2` (then V4 on top), `exp-v3`, `exp-base`), each run
over `kyo.proto.*` plus two probe tests added to `EvalTest` in every variant. Nothing in the committed tree
was changed by this exercise.

## The law under test

`handleLoop`'s clause returns `Outcome[O[C] < (E & S), B] < S`. The answer payload is region currency at
the handler's row: it runs under this handler (an own-tag re-raise is answered here) but the interior is
not in its scope (its foreign effects reach the handlers below, not a region inside the interior), and the
interior comes back around the answer's value (the remainder runs inside the interior's regions). Pinned by
`EvalTest:236-352` (six tests, red at base) and the two probes (own-tag re-raise, settled-outcome and
pending-outcome forms; green at base, they guard against parking the handler together with the interior).

At base both paths violate it for a pending answer: the settled-outcome arm leaves the interior on the
stack while the answer evaluates (`r._1` in place), and the pending-outcome node applies the dumped
interior to the answer with `k(r._1, Arrow.id)`, whose `Chain.apply(pending, next) = Defer(pending, chain,
next)` is flattened by `push`, so the interior's regions are active while the answer evaluates.

So the repair is: while a pending answer evaluates, the interior is parked as one value; the handler stays
present; the interior is flattened back when the answer's value enters it. The settled answer stays in
place (the hot path, `handleLoopAnswersInPlace`).

## Why `chain(value, next)` is called at all (the question raised mid-experiment)

A `Chain` exists only as `Stack.dump`'s fold. The evaluator decomposes it by `push` (flatten) or by the
fused walk `next.head(apply(b), next.tail)` (`Arrow.apply`'s transform, `Handler.apply`). The two-argument
`Chain.apply(v, next) = Defer(v, this, next)` means "I cannot fuse, re-push me", and at base it is reached
from:

- the four `*With` nodes, `done = v => next(apply(v), Arrow.id)` (`ArrowEffect.scala:42,157,193,231`), and the
  new outcome node (`Eval.scala:66`): they apply the whole tail instead of walking it. With a one-entry
  tail this is the walk; with two or more entries the result is deferred and the tail re-flattened by the
  trampoline instead of fused.
- `Id.apply`'s fallback (`Arrow.scala:101`), unreachable in practice.
- the base arm's `k(r._1, Arrow.id)`, which should be written as the reification it is, `Kyo.Defer(r._1, k)`.

Connection to the quadratic (`trailingMapsStayLinear`, 974 ms/op in the probe run; the ~42 s tower): it is the
same cost one level up. At depth d the stack holds the suspension's continuation plus d trailing maps; the
`HandlerCont` capture `dump(pos)` folds d entries, `cont(1)` re-pushes d entries, and every settled value at
the done branch reifies the whole tail with `dump()` and the walk re-pushes the remainder whenever it
defers after one step (budget or pending). O(d) per level, O(n^2) per run. The `next(...)` sites are a
milder instance (a re-flatten per node), not the cause; that row has no `*With` node in it.

What kept the old evaluator linear on that row (its own comment, `2da854f5aa` `Eval.scala:99-107`): a
captured interior went back on the stack as ONE entry, so the next capture folded one entry, not every
continuation again. In the current design a captured chain is flattened on push, so the trailing maps are
re-chained and re-flattened at every level. Any fix must respect that the prefix of a captured interior up
to and including its last handler has to be flattened when a settled value enters it (the interior's own
computation runs inside those regions); only a handler-free suffix could be kept as one entry, and applying
it to a settled value must decompose it (a walk, not a re-push), or it loops. That is a separate design
item; recorded here because the question tied them.

## Variants

Common to all four: the pending-outcome node takes the interior and the handler off the stack separately
(`dump(pos)` then `pop`) and re-enters the region as `Defer(<answer into interior>, h)`; `Defer` with a
handler as `contA` is what `Handle` is (the two Eval arms are identical). The settled-outcome arm splits
the answer: settled stays `r._1` in place; pending dumps the interior and parks it. They differ in WHERE
"pending value, then chain" gets its reification.

| | parking lives in | `Chain.apply` 2-arg called | chains become stack entries | extra on hot paths | delta (main) |
|---|---|---|---|---|---|
| V1 | `Chain.apply`'s pending arm: `Defer(v, Arrow(v => apply(v)), next)` | yes, by design | no | every `Chain.apply(pending, next)` allocates a wrapper, including handler-free tails reached through the `*With` nodes' `next(...)` | Arrow +4, Eval +9 |
| V2 | the two answer sites, `r._1.map(k(_))` (the CPS line; a pending answer is deferred behind the interior as one transform) | no new call | no | none; settled-outcome/settled-answer path loses the per-turn `map` transform allocation the base arm had | Eval +13 |
| V3 | the evaluator: `Defer` arm pushes `contA` whole when the value is pending (`Stack.push(f, flatten)`), sites write `Kyo.Defer(a, k)` | yes, when the whole-pushed chain is popped and applied | yes | a `lower` on every `Defer` value; every `Defer(pending, chain)` takes one extra trampoline turn | Eval +10, Stack +4 |
| V4 | V2, plus the five `next(apply(v), Arrow.id)` sites walk `next.head(apply(v), next.tail)` under the budget, as `Arrow.apply`'s transform does | never (only `Id`'s fallback remains) | no | the `*With` nodes fuse into a multi-entry tail instead of re-pushing it; each inline site grows by the guard | ArrowEffect +31, Eval +22 |

V1 verbatim (`Arrow.scala`, `Chain.apply`):

```scala
def apply[D, S2](v: A < S2, next: Arrow[C, D, S2]) =
    v.lower(
        pending = Kyo.Defer(_, Arrow((v: A) => apply(v))(using Frame.internal), next),
        done = _ => Kyo.Defer(v, this, next)
    )
```

V2 verbatim (`Eval.scala`, loop arm; the node otherwise as at base):

```scala
pending = clause =>
    val k = stack.dump[OX[CX], AX, EX & S](pos)
    discard(stack.pop())
    new Kyo.Defer[...] with Arrow.Transform[...]:
        ...
        def apply(o: Loop.Outcome[OX[CX] < (EX & S), BX]) =
            o match
                case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked => Kyo.Defer(r._1.map(k(_)), h)
                case v                                               => v.asInstanceOf[BX]
        ...
,
done =
    case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
        r._1.lower(
            pending = _ =>
                val k = stack.dump[OX[CX], AX, EX & S](pos)
                r._1.map(k(_))
            ,
            done = _ => r._1
        )
    case v =>
        stack.truncate(pos + 1)
        v
```

V3 verbatim (`Eval.scala` Defer arm and sites; `Stack.scala` gains `push(f, flatten: Boolean)` with the
chain case guarded by `flatten`):

```scala
case kyo: Kyo.Defer[?, ?, A, S] @unchecked =>
    stack.push(kyo.contB)
    stack.push(kyo.contA, kyo.value.lower(pending = _ => false, done = _ => true))
    curr = kyo.value
...
case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked => Kyo.Defer(Kyo.Defer(r._1, k), h)
...
pending = a => Kyo.Defer(a, stack.dump[OX[CX], AX, EX & S](pos)),
```

V4 delta over V2, at each of the five sites:

```scala
done = v =>
    val slot = Safepoint.get()
    if !Safepoint.enter(slot) then
        Kyo.Defer(b, this, next)
    else
        val out = next.head(apply(v), next.tail)
        Safepoint.exit(slot)
        out
    end if
```

## Results

Tests, `kyo.proto.*` (149 at base + 2 probes), one run each, exit 0:

| variant | ran | green | red |
|---|---|---|---|
| base `461cf8e9ea` | 149 | 143 | 6 (the answer-scoping six in `EvalTest`) |
| V1 | 151 | 151 | 0 |
| V2 | 151 | 151 | 0 |
| V3 | 151 | 151 | 0 |
| V4 | 151 | 151 | 0 |

Correctness does not separate the four; the probes pass in all (the handler is present for the answer in
every variant).

Probe bench, `YetAnotherProtoBench`, `-f 1 -wi 1 -w 1 -i 1 -r 1`, same session back to back, base then V4
(V4 is the only variant that touches the `*With` nodes, which the fuse rows exercise). These are hints, not
results: one fork, one second, a loaded machine; a claim needs `-f 3` and a quiet machine.

| row | base us/op | V4 us/op | delta |
|---|---|---|---|
| evalFixedOverhead | 0.014 | 0.014 | +0.0% |
| fusionAllocatesNothing | 0.592 | 0.590 | -0.3% |
| nestedPayloadsUnwrapInMaps | 8.537 | 8.577 | +0.5% |
| continuationBodiesFuse | 33.809 | 34.012 | +0.6% |
| deepRecursionPaysRescuesOnly | 65.465 | 64.379 | -1.7% |
| idleHandlerAddsNothing | 79.991 | 82.169 | +2.7% |
| fusionPastBudgetPaysRescuesOnly | 80.638 | 80.328 | -0.4% |
| uncachedValuesPayBoxingOnly | 83.223 | 84.708 | +1.8% |
| suspensionFusesContinuation | 104.344 | 105.380 | +1.0% |
| emittingClausesPayRegionRebuild | 174.475 | 168.989 | -3.1% |
| handleLoopAnswersInPlace | 219.332 | 219.662 | +0.2% |
| handleLoopFusesContinuation | 236.091 | 237.430 | +0.6% |
| statefulAnswersPaySuccessor | 281.601 | 285.412 | +1.4% |
| suspensionBaseline | 397.791 | 403.084 | +1.3% |
| trailingMapsStayLinear | 947787.729 | 965147.771 | +1.8% |

Every row inside the machine's 3-4% drift band; the fuse rows do not move, so the `*With` walk is a
cleanliness change at this resolution, not a speed change. (Aside, not a claim: `handleLoopAnswersInPlace`
read 238.7 in the earlier probe at `5d12766c62`, before the base arm replaced `.map` with `lower`; a
different session, not comparable, recorded only as the row to bracket if the allocation claim matters.)

## Reading

- V1 puts the parking inside a call that should not be on any path (`Chain.apply` two-argument) and taxes
  every pending application of a handler-free tail reached through `next(...)`. Dropped.
- V3 makes chains stack entries and keys a flatten decision on the value's state inside the evaluator; it is
  the VM reading of the same law. Dropped.
- V2 is the law spelled with the combinators at the two sites where an answer meets its interior, nothing
  else moves, and the settled-answer hot path now allocates nothing per turn (the base arm's `.map` built a
  transform per answer even when it ran immediately; unmeasured claim, the `handleLoopAnswersInPlace` and
  `statefulAnswersPaySuccessor` rows would show it).
- V4 answers the question "why is `chain(value, next)` called": after it, nothing applies a whole tail, the
  `*With` nodes fuse like every other transform, and `Chain.apply`'s two-argument form has no caller but
  `Id`'s fallback. Its cost is the guard in five inline bodies (bytecode per call site) and it is
  independent of the answer fix; it can land separately or not at all.

## Open, unchanged by this

- The `HandlerLoopState` arm still dispatches a pending stateful clause in place; it takes the same shape
  as the chosen variant once the handler re-entry spelling (`initialState` = the clause's new state) is
  ruled; the same spelling serves the capture gap.
- The quadratic (`trailingMapsStayLinear`, the tower) is its own item, see above.
- The done branch reads `state(0)` after `pop` (`Eval.scala:104-105` at base); as `Stack.pop` stands that
  reads the next entry's slot. Unpinned; raised, not changed.
