# Oracles: the raw data behind v3's load-bearing numbers

v3 quoted three figures it inherited from a held-out review rather than deriving. Re-derived here from
the artifacts. **One confirmed exactly, two wrong.** Raw data is the arbiter, so these supersede both
the review's numbers and my own earlier writeups.

These are oracle extractions, not verdicts: they expose raw facts so the tool's output can be checked,
and they are the ground truth the corresponding fixes get tested against.

## 1. `InlineSites.bytes` population: CONFIRMED, exactly

    qa-control-36b41336fb  entries= 944  bytes>0= 887  (94%)
    qa-variant-0f9b4b69f7  entries= 958  bytes>0= 904  (94%)

Both 94%, matching the review. **Cutting v2's item 7 is correct**: every Full leg already records
per-method sizes from HotSpot's own `bytes=` attribute, so capturing them again via javap is
redundant, and `Bytecode.verifyAgainst` exists precisely to treat javap as the side needing
verification.

## 2. Row-to-method attribution: mechanism CONFIRMED, fraction WRONG

The review said **99 of 1,117 C2 verdicts (8.9%)** were made compiling the row's own `jmhStub`. On
`bench-results/exp1/logc-new-default.xml`, splitting on `<task ` boundaries (the blocks are not closed
with `</task>`, which is what made my first attempt find 8 verdicts instead of thousands):

    inline verdicts inside task blocks: 6329
      JMH/JDK-rooted                4200   66.4%
      kyo.-rooted (shared)          1988   31.4%
      row-own jmhStub/jmhTest        141    2.2%

**141 of 6,329, or 2.2%**, not 99 of 1,117. Same direction, different denominator: the review filtered
to some C2/`kyo.`-prefixed subset it did not state. The fraction is **filter-dependent and should not
be quoted as a bare number.**

What is not filter-dependent, and is the actual finding:

- The row key **is** present, on the `<task>` element:
  `ProtoKernelBench_continuationBodiesFuse_jmhTest continuationBodiesFuse_avgt_jmhStub`.
- The harness **already parses it** into `Task.method` (`LogCompilation.scala:170`).
- One `flatMap` at `LogCompilation.scala:272` discards it.
- A non-trivial slice of verdicts is attributable to the row's own OSR stub, which the harness itself
  calls "where the measured code actually runs".

So v2's "hard limit" claim is still false and item 2 still stands. Only the number changes.

This log names exactly **one** benchmark row (`continuationBodiesFuse`), consistent with defect 30 but
not proof of it: it was a single-row invocation, so it cannot distinguish per-fork truncation from
having only ever had one row.

## 3. `KnownNoise` understatement: WRONG, and worse than recorded

Three documents said the true non-kernel share is **70%** against the 29% the tool prints. From
`qa-artifacts/qa-cpu.txt` (28 frames, percentages summing to 100.01):

    KnownNoise matches                  :  29.07%   <- what the tool prints
    ProtoKernelBench.* (benchmark code) :  48.37%   <- missed entirely
    kyo.kernel.proto.* (kernel-owned)   :  16.04%   <- the ONLY movable part
    JDK / native / other                :   6.53%
    => truly not movable by a kernel change: 83.97%

`KnownNoise` is `Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")`, and on this profile it
matches `scala.runtime.BoxesRunTime.boxToInteger` and **nothing else**. The benchmark's own generated
code, `ProtoKernelBench.loop$9` (17.14%), `run$39` (13.23%), `ask` (11.06%), `anon$95.<init>` (6.72%),
contains no `jmh_generated` in its frame names and is missed in full.

The sentence the tool prints is "% of sampled time is in classes no kernel change can move". The
answer is **83.97%** and it prints **29.07%**: understated by **54.9 points**, not 41, and in the
direction that flatters the kernel. Only **16.04%** of this profile is kernel-owned at all.

**Item 4's acceptance is this table**, and its fixture is this capture rather than authored frames.

## The pattern

Four numbers I have published in this campaign have been wrong: a one-arm forecast called
"systematically optimistic" when it errs both ways, "the two largest deltas are flat" when it was
three, "three of eight selectors wired" when it is one, and now "70%" when it is 84%. Every one was
wrong in the direction that flattered either the tool or the kernel, and every one was caught by going
back to the raw data rather than by re-reading the writeup.
