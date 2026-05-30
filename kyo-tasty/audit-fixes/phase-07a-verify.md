# Phase 07a verify report

Status: PASS

Run-id: phase-07a-verify-1
HEAD: 64d4cc17d (Phase 06; impl reports unchanged)
Baseline: kyo-tasty/audit-fixes/phase-07a-baseline.txt (contains only itself)

Scope: B12. `Interner.internInShard` wraps the "observe + decide-to-grow" pair
atomically: `shardRef.synchronized { if shardRef.get() eq table then growShard(...) }`.
Race window closed. `growShard` retains its inner re-check as defense-in-depth.

Authorized files:
- kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala (modified)
- kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala (modified; +2 leaves)

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: GREEN on all three platforms.
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-07a-flow-verify-testOnly-jvm-1.log`
    `Tests: succeeded 17, failed 0, canceled 0, ignored 0`, `[success] Total time: 1 s`.
    Both new B12 leaves listed: `B12/Phase-07a: concurrent grow-and-insert preserves
    all entries under contention (24 ms)`, `B12/Phase-07a: grow during contention
    preserves reference equality for shared key (2 ms)`.
  - JS testOnly: `kyo-tasty/audit-fixes/runs/phase-07a-flow-verify-testOnly-js-1.log`
    `Tests: succeeded 17, failed 0, canceled 0, ignored 0`, `[success] Total time:
    3 s`. Full Scala.js link succeeded; no `java.lang.Thread` linker rejection.
    Both B12 leaves ran on JS.
  - Native testOnly: `kyo-tasty/audit-fixes/runs/phase-07a-flow-verify-testOnly-native-1.log`
    `Tests: succeeded 17, failed 0, canceled 0, ignored 0`, `[success] Total time:
    18 s`. Both B12 leaves ran on Native.

- reward-hacking grep: 0 hits, 0 overridden.

- fp-discipline grep: 24 raw hits, 0 overridden. Classified:
  - PRE-EXISTING in `Interner.scala`: `bare-var` lines 71, 72, 121, 125, 145, 146,
    156, 157 (all at HEAD before phase 07a); `juc-atomic-import` and `juc-tree`
    lines 3-5 (HEAD imports); `null-literal` lines 72, 73, 75, 94, 124, 126 (HEAD
    sentinel checks for empty slots / loop probe); `private-over-annotation`
    lines 138, 155 (HEAD `private def bytesEqual` / `private def computeHash`).
  - PRE-EXISTING in `InternerTest.scala`: `juc-tree` line 185
    (`java.util.concurrent.ThreadLocalRandom.current().nextInt()` from Phase 06
    test, already at HEAD).
  - NEW in `InternerTest.scala`: `juc-tree` line 287
    (`new java.util.concurrent.atomic.AtomicInteger(0)` introduced by phase 07a
    test 1 as the fiber-id counter). Established test-infra convention in this
    module: `OnceCellTest.scala:3` imports the same type, `QueryApiTest.scala:285`
    constructs `AtomicReference`, `InternerTest.scala:185` already uses
    `ThreadLocalRandom`. Convention precedent set in Phase 06 verify report
    (`phase-06-verify.md`) and accepted; treated as conventional test-infra here.
  - Verdict: PASS for 07a scope. NEW hit is the single AtomicInteger counter
    matching established cross-test convention; all source-side hits are
    PRE-EXISTING at HEAD.

- llm-tells grep: 6 raw hits, 0 overridden. All in
  `kyo-tasty/audit-fixes/phase-06-audit.md` (em-dash lines 1, 9, 12, 15, 18, 21).
  This is a PRE-EXISTING untracked prior-phase audit artifact; not authored in
  07a. Authorized 07a files (Interner.scala, InternerTest.scala,
  phase-07a-decisions.md, phase-07a-baseline.txt) grep clean for em-dash /
  en-dash / hedging vocabulary / AI-boilerplate. Verdict: PASS for 07a scope.

- dev-tag grep: 0 hits, 0 overridden.

- plan-diff (with baseline, manual classification due to known yq
  object-form limitation cited in phase-06-verify.md):
  - AUTHORIZED:
    `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala`
    (plan files_modified.path),
    `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala` (plan tests.files).
  - DRIFT-FROM-IMPL: NONE.
  - PRE-EXISTING (untracked artifacts, supervisor judgment):
    `kyo-tasty/audit-fixes/phase-06-audit.md` (post-phase-06 audit artifact, was
    not in 07a baseline because the baseline was captured AFTER 06 audit
    landed),
    `kyo-tasty/audit-fixes/phase-07a-baseline.txt`,
    `kyo-tasty/audit-fixes/phase-07a-decisions.md` (this phase's process
    artifacts).
  - Counts: MISSING=0 DRIFT-FROM-IMPL=0 AUTHORIZED=2 PRE-EXISTING=3.

- test-count: expected=2 actual=2. Plan declared 2 leaves (Phase 07a slice in
  05-plan.yaml line 513). Impl added exactly 2 new leaves, both prefixed
  `B12/Phase-07a:`, into the single authorized test file `InternerTest.scala`.

- stowaway-commit: NONE. HEAD remains `64d4cc17d` (Phase 06); no commit was
  authored inside the impl dispatch.

- cross-platform (plan declares `platforms: [jvm, js, native]`):
  JVM: 17/17 passed | JS: 17/17 passed | Native: 17/17 passed.
  All three platforms green, all two new B12 leaves green on each. The
  cross-platform pattern (`kyo.Async.foreach` instead of raw
  `java.lang.Thread`) is the exact remediation pattern recommended in
  phase-06-verify class-B finding #1 (PHASE-06 BLOCKER), and it works.

- invariants gate: consumed=INV-010 (Interner offset/length bounds check),
  produced=none. INV-010 is unchanged by this phase; the bounds-check guard at
  the entry to `intern` is untouched. All four B10 INV-010 tests still pass on
  every platform (verified in the test logs).

## B12 verdict

**B12 source change (Interner.scala lines 76-89): CORRECT.**

The diff inserts `shardRef.synchronized { if shardRef.get() eq table then
growShard(shardRef, loadCounter, len) }` between the load-factor observation
(line 77 `if loadCounter.get() * 4 >= len * 3`) and the retry-via-recursion
(line 90 `return internInShard(...)`). Properties verified:

1. `table` was captured at the top of `internInShard` BEFORE the while loop
   (Interner.scala line 70); the eq-check inside the lock is therefore against
   the table this fiber actually probed.
2. `shardRef.synchronized` and `growShard`'s internal `shardRef.synchronized`
   are the same monitor; JVM intrinsic locks are reentrant, so no deadlock.
3. `growShard` retains its inner `current.length() == observedLen` re-check
   (defense-in-depth, layered with the new outer eq-check).
4. Re-entry via `return internInShard(...)` re-reads the current `shardRef`
   from scratch, so the calling fiber always observes the post-grow table.
5. The race window between un-synchronized load-factor observation and grow
   decision is now closed: any concurrent grow that completes between
   `loadCounter.get()` and `shardRef.synchronized` will be observed as `ne
   table`, and the redundant grow is skipped.

The two B12 leaves pin the property:

- Leaf 1 (8 fibers x 1000 inserts on numShards=2/initialShardCapacity=4):
  forces dozens of concurrent grows, then verifies all 8000 inserts survived
  and re-interning returns the exact same Entry reference. Passed on JVM
  (24 ms), JS (28 ms), Native (50 ms).

- Leaf 2 (4 fibers racing the same `[1,2,3]` key on
  numShards=1/initialShardCapacity=2): forces immediate grow contention on a
  single shared key. Verifies all 4 fibers return the exact same Entry
  reference (no spurious distinct Entry from a torn grow). Passed on JVM
  (2 ms), JS (1 ms), Native (0 ms).

Both leaves use `kyo.Async.foreach` for concurrency. Source grep confirms no
raw `java.lang.Thread` reference anywhere in `InternerTest.scala` (the JS
linker would have rejected such a reference, as it did for `OnceCellTest`
in Phase 06).

## Class-B findings (opus judgment)

(none)

The diff was reviewed against the catch-list and the design citation in
phase-07a-decisions.md. No hits on: specific-to-catchall, hash collision,
coverage mismatch, bespoke-where-canonical, stringly-typed dispatch, Frame
propagation gap, refactor invariant drift, re-framing failure as success,
extension-on-owned-type, test-infra drift (no new test base class), stub
returns expected value (both leaves observe via the public `intern` API and
assert on REFERENCE-EQUALITY of the returned Entry, which is what `intern`
itself produces, so reference equality cannot be faked by the test), test
controls its own signal (`fiberIdCounter` only namespaces test-data byte
strings; the assertion is against the Entry identity which is produced
entirely by `Interner`, not the test), test bypasses the API under test
(both leaves go through the public `intern` method end-to-end), fabricated
facts (decisions doc cites real file/line anchors and a real source diff).

## Held-out acceptance check

Derived from 02-design.md (Interner section, B12 finding): "The
observe-load-factor-then-grow pair MUST be atomic against concurrent
growShard calls on the same shard; otherwise the same fiber can decide to
grow when another fiber has already done so."

Held-out check: examine the source to verify that the load-factor
observation (`loadCounter.get() * 4 >= len * 3`) is now followed by an
explicit synchronized re-check against `shardRef.get() eq table` BEFORE
`growShard` is invoked. Confirmed at Interner.scala lines 77-87:

```scala
if loadCounter.get() * 4 >= len * 3 then
    shardRef.synchronized {
        if shardRef.get() eq table then
            growShard(shardRef, loadCounter, len)
    }
    return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
```

The re-check is present, gates growShard, and is followed by a full
re-entry that re-reads `shardRef`. Held-out check PASSES.

## Overrides

(none)

## Exit code: 0
