# Three candidates' premises, checked against evidence already on disk

No benchmark was run for any of this. The compilation log from experiment 1 was already stored, and
the harness reads it; these are readings, not measurements, and each says which part of its candidate
it settles and which it does not.

## IN-1, split `Arrow.Identity.apply` into fast entry and slow path

The candidate's field is "per-site C2 verdict: predicts `deliver` refused with `low call site
frequency` at the same 5 sites". The log says:

    kyo.kernel.proto.Arrow$Identity$::apply   92B inlined, 25 sites, 0 refused

**That field cannot move.** The method is already inlined at every one of its 25 sites, so there is no
refusal for a fast/slow split to flip, and the candidate's own falsifier ("the size drops but no
verdict moves") is satisfied before the edit is written.

This is not a refutation of the candidate, it is a refutation of the field it nominated. Inlining 92 B
into a caller spends 92 B of that caller's budget, 25 times over. If 86 of those bytes really are dead
on the measured path, the effect worth looking for is in *other* methods' verdicts, not this one's:
whether freeing budget in 25 callers lets something else in. The tool's budget ranking below names
what is close enough to be flipped that way.

**Status: the stated field is dead; the candidate needs a different one before it is worth an edit.**

## IN-3, take the VarHandle guard chain off `Safepoint.get`

    java.util.concurrent.atomic.AtomicReferenceArray::get   12B inlined, 24 sites
    kyo.kernel.proto.Safepoint::get                         28B inlined, 23 sites
    kyo.kernel.proto.Safepoint::enter                       50B inlined, 23 sites
    kyo.kernel.proto.Safepoint::exit                        19B inlined, 23 sites
    kyo.kernel.proto.Safepoint::home                        17B inlined, 24 sites
    kyo.kernel.proto.Safepoint$::inline$depths               4B inlined, 24 sites

The candidate claims one `AtomicReferenceArray.get` force-inlines **434 bytes** into the hot unit. The
log reports that callee at **12 bytes**. Both can be true: the log's figure is the callee's own
bytecode and the 434 is its transitive expansion through the VarHandle guard chain, which this
instrument does not show and cannot be asked for.

So the candidate's headline number is **not checkable against the compilation log**, and the reading
it needs is `javap` of the compiled unit, which is the field the candidate already names second.
Nothing here contradicts it; nothing here supports it either. IN-3 is additionally owner-gated for
raising a memory-model question.

**Status: unaffected by this evidence, and the log is the wrong instrument for its first field.**

## IN-2's premise, confirmed

IN-2 proposes splitting `Eval.dump` and `Stack.truncate` at their entry test. The tool's
budget-proximity ranking, worst first:

| method | size against budget | refused |
|---|---|---|
| `ProtoKernelBench::run$56` | 379B against FreqInlineSize (325), 1.17x over | 10/10 sites |
| `kyo.kernel.proto.Stack::grow` | 55B against MaxInlineSize (35), 1.57x over | 4/4 sites |
| `kyo.kernel.proto.Eval$::dispatch$1` | 607B against FreqInlineSize (325), 1.87x over | 2/2 sites |
| `kyo.kernel.proto.Stack::truncate` | 52B against MaxInlineSize (35), 1.49x over | 2/4 sites |

`Stack::truncate` is 52 B against a 35 B cold budget and refused at half its sites, and `Stack::grow`
is 55 B and refused at all of them. Both are within 20 bytes of the budget, which is the range where
moving the cold half out of line can plausibly flip the verdict. **The premise holds and the targets
are ranked.**

One correction to the candidate as written: it names `Eval.dump`, which does not appear in this
ranking at all. `Stack::grow` does, is not in the candidate, and is the worse offender of the two by
ratio.

## The finding that is not about any candidate

The method ranked **first**, above every kernel method, is `ProtoKernelBench::run$56`: 379 bytes,
1.17x over the hot budget, refused at 10 of 10 sites. It is the benchmark's own closure. The same
family, `run$57` through `run$67` at 119 B, is what `FreqInlineSize=600` moved when it produced a
17.4% score change that had nothing to do with the kernel.

Two independent readings now point the same way: a material share of what these rows measure is the
benchmark's own generated code, and both the largest flag effect observed and the top budget candidate
belong to it rather than to the kernel.
