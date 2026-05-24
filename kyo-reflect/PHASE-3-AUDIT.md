# Phase 3 Audit (commit e29f81a34)

Audited at commit `e29f81a34` (HEAD), comparing strictly against `execution-plan.md` lines 190-263 and the directives recorded in `STEERING.md` "Phase 3 fixes (RESOLVED, applied in e29f81a34)".

## Test count

- Plan: 23 tests across `FlagsTest` (6), `AstUnpicklerTest` (14), `DeclarationTableTest` (3).
- Implemented: 23 (FlagsTest=6, AstUnpicklerTest=14, DeclarationTableTest=3).

Per-leaf:
| # | Class / scenario | Verdict | Citation |
|---|---|---|---|
| 1 | FlagsTest: Flag.Inline.bit power-of-two | PRESENT_STRICT | FlagsTest.scala:13-18 |
| 2 | FlagsTest: Flags.empty.contains(Inline) false | PRESENT_STRICT | FlagsTest.scala:21-24 |
| 3 | FlagsTest: Flags(Inline\|Private).contains(Private) | PRESENT_STRICT | FlagsTest.scala:27-32 |
| 4 | FlagsTest: fromTastyModifierTag PRIVATE | PRESENT_STRICT | FlagsTest.scala:35-39 |
| 5 | FlagsTest: fromTastyModifierTag INLINE | PRESENT_STRICT | FlagsTest.scala:42-46 |
| 6 | FlagsTest: all ~42 flags distinct + powers-of-two | PRESENT_STRICT (43 flags listed) | FlagsTest.scala:49-102 |
| 7 | AstUnpickler: pass 1 yields >=1 Class symbol | PRESENT_STRICT (PlainClass.tasty) | AstUnpicklerTest.scala:56-67 |
| 8 | AstUnpickler: top-level class name in symbol set | PRESENT_STRICT (`"PlainClass"`) | AstUnpicklerTest.scala:70-85 |
| 9 | AstUnpickler: def yields Method | PRESENT_WEAKENED (asserts existence of *some* Method, not specifically a body-def; constructor satisfies it) | AstUnpicklerTest.scala:88-103 |
| 10 | AstUnpickler: val yields Val | PRESENT_STRICT (SomeObject `value`) | AstUnpicklerTest.scala:107-122 |
| 11 | AstUnpickler: trait yields Trait | PRESENT_STRICT | AstUnpicklerTest.scala:125-140 |
| 12 | AstUnpickler: object yields Object | PRESENT_STRICT | AstUnpicklerTest.scala:144-159 |
| 13 | AstUnpickler: enum yields Class+Enum flag | PRESENT_STRICT | AstUnpicklerTest.scala:162-181 |
| 14 | AstUnpickler: inline def has Inline flag | PRESENT_STRICT | AstUnpicklerTest.scala:185-201 |
| 15 | AstUnpickler: type param yields TypeParam | PRESENT_STRICT | AstUnpicklerTest.scala:204-219 |
| 16 | AstUnpickler: method.owner is class symbol | PRESENT_STRICT | AstUnpicklerTest.scala:222-243 |
| 17 | AstUnpickler: nested class fullName dotted | PRESENT_WEAKENED (asserts `contains("Inner")` and `contains(".")`, not exact dotted FQN like `"kyo.fixtures.Outer.Inner"`; plan example specified exact match) | AstUnpicklerTest.scala:246-266 |
| 18 | AstUnpickler: DEFDEF body slices non-zero | PRESENT_WEAKENED (asserts only `bodyEnd > 0`; plan said both `(bodyStart, bodyEnd)` non-zero) | AstUnpicklerTest.scala:269-288 |
| 19 | AstUnpickler: cross-forward TypeParam in addrMap | PRESENT_WEAKENED (plan called for `class C[T1 <: T2, T2]` two-typeparam exact-name check at specific addresses; impl uses GenericBox with one type param A and only verifies A exists in addrMap.values, not addr-keyed) | AstUnpicklerTest.scala:292-308 |
| 20 | AstUnpickler: truncated TASTy -> MalformedSection | PRESENT_WEAKENED (asserts any `Result.Failure`, not specifically `ReflectError.MalformedSection("ASTs", ...)`) | AstUnpicklerTest.scala:311-326 |
| 21 | DeclTable: 4 members flat-array | PRESENT_WEAKENED (no internal-rep assertion that `HashMap` is unused; plan's parenthetical "internal representation uses no HashMap" not verified) | DeclarationTableTest.scala:27-37 |
| 22 | DeclTable: 9 members HashMap path | PRESENT_WEAKENED (no internal-rep check; only get() correctness) | DeclarationTableTest.scala:40-50 |
| 23 | DeclTable: AtomicRef CAS-swap visibility | PRESENT_STRICT (CORRECTED from pulse 1: writer populates first then releases latch, reader awaits then reads, asserts `sz == 4`; STEERING fix applied) | DeclarationTableTest.scala:53-82 |

Counts: PRESENT_STRICT = 14, PRESENT_WEAKENED = 9, MISSING = 0.

## CONTRIBUTING.md violations

- No em-dashes detected in committed files.
- No semicolons (statement-separator) detected.
- No `Co-Authored-By` line in commit `e29f81a34`.
- Internal packages correctly under `kyo.internal.reflect.{symbol,tasty,query}.*`.
- `kyo` package contains only public API (`Reflect.scala`).
- No emojis.

## Unsafe markers

- `asInstanceOf` in production: 5 hits, all pre-Phase-3:
  - `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala:17,19,21` (Phase 0 Memo `Unset` sentinel pattern, justified)
  - `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/SingleAssign.scala:16,23` (Phase 0 SingleAssign `Unset` sentinel pattern, justified)
  - **Constant.scala: 0 hits** — STEERING directive applied; the pulse-1 `null.asInstanceOf[Reflect.Symbol]` at line 73 was replaced with a real `classConstSentinel: Reflect.Symbol` built via `Reflect.Symbol.make(SymbolKind.Unresolved, ..., new ClasspathRef, TastyOrigin(...))`. No cast.
- `null` in production: 3 hits:
  - `Constant.scala:26` — passes `null` for `owner` of the sentinel symbol (the root-style termination convention). Mirrors `AstUnpickler.scala:85` root-symbol pattern. Justified, but symmetric with the AstUnpickler decision; the owner-walk in `Reflect.computeFullName/computeBinaryName` does `(cur ne null) && (cur.owner ne cur) && cur.owner != null` to terminate, so null is the documented sentinel.
  - `AstUnpickler.scala:85` — root owner = `null` (sentinel, documented at line 82).
- `Frame.internal`: 0.
- `AllowUnsafe`: 0.
- `Sync.Unsafe.defer`: 0.

Verdict: STEERING `null.asInstanceOf` removal is CONFIRMED applied. Surviving `null`s are documented sentinels with a defended owner-walk loop.

## STEERING compliance verification

| Directive | Verdict | Citation |
|---|---|---|
| `Pass1Result.placeholders` field present | PRESENT (declared) but UNPOPULATED | AstUnpickler.scala:39 declares the field; line 95 always assigns `Chunk.empty`. No code path appends to a placeholders accumulator. The field is structural-only; Phase 4 must supply the actual `UnresolvedRef` population — at HEAD it carries no data. (Plan line 202: pass 1 should "record parent type references as `UnresolvedRef(nameRef)` placeholders stored in `Pass1Result.placeholders`"; impl skips parent-ref extraction entirely.) |
| `Constant.scala asInstanceOf` removed | REMOVED | Constant.scala:21-29 introduces `classConstSentinel: Reflect.Symbol` built via `Reflect.Symbol.make(SymbolKind.Unresolved, ...)`; line 84 returns `Reflect.Constant.ClassConst(Reflect.Type.Named(classConstSentinel))` with zero casts |
| `SymbolKind.fromTagAndFlags(tag, flags)` present | PRESENT | SymbolKind.scala:45-56 (dispatches on AST tag; delegates to three private helpers; matches plan line 196 signature) |
| DeclarationTableTest test 23 latch order: writer-populates-then-releases | CORRECT | DeclarationTableTest.scala:72-77: `populate(...)` (lines 72-76) precedes `latch.release` (line 77); reader awaits then reads and asserts `sz == 4` (line 68) |
| TYPEDEF discrimination peeks for TEMPLATE | PRESENT | AstUnpickler.scala:187 (`view.peekByte(view.position) & 0xff`) compared to `TastyFormat.TEMPLATE` at line 189; STEERING comment at lines 23-24 explicitly cites dotty `TreeUnpickler.readNewDef` |
| PRIVATEqualified/PROTECTEDqualified Cat 3 sub-tree skip | PRESENT | Both helpers handle them: `scanForwardAndCollectFlags` lines 286-291 (`skipTree(view)` after setting flag bit), and `readModifiers` lines 315-320 (same). STEERING comment at lines 25-26 and 273, 305-306 explicitly references the directive |

All 4 originally-blocking STEERING directives + 2 prior-pulse-passing directives are CONFIRMED applied at HEAD.

## Cross-platform consistency

- All Phase 3 sources live in `kyo-reflect/shared/src/main/scala/` and `kyo-reflect/shared/src/test/scala/`. No JVM-only branches under `jvm/`, `js/`, or `native/` introduced.
- AstUnpickler, Symbol (internal), Flags, SymbolKind, Annotation, Constant, ClasspathRef, DeclarationTable are platform-neutral.
- `java.util.concurrent.atomic.AtomicReference` is used in `DeclarationTable.scala:3,18,19,24,30,37` directly. This is acceptable in Scala.js (mapped via scalajs-java-util) and Scala Native (supported in javalib). However, kyo idiomatic style would prefer `kyo.AtomicRef` — see WARN note.
- `java.lang.Float.intBitsToFloat` / `java.lang.Double.longBitsToDouble` in Constant.scala:72,75 are JVM-spec methods present on JS + Native javalib.
- No use of `java.lang.Class`, `java.lang.reflect`, JVM-only `ClassLoader`, or other JVM-restricted APIs.

Verdict: shared placement and cross-platform deps are clean. The kyo-reflect JS + Native compile claim from the commit message is structurally consistent with the source.

## Naming

- All eight planned `Files to produce` are present at the exact paths specified by the plan (no rename, no relocation; query/ subdir for ClasspathRef matches `kyo.internal.reflect.query` convention).
- All three planned test files present at the exact specified paths.
- Internal packages under `kyo.internal.reflect.{symbol,tasty,query}.*` per plan + project rules.
- `Reflect.scala` modified as specified.

## Steering deviation (file scope)

`git diff --name-only e29f81a34~1 e29f81a34` returns exactly the 19 files: 1 modified (Reflect.scala), 11 added Phase-3 sources/tests, plus 7 audit/doc files (PHASE-2-AUDIT.md, PHASE-3-INFLIGHT-REVIEW-1.md, PHASE-3-INFLIGHT-REVIEW-2.md, PHASE-4-PREP.md, PHASE-5-PREP.md, PROGRESS.md, STEERING.md). All match the plan's `Files to produce`/`Files to modify` sections.

PHASE-4-PREP.md and PHASE-5-PREP.md are forward-prep notes (1000+ lines each); they are documentation, not implementation, so they do not constitute an off-plan implementation drift, but supervisors should be aware they were committed in the same commit as Phase 3 source.

## Anti-flakiness

- Concurrency test (Test 23) uses `kyo.Latch.init(1)` for ordering and `Fiber.initUnscoped` + `readerFiber.get` for join. No raw `Thread.sleep`.
- `Async.timeout(1.second)` wraps the whole scenario so a hang fails the test rather than blocking CI (matches plan).
- Latch ordering: writer `populate` first (DeclarationTableTest.scala:72-76), then `latch.release` (line 77), then reader (started at line 62) unblocks past `latch.await` (line 63) and reads. Reader's assertion is `sz == 4` (line 68), which is now the deterministic post-populate state. STEERING directive applied correctly.

## Findings categorization

### BLOCKER
None. All four STEERING-mandated fixes are present at HEAD. No reward-hacking patterns. No cast / unsafe regressions in new code.

### WARN
1. **`Pass1Result.placeholders` is structurally present but never populated** — `AstUnpickler.runPass1` returns `placeholders = Chunk.empty` (line 95) unconditionally. Plan line 202 specifies pass 1 records parent type references as `UnresolvedRef(nameRef)` placeholders. Phase 4 will need to retrofit pass 1 to actually collect these during PACKAGE / TYPEDEF parent-walks, OR introduce a separate parent-ref-collection pass. This is a deferred scope-cut that the STEERING fix-up "complied with" in form (field exists) but not in substance (no data flows). Flag for Phase 4 prep.
2. **AstUnpicklerTest tests 17, 18, 19, 20 are weakened from plan spec** —
   - test 17 checks `fullName.contains("Inner") && contains(".")` instead of the plan's exact `"kyo.fixtures.Outer.Inner"` form (could pass if fullName were `"X.Inner"`).
   - test 18 asserts `bodyEnd > 0` only, not both `bodyStart` and `bodyEnd`.
   - test 19 uses GenericBox with a single TypeParam A and asserts existence in `addrMap.values`, not the plan's `C[T1 <: T2, T2]` exact-address `addrMap(T1Addr).name.asString == "T1"` check; cannot demonstrate the cross-forward-reference scenario.
   - test 20 asserts any `Result.Failure`, not specifically `ReflectError.MalformedSection("ASTs", ...)` — could pass on any error subtype.
3. **DeclarationTableTest tests 21-22 cannot distinguish Dict's flat-array vs HashMap representation from outside.** Plan called for an internal-representation check ("internal representation uses no HashMap"). Tests are correctness-only. Inherits from pulse-1 note; not regressed.
4. **`Pass1Result.rootSymbol` is an unplanned field addition.** The plan's signature is `Pass1Result(symbols, addrMap, placeholders)`; impl adds `rootSymbol` as a fourth field. Not justified in commit message or PROGRESS.md. Confirm intentional vs. incidental in Phase 4 prep.
5. **`AstUnpickler.readPass1` returns `Sync & Abort[ReflectError]` widening over plan's `Abort[ReflectError]` only.** Minor effect-row widening (Sync.defer used to lift the pure-computed Pass1Result). Not behaviorally wrong but worth confirming.
6. **`Reflect.scala` uses `Name.asString(cur.name)` (lines 241, 243, 255, 257)** where `asString` is defined as an extension method `(n: Name).asString` on line 54. The call form `Name.asString(cur.name)` looks like a static call on the companion object but `Name` is the opaque-type companion. This compiles only because Scala 3 resolves it via the extension syntax (`Name.asString(arg)` as `arg.asString` under the extension scope). Stylistically inconsistent with kyo style (extension is normally `cur.name.asString`); ergonomic concern only. Confirm intentional.
7. **`computeBinaryName` (Reflect.scala lines 250-272) uses `.` separator for all segments,** not `$` for nested classes as the doc-comment on line 234 specifies. Pulse-1 already noted this is "likely deferred to Phase 4 but should be confirmed". Not closed in this commit.

### NOTE
1. The `Tailrec` flag has no `fromTastyModifierTag` mapping case (Flags.scala lacks a `TAILREC` arm). Pulse-1 noted this is correct (TAILREC is compiler-internal, not TASTy-encoded), but Flags.scala doc comment line 13 names `Tailrec` among bits 16+ without that caveat. Doc-only nit.
2. `Pass1Result.symbols` excludes the synthetic root via `allSymbols.tail` (line 93); root remains in `rootSymbol`. Consistent with the unplanned-field addition.
3. `Annotation.scala` (kyo.internal) duplicates the shape of `Reflect.Annotation` (kyo public). Plan line 199 specifies this intentionally as the internal-package-visible variant used during pass 1. Both share `(annotationType: Reflect.Type, argsPickle: Chunk[Byte])`; consider eliminating the duplicate in a future pass if not used distinctly.
4. `DeclarationTable.populate` is intended single-shot (throws `IllegalStateException` on second call). The interface choice differs from a pure CAS-no-op, but plan does not specify; behavior is documented (line 14).
5. Empty `Span` import not used; `mutable.ArrayDeque` + `mutable.ArrayBuffer` + `mutable.HashMap` heavily used in AstUnpickler. Consistent with prior phases' pragmatic use of `scala.collection.mutable` for short-lived per-file decoding state.
6. `Reflect.Symbol.computeFullName` builds a `Name` by interning the joined String. This allocates a fresh interned entry per call to `fullName`; no per-symbol memoization. Acceptable for Phase 3 (correctness over perf); Phase 4+ should consider memoizing on the Symbol.
7. `Pass1Result.addrMap: Map[Int, Reflect.Symbol]` is immutable. Built from `mutable.HashMap.toMap` (line 94). Fine for the read pattern in Phase 4.
8. Java-defined `attrs.isJava` flag is plumbed through but never read inside `walkStats` — no symbol receives `Flag.JavaDefined` based on it. Plan line 202 mentioned "flags (modifier tags)" without specifying isJava plumbing; not a regression, but the `attrs` parameter is currently dead in pass 1.

## Recommendation: PROCEED

All four BLOCKING STEERING directives from in-flight pulses 1 and 2 are confirmed applied at HEAD `e29f81a34`. Test count matches plan (23/23). No new unsafe markers, no casts in Phase 3 code, no off-plan files committed (the dirty-tree UnresolvedRef.scala is NOT in the commit). Cross-platform shared/ placement is correct.

7 WARN findings are deferrable to Phase 4 — chiefly `Pass1Result.placeholders` is a no-op accumulator (structural compliance, not substantive) and four test weakenings on AstUnpicklerTest 17/18/19/20 that should be tightened during Phase 4 type-resolution work when the plumbing is available to do so. None of the WARNs invalidate the Phase 3 deliverable; they are scope-deferral risks that Phase 4 must absorb.

8 NOTE findings are cosmetic / future-cleanup.

Proceed to Phase 4; carry forward the WARN list into PHASE-4-PREP.md as explicit open items so they do not silently compound.
