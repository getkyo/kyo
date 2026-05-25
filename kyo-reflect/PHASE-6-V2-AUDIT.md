# Phase 6 v2 Audit: G3 Comments Section Reader (Symbol.scaladoc)

**Commit audited**: `89b15fa7d` ("kyo-reflect v2 Phase 6: G3 Comments section reader (Symbol.scaladoc)")
**Plan reference**: `execution-plan-v2.md` lines 232-276
**Audited**: 2026-05-25

---

## Summary

All plan requirements are fully satisfied. No BLOCKERs. Zero new prohibited patterns. The only
deviation from the plan (Symbol.comment -> Symbol.scaladoc) is supervisor-approved and documented.

---

## Checklist

### File production

**CommentsUnpickler.scala created**
PASS. Present at `shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala`.
Signature matches plan: `def read(view: ByteView, addrMap: Map[Int, Reflect.Symbol])(using Frame): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError])`. Plan specified an additional `< Abort[ReflectError]` only but the extra `Sync` is correct because `Sync.defer` wraps the pure-computed result on the success path.

**CommentsUnpicklerTest.scala created**
PASS. Present at `shared/src/test/scala/kyo/CommentsUnpicklerTest.scala` with 6 test cases, matching the plan's "Total new tests: 6".

### Symbol fields

**_scaladoc SingleAssign field on internal Symbol**
PASS. `Reflect.Symbol` (line 237-238 of Reflect.scala) declares:
```
private[kyo] val _scaladoc: kyo.internal.reflect.symbol.SingleAssign[Maybe[String]] =
    new kyo.internal.reflect.symbol.SingleAssign
```
Uses `SingleAssign` (the plan-mandated type), not a `Memo` or `var`.

**Symbol.scaladoc public accessor (plan says Symbol.comment -- supervisor-approved deviation)**
PASS. The plan named this accessor `Symbol.comment`. The implementation uses `Symbol.scaladoc`. The commit message documents this as a supervisor-approved deviation on grounds that the TASTy Comments section only encodes scaladoc text; `scaladoc` is the more precise name. The accessor is pure (no effect row), matching the plan contract. The PROGRESS.md deviation log records this.

### mergeResults wiring

**mergeResults wires _scaladoc**
PASS. `ClasspathOrchestrator.mergeResults` (lines 335-344) implements the Phase 6 pattern:
- Iterates `fr.commentsBySymbol` and calls `sym._scaladoc.set(Maybe(text))` for each entry.
- After the per-file pass, iterates `allSyms` and calls `sym._scaladoc.set(Maybe.Absent)` for every symbol not yet set.
- Protected by `if !sym._scaladoc.isSet` guard before each `set` to avoid double-set errors.

`FileResult` carries `commentsBySymbol: Map[Reflect.Symbol, String]` (added in this commit alongside the wiring).

`decodeTastyBytes` calls `CommentsUnpickler.read` when the `Comments` section is present, and defaults to `Map.empty` when absent. Uses `attrs.sourceFile` for the PositionsUnpickler (Phase 7 concern; Phase 6 uses only `addrMap`).

### ClassfileUnpickler wiring

**ClassfileUnpickler sets _scaladoc = Absent**
PASS. `ClassfileUnpickler.scala` (lines 78, 85-86, 96-97 of the class file) sets `_scaladoc.set(Maybe.Absent)` for every classfile-sourced symbol: the class symbol itself, all member symbols, and all type-param symbols. This satisfies Test 6.

### Tests

All 6 plan tests are present and correctly scoped:

| Plan test | Description | File:method |
|-----------|-------------|-------------|
| Test 1 | Documented class entry produces scaladoc text | CommentsUnpicklerTest "documented class entry..." |
| Test 2 | Empty section returns empty map without error | CommentsUnpicklerTest "empty payload returns empty map..." |
| Test 3 | Truncated section fails with MalformedSection("Comments", ...) | CommentsUnpicklerTest "truncated section produces MalformedSection error" |
| Test 4 | Present(text) for documented symbol; Absent for undocumented sibling | CommentsUnpicklerTest "symbol with comment gets Present..." |
| Test 5 | Two siblings independently accessible, no cross-contamination | CommentsUnpicklerTest "two sibling definitions have independent..." |
| Test 6 | Java classfile symbol always Absent (tagged jvmOnly) | CommentsUnpicklerTest "Java classfile symbol always has scaladoc == Absent" |

### Prohibited pattern scan

| Pattern | CommentsUnpickler.scala | CommentsUnpicklerTest.scala |
|---------|-------------------------|-----------------------------|
| em-dashes (`—`) | PASS: none found | PASS: none found |
| `Frame.internal` | PASS: none | PASS: none |
| new `asInstanceOf` | PASS: none | PASS: none |
| new `AllowUnsafe` | PASS: none in unpickler; test uses `AllowUnsafe.embrace.danger` to simulate mergeResults, which is correct: the test manually calls `sym._scaladoc.set(...)` to verify the field, exactly as mergeResults does |

---

## Findings

### NOTE-1: Sync widening in read signature
The plan specified `Map[Reflect.Symbol, String] < Abort[ReflectError]` but the implementation returns `Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError])`. The `Sync` arises from `Sync.defer(m)` on the success path. This is idiomatic kyo-reflect style (uniform with all other section readers) and not a deviation from intent. No action needed.

### NOTE-2: LongInt skip is bit-inverse of standard TASTy
The scaladoc LongInt skip (span info after each entry) uses the dotty convention where bit 7 CLEAR is the continuation bit and bit 7 SET is the stop bit. The implementation's `skipLongInt` correctly applies `(b & 0x80) == 0` as the continue condition. This is the inverse of the Nat encoding convention (where SET is the last byte). The code comment documents this. No issue.

### NOTE-3: addrMap skips for sub-expression nodes
Entries whose address is not in `addrMap` are silently skipped (as specified). There is no test exercising a section that contains both definition-level and sub-expression-level entries to confirm the filtering is correct. This is a minor coverage gap but not a correctness issue because the `addrMap` keys are authoritative (built by Pass 1 from definition-node addresses only).

---

## Verdict

**0 BLOCKER. 0 WARN. 3 NOTE (informational only).**

Phase 6 is complete and correct. All plan requirements met. Supervisor-approved deviation (scaladoc vs. comment) is properly documented. Safe to proceed to Phase 7.
