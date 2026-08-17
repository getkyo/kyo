# IN-2: the right answer, for the wrong methods, on a path the profile never reaches

Candidate 9 in `optimization-plan.md`, included as "the honest low-ceiling case: its own analysis predicts
it changes no score". The prediction is right. The reasoning behind it is not, and neither is the
target list.

No run spent. Both readings came out of the harness over evidence already stored.

## What IN-2 says

> **Hypothesis.** Both `Eval.dump` and `Stack.truncate` execute entry-compare-and-return essentially
> always, so the body is dead weight in the hot unit.
> **Falsifier.** Sizes drop, verdicts hold, score flat. **Likely outcome given both already inline hot.**

## The verdicts, from the compilation log

    kyo.kernel.proto.Eval$::dump$1        92B inlined                              sites=2
    kyo.kernel.proto.Stack::truncate      52B refused at 2/4 sites (too big)       sites=4
    kyo.kernel.proto.Stack::grow          55B refused (too big)                    sites=4
    kyo.kernel.proto.Stack::apply          7B refused at 4/5 sites (low call site frequency)
    kyo.kernel.proto.Stack::marked        15B refused at 2/4 sites (low call site frequency)
    kyo.kernel.proto.Stack::pop           52B inlined                              sites=2
    kyo.kernel.proto.Stack::push          50B inlined                              sites=4

Three corrections to the candidate, in order of how much they matter:

1. **`Eval.dump` has nothing to fix.** It is 92 bytes and inlined at both its sites. The candidate's
   own falsifier ("sizes drop, verdicts hold") is already satisfied for it before any edit, because
   there is no refusal to hold or flip.
2. **"Both already inline hot" is wrong for the other one.** `Stack::truncate` is refused at half its
   sites, for size.
3. **The method with the strongest refusal is not in the candidate.** `Stack::grow`, 55 bytes,
   refused at 4 sites of 4. If splitting at an entry test is the move, that is the method it applies
   to, and IN-2 does not name it.

So IN-2's prediction of no score change was reached from a premise the log contradicts. Right answer,
wrong reasoning, which is the shape this project keeps having to correct.

## Why the prediction survives the correction anyway

The CPU profile of `nestedPayloadsUnwrapInMaps`, 28 sampled frames:

    no Stack or dump frame appears in the profile at all

    boxToInteger                            29.1%
    ProtoKernelBench.loop$9                 17.1%
    ProtoKernelBench...run$39               13.2%
    ProtoKernelBench$.ask                   11.1%
    ProtoKernelBench$$anon$95.<init>         6.7%
    Nested$.apply                            5.0%

Not one `Stack` frame is sampled. A refusal costs only when the call happens, so a method the profile
never reaches cannot be worth an inlining verdict however far over the budget it sits. `Stack::apply`
being refused at 4 of 5 sites for **low call site frequency** rather than for size says the same thing
from the JIT's side: it judged those sites cold.

**IN-2 is refuted for the rows profiled**, and refuted more strongly than its own analysis claimed:
not "the sizes drop and the score does not move", but "the methods are not on the measured path".

## Limits

- **One row profiled.** IN-2 names the map-heavy rows; only `nestedPayloadsUnwrapInMaps` has a CPU
  profile stored. A different row could reach `Stack::grow`, and nothing here says it does not.
- **The log carries no invocation counts for these callees.** It profiled a receiver at 12 sites out
  of 5,093, and none of them is a `Stack` method, so "how often is `grow` called" cannot be answered
  from this instrument. The CPU profile answers the question that matters instead, by not sampling it.
- **A 28-frame profile is coarse.** The skill's own reading note applies: use it directionally, to
  notice that a method is absent, never to attribute percentages.

## The finding underneath, for the third time

29.1% of this row's samples are `boxToInteger` and the next three frames, another 41.4%, are the
benchmark's own generated methods. Together that is 70% of the profile in code no kernel change
touches. This is the third independent reading pointing the same way: the largest flag effect measured
belonged to the benchmark's closures, the top budget candidate is one of them, and now the CPU profile
says most of the time is theirs too.
