# Analysis: kyo.STMStressTest failures on main CI

## Symptom

Since run 28628322440 (commit `ba5c7285e`, "Replace Murmur hashing with XXHash (#1726)", July 2),
every main CI run fails `kyo-stmJVM/test` on linux-arm64, and the latest run also on linux-x64.
The failing tests are a stable set:

- "a fiber observing post-commit value via another ref also observes all commit-batched writes"
- "doomed STM transaction aborts before user code observes division-by-zero from stale snapshot"
- "read lock release -> write lock acquire transition produces no stale-read observers"
- "nested TRef pointer-chase under concurrent rotation never observes orphan node"
- intermittently: "atomic update across TMap + TRef + TChunk preserves cross-type invariant"

All fail with `kyo.FailedTransaction` ("STM transaction failed!"): the writer side exhausts
`STM.defaultRetrySchedule` (`Schedule.fixed(1.millis).jitter(0.5).take(Async.defaultConcurrency * 16)`).
These are exactly the tests that commit `246d9977` ("make STM and Hub stress tests deterministic")
deliberately left on the bounded default schedule because their subject is the barging liveness
guarantee ("the writer must never starve out of its retry budget").

## Timeline evidence (arm64 JVM job, same runner class, same test code)

| Run | Commit | STMTest | STMStressTest | Result |
|-----|--------|---------|---------------|--------|
| 28486780335 (Jul 1) | 968b5430 (pre-XXHash) | 3.3s | 35.0s | green |
| 28593851463 (Jul 2) | 72c2f2a3 (pre-XXHash) | 3.0s | 34.8s | green |
| 28628322440 (Jul 2) | ba5c7285 (XXHash) | **29.8s** | **1m 15s, 3 failed** | red |
| 29351755565 (Jul 14) | c77de2e6 (HEAD) | **35.3s** | **1m 23s, 4 failed** | red |

STMTest (no test changes in the window) slows down 10x at exactly the XXHash commit.
Four arm64-green runs precede it; five consecutive arm64-red runs follow it.

## Root cause

`ba5c7285` replaced the memoized `String.hashCode` with per-call `XXHash.hash32(String)` at the
two hottest Tag call sites in `kyo-data/shared/src/main/scala/kyo/Tag.scala`:

1. `fastPathEqual` (used by every `=:=` and `<:<`): was `self.hashCode == that.hashCode`
   (memoized in the String object, O(1) after first call), now `XXHash.hash32(self) == XXHash.hash32(that)`,
   which streams the full UTF-8 encoding of BOTH tag strings and allocates a `Utf8State32` per call.
2. `checkTypes` cache-slot hashing: was `a.hashCode`/`b.hashCode`, now `a.hash`/`b.hash`, which for
   static (String) tags calls `XXHash.hash32` per invocation.

A negative `<:<` check (the common case in kernel handler dispatch loops) now performs four full
XXH32 string streams plus allocations where it previously performed two memoized int loads. Tag
comparisons sit on the kernel's effect-dispatch path, so effect-heavy code (STM's run loop:
`Local.let` + `Abort.run` + `Var.runTuple` per attempt) slows down roughly 10x.

The STM stress tests then fail as a downstream effect: their writers are
`Async.foreachDiscard(...)(i => STM.run(...))`, which runs `defaultConcurrency`-way parallel, so
writers contend with each other (validate-failure churn, which barging does not defend against;
barging only bypasses the readTick reader-fairness yield). The 10x-inflated transaction window on
loaded 4-core CI runners pushes the previously-marginal starvation probability past the bounded
retry budget, and the writer aborts with `FailedTransaction`.

Other call sites migrated by the commit are cold or allocation-free (`XXHash.hashInt` in Cache is
constant work; schema field IDs are derivation-time; Admission fires only on overload) and are not
implicated. The kyo-browser Native settlement failures and Native-job OOMs in the same window are
separate signals (the OOMs predate the XXHash commit); the browser `HoldStill`/`Image` hash sites
use the Array[Byte] XXHash paths, not the String path.

## Local reproduction (Apple Silicon, this worktree at HEAD = c77de2e6)

- `sbt 'kyo-stmJVM/testOnly kyo.STMStressTest'`: 84 passed, 1 failed in 2m 24s; the failure is
  "doomed STM transaction..." with the same `FailedTransaction` signature as CI.
- A/B with the two Tag call sites restored to `String.hashCode`, same machine:

| Suite | HEAD (XXHash hot path) | Restored |
|-------|------------------------|----------|
| STMTest | 25.3s | 3.3s |
| STMStressTest | 84 passed, 1 failed (2m 24s) | 85 passed, 0 failed (1m 22s) |

The 7.7x STMTest recovery matches the CI-observed 10x regression, and the stress failure
disappears with the restoration.

## Fix

Restore the memoized `String.hashCode` at the two in-process call sites; keep XXHash where
cross-process content stability is the documented requirement:

- `fastPathEqual`: back to `self.hashCode == that.hashCode`. In-process comparison needs no
  cross-JVM stability; the collision tradeoff is pre-existing and documented in its scaladoc.
- `checkTypes`: back to `a.hashCode`/`b.hashCode` for slot hashing (in-process cache).
- Keep `Tag.hash` (cross-JVM content hash for aeron stream ids), `Dynamic.hashCode`
  (computed once into a `val`), and all non-Tag XXHash migrations unchanged.

Open question to settle with the user: whether `fastPathEqual` should additionally confirm with
full `String` equality after hash equality (strictly safer than both pre- and post-commit
behavior, costs O(n) only on hash-equal pairs). Default proposal: exact restoration, no semantic
change bundled into a regression fix.

## Validation with the final design applied (local, arm64)

- `kyo-configJVM/test`, `kyo-dataJVM/test`, `kyo-schemaJVM/test`, `kyo-flowJVM/test`: all green.
- `kyo-stmJVM/testOnly kyo.STMTest`: 3.3s (25.3s at HEAD, ~10x recovery matching the CI regression).
- `kyo-stmJVM/testOnly kyo.STMStressTest`: 5 of 5 runs green across the campaign
  (85 passed, 0 failed each), versus 1 failure at HEAD baseline.
- New pins: Tag[Int].hash = -1492440803, Tag[String].hash = -59591402 (harvested from the real
  implementation); README field-number examples recomputed; the reserved-range test fixture
  re-mined (r1641 -> r1635, new in-band number 19700); XXHashTest string pins verified against
  the implementation.

## Adopted design (user-directed): fix the String path inside XXHash itself

Instead of only restoring the two Tag call sites, `XXHash.hash32(input: String)` is reimplemented
as `hashInt(input.hashCode)`: XXH32 applied to the four little-endian bytes of the JLS
`String.hashCode`. Properties:

- Constant-time and allocation-free for reused strings (rides the JVM's `hashCode` memoization),
  so every String call site, present or future, is safe at any frequency. The footgun class dies
  at the source.
- Deterministic cross-platform (JLS hash plus a fixed mixer).
- The XXH32 finalizer restores avalanche quality over the weakly-mixed JLS hash; collision pairs
  are exactly `String.hashCode`'s, the documented pre-XXHash posture.
- The String overload is no longer a UTF-8 content hash; its scaladoc and the object scaladoc say
  exactly what it computes, and interop-relevant docs (kyo-schema CONTRIBUTING/README) state the
  derivation so external implementations can reproduce field numbers.
- The seeded String overload and the `Utf8State32` streaming machinery are deleted (no main-code
  users; dead after the change). Byte-array/Span/Int paths remain official-vector XXH32.
- Nothing is released with the old XXH32-UTF8 String values (no tag contains ba5c7285), so the
  value churn (Tag.hash pins, README field-number examples) is compat-free.
- Tag.fastPathEqual/checkTypes keep comparing raw memoized hashCodes: the mixer is bijective, so
  mixing adds zero discrimination for equality and only costs cycles.

## Audit: every other XXHash call site, checked for the same failure shape

The failure shape is: replacing a memoized O(1) hash read with per-call O(n) recomputation on a
per-operation path. The Tag sites were the only ones with that shape, because they were the only
sites where the pre-XXHash code was memoized. Everywhere else the prior Murmur hashing was already
per-call, so the commit preserved the cost class.

| Site | Variant | Frequency | Pre-XXHash | Verdict |
|------|---------|-----------|------------|---------|
| `Tag.fastPathEqual` | String stream x2/call | per effect op (hottest path in kyo) | memoized `String.hashCode` | WAS the bug; fixed |
| `Tag.checkTypes` cache key | String stream x2/call | per non-`eq` tag comparison | memoized `String.hashCode` | WAS the bug; fixed |
| `Tag.hash` | String stream | aeron topic setup only | `String.hashCode` | OK: cold, content-stable semantics are the point |
| `Tag.internal.dynamicHashCode` | String stream, once | per `Dynamic` construction, cached in a `val` | Murmur `caseClassHash`, also once | OK |
| `Admission.reject(key: String)` (kyo-scheduler:156) | String stream | per admitted request (opt-in keyed backpressure via `kyo.Admission`) | `MurmurHash3.stringHash` per call | OK: same cost class as before; short keys; tens of ns per request |
| `Rollout` bucket (kyo-config:49,113) | String stream | per flag evaluation | Murmur per call | OK: cross-process-stable bucketing is the semantic requirement |
| `Cache` slot scramble (kyo-core:762) | `hashInt` | hot | inline fmix32 | OK: constant-time, allocation-free, equivalent |
| `Image.hash` / HoldStill `frameHash` (kyo-browser:39) | Array bulk stripes | per captured frame | Murmur over same bytes | OK: no allocation, capture cost dwarfs the hash |
| `CodecMacro` field IDs (kyo-schema:18) | String stream | compile-time (macro expansion) | Murmur | OK |
| `WorkflowSchema` structural hash (kyo-flow:54) | String stream | workflow registration | Murmur | OK |

Notes, non-actionable: the runtime String sites (Admission, Rollout) got moderately more expensive
per call than Murmur (UTF-8 encoding ladder plus a `Utf8State32` allocation, versus Murmur's
two-chars-per-round mixing with no allocation), roughly 2-4x on short keys. At per-request and
per-flag-evaluation frequencies this is noise. The `Array[Byte]`/`Span[Byte]` bulk paths read
16-byte stripes with no allocation and are fine at any frequency used here.
