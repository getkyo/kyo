# Phase 7 v2 Audit: G2 Positions Section Reader (Symbol.position)

**Commit audited**: `63dcbe53f` ("kyo-reflect v2 Phase 7: G2 Position section reader (Symbol.position)")
**Plan reference**: `execution-plan-v2.md` lines 278-321
**Audited**: 2026-05-25

---

## Summary

All plan requirements are fully satisfied. No BLOCKERs. One WARN (minor: IMPROVEMENT-ANALYSIS.md
stated G2 depends on G1 but the plan correctly overrides this dependency). One NOTE on an
Attributes section decode that arrived in this phase rather than earlier. No regressions.

---

## Checklist

### File production

**PositionsUnpickler.scala created**
PASS. Present at `shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala`.
Signature matches plan: `def read(view: ByteView, addrMap: Map[Int, Reflect.Symbol], sourceFile: Maybe[String])(using Frame): Map[Reflect.Symbol, Reflect.Position] < (Sync & Abort[ReflectError])`.

**PositionsUnpicklerTest.scala created**
PASS. Present at `shared/src/test/scala/kyo/PositionsUnpicklerTest.scala` with 5 test cases, matching plan's "Total new tests: 5".

### Symbol fields

**_position SingleAssign field**
PASS. `Reflect.Symbol` (lines 239-240 of Reflect.scala) declares:
```
private[kyo] val _position: kyo.internal.reflect.symbol.SingleAssign[Maybe[Position]] =
    new kyo.internal.reflect.symbol.SingleAssign
```

**Position case class (sourceFile, line, column)**
PASS. `Reflect.scala` line 155:
```scala
final case class Position(sourceFile: Maybe[String], line: Int, column: Int)
```
Matches plan spec exactly: `Maybe[String]` for sourceFile, `Int` for line and column.

**Symbol.position public accessor (pure, no effect row)**
PASS. The `position` accessor (lines 276-281 of Reflect.scala) returns `Maybe[Position]` with no Sync/Async/Abort in the return type. It reads `_position.get()` under an `AllowUnsafe.embrace.danger` scope, consistent with all other pure `SingleAssign`-backed accessors in the file.

### mergeResults wiring

**mergeResults wires _position**
PASS. `ClasspathOrchestrator.mergeResults` (lines 346-355) implements the Phase 7 pattern symmetrically with Phase 6:
- Iterates `fr.positionsBySymbol` and calls `sym._position.set(Maybe(pos))`.
- Iterates `allSyms` and calls `sym._position.set(Maybe.Absent)` for every symbol not yet set.
- `FileResult` carries `positionsBySymbol: Map[Reflect.Symbol, Reflect.Position]` added in this commit.

### Attributes section finally being decoded

**Attributes section decoded (was previously defaulted)**
PASS. This commit introduced the full Attributes section decode in `decodeTastyBytes`:
```scala
attrs <- sections.get(TastyFormat.AttributesSection) match
    case Present((offset, length)) =>
        val attrView = view.subView(offset, offset + length)
        AttributeUnpickler.read(attrView, names)
    case Absent =>
        Sync.defer(FileAttributes.default)
```
Prior to this commit (verified via `git show 98416eacf -- "*/ClasspathOrchestrator.scala"`), the
Attributes section was hardcoded to `FileAttributes.default`. The Phase 7 decode of `attrs.sourceFile`
required this to be real. The Attributes decode was correctly introduced here as a prerequisite for
the `sourceFile` field passed to `PositionsUnpickler.read`.

### ClassfileUnpickler wiring

**ClassfileUnpickler sets _position = Absent**
PASS. `ClassfileUnpickler.scala` (lines 80, 86, 97) sets `_position.set(Maybe.Absent)` for the
class symbol, all member symbols, and all type-param symbols. This satisfies Test 4.

### Tests

All 5 plan tests are present and correctly scoped:

| Plan test | Description | File:method |
|-----------|-------------|-------------|
| Test 1 | Fixture TASTy: class Foo at line 3, column 1 | PositionsUnpicklerTest "class at line 3 column 1 returns Present(Position(...))" |
| Test 2 | Empty section returns empty map without error | PositionsUnpicklerTest "empty payload returns empty map..." |
| Test 3 | Truncated section fails with MalformedSection("Positions", ...) | PositionsUnpicklerTest "truncated section produces MalformedSection error" |
| Test 4 | Java classfile symbol always Absent (tagged jvmOnly) | PositionsUnpicklerTest "Java classfile symbol always has position == Absent" |
| Test 5 | Two siblings have distinct line/column values | PositionsUnpicklerTest "two sibling definitions have distinct line/column values" |

Test 1 uses a carefully hand-crafted synthetic Positions payload with a 3-line file and an Assoc
entry encoding `class Foo` at line 3, column 1 (offset=17, lineStarts=[0,11,17]). The arithmetic
is documented inline. Test 5 encodes two sibling symbols with distinct offsets across two lines.

### Prohibited pattern scan

| Pattern | PositionsUnpickler.scala | PositionsUnpicklerTest.scala |
|---------|--------------------------|------------------------------|
| em-dashes (`—`) | PASS: none | PASS: none |
| `Frame.internal` | PASS: none | PASS: none |
| new `asInstanceOf` | PASS: none | PASS: none |
| new `AllowUnsafe` | PASS: none in unpickler; test does not need AllowUnsafe (no direct _position.set calls) | PASS |

### Regression check

Phase 6 data (commentsBySymbol, _scaladoc wiring) is preserved in the Phase 7 commit. The Phase 7
commit appends `positionsBySymbol` to `FileResult` and its wiring to `mergeResults` without altering
the Phase 6 loops. No regression.

---

## Findings

### WARN-1: IMPROVEMENT-ANALYSIS.md G2 states "G2 depends on G1" but plan overrides this
IMPROVEMENT-ANALYSIS.md line 161: "G2 depends on G1 because positions attach to tree nodes; without
the tree node addresses, there is nothing to map positions to."

The execution plan (lines 278-283) correctly scopes G2 to "definition-level positions only (the
address of the definition node in the TASTy tree)". Definition node addresses come from `addrMap`
produced by Pass 1, not from full tree body decode (G1). The plan explicitly documents this:
"G2 does NOT depend on G1 (tree body decode) for definition-level positions."

The implementation follows the plan scope and is correct. IMPROVEMENT-ANALYSIS.md's G2 entry
predated the plan refinement and is now stale for the "definition-level only" constraint. This
is a documentation consistency issue only; no code action needed.

### NOTE-1: Attributes section decode introduced here, not earlier
Per git history, `AttributeUnpickler` was imported and the `attrs <- sections.get(AttributesSection)`
decode was introduced in this Phase 7 commit. Prior phases used `FileAttributes.default` (a constant
with all fields false/Absent). This means the `isJava` flag (used by AstUnpickler to set JavaDefined
on symbols) was always false in Phase 1-6 for TASTy files. In practice, Java-TASTy files are rare
in test fixtures, so no visible test breakage occurred. The Attributes decode arriving here is
correct and complete going forward.

### NOTE-2: offsetToLineCol uses binary search (sound, minor complexity note)
The `offsetToLineCol` helper correctly implements a binary search over `lineStarts` to convert a
character offset to (line, column). The edge case of synthetic positions past the last line is
handled by returning `(numLines, offset - lastLineStart + 1)`. This is correct but the out-of-bounds
case is not directly tested. Not a correctness issue because TASTy positions are always within
source file bounds.

---

## Verdict

**0 BLOCKER. 1 WARN (stale IMPROVEMENT-ANALYSIS.md dependency note, documentation only). 2 NOTE (informational).**

Phase 7 is complete and correct. All plan requirements met. Attributes section decode is now live.
Safe to proceed to Phase 8.
