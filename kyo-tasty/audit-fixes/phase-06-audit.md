# Phase 06 Audit — OnceCell idempotence enforcement

**HEAD:** `64d4cc17d`
**Scope:** `OnceCell.scala` debug-mode CAS-loss check; 5 new tests in `OnceCellTest.scala`.
**Overall verdict:** READY.

## Per-category

1. **debugIdempotent class-load read — PASS (with NOTE).**
   `private[kyo] val debugIdempotent` is resolved exactly once at object init (`OnceCell.scala:66-67`). A `-D` set after class load is ignored. Acceptable for a debug-only switch: callers set the property at JVM start, which matches every other `sys.props`-gated diagnostic in kyo. The doc string at line 64 already says "Enable via `-D...`", communicating the contract.

2. **`.equals()` vs `!=` — PASS.**
   Commit message and `OnceCell.scala:46` correctly justify Scala 3 E172. `winner.equals(v)` is the right semantic: reference equality (`eq`) would silently miss value-equal-but-distinct-instance non-idempotence (the exact failure mode the check exists to catch). `CanEqual[AnyRef, AnyRef]` would require a wider opt-in.

3. **Hot-path cost — PASS.**
   Line 44 `if !won && OnceCell.debugIdempotent` short-circuits on `&&` BEFORE `ref.get()` and `.equals()`. Non-debug builds pay one extra boolean load on CAS loss only. Zero cost on CAS win and zero cost on cache hit.

4. **kyo.Async.foreach cross-platform pattern — PASS.**
   Tests 5 and 6 use `Async.foreach(1 to 8, concurrency = 8)` inside `run { ... }`, wrapping per-fiber work in `Sync.Unsafe.defer`. Test 6 collects `Chunk[Result[IllegalStateException, AnyRef]]` so both `debugIdempotent` branches share a uniform effect row. Pattern is clean; runs on JVM/JS/Native from `shared/`.

5. **Remediation history — PASS.**
   Initial impl used `java.lang.Thread` and broke JS link (commit msg: "initial FAIL exit 1 ... JS LINK failed"). Rewrite to `Async.foreach` is documented inline and aligns with `feedback_all_platforms_all_tests`. Final flow-verify exit 0 across all three platforms.

## NOTE for Phase 07a prep

Class-load-time property read means `OnceCell.debugIdempotent` cannot be toggled per-test inside the same JVM. If a future phase wants per-suite assertion of the throw path, it must fork a JVM with the `-D` set, or refactor to read the property per `get()` call (rejected here on hot-path grounds). Phase 07a should not assume in-process toggling.
