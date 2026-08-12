# Compile-time benchmarking: old kernel vs kernel2

Goal: a defensible, repeatable measurement of the compilation-time cost each
kernel imposes on user code. The kernel controls this cost through the inline
expansion of map/suspend/handle (typer: inlining, implicit search, macro
execution) and through the volume of code it makes the backend emit (the
bytecode probe measured kernel2 at -32% per map site).

## Why not just time sbt

`sbt clean compile` couples the measurement to zinc invalidation, JVM startup,
and a cold compiler JIT; variance swamps a 10-30% effect. The compiler itself
must be warm and in-process, with fork-level statistics. That is exactly the
JMH shape.

## Instrument 1 (primary): batch dotc driver under JMH

A new `kyo-compile-bench` project (the kyo-kernel-bench pattern: standalone,
excluded from the mid-migration stack) depending only on `scala3-compiler` and
JMH. It has no kyo dependency at runtime, so it builds and runs in this
worktree today; the kernels enter only as classpath entries.

- Each op: run the dotc driver in-process on a corpus directory with
  `-classpath` = kyo-data classes + one kernel's classes, `-d` scratch dir,
  `-usejavacp:false`. A captured reporter asserts zero errors, so a broken
  corpus cannot masquerade as a fast one.
- One benchmark method per (corpus row, kernel), same JVM, JMH warmup brings
  the compiler to steady state; 3 forks give the error bars.
- Full pipeline: captures typer/inlining AND the backend cost of the emitted
  volume, i.e. what `sbt compile` users actually feel.

### Corpus (byte-identical for both kernels)

The mirrored KernelBench proved the shared surface is large enough. Rows:

1. `MapChains`: 10 methods of 10 chained maps (the inline-expansion hammer),
   plus scaling variants at 10/50/100 sites to expose the per-site slope.
2. `SuspendHandle`: suspend, suspendWith, handle, handleLoop sites in the
   shapes both kernels share.
3. `EffectRows`: generic methods over effect intersections, Tag and implicit
   search pressure.
4. The mirrored KernelBench source itself as the realistic slice.

Constructs whose shape diverges between kernels (the stateful handleLoop
answer form) are either excluded or held in per-kernel shim files of equal
size, so the measured text stays identical.

### Phase attribution (one-shot, not JMH)

Diagnostic runs with `-Yprofile-enabled -Yprofile-trace <file>` produce chrome
traces attributing time to typer/inlining vs erasure vs genBCode per kernel,
answering WHERE a delta comes from, the same role PrintInlining plays for the
runtime boards.

## Instrument 2 (secondary): interactive latency via kyo-compiler

kyo-compiler's warm per-config pool is the right instrument for the IDE-feel
number (typer-only diagnostics latency), and this doubles as dogfooding.

- Must run from a green checkout (main): this worktree's kyo-prelude/kyo-core
  are mid-migration red and kyo-compiler sits above them. The harness process
  is independent of the measured classpath, which points into this worktree's
  kernel class directories.
- Config A/B: identical toolchain and corpus, classpath old kernel vs kernel2.
- Cache defeat: the presentation compiler caches typechecks by content, so
  each iteration appends a unique nonce comment to the text.
- Measured op: `compiler.compile(uri, corpus + nonce)` wall time, warmed,
  reported as a distribution. Not JMH (the pool is Async and Scope-managed); a
  simple timed loop with warmup discard and percentile output is adequate at
  the 10-500 ms scale.

## Deliverables

- `kyo-compile-bench` project + corpus + a results table: per corpus row, mean
  compile time per op for old vs new kernel, ratio, 3 forks, plus the phase
  trace attribution for the largest delta.
- The kyo-compiler interactive harness as a follow-up once measured against
  main, reporting p50/p99 typecheck latency per corpus row per kernel.

## Open choices

1. Corpus scale: aim for ~200-500 ms per batch op so JMH avgt converges
   quickly; tune file count once the first numbers land.
2. Whether to pin `scala3-compiler` to the build's 3.8.4 (yes: same version
   sbt uses, so numbers transfer).
3. Whether instrument 2 lands in-repo (a kyo-compiler example/bench) or stays
   a scratch harness until kyo-compiler's stack is green on kernel2.
