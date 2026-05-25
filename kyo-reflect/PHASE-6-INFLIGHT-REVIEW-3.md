# Phase 6 In-Flight Review (pulse 3 — new agent)

Pulse 3: 2026-05-24T00:00:00Z
Files reviewed:
- kyo-reflect/STEERING.md (full, lazy design section)
- kyo-reflect/PROGRESS.md (full)
- kyo-reflect/shared/src/main/scala/kyo/Reflect.scala lines 344-360
- kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala lines 1-380 (full)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReflectRuntime.scala (full, 84 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala (full, 881 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala lines 1-119
- kyo-reflect/shared/src/test/scala/kyo/ directory listing

## Git status summary

Only 3 files modified vs HEAD:
- kyo-reflect/PROGRESS.md (phase 6 now "pending")
- kyo-reflect/STEERING.md (lazy design section added)
- kyo-reflect/shared/src/main/scala/kyo/Reflect.scala (splice wired)

All new implementation files are UNTRACKED:
- kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala (new)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ directory (new: ReadsInstances.scala, ReflectRuntime.scala, TouchedFields.scala)

PHASE-6-IMPL-NOTES.md: ABSENT (agent did not create it)
ReadsDerivationTest.scala: ABSENT

## STEERING compliance (5 BLOCKING directives)

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | Macro splice `${ kyo.internal.ReflectMacro.derivedImpl[A] }` wired in Reflect.scala | COMPLIED | Reflect.scala:354: `inline def derived[A]: Reads[A] = ${ kyo.internal.ReflectMacro.derivedImpl[A] }` |
| 2 | ReadsDerivationTest.scala exists with all 18 tests, strict assertions | NOT_COMPLIED | File does not exist in kyo-reflect/shared/src/test/scala/kyo/. Directory listing shows no such file. |
| 3 | `asInstanceOf[Term]` at ReflectMacro.scala ~line 343 removed | COMPLIED | Grep for `asInstanceOf[Term]` returns zero hits. The paramss pattern now uses `Ref(paramss.head.head.symbol)` at line 319 (proper Symbol extraction from ValDef). |
| 4 | `asInstanceOf` at TouchedFields.scala:102 removed | COMPLIED | Grep for `asInstanceOf` in reads/ package returns zero hits. GotoAccum is now parameterized over `Q <: Quotes` with path-dependent `q.reflect.Tree` typed buffer. |
| 5 | Built-in given Reads instances exported from Reflect.Reads companion | COMPLIED | Reflect.scala:353: `object Reads extends kyo.internal.reflect.reads.ReadsInstances`. ReadsInstances is a trait mixin carrying nameReads, flagsReads, kindReads, typeReads, symbolReads, primitives, chunkReads, maybeReads, tuple2Reads..tuple22Reads. |

## Lazy design compliance

- readFieldsLazy signature: PRESENT — `kyo.internal.reflect.reads.ReflectRuntime.readFieldsLazy[A](sym, nonRecReaders, isRecSlot, isChunkSelf, self: => Reads[A], construct)` at ReflectRuntime.scala:26-37. Matches blessed design. Call-by-name `self: => Reflect.Reads[A]` used instead of plain `self: Reflect.Reads[A]`, which is a minor deviation from the blessed template (which wrote `instance` directly without `=>`); however CBN is safe and does not break the lazy semantics — it avoids forcing the lazy val prematurely. Functionally correct.

- ReflectMacro lazy emit pattern: PRESENT — emitLazyProduct at lines 181-239 emits exactly:
  ```
  '{
      lazy val instance: Reflect.Reads[A] = new Reflect.Reads[A]:
          ...
          def read(sym) = ReflectRuntime.readFieldsLazy(sym, _nonRecReaders, _isRecSlot, _isChunkSelf, instance, _ctor)
      instance
  }
  ```
  `instance` is referenced inside the same outer quote, hygienically valid. No cross-splice boundary name smuggling.

- 64-field cap: ABSENT — `buildProduct` calls `aSym.caseFields` and passes the list downstream without any `if caseFields.length > 64` guard and `report.errorAndAbort`. The bitmask slots would silently overflow for classes with more than 64 fields. Cap must be enforced at macro-expansion time (i.e., inside `buildProduct` before the bitmask construction, with a `report.errorAndAbort` for the recursive path).

- New asInstanceOf: One instance at ReflectMacro.scala:325 — `TypeApply(Select.unique(apply, "asInstanceOf"), List(TypeTree.of[t]))` inside `buildCtorFn`. This is NOT a raw Scala `asInstanceOf` cast; it is a quoted AST node being constructed (a compile-time `TypeApply` to lower Array[Any] elements to their field types in generated code). This is correct-by-construction and is distinct from the two violating casts the STEERING directives targeted. Comment at line 294 acknowledges this. No other new `asInstanceOf` found.

- New `null.asInstanceOf[T]`: ABSENT — grep returns zero hits across all of shared/src/main/scala/.

## Test progress

- 18 plan-mandated tests: 0/18 present (ReadsDerivationTest.scala does not exist)
- Weakened assertions: N/A (no test file)
- Tests currently passing: 0 plan-mandated Phase 6 tests

## CRITICAL (steer immediately)

1. **ReadsDerivationTest.scala ABSENT** (directive 2 NOT_COMPLIED). This is the most significant gap. Zero of the 18 required tests exist. The macro implementation is complete but entirely untested. Agent must create the file immediately with all 18 tests (product derivation, recursive Node/Tree case, built-in instance resolution, given override, touched-fields, sum-type compile-time guard, higher-kinded guard, hygiene). Tests must be strict (verify actual field values, not just "doesn't throw").

2. **64-field cap NOT enforced at macro expansion time** (lazy design requirement). `buildProduct` performs no `caseFields.length > 64` check before constructing bitmasks. A class with 65+ fields would silently produce incorrect bitmasks. Add: `if caseFields.length > 64 then report.errorAndAbort(s"Reflect.Reads.derived supports at most 64 fields; ${aSym.name} has ${caseFields.length}")` inside `buildProduct` before the field analysis loop.

3. **PHASE-6-IMPL-NOTES.md absent**. Agent was expected to write progress notes here. Minor operationally, but the supervisor relies on it for state transfer between pulses.

## MINOR (queue for post-commit audit)

- `readFieldsLazy` uses `self: => Reflect.Reads[A]` (call-by-name) whereas the blessed template showed `self: Reflect.Reads[A]`. The CBN form is strictly more correct (avoids forcing the lazy val before it is initialized in pathological call stacks), but it deviates from the literal blessed signature. Accept as an improvement; document in audit.

- `ReflectRuntime.loopLazy` at line 73-76 implements `isChunkSelf` slots by calling `sym.declarations.flatMap(...)` inside the runtime helper. The supervisor-blessed design shows the same pattern. Correct; noted for audit completeness.

- `booleanReads` and `intReads` and `longReads` in ReadsInstances return stub values (`false`, `0`, `0L`) rather than reading from the symbol. These are placeholder stubs that will likely produce wrong results if used in a derived product for Boolean/Int/Long fields. Acceptable for Phase 6 scope (these fields would fall through to the `Summon` path), but the stubs should be flagged in the Phase 6 audit.

- No `Reads[Frame]` / `Reads[using Frame]` propagation issue found; `read` method correctly takes `(using Frame)` in the trait definition (Reflect.scala:350).

## Recommendation: STEER: write ReadsDerivationTest.scala (18 tests, strict) + add 64-field cap; then ready to commit
