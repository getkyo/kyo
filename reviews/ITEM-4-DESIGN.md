# Item 4, designed against the derived table

Prepared while a held-out reviewer holds the sources read-only, so implementation is mechanical.

## Why the constant cannot be fixed by adding entries

    val KnownNoise = Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")

    def noiseShare(run: Run): Double =
        ... run.cpu.filter(c => KnownNoise.exists(c.method.contains)) ...

**The predicate is inverted.** It asks "which frames are known noise?", which requires enumerating
everything that is not the kernel: the benchmark's own generated closures, boxing, JDK internals, the
allocator, native scheduler frames. That set is open and grows with every benchmark added, so the
constant is guaranteed to under-report forever, and it under-reports in the direction that flatters the
kernel. Adding `ProtoKernelBench` to the list fixes this profile and not the next one.

**The kernel is the closed set.** It is one package and it is known.

## The change

    /** Package owning the code under test. Time outside it is time no kernel change can move. */
    val KernelPackage = "kyo.kernel.proto."

    /** Fraction of a run's sampled time in classes no kernel change can move. */
    def noiseShare(run: Run): Double =
        val total = run.cpu.map(_.nanos).sum
        if total == 0L then 0.0
        else run.cpu.filterNot(_.method.startsWith(KernelPackage)).map(_.nanos).sum.toDouble / total * 100

**Open question, flagged rather than guessed:** the prefix hardcodes the *proto* kernel. `Cli.protoPaths`
already localises that assumption, so the honest shape is to carry the prefix on the run or take it
from the same place `protoPaths` comes from. **Default if unresolved: `kyo.kernel.proto.` with the
constant named `KernelPackage` so the assumption is visible**, rather than leaving it implicit in a
noise list.

## Acceptance: the derived table, not a remembered number

From `qa-artifacts/qa-cpu.txt` (28 frames, percentages summing to 100.01):

| category | share |
|---|---|
| `KnownNoise` matches (what the tool prints today) | 29.07% |
| `ProtoKernelBench.*`, the benchmark's own code | 48.37% |
| `kyo.kernel.proto.*`, kernel-owned, the only movable part | 16.04% |
| JDK / native / other | 6.53% |
| **`noiseShare` must report** | **83.97%** |

So the fix moves the printed figure from **29.07%** to **83.97%**, correcting an understatement of
**54.9 points**.

## The second half: name frames instead of one aggregate

`Store.noiseNote` prints one number and suppresses below 25%. At a corrected 84% it fires on every Full
run, which is right, but a bare percentage still tells the operator to go look. Per v3's rule that a
flag carries its own evidence:

    ℹ️  84% of sampled time is outside kyo.kernel.proto, so kernel-attributable movement is a
        fraction of each delta above. Largest contributors:
          29.1%  scala.runtime.BoxesRunTime.boxToInteger
          17.1%  kyo.kernel.bench.ProtoKernelBench.loop$9
          13.2%  kyo.kernel.bench.ProtoKernelBench...run$39

The frames are already in `Run.cpu`, which the survey lists as **captured-but-never-rendered** beyond
this one percentage: the second of the two extra JMH invocations per Full leg. This gives it its first
real output.

## The fixture, which is the actual defect-9 instance

The current fixture is two authored frames containing no `ProtoKernelBench`, so it **cannot fail**: it
was written by the same understanding that wrote the constant. Replace it with
`qa-artifacts/qa-cpu.txt`, a real capture, asserting the table above.

`QaParsers.scala:72` only *prints* `noiseShare` and asserts nothing, so today nothing anywhere fails if
this number is wrong. That is why it stayed wrong through the whole campaign.

## Ordering note

Item 4 needs no prerequisite: it touches `Run.cpu`, not `Run.jit`, so Step 0 does not gate it. It is
the strongest starting point in the plan because the fix, the acceptance table and the fixture are all
already derived.
