# C1 is refuted on its stated field, with no source change

Candidate 3 in `optimization-plan.md`, and the plan's own "single cheapest decisive candidate: one
allocation reading settles it either way". It is also owner-gated, because it needs an explicit
`Nested(...)` spelling that the kernel skill forbids without sign-off.

It did not need the edit, or the sign-off, to be answered.

## The hypothesis, and the reading that settles it

> The `Nested` box is heap-allocated only because the park arm stores the incoming union; taking it
> from the payload removes the allocation entirely.
>
> Field: `gc.alloc.rate.norm`, exact and per-op. The row is exactly 32,080.04 B/op, which is two
> 16-byte kernel nodes per iteration over 1001 iterations: `Nested` 16,043 and the `Suspend` node
> 15,985. This targets the first 16,043.
>
> Falsifier: B/op unchanged.

The premise is about *which code asks for the `Nested`*. Until this week the harness could not answer
that: the flat allocation table names the class and never the caller. The collapsed view added in
Phase 5 does, and the profile of `nestedPayloadsUnwrapInMaps` is unambiguous.

Every `Nested` sample on the row, all 3,790 of them, 100%, comes from one stack:

    ProtoKernelBench.loop$9
      ProtoKernelBench$.boxed
        Nested.nest
          Nested$.apply
            kyo.kernel.proto.Nested        3790 samples, ~1.99 GB

Zero samples are attributed to any park arm.

`ProtoKernelBench$.boxed` is the benchmark's own method, boxing a value at the lift boundary because
that is what the row exists to exercise. This is the single lift doing exactly what it is designed to
do, and it is nested once, at the public emission, per the representation contract.

## What that means for C1

The eight mechanical edits C1 proposes are to the park arms in `Pending.scala` and `ArrowEffect.scala`.
On this row those arms allocate nothing, so changing them removes nothing. **Refuted**, on the field
the candidate itself nominated, for the cost of reading a profile already captured.

Two limits, stated rather than glossed:

- **This is one row.** C1 names "`nestedPayloadsUnwrapInMaps` and the boxing rows"; only the first has
  been profiled this way. The others could differ, and nothing here says they do not.
- **The inlining confound applies**, as it always does: the profiler names where the JIT *placed* the
  allocation. Here the stack is four frames deep and lands in the benchmark's own `boxed`, which is
  not a placement a park arm could be mistaken for, so it does not weaken this reading.

## What it cost, and the tool change it forced

No benchmark run: the profile was already on disk from the Phase 5 validation. No source edit. No
sign-off needed for a gated change that turns out not to be worth making.

It did force one tool fix. The first attribution named `Nested$.apply` as the allocating frame, which
is true, unsurprising, and decides nothing: a type's own factory is where every one of its instances
is allocated. `AllocByMethod` now also carries the first frame *outside* the allocated type's own
code, and it is that frame, `ProtoKernelBench$.boxed`, that answers the question.
