# Phase 8 Audit: G1 Tree Body Decode

**Commit**: `e08e70478539a0ff6f170a7b0b139f4f0f2dc7b3`
**Plan reference**: execution-plan-v2.md lines 323-373
**Status**: PASS with caveats (see WARNs below)

---

## Checklist Results

### Tree ADT

**PASS** -- `Reflect.scala` line 228 defines `sealed trait Tree` with all 20 plan-listed cases plus two extras:
`NamedArg` (line 319) and `Annotated` (line 322) are present but not listed in the plan spec. Both are
legitimate TASTy tags; their presence is correct. `Unknown` (line 325) is present as a catch-all. Total
in file: 23 named cases + `Unknown`.

All plan-required cases verified present:
`Ident`, `Select`, `Apply`, `TypeApply`, `Block`, `If`, `Match`, `CaseDef`, `Literal`, `New`, `Assign`,
`Return`, `Throw`, `Lambda`, `Typed`, `Inlined`, `Try`, `While`, `Bind`, `Alternative`, `Unapply`,
`ValDef`, `DefDef`, `TypeDef`, `PackageDef`, `ClassDef`, `Template`, `Super`, `This`.

### Symbol.body accessor

**PASS** -- `Reflect.scala` line 517:
```scala
def body(using Frame): Tree < (Sync & Abort[ReflectError])
```
Dispatches on `origin`:
- `JavaOrigin` -> `Abort.fail(ReflectError.NotImplemented("body not available for Java symbols"))` (line 521)
- `TastyOrigin` with `bodyStart == 0` or `kind == SymbolKind.Package` -> `Abort.fail(ReflectError.NotImplemented(...))` (line 526-527)
- `TastyOrigin` open classpath with valid slice -> decodes via `_bodyMemo.get()` (line 535)
- After close -> `checkOpen` fails with `ReflectError.ClasspathClosed` (line 524)

### TreeUnpickler.scala

**PASS** -- Present at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TreeUnpickler.scala`,
exactly 927 lines (matches plan target of ~927 LOC).

### Memo caching (Test 9)

**PASS** -- `_bodyMemo` field defined at `Reflect.scala` line 355:
```scala
private[kyo] val _bodyMemo: kyo.internal.reflect.symbol.Memo[Tree] =
    new kyo.internal.reflect.symbol.Memo[Tree](...)
```
Test 9 in `TreeUnpicklerTest.scala` line 350 asserts `tree1 eq tree2` via `_bodyMemo.get()` called twice
on the same symbol. Correct reference-equality check.

### JavaOrigin returns NotImplemented

**PASS** -- Handled in `body` accessor (line 520-521) and explicitly tested in Test 6 (`TreeUnpicklerTest.scala`
line 256). Test verifies the `NotImplemented` message contains "Java".

### Top-level Package returns NotImplemented

**PASS (partial)** -- The `body` accessor guards `kind == SymbolKind.Package` at line 526 and returns
`NotImplemented("body not available for this symbol kind")`. However, there is no dedicated test case
for a Package-kind symbol specifically returning `NotImplemented`. Test 5 (recursive / stack-overflow
safety) uses `fixtureClassesPackageTasty` but calls `TreeUnpickler.decodeSync` directly on all symbols
with `bodyStart > 0`, so Package-kind symbols (which have `bodyStart == 0` in practice) are filtered out
before the guard is reached.

### ClasspathClosed post-close

**PASS** -- Test 7 (`TreeUnpicklerTest.scala` line 275) calls `sym.body` after `Scope.run` exits and
verifies `Result.Failure(ReflectError.ClasspathClosed)`. Also accepts `NotImplemented` as a fallback
when the symbol is not found (lines 290, 300), which is a pragmatic test-fixture limitation, not a logic
gap.

### TastyOrigin retrofit with sectionOffset

**PASS** -- `AstUnpickler.scala` captures `sectionOffset` at line 127 (`val sectionOffset = view.position`)
and threads it into every `new TastyOrigin(...)` call at lines 217, 235, 260, 277, 290, 343, 358, 387,
406, 424. The `TastyOrigin` class stores it as `val sectionOffset: Int` (`Reflect.scala` line 655).

### SHAREDtype absolute-address fix

**PASS** -- `TreeUnpickler.scala` computes `val absRef = ctx.sectionOffset + astRef` at line 257 for
`SHAREDtype` back-references. `TypeUnpickler.scala` (the embedded type reader in `TreeTypeSession`) does
the same at line 119 (`val absAddr = session.sectionOffset + addr`). Design doc comment at `TreeUnpickler.scala`
line 16 explains: "each decode session uses an independent treeAddrCache keyed by absolute byte address."

### 9 plan tests present and strict

**WARN** (minor) -- 9 tests are present and labeled "Test 1" through "Test 9". However:

- **Test 1** (plan: `decodes to Block(Nil, Literal(IntConst(42))) or equivalent single-expression form`):
  The implementation uses `findLiteral` to search the full tree recursively for `IntConst(42)`, rather
  than asserting the top-level shape. This is weaker than the plan's "or equivalent single-expression
  form" phrasing, but acceptable given that `SomeObject.value = 42` in a Scala object may be wrapped in
  additional `Typed` or `Inlined` nodes by the Scala compiler. The test would not catch a decode that
  returned, say, `Literal(IntConst(0))` buried inside an unrelated tree. Functionally adequate, but the
  plan's intent was a shape-precise check. Mark as WARN.

- **Test 2** (plan: `x + 1 body decodes to Block containing Apply of + with Ident("x") and Literal(IntConst(1))`):
  The test only checks that decoded bodies are non-null. It does not assert `Apply`/`Ident` shape. Mark
  as WARN.

- Tests 3-9: Tests 3/4 check structural properties (If has 3 subtrees, Match has CaseDefs). Test 5 is
  pure no-crash. Tests 6/7/8/9 are all strict (error type, reference equality).

### No em-dashes

**PASS** -- Python UTF-8 scan of `TreeUnpickler.scala` and `Reflect.scala` finds no U+2014 em-dash
character. Box-drawing characters (`─`) used in comment banners are acceptable.

### No Frame.internal

**PASS** -- `grep Frame.internal` returns no results in `TreeUnpickler.scala`.

### No new asInstanceOf in macro source

**PASS (existing only)** -- `ReflectMacro.scala` references `asInstanceOf` at lines 13 (comment), 320
(comment), and 351 (macro-generated `TypeApply` for cast). These are all pre-existing. No new casts
added by Phase 8. The cast at line 351 is inside a macro building a `TypeApply` expression in quoted
code, which is correct by construction per the comment.

### No new AllowUnsafe

**WARN** -- `TreeUnpickler.scala` line 36 contains `import AllowUnsafe.embrace.danger` in `decodeSync`.
This is a synchronous helper called from within the `_bodyMemo` init lambda, which is itself wrapped in
`AllowUnsafe` at the `Symbol.body` call site in `Reflect.scala` (line 534). The safety note is: the
`AllowUnsafe` import in `TreeUnpickler.decodeSync` makes that method callable from any context without
a `Frame`, bypassing the safe API layer. Per `feedback_no_unsafe.md`, `AllowUnsafe` is permitted only
for justified bridging. Here the justification is that `Memo`'s init lambda is synchronous and the
danger is embraced at the `body` accessor boundary. The comment "// Unsafe: AllowUnsafe is needed..."
is present. This is architecturally sound but worth noting since `decodeSync` is `private[kyo]` (not
fully private), meaning test code can call it without going through the safe `body` accessor.

---

## Summary

| Check | Status |
|---|---|
| Tree sealed trait with ~30 cases | PASS |
| Symbol.body accessor with Memo lazy decode | PASS |
| TreeUnpickler.scala ~927 LOC | PASS |
| Memo caching: Test 9 reference equality | PASS |
| JavaOrigin returns NotImplemented | PASS |
| Top-level Package returns NotImplemented (guarded) | PASS |
| Package-specific test | WARN: no dedicated test for Package-kind body |
| ClasspathClosed post-close | PASS |
| TastyOrigin retrofit with sectionOffset | PASS |
| SHAREDtype absolute-address fix | PASS |
| 9 plan tests present | PASS |
| Test 1 strictness (exact shape) | WARN: relaxed shape check |
| Test 2 strictness (Apply/Ident) | WARN: non-null only |
| No em-dashes | PASS |
| No Frame.internal | PASS |
| No new asInstanceOf in macro source | PASS |
| AllowUnsafe in decodeSync | WARN: justified bridging, but private[kyo] widens blast radius |

## BLOCKERs

None.

## WARNs

1. **Test 1 shape not pinned**: Plan specifies `Block(Nil, Literal(IntConst(42)))` or equivalent. Actual
   test uses a recursive `findLiteral` search. Would not catch a structurally incorrect top-level wrap.
   Acceptable for now because TASTy wrapping varies; consider tightening to assert the top-level is one
   of `{Literal, Block(_,Literal), Typed(_,Literal), Inlined(_,_,Literal)}`.

2. **Test 2 shape not pinned**: Plan specifies `Apply` of `+` with `Ident("x")` and `Literal(IntConst(1))`.
   Actual test only checks non-null. Consider adding an `Apply` presence check.

3. **No dedicated Package-kind test**: The `NotImplemented` guard for `kind == SymbolKind.Package` is
   present in production code but not covered by a standalone test. Test 5 touches Package-flavored TASTy
   but filters Package symbols out before the guard fires.

4. **`decodeSync` is `private[kyo]`**: The safe `body` accessor is the intended entry point. `decodeSync`
   being `private[kyo]` allows test code to bypass `ClasspathClosed` checks and `Memo` caching. If test
   code calls `decodeSync` after close it will not produce `ClasspathClosed`. This is a test-correctness
   concern, not a production safety issue.

## NOTEs

- The `AllowUnsafe` inside `decodeSync` has a comment and is properly scoped; no action required.
- `NamedArg` and `Annotated` tree cases are not in the plan list but are correct additions for TASTy
  completeness.
- The `Unknown(tag, length)` catch-all case is a good forward-compatibility measure.
- `TreeTypeSession` in `TreeUnpickler.scala` uses its own `sectionOffset` thread for type re-decode on
  `SHAREDtype` cache miss -- this dual-session approach is correct and the comments explain it.
