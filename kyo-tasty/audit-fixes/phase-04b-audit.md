# Phase 04b audit

Time: 2026-05-29T22:35:00Z
HEAD: 879b88897
Phase commit: 879b88897
Plan cites: ./05-plan.md §Phase 04b (lines 1075-1170)
Design cites: ./02-design.md (B6, INV-018)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: position is Long-typed | PRESENT_STRICT | jvm/src/test/scala/kyo/MappedByteViewTest.scala:36-45 — `goto(3_000_000_000L)` then `assert(view.position == 3_000_000_000L)`. Pins INV-018, B6. |
| 2: readByte past Int.MaxValue raises | PRESENT_STRICT | jvm/src/test/scala/kyo/MappedByteViewTest.scala:47-58 — cursor at `Int.MaxValue.toLong + 1L`, intercepts `IllegalStateException` containing `"mmap segment overflow"`. Pins INV-018, B6. |

Both tests `taggedAs jvmOnly`. 2/2 expected, 2/2 PRESENT_STRICT.

## CONTRIBUTING.md violations

None. Convention sweep clean on added lines (no em-dash, no AllowUnsafe additions, no `asInstanceOf`, no `Frame.internal`, no `java.util.concurrent` outside the JVM-only test, no semicolons, no default params on internal APIs, no LLM-tells). `j.u.c.a.AtomicBoolean` import in the new test file is justified: the SUT's `closed` parameter is `j.u.c.a.AtomicBoolean`.

## Unsafe markers

No new `AllowUnsafe` sites introduced in this phase. Pre-existing `AllowUnsafe.embrace.danger` in `AstUnpickler.runPass1` (line 161) untouched.

## Cross-platform consistency

- Platforms checked: jvm, js, native.
- JVM: 328/328 + 2/2 MappedByteViewTest PASS.
- JS: `Test/compile` PASS (Mapped impl not present on JS; trait widening compiles cleanly via the shared sources).
- Native: `Test/compile` PASS. Native `MappedByteView` widened to Long signatures (D2). No `>Int.MaxValue` guard on Native `readByte`: confirmed correct — Scala Native pointer indexing `ptr(cursor)` accepts Long natively (see `scala.scalanative.unsafe.Ptr.apply(Long)`), so there is no Int-domain narrowing point where the guard would attach.

## Naming convention compliance

No deviations. `positionInt` / `readEndInt` / `remainingInt` / `gotoInt` / `subViewInt` follow the canonical `xxxInt` suffix idiom (consistent with Scala's `IntMap` / `LongMap` naming).

## Steering deviation

`git diff --name-only 879b88897~1 879b88897` matches `files_modified` for phase 04b plus the 8 cascade files surfaced in `phase-04b-prep.md` items 2/3/4 and resolved per D2-D5. Plan declared `platforms: [jvm]`, which understated the real shape (trait widening forces JS/Native rebuild + Native impl). Per `feedback_all_platforms_all_tests`, lockstep update of Native is correct, not drift. The commit message names this explicitly.

## Anti-flakiness measures

The two MappedByteViewTest scenarios are deterministic: a 1-byte mmap temp file with the logical cursor placed via `goto` to the Long target. No timing, no sleep, no concurrency. `tmp.toFile.deleteOnExit()` plus `try/finally cleanup()` closes the channel and RAF deterministically.

## Architecture substitution check

- Design intent: widen `MappedByteView` accessors that may address > 2GB regions to `Long`; widen the `ByteView` trait abstract methods in lockstep; provide Int wrappers via `Math.toIntExact` at migration sites so the Int-keyed downstream (TastyOrigin Int fields, IntMap-keyed addrMap, Array indexing) stays Int.
- HEAD reality: 6 trait abstract methods widened to Long (`peekByte`, `readEnd`, `subView`, `goto`, `remaining`, `position`); 5 `final` Int wrapper methods on the trait (`positionInt`, `readEndInt`, `remainingInt`, `gotoInt`, `subViewInt`); JVM and Native impls widened in lockstep; cascade uses `positionInt` / `Math.toIntExact(...)` at the 7 TastyOrigin construction sites and at every Int-keyed boundary (HashMap keys, `Tasty.Tree.Unknown.length`, `copyOfRange` indices, `UnknownTagException` position arg).
- Verdict: MATCH. No simpler-equivalent substitution; the two-tier surface (Long abstract + Int `final` wrappers) is the cleanest expression of the design constraint, not a half-state — the wrappers exist to keep the 7+ Int-bounded downstream sites readable without a uniform rewrite. New-write sites take Long; legacy Int sites call `xxxInt`. The naming makes the narrowing point self-documenting at every call site.

### API consistency (audit focus 1)

The trait's 6 Long abstract methods + 5 Int `final` wrappers is a clean two-tier surface, not a half-state:
- Long methods are the primitive surface (mandatory override for any impl).
- Int wrappers are pure `final` derivations on the trait (`Math.toIntExact` for read-side, `.toLong` for write-side); zero impl burden.
- The wrappers are named with the explicit `Int` suffix and scaladoc tags them as Int-narrowing for TastyOrigin migration. There is no ambiguity at the call site about which version is in use.

A maximally-pure alternative ("widen TastyOrigin to Long too, kill the wrappers") would have ballooned scope into the public `Tasty.Symbol.TastyOrigin` API and downstream addrMap/IntMap representation, where the section-bytes invariant (`Array[Byte]` is always Int-bounded) makes Long carriage genuinely redundant. The chosen split is principled.

### TastyOrigin downcast safety (audit focus 3)

7 `Math.toIntExact` / `positionInt` sites narrow Long back to Int for `Tasty.Symbol.TastyOrigin(bodyStart: Int, bodyEnd: Int, ...)`. On a genuine > Int.MaxValue value (a real 4GB+ TASTy section), `Math.toIntExact` throws `ArithmeticException`. This propagates up through `walkStats` into `runPass1`, which is wrapped at `readPass1` (`AstUnpickler.scala:97-106`):

```
catch
    case ex: ArrayIndexOutOfBoundsException => Left(MalformedSection("ASTs", ...))
    case ex: java.lang.Error if ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] => ...
    case ex: Exception => Left(MalformedSection("ASTs", s"parse error: ${ex.getMessage}"))
```

`ArithmeticException extends RuntimeException extends Exception`, so the third arm catches it and lifts it to `TastyError.MalformedSection`. Behavior on overflow: a clean Abort.fail with a parse-error message, not an uncaught crash. The MalformedSection label is slightly imprecise (the file is well-formed but unsupported by the Int-keyed addrMap shape), but it routes correctly through the standard error channel. Acceptable; documented under D3.

## Documentation drift

Scaladoc additions in this phase:
- `ByteView.scala`: scaladoc on each of the 5 Int wrapper methods (one-liners naming the underlying Long method). Within plan intent.
- `MappedByteView.scala` (JVM and Native): updated `@param end` and class-level scaladoc to reflect Long absolute end offset. Within plan intent.
- `MappedByteViewTest.scala`: scaladoc on the test class and on `makeView` helper. Standard test-doc shape.

Beyond plan intent: no.

## Cascade containment (audit focus 4)

7 reader files touched. Each change is a strict mechanical narrowing at the trait-boundary point:
- `AstUnpickler.scala` (7 TastyOrigin sites: `Math.toIntExact(payloadBody/payloadEnd)`; `walkStats` / `decodeOneTypeIfPresent` / `readDefDefReturnType` / `decodeTemplateParents` / `scanForwardAndCollectFlags` / `readModifiers` signatures widened to `end: Long`).
- `TypeUnpickler.scala` (`readTypeNode` uses `positionInt` for Int-keyed addrCache; `readTypeLambdaParams` / `readMethodParams` / `readTypesUntil` / `skipToEnd` widened to `end: Long`; `ANNOTATEDtype` uses `view.positionInt` + `Math.toIntExact(end)` for `Arrays.copyOfRange`).
- `TreeUnpickler.scala` (`readTree` uses `positionInt` for Int-keyed treeAddrCache; 9 helper methods widened to `end: Long`; 3 `Tasty.Tree.Unknown(tag, end - startAddr)` sites use `Math.toIntExact(end - startAddr)`).
- `SectionIndex.scala` (`val offset = view.positionInt` for `Map[String, (Int, Int)]`).
- `AttributeUnpickler.scala` (`view.positionInt` for `UnknownTagException(tag, pos: Int)`).
- `ClassfileUnpickler.scala` (`captureBytes`: `start = h.positionInt`).
- `ConstantPool.scala` (CONSTANT_Utf8: `off = view.positionInt`).

Incidental edit found: `TreeUnpickler.readUnapplyParts` (lines ~723-731) had an unused local `val startAddr = view.position` removed and `val inner = readTree(...); implicits += inner` collapsed to `implicits += readTree(...)`. This is a dead-store cleanup co-located with the type widening rather than a plan-justified change. Per `feedback_no_scope_cuts` and the steering bar, this is borderline — it does not alter behavior or surface, and the dead `val` would otherwise have become a Long-typed dead val. Classifying as NOTE, not WARN, because behavior is unchanged and the removal makes the function strictly tighter.

## Test integrity (audit focus 5)

Test 1 (`position is Long-typed after goto with cursor beyond Int.MaxValue`):
- Does NOT mmap 3GB. Creates a 1-byte temp file, mmaps 1 byte, then constructs `MappedByteView(buf, start=0L, end=5_000_000_000L, closed)` and calls `goto(3_000_000_000L)`. The mmap buffer is intentionally smaller than the logical end; the test never reads from the mapped region, only verifies that `view.position` round-trips the Long value through the internal `cursor: Long` field.
- Semantics: this is the correct minimal test for the cursor-width contract. The cursor is a `private var cursor: Long`, and the read path is `goto(addr)` -> `cursor = addr` -> `position` returns `cursor`. The test exercises exactly this slice. It would catch:
  - `position: Int` (return type narrowing — compile error).
  - `cursor: Int` (storage narrowing — `goto(3_000_000_000L)` would throw or truncate).
  - any `.toInt` narrowing in `goto` or `position` (assertion failure).
- It would not catch: a backing-buffer addressing bug on actual > 2GB reads. That is what test 2 covers structurally (the overflow guard), and a real 4GB+ smoke test is genuinely out of scope for a unit-time test (would require 4GB free disk + minutes of mmap setup). The two-test combination is the right shape.

Test 2 (`readByte past Int.MaxValue raises IllegalStateException with mmap segment overflow`):
- Cursor at `Int.MaxValue.toLong + 1L`. `readByte()` is called; the JVM-side guard at `MappedByteView.scala:41-45` throws `IllegalStateException(s"MappedByteView cursor $cursor exceeds Int.MaxValue; mmap segment overflow")` before touching the buffer. The intercept matches the substring.
- This pins the JVM-specific guard explicitly. Native correctly has no equivalent guard (D2 rationale: pointer arithmetic accepts Long).

Verdict: tests exercise the actual Long cursor behavior and the actual overflow guard, not a simulation.

## Findings (categorized)

- BLOCKER: none.
- WARN: none.
- NOTE:
  - NOTE-1: incidental dead-store cleanup in `TreeUnpickler.readUnapplyParts` (removed unused `startAddr = view.position` and inlined `inner` temporary). Behavior unchanged; tighter code. Cross-checked: no plan item authorizes this; cascade containment is otherwise strict. Routes to end-of-project cleanup acknowledgement only.
  - NOTE-2: `MalformedSection("ASTs", "parse error: ...")` is the message a genuine 4GB+ TASTy file would surface via `Math.toIntExact` ArithmeticException catch. If kyo-tasty ever needs to support such files, replace TastyOrigin's Int fields with Long and remove the `xxxInt` wrappers as a Phase 04b-followup. Not a 04b issue; informational for downstream scale work.

## Routing

- BLOCKER findings: none — no halt of SLOT-A launch.
- WARN findings: none — no TaskCreate for Phase 04c prep.
- NOTE findings: NOTE-1, NOTE-2 — TaskCreate for end-of-project cleanup acknowledgement only. Phase 04c is unrelated (truncated CEN detection in `JarCentralDirectory`); no carry-forward needed.

## Overall

Phase 04b is READY. Architecture MATCH, conventions clean, cascade strictly contained except one minor dead-store cleanup (NOTE), tests pin both contracts with correct semantics, cross-platform PASS. Proceed.
