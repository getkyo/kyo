# Phase 28b decisions

Decision 1: SingleAssign internal impl
Rationale: Replaced `java.util.concurrent.atomic.AtomicReference[AnyRef]` with `kyo.AtomicRef.Unsafe[AnyRef]`
per spec Step 1. Constructor is `private`. Factory `SingleAssign.init[A]()(using AllowUnsafe)` allocates via
`AtomicRef.Unsafe.init[AnyRef](Unset)`. Drops SingleAssign.scala from j.u.c.a import list (6 -> 5 files).
Time: 2026-05-30

Decision 2: Symbol.make signature
Rationale: Symbol.make now takes `(using AllowUnsafe)` and constructs all 7 SingleAssign + 2 OnceCell fields
inside the factory. Used `lazy val sym: Symbol = new Symbol(...)` to enable _fullNameOnce and _bodyOnce
lambdas to capture `sym` by reference without forward-reference issues. Symbol constructor is now `private`
(was `private[Tasty]`).
Time: 2026-05-30

Decision 3: TastyOrigin constructor private, TastyOrigin.init factory
Rationale: Moved `_addrMap: SingleAssign[IntMap[Symbol]]` to constructor parameter. Made constructor private.
Added `TastyOrigin.init(...)( using AllowUnsafe): TastyOrigin` factory. Kept `TastyOrigin.empty` as a `val`
using `import AllowUnsafe.embrace.danger` (module-load init, `§839 case 3`) to avoid requiring AllowUnsafe
at all `TastyOrigin.empty` callsites.
Time: 2026-05-30

Decision 4: ClasspathRef factory pattern
Rationale: ClasspathRef.scala needed the same treatment since it also held a `new SingleAssign`. Made
ClasspathRef constructor private, added `ClasspathRef.init()(using AllowUnsafe): ClasspathRef`. This was
collateral scope for Phase 28b but required by the SingleAssign constructor privatization. All 7 production
src/main callsites of `new ClasspathRef` updated. All test callsites replaced with `ClasspathRef.init()`.
Time: 2026-05-30

Decision 5: InternalSymbol.makeSymbol signature
Rationale: `InternalSymbol.makeSymbol` (kyo.internal.tasty.symbol.Symbol) delegates to `Tasty.Symbol.make`
which now requires AllowUnsafe. Added `(using AllowUnsafe)` to `makeSymbol` to propagate.
Time: 2026-05-30

Decision 6: AllowUnsafe propagation strategy for production callsites
Rationale: For AstUnpickler, SnapshotReader: moved `import AllowUnsafe.embrace.danger` to top of the private
entry methods (`runPass1`, `deserialize`, `deserializeMapped`). For AstUnpickler.walkStats: added
`(using AllowUnsafe)` to the method signature (called from `runPass1` which now has AllowUnsafe in scope).
For ClassfileUnpickler: added `(using AllowUnsafe)` to `read`, `readFrom`, `readFromRaw`, `readBody`, and
all private helpers that create symbols. This removed the existing `import AllowUnsafe.embrace.danger` inside
`readFrom`'s lambda, achieving a net REDUCTION in import count.
Time: 2026-05-30

Decision 7: JavaAnnotationUnpickler.readAnnotations signature
Rationale: Added `(using AllowUnsafe)` to `readAnnotations`, `readAnnotationList`, `readOneAnnotation`,
`readElementValuePairs`, `readElementValue`, `readElementValueArray`, `descriptorToUnresolvedSymbol`,
`descriptorToType`. No in-method `import AllowUnsafe.embrace.danger` added; the proof propagates from method
parameters. Net: -1 import vs baseline.
Time: 2026-05-30

Decision 8: JavaSignatures classSignature approach
Rationale: `makeStub` (called at module-load val init time) uses `import AllowUnsafe.embrace.danger` (one new
import, unavoidable). `classSignature` previously had an import for `Name.asString`; replaced with `(using
AllowUnsafe)` parameter, removing that existing import. `classStub` keeps `import AllowUnsafe.embrace.danger`
to avoid cascading `(using AllowUnsafe)` through all Parser methods that call it. Net: 0 imports for
JavaSignatures vs baseline.
Time: 2026-05-30

Decision 9: Scala2PickleReader decode* methods
Rationale: Added `(using AllowUnsafe)` to `buildResult`, all `decode*` methods, `makePickleSym`,
`buildAnyRefParent`, `readRaw`, `readScalaSig`, `readScalaAttr`, `parsePickle`. Removed the in-method
`import AllowUnsafe.embrace.danger` statements from `decodeValSym`, `decodeAliasSym`, `decodeExtRef`,
`decodeExtModClassRef` since they are now satisfied by the parameter. Net: -4 imports for Scala2PickleReader.
Time: 2026-05-30

Decision 10: TypeUnpickler MatchCaseSentinel
Rationale: `MatchCaseSentinel` is a `val` at object level (module-load init, §839 case 3). Added
`import AllowUnsafe.embrace.danger` with `// flow-allow: §839 case 3` annotation. One new import, unavoidable
since `Tasty.Symbol.make` now requires AllowUnsafe.
Time: 2026-05-30

Decision 11: Test callsite migration
Rationale: Replaced all `new ClasspathRef` with `ClasspathRef.init()` across test files. Test classes
already had `import AllowUnsafe.embrace.danger` or added it locally in helper methods. Replaced `new
SingleAssign[T]` with `SingleAssign.init[T]()` in SingleAssignTest.scala and UnresolvedRefTest.scala.
Replaced `new Tasty.Symbol.TastyOrigin(...)` in TreeUnpicklerTest.scala with `Tasty.Symbol.TastyOrigin.init(...)`.
Time: 2026-05-30

Decision 12: Final import count
Rationale: import AllowUnsafe.embrace.danger count went from 46 (baseline) to 41 (net -5). This exceeds
the requirement of <= 46 and demonstrates that the (using AllowUnsafe) propagation approach is superior to
adding imports. The -5 comes from removing pre-existing imports in ClassfileUnpickler, JavaAnnotationUnpickler,
Scala2PickleReader, and JavaSignatures that were replaced by (using AllowUnsafe) parameter chains.
Time: 2026-05-30
