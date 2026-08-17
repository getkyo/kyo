# Measurement protocol for the first two experiments

The two cheapest decisive experiments from `optimization-plan.md`, written out so they can be run
without re-deriving anything. Both target the open `continuationBodiesFuse` +4.3%, and between them
they decide which of DIS-1 and DIS-2 owns it.

Run them **before any edit lands**. Running an isolation experiment after an edit reproduces the
exact failure the skill records: a mechanism stated confidently and contradicted by the next
measurement.

## The build works

Verified rather than assumed, since an earlier note in this campaign said the repo did not compile:

    sbt kyo-kernel2JVM/Jmh/compile   ->   [success] Total time: 16 s, 0 errors

The JMH generator processed 684 classes and wrote the benchmark stubs. Measurement is unblocked; the
16-second incremental build also means a leg's recompile is cheap enough that the C V C V C shape is
affordable.

## Preconditions, all of which the harness checks

- The measurement worktree is detached (`/Users/fwbrasil/workspace/kyo/.claude/worktrees/bench-sweep`).
- No agent, build, or background job runs during a measurement. This is the one rule the harness
  cannot enforce for itself.
- The suite is green on the leg being measured, checked before the runs are spent.
- Markers read before and after each leg, so a number carries proof of which design produced it.

## Experiment 1 (DIS-2's falsifier): the flag probe

**Needs no source change**, which is why it goes first. Four numbers, one table.

The claim under test: `continuationBodiesFuse` is slower under the current design because the
`SuspendWith` delivery path exceeds an inlining budget, not because of the extra frame.

    # on each design in turn, same session, back to back
    -f 2 -wi 10 -i 5 -r 1s -w 1s -prof gc -prof comp \
      -jvmArgsAppend "-Xms4g -Xmx4g -XX:+UseG1GC" \
      kyo.kernel.bench.ProtoKernelBench.continuationBodiesFuse

    # then the same, adding:
      -XX:FreqInlineSize=600

| | default | FreqInlineSize=600 |
|---|---|---|
| old design `36b41336fb` | A | B |
| current design | C | D |

**Reading it.** If `D - B` closes the gap that `C - A` shows, the cause is budget, and DIS-2's
size argument is the mechanism. If the gap survives at 600, budget is not the cause and DIS-1's
out-of-line frame is the remaining candidate. If raising the budget moves *both* designs equally,
the flag is doing something unrelated to the difference and the experiment is void.

**Efficacy gate, mandatory.** A flag that silently fails to take refutes everything and passes. The
run must re-collect the per-site C2 verdict for the delivery method and show it actually changed
before either reading above is recorded. Without that gate this experiment can only produce a
confident answer, never a correct one.

## Experiment 2 (DIS-1's falsifier): the isolation run

The claim under test: the +4.3% is the out-of-line `dispatch$1` frame, which `javap` shows as a
separate 607-byte method invoked at bci 821 of a 1582-byte `Eval$::loop`, and which the old design
did not have because `ask.map{...}` minted a `Suspend` (expanded inline) rather than a `SuspendWith`.

**Add the frame to the old design and measure.** One edit on `36b41336fb`, in the throwaway worktree,
never committed: force the old design's suspension delivery through a non-inlinable call, changing
nothing else.

- **Reproduces the +4.3%**: the frame is the mechanism. DIS-1 is the fix.
- **Does not reproduce it**: the frame is not the mechanism, and the hypothesis is dead regardless of
  how well it explains the code.

This is the one that settles it. The project's record is that isolation runs settled every
attribution and profiles settled none.

## What the harness must report either way

- The A/A null first. If the null is dirty, nothing below it is readable.
- The minimum detectable effect on every flat row, so a null result means "flat to within X%".
- The diffstat over the restored paths, since a mechanism naming one method is inadmissible while
  the diff spans several.
- `gc.alloc.rate.norm`, which is exact and independent of every sampling decision.

## If both experiments come back inconclusive

That is a real result and gets recorded as one. The failure this whole rework exists to prevent is
choosing the hypothesis that best fits the evidence already gathered and calling it a mechanism.
