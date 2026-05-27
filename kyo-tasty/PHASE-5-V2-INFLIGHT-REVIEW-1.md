# Phase 5 v2 In-Flight Review 1 — G20: declaredType via Pass 1 eager member-type decode

**Date**: 2026-05-25  
**Reviewer**: Slot-B pulse agent  
**Compile result**: `[success]` (kyo-reflect/Test/compile, 3 s)

---

## Pattern Checklist

| Pattern | Verdict | Citation |
|---|---|---|
| `declaredType` stub replaced in `Reflect.scala` | CLEAN | Lines 259-271: guard on Package/unassigned home, then reads `_declaredType.isSet` / `.get()`, else `Abort.fail(NotImplemented(...))` |
| `_declaredType` `SingleAssign` field added to internal Symbol | CLEAN | `Reflect.scala` line 228: `private[kyo] val _declaredType: kyo.internal.reflect.symbol.SingleAssign[Type] = new ...` |
| AstUnpickler eagerly reads type annotation for ValDef/DefDef/TypeDef | CLEAN | Lines 211-268/324-386: VALDEF calls `decodeOneTypeIfPresent`; DEFDEF calls `readDefDefReturnType` (fresh sub-view skipping TYPEPARAM/PARAM nodes); type-level TYPEDEF decodes inline; TYPEPARAM and PARAM also decoded and stored |
| `mergeResults` assigns `_declaredType` after Phase C placeholder resolution | CLEAN | `ClasspathOrchestrator.scala` lines 273-288: iterates `fr.typeBySymbol`, guards with `!isSet`, then assigns Class/Trait/Object symbols `Type.Named(sym)`; comment cites "Phase 5 (G20)" |
| `ClassfileUnpickler` reads `FieldDescriptor` / `MethodDescriptor` | CLEAN | `buildOneMemberSymbol` now calls `pool.utf8(info.descriptorIdx)` then `resolveComponentType(pool, interner, home, path, descriptor, info.signatureIdx)` and returns `(sym, memberType)`; `buildMemberSymbols` return type changed to `Chunk[(Reflect.Symbol, Reflect.Type)]`; class symbol gets `Type.Named(classSymbol)` |
| 7 new tests added | FLAG | Zero new tests in `kyo-reflect/shared/src/test/`. `git diff HEAD -- kyo-reflect/shared/src/test/` is empty. `QueryApiTest.scala` and `AstUnpicklerTest.scala` have no occurrences of "declaredType". Plan requires 5 tests in `QueryApiTest` + 2 in `AstUnpicklerTest`. |
| Tests are strict (not tautological) | N/A | Tests absent |
| Compile passes | CLEAN | `[success] Total time: 3 s` |
| No new `asInstanceOf` | CLEAN | Pre-existing uses in `ReflectMacro.scala` and `Memo.scala` only; no new `asInstanceOf` in diff |
| No `Frame.internal` | CLEAN | None found |
| No em-dashes in modified files | CLEAN | grep returned no `—` characters |
| No new `null` / `var` | CLEAN | `null` / `var` usages in modified files are pre-existing (walk loop vars in AstUnpickler, owner sentinel in Reflect.scala) |
| No new `AllowUnsafe` without `// Unsafe:` | CLEAN | All new `AllowUnsafe` sites in ClassfileUnpickler line 68 and Reflect.scala line 266 are paired with `// Unsafe:` comments |

---

## Scope-Cutting Check: 7 Test Leaves

Per plan (execution-plan-v2.md lines 209-215):

| # | Test | File | Status |
|---|---|---|---|
| 1 | `sym.declaredType` for `val x: Int` returns `Type.Named(intSym)` | `QueryApiTest` | MISSING |
| 2 | `sym.declaredType` for `def add(x: Int, y: Int): Int` returns `Type.Function` | `QueryApiTest` | MISSING |
| 3 | `sym.declaredType` for `type Alias = String` returns `Type.Named(stringSym)` | `QueryApiTest` | MISSING |
| 4 | `sym.declaredType` for Java field `int[] values` returns `Type.Array(Type.Named(intSym))` | `QueryApiTest` | MISSING |
| 5 | `sym.declaredType` after classpath close returns `Abort.fail(ReflectError.ClasspathClosed)` | `QueryApiTest` | MISSING |
| 6 | `Pass1Result.typeBySymbol` for `def foo: String` contains entry for `foo` with `scala.String` type | `AstUnpicklerTest` | MISSING |
| 7 | `Pass1Result.typeBySymbol` for `class Foo[T](val x: T)` contains entry for `x` with `Type.Named(TSymbol)` | `AstUnpicklerTest` | MISSING |

All 7 tests are **MISSING**. Test files have no changes from HEAD.

---

## Summary

**Blocking issue: all 7 required tests are absent.** The implementation work across the four modified source files is substantively complete and correctly structured:

- `_declaredType` SingleAssign field is present on Symbol.
- `declaredType` accessor is no longer a stub; it reads from the field with proper Package / unassigned-home guards.
- Pass 1 eagerly populates `typeBySymbol` for VALDEF, DEFDEF (return type via fresh sub-view scan), type-level TYPEDEF, class-like TYPEDEF, TYPEPARAM, and PARAM.
- `mergeResults` applies `typeBySymbol` entries post-Phase-C and assigns `Type.Named(sym)` to Class/Trait/Object symbols.
- ClassfileUnpickler threads `memberType` from `resolveComponentType` (descriptor + Signature attribute) through `buildOneMemberSymbol` and `buildMemberList` as `(Symbol, Type)` pairs.
- Compile is clean.

The phase cannot be considered done until the 7 specified tests are written. No other issues found.
