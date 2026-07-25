# Overnight autonomy: green and stable main

Standing orders from the user (2026-07-24, before bed):
1. Work fully autonomously to ensure a green and stable main.
2. Do NOT push to main overnight. Validation goes through branches + dispatched CI.
3. Do NOT ignore any failure or flakiness. No reward hacking: a rerun is only for
   gathering data when a job left no evidence (runner loss); any failure with evidence
   gets diagnosed to root cause, and repeated infra deaths get investigated, not rerun.
4. A wakeup fires every 50 minutes to recover from temporary quota exhaustion; on each
   wakeup, read this file, check watcher/run state, and continue.

## Fixed state (do not re-derive)

- main = fea61a099 ([ci] pin checkout sha, ...) on top of 945739789 ([build] fix
  kyo-test-snapshot schema deps, ...). Both authored tonight; full context in
  ci-failure-analysis-main.md and ci-green-plan.md (same directory).
- Branch `ci-green-validation` = fea61a099 = main tip: the soak vehicle. Dispatch:
  `gh workflow run ci.yml --ref ci-green-validation -f mode=full -f targets='JVM JS Native Wasm' -f arches='x64 arm64'`
- Triage tool: `REPO=getkyo/kyo bash scripts/ci-logs.sh run <id>` (or `job <id> --grep/--tail`).
- On fea61a099 push runs: release/readme/scalafmt GREEN; ci 7/8 green.
- Open watch items:
  - x64 Native job of run 30059818927 died by runner loss (no failed step, no log).
    ONE rerun in flight (allowed: dead job left zero evidence). If the rerun dies the
    same way: investigate the Native job memory profile (ci-mon telemetry, compare
    green Native runs), prepare a mitigation on a branch, dispatch CI to validate. No
    third rerun.
  - Issue 2: kyo-net fd leak (ESTABLISHED pair, stale PollerIoDriver pendingReads),
    seen once on run 30028349055. If any soak run reproduces it: capture diagnostics,
    then dispatch an attribution run:
    `gh workflow run ci.yml --ref ci-green-validation -f mode=custom -f command='KYO_TEST_LEAK_DEBUG=1 ./scripts/ci-test.sh JVM test'`
    (verify the custom-mode env var passthrough in ci.yml before relying on it).
- PR follow-ups reserved for the user in the morning: rebase/rerun #1773, close #1774
  as superseded (its residual delta is only the harmful dependsOn removal), #1760 and
  #1761 rerun at leisure.

## Overnight protocol

1. Wait for the in-flight rerun (watcher b9a6pnhf0). Triage on completion; log below.
2. Then run serial full-CI soak runs on ci-green-validation (target: 4, one at a
   time). After each: triage every job with ci-logs.sh, log the result below, then
   dispatch the next. ANY red: diagnose to root cause before continuing; a fix, if
   needed, goes on a fresh branch with its own dispatched CI, never to main.
3. Keep this file current after every event (it is the recovery state).
4. Morning deliverable: results table below complete, plus recommended actions.

## Event log (append-only)

- 2026-07-24 ~03:15Z: push-run ledger: release/readme/scalafmt green; ci 7/8, x64
  Native runner loss; rerun dispatched ~04:00Z.
- 2026-07-24 05:57Z: x64 Native rerun SUCCESS (1h57m). Run 30059818927 now fully
  green: MAIN IS GREEN on all four workflows at fea61a099. Runner-loss watch item
  stays open (one loss, one clean sample).
- 2026-07-24 05:58Z: soak run 1 dispatched (30070911978), watcher bxmusczn6.

## Soak results

| # | Run id | Dispatched | Result | Notes |
|---|--------|-----------|--------|-------|
| rerun | 30059818927 (x64 Native only) | ~04:00Z | SUCCESS | runner-loss recovery sample, 1h57m |
| 1 | 30070911978 | 05:58Z | SUCCESS (all 8) | 2nd clean full pass; 2nd clean x64 Native |
| 2 | 30078422707 | 08:17Z | FAILURE: arm64 Native | kyo-configNative SIGSEGV, see issue 7 |
| 3 | 30086637263 | 10:35Z | in progress | watcher bvl4wguma |

## Issue 7 ROOT-CAUSED and FIXED (branch pending CI): Scala Native String.hashCode
## faults on zero-hash interned literals

- Reproduced deterministically on this arm64 mac (6/6 crashes, signal 10/SIGBUS).
- lldb backtrace: crash is a WRITE in java.lang.String.hashCode (str w8, [x9, 0x20]),
  called from XXHash.hash32(String) <- Rollout.bucketFor <- RolloutTest's key loop.
- Mechanism, fully proven:
  1. Scala Native's String.hashCode (javalib String.scala): when cachedHashCode reads
     0 and count > 0, it computes and stores `cachedHashCode = hash` unconditionally,
     even when the computed hash is 0.
  2. "\u0000" (RolloutTest.scala:701) is non-empty with JLS hash 0, so every
     hashCode call re-stores 0.
  3. lldb `memory region` on the object: [0x1014b0000-0x10166c000) r-- __DATA_CONST.
     The literal is interned in the binary's READ-ONLY data segment; object dump
     shows count=1, offset=0, store target +0x20 = cachedHashCode. Write to r-- page
     = SIGBUS (macOS) / SIGSEGV (Linux, section-placement dependent, hence the CI
     flakiness across runs and the x64/arm64 asymmetry).
  4. CI's second errored suite (DynamicFlagValidationTest) is collateral: suites
     in flight when the process dies report as errors.
- This is an upstream Scala Native bug (any String.hashCode call on a zero-hash
  read-only literal faults). Upstream issue draft: see below. Filing is the user's
  call (outward-facing).
- kyo-side fix (correct independent of upstream): XXHash.hash32(String) now uses
  XXHashPlatform.stringHash: JVM and js-wasm delegate to the memoized String.hashCode
  (safe there, keeps the constant-time reuse property the scaladoc pins); native
  computes the JLS hash with a store-free loop. Values identical on all platforms.
  Regression leaf added to XXHashTest asserting hash32("\u0000") == hashInt(0)
  without ever calling .hashCode on the literal; RolloutTest:701 remains the
  end-to-end guard (it was the crashing test).
- Validation: kyo-configJVM/test 294/294 green (pinned hash constants unchanged).
  Native 3x loop in flight (was 6/6 crash before the fix).

### Upstream issue draft (scala-native)

Title: String.hashCode faults on interned literals whose JLS hash is 0

String.hashCode caches with `if (currentHashCode == 0) { ...; cachedHashCode = hash }`
(javalib String.scala, hashCode). For a non-empty string whose JLS hash is 0, e.g.
"\u0000", the store executes on every call because the cached field never becomes
nonzero. String literals are interned in a read-only section (__DATA_CONST on Mach-O,
observed r-- via lldb), so the store faults: SIGBUS on macOS arm64, SIGSEGV observed
on Linux arm64. Minimal repro: `"\u0000".hashCode` in any native binary where the
literal is placed read-only. Suggested fix: skip the store when the computed hash is
0 (`if (hash != 0) cachedHashCode = hash`), matching the JDK's hashIsZero-free
historical behavior of recomputing for zero-hash strings, or have
StringLowering::stringHashCode precompute a sentinel-free encoding.

## Issue 8 ROOT-CAUSED and FIXED (branch pending CI): scalafmt format race in CI

- The `[error] scalafmt: failed for 1 sources` line appears in EVERY CI Native job,
  including fully green ones (verified: soak 1 arm64+x64 Native green, soak 2 x64
  Native green), never in JVM jobs, and never locally (scalafmtCheckAll over all
  projects: clean).
- Root cause: the CI aggregator compiles each cross-built module for two Scala
  versions in one sbt session; both compilations run scalafmtOnCompile over the same
  shared source files concurrently and the losing formatter logs the failure. Benign
  (the winner formats the file, the build continues) but it reads like a real error
  and could mask one.
- Fix: `scalafmtOnCompile := !insideCI.value` (branch ci-scalafmt-race, commit
  7f97de36c). CI formatting enforcement lives in the scalafmt workflow (scalafmtAll
  plus dirty-tree check), unaffected. Verified locally: true without CI env, false
  with CI=true.

## Branch and validation state (for morning push decision)

- Branch kyo-config-native-zero-hash (7cc5792f6): issue-7 fix. Local: JVM 294/294,
  Native 3x 294/294 (was 6/6 crash). CI dispatched: run 30087568369 (watcher
  bnfsq7e1i).
- Branch ci-scalafmt-race (7f97de36c): issue-8 fix. Validated locally via setting
  inspection both ways.
- Branch ci-green-combined (c491bfcaa = both stacked on main): the exact tree a
  morning push to main would produce. CI dispatched: run 30087825244 (watcher
  running). This is the load-bearing validation for the morning push.
- Morning recommendation: after both runs are green, push ci-green-combined's two
  commits to main (fast-forward), then the PR follow-ups (#1773 rebase, #1774 close
  as superseded, file the scala-native upstream issue from the draft above).

## Morning outcome (user-directed)

- User cancelled the three in-flight validation dispatches and directed the push:
  main is now c491bfcaa (7cc5792f6 zero-hash fix + c491bfcaa scalafmt gate),
  fast-forwarded from fea61a099 at ~14:4xZ.
- Push-triggered runs on c491bfcaa: ci 30089712337, release 30089712209, readme
  30089712221, scalafmt 30089712223; watcher bb7sw4l3t on all four.
- Remaining user actions: #1773 rebase/rerun, #1774 close as superseded, file the
  scala-native issue (draft above), #1760/#1761 at leisure.
- Still-open watch items: issue 2 (kyo-net fd leak, 4 clean samples tonight) and the
  one-off x64 Native runner loss (3 clean samples since).

## PR #1773 stabilization (user-directed, daytime)

- The PR (rebased on stabilized main, head dd2b48454) failed both JVM CI jobs with a
  1-fd CLOSE_WAIT leak plus a named failing suite.
- Defect A FIXED and pushed to the PR branch (bee6c83fa): PosixHandleInvariantsTest's
  typecheck snippet lacked ReadPump's new clock parameter; deterministic failure.
  Validated locally, suite green.
- Defect B ROOT-CAUSED and FIXED: the attribution run (30094149410) tagged the leak:
  `opened by test: a read delivers the grace probe's staged plain bytes before fresh
  socket bytes` (NioIoDriverTest). That leaf closed the handle and driver but never
  closed sv, its held peer socket; closeHandle's FIN left sv in CLOSE_WAIT for the
  life of the fork. macOS silence explained: the fd-state enumeration is /proc-based,
  so the leak existed everywhere but was only detected on Linux. Fix: close sv first,
  matching every sibling leaf (commit 92e257971). Validated: NioIoDriverTest 41/41,
  full kyo-netJVM/test success locally.
- DELIVERABLE: isolated branch `kyo-net-pr1773-green` = PR head + defect-A fix
  (bee6c83fa, also on the PR branch) + defect-B fix (92e257971). Two validation
  dispatches in flight, watcher bc4pigqbo:
  - full CI both arches all targets: run 30099782049
  - serial leak-regression (same command that failed pre-fix): run 30099796037
  Green on both = the PR content is green and stable; the user can merge the two
  commits into the PR branch (defect-A fix is already there; only 92e257971 is new).
- OUTCOME (user-directed): validation dispatches cancelled mid-flight; both fixes
  pushed to the PR branch itself (head now 92e257971). The PR's own checks on the
  fixed head are the validation: ci 30104433860, scalafmt 30104433387, readme
  30104433349 (watcher b1mptiezo). The issue-9 probe rerun on main was cancelled
  with the rest (sample #2 lost); issue 9 stays open, next sample comes from any
  future arm64 full run. Main state unchanged: c491bfcaa, release/readme/scalafmt
  green, ci cancelled-after-arm64-JVM-red (issue 9).
- PR checks on 92e257971 so far: scalafmt GREEN, readme GREEN; ci: both JVM jobs
  GREEN (leak fix confirmed in CI: the end-of-run leak check passed where it
  previously killed the fork), JS x2 + Wasm x2 GREEN; arm64 Native pending; x64
  Native RUNNER LOSS (see issue 10 below). Rerun of the lost job queued once the
  run completes.

## Defect C (NEW, PR regression, OPEN): kyo-httpNative SIGSEGV on arm64 Linux

- PR ci run 30104433860 arm64 Native: the kyo-http Native test binary SIGSEGVs
  (si_addr=0x10 and (nil): null-plus-offset deref) in all 3 in-job attempts, every
  time at the SAME location: immediately after "concurrent requests > parallel
  requests to same endpoint > tls" PASSES. The next thing to run is that suite's
  server/listener teardown with freshly closed TLS connections: the PR's
  listener-close discharge + peer-close probe surface on the epoll backend.
- Differential: main's arm64 Native passed kyo-httpNative 3x (push run + 2 soaks);
  the PR crashes 3/3 attempts. Probable REAL regression from the PR's kyo-net
  transport changes (kyo_uring.c, PollerIoDriver, PosixTransport all modified).
- x64 Native never reached these tests (disk runner-loss mid-link), so x64
  exposure is unknown.
- REPRODUCED LOCALLY (darwin arm64 kqueue, deterministic, HttpServerTest) and
  ROOT-CAUSED under lldb: EXC_BAD_ACCESS at address 0x0 inside libunwind during
  Throwable.fillInStackTrace, triggered by `new java.io.IOException("connection
  closed")` in Http1ClientConnection.close (a completion sentinel), invoked from a
  Scope finalizer whose carrier stack crosses the poller's C frames; the Scala
  Native unwinder cannot step them on arm64 and derefs null. Main does not crash
  because the PR moved this close onto the driver-adjacent carrier path.
- FIX round 1 (close() only) was INSUFFICIENT: 1-2 crashes in 3 post-fix runs. The
  file had a SECOND identical construction at the parser's onClosed callback (line
  48), which fires on the driver-side close delivery path and was likely the true
  deterministic site. Audit of all promise-completion constructions
  (Result.panic(new / Result.fail(new) across kyo-net drivers + kyo-http internals
  found exactly one more: NioIoDriver awaitConnect's duplicate-await panic.
- FIX round 2 (NOT yet pushed): one preallocated NoStackTrace sentinel
  (Http1ClientConnection.connectionClosedPanic) used by both kyo-http sites, and
  NoStackTrace on the NioIoDriver invariant panic (its trace records driver
  internals, not the misusing caller). Result: 8/10 (still 1 SIGSEGV, plus a NEW
  SIGABRT). NoStackTrace is correct house style but symptom relief only.
- UNIFIED ROOT CAUSE (from the SIGABRT log): `libunwind: stepWithCompactEncoding -
  invalid compact unwind encoding` then fatal signal 6, fired while LOGGING a test
  panic. Both death modes are libunwind failing on a stack frame with unwind info
  it cannot parse; prime suspect: BoringSSL's hand-written assembly frames on-stack
  during TLS work whenever any exception's trace is captured or logged. The PR
  triggers it by capturing/logging exceptions on TLS carriers far more often
  (teardown, probes, reclaim). NOT kyo memory corruption.
- HYPOTHESIS FALSIFIED: no-asm BoringSSL still fails 2/10 (one signal-11, and one
  NEW signature: HttpWebSocketTest "server cleanup runs on handler error" hung to
  its 2m leaf timeout). Statistically identical to with-asm (2/10). BoringSSL asm
  is exonerated as the sole unparseable-frame source; the pinned asm build was
  restored locally. Remaining frame candidates: Scala Native's own generated
  compact-unwind encodings, its runtime trampoline asm, C shim frames: an upstream
  scala-native investigation, not a kyo-side lever.
- CURRENT DEFECT-C STATE, honest: OPEN, toolchain-interop, PR-amplified. The
  NoStackTrace sentinel fixes (commit 5e972b7f7, pushed to the PR branch and
  kyo-net-pr1773-green) are validated house-style improvements that cut crash
  frequency (removed the deterministic sites) but do NOT close the class: any
  trace capture or exception logging on the affected carriers can still crash on
  arm64 Native, and there is now also at least one HANG signature in websocket
  server cleanup (1 in 20 local runs) that needs its own investigation.
- PR #1773 STATUS: defects A and B fixed and CI-confirmed; defect C open at the
  toolchain boundary plus the new hang. The PR is NOT stable on arm64 Native yet.
  Decision points for the user: (1) pursue the scala-native upstream unwinder
  issue (draft evidence: lldb traces, the compact-encoding abort line, the
  falsified asm experiment); (2) whether the PR should trampoline closes and
  exception logging off driver carriers (kills the amplification structurally);
  (3) the HttpWebSocketTest cleanup hang investigation.
- SYSTEMIC follow-ups for the user:
  1. Upstream Scala Native issue candidate #2: fillInStackTrace segfaults when
     unwinding crosses extern C frames on arm64 (libunwind get32 null deref).
  2. PR design question: connection finalizers/closeFn now run on driver carriers;
     ANY stack-trace-carrying Throwable constructed there risks the same crash.
     Consider trampolining user-visible closes off the poller carrier, or auditing
     exception construction on those paths.

## Issue 10 RESOLVED (measured, then fixed at the source): Native disk appetite

Attribution from the CI probe (run 30113323712) plus local composition analysis:
- Runner started 77GB free, floor 35GB, ended 52GB: peak draw-down 42GB (a full row
  measured 41GB earlier, consistent). That runner was never at risk; the two lost
  runners must have started near or below 42GB (image variance).
- */native/target = 21.3GB of it. Within one module (kyo-core, 665MB workspace):
  generated/ = 526MB (431MB .ll IR + 96MB .o objects) vs an 84MB binary, so 79% is
  intermediates that die the moment the binary links. Across ~40 modules: ~13GB.
- Second finding: each test binary exists TWICE with different inodes (real copies,
  not hardlinks): scala-3.8.4/<module>-test and native-test/TestMain-test, 84MB each
  for kyo-core, so ~3.3GB of duplication per row. Not addressed (scala-native's own
  packaging); noted for upstream.
- Caches: kyo-browser 1.9GB, coursier 1.3GB, /tmp 785MB, .sbt 141MB, BoringSSL 73MB.

FIX SHIPPED to main (ee8259dd4): `Test / nativeLink ~=` in native-settings deletes
each module's generated/ as soon as its binary links, when CI is set. Deterministic
(runs inside the link task, in order), no background process, no mtime heuristic:
the first implementation was a concurrent sweeper and was replaced after review.
kyoNative (aggregate, no native sources, so no Test / nativeLink to transform) takes
a new native-settings-base half. Validated locally: plain link keeps generated
(67MB, kyo-config), CI=1 link leaves it absent with the binary present, CI=1 test
over the pruned tree reuses the cached binary (no link phase, 294/294 green).
Expected effect: row peak ~42GB -> ~29GB.

MEASURED ON A REAL ROW (main run 30124011127, full mode, ee8259dd4): all four
workflows GREEN, and the disk draw-down per Native row fell from 41GB to 17GB (58%
less), better than the ~13GB predicted. x64: start 78.0GB free, floor 60.7GB (was
36.3GB). arm64: floor 83.4GB, same 17GB draw-down. No flight-recorder WARN or CRIT
in either job. A runner now needs ~18GB free to survive a Native row instead of
~42GB, which is below what either lost runner had: the failure mode is closed, not
merely rarer.

NOT NEEDED, not shipped: scripts/ci-reclaim-disk.sh (delete unused preinstalled
toolchains) on branch ci-native-disk-preflight. The measurement above makes the blunt
deletion unnecessary; recommend deleting that branch.

## Issue 11 (NEW, pre-existing on MAIN, serious): kyo-coreNative crashes 3/5 locally

Control run on main's tree, darwin-arm64: `sbt kyo-coreNative/test` 5 times ->
2 passed, 3 failed, every failure `ScalaNative: Unhandled signal 11`. Same libunwind
class as defect C but in kyo-core, on main, with no kyo-net PR involved. So defect C
is NOT a PR regression class: it is a codebase-wide Scala Native arm64 fragility that
the PR's carrier changes make more frequent in kyo-http. Linux arm64 CI passes
kyo-core, so the rate is platform-dependent (darwin-arm64 worst).
Consequence for planning: local Native runs on arm64 macs are unreliable as a gate
today, and the upstream scala-native issue is the load-bearing fix for BOTH.

## Issue 10 original analysis (memory margins, superseded by the disk attribution)

- The lost PR job's live-stream ci-mon tail (user paste; API serves no blob) shows
  memory healthy (availMB 4.5-7GB, psiMem 0.00) while diskFreeMB collapsed
  857 -> 420 -> 221 in the final ~2.5 min, death seconds later, mid kyo-net/kyo-http
  native test links.
- Baseline from the green x64 Native run: disk 77GB at start, 36GB at trough: the
  Native row consumes ~41GB (debug LLVM IR + objects + per-module test binaries).
  Runner images vary in starting free disk; a runner starting under ~42GB dies at
  the ~80-90 min mark (consumption rate, which also explains the consistent death
  timing earlier misread as a phase correlation).
- The memory-margin analysis below stands as a SECONDARY watch item (floors 948MB
  x64 / 450MB arm64 on green runs) with no observed death attributed to it.
- Fix options: (1) disk preflight in setup for Native rows (drop unused toolchains,
  +20-30GB deterministic); (2) prune per-module native intermediates after each
  module's tests; (3) ci-mon fail-fast under 2GB free (turns logless runner death
  into an attributable failure). Recommended: 1 + 3 now, 2 as follow-up.

## Issue 10 original analysis (memory margins, now secondary): Native jobs graze kernel OOM

- Two x64 Native runner losses now (main push run overnight; PR run 30104433860),
  both the same signature: main step frozen in_progress, no failed step, no log
  blob, death ~85-87 min in.
- ci-mon telemetry from GREEN runs quantifies the margin: x64 Native memory floor
  availMB=948; arm64 Native floor availMB=450 (16GB runners, ~11GB swap present but
  psiMem pressure spikes recorded). The Native rows structurally run within ~0.5-1GB
  of the cliff; an unlucky overlap of nativeLink and test forks goes through zero
  and the kernel OOM-kills the runner daemon: logless runner loss.
- build.yml already documents this class (NATIVE_LINK_CPUS=2 mitigation for link
  parallelism). Mitigation candidates for a user decision: reduce Native-row test
  fork concurrency, serialize nativeLink vs test execution, or move Native rows to
  larger runners. Until then, expect roughly 1-in-6 Native-job runner losses;
  policy stays: one data rerun per loss, always logged here.
- Main runs on c491bfcaa: release/readme/scalafmt GREEN; ci in progress (watcher
  bb7sw4l3t).

## Issue 9 (NEW, on main): PosixTransportShutdownReclaimTest hang, arm64 JVM, flaky

- Main ci run 30089712337, job 89470016101 (arm64 JVM): leaf "closing a listener
  reclaims the handshakes it accepted > a stalled accept handshake with no deadline
  is released when its listener closes" TIMED OUT at its 1m limit. First occurrence
  in 6 full runs of this tree family. Suite exists on main (predates #1773).
- kyo-test hang diagnostics captured in the log (saved at scratchpad
  main-arm64-jvm.log line 46770+): driver loops idle normally; the listener-close
  sweep never released the stalled handshake. A liveness race in the
  handshake-reclaim path; #1773 reworks this same area and may change or fix it.
- NOT rerun: the failure has evidence, so it stays red pending diagnosis (unlike the
  runner-loss case). Local 10x reproduction loop of the suite dispatched (task
  bflcymqj1) on the PR tree (which contains the same suite plus the PR's rework).
- User decision needed eventually: whether main's red ci run gets a rerun once the
  race is understood, or waits for the fix to land.
- Analysis so far (code reading, PR tree == main for this machinery):
  - Exonerated: listener.isClosed is AtomicBoolean (no stale-read escape);
    registerHandshake's insertion-after-sweep recheck is ordered correctly;
    accept-side Infinity-timeout disarm gate is a fresh one-shot CAS;
    submitEngineOp offers then triggerWake()s on both main and the PR tree.
  - Open lead: the accept-side registered teardown is ASYNC:
    `() => handle.driver.submitEngineOp(() => teardown())` (PosixTransport ~1191).
    The discharge only enqueues the engine free; the hang means the enqueued op
    never ran (CI dumps show poller parked in epoll). Candidate: a drain-ordering
    or wake-consumption bug where the poll loop consumes the wake without draining
    engineQueue in that cycle. Needs live reproduction to progress.
  - Local reproduction unlocked: BoringSSL staged for darwin-aarch64 (the leaf
    previously self-cancelled via assumeTlsReady). Results: 30/30 green on kqueue
    locally; 100/100 green on Linux x64 epoll via ci custom loop (run 30095559699).
    Zero reproductions in 130 targeted samples across three environments: the hang
    needs full-suite parallel load and likely arm64. Classification: rare
    load-dependent race, OPEN, with the async submitEngineOp drain lead recorded.
    Next vehicle if it recurs: arm64 full-suite runs (no arm64 custom-mode runner
    exists; the custom job is pinned ubuntu-latest, and its checkout still carries
    the github.ref fallback, same drift class fixed in build.yml: cleanup item).
- Main push runs on c491bfcaa CONCLUDED: release/readme/scalafmt green; ci red only
  on the arm64 JVM job (this issue). Issue-8 fix verified in CI: Native job logs
  carry zero scalafmt activity. The failed arm64 JVM job was rerun explicitly AS
  issue-9 sample #2 under full arm64 load (the only vehicle that has reproduced it),
  outcome to be recorded either way; watcher bl0x152oe.
