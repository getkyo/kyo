# Phase 2 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00:00Z
Files reviewed:
- `execution-plan.md` lines 127-189 (Phase 2 plan)
- `PHASE-2-PREP.md` (full)
- `STEERING.md` (full)
- `shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` (all)
- `shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala` (all)
- `shared/src/main/scala/kyo/internal/reflect/symbol/SingleAssign.scala` (all)
- `shared/src/main/scala/kyo/internal/reflect/tasty/NameUnpickler.scala` (all)
- `shared/src/main/scala/kyo/internal/reflect/tasty/AttributeUnpickler.scala` (all)
- `shared/src/main/scala/kyo/internal/reflect/tasty/SectionIndex.scala` (all)
- `shared/src/main/scala/kyo/Reflect.scala` (all)
- `shared/src/test/scala/kyo/InternerTest.scala` (all)
- `shared/src/test/scala/kyo/NameUnpicklerTest.scala` (all)
- `shared/src/test/scala/kyo/AttributeUnpicklerTest.scala` (all)

---

## Plan anchor

### Files to produce (plan lines 132-141)

| File | Present in dirty tree |
|------|----------------------|
| `symbol/Interner.scala` | YES (untracked) |
| `symbol/Memo.scala` | YES (untracked) |
| `symbol/SingleAssign.scala` | YES (untracked) |
| `tasty/NameUnpickler.scala` | YES (untracked) |
| `tasty/AttributeUnpickler.scala` | YES (untracked) |
| `tasty/SectionIndex.scala` | YES (untracked) |
| `test/kyo/NameUnpicklerTest.scala` | YES (untracked) |
| `test/kyo/InternerTest.scala` | YES (untracked) |
| `test/kyo/AttributeUnpicklerTest.scala` | YES (untracked) |

### Files to modify (plan lines 142-151)

| File | Status |
|------|--------|
| `kyo/Reflect.scala` -- `opaque type Name` change | YES (modified, `opaque type Name = Interner.Entry`) |
| `tasty/TastyFormat.scala` -- attribute tag constants | YES (modified, constants present at lines 211-225) |

### Test count

Plan specifies 15 tests. Actual counts: InternerTest=6, NameUnpicklerTest=5, AttributeUnpicklerTest=4. Total = 15. MATCHES.

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | UNKNOWN -- no sbt output found in dirty tree; tests not yet run | No compile/test logs present |
| Compile-only "success" claim | NOT PRESENT -- no claim of passing, dirty tree only | N/A |
| Priority inference (item skipped as "low priority") | CLEAN -- all 9 source files + 3 test files present | Full file list delivered |
| Scope substitution ("simpler equivalent") | FLAG (CRITICAL) -- NameRefs are implemented as 0-based throughout, while PREP doc line 225 states "NameRefs are 1-based indices...NameRef 0 is invalid. Entry at index `ref - 1`". See CRITICAL section. | NameUnpickler.scala line 34, SectionIndex.scala line 16 |
| Foreach-discards-assert in tests | CLEAN -- all test bodies use `assert(...)` directly; no `foreach` discarding | All 15 tests inspected |
| Stale-state / tautological matchers | FLAG (MINOR) -- InternerTest test 3 asserts `!(na eq nb)` which is trivially true for any two distinct objects; it does NOT verify they actually fall into different shards | InternerTest.scala lines 31-42 |
| **Name table byte-count delimiter honored** (STEERING active directive) | CLEAN -- NameUnpickler.scala lines 57-60: reads `nameTableByteCount = view.readNat()`, sets `nameTableEnd = view.position + nameTableByteCount`, loops `while view.position < nameTableEnd`. Byte-count-delimited, NOT entry-count-delimited. | NameUnpickler.scala lines 57-60 |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan exactly | FLAG (CRITICAL) -- Plan line 137 specifies `SectionIndex.read(view: ByteView): SectionIndex < Abort[ReflectError]` (no `names` param). PREP-doc Concern 4 (page 693) flags this as incomplete and says the supervisor must add `names: Array[Reflect.Name]`. The implementation uses the corrected signature `read(view: ByteView, names: Array[Reflect.Name])`. This is the RIGHT fix but it is a deliberate deviation from the plan spec. See MINOR notes. | SectionIndex.scala line 37 vs plan line 137 |
| No off-plan architecture substitution | CLEAN -- all components follow the plan's described architecture | -- |
| No cross-cutting refactor outside Phase 2 scope | CLEAN -- only Phase 2 files created/modified; Phase 1 files untouched | git status |
| Internal helpers stay in `kyo.internal.reflect.*` | CLEAN -- Interner, Memo, SingleAssign in `kyo.internal.reflect.symbol`; NameUnpickler, AttributeUnpickler, SectionIndex in `kyo.internal.reflect.tasty` | File paths confirmed |
| Sharded interner: 32 segments per plan, not "simpler N=1 for now" | CLEAN -- `Interner(numShards: Int = 32)` default; constructor enforces power-of-2 | Interner.scala lines 16-17 |

---

## Scope-cutting checks (per plan-mandated test leaf, all 15)

| Leaf | Status | Notes |
|---|---|---|
| 1: `InternerTest` -- same bytes return ref-equal Name instances | PRESENT_STRICT | Lines 12-18, `assert(n1 eq n2)` |
| 2: `InternerTest` -- different bytes return non-equal instances | PRESENT_STRICT | Lines 21-28, `assert(!(n1 eq n2))` |
| 3: `InternerTest` -- intern from different shards produces distinct entries | PRESENT_WEAKENED | Lines 31-42: uses "alpha"/"beta" which are NOT guaranteed different shards with 32 shards; assertion only checks `!(na eq nb)` and `na.string.get() != nb.string.get()` -- these are trivially true for ANY two distinct strings; the test does NOT verify shard separation; accessing `na.string` is also an `Interner.Entry` field exposed directly (not via `Name.asString`) |
| 4: `InternerTest` -- `Name.asString` returns correct UTF-8 string | PRESENT_STRICT | Lines 44-53, `assert(name.asString == s)` |
| 5: `InternerTest` -- `Name.asString` twice returns ref-equal `String` (Memo) | PRESENT_STRICT | Lines 55-63, `assert(s1 eq s2)` |
| 6: `InternerTest` -- `CanEqual[Name, Name]` holds | PRESENT_STRICT | Lines 66-74, `assert(n1 == n2)` |
| 7: `NameUnpicklerTest` -- fixture Names section present and non-empty | PRESENT_WEAKENED | Lines 43-61: asserts `names.nonEmpty` AND `names.length == 29` (hardcoded). The hardcoded 29 is reasonable only if the fixture was pre-analyzed and the count verified. However it is fragile -- any change to FixtureClasses.scala would silently break the assertion. Low severity but noteworthy. |
| 8: `NameUnpicklerTest` -- fixture top-level class name found | PRESENT_STRICT | Lines 63-81, `assert(names.exists(_.asString == "PlainClass"))` |
| 9: `NameUnpicklerTest` -- QUALIFIED name decodes to dotted string | PRESENT_WEAKENED | Lines 83-106: only asserts `qualified.isDefined` and `qualified.get.asString.contains(".")`. Does NOT assert the specific string value "kyo.fixtures" even though the comment says it knows the exact entry. Mild weakening. |
| 10: `NameUnpicklerTest` -- corrupt/truncated section produces `MalformedSection("Names", ...)` | PRESENT_STRICT | Lines 108-131, matches `ReflectError.MalformedSection("Names", _)` |
| 11: `NameUnpicklerTest` -- all decoded names are interned (same bytes = ref-equal) | FLAG (CRITICAL TYPE MISMATCH) | Lines 133-155: `n2 = interner.intern(b2, 0, b2.length)` returns `Interner.Entry`; `n1 = names.find(_.asString == "PlainClass").get` returns `Reflect.Name` (which is `opaque type Name = Interner.Entry`). `assert(n1 eq n2)` compares `Reflect.Name` (opaque) with `Interner.Entry` (raw). In Scala, `eq` on an opaque type compares the underlying value. Since `Reflect.Name` is `opaque type Name = Interner.Entry` and both sides are the same `Interner.Entry` object after interning, this SHOULD compile and pass at runtime. However, the type of `n1` is `Reflect.Name` and `n2` is `Interner.Entry` -- this will NOT compile without a `.asInstanceOf` or an implicit conversion because `eq` is `AnyRef.eq` and the types are different at the Scala level (opaque vs concrete). This is likely a compile error. Needs verification. |
| 12: `AttributeUnpicklerTest` -- no Attributes section returns `FileAttributes.default` | PRESENT_STRICT | Lines 12-31, asserts all flags false and `sourceFile == Absent` |
| 13: `AttributeUnpicklerTest` -- synthesized `isJava = true` | PRESENT_STRICT | Lines 33-56, asserts `fa.isJava` and `fa.explicitNulls` |
| 14: `AttributeUnpicklerTest` -- synthesized `explicitNulls = true` | PRESENT_STRICT | Lines 58-75, asserts `fa.explicitNulls` |
| 15: `AttributeUnpicklerTest` -- `sourceFile` decoded as `Present("Foo.scala")` | FLAG (CRITICAL NameRef BASE MISMATCH) | Lines 77-102: test constructs `names = Array(entry)` at index 0, then passes NAT `0 | 0x80 = 0x80` (NameRef=0, 0-based). AttributeUnpickler.scala line 92 does `names(nameRef).asString` with `nameRef=0` -- this is 0-based. PREP doc line 363 says "Utf8Ref (1-based)" but the test and implementation both use 0-based. This is internally consistent between test and impl, but INCONSISTENT with PREP doc / dotty spec (which says NameRef=1 is the first entry). The correct encoding per spec should be `1 | 0x80 = 0x81` for the first entry, and `names(nameRef - 1)`. Either the spec and the test/impl all need to align on 0-based (a conscious deviation) or on 1-based (per spec). Currently: test uses 0-based, impl uses 0-based, PREP says 1-based. |

---

## CRITICAL (steer immediately)

1. **NameRef indexing is 0-based throughout but PREP doc specifies 1-based.** PREP doc line 225: "NameRefs are 1-based indices...NameRef 0 is invalid. Entry at index `ref - 1`." The NameUnpickler, SectionIndex, and AttributeUnpickler all use 0-based indexing (`buf(prefix)`, `names(nameRef)` directly). The test 15 fixture also uses NameRef=0 with a 0-based names array. This is either a deliberate deviation from the dotty spec (which IS 1-based) or an error that will misparse real TASTy files. **Must verify against real PlainClass.tasty to determine which is correct.** If dotty emits 1-based NameRefs, the implementation will produce wrong results on real files. The NameUnpicklerTest test 7 assertion `names.length == 29` and test 8 finding "PlainClass" will catch this at runtime but only if the fixture was decoded with 0-based refs and happens to match. The comment at NameUnpickler.scala line 31 says "0-based indices" but this contradicts the verbatim spec excerpt in PREP doc. **File: NameUnpickler.scala line 31 + 71,72,74,78,79,81; SectionIndex.scala lines 16,51,54; AttributeUnpickler.scala lines 34,91-92; NameUnpicklerTest.scala line 85-89; PREP doc line 225. Steer: choose one base, document it, verify against real PlainClass.tasty header dump.**

2. **Test 11 type mismatch: `n1: Reflect.Name` eq `n2: Interner.Entry`.** NameUnpicklerTest.scala lines 147-149: `n2 = interner.intern(...)` is `Interner.Entry`; `n1 = names.find(...).get` is `Reflect.Name` (opaque = `Interner.Entry`). `assert(n1 eq n2)` may not compile because `eq` requires `AnyRef` and opaque types do not auto-coerce. Fix: either `val n1: Interner.Entry = ...` using a package-private unwrap, or `Reflect.Name.wrap(n2)` to make both sides `Reflect.Name`. **File: NameUnpicklerTest.scala line 148-149.**

---

## MINOR (queue for post-commit audit)

1. **SectionIndex.read signature deviation from plan.** Plan line 137 specifies `SectionIndex.read(view: ByteView): SectionIndex < Abort[ReflectError]`. PREP doc Concern 4 correctly identifies this as incomplete. The implementation uses the corrected signature `read(view: ByteView, names: Array[Reflect.Name])` which is right. The plan text is stale. No action needed in Phase 2 code; update plan annotation or proceed as-is.

2. **InternerTest test 3 does NOT verify shard separation.** The test description says "intern from two different shards produces distinct entries" but the assertion is just `!(na eq nb)` (trivially true for different strings). A proper test would fix the shard mapping for known inputs by using a 1-shard interner and checking that two colliding hashes still both resolve correctly, OR use hash inspection to confirm different shards. Low impact since the behavior is implicitly tested by the concurrent correctness guarantee.

3. **Plan's STEERING directive (byte-count delimiter) requires a specific additional test.** STEERING.md lines 44-48 says tests must include "a name table whose entries do not align to a 'round' count, and a name table with trailing padding bytes that the unpickler must NOT interpret as an extra entry." The current test 10 uses a truncated name (too few bytes) but does not test trailing padding bytes that the loop must not over-read. Consider adding a padding-bytes variant to test 10 or as a test 10b.

4. **NameUnpicklerTest test 7 hardcodes `names.length == 29`.** This is fragile against fixture changes. Document the fixture's exact byte layout if relying on this count, or assert `names.length >= 1` for robustness.

5. **NameUnpicklerTest test 9 uses `.contains(".")` not a specific qualified name.** Comment says it knows the exact entry "kyo.fixtures" but asserts only `.isDefined`. Strengthen to `assert(names.exists(_.asString == "kyo.fixtures"))`.

6. **`Reflect.Name.wrap` is `private[kyo]` but InternerTest is in package `kyo`.** This is correct -- `private[kyo]` is accessible from `kyo.InternerTest`. No action needed; confirm the package-private access is intentional.

---

## Recommendation: STEER: (1) Resolve NameRef base (0-based vs 1-based) against real PlainClass.tasty; (2) Fix test 11 type mismatch `Reflect.Name eq Interner.Entry`
