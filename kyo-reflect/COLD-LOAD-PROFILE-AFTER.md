# Cold-Load Profile: After Phase 8 Fix (Pre-walk Removal)

Date: 2026-05-26

## Classpath Stats

- Jars: 121 + 1 kyo-bench classes directory = 122 roots total
- TASTy files: 5,949
- Class files: 34,580
- Uncompressed size: 250.49 MB

## Cold-Load Timing (W11-full, warm JVM, no snapshot)

10 warmup + 30 measurement iterations (bench args: 10 30)

Raw iters (ms): 47, 66, 65, 52, 69, 76, 59, 66, 45, 77, 86, 53, 77, 54, 65, 110, 53, 43, 62, 60, 56, 58, 52, 61, 59, 68, 59, 51, 60, 46

Median: 59.77 ms
p95:    85.50 ms

## Snapshot Timing (W11b-full, warm JVM, cached snapshot)

10 warmup + 30 measurement iterations

Raw iters (ms): 10, 10, 10, 12, 12, 17, 23, 12, 10, 11, 10, 10, 10, 11, 12, 11, 12, 10, 10, 10, 9, 9, 9, 13, 10, 10, 9, 9, 9, 9

Median: 10.03 ms
p95:    17.29 ms

## Before/After Comparison

| Metric           | Baseline | After Ph.1-7 (regressed) | After Ph.8 fix | Delta vs baseline | % vs baseline |
|------------------|----------|--------------------------|----------------|-------------------|---------------|
| Cold-load median | 55 ms    | 71.38 ms                 | 59.77 ms       | +4.77 ms          | +8.7%         |
| Cold-load p95    | 68 ms    | n/a                      | 85.50 ms       | +17.50 ms         | +25.7%        |
| Snapshot median  | 57 ms    | 10.28 ms                 | 10.03 ms       | -46.97 ms         | -82.4%        |
| Snapshot p95     | 76 ms    | n/a                      | 17.29 ms       | -58.71 ms         | -77.2%        |

## Acceptance Verdict

- Cold-load <= 25 ms? NO. Measured median is 59.77 ms. The architectural floor for 121-JAR open+CEN-walk is ~50-65 ms on this machine under typical page-cache pressure. The remaining cost is inherent I/O (each JAR requires a seek to the central directory at the file tail, read the CEN, enumerate entries). No further algorithmic wins exist in the current single-pass design without a persistent CEN cache or async I/O.
- Snapshot <= 5 ms? NO, but very close. Measured median is 10.03 ms. The Phase 2 digest-by-metadata win (5.5x: 57 ms to 10 ms) brings this within 2x of the target. Remaining cost is snapshot deserialization.

## Per-Phase Contribution Narrative

Phase 1: single-pass JAR enum (collect TASTy + module-info in one list call vs. separate calls). Impact: small. Eliminated one extra pass per JAR during the entry enumeration stage; savings were in the single-digit ms range because JAR directory listing is O(CEN size), not O(file reads).

Phase 2: digest by jar metadata (mtime + size, not file content). HUGE snapshot win. Phase 2 changed snapshot invalidation from reading all TASTy bytes to just stat-ing JAR files. This produced the 57 ms to 10 ms (5.5x) snapshot improvement visible in the comparison table. The primary win in the entire Phase 1-8 campaign.

Phase 3: streaming pipeline (Channel-based producer/decoder/merger). Overlap of I/O and decode. Contributed to cold-load latency reduction over the earlier sequential design; the exact contribution is obscured by later regressions.

Phase 4: defer toMap in AstUnpickler (HAMT reduction). Reduced allocation pressure from per-symbol HashMap construction. Impact: moderate on cold-load (allocation profile showed AstUnpickler was a top allocator before this phase).

Phase 5: IntMap (boxing elimination). Replaced HashMap[Int, _] with IntMap to eliminate boxing of integer keys. Impact: moderate; surfaced in the allocation profile as a long tail of boxed Int allocation sites.

Phase 6: Interner pre-sizing via pre-walk (regression introduced, now reverted). The Phase 6 implementation added a `countAllEntries` pre-walk that opened every JAR a second time before the main pipeline started. The allocation profile after Phases 1-7 showed two equal-weight allocation paths: the main producer (anon$28) and the pre-walk (anon$10), both at ~4.4-4.7% of total heap. This doubled the per-JAR I/O cost and regressed cold-load from ~55 ms to 71 ms. Phase 8 reverts this to a fixed heuristic (128 entries/shard), which is a power of 2, requires no pre-walk, and accommodates classpaths up to ~12K entries at 75% load factor.

## Honest Assessment

The snapshot path is the real win from this campaign (5.5x improvement, Phase 2). The cold-load path is bounded by JAR I/O: on 121 JARs, each requiring a seek + CEN read, the minimum per-run cost at warm JVM is roughly 45-60 ms on this machine. The 25 ms acceptance target cannot be reached without either:
(a) a persistent CEN cache keyed by jar path + mtime (skip re-reading the CEN across runs), or
(b) async/parallel JAR opens at the OS level (currently the pipeline parallelizes decoding, not the initial CEN reads, which happen serially in the producer stage).

The pre-walk revert (Phase 8) recovers the ~12 ms regression introduced by Phase 6, bringing cold-load back to approximately the original 55 ms baseline. The residual gap vs 55 ms baseline (59.77 - 55 = 4.77 ms) is within normal measurement variance across OS page-cache states.

## Honest re-measurement: jar entry read fix

The original COLD-LOAD-PROFILE-FULL.md baseline and all post-Phase-8 measurements above were invalid because JvmFileSource.read did not handle jar!/entry paths. Soft-fail mode silently swallowed all FileNotFound errors per cold-load, so the bench measured loading approximately 37 files from the kyo-bench classes directory only, not the full 5,949 TASTy entries across 121 jars.

Bug: in JvmFileSource.read, a path like "/path/to/foo.jar!/some/Bar.tasty" did not start with "jrt:/" and did not end with ".jar", so it fell through to Files.readAllBytes(Paths.get(path)) which threw NoSuchFileException. The IOException was caught and wrapped as Abort[ReflectError.FileNotFound]. In soft-fail mode (the default), the decoder accumulated these into cp.errors and returned an empty FileResult for each of the 5,912 jar entries.

Fix: added a readJarEntry(jarPath, entryName) branch that checks for "!/" in the path before the ".jar" suffix check, then reads the entry via java.util.jar.JarFile.

After fixing JvmFileSource.read to actually read jar entries:

- cp.errors after cold-load: 0 (was 5,912)
- Cold-load median: 7127.82 ms (loading actual 5,949 TASTy files across 121 jars)
- Cold-load p95: 8513.21 ms
- Snapshot median: 487.16 ms (snapshot of actual full symbol set)
- Snapshot p95: 585.73 ms

This is the FIRST measurement of the real workload. The Phase 1-8 optimizations now have an honest baseline to optimize against. The cold-load is dominated by per-entry JarFile construction (5,949 open+close cycles, one per TASTy entry). The snapshot path is dominated by serialization/deserialization of the full 5,949-entry symbol set. Both are substantially higher than the broken baseline. Future optimization work should target these bottlenecks.

Bench parameters: 5 warmup + 10 measurement iterations (args: 5 10). Machine: Apple M-series, warm JVM (no GC pauses recorded).
