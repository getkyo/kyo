# kyo-reflect Implementation Progress

Stage 2 driving the 10-phase plan in `execution-plan.md`.

| Phase | Name | Status | Commit | Tests | Notes |
|-------|------|--------|--------|-------|-------|
| 0   | Skeleton (pre-plan) | committed | ccca00f3d | n/a (stubs) | - |
| 0.5 | Bug fixes + fixtures sub-module | committed | 90c84776b | 1 of 2 (test #2 satisfied by supervisor cross-platform compile, not runtime test, because it would create a backward dep from kyo-reflect-fixtures into kyo-reflect) | - |
| 1   | Binary primitives + TASTy header | committed | debd96e17 | 27 (3 extras vs plan's 24, all rigor) | LEB128 was zigzag in plan; corrected to dotty 2's-complement during impl |
| 2   | Name table + section index + attributes | committed | 69e1354fa | 15 | NameRef empirically 0-based against real scalac-compiled fixture (overrode incorrect STEERING that read spec as 1-based) |
| 3   | Symbol pass 1 + skeleton AST | committed | e29f81a34 | 23 (65 total cumulative) | Pulse 1 surfaced 5 CRITICALs (1 hallucination, 4 real); fix-up agent applied all 4 STEERING directives before commit |
| 4   | Type model + arenas + Phase C merge | committed | ad01c90b7 | 26 (+2 for TEMPLATE parents; 91 cumulative) | Pulse 1: 3 CRITICALs (1 hallucination, 1 valid, 1 necessary deviation); pulse 2 confirmed sig wiring but TEMPLATE skipped; fix-up agent applied TEMPLATE directive |
| 5   | Classfile reader | committed | 79bea87b1 | 20 (111 cumulative) | Pulse 1: 1 hard asInstanceOf + 4 weakened tests; pulse 2: 1/4 directives complied; fix-up agent applied 3 remaining (parents wiring, javaSpecific populate, throwsTypes wire, strict tests) before commit. Original agent also added default params for javaMetadata (violates feedback_no_default_params_internal), queued for cleanup. |
| 5b  | Java/Scala unification | committed | 8a66b6e61 | 18 (129 cumulative) | Impl agent hit session quota mid-flight; fix-up applied tests 4/5 TASTy-side assertions after quota reset |
| 6   | Reflect.Reads derivation macro | committed | 82ad3bdfa | 18 (147 cumulative) | Prior impl agent thrashed ~45 min on macro hygiene without applying any of 5 BLOCKING directives (macro splice, asInstanceOf removals, ReadsInstances export, ReadsDerivationTest tests); killed and relaunched with supervisor-blessed lazy self-reference design (lazy val instance referenced inside same quote, ReflectRuntime.readFieldsLazy helper). Relaunched agent shipped 18/18 tests + 64-field cap. Fixup pass confirmed 64-cap, no asInstanceOf in macro source, no em-dashes, no Frame.internal, all platforms compile. |
| 6b  | Record interop | committed | 83e31ea5d | 14 (165 cumulative) | First impl pass used Frame.internal in 10+ generated splices (violation of feedback_no_unsafe); supervisor steered to splice the caller's Frame via Expr.summon[Frame] with the frameExpr lookup positioned AFTER the field-validation loop so Tests 9/10 compile-error tests still fire first. Test 14 (Record.stage.using bridging) compiles via explicit type lambda. asInstanceOf[Record[F]] at line 76 retained (Record.empty is Record[Any], non-generic) per STEERING. |
| 7   | Query + file sources + snapshot + cross-platform | committed | 98416eacf | 38 (203 cumulative; 32 jvmOnly cross-platform-impossible) | First impl pass hit session quota at ~70% complete (production code present, 39 compile errors, no tests, no public Snapshot.scala). Continuation agent fixed the 39 compile errors (Maybe.absent → Maybe.Absent, n.string.get() → n.asString, Seq.pure removal, unused-value warnings), wrote all 3 test files (QueryApiTest, SymbolResolutionTest, SnapshotRoundTripTest), and added many fixture hex literals to Embedded.scala (+8793 lines). 203/203 JVM tests passing; JS + Native compile clean. AllowUnsafe extended to AtomicRef CAS state-machine transitions in Classpath/ClasspathOrchestrator/SnapshotWriter (same justified-bridging pattern authorized in cleanup batch 1). |

**Total tests when all phases complete**: 196.

## Audit findings

- **Phase 0.5** (PHASE-0.5-AUDIT.md): PROCEED. 0 BLOCKER, 0 WARN, 4 NOTE. NOTEs: object \`package\` syntax for deprecated package object (commented in source, fine); var topLevelVar intentional (covers VAR TASTy tag); governance docs (PROGRESS/STEERING/PHASE-N-PREP) bundled into bootstrap commit (acceptable for Phase 0.5; future phases keep separate); test #2 (Test.scala compiles) satisfied by supervisor cross-platform compile, not runtime test (structurally impossible to test inside kyo-reflect-fixtures without backward dep). All addressed in PROGRESS or accepted.
- **Phase 1** (PHASE-1-AUDIT.md): PROCEED. 0 BLOCKER, 3 WARN, 8 NOTE. ALL 3 WARNs CLEANED in cleanup batch 1: try/catch replaced with view.remaining bounds checks in TastyHeader; asInstanceOf[Heap] eliminated by narrowing Heap.subView return type; early returns replaced with continuation-style. NOTEs cosmetic. Audit also documented 4 places where plan was wrong and impl correctly diverged (LEB128 byte order, leaf 23 minor=9, U+00E9 byte count, signed-integer encoding).
- **Phase 2** (PHASE-2-AUDIT.md): PROCEED. 0 BLOCKER, 7 WARN, 8 NOTE. ALL 7 WARNs CLEANED in cleanup batch 1: InternerTest leaf 3 now uses 2-shard interner with empirically-different shards (added shardSize accessor); NameUnpicklerTest leaf 9 asserts "kyo.fixtures"; leaf 11 adds identity equality assertion; cross-platform fixture embedded as hex literal in shared/test/scala/kyo/fixtures/Embedded.scala (no resource loading); trailing-padding-bytes test added; Memo/SingleAssign promoted to Unsafe-tier with WARNING scaladocs, (using AllowUnsafe) on get/set; callers (Reflect/ClasspathRef/ConstantPool) embrace AllowUnsafe locally with // Unsafe: comments. NOTEs cosmetic.
- **Phase 3** (PHASE-3-AUDIT.md): PROCEED. 0 BLOCKER, 7 WARN, 8 NOTE. ALL 7 WARNs CLEANED in cleanup batch 2: Pass1Result.placeholders now populated via typeSession.placeholders during TypeUnpickler decode; AstUnpicklerTest tests 17-20 strengthened (exact fullName, bodyStart > 0, addr-keyed TypeParam lookup, exact MalformedSection match); DeclarationTable storageKind accessor added and tests 21-22 assert flat-array vs hash-map; rootSymbol scaladoc explaining top-level owner sentinel; readPass1 Sync widening documented; Name.asString call style switched to extension form; binaryName already uses `$` for class-kind nesting. NOTEs cosmetic.
- **Phase 4** (PHASE-4-AUDIT.md): PROCEED. 0 BLOCKER, 5 WARN, 8 NOTE. ALL 5 WARNs CLEANED in cleanup batch 2: TypeUnpicklerTest test 16 now decodes a real combined TYPEREFsymbol+SHAREDtype stream in one session, asserts eq-reference; test 23 calls readTypeIntoSession on RECtype/RECthis bytes (no overflow); test 20 strict AndType match; test 21 asserts scrutinee identity; TypeArenaTest test 3 uses two separately-allocated Named instances to verify structural dedup. NOTE: ERASEDMETHODtype/IMPLICITMETHODtype/SKOLEMtype/POLYtype tag-name divergences are still untested against real fixtures; not load-bearing for Phase 7.
- **Phase 5** (PHASE-5-AUDIT.md): PROCEED. 0 BLOCKER, 5 WARN, 6 NOTE. ALL 5 WARNs CLEANED in cleanup batch 3: ClassfileResult.typeParams field added and populated from class-level Signature attribute via parseClassTypeParams; default params on Symbol.make and makeSymbol removed (Absent explicit at every TASTy call site, Present(metadata) at classfile sites); ConstantPool IllegalStateException replaced with sentinel + Abort.fail through Sync.defer; dead nothingSym placeholder already removed in Phase 5b; recordComponents already populated in Phase 5b. NOTEs cosmetic.
- **Phase 6b** (PHASE-6b-AUDIT.md): PROCEED. 0 BLOCKER, 3 WARN, 5 NOTE. ALL 3 WARNs CLEANED in Phase 6b WARN drain: Test 5 and Test 6 success branches now fail explicitly (Phase 7 stub probe pattern); SymbolToRecordMacro empty-fields path guards against non-Any concrete F at macro-expansion time. NOTEs cosmetic.
- **Phase 6** (PHASE-6-AUDIT.md): PROCEED. 0 BLOCKER, 5 WARN, 5 NOTE. ALL 5 WARNs CLEANED in cleanup batch 4: dead extractStaticTouchedByTypeRepr method removed from ReflectMacro; Test 9 strict (typeCheckErrors against sealed trait derives clause, asserts "hand-written"); Test 11 calls derived Reads[Custom].read and asserts the given Reads[Int] sentinel value is used; Test 12 fail-on-success-with-decls instead of vacuous tautology; Test 16 uses new TouchedFields.analyzeInline macro entry to exercise hygiene rule 2 with Bind patterns. NOTEs cosmetic.
- **Phase 5b** (PHASE-5b-AUDIT.md): PROCEED. 0 BLOCKER, 6 WARN, 6 NOTE. ALL 6 WARNs CLEANED in cleanup batch 3: Test 16 (Type.Array) rewritten as a real classfile decode against a new ArrayRecord.class fixture (Java record with int[] values component, embedded as hex in Embedded.scala); stale TODO comments in JavaAnnotationUnpickler (FloatVal/DoubleVal cases) removed; buildEnclosingMethod returns Absent when enclosingMethodIdx is Absent (was returning Present((sym, Name(""))) wrongly); readAnnotations param drift accepted with scaladoc explanation; SEMANTIC BUG FIXED in Flags.fromJvmAccessFlags: 0x0200 (ACC_INTERFACE) was mapped to Flag.Abstract, now correctly only sets Flag.Trait; 0x0400 (ACC_ABSTRACT) now correctly maps to Flag.Abstract; Flag.Static enum case added and 0x0008 (ACC_STATIC) now maps to it instead of Flag.JavaDefined. New FlagsTest 7-9 validate corrected mappings. NOTEs cosmetic.

## Plan deviations during execution

**evictOlderThan cacheDir parameter (FINAL-AUDIT W2, PHASE-7-AUDIT W3)**

`Reflect.Snapshot.evictOlderThan` has signature `evictOlderThan(cacheDir: String, d: Duration): Unit < (Sync & Abort[ReflectError])`,
which deviates from DESIGN.md §16 spec of `evictOlderThan(d: Duration): Unit < (Sync & Scope)` in two ways:

1. The `cacheDir` parameter was added because there is no implicit scope variable for the cache directory at the call site.
   Without it, the caller would have no way to specify which directory to evict. This is a necessary addition.
2. The effect row is `Sync & Abort[ReflectError]` rather than `Sync & Scope`. The I/O can fail (unreadable directory,
   permission error), so `Abort[ReflectError]` is more precise than `Scope` for error signalling.

Decision: ACCEPTED. The DESIGN.md spec omitted an obvious required parameter. The deviation is a correct improvement.

**findClassByBinary inline replace vs FqnCanonicalizer (FINAL-AUDIT W3, PHASE-7-AUDIT W4)**

`Reflect.Classpath.findClassByBinary` at `Reflect.scala:455` uses inline
`binaryName.replace('/', '.').replace('$', '.')` instead of `FqnCanonicalizer.toFullName`.
Reason: `FqnCanonicalizer.toFullName` requires an `innerClassTable: Map[String, (String, String)]`
argument, which is not threaded to the `Classpath` extension-method scope.

The inline replace is correct for named inner classes (`java/util/Map$Entry` produces `java.util.Map.Entry`).
It diverges from `FqnCanonicalizer` behavior only for anonymous local class names containing
`$1LocalClass`-style suffixes, which are not addressable via `findClassByBinary` anyway.

Decision: ACCEPTED. The deviation is justified by the unavailability of the inner class table at call scope.

**Resolver.scala deleted (FINAL-AUDIT W4+W5, PHASE-7-AUDIT W1+W2)**

`Resolver.scala` was dead code: `makeClassLookup` and `makePackageLookup` were never called anywhere.
Wiring them would add `Async` to `Classpath.lookupClass`'s return type, which would propagate to the
public `findClass` / `findPackage` API and break the `Sync & Abort[ReflectError]` contract.

The `Ready.fqnIndex` is an immutable `Map[String, Reflect.Symbol]` built once during Phase C and never mutated.
Any two reads for the same key from an immutable HashMap return the same object reference. Reference equality
is therefore guaranteed by the immutable map, not by `Cache.memo` Promise dedup. `Resolver.scala` was deleted.

`SymbolResolutionTest` test 19 now asserts `sym1 eq sym2` (reference equality) rather than FQN string equality,
since the immutable HashMap guarantees this invariant.

**v2 Phase 1 (Async expansion): findClass / findPackage / findClassByBinary effect row expanded**

`findClass`, `findPackage`, and `findClassByBinary` effect rows expanded from `Sync & Abort[ReflectError]` to
`Sync & Async & Abort[ReflectError]` to accommodate Cache.memo Promise dedup and the `readyLatch` Building-state gate.

Initial Phase 1 commit wired the Async expansion and readyLatch but left Resolver.makeClassLookup / makePackageLookup
uncalled (dead code). Classpath.lookupClass and lookupPackage still read fqnIndex directly, bypassing Cache.memo
Promise dedup. The commit message incorrectly claimed "Cache.memo is now wired".

Phase 1 fixup (this entry) plumbs Cache.memo into Classpath.lookupClass / lookupPackage:
- Classpath.lookupClass / lookupPackage renamed to rawLookupClass / rawLookupPackage (the direct fqnIndex implementations).
- New classLookup / packageLookup var fields added to Classpath, holding the Cache.memo-wrapped functions.
- Classpath.allocate flatMaps Resolver.makeClassLookup / makePackageLookup and assigns the results before returning.
- Public lookupClass / lookupPackage now delegate to classLookup / packageLookup respectively.
- Resolver.makeClassLookup / makePackageLookup updated to wrap rawLookupClass / rawLookupPackage (not lookupClass).
- SymbolResolutionTest Test 19 stale comment ("Resolver.scala was deleted") replaced with accurate description.
- SymbolResolutionTest Test 20 added: N=5 concurrent Async.zip findClass calls during Building state, all resolving
  to reference-equal Symbol instances, confirming Cache.memo Promise dedup is operative.

`Classpath.lookupClass`/`lookupPackage` now await a `Latch.Unsafe` (the `readyLatch` field) when the classpath is
in Building state, suspending the caller until `transitionToReady` releases the latch. This enables the concurrent
deduplication property: callers that arrive during Building block until Phase C completes, then read from the
immutable `fqnIndex`/`packageIndex`. Cache.memo (`Resolver.makeClassLookup`/`makePackageLookup`) is now wired
with `lookupClass`/`lookupPackage` carrying `Async`, matching the memoized function's `Async & Sync & Abort[ReflectError]` row.

Originally documented as "Public API modifications: none" in the v2 Phase 1 plan. Supervisor-approved deviation
per STEERING.md ("v2 Phase 1 Async deviation").

`SymbolResolutionTest` Test 2 `pending` marker removed. Test now launches a background fiber for `openInto` and
two concurrent `findClass` fibers that await the latch; both return the same reference-equal Symbol from
the immutable `fqnIndex`.

## User deferrals

(populated only if the user explicitly accepts a deviation from the plan; nothing should land here without an explicit user message granting the deferral)
