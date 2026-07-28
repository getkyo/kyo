# Analysis: Native x64 CI job kernel OOMs (July 1-3)

## Symptom

Main runs `78c079f1` (Jul 1) through `64db0fdb` (Jul 3) had the linux-x64 Native job killed by
the kernel OOM killer: `Killed process (java) total-vm ~18GB, anon-rss ~11GB`, runner shutdown,
job cancelled. From `b5d0e265` (Jul 9) on, the job passes the link phase (its failures are
kyo-browser tests, a separate issue).

## Where the OOM happens

From the `64db0fdb` job log (ci-mon 20s sampling): the aggregate sbt driver had already linked
~28 native test binaries, then reached the kyo-schema test binary link (42218 classes, 213016
methods; optimize 78s in the driver heap, codegen 78s). When the driver forked the LLVM
toolchain to compile the 149 generated IR files, available memory collapsed
(swapFree 1985MB to 0, availMB 2187 to 417, psiMem10 spiking to 33) and the kernel killed the
java driver at 11GB anon RSS.

## Root cause chain

1. **The sbt driver ceiling is 12G everywhere, regardless of what CI sets.** `.jvmopts`
   (committed Jun 23 in `f6d0418e2`, JDK 25 migration) contains `-Xmx12G`. The sbt runner script
   appends `.jvmopts` contents AFTER `$JAVA_OPTS` on the java command line, and java takes the
   last `-Xmx`. Verified two ways:
   - `sbt -v` locally with `JAVA_OPTS=-Xmx3G`: command line is `java -Xmx3G -Xmx12G ... -Xmx12G`.
   - CI job logs print sbt's GC warning with the effective ceiling: the Jul 14 Native job
     (build.yml intent: 8G) shows `max 12.00GB`; the Jul 14 JS job (ci-test.sh intent: 14G)
     shows `max 12.00GB`.

   Double-checked against the real CI launcher: the setup action installs sbt through
   `coursier/setup-action`, and coursier's `sbt` app descriptor explicitly declares
   `"jvmOptionFile": ".jvmopts"` over the official sbt distribution, so the behavior is by
   design, not an accident of the local script. The CI step log also prints the env that went
   in (`JAVA_OPTS: -Xmx8G ...` on the Native job), eliminating the alternative explanation
   that the workflow expression produced the wrong value.

   Consequences:
   - The Native 8G cap added Jul 9 (`baa4c1eb`) as the OOM fix is dead config; it never reached
     the JVM.
   - ci-test.sh's JS 14G bump is equally dead; JS has run at 12G the whole time (and has been
     mostly green there, so the bump may be unnecessary; its motivating OOM claim never ran at
     14G). ci-test.sh's self-tests assert the env manipulation, not the effective JVM flag,
     which is exactly the blind spot.

2. **The job has been at the overcommit edge since at least July 1, with the binary still
   growing.** Double-checked against run data: the July 1 green run already linked 42179
   classes, the July 3 OOM run 42218 (essentially identical), and the July 14 run 44576
   (+5.7% in two weeks; Ion/BSON landed then). So no growth cliff separates green from OOM;
   at ~42k classes the accumulated aggregate driver (~11GB RSS at the effective 12G ceiling)
   plus the forked clang/ld phase sits within noise of the 16GB runner's capacity, and
   individual runs flip between passing and being killed. The first-failing commit
   (`78c079f1`, publishing footprint reduction) contains nothing memory-increasing; it was
   the unlucky coin flip, not the cause.

3. **What actually stopped the OOMs**: `c77de2e6` (Jul 14, PR #1742) added NATIVE_HEAVY
   sharding: ci-test.sh pre-links kyo-schema's binary in a fresh sbt driver before the
   aggregate link. In the fresh driver the heap stays ~2.6GB committed, so the link survives.
   The 8G cap contributed nothing (it never took effect).

## Residual risk (why this is not done)

- In the Jul 14 run, availMB bottomed at **311MB** during the sharded pre-link's clang/ld
  phase. One more growth step in kyo-schema (Ion/BSON landed in the same commit) or any noise
  re-breaks it.
- The aggregate driver still runs at an effective 12G ceiling. The accumulation mechanism that
  killed kyo-schema's link will recur for the next module that gets heavy, and the intended
  guard (8G cap) is not operative.
- The Jul 14 JS job's runner shutdown (TimeoutSuite then "runner received a shutdown signal")
  is unproven but fits the same family: an 11.17GB-committed 12G driver heap plus forked node
  processes on the same 16GB box. No kernel OOM line appears in that log, so this stays a
  watch item.

## Proposed fix (pending approval; revised after user direction)

1. **Primary: cap the Native link parallelism with `-XX:ActiveProcessorCount=2`, scoped to
   the link invocations only.** build.yml sets `NATIVE_LINK_CPUS=2` for the Native target
   (same pattern as `NATIVE_HEAVY`); ci-test.sh appends the flag to the pre-link and
   aggregate `nativeLink` sbt invocations only, leaving the `testKyo` invocation (test
   compilation and the test run) at full parallelism. Mechanism double-checked in
   scala-native 0.5.12 source: the sbt plugin calls `Build.buildCachedAwait`, whose `await`
   creates `Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), ...)`
   (Build.scala:337), and a pool audit of the toolchain (ScalaNative.scala, LLVM.scala,
   Interflow) finds no other executor: optimizer, codegen, and forked clang all run on that
   one pool. Scala-concurrency system properties would NOT work (dedicated pool, not the
   global EC). Env delivery is guaranteed by the sbt runner script itself (line 798:
   `.jvmopts` is appended to `JAVA_OPTS`, so non-conflicting env flags always reach the JVM;
   only duplicates like `-Xmx` resolve in `.jvmopts`' favor). This attacks the actual
   overcommit (concurrent clang forks stacked on the driver heap) and is robust to further
   binary growth. Estimated cost: link phases roughly double their CPU-bound parts; against
   the 1h56m job, +15-30 min worst case, test phase untouched. Note the cap saves roughly
   1GB at the pinch (two fewer clang workers), so it needs the swap backstop below for the
   aggregate-accumulation case; `NATIVE_HEAVY` remains the lever if a second module (the
   next-biggest link is already 33k classes) goes heavy.
2. **Simplify the memory configs by deletion, not delivery machinery.** Reality has been
   "effective 12G everywhere" and JVM/JS/Wasm are green there, so remove the config that
   lies: drop build.yml's dead Native-8G conditional and ci-test.sh's dead JS-14G
   substitution (plus its two self-test cases). `.jvmopts` remains the single source of
   truth it already is. The 8G intent becomes unnecessary once parallelism is capped and the
   sharded pre-link stays; note an effective 8G cap would anyway have risked flipping the
   kernel OOM into a driver-side OutOfMemoryError (schema optimize peaked ~7.7G RSS alone).
3. **Add a per-process memory layer to ci-monitor.sh** (user-raised). Today each tick reports
   only host-level data (MemAvailable, swap, disk, load, PSI, steal) plus the kyo scheduler
   snapshot; nothing attributes memory to processes. This investigation had to infer "the
   11GB was the sbt driver" and "clang forks caused the collapse" from a kernel kill line and
   arithmetic. Proposed: aggregate RSS by command name each tick and append the top few, e.g.
   `top=[java:11216M/2 clang:1834M/4 node:912M/1]` (total MB and process count per command).
   One `ps` pass into awk, no sudo, cross-platform best-effort, keeps the single greppable
   line; also validates fix 1 on real runs (clang count visibly capped at 2). Deeper JVM
   introspection (jcmd heap_info per java PID) stays a future option.
4. **Recommended backstop: swap headroom on the Linux runners** (supplementary swapfile;
   disk has 70-86GB free). The parallelism cap saves about 1GB at the pinch, but the
   aggregate driver can still accumulate toward ~11GB RSS at its 12G ceiling before linking
   the second-tier modules, and the OOM run exhausted the stock ~2GB swap entirely. Extra
   swap converts any residual overcommit into slowdown instead of SIGKILL, and also covers
   the JS job's 12G-heap-plus-node profile (the unexplained Jul 14 JS runner shutdown fits
   that family).

## Validation result (dispatched run 29612652212 on branch native-ci-oom, commit 27a0c19d2)

Full Native runs on both arches, mode=full: **success end to end**.

- x64 (the OOM pole): zero kernel OOM detections; supplementary swap active (11.2GB total,
  ~2.2GB used at peak); availMB floor 1075 (versus 311-417 in the OOM era); cores=4 confirmed;
  clang processes capped (max 3 observed, consistent with 2 pool slots given clang's driver+cc1
  pairs, versus up to 8 uncapped). Job duration 1h09m versus the 1h56m baseline: the feared
  wall-clock cost did not materialize.
- arm64: success, zero OOM detections, supplementary swap active, availMB floor 1150.
- New data from the monitoring layer: single cc1 processes reach 4.4GB RSS on kyo-schema's
  largest IR files, far above prior estimates, which is why the parallelism cap is the decisive
  lever. The pinch line reads `java:11123M/1 clang:3677M/1` at availMB=1075: the exact
  attribution that previously required kernel-log forensics.
- Unexpected bonus, held loosely (one run): the kyo-browser Native settlement tests passed on
  x64 after failing the last three main runs, hinting they were memory-pressure collateral.

## Verification plan for the fix

- ci-test.sh self-test updated and green.
- A workflow_dispatch CI run (mode=full, targets=Native, arches=x64) confirming: the ci-mon
  per-process line shows clang capped at 2 concurrent processes, availMB minimum comfortably
  above 1GB, job green end to end.
- The monitor's new layer visible on all targets' lines (java/node/clang attribution).
