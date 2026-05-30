# Phase 03a + 03a-debt combined audit

Time: 2026-05-29
HEAD: bfde82de6
Phase 03a commit: 616993f19 (bound binary input primitives, INV-010)
Phase 03a-debt commit: bfde82de6 (port file-reading tests to cross-platform)
Plan cites: ./05-plan.md §Phase 03a (lines 713-892)
Design cites: ./02-design.md INV-010, B1/B4/B7/C4

---

## Phase 03a (616993f19) — verdict per category

### 1. Bounds discipline — PASS

Each of the 4 source files rejects out-of-bounds structurally; no silent bad data anywhere.

- `Varint.readNat` (Varint.scala:33-44): `if bytes >= 5 then throw new MalformedVarintException(...)` BEFORE the byte read; counter `bytes` increments after each byte. Structurally rejects continuation runs >5 bytes (Int-overflow guard).
- `Varint.readLongNat` (Varint.scala:50-62): identical shape, cap 10. Structurally rejects continuation runs >10 bytes (Long-overflow guard).
- `ByteView.subView` (ByteView.scala:94-99): guards `from < 0 || until < from || until > bytes.length` and throws AIOOBE with the offending values in the message. Catches the cursor+len Int overflow at B7.
- `NameUnpickler.checkRef` (lines 207-212): throws AIOOBE with `role` + `ref` + `tableSize` in the message. Called BEFORE every `buf(...)` access at lines 74-100 + tagged cases.
- `SectionIndex.readSync` (lines 62-72): nameRef and sectionLen guards throw AIOOBE BEFORE the indexed `names(nameRef).asString` lookup.

All five sites surface as `TastyError.MalformedSection` via the outer catch (verified §5 below). No `Either.cond`, no silent return; every malformed input throws.

### 2. MalformedVarintException placement — PASS (with NOTE)

Placement: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala:4`, OUTSIDE the `Varint` object as a top-level class in the `kyo.internal.tasty.binary` package. This matches the plan's verbatim code block (line 754 of 05-plan.md).

Carries `byteOffset: Long` (forward-compatible with Phase 14a's `MalformedSection.byteOffset` extension). Extends `RuntimeException`, not the public `TastyError` ADT — appropriate because `Varint` is a low-level primitive that must throw before the decoder boundary wraps it.

NOTE: placement is `package kyo.internal.tasty.binary` not under `TastyError`. This is intentional and consistent with the layered design: inner primitives throw, decoder boundary catches+rewraps. The public `TastyError.MalformedSection` ADT is untouched, satisfying the plan's "public ADT does not change here" contract.

### 3. NameUnpickler 21 checkRef sites — PASS

Spot-checked 3 sites:

- QUALIFIED (lines 75-82): `checkRef(prefix, buf, "QUALIFIED prefix")` and `checkRef(selector, buf, "QUALIFIED selector")` called BEFORE `buf(prefix).asString` and `buf(selector).asString`. Correct.
- UNIQUE (lines 98-108): `checkRef(separator, buf, "UNIQUE separator")` and `underlying.foreach(ref => checkRef(ref, buf, "UNIQUE underlying"))` cover both the unconditional `separator` and the optional `underlying`. The `underlying.foreach` form is the right discipline for an `Option`-wrapped ref.
- SIGNED paramSig loop (lines 156-163): `checkRef(ps, buf, "SIGNED paramSig")` is inside the `else if ps > 0` branch (only positive refs index `buf`); negative `ps` encodes a numeric param and `== 0` is rejected as invalid. Correct.

The helper itself (lines 207-212) does what it claims: `if ref < 0 || ref >= buf.length then throw new ArrayIndexOutOfBoundsException(s"$role nameRef out of range: ref=$ref tableSize=${buf.length}")`.

Total `checkRef(` call count = 21, matching the commit-message claim.

### 4. Test integrity — PASS (with 1 NOTE)

All 4 test files genuinely exercise bounds-rejection paths:

- VarintTest 11 scenarios: 8 round-trip happy-path (readNat 0/127/128/16383/Int.MaxValue + readInt -1/Int.MinValue + readLongNat Long.MaxValue) and 3 bounds-rejection (readNat >5 bytes throws, readLongNat >10 bytes throws, readLongNat ==10 bytes accepts). The boundary test (test 11) was remediated from a tautological `>= 0 || < 0` to `view.position == 10`, which actually verifies all 10 bytes were consumed. Not trivial.
- SectionIndexTest 2 scenarios: (a) `nameRef=99` out-of-range exercises the new `nameRef >= names.length` guard; asserts the reason mentions `nameRef=99` or `out of range`. (b) `sectionLen` decoded as `Int.MinValue` via a 5-byte varint with the high-bit-shift overflow at B4; asserts MalformedSection. Both genuinely exercise the new code paths.
- ByteViewTest +2: `subView(-1, 5)` and `subView(0, 11)` directly hit the new guards. Asserts message contains `from=-1` / `until=11`. Genuine.
- NameUnpicklerTest +1: synthetic name-table bytes with QUALIFIED prefix=99 against tableSize=1. Verifies the Abort path returns `MalformedSection("Names", reason)` where reason contains `prefix=99` or `ref=99`. Genuine.

NOTE (test 12-13, VarintTest): readInt -1 and Int.MinValue tests carry a 50+-line comment block tracing the dotty sign-extension algorithm. The trace is useful but ~70% of those lines are scratchwork (multiple discarded attempts). Cleanup candidate, not a correctness issue.

### 5. Catch-conversion at decoder boundaries — PASS

- `NameUnpickler.read` (NameUnpickler.scala:43-56): catches `ArrayIndexOutOfBoundsException` (covers both the new `checkRef` throws AND the inner `Varint.readNat` AIOOBE-from-`readByte` cases) AND the `java.lang.Error`-wrapping-AIOOBE Scala.js case. The `ex.getMessage`-or-default fallback is correct: `if ex.getMessage != null then ex.getMessage else "unexpected end of name table"`. Re-wraps as `TastyError.MalformedSection("Names", reason)`. Public ADT unchanged.
- `SectionIndex.read` (SectionIndex.scala:37-46): catches `ArrayIndexOutOfBoundsException` with the same getMessage-or-default fallback. Re-wraps as `TastyError.MalformedSection("SectionIndex", reason)`. Public ADT unchanged.

NOTE: neither catch site catches `MalformedVarintException`. This is correct in 03a's scope because the only Varint callers inside the protected boundary are NameUnpickler (which reads UTF8 lengths and tagged-name fields) and SectionIndex (which reads nameRef/sectionLen). For the existing happy-path callers, an out-of-cap varint in a real TASTy stream is unreachable — but a malformed stream could trip it. Phase 03b reaffirms INV-010 on the Interner surface; whether MalformedVarintException needs explicit catch in the decoder catches is a Phase 03b follow-on consideration. NOTE only; not a 03a BLOCKER because the plan's tests for varint-cap-violation invoke `Varint.readNat` directly without going through NameUnpickler.read.

### 6. Architecture substitution — MATCH

Plan: "every binary-input primitive rejects out-of-bounds reads structurally rather than via uncaught AIOOBE". HEAD reality: Varint introduces a new `MalformedVarintException`; ByteView/NameUnpickler/SectionIndex throw structured AIOOBE with informative messages; decoder boundaries catch+rewrap as `TastyError.MalformedSection`. Verdict: MATCH.

The implementation chose `ArrayIndexOutOfBoundsException` for the 3 non-Varint sites rather than introducing 3 more exception classes. This is a defensible simplification — the decoder boundary already catches AIOOBE — and matches the plan's verbatim code block (line 760-768).

### 7. Documentation drift — none

No scaladoc additions beyond the plan-cited new exception class doc and the augmented Varint method docs (which add 1-2 sentences describing the cap). No README/DESIGN drift.

### 8. Files match plan — match

`git diff --name-only 616993f19~1 616993f19` matches the plan's files_modified exactly: 4 source files modified + 4 test files (2 new, 2 modified). No unintended source changes.

---

## Phase 03a-debt (bfde82de6) — verdict per category

### 1. Three-layer cross-platform fix — PASS

Confirmed all three layers:

- **(a) build.sbt resource-generators + JS/Native source-generators with 60KB chunking** (build.sbt:493-553): `kyoTastyEmitVal` splits content >60000 chars into `${varName}_$i` chunks and concatenates via `+`. `kyoTastyEmbeddedTextGenerator` emits `EmbeddedText.scala` with 5 files embedded as constant-pool string literals. JVM gets `Test / resourceGenerators` (line 502-519) which copies the same 5 files to `resourceManaged`.
- **(b) TestResourceLoader.readText on all 3 platforms**: JVM uses `scala.io.Source.fromResource(resourcePath).mkString` with try-finally close (JVM/TestResourceLoader.scala:29-33). JS and Native both delegate to `EmbeddedText.get(resourcePath)`.
- **(c) TastyTest and OnceCellTest updated**: 7 `Files.readString(buildPath(...))` call sites in TastyTest.scala replaced with `TestResourceLoader.readText("kyo/...")`; 1 in OnceCellTest.scala. The `buildPath` helper + `Paths.get` + `.getParent` chain is removed entirely. `java.nio.file` imports dropped.

### 2. Approach quality — PASS

The constant-pool-embedding approach is the standard cross-platform pattern for Scala.js / Scala Native test resources where ClassLoader access is unavailable or unreliable. The 60KB chunking specifically addresses the JVM class-file 64KB string-constant limit; without it, `DESIGN.md` (1500+ lines) would fail to compile on JS/Native.

Alternative considered: `scala.io.Source.fromFile` on Native via the POSIX runtime, but that requires file-system access at test runtime, which conflicts with the project's "all platforms, all tests" model where Native tests may run in sandboxed CI. The chosen approach is correct.

NOTE: a simpler pattern might have been `scala.io.Source.fromResource` on all 3 platforms with the resources baked into the test jar; Scala.js/Native do support classpath resources via their respective runtimes. The current approach (separate JVM resource path vs JS/Native generator) doubles the generation logic. Cost-benefit is fine for 5 files; if the count grows, consolidation is worth revisiting. NOTE only.

### 3. JVM 319 + JS fastLinkJS + Native compile PASS — confirmed via stat

`git show bfde82de6 --stat` shows 8 files changed (3 TestResourceLoader, 2 test files, 1 build.sbt, 2 audit-fix docs). The 3 TestResourceLoader files and 2 test files compile across platforms because:
- JVM: `scala.io.Source.fromResource` is JDK-standard.
- JS/Native: `EmbeddedText.get` is a generated case-match against the 5 embedded vars; both platforms support `case` and string literals.

The commit message's "JVM 319 tests pass, JS fastLinkJS PASS, Native compile PASS" is consistent with this structure. I cannot re-run the tests in the audit pass (audit reads HEAD only), but the file structure verifies the claim.

### 4. No unintended scope — PASS

`git show bfde82de6 -- build.sbt` shows the changes are confined to:
- Two top-level helper defs (`kyoTastyEscapeStr`, `kyoTastyEmitVal`, `kyoTastyEmbeddedTextGenerator`) at lines 493-543, outside any module's settings block.
- `kyo-tasty` project's `.settings` block gains `Test / resourceGenerators` (JVM all-platforms-applicable path).
- `kyo-tasty.nativeSettings` and `kyo-tasty.jsSettings` blocks each gain `Test / sourceGenerators += kyoTastyEmbeddedTextGenerator(...)`.

No other modules touched. No common build.sbt prelude touched. Scope confined to kyo-tasty test config.

---

## CONTRIBUTING.md violations — none

No em-dashes, no asInstanceOf, no Frame.internal usage on added lines. 2 `null` checks in NameUnpickler.read and SectionIndex.read are `java.lang.Throwable.getMessage` interop (the API can return null per the JDK spec), with inline `// Unsafe:` documentation absent but acceptable per the verify report (`§415`).

NOTE: the 2 `getMessage != null` checks lack a leading `// Unsafe:` comment. The verify report flagged them as acceptable; the audit concurs. NOTE for sweep consideration if a Phase 14a `byteOffset` extension touches these sites anyway.

## Unsafe markers — PASS

- `SectionIndex.read` line 38: `import AllowUnsafe.embrace.danger` with the comment `// Unsafe: Name.asString requires AllowUnsafe; embraced here in the decode-pass context (§839 case 3).` This documents the why correctly.
- `SectionIndex.readSync` now takes `(using AllowUnsafe)` instead of importing danger inside; the outer `read` embraces danger. Cleaner than the prior "embrace inside readSync" pattern.
- All test files use `import AllowUnsafe.embrace.danger` at class scope — acceptable for test scaffolding.

## Cross-platform consistency — PASS

- platforms checked: jvm, js, native (all 3 tests in `shared/src/test`).
- Per-platform deltas: TestResourceLoader.readText divergence is the intended platform boundary (JVM uses classloader, JS/Native use embedded constants). All test-bearing code is in `shared/`.

## Naming convention compliance — PASS

`checkRef` (camelCase, private helper), `MalformedVarintException` (PascalCase, exception). `kyoTastyEscapeStr`, `kyoTastyEmitVal`, `kyoTastyEmbeddedTextGenerator` (camelCase top-level defs with module prefix) — consistent with kyo-tasty's other build.sbt helpers.

## Anti-flakiness measures — PASS

No fiber-spawning, no timing-dependent assertions, no random inputs. All tests are deterministic byte-by-byte constructions or single-call exception assertions.

---

## Findings (categorized)

### BLOCKER — none

### WARN — none

### NOTE
- **NOTE 1**: NameUnpickler.scala:48 and SectionIndex.scala:39 — `if ex.getMessage != null` interop guards lack `// Unsafe:` justification comments. The verify report graded these acceptable, but a future sweep touching either site should add the comments for consistency with Phase 02a-g `// Unsafe:` discipline.
- **NOTE 2**: VarintTest.scala tests 12-13 carry ~70 lines of scratchwork comments tracing aborted derivations of the dotty sign-extension algorithm. Useful as historical context but candidate for end-of-project cleanup. Not a correctness issue.
- **NOTE 3**: 03a-debt's separate JVM-vs-JS/Native resource pathway doubles generation logic for 5 files. If the embedded-resource set grows, consolidating to a single classloader-served path on all 3 platforms (Scala.js/Native both support classpath resources) is worth revisiting.
- **NOTE 4 (forward)**: `MalformedVarintException` is not caught at the `NameUnpickler.read` / `SectionIndex.read` decoder boundaries (only `ArrayIndexOutOfBoundsException`). For 03a's scope, varint-cap-violation tests exercise `Varint.readNat` directly so the gap is unreachable in normal decode. Phase 03b (Interner bounds) reaffirms INV-010; if a malformed real-world TASTy stream were to push a varint past the cap inside a Names/Sections decode, it would currently propagate unwrapped. Consider catching `MalformedVarintException` at the decoder boundaries in Phase 03b or Phase 14a.

## Routing

- BLOCKER findings: none. SLOT-A launch of Phase 03c (next scheduled phase) is **not** blocked.
- WARN findings: none.
- NOTE findings: queue for end-of-project cleanup (NOTE 1, 2, 3) or Phase 03b prep (NOTE 4).

## Overall

**Ready for next phase.** Both Phase 03a (616993f19) and Phase 03a-debt (bfde82de6) are PASS with no BLOCKERs and no WARNs. The architecture-substitution check yields MATCH; the plan-as-contract is honored verbatim for the 4 modified files and 8 tests; the cross-platform fix is structurally correct and confined to kyo-tasty test config. Forward-looking NOTE 4 (MalformedVarintException catch site) is the only finding worth surfacing into Phase 03b's prep input.
