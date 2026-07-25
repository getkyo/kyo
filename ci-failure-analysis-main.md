# CI failures on main (as of 2026-07-23)

Four distinct issues. The last fully green `ci` run on main was f28d7beb3 (Jul 23, 09:59 UTC).

## 1. kyo-test-snapshot compile break: the failure holding main red (deterministic)

Every `ci` run since 1a82293cd fails all 8 build jobs (JVM/JS/Native/Wasm on x64 and arm64)
at the same point:

```
[E008] kyo-test/snapshot/shared/src/main/scala/kyo/test/snapshot/SnapshotCodec.scala:37-55
value Yaml is not a member of kyo        (also Json, Ion, Protobuf, Bson, MsgPack, IonBinary)
(kyo-test-snapshotJVM / Compile / compileIncremental) Compilation failed
```

Root cause: a cross-PR semantic conflict between two PRs that were each green on their
own branch.

- PR #1771 (1a82293cd, merged 17:39) added `SnapshotCodec` with seven presets that call
  `kyo.Yaml()`, `kyo.Json()`, `kyo.Ion()`, `kyo.Protobuf()`, `kyo.Bson()`, `kyo.MsgPack()`,
  `kyo.IonBinary()`. At merge time those all lived in `kyo-schema`, which
  `kyo-test-snapshot` depends on.
- PR #1769 (6cc697836, merged 19:28) split kyo-schema into a format-agnostic core plus
  per-format modules. The seven codecs now live in `kyo-schema-yaml`, `kyo-schema-json`,
  `kyo-schema-ion` (both `Ion` and `IonBinary`), `kyo-schema-protobuf`, `kyo-schema-bson`,
  `kyo-schema-msgpack`.
- `kyo-test-snapshot` in build.sbt still depends only on `kyo-schema` core, so the presets
  no longer resolve. The split branch predates #1771's merge, so its CI never compiled
  `SnapshotCodec`.

Status at HEAD (769b678ee): still broken; nothing after the split touches this. The
in-progress run will fail identically, and while the compile break stands no tests run at
all, so it also masks any other regressions.

Fix direction (user decision): either wire `kyo-test-snapshot` to the six per-format
modules (`.dependsOn(kyo-schema-json, -yaml, -ion, -protobuf, -bson, -msgpack)`; all of
them cross-build to all four platforms), or relocate the seven presets so the snapshot
module keeps a core-only dependency. The wiring change is the direct fix; it does make
every `kyo-test-snapshot` consumer pull all format modules, which partially undoes the
point of the split for that path.

### Why runs for commits older than #1771 show the same error

`.github/workflows/ci.yml:129` checks out `ref: ${{ inputs.ref || github.ref }}`. For a
push event `github.ref` is `refs/heads/main`, a moving branch ref, so a queued job
compiles whatever main's tip is when the job starts, not the SHA that triggered the run.
The 11b476a12 and afe6f91b0 runs' build jobs started after the split merged (log
timestamps ~21:00+) and compiled post-split main. Consequences worth fixing on their own:
failures get attributed to the wrong commit, and some merged SHAs are never actually
validated. Checking out `github.sha` would pin each run to its commit.

## 2. kyo-netJVM fork death: file-descriptor leak caught by the kyo-test leak check (one occurrence, currently masked)

Run 30028349055 (trigger ba9ac3ac6; job `build (linux-x64) / build (JVM)`, db=89278437497):
every kyo-net suite passed, then the end-of-run leak check failed the forked JVM:

```
kyo.test.runner.internal.LeakCheck$Detected: kyo-test leak check failed:
  - file-descriptor leak (2): socket:[254641] [ESTABLISHED local:40186 remote:56351];
                              socket:[254642] [ESTABLISHED local:56351 remote:40186]
```

One established loopback TCP pair outlived the run. Driver diagnostics at probe time show
the shared-transport `PollerIoDriver` with `activeFds=0` but 17 stale `pendingReads`.
`SbtRunner.done` threw, the fork exited, sbt's acceptor got `EOFException`, so the job
reports only `TestsFailedException` with no failing test name. The same job also had an
`IoUringDriver deferred close claim` leaf self-cancel citing "unrelated concurrent fd
churn", consistent with fd instability in that run.

The only commit between the last green run and this failure is #1770 (kyo-ai Observe),
which touches neither kyo-net nor kyo-test nor kyo-core, so this is a latent
nondeterministic teardown leak in kyo-net or its tests, not that commit's content.
Frequency unknown: every later run dies at the compile break before reaching kyo-net
tests. Once issue 1 is fixed, watch for recurrence; the leak check's own guidance
(`KYO_TEST_LEAK_DEBUG=1`, serial leaves, per-descriptor `opened by test:` attribution) is
the attribution tool.

## 3. release workflow: publish job cannot link liburing (persistent, predates issues 1 and 2)

Every push-triggered `release` run fails (verified back through f28d7beb3 on Jul 23 and
7e22af6fd on Jul 22, both while `ci` was green):

```
cc ... kyo_uring.c ... -Wl,-Bstatic -luring -Wl,-Bdynamic
/usr/bin/ld: cannot find -luring: No such file or directory
(kyo-netJVM / ffiCompile) [kyo-ffi-plugin] C compilation failed (exit=1)
```

Root cause: `release.yml`'s publish job installs libcurl/libidn2/libh2o but not
`liburing-dev`. Both `.github/actions/setup` (used by ci) and `readme.yml` install it,
and readme.yml's comment states the rule explicitly: any workflow that does not use
`.github/actions/setup` must install it itself. release.yml was missed when the kyo-net
io_uring shim gained the static liburing link. Fix: add the same
`sudo apt-get install -y -o Acquire::Retries=3 liburing-dev` step (or switch the job to
`.github/actions/setup`).

## 4. readme workflow on afe6f91b0: infra flake in the liburing install step (one-off)

The `readme` run for afe6f91b0 failed in "Install liburing (Linux)": `apt-get update`
hung for minutes on the unreachable Azure apt mirror (`azure.archive.ubuntu.com` all
`Ign:`), and when the `nick-fields/retry@v4` wrapper tried to kill the timed-out command
it crashed with `Error: kill EPERM` (Node cannot kill the root-owned `sudo apt-get`
child), turning a retriable timeout into a hard job failure. The next readme run
(6cc697836) got past the install and failed on `sbt doctest` with exactly the issue-1
compile error, so readme is not independently broken. The EPERM crash is a known sharp
edge of wrapping `sudo` in nick-fields/retry; running the apt commands without the retry
wrapper (apt already retries via `Acquire::Retries=3`) or without sudo-owned children
would remove it.

## Open-PR coverage (checked 2026-07-23)

Open PRs: #1774 (JS linker batch mode in CI), #1773 (kyo-net backpressure reclaim),
#1761 (kyo-aeron cross-platform), #1760 (dependabot checkout 7.0.0 -> 7.0.1).

- Issue 1 (SnapshotCodec compile break): NO open PR fixes it. #1774 touches build.sbt
  but only sets Scala.js `batchMode` inside CI; no dependency wiring for
  `kyo-test-snapshot`.
- Issue 2 (kyo-net fd leak / leak-check fork death): #1773 is the relevant candidate.
  Its stated problem is an fd-leak class in exactly this area (a backpressured
  `ReadPump` never observes a peer FIN, leaving a `CLOSE_WAIT` fd for the life of the
  process), and it carries leak-check-adjacent fixes: `NioTransportTest` closes owned
  transports at leaf-scope exit (that suite was failing the end-of-run leak check),
  and `NioIoDriver` registers a `Diagnostics` dump so leaked NIO connections are
  attributable. Caveat: the observed leak was an ESTABLISHED pair with stale
  `PollerIoDriver` pendingReads, not literally the CLOSE_WAIT scenario, so #1773
  addresses the failure class but is not confirmed to cover that exact leak.
- Issue 3 (release.yml missing liburing-dev): NO open PR fixes it. #1760 only bumps
  the checkout action version in all workflows.
- Issue 4 (readme apt-mirror + retry kill-EPERM flake): NO open PR fixes it. #1761
  edits readme.yml but only adds aeron native staging and keeps the retry wrapper.
  Note #1761 repeats the per-workflow native-dep staging in readme.yml without a
  matching release.yml step, the same omission pattern behind issue 3.
- Ref drift (ci.yml checking out `github.ref`): unchanged by any open PR; #1760 keeps
  `ref: ${{ inputs.ref || github.ref }}` as is.

### Issue 5, revealed by #1774's body: JS/Wasm sbt-heap OOM, currently masked

#1774 documents that on #1769's own CI (the schema split), both JS jobs (x64 and arm64)
and the arm64 Wasm job died with `java.lang.OutOfMemoryError: Java heap space` in the
sbt process during the test phase: the split links a test binary per schema module in
one 12G-heap sbt process and the Scala.js incremental linker state overflowed it. The
split merged before that fix landed, so main is exposed. It is currently invisible
because issue 1 kills every job at compile, before linking. Once issue 1 is fixed the
JS/Wasm jobs will likely start OOMing until the batch-mode change lands.

### Verification round 2 (2026-07-23, second pass)

- **#1774 as it stands would re-break kyo-test-snapshot.** Its diff is the two correct
  `scalaJSLinkerConfig` batch-mode hunks PLUS a removal of kyo-test-snapshot's
  `.dependsOn(kyo-schema)` and `.dependsOn(kyo-test-prop)` lines. Those lines were
  added by #1771 (verified via `git log -S`); the branch forked before #1771 and its
  "Merge branch 'main'" commit resolved the build.sbt conflict by dropping them, so
  merging #1774 would unresolve even `kyo.Codec` and the prop-based golden tests. Its
  own CI is still pending and will fail. The batch-mode hunks are needed; the
  dependsOn removal must not merge.
- **The release gap is worse than a red job: published kyo-net artifacts ship stub
  TLS.** `.github/actions/setup` stages BoringSSL (`build-boringssl.sh`) before
  building; without staging, build.sbt compiles the `c-boringssl-stub` shim
  (`probe_available -> 0`, BoringSslProvider unavailable) into the artifact.
  release.yml has never staged BoringSSL, so every published kyo-net JVM/Native
  artifact so far carries the stub. The release fix needs BOTH `liburing-dev` and the
  BoringSSL staging step, mirroring setup/action.yml.
- **The retry-kill-EPERM flake class also exists in ci**: every apt step in
  `.github/actions/setup/action.yml` is wrapped in `nick-fields/retry@v4` the same way
  readme.yml's liburing step is.
- **Issue-1 fix confirmed safe and precedented**: `kyo-schema-tests`
  (build.sbt:729-738) already wires core + all six format modules; every format module
  cross-builds JS/JVM/Native/Wasm; `kyo.Codec` stays in `kyo-schema` core;
  `kyo-test-runner` depends only on `kyo-test-api` + `kyo-scheduler`, and schema
  modules reach kyo-test only via `unmanagedClasspath` (no `dependsOn` cycle
  possible). kyo-test-snapshot's own test sources (`SnapshotGoldenTest`,
  `SnapshotSchemaTest`) also reference the format codecs, so the compile-scope deps
  cover Test as well. A tree-wide grep found no other references to the moved
  constructors outside the schema family.
- **Ref-drift fix located**: ci.yml passes `ref: ${{ inputs.ref }}` (empty on
  push/PR) to build.yml, whose checkout falls back to `github.ref`
  (build.yml:91-93). ci.yml's own concurrency comment says every main commit "is
  validated on its own", so the drift contradicts the workflow's stated design.
  Changing the fallback to `github.sha` pins push and PR runs alike (for
  `pull_request`, `github.sha` is the merge SHA, matching default checkout
  semantics); `workflow_dispatch` keeps `inputs.ref` precedence.
- **PR CI states at check time**: #1774 pending; #1773 4 pass / 9 pending, mergeable;
  #1760 is version bumps only; #1761 adds aeron staging to readme.yml but not
  release.yml (repeating the issue-3 omission pattern for aeron).

## Post-fix ledger (fea61a099, pushed 2026-07-24 01:45 UTC)

- release: success (first in days; liburing + BoringSSL staging validated).
- readme: success (doctest green, matching the local run).
- scalafmt: success.
- ci: 7/8 build jobs green (JVM x2, JS x2, Wasm x2 with no OOM, Native arm64).
- WATCH ITEM: ci x64 Native job 89379156612 died by runner loss (step frozen
  in_progress, no failed step, no log blob) after ~87 min. One rerun issued to get a
  second sample; no evidence existed to diagnose in the dead job. If the rerun dies the
  same way, treat as a load-induced runner kill (build.yml documents the Native
  overcommit risk, NATIVE_LINK_CPUS=2 is the existing mitigation) and investigate the
  Native job's memory profile via ci-mon telemetry instead of rerunning again. If it
  passes, keep this entry so repeated runner losses cannot hide behind one-off reruns.
- OPEN: issue 2 (kyo-net fd leak) did not fire on this run's JVM jobs; stays open until
  #1773 lands plus a clean window, or an attributed fix.

## Timeline summary

| Run (ci unless noted) | Trigger commit | Result | Cause |
|---|---|---|---|
| 29997574940 | f28d7beb3 | success | last green ci |
| release 29997574646 | f28d7beb3 | failure | issue 3 (liburing) |
| 30028349055 | ba9ac3ac6 (#1770) | failure | issue 2 (kyo-net fd leak) |
| 30028447950 | 11b476a12 (#1762) | failure | issue 1 via ref drift |
| 30030317659 | 1a82293cd (#1771) | failure | issue 1 via ref drift |
| readme 30031362459 | afe6f91b0 | failure | issue 4 (apt mirror + retry EPERM) |
| 30031363928 | afe6f91b0 (#1766) | failure | issue 1 via ref drift |
| 30038133647 | 6cc697836 (#1769) | failure | issue 1 (the split itself) |
| 30058082207 | 769b678ee (#1772) | in progress | will hit issue 1 |
