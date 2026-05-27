# Phase 6 v2 Prep

Addresses G3 (Comments section reader) per `execution-plan-v2.md` lines 232-276.

---

## Pre-existing work to account for

Several plan deliverables are already in place from a prior partial implementation. The implementer must not re-do them, but must complete the missing wire-up:

**Already present:**
- `shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala` -- `object CommentsUnpickler` with `def read(view: ByteView, addrMap: Map[Int, Reflect.Symbol]): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError])` and private `readSync` plus `skipLongInt`.
- `Reflect.Symbol._scaladoc: kyo.internal.reflect.symbol.SingleAssign[Maybe[String]]` field (line 229 of `Reflect.scala`).
- `Reflect.Symbol.scaladoc: Maybe[String]` pure accessor (lines 252-256 of `Reflect.scala`).
- `FileResult.commentsBySymbol: Map[Reflect.Symbol, String]` field (line 53 of `ClasspathOrchestrator.scala`).
- `decodeTastyBytes` reads the Comments section and produces `commentsBySymbol` (lines 154-159 of `ClasspathOrchestrator.scala`). The section lookup pattern is:
  ```
  commentsBySymbol <- sections.get(TastyFormat.CommentsSection) match
      case Present((offset, length)) =>
          val commentsView = view.subView(offset, offset + length)
          CommentsUnpickler.read(commentsView, pass1Result.addrMap)
      case Absent =>
          Sync.defer(Map.empty[Reflect.Symbol, String])
  ```

**Missing (the Phase 6 implementation task):**
- `mergeResults` does not wire `_scaladoc`. No loop over `fr.commentsBySymbol` exists. `_scaladoc` is never set anywhere outside tests.
- `CommentsUnpicklerTest.scala` does not exist. No Comments-specific test file exists.

---

## Verbatim signatures

### Existing readSection helpers (SectionIndex)

```scala
// SectionIndex.scala line 21
def get(name: String): Maybe[(Int, Int)]
```

Section-lookup pattern (established, copy from existing callers):
```scala
sections.get(TastyFormat.CommentsSection) match
    case Present((offset, length)) => ...view.subView(offset, offset + length)...
    case Absent                    => Sync.defer(Map.empty)
```

### CommentsUnpickler

```scala
// CommentsUnpickler.scala line 33
def read(
    view: ByteView,
    addrMap: Map[Int, Reflect.Symbol]
)(using Frame): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError])
```

`readSync` (private): loops on `view.remaining > 0`, reads `addr = view.readNat()`, `textLen = view.readNat()`, raw `textLen` bytes into `Array[Byte]`, calls `skipLongInt(view)` to skip the span, then `addrMap.get(addr)` to find the symbol.

`skipLongInt` (private): reads bytes while `(b & 0x80) == 0`, stops on the byte with the stop bit set (TASTy signed LongInt encoding -- continuation bit is 0x80 CLEAR; stop on 0x80 SET). This is correct per the CommentsUnpickler.scala source.

### NameRef to Symbol resolution path

Already complete: `pass1Result.addrMap: Map[Int, Reflect.Symbol]` (produced by `AstUnpickler.runPass1`, line 144). No new resolver needed. `CommentsUnpickler.read` receives `pass1Result.addrMap` directly.

### Symbol.scaladoc accessor signature

```scala
// Reflect.scala line 252
def scaladoc: Maybe[String]
```

Pure (no effect). Reads from `_scaladoc` via `AllowUnsafe.embrace.danger`. Returns `Absent` if `_scaladoc` is not yet set. No classpath access after load.

---

## File:line anchors

| Location | File | Line |
|----------|------|------|
| Section index lookup (`ASTsSection`) | `ClasspathOrchestrator.scala` | 148 |
| Section lookup for Comments (already wired) | `ClasspathOrchestrator.scala` | 154-159 |
| `FileResult.commentsBySymbol` field | `ClasspathOrchestrator.scala` | 53 |
| `commentsBySymbol` passed into FileResult | `ClasspathOrchestrator.scala` | 172 |
| `mergeResults` start | `ClasspathOrchestrator.scala` | 185 |
| `mergeResults` _declaredType loop (insertion point: after this block) | `ClasspathOrchestrator.scala` | 273-296 |
| `Pass1Result.addrMap` field | `AstUnpickler.scala` | 60 |
| `pass1Result.addrMap` frozen at end of runPass1 | `AstUnpickler.scala` | 144 |
| `CommentsUnpickler.read` | `CommentsUnpickler.scala` | 33 |
| `_scaladoc` field on Symbol | `Reflect.scala` | 229 |
| `Symbol.scaladoc` pure accessor | `Reflect.scala` | 252 |

### CommentsUnpickler decode loop (existing source)

`CommentsUnpickler.scala` lines 47-67: `readSync` iterates `view.remaining > 0`, reads addr (LEB128 Nat), text length (LEB128 Nat), raw bytes (loop), calls `skipLongInt`, does `addrMap.get(addr)` to map to symbol, accumulates into `Map.newBuilder`.

### Pass1Result extension needed

`Pass1Result` already has `addrMap: Map[Int, Reflect.Symbol]` (line 60). No extension needed. The `addrMap` is already threaded into `FileResult` via `commentsBySymbol` (which is populated in `decodeTastyBytes`). The `addrMap` itself is not in `FileResult`; comments are already decoded from it into `commentsBySymbol`.

### mergeResults wiring (the gap)

Insert after the existing `_declaredType` assignment loops (after line 296), before the `stateRef.unsafe.get()` block (line 302):

```scala
// Phase 6 (G3): assign _scaladoc from Comments section decode.
// Unsafe: SingleAssign.set() is an unsafe-tier helper; AllowUnsafe.embrace.danger is already in scope above.
for fr <- fileResults do
    for (sym, text) <- fr.commentsBySymbol do
        if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe(text))
    end for
end for
// Symbols without a comment entry keep _scaladoc unset; scaladoc accessor returns Absent for them.
```

---

## TASTy comment format

**Section name constant:** `TastyFormat.CommentsSection = "Comments"` (`TastyFormat.scala` line 31).

**Section location:** Located via `sections.get(TastyFormat.CommentsSection)` which returns `Present((offset, length))` or `Absent`. Payload begins at `offset`, byte count is `length`.

**Entry byte layout** (from dotty `CommentUnpickler.scala`):
```
CommentsSection = Entry*
Entry           = Addr Utf8 LongInt
Addr            = Nat           -- LEB128 unsigned, byte offset into the ASTs section payload
                                -- matches keys in addrMap (populated by AstUnpickler)
Utf8            = Nat Byte*     -- LEB128 unsigned length, then exactly `length` raw UTF-8 bytes
LongInt         = Byte*         -- signed big-endian base-128 span info; stop bit 0x80 SET
                                -- CommentsUnpickler.skipLongInt reads until stop bit found
```

**NameRef vs addr**: The Comments section does NOT use NameRef values (indexes into the name table). It uses `Addr` values: raw byte offsets into the ASTs section payload. These match the `addrMap` keys populated by `AstUnpickler.runPass1` at lines 192, 225, 242, 305, 346 (wherever `addrMap(nodeAddr) = sym` is called).

**UTF-8 decode**: Raw bytes decoded via `new String(buf, java.nio.charset.StandardCharsets.UTF_8)` (already in CommentsUnpickler.scala line 62).

**LongInt span skip**: The `skipLongInt` logic in CommentsUnpickler reads until `(b & 0x80) != 0` (stop bit set). Note the polarity: TASTy LongInt uses 0x80 SET as the stop bit, opposite of LEB128 Nat (where 0x80 CLEAR is stop). Verify this against the existing `skipLongInt` implementation -- in the committed source, loop condition is `while (b & 0x80) == 0 do` (line 72), meaning it keeps reading while stop-bit is CLEAR, stops when stop-bit is SET. This is correct.

**Section absent in some TASTy files**: Older TASTy files (pre-Scala 3.2) may not have a Comments section. `decodeTastyBytes` already handles this: the `Absent` branch returns `Sync.defer(Map.empty[Reflect.Symbol, String])` (line 159). No special handling needed in mergeResults.

---

## Fixture concern

**Finding**: None of the classes in `kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala` have scaladoc comments. `BaseClass.scala` and `ChildClass.scala` have scaladoc (`/** Base class for cross-file inheritance... */` and `/** Child class for cross-file inheritance... */`), but those comments describe test infrastructure, not the scaladoc under test.

**Recommendation**: Add a new fixture file `kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/DocumentedClass.scala` with:

```scala
package kyo.fixtures

/** A documented class for Phase 6 CommentsUnpickler tests. */
class DocumentedClass

/** A documented trait for Phase 6 CommentsUnpickler tests. */
trait DocumentedTrait:
    /** A documented method. */
    def compute: Int
end DocumentedTrait

// Intentionally undocumented sibling: no scaladoc
class UndocumentedClass
```

Then generate the corresponding embedded TASTy bytes in `Embedded.scala` (following the same pattern as `plainClassTasty`, etc.) and add `def documentedClassTasty: Array[Byte]` to `Embedded`. Tests can load `kyo.fixtures.Embedded.documentedClassTasty` into `MemoryFileSource`.

**Alternative (using BaseClass/ChildClass)**: Tests could use `kyo.fixtures.Embedded.baseClassTasty` or `kyo.fixtures.Embedded.childClassTasty` -- those symbols will have scaladoc present in the Comments section because their Scala source has `/** ... */`. However, the exact text contains "for cross-file inheritance fixtures used by kyo-reflect Phase 2..." which is verbose and fragile if those files change. A dedicated fixture with a controlled short string ("A documented class for Phase 6 CommentsUnpickler tests.") is safer.

**Decision for implementer**: If adding a new fixture is out of scope for a single phase, use the existing `baseClassTasty` embedded bytes and assert `sym.scaladoc.isDefined` (not exact-text match). If exact-text match is desired, add `DocumentedClass.scala`.

---

## Test specifications (6 tests)

All tests go in a new file `kyo-reflect/shared/src/test/scala/kyo/CommentsUnpicklerTest.scala`.

**Test 1** (plan line 256): Load a TASTy file with a documented definition. Assert that `CommentsUnpickler.read` returns a non-empty map with an entry whose value contains the expected scaladoc text.
- Concrete: load `baseClassTasty` into `MemoryFileSource`; run `AstUnpickler.readPass1` to get `addrMap`; call `CommentsUnpickler.read(view, addrMap)` on the Comments section payload; assert result is non-empty and at least one value contains "Base class".
- **Concern**: This test exercises `CommentsUnpickler` directly, not via `Classpath`. The test must manually parse the TASTy header, name table, and section index to locate the Comments section, then call `CommentsUnpickler.read`. Follow the pattern from `AstUnpicklerTest.scala`.

**Test 2** (plan line 257): A TASTy file with no Comments section (or absent section) returns an empty map without error.
- Concrete: Use `plainClassTasty` which may or may not have a Comments section (the PlainClass in `FixtureClasses.scala` has no scaladoc, but the TASTy file may still contain an empty Comments section because the Scala compiler includes the section header regardless). If the section is absent: create a minimal TASTy byte sequence with no Comments section and feed it. Simplest: pass an empty `ByteView` (zero remaining) to `CommentsUnpickler.read` with an empty `addrMap`; assert the result is an empty map.

**Test 3** (plan line 258): Malformed Comments section (truncated mid-entry) produces `Abort.fail(ReflectError.MalformedSection("Comments", ...))`.
- Concrete: construct a byte array that begins a valid entry (write a valid `Addr` Nat and partial `Utf8` Nat) but truncates before the text bytes. Call `CommentsUnpickler.read` directly with this view and any `addrMap`. Assert `Abort.run[ReflectError](...).map { case Result.Failure(ReflectError.MalformedSection("Comments", _)) => succeed }`.

**Test 4** (plan line 259): Integration test via `Classpath`. Load `baseClassTasty`; open classpath; call `cp.findClass("kyo.fixtures.BaseClass")`; assert `sym.scaladoc == Present(...)` for the documented class, `sym.scaladoc == Absent` for symbols without comments.
- This is the test that exercises the full pipeline including `mergeResults` wiring.
- Depends on the `_scaladoc` wiring in `mergeResults` being implemented.

**Test 5** (plan line 260): Two sibling definitions in the same file have independently correct scaladoc.
- Concrete: Use `documentedClassTasty` (or construct a minimal TASTy with two documented definitions). After `CommentsUnpickler.read`, verify the resulting map has entries for both symbols and the text values are independent (no cross-contamination).
- If only one documented fixture is available, use `BaseClass` (`/** Base class ... */`) and confirm the map has exactly one entry for the class symbol.

**Test 6** (plan line 261): Java-sourced classfile symbol has `sym.scaladoc == Absent`.
- Concrete: Open a classpath containing `arrayRecordClass` (already embedded in `Embedded`). Find `ArrayRecord` class symbol. Assert `sym.scaladoc == Absent` (classfiles have no Comments section; `_scaladoc` is never set for classfile symbols; the `scaladoc` accessor returns `Absent` for unset slots).

---

## Edge cases

- **Symbol without scaladoc**: `_scaladoc` slot is never set; `scaladoc` accessor returns `Absent` unconditionally (line 255: `if _scaladoc.isSet then _scaladoc.get() else Maybe.Absent`). No action required in Phase 6.
- **Classfile path**: Classfiles have no Comments section. The classfile reader (`ClassfileUnpickler`) never sets `_scaladoc`. `sym.scaladoc` returns `Absent` for all classfile-sourced symbols.
- **Comments section absent in some TASTy files**: Scala 3 does emit a Comments section even for files with no scaladoc (the section is empty but present). Older TASTy format versions may omit it. The `decodeTastyBytes` `Absent` branch (line 159) handles this by producing `Map.empty`.
- **Addr collision**: The same `addrMap` key should appear at most once per file. If a TASTy file somehow encodes two Comment entries for the same address, `readSync` will overwrite the first with the second (Map.newBuilder behavior). This is a degenerate input; no special handling needed.
- **Empty scaladoc string**: If a definition has `/** */` (empty scaladoc body), the compiler may emit a zero-length UTF-8 entry. `Maybe("")` (a Present with empty string) is correct; callers can distinguish from `Absent`.

---

## Anti-flakiness deltas

- Tests 1, 4, 5, 6 load fixture TASTy bytes from `kyo.fixtures.Embedded` (compile-time constants). No file I/O, no port binding, no timing dependency.
- Tests 2 and 3 use hand-crafted byte arrays -- no external dependencies.
- No `Async.timeout` or `Async.delay` needed anywhere in this test file (all operations are `Sync & Abort[ReflectError]`, no `Async` in scope).
- All tests use `Scope.run` + `Abort.run[ReflectError]` for effect handling, matching the pattern in `QueryApiTest.scala`.
- Run command: `sbt 'project kyo-reflect; testOnly kyo.CommentsUnpicklerTest'`.

---

## Concerns

**Concern 1 (BLOCKER if unresolved before implementation)**: `mergeResults` runs inside `Sync.defer` (line 189: `Sync.defer:`). The `import AllowUnsafe.embrace.danger` for `SingleAssign.set()` is already present inside `mergeResults` (line 238: `import AllowUnsafe.embrace.danger`). The `_scaladoc` wiring loop must be placed after line 296 (end of `_declaredType` loop) and before line 302 (`cp.stateRef.unsafe.get()`). The existing `import AllowUnsafe.embrace.danger` at line 238 covers the entire `Sync.defer` block, so no second import is needed for the `_scaladoc` loop.

**Concern 2 (WARN)**: `decodeTastyBytes` currently reads `FileAttributes.default` (line 147: `attrs = FileAttributes.default`) instead of actually decoding the Attributes section. This means `isJava` on TASTy symbols is always false from the attributes side (it's set by the classfile reader via `Flag.JavaDefined`). This predates Phase 6 and is a pre-existing gap. No action for Phase 6.

**Concern 3 (WARN)**: Test 1 / Test 5 require fixture bytes whose Comments section contains actual scaladoc. `BaseClass.tasty` and `ChildClass.tasty` are generated from `BaseClass.scala` and `ChildClass.scala` which both have `/** ... */` comments. However, the embedded bytes in `Embedded.scala` were generated at a specific time and will not change if the fixture source changes. If the fixture is regenerated (e.g., for a different Scala minor version), the addresses in the Comments section will shift. The tests should assert on `sym.scaladoc.isDefined` and `sym.scaladoc.map(_.contains("Base class")) == Present(true)` rather than exact-equality on the full string, to tolerate minor compiler variation in how the scaladoc text is stored.

**Concern 4 (NOTE)**: The plan specifies `Symbol.comment` as the accessor name (execution-plan-v2.md line 243), but the implemented accessor is `Symbol.scaladoc` (Reflect.scala line 252). The field is `_scaladoc: SingleAssign[Maybe[String]]`. Phase 6 implementation should use `scaladoc` as the name, consistent with the existing committed code.

**Concern 5 (NOTE)**: The plan's Test 1 description says `CommentsUnpicklerTest` (new), but the plan also says to populate `_scaladoc` in `ClasspathOrchestrator.decodeTastyBytes`. The committed code already populates `commentsBySymbol` in `decodeTastyBytes` but does not write `_scaladoc` in `mergeResults`. Test 4 (the integration test via `Classpath`) is the one that will fail until `mergeResults` is updated. Tests 1-3 and 5 exercise `CommentsUnpickler` directly and may pass without the `mergeResults` fix.
