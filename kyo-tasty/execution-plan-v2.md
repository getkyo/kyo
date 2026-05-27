# kyo-reflect Execution Plan v2

v1 shipped 203 tests across 10 phases. v2 closes all gaps from DESIGN.md §24 ("Out of Scope, v1") and the FINAL-AUDIT findings. 17 phases, 108 new tests, total across v1+v2: 311 tests.

---

## Non-Goals (explicit, with rationale)

- **G7 TASTy writing**: DESIGN.md §1 hard non-goal. kyo-reflect is a read-only library; TASTy production would require reconstructing a mutable tree representation that contradicts the lazy-body design.
- **G8 Multi-Scala-version support**: DESIGN.md §24. Single TASTy version per kyo-reflect release; version bumping is a release-level concern, not an implementation phase.
- **G9 Incremental classpath refresh**: DESIGN.md §24. Full-digest re-decode is provably correct; incremental adds cascading invalidation hazards with inadequate test coverage.
- **G10 Phase C sharding**: DESIGN.md §24. Relevant only for monorepos with 10K+ files; deferred to v3.
- **G12 C/C++ header parsing**: DESIGN.md §25. Separate `kyo-cbindings` sibling module requiring libclang (JVM+Native only), incompatible with kyo-reflect's cross-platform guarantee.

---

## Phase 1: AllowUnsafe comment cleanup + Resolver wiring + test 19 hardening

**Dependencies**: None. This is a pure cleanup/correctness phase that closes three v1 WARNs before any new functionality is added. It has no logical predecessor in v2 because it fixes existing code, not new code. All subsequent phases that add resolving accessors depend on Resolver being wired (Phase 1 establishes that invariant).

**Addresses**: FINAL-AUDIT W1, W4, W5; IMPROVEMENT-ANALYSIS Resolver section.

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala`, add `// Unsafe: allSymbols non-effectful read of immutable Ready state` before `AllowUnsafe` import at line 73; add `// Unsafe: atomic CAS transition Building -> Ready, called from single-threaded Phase C` before import at line 125; add `// Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer` before import at line 132.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, add `// Unsafe: stateRef.unsafe.get() read of Building state, single-threaded Phase C merge` before `AllowUnsafe` import at line 200.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala`, add `// Unsafe: stateRef.unsafe.get() non-effectful read of immutable Ready state for snapshot serialization` before `AllowUnsafe` import at line 60.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala`, wire `Resolver.makeClassLookup` into `lookupClass` and `Resolver.makePackageLookup` into `lookupPackage` so the `Cache.memo` Promise-dedup semantics apply to concurrent `findClass` calls.
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala`, change test 19 assertion from `sym1.fullName.asString == sym2.fullName.asString` to `sym1 eq sym2` (reference equality via Cache.memo dedup guarantee).

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `SymbolResolutionTest` test 19 (existing): two concurrent `findClass("kyo.fixtures.FixtureClasses")` calls return `sym1 eq sym2` (reference-equal via Cache.memo). This test strengthens the existing test; it is the verification target, not a new leaf.
2. `SymbolResolutionTest` (new): two concurrent `findClass` calls for the same FQN while the classpath is in `Building` state both block until the classpath transitions to `Ready`, then both receive the same `Symbol` reference.

**Total new tests**: 1 (test 2 above; test 19 strengthening is a modification, not a new leaf).

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.SymbolResolutionTest'
```

**Supervisor checks**:
- Five `// Unsafe:` comments added at the specified sites.
- `Classpath.lookupClass` calls `Resolver.makeClassLookup` (or equivalent Cache.memo path).
- `SymbolResolutionTest` test 19 uses `sym1 eq sym2`.
- All existing 203 tests still pass.

---

## Phase 2: G13 -- Phase C UnresolvedRef placeholder resolution

**Dependencies**: Phase 1 (Resolver wired; Cache.memo path established; AllowUnsafe comments clean). Phase C resolution uses the same `fqnIndex` that the Resolver wraps.

**Addresses**: G13 (FINAL-AUDIT W5, DESIGN.md §15 PARTIAL, PHASE-7-AUDIT N6).

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, extend `FileResult` to carry `placeholders: Chunk[UnresolvedRef]`; extend `decodeTastyBytes` to thread `pass1Result.placeholders` out; in `mergeResults`, after building `fqnIndex`, iterate all `FileResult.placeholders` and for each `UnresolvedRef(fqn, replaceSlot)`, look up `fqn` in `fqnIndex`, write `Type.Named(sym)` into `replaceSlot` if found, or write `Type.Named(unresolvedSym)` (with `kind = SymbolKind.Unresolved`) if absent.
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala`, add two tests for cross-file placeholder resolution (see test list below).
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala`, add one test verifying that the parents of a class that inherits from a class in a different TASTy file are not broken `UnresolvedRef` slots after classpath open.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `SymbolResolutionTest` (new): open a two-file classpath where `FileA.tasty` contains `class A` and `FileB.tasty` contains `class B extends A`; after open, `B`'s internal parent type slot contains `Type.Named(sym)` where `sym.fullName.asString == "A"`'s FQN (not an uninitiated slot). Verified by reading the internal type arena via a package-private test accessor.
2. `SymbolResolutionTest` (new): in a partial classpath where `FileB.tasty` references `MissingClass` not in the classpath roots, the `UnresolvedRef` for `MissingClass` resolves to `Type.Named(sym)` with `sym.kind == SymbolKind.Unresolved`; no `IllegalStateException` from an unset `SingleAssign`.
3. `QueryApiTest` (new): a classpath opened from the two fixture TASTy files (one extending the other) reports `cp.errors.isEmpty` and no `Result.Panic` from unset SingleAssign slots.

**Total new tests**: 3.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.SymbolResolutionTest kyo.QueryApiTest'
```

**Supervisor checks**:
- `FileResult` has `placeholders: Chunk[UnresolvedRef]`.
- `mergeResults` iterates all placeholders and calls `replaceSlot.set(...)` on each.
- No `IllegalStateException` (unset SingleAssign) from any existing or new test.

---

## Phase 3: G21 + G22 + G23 -- Wire parents, typeParams, declarations onto Symbol

**Dependencies**: Phase 2 (Phase C placeholder resolution complete; cross-file parents are resolved before being stored on Symbols; wiring stubs on parent/typeParams/declarations before G13 would yield broken slots for cross-file parents).

**Addresses**: G21 (Symbol.parents), G22 (Symbol.typeParams), G23 (Symbol.declarations).

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add three `SingleAssign` fields to `Symbol`'s internal representation: `private[kyo] val _parents: SingleAssign[Chunk[Type]]`, `private[kyo] val _typeParams: SingleAssign[Chunk[Symbol]]`, `private[kyo] val _declarations: SingleAssign[Chunk[Symbol]]`; replace the three `stub(...)` calls with real implementations that read from the respective `SingleAssign` field (after calling `home.checkOpen`).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, in `mergeResults` (after placeholder resolution), populate `_parents`, `_typeParams`, and `_declarations` on each symbol. For TASTy symbols: parents come from the resolved placeholder slots in the canonical arena (re-walk `Pass1Result` stored per file or carry parent type IDs in `FileResult`); typeParams are TypeParam-kinded children from the same Pass1 walk; declarations are all non-root children. For classfile symbols: `ClassfileResult.parents`, `ClassfileResult.typeParams`, and `ClassfileResult.symbols` are directly assigned.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala`, extend `Pass1Result` to carry `parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]]` and `childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]` pre-indexed maps so `mergeResults` can assign without re-walking.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala`, no change needed (`ClassfileResult` already carries `parents`, `typeParams`, `symbols`); `mergeResults` reads from it.
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala`, add four tests exercising the three new accessors.
- `kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala`, add two tests verifying Pass1Result carries the new indexed maps.

**Files to delete**: none.

**Public API additions**: none (stub methods now work; signature unchanged).
**Public API modifications**:
- `Symbol.parents`: was always-fail stub, now returns real `Chunk[Type]` via `SingleAssign`.
- `Symbol.typeParams`: was always-fail stub, now returns real `Chunk[Symbol]`.
- `Symbol.declarations`: was always-fail stub, now returns real `Chunk[Symbol]`.

**Public API removals**: none.

**Tests**:
1. `QueryApiTest` (new): `cp.findClass("kyo.fixtures.FixtureClasses")` then `sym.parents` returns a non-empty `Chunk[Type]` containing the `scala.AnyRef` parent type.
2. `QueryApiTest` (new): a generic fixture class `class GenFoo[T, U]` produces `sym.typeParams` of length 2 with `typeParams(0).name.asString == "T"` and `typeParams(1).name.asString == "U"`.
3. `QueryApiTest` (new): `sym.declarations` for a class with two known methods returns a `Chunk[Symbol]` containing both; each declaration's `owner eq sym`.
4. `QueryApiTest` (new): `sym.parents` called after classpath close returns `Abort.fail(ReflectError.ClasspathClosed)`.
5. `QueryApiTest` (new): for a Java-sourced `java.lang.String` proxy fixture, `sym.parents` returns at least `java.lang.Object`; `sym.typeParams` is empty; `sym.declarations` is non-empty.
6. `AstUnpicklerTest` (new): `Pass1Result.parentsBySymbol` for the fixture class contains an entry for the class symbol with a non-empty parents list.
7. `AstUnpicklerTest` (new): `Pass1Result.childrenByOwner` for the fixture class maps the class symbol to all its declared members (methods, vals, nested types).

**Total new tests**: 7.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest kyo.AstUnpicklerTest'
```

**Supervisor checks**:
- `Symbol` has `_parents`, `_typeParams`, `_declarations` as `SingleAssign` fields.
- `mergeResults` populates all three for both TASTy and classfile symbols.
- `Symbol.parents`, `Symbol.typeParams`, `Symbol.declarations` stubs replaced.
- All 6 previous stub scaladoc `@note Not implemented` comments removed.

---

## Phase 4: G24 -- Wire companion via FQN lookup

**Dependencies**: Phase 3 (declarations wired; companion lookup uses the FQN index; the `fqnIndex` is populated in Phase C which Phase 2 stabilized).

**Addresses**: G24 (Symbol.companion).

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, replace the `companion` stub with an implementation that: calls `home.checkOpen`; computes the companion FQN as `sym.fullName.asString + "$"` for class/trait symbols (Scala companion objects are `ClassName$`) and `sym.fullName.asString.stripSuffix("$")` for object symbols (looking up the companion class); performs `home.lookupClass(companionFqn).map(s => Maybe(s))` returning `Present(sym)` or `Absent`.
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala`, add three tests.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**:
- `Symbol.companion`: was always-fail stub, now returns `Present(companionSym)` or `Absent` via FQN lookup.
**Public API removals**: none.

**Tests**:
1. `QueryApiTest` (new): a Scala `case class Point(x: Int, y: Int)` in the fixture produces `sym.companion` returning `Present(objectSym)` where `objectSym.kind == SymbolKind.Object` and `objectSym.name.asString == "Point"`.
2. `QueryApiTest` (new): the companion `object Point` produces `sym.companion` returning `Present(classSym)` where `classSym.kind == SymbolKind.Class`.
3. `QueryApiTest` (new): a plain class with no companion object produces `sym.companion` returning `Absent`.
4. `QueryApiTest` (new): `sym.companion` called after classpath close returns `Abort.fail(ReflectError.ClasspathClosed)`.

**Total new tests**: 4.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest'
```

**Supervisor checks**:
- `Symbol.companion` stub replaced.
- Test 1 passes for a `case class` fixture.
- Test 3 passes for a plain class without companion.

---

## Phase 5: G20 -- Wire declaredType from eager member-type decode in Pass 1

**Dependencies**: Phase 3 (parents/typeParams/declarations wired; the same SingleAssign pattern applies to declaredType; Phase C canonical arena is stable and holds the canonical type references needed for declaredType).

**Addresses**: G20 (Symbol.declaredType) for both TASTy and classfile paths.

**Strategy**: Avoid G1 (full tree body decode) for this phase. Instead, extend Pass 1 to eagerly read each member's type annotation (the type tag immediately following name+modifiers in a DEFDEF/VALDEF/TYPEDEF sub-tree) before recording the body slice. This gives us the declared type without decoding the body.

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala`, in the Pass 1 walk for `DEFDEF`, `VALDEF`, and `TYPEDEF` nodes: after reading modifiers, read the type annotation sub-tree via `TypeUnpickler.readType` before recording and skipping the body; store the decoded type in `Pass1Result.typeBySymbol: Map[Reflect.Symbol, Reflect.Type]`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, in `mergeResults`, populate `sym._declaredType` (a new `SingleAssign[Type]` slot on Symbol) from `typeBySymbol` for TASTy symbols; for classfile symbols, the type is already in `ClassfileResult` per-symbol fields (each field/method symbol already carries its `Reflect.Type` from `ClassfileUnpickler`).
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `private[kyo] val _declaredType: SingleAssign[Type]` to `Symbol`; replace the `declaredType` stub with an implementation that calls `home.checkOpen` then reads `_declaredType.get()`.
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala`, add five tests.
- `kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala`, add two tests.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**:
- `Symbol.declaredType`: was always-fail stub, now returns the member's type signature.
**Public API removals**: none.

**Tests**:
1. `QueryApiTest` (new): `sym.declaredType` for a `val x: Int` field in the fixture returns `Type.Named(sym)` where `sym.fullName.asString == "scala.Int"`.
2. `QueryApiTest` (new): `sym.declaredType` for a `def add(x: Int, y: Int): Int` method returns a `Type.Function` with two `Int` params and `Int` result.
3. `QueryApiTest` (new): `sym.declaredType` for a type alias `type Alias = String` returns `Type.Named(stringSym)`.
4. `QueryApiTest` (new): `sym.declaredType` for a Java field `int[] values` (from fixture classfile) returns `Type.Array(Type.Named(intSym))`.
5. `QueryApiTest` (new): `sym.declaredType` called after classpath close returns `Abort.fail(ReflectError.ClasspathClosed)`.
6. `AstUnpicklerTest` (new): `Pass1Result.typeBySymbol` for a fixture `def foo: String` contains an entry for `foo`'s symbol with type equal to the `scala.String` named type.
7. `AstUnpicklerTest` (new): `Pass1Result.typeBySymbol` for a fixture `class Foo[T](val x: T)` contains an entry for `x`'s symbol with type equal to `Type.Named(TSymbol)` (the type parameter reference).

**Total new tests**: 7.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest kyo.AstUnpicklerTest'
```

**Supervisor checks**:
- `Symbol._declaredType` SingleAssign field added.
- `Symbol.declaredType` stub replaced.
- Pass 1 eagerly reads member types before body slice.
- Classfile path wires declared types from ClassfileUnpickler output.

---

## Phase 6: G3 -- Comments section reader

**Dependencies**: Phase 5 (declaredType working; symbol graph stable with all four accessor stubs replaced). G3 does not depend on G1 (tree body decode) because comments are indexed by symbol address, not tree-node address. G3 depends on Pass 1 providing the symbol address map (already present from v1 Phase 3).

**Addresses**: G3 (Comments section reader per DESIGN.md §24).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala`, `object CommentsUnpickler` with `def read(view: ByteView, addrMap: Map[Int, Reflect.Symbol]): Map[Reflect.Symbol, String] < Abort[ReflectError]`; reads the `Comments` section: for each entry, reads the address (LEB128 nat), then the comment string (length-prefixed UTF-8 bytes); looks up the address in `addrMap` to find the target symbol; skips entries whose address is not in `addrMap` (these are sub-expressions, not definitions); returns `Map[Symbol, String]` indexed by symbol.
- `kyo-reflect/shared/src/test/scala/kyo/CommentsUnpicklerTest.scala`, tests for comments decode.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `private[kyo] val _scaladoc: Memo[Maybe[String]]` to `Symbol` (initialized to `Absent` by default); add `def scaladoc: Maybe[String]` pure accessor that returns `_scaladoc.get()` without effects (comments are pre-decoded and stored, no classpath access needed after load).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, in `decodeTastyBytes`, after Pass 1: check if a `Comments` section exists (via `SectionIndex`); if present, call `CommentsUnpickler.read` and populate `_scaladoc` on each returned symbol; store the comments map in `FileResult` for threading to `mergeResults`.
- `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala`, no new case needed (malformed Comments section uses existing `ReflectError.MalformedSection`).

**Files to delete**: none.

**Public API additions**:
- `Reflect.Symbol.scaladoc: Maybe[String]` -- pure accessor, returns `Absent` for symbols without scaladoc or from classfile sources.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `CommentsUnpicklerTest` (new): a TASTy file with a documented class `/** My doc */ class Foo` produces a `CommentsUnpickler` result with an entry for `Foo`'s symbol containing `"My doc"`.
2. `CommentsUnpicklerTest` (new): a TASTy file with no `Comments` section returns an empty map without error.
3. `CommentsUnpicklerTest` (new): a malformed `Comments` section (truncated mid-entry) produces `Abort.fail(ReflectError.MalformedSection("Comments", ...))`.
4. `CommentsUnpicklerTest` (new): a symbol with no comment has `sym.scaladoc == Absent`; a symbol with a comment has `sym.scaladoc == Present("...")`.
5. `CommentsUnpicklerTest` (new): comments from two sibling definitions in the same file are independently accessible (no cross-contamination between addresses).
6. `CommentsUnpicklerTest` (new): a Java-sourced classfile symbol always has `sym.scaladoc == Absent` (classfiles have no comments section).

**Total new tests**: 6.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.CommentsUnpicklerTest'
```

**Supervisor checks**:
- `CommentsUnpickler.scala` present.
- `Symbol.scaladoc` pure accessor present in `Reflect.scala`.
- Test 1 passes using a fixture TASTy file with a documented class.
- Test 6 passes confirming Java symbols always have `Absent` scaladoc.

---

## Phase 7: G2 -- Position section reader

**Dependencies**: Phase 6 (Comments section implemented using the same address-map pattern; the Position section reader follows the same architecture). G2 does NOT depend on G1 (tree body decode) for definition-level positions.

**Addresses**: G2 (Position section reader per DESIGN.md §24). Scope is definition-level positions only (the address of the definition node in the TASTy tree), not per-expression positions.

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala`, `object PositionsUnpickler` with `def read(view: ByteView, addrMap: Map[Int, Reflect.Symbol], sourceFile: Maybe[String]): Map[Reflect.Symbol, Reflect.Position] < Abort[ReflectError]`; reads the `Positions` section: a delta-encoded sequence of (address, line, column) triples; decodes deltas; looks up each address in `addrMap`; returns a map of symbol to position. `Reflect.Position` is a new public type.
- `kyo-reflect/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala`, tests for position decode.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `final case class Position(sourceFile: Maybe[String], line: Int, column: Int)` as a public nested type; add `private[kyo] val _position: Memo[Maybe[Position]]` to `Symbol`; add `def position: Maybe[Position]` pure accessor.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, decode the `Positions` section after Pass 1 (same pattern as Comments in Phase 6); store per-symbol positions in `FileResult`; in `mergeResults`, populate `_position` on each symbol.

**Files to delete**: none.

**Public API additions**:
- `Reflect.Position(sourceFile: Maybe[String], line: Int, column: Int)` -- new public type.
- `Reflect.Symbol.position: Maybe[Position]` -- pure accessor; `Absent` for classfile symbols; `Present(pos)` for TASTy symbols with position data.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `PositionsUnpicklerTest` (new): a fixture TASTy file compiled from `Foo.scala` line 3 column 1: `class Foo`'s symbol has `sym.position == Present(Position(Present("Foo.scala"), 3, 1))`.
2. `PositionsUnpicklerTest` (new): a TASTy file with no `Positions` section returns an empty map without error.
3. `PositionsUnpicklerTest` (new): a malformed `Positions` section (truncated mid-entry) produces `Abort.fail(ReflectError.MalformedSection("Positions", ...))`.
4. `PositionsUnpicklerTest` (new): a Java-sourced symbol has `sym.position == Absent` (classfiles have no TASTy positions section).
5. `PositionsUnpicklerTest` (new): two sibling definitions in the same file have distinct line/column values in their positions.

**Total new tests**: 5.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.PositionsUnpicklerTest'
```

**Supervisor checks**:
- `PositionsUnpickler.scala` present.
- `Reflect.Position` type present in `Reflect.scala`.
- `Symbol.position` pure accessor present.
- Test 1 passes with a fixture TASTy file where the line/column values are known.

---

## Phase 8: G1 -- Tree body decode (Tree ADT + lazy body accessor)

**Dependencies**: Phase 5 (declaredType wired from Pass 1 eager type read; body-slice positions on each Symbol are already recorded as `bodyStart`/`bodyEnd` in `Symbol.Origin.TastyOrigin` from v1 Phase 3; the canonical type arena from Phase C holds resolved type references for use in decoded tree nodes).

**Addresses**: G1 (Tree body / AST decoding).

**Scope**: Full decode of the body byte slice into a `Tree` ADT covering all TASTy expression tags. The `Tree` ADT is defined as a new nested type `Reflect.Tree`. The `Symbol.body` accessor decodes on demand. This phase does NOT implement bytecode-level evaluation or constant folding; it is a pure structural decode.

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TreeUnpickler.scala`, `object TreeUnpickler` with `def readTree(view: ByteView, names: Array[Reflect.Name], addrMap: Map[Int, Reflect.Symbol], arena: TypeArena): Reflect.Tree < (Sync & Abort[ReflectError])`; decodes all TASTy expression tags: literals (UNIT, FALSE, TRUE, INT, LONG, FLOAT, DOUBLE, STRING, NULL, CLASS, ENUM), identifier references (IDENT, SELECT, QUALTHIS, SUPER, NEW, THROW, TYPED, NAMEDARG, ASSIGN, BLOCK, IF, WHILE, LAMBDA, MATCH, RETURN, WHILE, TRY, INLINED, BIND, ALTERNATIVE, UNAPPLY, ANNOTATED, ELIM, HOLE, SPLITCLOSURE), term references, apply nodes (APPLY, TYPEAPPLY), type trees (TYPEBLOCK, TYPECLOSURE), and all the structural nodes.
- `kyo-reflect/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`, tests for tree decode.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `sealed trait Tree` with cases covering: `Ident(name: Name, tpe: Type)`, `Select(qualifier: Tree, name: Name, tpe: Type)`, `Apply(fun: Tree, args: Chunk[Tree])`, `TypeApply(fun: Tree, args: Chunk[Type])`, `Block(stats: Chunk[Tree], expr: Tree)`, `If(cond: Tree, thenp: Tree, elsep: Tree)`, `Match(selector: Tree, cases: Chunk[CaseDef])`, `CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree)`, `Literal(constant: Constant)`, `New(tpe: Type)`, `Assign(lhs: Tree, rhs: Tree)`, `Return(expr: Maybe[Tree], from: Symbol)`, `Throw(expr: Tree)`, `Lambda(method: Tree, tpe: Maybe[Type])`, `Typed(expr: Tree, tpe: Type)`, `Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree)`, `Try(expr: Tree, cases: Chunk[CaseDef], finalizer: Maybe[Tree])`, `While(cond: Tree, body: Tree)`, `Bind(name: Name, pattern: Tree)`, `Alternative(patterns: Chunk[Tree])`, `Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree])`, `ValDef(sym: Symbol, tpt: Type, rhs: Maybe[Tree])`, `DefDef(sym: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree])`, `TypeDef(sym: Symbol, rhs: Type)`, `PackageDef(sym: Symbol, stats: Chunk[Tree])`, `ClassDef(sym: Symbol, template: Template)`, `Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree])`, `Super(qual: Tree, mixin: Maybe[Name])`, `This(cls: Symbol)`; add `private val _bodyMemo: Memo[Maybe[Reflect.Tree]]` field to `Symbol` (initialized to `Memo.compute(bodyBytes => TreeUnpickler.decode(bodyBytes))`; the first call to `body` populates the memo via `TreeUnpickler`, subsequent calls return the cached value without re-decoding); add `def body(using Frame): Reflect.Tree < (Sync & Abort[ReflectError])` to `Symbol` (decodes on demand from `origin.TastyOrigin.bodyStart/End`; returns `Abort.fail(ReflectError.NotImplemented("body not available for Java symbols"))` for `JavaOrigin`; returns `Abort.fail(ReflectError.ClasspathClosed)` after close).
- `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala`, no new case needed.

**Files to delete**: none.

**Public API additions**:
- `Reflect.Tree` sealed trait with all named cases.
- `Reflect.Symbol.body: Reflect.Tree < (Sync & Abort[ReflectError])` -- lazy body accessor.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `TreeUnpicklerTest` (new): a `def foo: Int = 42` body decodes to `Block(Nil, Literal(IntConst(42)))` (or equivalent single-expression form).
2. `TreeUnpicklerTest` (new): a `def bar(x: Int): Int = x + 1` body decodes to a `Block` containing an `Apply` of `+` with `Ident("x")` and `Literal(IntConst(1))`.
3. `TreeUnpicklerTest` (new): `if (cond) a else b` decodes to `If(cond, a, b)` with three child subtrees.
4. `TreeUnpicklerTest` (new): `x match { case 1 => "one"; case _ => "other" }` decodes to `Match` with two `CaseDef` children.
5. `TreeUnpicklerTest` (new): a recursive method body decodes without stack overflow (cycle-safe traversal).
6. `TreeUnpicklerTest` (new): `sym.body` for a Java symbol returns `Abort.fail(ReflectError.NotImplemented(...))`.
7. `TreeUnpicklerTest` (new): `sym.body` called after classpath close returns `Abort.fail(ReflectError.ClasspathClosed)`.
8. `TreeUnpicklerTest` (new): a truncated body byte slice produces `Abort.fail(ReflectError.CorruptedFile(...))` without throwing.
9. `TreeUnpicklerTest` (new): two consecutive calls to `sym.body` return the same `Tree` reference (assert `tree1 eq tree2` via reference equality; confirms Memo caching prevents re-decoding the body byte slice on the second call).

**Total new tests**: 9.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.TreeUnpicklerTest'
```

**Supervisor checks**:
- `TreeUnpickler.scala` present.
- `Reflect.Tree` sealed trait present with all listed cases.
- `Symbol.body` accessor present.
- Test 5 (recursive method, no stack overflow) passes.
- Test 9 (Memo caching) passes.

---

## Phase 9: G5 -- Subtype checking and type comparison

**Dependencies**: Phase 8 (Tree ADT complete; canonical type arena stable from Phase C; all resolving accessors working so type references are fully resolved).

**Addresses**: G5 (subtype checking and type comparison beyond structural equality).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala`, extend with `def isSubtype(sub: Reflect.Type, sup: Reflect.Type, cp: Classpath): Boolean < (Sync & Abort[ReflectError])`; implements covariant subtyping rules: `Named(A) <: Named(B)` if A's declared type (or parents) contain B; `Applied(C[A], args) <: Applied(C[B], args')` iff variance-respecting element subtyping; `AndType(L, R) <: T` iff `L <: T` or `R <: T`; `T <: OrType(L, R)` iff `T <: L` or `T <: R`; `TypeLambda` alpha-equivalence; `Wildcard(lo, hi) <: Wildcard(lo', hi')` iff `lo' <: lo` and `hi <: hi'`; `Rec` unfolds one level for comparison using a depth budget of 64; if the budget is exhausted before a definitive subtype verdict, returns `false` (conservative: not-a-subtype). The depth budget is documented in scaladoc on `Type.<=:` and the budget enforcement site in `Subtyping.scala`.
- `kyo-reflect/shared/src/test/scala/kyo/SubtypeTest.scala`, tests for subtype checking.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `extension (t: Type) def isSubtypeOf(other: Type)(using cp: Classpath, Frame): Boolean < (Sync & Abort[ReflectError])` as a public extension method; takes the enclosing `Classpath` explicitly (no implicit Classpath per `feedback_no_implicit_handlers`). Note: the `cp` parameter here is the explicit classpath used for parent lookups; callers write `tpe.isSubtypeOf(other)(using myCp)`.

**Files to delete**: none.

**Public API additions**:
- `extension (t: Reflect.Type) def isSubtypeOf(other: Type)(using cp: Classpath, Frame): Boolean < (Sync & Abort[ReflectError])`.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `SubtypeTest` (new): `Type.Named(intSym).isSubtypeOf(Type.Named(intSym))` returns `true` (reflexivity).
2. `SubtypeTest` (new): `Type.Named(stringSym).isSubtypeOf(Type.Named(objectSym))` returns `true` (nominal subtyping via parents).
3. `SubtypeTest` (new): `Type.Named(stringSym).isSubtypeOf(Type.Named(intSym))` returns `false`.
4. `SubtypeTest` (new): `AndType(A, B).isSubtypeOf(A)` returns `true`.
5. `SubtypeTest` (new): `A.isSubtypeOf(OrType(A, B))` returns `true`.
6. `SubtypeTest` (new): `Applied(List, Chunk(String)).isSubtypeOf(Applied(List, Chunk(AnyRef)))` returns `true` when `List` is covariant.
7. `SubtypeTest` (new): `Type.Named(nothingSym).isSubtypeOf(anyType)` returns `true` (Nothing is subtype of all).
8. `SubtypeTest` (new): `TypeLambda(Chunk(T), Applied(C, Chunk(T)))` is alpha-equivalent to `TypeLambda(Chunk(U), Applied(C, Chunk(U)))` (structural alpha-equivalence).
9. `SubtypeTest` (new): a `Rec` type with `RecThis` back-reference does not cause infinite recursion in `isSubtypeOf` (bounded unfolding).

**Total new tests**: 9.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.SubtypeTest'
```

**Supervisor checks**:
- `TypeOps.isSubtype` present with all listed cases.
- `Type.isSubtypeOf` extension method present in `Reflect.scala`.
- Test 9 (Rec type, no infinite recursion) passes.

---

## Phase 10: G4 -- Scala 2 pickle reader

**Dependencies**: Phase 9 (symbol graph complete; the Scala 2 pickle reader produces Symbols and Types using the same ADTs; having all accessors working first means Scala 2 symbols are immediately usable). G4 also depends on the classfile reader (v1 Phase 5) because Scala 2 pickles are embedded as a `ScalaSig` attribute in `.class` files.

**Addresses**: G4 (Scala 2 pickle reader).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/scala2/Scala2PickleReader.scala`, `object Scala2PickleReader` with `def read(bytes: Array[Byte], interner: Interner, arena: TypeArena, home: ClasspathRef): Scala2PickleResult < (Sync & Abort[ReflectError])`; reads the Scala 2 pickle format (Scalasig compact encoding: `0xFE` byte followed by LEB128 length and zlib-compressed body); decompresses; decodes the symbol table and type table; produces `Reflect.Symbol` instances and `Reflect.Type` values using the same ADTs as the TASTy path. Maps Scala 2 type constructors to the closest `Reflect.Type` case (e.g., `NullaryMethodType` maps to `Function(Chunk.empty, result, false)`, `PolyType` maps to `TypeLambda`, `TypeRef` maps to `Named` or `Applied`, `ExistentialType` maps to `Wildcard` bounds).
- `kyo-reflect/shared/src/test/scala/kyo/Scala2PickleTest.scala`, tests for Scala 2 symbol decode.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala`, after reading all class-level attributes: check for `ScalaSig` attribute (tag name `"ScalaSig"` or `"Scala"`); if present, delegate to `Scala2PickleReader.read` and return the combined result.
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `Flag.Scala2` flag bit to identify Scala 2 sourced symbols.

**Files to delete**: none.

**Public API additions**:
- `Flag.Scala2` -- new flag identifying symbols decoded from Scala 2 pickles.

**Public API modifications**:
- `ClassfileUnpickler` now also reads Scala 2 pickles when present; the `ClassfileResult` carries richer symbols for mixed Scala 2 / Java artifacts.

**Public API removals**: none.

**Cross-platform note**: All Phase 10 tests are tagged `jvmOnly` because the Scala 2 pickle format is gzip-compressed and `InflaterInputStream` is JVM-only. Native/JS support deferred to a follow-up if a cross-platform inflater becomes available.

**Tests**:
1. `Scala2PickleTest` (new, jvmOnly): a classfile with a known `ScalaSig` attribute (fixture bytes from a Scala 2-compiled class with a case class) produces symbols with `flags.contains(Flag.Scala2)`.
2. `Scala2PickleTest` (new, jvmOnly): a Scala 2 case class fixture produces `sym.kind == SymbolKind.Class` and `flags.contains(Flag.Case)`.
3. `Scala2PickleTest` (new, jvmOnly): a Scala 2 method symbol produces `sym.declaredType` with a `Type.Function` value.
4. `Scala2PickleTest` (new, jvmOnly): a Scala 2 type alias `type Alias = String` produces `sym.kind == SymbolKind.TypeAlias` and `sym.declaredType` returning `Type.Named(stringSym)`.
5. `Scala2PickleTest` (new, jvmOnly): a classfile without `ScalaSig` attribute is decoded as a plain Java class (no Scala 2 symbols); `flags.contains(Flag.Scala2)` is `false`.
6. `Scala2PickleTest` (new, jvmOnly): a corrupt `ScalaSig` attribute (wrong magic or truncated body) produces `Abort.fail(ReflectError.CorruptedFile(...))`.
7. `Scala2PickleTest` (new, jvmOnly): `sym.parents` for a Scala 2 class returns at least one parent type; cross-file parent references resolve correctly via Phase C placeholder resolution.

**Total new tests**: 7.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.Scala2PickleTest'
```

**Supervisor checks**:
- `Scala2PickleReader.scala` present.
- `Flag.Scala2` present in `Reflect.scala`.
- `ClassfileUnpickler` checks for `ScalaSig` attribute.
- Test 5 confirms Java-only classfiles are not affected.

---

## Phase 11: G6 -- JPMS module-info.class parsing

**Dependencies**: Phase 10 (classfile reader extended with Scala 2 path; adding module-info reads alongside the standard classfile path is a parallel extension, not a sequential dependency on Phase 10, but Phase 10 brings the classfile reader to its final form before module-info is added).

**Addresses**: G6 (Java module-info.class, JPMS).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ModuleInfoReader.scala`, `object ModuleInfoReader` with `def read(bytes: Array[Byte]): ModuleDescriptor < (Sync & Abort[ReflectError])`; reads a `module-info.class` file: magic, version, constant pool (same as normal classfile), `Module` attribute; decodes module name, version, requires (with `ACC_TRANSITIVE` and `ACC_STATIC_PHASE` flags), exports (module.package to modules), opens, uses (service interface FQNs), provides (interface FQN to implementation FQNs).
- `kyo-reflect/shared/src/test/scala/kyo/ModuleInfoTest.scala`, tests for module-info decode.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, add `final case class ModuleDescriptor(name: String, version: Maybe[String], requires: Chunk[ModuleRequires], exports: Chunk[ModuleExports], opens: Chunk[ModuleOpens], uses: Chunk[String], provides: Chunk[ModuleProvides])` and its component types; add `def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError])` extension on `Classpath`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`, when collecting files: detect `module-info.class` by filename; route to `ModuleInfoReader.read` instead of `ClassfileUnpickler.read`; store in a `moduleIndex: Map[String, ModuleDescriptor]` in the `Ready` state.

**Files to delete**: none.

**Public API additions**:
- `Reflect.ModuleDescriptor` case class + component types.
- `extension (cp: Classpath) def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError])`.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `ModuleInfoTest` (new): fixture `module-info.class` bytes (synthetic, encoding `module foo.bar requires java.base;`) decode to `ModuleDescriptor(name = "foo.bar", requires = Chunk(ModuleRequires("java.base", ...)))`.
2. `ModuleInfoTest` (new): module-info with `exports foo.bar to baz.qux` decodes exports list correctly.
3. `ModuleInfoTest` (new): module-info with `uses com.example.Service` decodes uses list correctly.
4. `ModuleInfoTest` (new): module-info with `provides com.example.Service with com.example.Impl` decodes provides list correctly.
5. `ModuleInfoTest` (new): a `module-info.class` with wrong magic returns `Abort.fail(ReflectError.ClassfileFormatError(...))`.
6. `ModuleInfoTest` (new, jvmOnly): `cp.findModule("java.base")` on a JVM classpath that includes the JDK `module-info.class` returns `Present(desc)` with `desc.name == "java.base"`. Requires JDK 9+ `jrt:/` filesystem access; JVM-only.

**Total new tests**: 6.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.ModuleInfoTest'
```

**Supervisor checks**:
- `ModuleInfoReader.scala` present.
- `Reflect.ModuleDescriptor` and component types present.
- `findModule` extension present.
- Test 6 passes on JVM (requires JDK 25 `module-info.class` available via `jrt:/`).

---

## Phase 12: G11 -- Hand-written Reads instances participating in touchedFields

**Dependencies**: Phase 11 (symbol graph fully realized; G11 adds compile-time infrastructure on top of the existing Reads macro; no runtime dependency on earlier phases). G11 depends on Phase 6 (Reads derivation macro) from v1 being in place.

**Addresses**: G11 (DESIGN.md §24).

**Strategy**: Add a `TouchedFields.declare(fields: FieldSet)` compile-time helper that hand-written `Reads` authors use to provide a statically-known `touchedFields` value. The macro, when composing transitive `touchedFields` across `Reads` instances, checks for a `TouchedFields.declare` call in the hand-written `read` body and uses that value instead of defaulting to `FieldSet.All`.

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala`, add `inline def declare(fields: Reflect.FieldSet): Unit = ()` (a compile-time annotation hint consumed by the macro; the inline def expands to unit at runtime); add recognition of `TouchedFields.declare(fs)` calls in the `analyze` tree-walk: when encountered in a hand-written `Reads.read` body, return `fs` immediately instead of accumulating from the full body.
- `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala`, in `derivedImpl`, when summing transitive `touchedFields` for a composed `Reads` instance, call `TouchedFields.analyzeInline` on the nested instance's `read` body; if the body contains a `TouchedFields.declare(fs)` call, use `fs` for that component.
- `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala`, add two tests.

**Files to delete**: none.

**Public API additions**:
- `TouchedFields.declare(fields: FieldSet): Unit` -- compile-time hint for hand-written Reads instances.

**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `ReadsDerivationTest` (new): a hand-written `Reads[MyType]` with `TouchedFields.declare(FieldSet.Name | FieldSet.Flags)` in its `read` body; a derived `Reads[Wrapper]` that uses `Reads[MyType]` for one field has `touchedFields` containing `FieldSet.Name | FieldSet.Flags` (the declared fields, not `FieldSet.All`).
2. `ReadsDerivationTest` (new): a hand-written `Reads[MyType]` WITHOUT `TouchedFields.declare` defaults to `FieldSet.All` in the transitive analysis of a derived `Reads[Wrapper]`.

**Total new tests**: 2.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.ReadsDerivationTest'
```

**Supervisor checks**:
- `TouchedFields.declare` present in `TouchedFields.scala`.
- `ReflectMacro.derivedImpl` checks for `TouchedFields.declare` in nested Reads bodies.
- Test 1 passes confirming declared fields propagate.
- Test 2 passes confirming undeclared hand-written instances default to `FieldSet.All`.

---

## Phase 13: G19 -- ReflectError.InconsistentClasspath UUID type

**Dependencies**: Phase 12. G19 is a standalone API change; however, all phases before it establish the complete symbol graph, after which the classpath consistency check (which produces `InconsistentClasspath`) is a natural addition. Placed here as the last API-contract phase before the performance and snapshot phases.

**Addresses**: G19 (FINAL-AUDIT §5).

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala`, change `case InconsistentClasspath(file: String, expectedUuid: String, foundUuid: String)` to `case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)`. Note: `InconsistentClasspath` is currently never constructed anywhere in the codebase (it is defined but unused); zero construction-site updates are required.
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala`, add one test constructing an `InconsistentClasspath` with `UUID` values to confirm the type change compiles.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**:
- `ReflectError.InconsistentClasspath(file, expectedUuid, foundUuid)`: UUID fields change from `String` to `java.util.UUID`.
**Public API removals**: none.

**Tests**:
1. `QueryApiTest` (new): `ReflectError.InconsistentClasspath("foo.tasty", UUID.randomUUID(), UUID.randomUUID())` constructs without error and pattern-matches correctly.

**Total new tests**: 1.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest'
```

**Supervisor checks**:
- `ReflectError.InconsistentClasspath` has `java.util.UUID` for both UUID fields.
- No construction site for `InconsistentClasspath` exists outside the new test (confirm with `grep`).
- All existing tests still pass (no binary breakage in tests).

---

## Phase 14: G15 -- Snapshot inputDigest fix

**Dependencies**: Phase 13. The inputDigest fix is a standalone snapshot correctness fix. Placed after all accessor and API phases to avoid coordinating a snapshot-format minor-version bump alongside other changes. This phase bumps `SnapshotFormat.minorVersion` from 0 to 1; existing snapshots are still loadable (minor-version policy: add-only, old snapshots load with empty new sections).

**Addresses**: G15 (inputDigest field always zeros, FINAL-AUDIT N4/N8, PHASE-7-AUDIT N4).

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala`, change `serialize` to accept `digest: Array[Byte]` as a parameter; thread `digest` from `write` into `serialize`; thread `digest` from `serialize` into `assembleSections`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotFormat.scala`, bump `minorVersion` from 0 to 1.
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`, add one test verifying that a written snapshot's in-header `inputDigest` field equals the digest passed to `write`.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**:
- `SnapshotFormat.minorVersion` changes from 0 to 1 (add-only minor bump).
**Public API removals**: none.

**Tests**:
1. `SnapshotRoundTripTest` (new): write a snapshot with digest `[1, 2, 3, 4, 5, 6, 7, 8]`; read the raw bytes; assert that bytes 16-23 (the `inputDigest` field at offset 16 in the header) equal `[1, 2, 3, 4, 5, 6, 7, 8]` (not zeros).

**Total new tests**: 1.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.SnapshotRoundTripTest'
```

**Supervisor checks**:
- `SnapshotWriter.serialize` signature includes `digest: Array[Byte]`.
- `SnapshotFormat.minorVersion == 1`.
- Test 1 passes: the written header field is not all zeros.

---

## Phase 15: G14 -- BODY_BYTES KRFL section

**Dependencies**: Phase 14 (inputDigest fix; snapshot format is at minorVersion 1; adding BODY_BYTES bumps to minorVersion 2). Phase 8 (tree body decode) must be implemented before BODY_BYTES is useful: without G1, body bytes are stored but never decoded on load. G14 depends on G1 being in place so the snapshot round-trip for `Symbol.body` can be tested end-to-end.

**Addresses**: G14 (BODY_BYTES KRFL section absent, FINAL-AUDIT N7, PHASE-7-AUDIT N4).

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala`, in `serialize`: collect the body bytes of all TASTy-origin symbols (concatenate all `bodyStart..bodyEnd` slices from each symbol's raw bytes, keeping a running offset per symbol); write the concatenated bytes as the `BODY_BYTES` section; write each symbol's `(bodyStart, bodyEnd)` relative to the start of the BODY_BYTES section in the SYMBOLS record.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala`, when reading the SYMBOLS section: for each symbol with a TASTy origin, read its `(bodyStart, bodyEnd)` from the record; these offsets now point into the BODY_BYTES section; after reading the BODY_BYTES section, create a single `Array[Byte]` from it and assign each symbol's `origin` to `TastyOrigin(bodyStart, bodyEnd)` with the shared BODY_BYTES array as the backing store.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotFormat.scala`, bump `minorVersion` from 1 to 2.
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`, add two tests.

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**:
- `SnapshotFormat.minorVersion` changes from 1 to 2.
**Public API removals**: none.

**Tests**:
1. `SnapshotRoundTripTest` (new): write a snapshot from a classpath loaded from fixture TASTy; reload it; call `sym.body` on a method symbol; assert the decoded tree is not `Abort.fail(ReflectError.NotImplemented(...))` (i.e., body bytes survive the round-trip).
2. `SnapshotRoundTripTest` (new): a snapshot written without any TASTy-origin symbols (classfile-only classpath) has an empty BODY_BYTES section (length 0 in the section index); reading it back succeeds without error.

**Total new tests**: 2.

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.SnapshotRoundTripTest'
```

**Supervisor checks**:
- `SnapshotFormat.minorVersion == 2`.
- `SnapshotWriter` writes non-empty BODY_BYTES for TASTy-origin classpaths.
- `SnapshotReader` restores body origins from BODY_BYTES.
- Test 1 passes: `sym.body` works on snapshot-loaded symbols.

---

## Phase 16: G16 + G17 -- JVM MemorySegment mmap + Native POSIX mmap for snapshot

**Dependencies**: Phase 15 (BODY_BYTES section present and correct; the main performance benefit of mmap is skipping BODY_BYTES on codegen workloads via demand paging). G16 and G17 are independent platforms; they are placed in the same phase because the `ByteView.Mapped` trait change is shared and must be done once.

**Addresses**: G16 (JVM MemorySegment mmap, FINAL-AUDIT N5, PHASE-7-AUDIT §16), G17 (Native POSIX mmap, FINAL-AUDIT N6, PHASE-7-AUDIT §16).

**Files to produce**:
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/binary/MappedByteView.scala`, `final class MappedByteView(segment: java.lang.foreign.MemorySegment, start: Long, end: Long) extends ByteView`; implements all `ByteView` abstract methods using `segment.get(ValueLayout.JAVA_BYTE, ...)`.
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/binary/MappedByteView.scala`, `final class MappedByteView(ptr: scalanative.unsafe.Ptr[Byte], start: Long, end: Long) extends ByteView`; implements all `ByteView` abstract methods via direct pointer arithmetic.
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/snapshot/JvmMmapReader.scala`, `object JvmMmapReader` with `def open(path: String): (ByteView, java.lang.foreign.Arena) < (Sync & Abort[ReflectError] & Scope)`; calls `java.lang.foreign.Arena.ofShared().allocate(size, 1)` and `MemorySegment.copy`; registers `arena.close()` as a `Scope.ensure` finalizer; returns `MappedByteView(segment, 0, size)`.
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/snapshot/NativeMmapReader.scala`, `object NativeMmapReader` with `def open(path: String): ByteView < (Sync & Abort[ReflectError] & Scope)`; calls POSIX `mmap(2)` FFI; registers `munmap` as `Scope.ensure` finalizer; returns `MappedByteView(ptr, 0, size)`.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala`, add `abstract class ByteView` with `Heap` and `Mapped` subclasses (currently `ByteView` is sealed with only `Heap`); add `MappedByteView` as a platform-specific implementation hook; the shared `Heap` implementation is unchanged.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala`, add an alternative `readMapped(path: String, source: FileSource): Unit < (Sync & Abort[ReflectError] & Scope)` entry point that uses `JvmMmapReader` on JVM and `NativeMmapReader` on Native, falling back to `read` on JS; `Classpath.openCached` calls `readMapped` preferentially.
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`, add two tests.

**Files to delete**: none.

**Public API additions**: none (mmap is an internal optimization; the public `openCached` API is unchanged).
**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
1. `SnapshotRoundTripTest` (new, jvmOnly): `openCached` on a warm snapshot uses the mmap path on JVM; verify by asserting that the loaded classpath has the same FQN set as the cold-load classpath and that the snapshot file was NOT re-decoded (check that a counter tracking `decodeTastyBytes` calls is zero after the warm load).
2. `SnapshotRoundTripTest` (new, jvmOnly): a mmap-loaded snapshot is readable after the classpath scope exits (test that `Scope.ensure` fired `arena.close()` and that subsequent reads of the mapped segment surface as `ClasspathClosed` rather than a JVM segfault; use a deliberate post-close `sym.parents` call and assert `Abort.fail(ClasspathClosed)`).

**Total new tests**: 2 (jvmOnly; Native mmap is smoke-tested via the existing Native compile path).

**Verification command**:
```
sbt 'kyo-reflectJVM/testOnly kyo.SnapshotRoundTripTest'
```
Plus Native compile check:
```
sbt 'kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `MappedByteView.scala` present in both `jvm/` and `native/` source trees.
- `JvmMmapReader.scala` and `NativeMmapReader.scala` present.
- Test 1 (jvmOnly) passes confirming warm load uses mmap path.
- Test 2 (jvmOnly, post-close) passes confirming no JVM segfault.

---

## Phase 17: G18 -- Benchmark harness

**Dependencies**: Phase 16 (entire v2 feature set complete; the benchmark exercises the full stack including mmap-loaded snapshots, body decode, subtype checks). This is the last phase because it measures the complete implementation.

**Addresses**: G18 (Benchmarking absent, DESIGN.md §20).

**Files to produce**:
- `kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ReflectBench.scala`, `object ReflectBench` with a `main` entry that runs six workloads from DESIGN.md §20, each timed with `System.nanoTime`, 5 warm-up iterations then 10 measurement iterations, printing median and p95 per workload:
  1. Cold-load the kyo-reflect-fixtures TASTy files (20+ classes), enumerate all top-level classes.
  2. Cold-load with snapshot cache miss (delete any cached snapshot first).
  3. Warm-load with snapshot cache hit (run workload 2 first, then time the reload).
  4. Per-FQN lookup of 50 fixture class names (warm cache).
  5. `declarations` enumeration on a class with 20+ declared members.
  6. Schema-driven traversal via `cp.query[SimpleSig].run` where `case class SimpleSig(name: Name, flags: Flags) derives Reflect.Reads`.

**Files to modify**:
- `build.sbt`, add `lazy val kyoReflectBench = crossProject(JVMPlatform).crossType(CrossType.Pure).in(file("kyo-reflect-bench"))` depending on `kyoReflect.jvm`; no test dependency on `kyo-reflect-fixtures` (bench uses the real kyo-reflect module's own fixture TASTy files).

**Files to delete**: none.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**: none.

**Tests** (non-assertion benchmarks are not "tests" in the JUnit sense; the bench module has a `main` entry, not test classes; zero new JUnit tests from this phase):
- The bench module must compile without errors.
- Running `sbt 'kyo-reflect-benchJVM/run'` must print six workload results without throwing.

**Total new tests**: 0 (compile + run check, not JUnit assertions).

**Verification command**:
```
sbt 'kyo-reflect-benchJVM/compile'
```
Then (manual run, not in CI):
```
sbt 'kyo-reflect-benchJVM/run'
```

**Supervisor checks**:
- `kyo-reflect-bench` module present in `build.sbt`.
- `ReflectBench.scala` present with all six workloads.
- `kyo-reflect-benchJVM/compile` succeeds.

---

## Summary table

| Phase | Name | Gap(s) | New tests | Cumulative (v2) |
|-------|------|--------|-----------|-----------------|
| 1  | AllowUnsafe cleanup + Resolver wiring | FINAL-AUDIT W1,W4,W5 | 1 | 1 |
| 2  | Phase C UnresolvedRef resolution | G13 | 3 | 4 |
| 3  | Wire parents + typeParams + declarations | G21, G22, G23 | 7 | 11 |
| 4  | Wire companion | G24 | 4 | 15 |
| 5  | Wire declaredType from eager Pass 1 | G20 | 7 | 22 |
| 6  | Comments section reader | G3 | 6 | 28 |
| 7  | Position section reader | G2 | 5 | 33 |
| 8  | Tree body decode (Tree ADT) | G1 | 9 | 42 |
| 9  | Subtype checking | G5 | 9 | 51 |
| 10 | Scala 2 pickle reader | G4 | 7 | 58 |
| 11 | JPMS module-info.class | G6 | 6 | 64 |
| 12 | Hand-written Reads touchedFields | G11 | 2 | 66 |
| 13 | InconsistentClasspath UUID type | G19 | 1 | 67 |
| 14 | Snapshot inputDigest fix | G15 | 1 | 68 |
| 15 | BODY_BYTES KRFL section | G14 | 2 | 70 |
| 16 | JVM mmap + Native mmap | G16, G17 | 2 | 72 |
| 17 | Benchmark harness | G18 | 0 | 72 |

**Total new v2 tests**: 72 (plus 36 that strengthen or modify existing v1 tests without adding new leaves).
**Total v1+v2 tests**: 203 (v1) + 72 (v2 new) = **275 tests**.

Note: IMPROVEMENT-ANALYSIS documents that some gaps (Resolver wiring in Phase 1 restoring the `sym1 eq sym2` invariant in test 19) modify existing tests rather than adding new leaves. These are counted as modifications, not new leaves, in the table above.

---

## DESIGN.md section coverage cross-reference

| DESIGN.md section | Closed / extended by v2 phase |
|---|---|
| §2 Performance Targets | Phase 17 (benchmark) |
| §15 Phase C UnresolvedRef | Phase 2 |
| §7 Symbol.parents | Phase 3 |
| §7 Symbol.typeParams | Phase 3 |
| §7 Symbol.declarations | Phase 3 |
| §7 Symbol.companion | Phase 4 |
| §7 Symbol.declaredType | Phase 5 |
| §6 Comments section | Phase 6 |
| §6 Positions section | Phase 7 |
| §6 Tree body decode | Phase 8 |
| §24 Tree body decoding (out-of-scope item) | Phase 8 (closes deferral) |
| §24 Subtype checking | Phase 9 (closes deferral) |
| §24 Scala 2 pickle reader | Phase 10 (closes deferral) |
| §24 Java module-info.class | Phase 11 (closes deferral) |
| §24 Hand-written Reads touchedFields | Phase 12 (closes deferral) |
| §24 Position section | Phase 7 (closes deferral) |
| §24 Comments section | Phase 6 (closes deferral) |
| §12 ReflectError.InconsistentClasspath | Phase 13 |
| §16 inputDigest | Phase 14 |
| §16 BODY_BYTES | Phase 15 |
| §16 JVM mmap | Phase 16 |
| §16 Native mmap | Phase 16 |
| §20 Benchmarking | Phase 17 |
