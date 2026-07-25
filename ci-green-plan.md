# Plan: get main back to green

Grounded in `ci-failure-analysis-main.md` (5 issues + verification round 2). Ordered by
technical dependency; each phase is validated and committed on its own. No PR is opened
by the agent at any point: branches are pushed on request and the user opens/merges PRs.

## Phase 1: fix the kyo-test-snapshot compile break (issue 1) plus the JS/Wasm linker OOM (issue 5), one PR

These belong in one tree: the first main push containing only the issue-1 fix would
un-mask issue 5 and produce a new red (JS x64/arm64, Wasm arm64 OOM). Landing both
together makes the next push the candidate green run.

1. build.sbt: add to `kyo-test-snapshot`, mirroring the `kyo-schema-tests` precedent:
   `.dependsOn(kyo-schema-json)`, `-protobuf`, `-msgpack`, `-bson`, `-ion`, `-yaml`
   (keeping the existing `kyo-schema` core and `kyo-test-prop` deps).
2. build.sbt: the two `scalaJSLinkerConfig` batch-mode-inside-CI hunks from #1774
   (`js-settings` and `wasm-settings`). Attribution/coordination with #1774's author is
   the user's call: either the user asks the author to fix the branch (drop the
   dependsOn-removal hunk, re-merge main) and merges #1774 first, or these two hunks
   ride in this PR and the user closes #1774. Decide before implementation; default
   proposal: fold the hunks here, since #1774 cannot merge as-is (it re-breaks
   kyo-test-snapshot by dropping its kyo-schema and kyo-test-prop deps).

Validation gate before push:
- `sbt kyo-test-snapshotJVM/Test/compile` then `sbt kyo-test-snapshotJVM/test`
- `sbt kyo-test-snapshotJS/Test/compile` (link-level validation left to CI)
- `sbt 'show kyo-schemaJS/scalaJSLinkerConfig'` locally still reports batchMode=false
  (CI-only activation)
- Full validation is the PR's own CI (diff mode) and then the merge push (full mode).

## Phase 2: workflows PR (issues 3 + 4 + the ref drift), independent of Phase 1

One PR touching only `.github/`:

1. **release.yml (issue 3)**: before the stage steps, add the liburing install and the
   BoringSSL staging step (cmake/go presence checks + `build-boringssl.sh
   linux-x86_64`), mirroring `.github/actions/setup` and readme.yml's comment
   convention. Both are required: liburing unbreaks `ffiCompile`; BoringSSL staging
   stops publishing kyo-net artifacts with the TLS stub (a silent published-artifact
   defect until now).
2. **build.yml (ref drift)**: change the checkout fallback at build.yml:93 from
   `github.ref` to `github.sha` so every run validates the commit that triggered it,
   matching ci.yml's own concurrency design comment. `workflow_dispatch` behavior is
   unchanged (`inputs.ref` still wins).
3. **readme.yml + .github/actions/setup/action.yml (issue 4)**: replace the
   `nick-fields/retry@v4` wrappers around `sudo apt-get` steps with a plain bash
   retry-with-timeout loop (`timeout` + bounded retries). The retry action's kill of a
   timed-out sudo child dies with EPERM, converting an apt-mirror hang into a hard job
   failure; apt-level retries (`Acquire::Retries=3`) stay.

Validation gate: the repo's actionlint workflow on the PR; readme + ci runs on the PR
branch. release.yml validates on the first main push after merge (or earlier via the
`/release` comment path on the PR if the user wants a pre-merge check).

## Phase 3: confirm green on main

After Phases 1 and 2 merge (Phase 1 is the gating one), the next main push should be
the candidate green run. Watch all four workflows on that push (`scripts/ci-logs.sh
runs main 1`):
- ci: all 8 build jobs green. Known residual risk: the nondeterministic kyo-net fd
  leak (issue 2) can still turn a JVM row red; if it fires, that is issue 2, not a
  Phase 1/2 regression.
- release: publish job past ffiCompile and through staging.
- readme, scalafmt: green.

## Phase 4: the kyo-net fd leak (issue 2), via #1773

- #1773 (the user's open PR) targets exactly this failure class (backpressured
  ReadPump never observing peer FIN leaking fds) and adds the missing NIO leak
  diagnostics; it also fixes `NioTransportTest`'s own leak-check failure. Rebase it on
  green main and let its CI validate; merging it is the user's action.
- The observed leak (ESTABLISHED pair, stale PollerIoDriver pendingReads) is not
  literally the CLOSE_WAIT scenario, so after #1773 lands, treat any recurrence as an
  open bug: reproduce with `KYO_TEST_LEAK_DEBUG=1` on `kyo-netJVM/test` (serial
  leaves, per-descriptor `opened by test:` attribution), then fix the attributed
  test or driver path. Leave-no-issue-behind: this item stays open until either a
  clean recurrence-free window or an attributed fix.

## Explicitly out of the critical path

- #1760 (dependabot checkout 7.0.1): orthogonal; merge whenever. If it merges before
  Phase 2, rebase the Phase 2 branch trivially (same lines adjacent).
- #1761 (aeron): unrelated to the current reds, but note for its review: it repeats
  per-workflow native staging in readme.yml without a release.yml counterpart, the
  same omission pattern behind issue 3 (aeron edition).

## Sequencing summary

1. Phase 1 branch: build.sbt wiring + batch-mode hunks -> validate locally -> push ->
   user opens PR -> CI green -> user merges.
2. Phase 2 branch (parallel): workflows fixes -> actionlint/CI -> push -> user opens
   PR -> user merges.
3. Phase 3: observe the post-merge push; confirm all four workflows green.
4. Phase 4: user rebases/merges #1773; monitor for leak recurrence; attribute and fix
   if it fires again.
