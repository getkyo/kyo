# 05 Plan: Remediate kyo-tasty audit findings (atomic re-merge)

Task type: refactor
Cites design: ./02-design.md (patched)
Cites invariants: ./04-invariants.md
Cites resolutions: ./03a-open-resolutions.md
Cites findings: ./audit-findings.md
Cites steering: ./steering.md (atomic phase sizing section)

Module: kyo-tasty
crossPlatforms: [jvm, js, native]

Phase 01 (Rewrite documentation) shipped at SHA cc3028881 and covers findings L1, L2, L3, L4, L6, L7, A1, M10-doc, Q-008. INV-019, INV-020, INV-021, INV-026 are LANDED by Phase 01. Phase 01 is excluded from this plan. The plan applies the steering rule "one conceptual change per phase" rather than "one accessor edit per phase". Phase count is 60.

## Cross-reference index

Every remaining finding code and INV maps to one or more phases.

| Code | Phase(s) |
|------|----------|
| L5 | 14a |
| A2 | 02f |
| A3 | 02g |
| A4 | 02a, 02b, 02c, 02d, 02e |
| C1 | 04a |
| C2 | 06 |
| C3 | 05c |
| C4 | 03a |
| M1 | 18a, 18b, 18c, 18d, 18e |
| M2 | 17 |
| M3 | 16 |
| M4 | 19a, 19b |
| M5 | 20a, 20b, 20c, 20d, 20e, 20f |
| M6 | 15 |
| M7 | 10 |
| M8 | 11 |
| M9 | 12 |
| M10 | 16, 19b |
| B1 | 03a |
| B2 | 04a |
| B3 | 04a |
| B4 | 03a |
| B5 | 09 |
| B6 | 04b |
| B7 | 03a |
| B8 | 08a |
| B9 | 08b |
| B10 | 03b |
| B11 | 04c |
| B12 | 07a |
| B13 | 07b |
| B14 | 05a |
| B15 | 05b |
| T1 | 13 |
| T2 | 21a-21h |
| T3 | 14b |
| T4 | 22a-22d |
| T5 | 23a, 23b |
| T6 | 25b |
| T7 | 24a |
| T8 | 24b |
| INV-001 | 02a |
| INV-002 | 02c |
| INV-003 | 19a |
| INV-004 | 10 |
| INV-005 | 18e |
| INV-006 | 14a |
| INV-008 | 11 |
| INV-009 | 06 |
| INV-010 | 03a |
| INV-011 | 02e |
| INV-012 | 04a |
| INV-013 | 16 |
| INV-014 | 17 |
| INV-015 | 19b |
| INV-016 | 15 |
| INV-017 | 20f |
| INV-018 | 04b |
| INV-022 | 26 |
| INV-023 | 19a |
| INV-024 | 20f |
| INV-025 | 02f |
| INV-027 | 27 |

INVs landed by Phase 01 (not re-listed above): INV-007, INV-019 (re-attributed to Phase 08a; Phase 01 actually produced INV-007, INV-020, INV-021, INV-026), INV-020, INV-021, INV-026. INV-019 produced_by reads "Phase 8" in the ledger and lands at Phase 08a in this plan.

---

## Phase 02a: Propagate AllowUnsafe through Symbol accessors

Depends on: none.

Every routine `Symbol` accessor (and the `Name.asString` extension that the Symbol surface re-exports) drops `import AllowUnsafe.embrace.danger` from its body and gains `(using AllowUnsafe)` in its signature per CONTRIBUTING.md §828 option 1. One conceptual change: "the proof propagates through the signature instead of hiding inside the body" applied uniformly across the 10 accessors that share the `Tasty.scala` Symbol-accessor surface. Produces INV-001 (introducing the convention).

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: Name.asString extension (lines 58-64), Symbol.fullName (545-549), Symbol.isPackageObject (554-558), Symbol.scaladoc (568-573), Symbol.position (582-587), Symbol.declaredType (604-611), Symbol.parents (617-622), Symbol.typeParams (628-633), Symbol.declarations (639-644), Symbol.companion (654-688).

  ```scala
  // kyo-tasty/shared/src/main/scala/kyo/Tasty.scala ; BEFORE (representative shape; all 10 sites follow this shape)
  extension (n: Name)
      def asString: String =
          import AllowUnsafe.embrace.danger
          n.string.get()

  def fullName: Name =
      import AllowUnsafe.embrace.danger
      _fullNameOnce.get()

  def isPackageObject: Boolean =
      import AllowUnsafe.embrace.danger
      flags.contains(Flag.Module) && name.string.get() == "package"

  def scaladoc: Maybe[String] =
      import AllowUnsafe.embrace.danger
      if _scaladoc.isSet then _scaladoc.get() else Maybe.Absent

  def position: Maybe[Position] =
      import AllowUnsafe.embrace.danger
      if _position.isSet then _position.get() else Maybe.Absent

  def declaredType: Type =
      if kind == SymbolKind.Package then
          throw new IllegalArgumentException("Symbol.declaredType is not available for Package symbols")
      else
          import AllowUnsafe.embrace.danger
          _declaredType.get()

  def parents: Chunk[Type] =
      import AllowUnsafe.embrace.danger
      _parents.get()

  def typeParams: Chunk[Symbol] =
      import AllowUnsafe.embrace.danger
      _typeParams.get()

  def declarations: Chunk[Symbol] =
      import AllowUnsafe.embrace.danger
      _declarations.get()

  def companion: Maybe[Symbol] =
      import AllowUnsafe.embrace.danger
      // existing body using inner name.asString calls
      ...

  // kyo-tasty/shared/src/main/scala/kyo/Tasty.scala ; AFTER
  extension (n: Name)
      /** Decode the interned bytes to a String. Requires (using AllowUnsafe) because the underlying OnceCell.get() is unsafe-tier. */
      def asString(using AllowUnsafe): String = n.string.get()

  def fullName(using AllowUnsafe): Name = _fullNameOnce.get()

  def isPackageObject(using AllowUnsafe): Boolean =
      flags.contains(Flag.Module) && name.string.get() == "package"

  def scaladoc(using AllowUnsafe): Maybe[String] =
      if _scaladoc.isSet then _scaladoc.get() else Maybe.Absent

  def position(using AllowUnsafe): Maybe[Position] =
      if _position.isSet then _position.get() else Maybe.Absent

  def declaredType(using AllowUnsafe): Type =
      if kind == SymbolKind.Package then
          throw new IllegalArgumentException("Symbol.declaredType is not available for Package symbols")
      else _declaredType.get()

  def parents(using AllowUnsafe): Chunk[Type] = _parents.get()
  def typeParams(using AllowUnsafe): Chunk[Symbol] = _typeParams.get()
  def declarations(using AllowUnsafe): Chunk[Symbol] = _declarations.get()

  def companion(using AllowUnsafe): Maybe[Symbol] =
      // same body, no import danger; inner name.asString calls now legal because (using AllowUnsafe) is in scope
      ...
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Name.asString`: gains `(using AllowUnsafe)`.
- `Symbol.fullName`, `Symbol.isPackageObject`, `Symbol.scaladoc`, `Symbol.position`, `Symbol.declaredType`, `Symbol.parents`, `Symbol.typeParams`, `Symbol.declarations`, `Symbol.companion`: each gains `(using AllowUnsafe)`.

### Tests
Total: 5. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala` and `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`.

1. `TastyTest.scala`: every Symbol accessor signature carries `(using AllowUnsafe)`
   - Given: source `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` read as String.
   - When: regex search counts occurrences of `def (fullName|isPackageObject|scaladoc|position|declaredType|parents|typeParams|declarations|companion|asString)\(using AllowUnsafe\)`.
   - Then: count equals 10.
   - Pins: INV-001.

2. `TastyTest.scala`: no `import AllowUnsafe.embrace.danger` remains in any migrated accessor body
   - Given: extracted bodies of the 10 accessors above.
   - When: substring scan for `import AllowUnsafe.embrace.danger`.
   - Then: each body's count is 0; total across the 10 is 0.
   - Pins: INV-001.

3. `TastySymbolTest.scala`: `Symbol.fullName` returns `"scala.Predef"` under explicit proof
   - Given: `cp.findClass("scala.Predef").get` produces `sym`; `given AllowUnsafe = AllowUnsafe.embrace.danger` in scope.
   - When: `sym.fullName.asString` evaluated.
   - Then: returns String `"scala.Predef"`.
   - Pins: INV-001 (Symbol.fullName case).

4. `TastySymbolTest.scala`: `Symbol.parents` includes `scala.AnyVal` for `scala.Int`
   - Given: `cp.findClass("scala.Int").get` produces `sym`; AllowUnsafe in scope.
   - When: `sym.parents.map(_.show)`.
   - Then: returned Chunk contains `"scala.AnyVal"`.
   - Pins: INV-001 (parents case).

5. `TastySymbolTest.scala`: class-Symbol's companion returns Module Symbol
   - Given: `cp.findClass("scala.Option").get` (class symbol); AllowUnsafe in scope.
   - When: `sym.companion`.
   - Then: result is `Maybe.Present(modSym)` with `modSym.kind == SymbolKind.Module` and `modSym.name.asString == "Option"`.
   - Pins: INV-001 (companion case).

### Consumed invariants
None.

### Produced invariants
- INV-001: AllowUnsafe routine-accessor signatures take `(using AllowUnsafe)` with no `import danger` inside the body.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 150.

---

## Phase 02b: Propagate AllowUnsafe through Classpath pure accessors

Depends on: Phase 02a.

Every internal `Classpath` accessor on the synchronous pure-read surface (10 sites: pureClass, purePackage, pureModule, pureTopLevelClasses, purePackages, accumulatedErrors, allSymbols, transitionToReady, close, isClosed) drops `import AllowUnsafe.embrace.danger` and gains `(using AllowUnsafe)`. One conceptual change: the Classpath state-ref accessor family adopts §828 propagate-the-proof. The mechanism underneath (`stateRef.unsafe.get/set`) differs from Symbol's OnceCell, so this is a distinct conceptual application of INV-001 to the Classpath surface.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`: pureClass (70), purePackage (80), pureModule (90), pureTopLevelClasses (100), purePackages (110), accumulatedErrors (138), allSymbols (156), transitionToReady (214), close (220), isClosed (27).

  ```scala
  // kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala ; BEFORE (representative)
  private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol] =
      import AllowUnsafe.embrace.danger
      stateRef.unsafe.get() match
          case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
          case _                        => Maybe.Absent

  private[kyo] def transitionToReady(state: Classpath.State.Ready): Unit =
      import AllowUnsafe.embrace.danger
      stateRef.unsafe.set(state)

  private[kyo] def close(): Unit =
      import AllowUnsafe.embrace.danger
      stateRef.unsafe.set(Classpath.State.Closed)

  private[kyo] def isClosed: Boolean =
      import AllowUnsafe.embrace.danger
      stateRef.unsafe.get() match
          case Classpath.State.Closed => true
          case _                      => false

  // ... same shape for purePackage, pureModule, pureTopLevelClasses, purePackages,
  //     accumulatedErrors, allSymbols ...

  // kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala ; AFTER
  private[kyo] def pureClass(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol] =
      stateRef.unsafe.get() match
          case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
          case _                        => Maybe.Absent

  private[kyo] def transitionToReady(state: Classpath.State.Ready)(using AllowUnsafe): Unit =
      stateRef.unsafe.set(state)

  private[kyo] def close()(using AllowUnsafe): Unit =
      stateRef.unsafe.set(Classpath.State.Closed)

  private[kyo] def isClosed(using AllowUnsafe): Boolean =
      stateRef.unsafe.get() match
          case Classpath.State.Closed => true
          case _                      => false

  // purePackage, pureModule, pureTopLevelClasses, purePackages, accumulatedErrors, allSymbols
  // all migrate identically: drop the import, add (using AllowUnsafe) to the signature.
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Classpath.pureClass`, `purePackage`, `pureModule`, `pureTopLevelClasses`, `purePackages`, `accumulatedErrors`, `allSymbols`, `transitionToReady`, `close`, `isClosed`: each gains `(using AllowUnsafe)`. All private[kyo].

### Tests
Total: 4. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ClasspathPureAccessorTest.scala` (new, prefix-match for query/Classpath.scala).

1. `ClasspathPureAccessorTest.scala`: every Classpath accessor signature carries `(using AllowUnsafe)`
   - Given: source `Classpath.scala` read as String.
   - When: regex counts `def (pureClass|purePackage|pureModule|pureTopLevelClasses|purePackages|accumulatedErrors|allSymbols|transitionToReady|close|isClosed)\(?[^)]*\)?\(using AllowUnsafe\)`.
   - Then: count equals 10.
   - Pins: INV-001 (Classpath case).

2. `ClasspathPureAccessorTest.scala`: `pureClass` returns Present on Ready classpath
   - Given: Ready Classpath with `fqnIndex` containing `"scala.Int"`; AllowUnsafe in scope.
   - When: `cp.pureClass("scala.Int")`.
   - Then: result is `Maybe.Present(intSym)`; `intSym.fullName.asString == "scala.Int"`.
   - Pins: INV-001.

3. `ClasspathPureAccessorTest.scala`: `transitionToReady` then `pureClass` observable
   - Given: fresh Classpath in Building state; a Ready-state value `s` with `"scala.Int"`; AllowUnsafe.
   - When: `cp.transitionToReady(s)`; then `cp.pureClass("scala.Int")`.
   - Then: returns `Maybe.Present(_)`.
   - Pins: INV-001.

4. `ClasspathPureAccessorTest.scala`: `close` then `isClosed` returns true
   - Given: Ready Classpath; AllowUnsafe.
   - When: `cp.close()`; then `cp.isClosed`.
   - Then: returns `true`.
   - Pins: INV-001.

### Consumed invariants
- INV-001.

### Produced invariants
None (reaffirms INV-001 on the Classpath surface).

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ClasspathPureAccessorTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 02c: Propagate AllowUnsafe through ClasspathRef accessors

Depends on: Phase 02a.

`ClasspathRef.get` and `ClasspathRef.isAssigned` drop `import AllowUnsafe.embrace.danger` and gain `(using AllowUnsafe)`. The underlying mechanism (`SingleAssign` slot) is distinct from Symbol's OnceCell and Classpath's AtomicRef stateRef. Completes the proof-propagation sweep across all three unsafe-tier mechanisms in kyo-tasty. Produces INV-002 (zero per-call `Sync.Unsafe.defer` allocation).

`ClasspathRef.assign` is excluded from this phase: that site remains an §839 case 3 initialization boundary inside the orchestrator.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala` `get` (28), `isAssigned` (35).

  ```scala
  // BEFORE
  def get(): Tasty.Classpath =
      import AllowUnsafe.embrace.danger
      slot.get()
  end get

  def isAssigned: Boolean =
      import AllowUnsafe.embrace.danger
      slot.isSet
  end isAssigned

  // AFTER
  def get()(using AllowUnsafe): Tasty.Classpath =
      slot.get()
  end get

  def isAssigned(using AllowUnsafe): Boolean =
      slot.isSet
  end isAssigned
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `ClasspathRef.get`, `ClasspathRef.isAssigned`: each gains `(using AllowUnsafe)`.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ClasspathRefTest.scala` (new) and `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`.

1. `ClasspathRefTest.scala`: `ClasspathRef.get` requires explicit proof
   - Given: `ClasspathRef` with assigned Classpath; AllowUnsafe in scope.
   - When: `ref.get()`.
   - Then: returns the assigned Classpath.
   - Pins: INV-001 (ClasspathRef case).

2. `ClasspathRefTest.scala`: `ClasspathRef.isAssigned` reflects assignment
   - Given: a fresh `ClasspathRef`; AllowUnsafe.
   - When: `ref.isAssigned`; then `ref.assign(cp)`; then `ref.isAssigned` again.
   - Then: first call returns `false`; second returns `true`.
   - Pins: INV-001.

3. `TastySymbolTest.scala`: zero `Sync.Unsafe.defer` allocation across migrated accessors
   - Given: a Symbol for `scala.Predef`; AllocationCounter measuring between two snapshots; AllowUnsafe.
   - When: 10000 sequential calls covering `sym.fullName`, `sym.parents`, `sym.declarations`, `sym.declaredType`, `sym.typeParams`, `sym.scaladoc`, `sym.position`, `sym.isPackageObject`, `sym.companion`.
   - Then: bytes allocated within 5% of a baseline loop calling only `OnceCell.get()`.
   - Pins: INV-002.

### Consumed invariants
- INV-001.

### Produced invariants
- INV-002: Cold-load and warm-cache paths allocate zero `Sync.Unsafe.defer` closures per Symbol or Classpath accessor call.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ClasspathRefTest kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 40.

---

## Phase 02d: Bridge Symbol.body through Sync.Unsafe.defer

Depends on: Phase 02a.

`Symbol.body` keeps its public `Tree < (Sync & Abort[TastyError])` row but bridges the internal `_bodyOnce.get()` call through `Sync.Unsafe.defer` per CONTRIBUTING.md §833 option 2. The body no longer carries `import AllowUnsafe.embrace.danger`.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.body` (lines 703-747).

  ```scala
  // BEFORE
  def body(using Frame): Tree < (Sync & Abort[TastyError]) =
      if !home.isAssigned then stub("Symbol.body")
      else
          home.get().checkOpen.andThen:
              import AllowUnsafe.embrace.danger
              _bodyOnce.get() match
                  case t: Tree => t
                  case _       => decodeBodyAt(...)
  // AFTER
  def body(using Frame): Tree < (Sync & Abort[TastyError]) =
      if !home.isAssigned then stub("Symbol.body")
      else
          home.get().checkOpen.andThen:
              Sync.Unsafe.defer:
                  _bodyOnce.get() match
                      case t: Tree => t
                      case _       => decodeBodyAt(...)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Symbol.body`: body re-bridges through `Sync.Unsafe.defer`; signature unchanged.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: `Symbol.body` returns decoded Tree on first call
   - Given: classpath loaded with `class Foo { def bar = 42 }`; `sym = cp.findClass("Foo").get.declarations.find(_.name.asString == "bar").get`.
   - When: `sym.body` evaluated.
   - Then: returns a Tree whose root is a method definition for `bar` (CategoryFive DEFDEF or current closest).
   - Pins: A4 Symbol.body bridge.

2. `TreeUnpicklerTest.scala`: `Symbol.body` body has no `import AllowUnsafe.embrace.danger`
   - Given: extracted body of `Symbol.body`.
   - When: substring scan.
   - Then: count is 0.
   - Pins: A4 Symbol.body bridge.

### Consumed invariants
- INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 40.

---

## Phase 02e: Privatize Symbol.TastyOrigin.addrMap

Depends on: Phase 02a.

`Symbol.TastyOrigin.addrMap` loses its public `(using AllowUnsafe)` slot and gains `private[kyo]` visibility. The accessor body keeps `import AllowUnsafe.embrace.danger` per §839 case 3 (internal-only inside an initialization context).

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Symbol.TastyOrigin.addrMap` (lines 862-864).

  ```scala
  // BEFORE
  def addrMap(using AllowUnsafe): IntMap[Tasty.Symbol] =
      if _addrMap.isSet then _addrMap.get() else IntMap.empty
  // AFTER
  private[kyo] def addrMap: IntMap[Tasty.Symbol] =
      // Unsafe: SingleAssign.get() is unsafe-tier; private[kyo] limits callers to kyo.internal.tasty.* §839 case 3 contexts.
      import AllowUnsafe.embrace.danger
      if _addrMap.isSet then _addrMap.get() else IntMap.empty
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Symbol.TastyOrigin.addrMap`: visibility becomes `private[kyo]`; loses the `(using AllowUnsafe)` parameter.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`.

1. `TastyTest.scala`: external code cannot reference `addrMap`
   - Given: a test under package `other` attempting `kyo.Tasty.Symbol.TastyOrigin.addrMap`.
   - When: compile.
   - Then: compilation fails with `private[kyo]` access error.
   - Pins: INV-011.

2. `TastyTest.scala`: `addrMap` reachable from `kyo.internal.tasty.symbol`
   - Given: a test under package `kyo.internal.tasty.symbol` referencing `addrMap`.
   - When: compile.
   - Then: succeeds.
   - Pins: INV-011 internal reachability.

### Consumed invariants
- INV-001.

### Produced invariants
- INV-011: `Symbol.TastyOrigin.addrMap` is not publicly accessible.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents.

estimated_loc: 15.

---

## Phase 02f: Delegate Classpath.open one-arg overload

Depends on: Phase 02a.

`Classpath.open(roots)` calls `Classpath.open(roots, strict = false)` (canonical two-arg form) per CONTRIBUTING.md §358-§382. No default-parameter shim; the one-arg form is the ergonomic shorthand.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Classpath.open` (899-904).

  ```scala
  // BEFORE
  def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
      openImpl(roots, strict = false)
  def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
      openImpl(roots, strict)
  // AFTER
  /** One-arg variant: delegates to the canonical two-arg form with `strict = false`. */
  def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
      open(roots, strict = false)
  /** Canonical two-arg form. Soft-fail when strict=false; fail-fast when strict=true. */
  def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
      openImpl(roots, strict)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Classpath.open(roots)`: body now delegates to `open(roots, strict = false)`.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`.

1. `TastyTest.scala`: `Classpath.open(roots)` delegates to `(roots, strict=false)`
   - Given: source `Tasty.scala` read as String.
   - When: extract the body of the one-arg `open`.
   - Then: the body contains the literal substring `open(roots, strict = false)`.
   - Pins: INV-025.

### Consumed invariants
None.

### Produced invariants
- INV-025: The no-strict `Classpath.open(roots)` overload delegates by name to the canonical `Classpath.open(roots, strict)` with `strict = false` explicit; no default-parameter shim.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 02g: Canonicalize OnceCell unsafe comments

Depends on: none.

`OnceCell.scala` inline comments around three `asInstanceOf` sites adopt the canonical `// Unsafe: ...` prefix per CONTRIBUTING.md §415.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala` (lines 37, 41, 45).

  ```scala
  // BEFORE
  if cached ne OnceCell.Unset then
      cached.asInstanceOf[A]  // safe by Unset sentinel
  ...
      val v = init().asInstanceOf[AnyRef]  // store in AtomicReference[AnyRef]
  ...
      ref.get().asInstanceOf[A]  // post-CAS read
  // AFTER
  if cached ne OnceCell.Unset then
      // Unsafe: AnyRef-sentinel pattern; ne-Unset guarantees the stored value is A.
      cached.asInstanceOf[A]
  ...
      // Unsafe: we store A as AnyRef to coexist with the Unset sentinel in AtomicReference[AnyRef].
      val v = init().asInstanceOf[AnyRef]
  ...
      // Unsafe: same sentinel pattern; ref.get() now holds the CAS-winning value.
      ref.get().asInstanceOf[A]
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala` (new).

1. `OnceCellTest.scala`: canonical `// Unsafe:` comments present
   - Given: source `OnceCell.scala` read as text.
   - When: count occurrences of `// Unsafe:` on lines preceding `asInstanceOf` calls.
   - Then: count equals exactly 3.
   - Pins: A3.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.OnceCellTest'`. JS / Native equivalents.

estimated_loc: 10.

---

## Phase 03a: Bound binary input primitives

Depends on: none.

`Varint.readNat` and `Varint.readLongNat` cap continuation byte counts (5 and 10 respectively) and throw `MalformedVarintException(byteOffset, msg)` on overflow. `ByteView.subView` rejects out-of-range `from`/`until`. `NameUnpickler` adds bounds checks before every indexed name-table lookup. `SectionIndex.readSync` bounds-checks `nameRef` against `names.length` and rejects negative `sectionLen`. All four primitives propagate to `TastyError.MalformedSection` via the surrounding decode catch. One conceptual change: "every binary-input primitive rejects out-of-bounds reads structurally rather than via uncaught AIOOBE". Produces INV-010.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala` `readNat`, `readLongNat` (42-51).

  ```scala
  // BEFORE
  def readLongNat(view: ByteView): Long =
      var b = 0L
      var x = 0L
      while
          b = view.readByte() & 0xffL
          x = (x << 7) | (b & 0x7fL)
          (b & 0x80L) == 0L
      do ()
      end while
      x
  // AFTER
  def readLongNat(view: ByteView): Long =
      var b     = 0L
      var x     = 0L
      var bytes = 0
      while
          if bytes >= 10 then
              throw new Varint.MalformedVarintException(view.position, "varint: continuation runs past 10 bytes (Long overflow)")
          b = view.readByte() & 0xffL
          x = (x << 7) | (b & 0x7fL)
          bytes += 1
          (b & 0x80L) == 0L
      do ()
      end while
      x
  // readNat: same shape with cap 5 (Int overflow).
  // New exception type:
  class MalformedVarintException(val byteOffset: Long, msg: String) extends RuntimeException(msg)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala` `subView` (90-92).

  ```scala
  // BEFORE
  override def subView(from: Int, until: Int): ByteView.Heap = new Heap(bytes, from, until)
  // AFTER
  override def subView(from: Int, until: Int): ByteView.Heap =
      if from < 0 || until < from || until > bytes.length then
          throw new ArrayIndexOutOfBoundsException(s"ByteView.subView: from=$from until=$until length=${bytes.length}")
      new Heap(bytes, from, until)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala` (73-179, every indexed buf-table read at QUALIFIED, EXPANDED, EXPANDPREFIX, UNIQUE, DEFAULTGETTER, SUPERACCESSOR, INLINEACCESSOR, OBJECTCLASS, BODYRETAINER, SIGNED, TARGETSIGNED).

  ```scala
  // BEFORE (QUALIFIED case, representative)
  case TastyFormat.NameTags.QUALIFIED =>
      val end      = view.readEnd()
      val prefix   = view.readNat()
      val selector = view.readNat()
      view.goto(end)
      val s = buf(prefix).asString + "." + buf(selector).asString
      buf += internString(interner, s)
  // AFTER
  case TastyFormat.NameTags.QUALIFIED =>
      val end      = view.readEnd()
      val prefix   = view.readNat()
      val selector = view.readNat()
      view.goto(end)
      if prefix < 0 || prefix >= buf.length || selector < 0 || selector >= buf.length then
          throw new ArrayIndexOutOfBoundsException(
              s"QUALIFIED nameRef out of range: prefix=$prefix selector=$selector tableSize=${buf.length}"
          )
      val s = buf(prefix).asString + "." + buf(selector).asString
      buf += internString(interner, s)
  // Same shape at all other indexed reads above, including SIGNED/TARGETSIGNED `ps` reads at 144, 162.
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala` `readSync` (54).

  ```scala
  // BEFORE
  private def readSync(view: ByteView, names: Array[Tasty.Name]): SectionIndex =
      val builder = Map.newBuilder[String, (Int, Int)]
      while view.remaining > 0 do
          val nameRef    = view.readNat()
          val sectionLen = view.readNat()
          val offset     = view.position
          val name       = names(nameRef).asString
          builder += (name -> (offset, sectionLen))
          view.goto(offset + sectionLen)
      end while
      new SectionIndex(builder.result())
  // AFTER
  private def readSync(view: ByteView, names: Array[Tasty.Name])(using AllowUnsafe): SectionIndex =
      val builder = Map.newBuilder[String, (Int, Int)]
      while view.remaining > 0 do
          val nameRef    = view.readNat()
          val sectionLen = view.readNat()
          val offset     = view.position
          if nameRef < 0 || nameRef >= names.length then
              throw new ArrayIndexOutOfBoundsException(s"SectionIndex: nameRef=$nameRef out of range (names.length=${names.length}) at byte ${view.position}")
          if sectionLen < 0 then
              throw new ArrayIndexOutOfBoundsException(s"SectionIndex: negative section length $sectionLen at byte ${view.position}")
          val name = names(nameRef).asString
          builder += (name -> (offset, sectionLen))
          view.goto(offset + sectionLen)
      end while
      new SectionIndex(builder.result())
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None (internal).

### Tests
Total: 8. Tests live in `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala` (new), `ByteViewTest.scala`, `NameUnpicklerTest.scala` (new), `SectionIndexTest.scala` (new).

1. `VarintTest.scala`: `readNat` rejects > 5-byte continuation
   - Given: `ByteView.Heap` over `Array.fill(6)(0x80.toByte)`.
   - When: `Varint.readNat(view)`.
   - Then: `MalformedVarintException` with message containing `"continuation runs past 5"`.
   - Pins: INV-010, B4.

2. `VarintTest.scala`: `readLongNat` rejects > 10-byte continuation
   - Given: `Array.fill(11)(0x80.toByte)`.
   - When: `Varint.readLongNat(view)`.
   - Then: `MalformedVarintException` with `"continuation runs past 10"`.
   - Pins: INV-010, B4.

3. `VarintTest.scala`: `readLongNat` accepts exact 10-byte continuation
   - Given: nine `0x81` bytes then `0x01` terminator.
   - When: `Varint.readLongNat(view)`.
   - Then: returns the canonical Long encoding (no exception).
   - Pins: INV-010 boundary.

4. `ByteViewTest.scala`: `subView` rejects negative `from`
   - Given: 10-byte heap view; args `from = -1`, `until = 5`.
   - When: `view.subView(-1, 5)`.
   - Then: `ArrayIndexOutOfBoundsException` with `"from=-1"`.
   - Pins: INV-010, B7.

5. `ByteViewTest.scala`: `subView` rejects `until > length`
   - Given: 10-byte heap view; args `0, 11`.
   - When: `view.subView(0, 11)`.
   - Then: exception containing `"until=11"`.
   - Pins: INV-010, B7.

6. `NameUnpicklerTest.scala`: QUALIFIED with out-of-range prefix yields MalformedSection
   - Given: synthetic stream: one UTF8 entry, then a QUALIFIED with prefix=99.
   - When: `NameUnpickler.read(view, interner)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Names", reason, _))` where reason contains `"prefix=99"`.
   - Pins: INV-010, B1.

7. `SectionIndexTest.scala`: nameRef out-of-range yields MalformedSection
   - Given: synthetic section-index bytes whose first entry's nameRef encodes 99; `names.length == 3`.
   - When: `SectionIndex.read(view, names)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("SectionIndex", reason, _))` containing `"nameRef=99 out of range"`.
   - Pins: INV-010, C4.

8. `SectionIndexTest.scala`: negative sectionLen yields MalformedSection
   - Given: bytes encoding nameRef 0 (valid) followed by Varint-encoded negative-interpreted length.
   - When: decode via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("SectionIndex", _, _))`.
   - Pins: INV-010.

### Consumed invariants
None.

### Produced invariants
- INV-010: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with a structured `TastyError.MalformedSection` rather than an uncaught exception. (Interner case lands in Phase 03b which reaffirms INV-010 on that surface.)

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.VarintTest kyo.ByteViewTest kyo.NameUnpicklerTest kyo.SectionIndexTest'`. JS / Native equivalents.

estimated_loc: 230.

---

## Phase 03b: Validate Interner bytesEqual offset

Depends on: none.

`Interner.bytesEqual` verifies `offset + length <= bytes.length` and rejects negative offsets or lengths before slice comparison. Caller-bug containment; distinct from binary-input primitives because Interner sits behind the deduplication surface, not the decode loop.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala` `bytesEqual` (125-134).

  ```scala
  // BEFORE
  private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
      entry.bytes.length == length && {
          var i = 0
          var eq = true
          while eq && i < length do
              if entry.bytes(i) != bytes(offset + i) then eq = false
              i += 1
          eq
      }
  // AFTER
  private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
      if offset < 0 || length < 0 || offset + length > bytes.length || offset + length < 0 then
          throw new ArrayIndexOutOfBoundsException(s"Interner.bytesEqual: offset=$offset length=$length bytes.length=${bytes.length}")
      entry.bytes.length == length && {
          var i = 0
          var eq = true
          while eq && i < length do
              if entry.bytes(i) != bytes(offset + i) then eq = false
              i += 1
          eq
      }
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala`.

1. `InternerTest.scala`: `bytesEqual` rejects `offset + length > bytes.length`
   - Given: interner with entry `[1, 2, 3]`; caller bytes length 5; args `offset = 4`, `length = 2`.
   - When: `interner.intern(bytes, 4, 2)` triggers `bytesEqual`.
   - Then: `ArrayIndexOutOfBoundsException` with `"offset=4 length=2 bytes.length=5"`.
   - Pins: B10, INV-010.

### Consumed invariants
- INV-010.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InternerTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 04a: Widen JAR offsets to 64-bit

Depends on: none.

`JarCentralDirectory` and `JarMappedReader` operate on `Long` offsets throughout. Replaces `.toInt` truncations at the nine cited sites in JarCentralDirectory (140, 142, 174, 189, 342, 345, 526, 560, 570) and the three in JarMappedReader (65, 72, 85) with `Long` arithmetic; adds Zip64 EOCD locator detection. One conceptual change: "stop truncating Long to Int in JAR offset arithmetic across the JAR-reading surface". Produces INV-012.

### Files to produce
None.

### Files to modify
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` (140, 142, 174, 189, 342, 345, 526, 560, 570) plus Zip64 EOCD locator detection.

  ```scala
  // BEFORE (representative)
  val cenBufSize = (eocdOffset - cenOffset).toInt.max(0)
  val cenBuf     = new Array[Byte](cenBufSize)
  // AFTER
  val cenBufSize: Long = (eocdOffset - cenOffset).max(0L)
  if cenBufSize > Int.MaxValue then
      throw new TastyError.MalformedSection.Toss("jar", s"central directory size $cenBufSize exceeds 2GB; Zip64 required")
  val cenBuf = new Array[Byte](cenBufSize.toInt)

  // ADDED Zip64 EOCD locator detection
  private def findZip64Eocd(buf: Array[Byte], eocdLocatorOffset: Long): Maybe[Long] =
      val sig = readInt32LE(buf, (eocdLocatorOffset - 20).toInt)
      if sig == 0x07064b50 then
          Present(readInt64LE(buf, (eocdLocatorOffset - 20 + 8).toInt))
      else Absent
  // Caller: if findZip64Eocd is Present, use its returned offset as the canonical CEN offset (Long).
  // Same Long-widening shape applies to all 9 cited offset sites.
  ```

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala` (65, 72, 85).

  ```scala
  // BEFORE
  val dataOffset = entry.lfhOffset.toInt + 30 + nameLen + extraLen
  channel.read(dataOffset, ...)
  // AFTER
  val dataOffset: Long = entry.lfhOffset + 30L + nameLen.toLong + extraLen.toLong
  if dataOffset < 0L || dataOffset > channel.size() then
      throw new TastyError.MalformedSection.Toss("jar", s"LFH dataOffset $dataOffset out of range (channel.size=${channel.size()})")
  channel.read(dataOffset, ...)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` and `JvmFileSourceTest.scala`.

1. `JarCentralDirectoryTest.scala`: central directory size > 2GB without Zip64 yields MalformedSection
   - Given: synthetic JAR with EOCD claiming CEN size `2_500_000_000L`; no Zip64 locator.
   - When: `JarCentralDirectory.read(jarPath)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("jar", reason, _))` containing `"exceeds 2GB"`.
   - Pins: C1, INV-012.

2. `JarCentralDirectoryTest.scala`: Zip64 EOCD locator detected
   - Given: synthetic JAR with EOCD locator at offset N carrying signature `0x07064b50`; Zip64 EOCD points to CD at `2_500_000_000L`.
   - When: `JarCentralDirectory.read(jarPath)`.
   - Then: first entry's `lfhOffset` equals `2_500_000_000L`.
   - Pins: B3, INV-012.

3. `JvmFileSourceTest.scala`: 64-bit LFH offset round-trip
   - Given: Zip64 JAR with entry `lfhOffset == Int.MaxValue + 1L`.
   - When: `JarMappedReader.read(jar, entry)`.
   - Then: returned bytes match expected via `Arrays.equals`.
   - Pins: B2, INV-012.

### Consumed invariants
None.

### Produced invariants
- INV-012: `JarCentralDirectory` and `JarMappedReader` handle 64-bit offsets and Zip64 archives correctly with no Int truncation past 2GB.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JarCentralDirectoryTest kyo.JvmFileSourceTest'`.

estimated_loc: 150.

---

## Phase 04b: Widen MappedByteView cursor to 64-bit

Depends on: none.

`MappedByteView` int-returning accessors widen to `Long` where the on-disk offsets exceed 2GB. `ByteView` trait gains Long-aware methods alongside Int wrappers via `Math.toIntExact`. Distinct from JAR offsets: this is the mmap-backed snapshot view, not the central-directory reader.

### Files to produce
None.

### Files to modify
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala` (41-58).

  ```scala
  // BEFORE
  def peekByte(at: Int): Byte
  def readEnd(): Int
  def subView(from: Int, until: Int): MappedByteView
  def goto(addr: Int): Unit
  def remaining: Int = (end - cursor).toInt
  def position: Int  = cursor.toInt
  // AFTER
  def peekByte(at: Long): Byte
  def readByte(): Byte =
      if cursor > Int.MaxValue then
          throw new IllegalStateException(s"MappedByteView cursor $cursor exceeds Int.MaxValue; mmap segment overflow")
      buf.get(cursor.toInt)
  def readEnd(): Long = cursor + Varint.readNat(this).toLong
  def subView(from: Long, until: Long): MappedByteView = new MappedByteView(buf, from, until, closed)
  def goto(addr: Long): Unit = cursor = addr
  def remaining: Long = end - cursor
  def position: Long  = cursor
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala`: trait gains Long methods alongside Int wrappers.

  ```scala
  // BEFORE
  def readEnd(): Int
  def subView(from: Int, until: Int): ByteView
  def goto(addr: Int): Unit
  def remaining: Int
  def position: Int
  // AFTER
  def readEnd(): Long
  def subView(from: Long, until: Long): ByteView
  def goto(addr: Long): Unit
  def remaining: Long
  def position: Long
  // Int wrappers for migration:
  final def readEndInt: Int = Math.toIntExact(readEnd())
  final def positionInt: Int = Math.toIntExact(position)
  final def remainingInt: Int = Math.toIntExact(remaining)
  final def gotoInt(addr: Int): Unit = goto(addr.toLong)
  final def subViewInt(from: Int, until: Int): ByteView = subView(from.toLong, until.toLong)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None (internal trait extension).

### Tests
Total: 2. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/MappedByteViewTest.scala` (new).

1. `MappedByteViewTest.scala`: position is Long-typed
   - Given: `MappedByteView(start=0L, end=5_000_000_000L, cursor=3_000_000_000L, closed=false)`.
   - When: `view.position`.
   - Then: equals `3_000_000_000L`.
   - Pins: INV-018, B6.

2. `MappedByteViewTest.scala`: readByte past Int.MaxValue raises IllegalStateException
   - Given: `MappedByteView(cursor=Int.MaxValue.toLong + 1L)`.
   - When: `view.readByte()`.
   - Then: `IllegalStateException` with `"mmap segment overflow"`.
   - Pins: INV-018, B6.

### Consumed invariants
None.

### Produced invariants
- INV-018: `MappedByteView` accessors that may address > 2GB regions return or accept `Long` offsets.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.MappedByteViewTest'`.

estimated_loc: 80.

---

## Phase 04c: Detect truncated JarCentralDirectory records

Depends on: Phase 04a.

`JarCentralDirectory.parseAllEntries` detects truncated CEN records (declared size > remaining bytes) and emits `MalformedSection`. Distinct from 04a's offset-arithmetic widening: this is malformed-record containment.

### Files to produce
None.

### Files to modify
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` `parseAllEntries`.

  ```scala
  // BEFORE
  while pos < cenBuf.length do
      val nameLen = readShort(cenBuf, pos + 28) & 0xffff
      val record  = readRecord(cenBuf, pos, nameLen, ...)
      pos += record.size
  // AFTER
  while pos < cenBuf.length do
      val nameLen    = readShort(cenBuf, pos + 28) & 0xffff
      val extraLen   = readShort(cenBuf, pos + 30) & 0xffff
      val commentLen = readShort(cenBuf, pos + 32) & 0xffff
      val recordSize = 46 + nameLen + extraLen + commentLen
      if pos + recordSize > cenBuf.length then
          throw new TastyError.MalformedSection.Toss("jar", s"truncated CEN record at $pos: declared size $recordSize exceeds remaining ${cenBuf.length - pos}")
      val record = readRecord(cenBuf, pos, nameLen, ...)
      pos += recordSize
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`.

1. `JarCentralDirectoryTest.scala`: truncated CEN record yields MalformedSection
   - Given: JAR whose CEN record at offset 0 declares `nameLen = 1000` but only 100 bytes follow.
   - When: `JarCentralDirectory.read(jarPath)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("jar", reason, _))` containing `"truncated CEN record"`.
   - Pins: B11.

### Consumed invariants
- INV-012.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JarCentralDirectoryTest'`.

estimated_loc: 50.

---

## Phase 05a: Atomicize JvmFileSource pool registration

Depends on: Phase 02b.

`JvmFileSource.openPool` replaces the two-step `activePool.set` + `Scope.ensure` registration with a single `Scope.acquireRelease` so failure between the steps cannot leak the pool.

### Files to produce
None.

### Files to modify
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JvmFileSource.scala` (149-156).

  ```scala
  // BEFORE
  val pool = new JarPool(...)
  activePool.set(pool)
  Scope.ensure(Sync.defer(pool.close())).andThen {
      ...
  }
  // AFTER
  Scope.acquireRelease(
      acquire = Sync.defer:
          val pool = new JarPool(...)
          activePool.set(pool)
          pool
      ,
      release = pool => Sync.defer(pool.close())
  ).map { pool =>
      ...
  }
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala`.

1. `JvmFileSourceTest.scala`: pool registration atomic on Scope.ensure failure
   - Given: Scope whose ensure throws on first invocation.
   - When: `JvmFileSource.openPool(...)`; test observes `activePool`.
   - Then: after throw, `activePool.get()` is the prior pool (or null); the new pool's `close()` ran via the release branch.
   - Pins: B14.

### Consumed invariants
- INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JvmFileSourceTest'`.

estimated_loc: 50.

---

## Phase 05b: Sanitize JarMappedReader channel close

Depends on: none.

`JarMappedReader.channel.map` failure replaces the IOException with a fresh exception that names the file path; the channel reference is not propagated.

### Files to produce
None.

### Files to modify
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala` (119-132).

  ```scala
  // BEFORE
  val raf = new RandomAccessFile(jarPath, "r")
  try
      val channel = raf.getChannel
      try
          val mapped = channel.map(MapMode.READ_ONLY, 0L, raf.length())
          ...
      finally channel.close()
  finally raf.close()
  // AFTER
  val raf = new RandomAccessFile(jarPath, "r")
  var mapped: MappedByteBuffer = null
  try
      val channel = raf.getChannel
      try
          mapped = channel.map(MapMode.READ_ONLY, 0L, raf.length())
      catch
          case ex: java.io.IOException =>
              throw new java.io.IOException(s"map failed for $jarPath: ${ex.getMessage}")
      finally channel.close()
  finally raf.close()
  mapped
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala`.

1. `JvmFileSourceTest.scala`: `channel.map` failure does not leak channel reference
   - Given: non-existent JAR path triggers IOException inside `channel.map`.
   - When: `JarMappedReader.read(jarPath)`.
   - Then: exception is `java.io.IOException` whose message contains `"map failed"`, whose stack trace contains no `java.nio.channels.FileChannel` reference; post-call `raf.getFD().valid()` is false.
   - Pins: B15.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JvmFileSourceTest'`.

estimated_loc: 40.

---

## Phase 05c: Accept Mapped ByteView in ConstantPool Utf8Lazy

Depends on: none.

`Utf8Lazy.view` field widens from `ByteView.Heap` to `ByteView`. Decode dispatches on the runtime view type and preserves cursor position. Closes C3 (Mapped ByteView rejection).

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala` `Utf8Lazy` (217-223).

  ```scala
  // BEFORE
  final case class Utf8Lazy(view: ByteView.Heap, offset: Int, length: Int):
      def decode: String =
          ...
  // AFTER
  final case class Utf8Lazy(view: ByteView, offset: Int, length: Int):
      def decode: String =
          view match
              case h: ByteView.Heap =>
                  Utf8.decode(h.allBytes, offset, length)
              case m: ByteView.Mapped =>
                  val buf = new Array[Byte](length)
                  val savedCursor = m.position
                  m.goto(offset.toLong)
                  var i = 0
                  while i < length do
                      buf(i) = m.readByte()
                      i += 1
                  m.goto(savedCursor)
                  Utf8.decode(buf, 0, length)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Utf8Lazy.view`: type widens from `ByteView.Heap` to `ByteView`.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala` (new).

1. `ConstantPoolTest.scala`: `Utf8Lazy.decode` reads from Mapped view
   - Given: a Mapped ByteView wrapping bytes `[0x66, 0x6f, 0x6f]`; `Utf8Lazy(mapped, 0, 3)`.
   - When: `lazyUtf8.decode`.
   - Then: returns `"foo"`.
   - Pins: C3.

2. `ConstantPoolTest.scala`: `Utf8Lazy.decode` preserves cursor
   - Given: Mapped ByteView with `cursor = 5L`; `Utf8Lazy(mapped, 0, 3)`.
   - When: `lazyUtf8.decode`; then `mapped.position`.
   - Then: position equals `5L`.
   - Pins: C3.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ConstantPoolTest'`. JS / Native equivalents.

estimated_loc: 45.

---

## Phase 06: Enforce OnceCell idempotence

Depends on: Phase 02a.

`OnceCell` gains a debug-mode duplicate-result detection flag. On CAS-loss with stored-value-differs and `kyo.tasty.OnceCell.debug=true`, throws `IllegalStateException`. Scaladoc documents the idempotence requirement. Produces INV-009.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala` (24-49).

  ```scala
  // BEFORE
  final class OnceCell[A](init: () => A):
      private val ref = new AtomicReference[AnyRef](OnceCell.Unset)
      def get()(using AllowUnsafe): A =
          val cached = ref.get()
          if cached ne OnceCell.Unset then cached.asInstanceOf[A]
          else
              val v = init().asInstanceOf[AnyRef]
              ref.compareAndSet(OnceCell.Unset, v)
              ref.get().asInstanceOf[A]
  // AFTER
  /** A lazy one-time computation cell.
    *
    * REQUIRES IDEMPOTENT INIT: if two threads race on `get()`, both run `init()` redundantly; the design assumes `init() == init()` modulo equality.
    * Debug mode: `-Dkyo.tasty.OnceCell.debug=true` flags non-idempotent init via `IllegalStateException`.
    */
  final class OnceCell[A](init: () => A):
      private val ref = new AtomicReference[AnyRef](OnceCell.Unset)
      def get()(using AllowUnsafe): A =
          val cached = ref.get()
          if cached ne OnceCell.Unset then
              cached.asInstanceOf[A]
          else
              val v   = init().asInstanceOf[AnyRef]
              val won = ref.compareAndSet(OnceCell.Unset, v)
              if !won && OnceCell.debugIdempotent then
                  val winner = ref.get()
                  if winner != v then
                      throw new IllegalStateException(s"OnceCell idempotence violated: init() returned $v but stored value is $winner")
              ref.get().asInstanceOf[A]
  end OnceCell

  object OnceCell:
      private val Unset: AnyRef = new AnyRef
      private[kyo] val debugIdempotent: Boolean =
          java.lang.System.getProperty("kyo.tasty.OnceCell.debug", "false").equalsIgnoreCase("true")
  end OnceCell
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala`.

1. `OnceCellTest.scala`: first call returns init result
   - Given: `OnceCell[Int](() => 42)`.
   - When: single-thread `cell.get()` with AllowUnsafe.
   - Then: returns 42.
   - Pins: INV-009.

2. `OnceCellTest.scala`: subsequent calls return cached value
   - Given: `OnceCell[Int](() => { counter.incrementAndGet(); 7 })`; counter AtomicInteger 0.
   - When: 10 sequential `cell.get()`.
   - Then: every return is 7; `counter.get()` equals 1.
   - Pins: INV-009 caching.

3. `OnceCellTest.scala`: debug-mode flags non-idempotent init
   - Given: `OnceCell.debugIdempotent=true`; cell with `() => counter.incrementAndGet()`; 8 fibers race.
   - When: all complete.
   - Then: at least one fiber throws `IllegalStateException` with `"idempotence violated"`.
   - Pins: INV-009, C2.

### Consumed invariants
- INV-001.

### Produced invariants
- INV-009: `OnceCell.init` lambdas are idempotent; concurrent first-callers compute the same value modulo equality.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.OnceCellTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 07a: Widen Interner growShard synchronization

Depends on: Phase 03b.

`Interner.internInShard` widens the synchronized window so the grow-then-recheck pair runs under the same monitor as `growShard`. Eliminates the prior unsynchronized re-read window.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala` (67-92).

  ```scala
  // BEFORE
  while ret eq null do
      val existing = table.get(slot)
      if existing eq null then
          if loadCounter.get() * 4 >= len * 3 then
              growShard(shardRef, loadCounter, len)
              return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
          end if
          ...
  // AFTER
  while ret eq null do
      val existing = table.get(slot)
      if existing eq null then
          if loadCounter.get() * 4 >= len * 3 then
              shardRef.synchronized {
                  if shardRef.get() eq table then
                      growShard(shardRef, loadCounter, len)
              }
              return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
          end if
          ...
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala`.

1. `InternerTest.scala`: concurrent grow-and-insert under contention
   - Given: `Interner(numShards = 2, initialShardCapacity = 4)`; 8 fibers each insert 1000 unique byte sequences with hashes hitting the same shard.
   - When: all complete.
   - Then: total entry count equals 8000; every byte sequence has a unique Entry (verified by reference-equality after re-intern).
   - Pins: B12.

2. `InternerTest.scala`: grow during contention preserves reference equality
   - Given: `Interner(numShards = 1, initialShardCapacity = 2)`; 4 fibers race to insert `[1, 2, 3]`.
   - When: all complete.
   - Then: every fiber's returned Entry is reference-equal.
   - Pins: B12.

### Consumed invariants
- INV-010.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InternerTest'`. JS / Native equivalents.

estimated_loc: 50.

---

## Phase 07b: Atomicize PerfCounters snapshot

Depends on: none.

`PerfCounters.snapshot()` returns a frozen view; `reset()` becomes snapshot-then-zero returning the pre-reset snapshot.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/PerfCounters.scala` (32-45).

  ```scala
  // BEFORE
  def reset(): Unit =
      jarOpenCount.set(0)
      entryReadCount.set(0)
      ...
  // AFTER
  final case class Snapshot(
      jarOpenCount: Int, entryReadCount: Int, bytesReadTotal: Long,
      jarConstructTimeNs: Long, jarReadTimeNs: Long, tastyHeaderTimeNs: Long,
      nameUnpicklerTimeNs: Long, sectionIndexTimeNs: Long, attributeUnpicklerTimeNs: Long,
      astPass1TimeNs: Long, commentsUnpicklerTimeNs: Long, positionsUnpicklerTimeNs: Long
  )

  def snapshot(): Snapshot =
      Snapshot(
          jarOpenCount.get(), entryReadCount.get(), bytesReadTotal.get(),
          jarConstructTimeNs.get(), jarReadTimeNs.get(), tastyHeaderTimeNs.get(),
          nameUnpicklerTimeNs.get(), sectionIndexTimeNs.get(), attributeUnpicklerTimeNs.get(),
          astPass1TimeNs.get(), commentsUnpicklerTimeNs.get(), positionsUnpicklerTimeNs.get()
      )

  def reset(): Snapshot =
      val s = snapshot()
      jarOpenCount.set(0)
      entryReadCount.set(0)
      bytesReadTotal.set(0L)
      jarConstructTimeNs.set(0L)
      jarReadTimeNs.set(0L)
      tastyHeaderTimeNs.set(0L)
      nameUnpicklerTimeNs.set(0L)
      sectionIndexTimeNs.set(0L)
      attributeUnpicklerTimeNs.set(0L)
      astPass1TimeNs.set(0L)
      commentsUnpicklerTimeNs.set(0L)
      positionsUnpicklerTimeNs.set(0L)
      s
  end reset
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `PerfCounters.reset`: return type was `Unit`, now `Snapshot`.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PerfCountersTest.scala` (new).

1. `PerfCountersTest.scala`: snapshot returns a coherent view
   - Given: writer thread incrementing `jarOpenCount` then `entryReadCount` in tight loop; reader collects 100 snapshots.
   - When: snapshots collected.
   - Then: every snapshot satisfies `snapshot.entryReadCount >= snapshot.jarOpenCount` minus 1 (relax-by-one for the inter-increment window).
   - Pins: B13.

2. `PerfCountersTest.scala`: reset returns pre-reset snapshot
   - Given: `jarOpenCount.set(42)`, `entryReadCount.set(7)`.
   - When: `PerfCounters.reset()`.
   - Then: returned Snapshot has `jarOpenCount=42` and `entryReadCount=7`; post-reset `jarOpenCount.get()` is 0.
   - Pins: B13.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PerfCountersTest'`. JS / Native equivalents.

estimated_loc: 80.

---

## Phase 08a: Bound TypeArena recursion depth

Depends on: none.

`TypeArena.internRec` threads a `depth: Int` parameter and caps at 1024. Pathological nesting throws `TypeArena.DepthExceededException` instead of `StackOverflowError`. Produces INV-019.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala` (81-96).

  ```scala
  // BEFORE
  def internRec(t: Tasty.Type): Tasty.Type =
      val key = TypeKey.of(t)
      canonical.map.get(key) match
          case Some(canon) => canon
          case None =>
              inProgress.get(key) match
                  case Some(placeholder) => placeholder
                  case None =>
                      inProgress(key) = t
                      val recurInterned = recurse(t)
                      ...
  // AFTER
  def internRec(t: Tasty.Type, depth: Int = 0): Tasty.Type =
      if depth >= TypeArena.MaxDepth then
          throw new TypeArena.DepthExceededException(s"TypeArena.internRec depth ${TypeArena.MaxDepth} exceeded; pathological nesting")
      val key = TypeKey.of(t)
      canonical.map.get(key) match
          case Some(canon) => canon
          case None =>
              inProgress.get(key) match
                  case Some(placeholder) => placeholder
                  case None =>
                      inProgress(key) = t
                      val recurInterned = recurse(t, depth + 1)
                      ...
  // Companion: object TypeArena { val MaxDepth: Int = 1024 ; class DepthExceededException(msg: String) extends RuntimeException(msg) }
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TypeArenaTest.scala`.

1. `TypeArenaTest.scala`: deeply nested `Applied` chain reports MalformedSection
   - Given: type formed by nesting `Applied(scala.Function1, Applied(...))` 2000 levels deep.
   - When: `TypeArena.canonical().merge(Map("k" -> t))` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Types", reason, _))` containing `"depth 1024 exceeded"`.
   - Pins: INV-019, B8.

2. `TypeArenaTest.scala`: nesting at MaxDepth-1 succeeds
   - Given: nesting exactly 1023 levels deep.
   - When: merge.
   - Then: `Result.Success(_)`; interned matches input structurally.
   - Pins: B8 boundary.

### Consumed invariants
None.

### Produced invariants
- INV-019: `TypeArena.internRec` enforces a recursion-depth cap and reports a structured error on overflow.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TypeArenaTest'`. JS / Native equivalents.

estimated_loc: 50.

---

## Phase 08b: Bound PositionsUnpickler line overflow

Depends on: none.

`PositionsUnpickler.lineStarts` cumulative arithmetic widens to Long; detects Int overflow before narrowing back. Reports `MalformedSection` instead of producing a silently negative position.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala` (82).

  ```scala
  // BEFORE
  lineStarts(k + 1) = lineStarts(k) + lineSizes(k) + 1
  // AFTER
  val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L
  if nextStart > Int.MaxValue then
      throw new ArrayIndexOutOfBoundsException(s"PositionsUnpickler: cumulative lineStart at line ${k + 1} exceeds Int.MaxValue ($nextStart); source file too large")
  lineStarts(k + 1) = nextStart.toInt
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala`.

1. `PositionsUnpicklerTest.scala`: overflow detected on very large source
   - Given: synthetic Positions section whose cumulative `lineSize` sum exceeds `Int.MaxValue`.
   - When: `PositionsUnpickler.read(view)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Positions", reason, _))` containing `"exceeds Int.MaxValue"`.
   - Pins: B9.

2. `PositionsUnpicklerTest.scala`: normal-sized file decodes positions correctly
   - Given: synthetic 200-line file; line k has size `100 + k`.
   - When: `PositionsUnpickler.read(view)`.
   - Then: `lineStarts(10)` equals the sum of line sizes 100-109 plus 10 newlines.
   - Pins: B9 baseline.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PositionsUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 09: Validate ConstantPool entry kinds

Depends on: none.

`ConstantPool` adds typed accessors `utf8At`, `classRefAt`, `methodRefAt`, `fieldRefAt`, `nameAndTypeAt`, `methodHandleAt`, `methodTypeAt`. Each validates the tag of the referenced entry. Callers (`ClassRef.nameIdx`, `FieldRef.classIdx`, `MethodRef.nameAndTypeIdx`) migrate to the typed accessors.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala` (66-102).

  ```scala
  // BEFORE
  def entry(idx: Int): Entry =
      if idx < 1 || idx >= entries.length then
          throw new ClassfileFormatException(s"constant pool index $idx out of range")
      entries(idx)
  // AFTER
  def entry(idx: Int): Entry =
      if idx < 1 || idx >= entries.length then
          throw new ClassfileFormatException(s"constant pool index $idx out of range")
      entries(idx)

  def utf8At(idx: Int): String =
      entry(idx) match
          case e: Entry.Utf8 => e.value
          case other         => throw new ClassfileFormatException(s"constant pool index $idx: expected Utf8, found ${other.getClass.getSimpleName}")

  def classRefAt(idx: Int): Entry.ClassRef =
      entry(idx) match
          case e: Entry.ClassRef => e
          case other             => throw new ClassfileFormatException(s"constant pool index $idx: expected ClassRef, found ${other.getClass.getSimpleName}")

  // Same shape for methodRefAt, fieldRefAt, nameAndTypeAt, methodHandleAt, methodTypeAt.
  // Callers update to use typed accessors throughout.
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala`.

1. `ConstantPoolTest.scala`: `utf8At` rejects ClassRef
   - Given: constant pool with `entries(5) = ClassRef(nameIdx = 6)`.
   - When: `pool.utf8At(5)`.
   - Then: `ClassfileFormatException` with `"expected Utf8, found ClassRef"`.
   - Pins: B5.

2. `ConstantPoolTest.scala`: `classRefAt.nameIdx` resolves to Utf8
   - Given: pool with `entries(5) = ClassRef(nameIdx = 6)` and `entries(6) = Utf8("scala/Int")`.
   - When: `pool.utf8At(pool.classRefAt(5).nameIdx)`.
   - Then: returns `"scala/Int"`.
   - Pins: B5.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ConstantPoolTest'`. JS / Native equivalents.

estimated_loc: 100.

---

## Phase 10: Log unknown TASTy type tags

Depends on: none.

`TypeUnpickler.decodeTag` fallback at lines 593, 598 emits a structured warn-level log via `kyo.Log.live.unsafe.warn` then routes to existing `Type.Named(makeUnresolvedSym(...))` fallback. Produces INV-004.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala` (593-600).

  ```scala
  // BEFORE
  case other =>
      Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))
  // AFTER
  case other =>
      Log.live.unsafe.warn(s"TypeUnpickler: unknown TASTy type tag $other at offset ${ctx.bytePosition} in ${ctx.classfilePath}")
      Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TypeUnpicklerTest.scala`.

1. `TypeUnpicklerTest.scala`: unknown tag logged
   - Given: synthetic TASTy type section whose first byte is tag value 250; Log backend captures messages.
   - When: `TypeUnpickler.decode(view, ctx)`.
   - Then: captured list contains exactly one warn-level message with `"unknown TASTy type tag 250"`; returned Type is `Type.Named(unresolved)` whose name equals `"unknown-type-tag-250"`.
   - Pins: INV-004, M7.

2. `TypeUnpicklerTest.scala`: known tag does not emit a warning
   - Given: synthetic type section encoding TYPEREF.
   - When: decode.
   - Then: captured Log list empty.
   - Pins: INV-004 negative.

### Consumed invariants
None.

### Produced invariants
- INV-004: Every TASTy type tag in `TypeUnpickler.decodeTag` has either an explicit decode branch or routes through the unknown-tag warning hook.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TypeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 11: Decode missing classfile attributes

Depends on: Phase 09.

`ClassfileUnpickler` adds match arms for the six missing attributes: BootstrapMethods, NestHost, NestMembers, PermittedSubclasses, MethodParameters, RuntimeTypeAnnotations. Each parser is ~30 LoC and extends the same attribute switch; all six share the same `mdBuilder` harness. `JavaMetadata` gains six new fields. `Symbol` gains a `permittedSubclasses` accessor for sealed-hierarchy enumeration. One conceptual change: "teach ClassfileUnpickler to parse the 6 missing attribute kinds". Produces INV-008.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `JavaMetadata` adds six fields; `Symbol` gains `_permittedSubclasses` slot and `permittedSubclasses` accessor.

  ```scala
  // BEFORE
  final case class JavaMetadata(
      throwsTypes: Chunk[Type], annotations: Chunk[JavaAnnotation],
      enclosingMethod: Maybe[(Symbol, Name)], accessFlags: Int, recordComponents: Chunk[(Name, Type)]
  )
  // AFTER
  final case class JavaMetadata(
      throwsTypes: Chunk[Type], annotations: Chunk[JavaAnnotation],
      enclosingMethod: Maybe[(Symbol, Name)], accessFlags: Int, recordComponents: Chunk[(Name, Type)],
      bootstrapMethods: Chunk[Chunk[Int]] = Chunk.empty,
      nestHost: Maybe[Symbol] = Maybe.Absent,
      nestMembers: Chunk[Symbol] = Chunk.empty,
      paramNames: Chunk[(Name, Chunk[Name])] = Chunk.empty,
      runtimeTypeAnnotations: Chunk[JavaAnnotation] = Chunk.empty
  )

  // Symbol additions:
  private[kyo] val _permittedSubclasses: SingleAssign[Maybe[Chunk[Symbol]]] = new SingleAssign

  /** Sealed-hierarchy permitted subclasses, if this symbol is a sealed class with a PermittedSubclasses attribute. */
  def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Symbol]] =
      if _permittedSubclasses.isSet then _permittedSubclasses.get() else Maybe.Absent
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`: extend attribute dispatch with six new arms.

  ```scala
  // BEFORE (around line 343)
  case "ScalaSig" => ...
  case other      => /* skip */
  // AFTER
  case "ScalaSig"               => ...
  case "BootstrapMethods"       => decodeBootstrapMethods(view, pool, mdBuilder)
  case "NestHost"               => decodeNestHost(view, pool, mdBuilder)
  case "NestMembers"            => decodeNestMembers(view, pool, mdBuilder)
  case "PermittedSubclasses"    => decodePermittedSubclasses(view, pool, currentSymbol)
  case "MethodParameters"       => decodeMethodParameters(view, pool, mdBuilder, currentMethodName)
  case "RuntimeVisibleTypeAnnotations" | "RuntimeInvisibleTypeAnnotations" =>
                                   decodeRuntimeTypeAnnotations(view, pool, mdBuilder)
  case other                    => /* skip */

  private def decodeBootstrapMethods(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadata.Builder): Unit =
      val n = view.readUnsignedShort()
      val bsm = Chunk.newBuilder[Chunk[Int]]
      var i = 0
      while i < n do
          val methodRef = view.readUnsignedShort()
          val argCount  = view.readUnsignedShort()
          val args = Chunk.newBuilder[Int]
          var j = 0
          while j < argCount do
              args += view.readUnsignedShort()
              j += 1
          bsm += args.result()
          i += 1
      mdBuilder.bootstrapMethods = bsm.result()

  private def decodeNestHost(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadata.Builder): Unit =
      val hostClassIdx = view.readUnsignedShort()
      val hostClassName = pool.utf8At(pool.classRefAt(hostClassIdx).nameIdx)
      mdBuilder.nestHost = Maybe.Present(makeOrFindSymbol(hostClassName))

  private def decodeNestMembers(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadata.Builder): Unit =
      val n = view.readUnsignedShort()
      val members = Chunk.newBuilder[Symbol]
      var i = 0
      while i < n do
          val idx = view.readUnsignedShort()
          val cls = pool.utf8At(pool.classRefAt(idx).nameIdx)
          members += makeOrFindSymbol(cls)
          i += 1
      mdBuilder.nestMembers = members.result()

  private def decodePermittedSubclasses(view: ByteView, pool: ConstantPool, currentSymbol: Symbol): Unit =
      val n = view.readUnsignedShort()
      val subs = Chunk.newBuilder[Symbol]
      var i = 0
      while i < n do
          val idx = view.readUnsignedShort()
          val cls = pool.utf8At(pool.classRefAt(idx).nameIdx)
          subs += makeOrFindSymbol(cls)
          i += 1
      currentSymbol._permittedSubclasses.set(Maybe.Present(subs.result()))

  private def decodeMethodParameters(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadata.Builder, currentMethodName: Name): Unit =
      val n = view.readUnsignedByte()
      val params = Chunk.newBuilder[Name]
      var i = 0
      while i < n do
          val nameIdx = view.readUnsignedShort()
          view.readUnsignedShort() // access flags ignored
          if nameIdx != 0 then params += Name(pool.utf8At(nameIdx)) else params += Name("")
          i += 1
      mdBuilder.paramNames :+= (currentMethodName -> params.result())

  private def decodeRuntimeTypeAnnotations(view: ByteView, pool: ConstantPool, mdBuilder: JavaMetadata.Builder): Unit =
      val n = view.readUnsignedShort()
      val anns = Chunk.newBuilder[JavaAnnotation]
      var i = 0
      while i < n do
          view.skipTypeAnnotationTargetInfo()
          val ann = JavaAnnotationUnpickler.read(view, pool)
          anns += ann
          i += 1
      mdBuilder.runtimeTypeAnnotations ++= anns.result()
  ```

### Files to delete
None.

### Public API additions
- `JavaMetadata.bootstrapMethods`, `nestHost`, `nestMembers`, `paramNames`, `runtimeTypeAnnotations` fields.
- `Symbol.permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Symbol]]`.

### Public API modifications
- `JavaMetadata` case class gains 5 fields with safe defaults.

### Tests
Total: 6. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ClassfileUnpicklerTest.scala` and `TastySymbolTest.scala`.

1. `ClassfileUnpicklerTest.scala`: BootstrapMethods attribute parsed
   - Given: classfile with one BootstrapMethods entry: methodRef=10, args=[1, 2, 3].
   - When: decode.
   - Then: `metadata.bootstrapMethods` equals `Chunk(Chunk(1, 2, 3))`.
   - Pins: INV-008.

2. `ClassfileUnpicklerTest.scala`: NestHost attribute parsed
   - Given: classfile with `NestHost = Outer`.
   - When: decode.
   - Then: `metadata.nestHost == Maybe.Present(outerSym)` where `outerSym.fullName.asString == "Outer"`.
   - Pins: INV-008.

3. `ClassfileUnpicklerTest.scala`: NestMembers attribute parsed
   - Given: classfile with `NestMembers = [Outer$Inner1, Outer$Inner2]`.
   - When: decode.
   - Then: `metadata.nestMembers.map(_.fullName.asString)` equals `Chunk("Outer.Inner1", "Outer.Inner2")`.
   - Pins: INV-008.

4. `ClassfileUnpicklerTest.scala`: PermittedSubclasses attribute parsed
   - Given: classfile for `sealed class Foo permits Bar, Baz`.
   - When: decode; read `currentSymbol._permittedSubclasses.get()`.
   - Then: returned Maybe.Present chunk size is 2 with names `"Bar"` and `"Baz"`.
   - Pins: INV-008.

5. `ClassfileUnpicklerTest.scala`: MethodParameters and RuntimeTypeAnnotations parsed
   - Given: classfile method `foo(int a, String b)` with MethodParameters listing `a, b`; field `@TypeUse String foo` with RuntimeVisibleTypeAnnotations.
   - When: decode.
   - Then: `metadata.paramNames` contains `(Name("foo"), Chunk(Name("a"), Name("b")))`; `metadata.runtimeTypeAnnotations.size == 1` and the contained annotation's class name ends with `"TypeUse"`.
   - Pins: INV-008.

6. `TastySymbolTest.scala`: `Symbol.permittedSubclasses` returns Present for sealed class
   - Given: `cp.findClass("Foo").get` produces a sealed-class symbol whose classfile carries PermittedSubclasses.
   - When: `sym.permittedSubclasses` with AllowUnsafe.
   - Then: returned Maybe.Present chunk equals expected subclass set.
   - Pins: INV-008.

### Consumed invariants
- INV-001.

### Produced invariants
- INV-008: Java classfile attributes `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` parsed and exposed via `Symbol` or `JavaMetadata`.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ClassfileUnpicklerTest kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 250.

---

## Phase 12: Decode Scala 2 EXT references

Depends on: none.

`Scala2PickleReader.decodeEntry` adds cases for entry types 7 (EXTref) and 8 (EXTMODCLASSref). Both decode (nameRef, ownerRef) into an `UnresolvedRef`-backed Symbol; EXTMODCLASSref appends `$` to the canonicalized name for module-class semantics.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala` (260-275).

  ```scala
  // BEFORE
  case 6 => decodeValSym(...)
  case other =>
      throw new MalformedPickleException(s"unsupported entry type $other")
  // AFTER
  case 6 => decodeValSym(...)
  case 7 => decodeExtRef(buf, idx)
  case 8 => decodeExtModClassRef(buf, idx)
  case other =>
      throw new MalformedPickleException(s"unsupported entry type $other")

  private def decodeExtRef(buf: PickleBuffer, idx: Int): Tasty.Symbol =
      val nameRef = buf.readNat()
      val ownerRefOpt = if buf.remaining(idx) > 0 then Some(buf.readNat()) else None
      val name = resolveName(nameRef)
      val ownerFqn = ownerRefOpt.flatMap(resolveOwnerFqn).getOrElse("")
      val fqn = if ownerFqn.isEmpty then name.asString else ownerFqn + "." + name.asString
      UnresolvedRef.make(fqn, classpathRef)

  private def decodeExtModClassRef(buf: PickleBuffer, idx: Int): Tasty.Symbol =
      val nameRef = buf.readNat()
      val ownerRefOpt = if buf.remaining(idx) > 0 then Some(buf.readNat()) else None
      val name = resolveName(nameRef)
      val ownerFqn = ownerRefOpt.flatMap(resolveOwnerFqn).getOrElse("")
      val moduleClassName = if name.asString.endsWith("$") then name.asString else name.asString + "$"
      val fqn = if ownerFqn.isEmpty then moduleClassName else ownerFqn + "." + moduleClassName
      UnresolvedRef.make(fqn, classpathRef)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/Scala2PickleTest.scala`.

1. `Scala2PickleTest.scala`: EXTref decodes to UnresolvedRef
   - Given: Scala 2 pickle bytes whose entry 5 is EXTref with nameRef 3 (`"Foo"`) and ownerRef 1 (`"com.example"`).
   - When: `Scala2PickleReader.decode(buf, cp)`.
   - Then: returned symbol table has entry with `fullName.asString == "com.example.Foo"` and `kind == SymbolKind.Unresolved`.
   - Pins: M9 EXTref.

2. `Scala2PickleTest.scala`: EXTMODCLASSref appends `$`
   - Given: Scala 2 pickle with entry 6 EXTMODCLASSref nameRef `"Foo"` ownerRef `"com.example"`.
   - When: decode.
   - Then: resulting symbol `fullName.asString == "com.example.Foo$"`.
   - Pins: M9 EXTMODCLASSref.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.Scala2PickleTest'`. JS / Native equivalents.

estimated_loc: 80.

---

## Phase 13: Test thin Symbol API surface

Depends on: Phase 02a.

Adds test coverage for thin public-API gaps: `Symbol.binaryName` (Scala nested-class binary names beyond `java.util.Map$Entry`), `Symbol.isPackageObject`, `Type.show`, and the public `Annotation` synthetic factory. One conceptual change: fill the T1 public-API coverage gaps.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 6. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`, `TastyTypeTest.scala` (new), and `TastyAnnotationTest.scala` (new).

1. `TastySymbolTest.scala`: `binaryName` for Scala nested class
   - Given: `cp.findClass("com.example.Outer.Inner").get` produces `sym` with `kind == SymbolKind.Class`.
   - When: `sym.binaryName`.
   - Then: returns `"com/example/Outer$Inner"`.
   - Pins: T1.

2. `TastySymbolTest.scala`: `binaryName` for top-level Scala class
   - Given: `cp.findClass("com.example.Foo").get`.
   - When: `sym.binaryName`.
   - Then: returns `"com/example/Foo"`.
   - Pins: T1.

3. `TastySymbolTest.scala`: `isPackageObject` true for `package` Module
   - Given: TASTy file with `package object com.example` producing a Module-kind Symbol named `"package"`.
   - When: `sym.isPackageObject` with AllowUnsafe.
   - Then: returns `true`.
   - Pins: T1.

4. `TastySymbolTest.scala`: `isPackageObject` false for class
   - Given: a class-kind Symbol named `"Foo"`.
   - When: `sym.isPackageObject` with AllowUnsafe.
   - Then: returns `false`.
   - Pins: T1.

5. `TastyTypeTest.scala`: `Type.show` for `Applied(scala.List, Int)`
   - Given: `t = Tasty.Type.Applied(Tasty.Type.Named(listSym), Chunk(Tasty.Type.Named(intSym)))`.
   - When: `t.show`.
   - Then: returns `"scala.List[scala.Int]"`.
   - Pins: T1.

6. `TastyAnnotationTest.scala`: synthetic `Annotation.apply` factory
   - Given: `a = Tasty.Annotation(Tasty.Type.Named(deprecatedSym), Chunk.empty)`.
   - When: `a.annotationType`, `a.argsPickle`, `Tasty.Annotation.unapply(a)`.
   - Then: `a.annotationType.show == "scala.deprecated"`; `a.argsPickle.isEmpty == true`; `unapply(a) == Some((a.annotationType, Chunk.empty))`.
   - Pins: T1.

### Consumed invariants
- INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastySymbolTest kyo.TastyTypeTest kyo.TastyAnnotationTest'`. JS / Native equivalents.

estimated_loc: 130.

---

## Phase 14a: Enrich malformed-section errors with byteOffset

Depends on: Phase 03a.

`TastyError.MalformedSection`, `TastyError.ClassfileFormatError`, and `TastyError.SnapshotFormatError` each gain a `byteOffset: Long` field. Every callsite updates to pass the cursor. One conceptual change: every malformed-section error carries the failure's byte offset. Produces INV-006.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala` (lines 12, 14, 18).

  ```scala
  // BEFORE
  case MalformedSection(name: String, reason: String)
  case ClassfileFormatError(path: String, reason: String)
  case SnapshotFormatError(path: String, reason: String)
  // AFTER
  case MalformedSection(name: String, reason: String, byteOffset: Long)
  case ClassfileFormatError(path: String, reason: String, byteOffset: Long)
  case SnapshotFormatError(path: String, reason: String, byteOffset: Long)
  ```

- Every callsite of these three error constructors updates to pass `view.position` (or the exception's carried `byteOffset`). Sites identified in Q-004: `JarCentralDirectory.scala` (10+ sites operating on a ByteView cursor), `ClassfileUnpickler.scala` cursor loop, `ConstantPool.scala` (`idx` + view cursor), `ModuleInfoReader.scala` classfile reader cursor, `Tasty.scala:190,192,730,735` decode catch blocks with cursor available.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `TastyError.MalformedSection`: adds `byteOffset: Long`.
- `TastyError.ClassfileFormatError`: adds `byteOffset: Long`.
- `TastyError.SnapshotFormatError`: adds `byteOffset: Long`.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyErrorTest.scala` (new).

1. `TastyErrorTest.scala`: MalformedSection carries byte offset
   - Given: a Varint malformed input that exercises Phase 03a's check.
   - When: decode runs via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection(_, _, off))` where `off` is non-zero (matches the failing read's position).
   - Pins: INV-006, L5.

2. `TastyErrorTest.scala`: ClassfileFormatError captures decode position
   - Given: corrupted classfile whose constant pool entry at byte 89 has invalid tag.
   - When: load via `Abort.run`.
   - Then: `Result.Failure(TastyError.ClassfileFormatError(path, _, 89L))`.
   - Pins: INV-006.

3. `TastyErrorTest.scala`: SnapshotFormatError captures byte position
   - Given: corrupted snapshot whose section header at offset 256 is malformed.
   - When: load via `Abort.run`.
   - Then: `Result.Failure(TastyError.SnapshotFormatError(_, _, 256L))`.
   - Pins: INV-006.

### Consumed invariants
- INV-010.

### Produced invariants
- INV-006: Every `TastyError.MalformedSection` event carries the byte offset of the failure. (Extended uniformly to ClassfileFormatError and SnapshotFormatError per Q-004.)

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyErrorTest'`. JS / Native equivalents.

estimated_loc: 120.

---

## Phase 14b: Exercise untested error cases

Depends on: none.

Adds T3 coverage for `TastyError.SymbolNotFound` and `TastyError.ParameterizedTypeNotAllowed`. Tests-only phase.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyErrorTest.scala`.

1. `TastyErrorTest.scala`: `SymbolNotFound` carries the FQN
   - Given: a Classpath with no symbol named `"missing.X"`.
   - When: `cp.lookupClass("missing.X")` via `Abort.run`; separately construct `TastyError.SymbolNotFound("missing.X")`.
   - Then: lookup is `Result.Success(Maybe.Absent)`; constructed value has `.fqn == "missing.X"`.
   - Pins: T3.

2. `TastyErrorTest.scala`: `ParameterizedTypeNotAllowed` carries the tag
   - Given: synthetic TASTy type section with APPLIEDtype at a position where parameterized application is illegal.
   - When: decode via `Abort.run`.
   - Then: `Result.Failure(TastyError.ParameterizedTypeNotAllowed(tag))` where `tag == "APPLIEDtype"`.
   - Pins: T3.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyErrorTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 15: Return SubtypeVerdict from isSubtypeOf

Depends on: Phase 02a.

New `enum SubtypeVerdict { Sub, NotSub, Unknown }` in `kyo.Tasty` per Q-001. `Type.isSubtypeOf` and `Subtyping.isSubtype` return type changes from `Boolean` to `SubtypeVerdict`. Produces INV-016.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: add `enum SubtypeVerdict`; change `Type.isSubtypeOf` return type.

  ```scala
  // ADDED (near SymbolKind, around line 134)
  enum SubtypeVerdict derives CanEqual:
      case Sub, NotSub, Unknown
  end SubtypeVerdict

  // BEFORE
  extension (t: Type)
      def isSubtypeOf(other: Type)(using cp: Classpath, AllowUnsafe): Boolean =
          kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  // AFTER
  extension (t: Type)
      def isSubtypeOf(other: Type)(using cp: Classpath, AllowUnsafe): SubtypeVerdict =
          kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala`: change return type; budget-exhausted and partial-classpath branches return `SubtypeVerdict.Unknown`; three-way lattice combinators.

  ```scala
  // BEFORE
  def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int)(using AllowUnsafe): Boolean =
      if budget <= 0 then false
      else ...
  // AFTER
  def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int)(using AllowUnsafe): Tasty.SubtypeVerdict =
      import Tasty.SubtypeVerdict.*
      if budget <= 0 then Unknown
      else
          sup match
              case Tasty.Type.Named(supSym) if supSym.fullName.asString == AnyFqn => Sub
              case Tasty.Type.OrType(supLeft, supRight) =>
                  val left = isSubtype(sub, supLeft, cp, budget)
                  if left == Sub then Sub
                  else
                      val right = isSubtype(sub, supRight, cp, budget)
                      if right == Sub then Sub
                      else if left == Unknown || right == Unknown then Unknown
                      else NotSub
              ...
              case _ =>
                  if !cp.hasFullParentChain(sub) then Unknown
                  else NotSub
  ```

### Files to delete
None.

### Public API additions
- `enum Tasty.SubtypeVerdict { Sub, NotSub, Unknown }`.

### Public API modifications
- `extension (t: Type).isSubtypeOf(other)`: return type changes from `Boolean` to `SubtypeVerdict`.

### Tests
Total: 4. Tests live in `kyo-tasty/shared/src/test/scala/kyo/SubtypeTest.scala`.

1. `SubtypeTest.scala`: positive subtype returns Sub
   - Given: `t = Int`, `other = Any`; classpath has both.
   - When: `t.isSubtypeOf(other)`.
   - Then: `SubtypeVerdict.Sub`.
   - Pins: INV-016.

2. `SubtypeTest.scala`: negative returns NotSub
   - Given: `t = String`, `other = Int`.
   - When: same.
   - Then: `SubtypeVerdict.NotSub`.
   - Pins: INV-016.

3. `SubtypeTest.scala`: budget exhaustion returns Unknown
   - Given: deeply-nested Rec type triggering budget exhaustion (66 unfoldings).
   - When: `t.isSubtypeOf(other)`.
   - Then: `SubtypeVerdict.Unknown`.
   - Pins: INV-016.

4. `SubtypeTest.scala`: partial classpath returns Unknown
   - Given: classpath missing parent chain for `Foo`; `t = Foo`, `other = Bar`.
   - When: `t.isSubtypeOf(other)`.
   - Then: `Unknown`.
   - Pins: INV-016, M6.

### Consumed invariants
- INV-001.

### Produced invariants
- INV-016: `Type.isSubtypeOf` returns `SubtypeVerdict { Sub, NotSub, Unknown }`; throws no exceptions; callers pattern-match all three.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SubtypeTest'`. JS / Native equivalents.

estimated_loc: 150.

---

## Phase 16: Resolve ClassConst type reference

Depends on: Phase 02a.

`Constant.fromTastyTag` for CLASSconst decodes the embedded type sub-AST and builds the real `Type`. Removes the `classConstSentinel` placeholder and the "Phase 4" legacy-comment marker at `Constant.scala:81`. Produces INV-013.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala` (20-30, 80-86).

  ```scala
  // BEFORE
  private val classConstSentinel: Tasty.Symbol = Tasty.Symbol.make(...)
  ...
  case TastyFormat.CLASSconst =>
      skipTree(view)
      Tasty.Constant.ClassConst(Tasty.Type.Named(classConstSentinel))
  // AFTER
  case TastyFormat.CLASSconst =>
      val tpe = kyo.internal.tasty.reader.TypeUnpickler.decodeType(view, decodeCtx)
      Tasty.Constant.ClassConst(tpe)
  ```

  Signature change: `Constant.fromTastyTag(tag, view, names)` becomes `Constant.fromTastyTag(tag, view, decodeCtx)`.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Constant.ClassConst.tpe`: shape unchanged; value changes from sentinel to real Type.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/UnifiedModelTest.scala`.

1. `UnifiedModelTest.scala`: ClassConst carries real Type
   - Given: TASTy file with `class Foo { val x: Class[_] = classOf[String] }`; load via `Classpath.open`.
   - When: traverse body to find `Literal(ClassConst(tpe))`.
   - Then: `tpe == Tasty.Type.Named(stringSym)` where `stringSym.fullName.asString == "java.lang.String"`.
   - Pins: INV-013, M3.

2. `UnifiedModelTest.scala`: ClassConst with unresolved class produces UnresolvedRef
   - Given: TASTy referencing `classOf[com.missing.X]` not in classpath.
   - When: extract `ClassConst(tpe)`.
   - Then: `tpe == Type.Named(unresolved)` where `unresolved.kind == SymbolKind.Unresolved` and `unresolved.fullName.asString == "com.missing.X"`.
   - Pins: INV-013.

3. `UnifiedModelTest.scala`: no classConstSentinel exported
   - Given: source `Constant.scala` read as text.
   - When: substring scan.
   - Then: `"classConstSentinel"` appears 0 times.
   - Pins: M10 (Constant.scala:81 legacy-comment removal).

### Consumed invariants
- INV-001.

### Produced invariants
- INV-013: `Constant.ClassConst` constants carry the real referenced `Type` rather than the `classConstSentinel` placeholder.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.UnifiedModelTest'`. JS / Native equivalents.

estimated_loc: 70.

---

## Phase 17: Decode Annotation args lazily

Depends on: Phase 02a, Phase 16.

`Annotation.args` removes the `NotImplemented` branch. Null-context and empty-pickle branches return `Tree.Unknown(-1, 0)`. The only remaining failure paths are `MalformedSection` and `CorruptedFile`. Produces INV-014.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Annotation.args` (178-195).

  ```scala
  // BEFORE
  def args(using Frame): Tree < (Sync & Abort[TastyError]) =
      _decodeCtx match
          case null =>
              Abort.fail(TastyError.NotImplemented("annotation args decode requires file decode context"))
          case ctx: Annotation.DecodeContext =>
              if argsPickle.isEmpty then
                  Abort.fail(TastyError.NotImplemented("annotation argsPickle is empty"))
              else
                  Sync.defer:
                      ...
  // AFTER
  def args(using Frame): Tree < (Sync & Abort[TastyError]) =
      _decodeCtx match
          case null =>
              Sync.defer(Tree.Unknown(-1, 0))
          case ctx: Annotation.DecodeContext =>
              if argsPickle.isEmpty then
                  Sync.defer(Tree.Unknown(-1, 0))
              else
                  Sync.defer:
                      try Right(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(argsPickle, ctx))
                      catch
                          case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                              Left(TastyError.MalformedSection("ASTs", s"annotation arg decode failed: ${ex.getMessage}", ex.byteOffset))
                          case ex: ArrayIndexOutOfBoundsException =>
                              Left(TastyError.MalformedSection("ASTs", s"annotation arg truncated: ${ex.getMessage}", -1L))
                      .map:
                          case Right(t) => t
                          case Left(e)  => Abort.fail(e)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `Annotation.args`: no longer returns `NotImplemented`; replaces null-context and empty-pickle branches with `Tree.Unknown(-1, 0)`.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala` and `TastyAnnotationTest.scala`.

1. `TreeUnpicklerTest.scala`: orchestrator-discovered annotation args decode
   - Given: TASTy file with `@deprecated("msg", "since 1.0") def foo()`.
   - When: `annotation.args` evaluated.
   - Then: structure matches `Apply(Select(New(Named(deprecated)), <init>), List(Literal(StringConst("msg")), Literal(StringConst("since 1.0"))))`.
   - Pins: INV-014, M2.

2. `TreeUnpicklerTest.scala`: annotation with empty argsPickle returns Tree.Unknown
   - Given: annotation with non-null `_decodeCtx` but `argsPickle.isEmpty`.
   - When: `annotation.args`.
   - Then: `Tree.Unknown(-1, 0)`.
   - Pins: INV-014.

3. `TastyAnnotationTest.scala`: synthetic factory args returns Tree.Unknown
   - Given: `a = Tasty.Annotation(Type.Named(sym), Chunk.empty)` (no decode context).
   - When: `a.args`.
   - Then: returns `Tree.Unknown(-1, 0)`; NOT `Abort.fail(NotImplemented)`.
   - Pins: INV-014.

### Consumed invariants
- INV-001, INV-006.

### Produced invariants
- INV-014: `Annotation.args` decode succeeds for any annotation discovered through the classpath orchestrator, including accesses after the initial decode boundary.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest kyo.TastyAnnotationTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 18a: Decode Tree category 1 modifiers

Depends on: Phase 17.

`TreeUnpickler` adds explicit decode arms for TASTy AST category 1 modifier tags (PRIVATE through INTO; tag < firstASTtag). New `Tree.Modifier(flag: Flag)` case. Per Q-003, category 1 has its own decode strategy (single-byte tag, no payload, no length).

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add `case Modifier(flag: Flag)`.

  ```scala
  // BEFORE (around line 394-492)
  enum Tree:
      case Ident(...) ; case Select(...) ; ... ; case Unknown(tag: Int, length: Int)
  // AFTER
  enum Tree:
      case Ident(...) ; case Select(...) ; ... ; case Modifier(flag: Flag) ; case Unknown(tag: Int, length: Int)
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-1 dispatch.

  ```scala
  // BEFORE
  val tag = view.readByte() & 0xff
  tag match
      case TastyFormat.IDENT => decodeIdent(...) ; ... ; case _ => Tree.Unknown(tag, 0)
  // AFTER
  val tag = view.readByte() & 0xff
  if tag < TastyFormat.firstASTtag then
      decodeCategoryOneModifier(tag)
  else
      tag match
          case TastyFormat.IDENT => decodeIdent(...) ; ... ; case _ => Tree.Unknown(tag, 0)

  private def decodeCategoryOneModifier(tag: Int): Tasty.Tree =
      val flag = tag match
          case TastyFormat.PRIVATE => Tasty.Flag.Private
          case TastyFormat.PROTECTED => Tasty.Flag.Protected
          case TastyFormat.ABSTRACT => Tasty.Flag.Abstract
          case TastyFormat.FINAL => Tasty.Flag.Final
          case TastyFormat.SEALED => Tasty.Flag.Sealed
          case TastyFormat.CASE => Tasty.Flag.Case
          case TastyFormat.IMPLICIT => Tasty.Flag.Implicit
          case TastyFormat.GIVEN => Tasty.Flag.Given
          case TastyFormat.LAZY => Tasty.Flag.Lazy
          case TastyFormat.OVERRIDE => Tasty.Flag.Override
          case TastyFormat.INLINE => Tasty.Flag.Inline
          case TastyFormat.MACRO => Tasty.Flag.Macro
          case TastyFormat.OPAQUE => Tasty.Flag.Opaque
          case TastyFormat.OPEN => Tasty.Flag.Open
          case TastyFormat.TRANSPARENT => Tasty.Flag.Transparent
          case TastyFormat.INFIX => Tasty.Flag.Infix
          case TastyFormat.ERASED => Tasty.Flag.Erased
          case TastyFormat.TRACKED => Tasty.Flag.Tracked
          case TastyFormat.SYNTHETIC => Tasty.Flag.Synthetic
          case TastyFormat.ARTIFACT => Tasty.Flag.Artifact
          case TastyFormat.STABLE => Tasty.Flag.Stable
          case TastyFormat.STATIC => Tasty.Flag.Static
          case TastyFormat.MUTABLE => Tasty.Flag.Mutable
          case TastyFormat.PARAMACCESSOR => Tasty.Flag.ParamAccessor
          case TastyFormat.PARAMsetter => Tasty.Flag.PARAMsetter
          case TastyFormat.PARAMalias => Tasty.Flag.PARAMalias
          case TastyFormat.EXPORTED => Tasty.Flag.Exported
          case TastyFormat.LOCAL => Tasty.Flag.Local
          case TastyFormat.HASDEFAULT => Tasty.Flag.HasDefault
          case TastyFormat.EXTENSION => Tasty.Flag.Extension
          case TastyFormat.INLINEPROXY => Tasty.Flag.InlineProxy
          case TastyFormat.COVARIANT => Tasty.Flag.CoVariant
          case TastyFormat.CONTRAVARIANT => Tasty.Flag.ContraVariant
          case TastyFormat.INVISIBLE => Tasty.Flag.Invisible
          case TastyFormat.INTO => Tasty.Flag.Into
          case other => throw new TreeUnpickler.DecodeException(s"unknown category-1 modifier tag $other", view.position.toLong)
      Tasty.Tree.Modifier(flag)
  ```

### Files to delete
None.

### Public API additions
- `Tree.Modifier(flag: Flag)`.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: PRIVATE tag decodes to Modifier(Private)
   - Given: bytes `[PRIVATE]`.
   - When: decode.
   - Then: returns `Tree.Modifier(Flag.Private)`.
   - Pins: M1 category 1.

2. `TreeUnpicklerTest.scala`: unknown category-1 tag throws DecodeException
   - Given: bytes `[5]` (below `firstASTtag` but not a recognized modifier).
   - When: decode via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("ASTs", reason, _))` containing `"unknown category-1 modifier tag 5"`.
   - Pins: M1 category 1 negative.

### Consumed invariants
- INV-014, INV-006.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 18b: Decode Tree category 2 tag+Nat

Depends on: Phase 18a.

`TreeUnpickler` adds category 2 decode arms (60 <= tag <= 89): SHAREDtype, SHAREDterm, BYTEconst, SHORTconst, CHARconst, INTconst, LONGconst, FLOATconst, DOUBLEconst, STRINGconst. New Tree cases: `Shared(addr)` and `Literal(constant)`.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add `case Shared(addr: Int)`, `case Literal(constant: Constant)`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-2 dispatch.

  ```scala
  else if tag >= 60 && tag <= 89 then
      decodeCategoryTwo(tag, view, decodeCtx)

  private def decodeCategoryTwo(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
      tag match
          case TastyFormat.SHAREDtype | TastyFormat.SHAREDterm =>
              Tasty.Tree.Shared(view.readNat())
          case TastyFormat.BYTEconst | TastyFormat.SHORTconst | TastyFormat.CHARconst
             | TastyFormat.INTconst | TastyFormat.LONGconst | TastyFormat.FLOATconst
             | TastyFormat.DOUBLEconst | TastyFormat.STRINGconst =>
              val constant = Constant.fromTastyTag(tag, view, decodeCtx)
              Tasty.Tree.Literal(constant)
          case other =>
              throw new TreeUnpickler.DecodeException(s"unknown category-2 tag $other", view.position.toLong)
  ```

### Files to delete
None.

### Public API additions
- `Tree.Shared(addr: Int)`, `Tree.Literal(constant: Constant)`.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: SHAREDtype decodes to Shared(addr)
   - Given: bytes `[SHAREDtype, 42]` (varint Nat 42).
   - When: decode.
   - Then: returns `Tree.Shared(42)`.
   - Pins: M1 category 2.

2. `TreeUnpicklerTest.scala`: INTconst decodes to Literal(IntConst)
   - Given: bytes `[INTconst, <varint 7>]`.
   - When: decode.
   - Then: returns `Tree.Literal(Constant.IntConst(7))`.
   - Pins: M1 category 2.

### Consumed invariants
None additional.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 130.

---

## Phase 18c: Decode Tree category 3 tag+AST

Depends on: Phase 18b.

`TreeUnpickler` adds category 3 decode arms (90 <= tag <= 109): RECtype, SUPERtype, REFINEDtype, APPLIEDtype, TYPEBOUNDS, ANNOTATEDtype, ANDtype, ORtype, BYNAMEtype, MATCHtype, FLEXIBLEtype.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add 11 cases (RecType, SuperType, RefinedType, AppliedType, TypeBounds, AnnotatedType, AndType, OrType, ByNameType, MatchType, FlexibleType).

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-3 dispatch.

  ```scala
  else if tag >= 90 && tag <= 109 then
      decodeCategoryThree(tag, view, decodeCtx)

  private def decodeCategoryThree(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
      tag match
          case TastyFormat.RECtype => Tasty.Tree.RecType(decodeTree(view, decodeCtx))
          case TastyFormat.SUPERtype => Tasty.Tree.SuperType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
          case TastyFormat.REFINEDtype =>
              val parent = decodeTree(view, decodeCtx)
              val nameRef = view.readNat()
              val info = decodeTree(view, decodeCtx)
              Tasty.Tree.RefinedType(parent, decodeCtx.names(nameRef), info)
          case TastyFormat.APPLIEDtype =>
              val tycon = decodeTree(view, decodeCtx)
              val args = decodeTreeListUntil(view, decodeCtx, view.position)
              Tasty.Tree.AppliedType(tycon, args)
          case TastyFormat.TYPEBOUNDS =>
              Tasty.Tree.TypeBounds(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
          case TastyFormat.ANNOTATEDtype =>
              Tasty.Tree.AnnotatedType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
          case TastyFormat.ANDtype =>
              Tasty.Tree.AndType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
          case TastyFormat.ORtype =>
              Tasty.Tree.OrType(decodeTree(view, decodeCtx), decodeTree(view, decodeCtx))
          case TastyFormat.BYNAMEtype =>
              Tasty.Tree.ByNameType(decodeTree(view, decodeCtx))
          case TastyFormat.MATCHtype =>
              val bound = decodeTree(view, decodeCtx)
              val scrutinee = decodeTree(view, decodeCtx)
              val cases = decodeTreeListUntil(view, decodeCtx, view.position)
              Tasty.Tree.MatchType(bound, scrutinee, cases)
          case TastyFormat.FLEXIBLEtype =>
              Tasty.Tree.FlexibleType(decodeTree(view, decodeCtx))
          case other => throw new TreeUnpickler.DecodeException(s"unknown category-3 tag $other", view.position.toLong)
  ```

### Files to delete
None.

### Public API additions
- 11 new `Tree.*Type` cases.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: APPLIEDtype decodes nested arguments
   - Given: bytes encoding `APPLIEDtype(tycon=Named(List), args=[Named(Int)])`.
   - When: decode.
   - Then: returns `Tree.AppliedType(Named(listSym), Chunk(Named(intSym)))`.
   - Pins: M1 category 3.

2. `TreeUnpicklerTest.scala`: MATCHtype with cases
   - Given: bytes encoding `MATCHtype(bound, scrutinee, [case Named(A) => Named(X), case Named(B) => Named(Y)])`.
   - When: decode.
   - Then: returns `Tree.MatchType(bound, scrutinee, cases)` with `cases.length == 2`.
   - Pins: M1 category 3.

### Consumed invariants
None additional.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 220.

---

## Phase 18d: Decode Tree category 4 tag+Nat+AST

Depends on: Phase 18c.

`TreeUnpickler` adds category 4 decode arms (110 <= tag <= 127): IDENTtpt, SELECTtpt, SINGLETONtpt, TERMREFpkg, TYPEREFpkg, TERMREFsymbol, TYPEREFsymbol, TERMREFdirect, TYPEREFdirect, SELECTin.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add 10 cases.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: add category-4 dispatch.

  ```scala
  else if tag >= 110 && tag <= 127 then
      decodeCategoryFour(tag, view, decodeCtx)

  private def decodeCategoryFour(tag: Int, view: ByteView, decodeCtx: DecodeContext): Tasty.Tree =
      tag match
          case TastyFormat.IDENTtpt =>
              val nameRef = view.readNat()
              val tpe = decodeTree(view, decodeCtx)
              Tasty.Tree.IdentTpt(decodeCtx.names(nameRef), tpe)
          case TastyFormat.SELECTtpt =>
              val nameRef = view.readNat()
              val qual = decodeTree(view, decodeCtx)
              Tasty.Tree.SelectTpt(qual, decodeCtx.names(nameRef))
          case TastyFormat.SINGLETONtpt =>
              Tasty.Tree.SingletonTpt(decodeTree(view, decodeCtx))
          case TastyFormat.TERMREFpkg => Tasty.Tree.TermRefPkg(decodeCtx.names(view.readNat()))
          case TastyFormat.TYPEREFpkg => Tasty.Tree.TypeRefPkg(decodeCtx.names(view.readNat()))
          case TastyFormat.TERMREFsymbol =>
              val addr = view.readNat()
              val qual = decodeTree(view, decodeCtx)
              Tasty.Tree.TermRefSymbol(addr, qual)
          case TastyFormat.TYPEREFsymbol =>
              val addr = view.readNat()
              val qual = decodeTree(view, decodeCtx)
              Tasty.Tree.TypeRefSymbol(addr, qual)
          case TastyFormat.TERMREFdirect => Tasty.Tree.TermRefDirect(view.readNat())
          case TastyFormat.TYPEREFdirect => Tasty.Tree.TypeRefDirect(view.readNat())
          case TastyFormat.SELECTin =>
              val nameRef = view.readNat()
              val qual = decodeTree(view, decodeCtx)
              val owner = decodeTree(view, decodeCtx)
              Tasty.Tree.SelectIn(qual, decodeCtx.names(nameRef), owner)
          case other => throw new TreeUnpickler.DecodeException(s"unknown category-4 tag $other", view.position.toLong)
  ```

### Files to delete
None.

### Public API additions
- 10 new `Tree.*` cases.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: TERMREFpkg decodes name
   - Given: bytes `[TERMREFpkg, <nameRef 5>]` with `names(5) = Name("scala")`.
   - When: decode.
   - Then: returns `Tree.TermRefPkg(Name("scala"))`.
   - Pins: M1 category 4.

2. `TreeUnpicklerTest.scala`: SELECTin captures qual, name, owner
   - Given: bytes encoding `SELECTin(name="map", qual=Named(List), owner=Named(scala))`.
   - When: decode.
   - Then: returns `Tree.SelectIn(Named(listSym), Name("map"), Named(scalaSym))`.
   - Pins: M1 category 4.

### Consumed invariants
None additional.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 180.

---

## Phase 18e: Decode Tree category 5 length-prefixed

Depends on: Phase 18d.

`TreeUnpickler` adds category 5 dispatch (length-prefixed AST tags >= firstLengthTreeTag): VALDEF, DEFDEF, TYPEDEF, TEMPLATE, CLASSDEF, IMPORT, EXPORT, APPLY, TYPEAPPLY, NEW, THROW, RETURN, BLOCK, IF, MATCH, CASEDEF, WHILE, TRY, BIND, ALTERNATIVE, UNAPPLY, ANNOTATION, ANNOTATEDtpt. Existing IDENT/SELECT/APPLY/TYPEAPPLY/BLOCK arms are absorbed. Produces INV-005.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` `Tree` ADT: add 23 cases.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: implement length-prefixed dispatch; the existing IDENT/SELECT/APPLY/TYPEAPPLY/BLOCK arms migrate into this dispatcher.

  ```scala
  else
      val end = view.readEnd()
      val result = decodeCategoryFive(tag, view, end, decodeCtx)
      if view.position != end then view.goto(end)
      result

  private def decodeCategoryFive(tag: Int, view: ByteView, end: Long, decodeCtx: DecodeContext): Tasty.Tree =
      tag match
          case TastyFormat.VALDEF =>
              val nameRef = view.readNat()
              val tpe = decodeTree(view, decodeCtx)
              val rhs = decodeTree(view, decodeCtx)
              val mods = decodeTreeListUntil(view, decodeCtx, end)
              Tasty.Tree.ValDef(decodeCtx.names(nameRef), tpe, rhs, mods)
          // ... DEFDEF, TYPEDEF, TEMPLATE, CLASSDEF, IMPORT, EXPORT, APPLY, TYPEAPPLY, NEW, THROW, RETURN, BLOCK,
          //     IF, MATCH, CASEDEF, WHILE, TRY, BIND, ALTERNATIVE, UNAPPLY, ANNOTATION, ANNOTATEDtpt cases each
          //     following the TASTy spec layout for that tag.
          case other =>
              throw new TreeUnpickler.DecodeException(s"unknown category-5 length-prefixed tag $other", view.position.toLong)
  ```

### Files to delete
None.

### Public API additions
- 23 new `Tree.*` cases.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`.

1. `TreeUnpicklerTest.scala`: VALDEF decodes name, type, rhs, mods
   - Given: bytes encoding `VALDEF(name="x", tpe=Named(Int), rhs=Literal(IntConst(0)), mods=[Private])`.
   - When: decode.
   - Then: returns `Tree.ValDef(Name("x"), Named(intSym), Literal(IntConst(0)), Chunk(Modifier(Private)))`.
   - Pins: INV-005, M1 category 5.

2. `TreeUnpicklerTest.scala`: APPLY length-prefixed decodes args
   - Given: bytes encoding `APPLY(fun=Named(foo), args=[Literal(IntConst(1)), Literal(IntConst(2))])`.
   - When: decode.
   - Then: returns `Tree.Apply(Named(fooSym), Chunk(Literal(IntConst(1)), Literal(IntConst(2))))`.
   - Pins: INV-005.

3. `TreeUnpicklerTest.scala`: real TASTy zero-Unknown sweep
   - Given: real TASTy from `kyo-core` (kyo.Sync source).
   - When: decode body trees and count `Tree.Unknown` emissions.
   - Then: count equals 0.
   - Pins: INV-005 production-corpus.

### Consumed invariants
- INV-014, INV-006.

### Produced invariants
- INV-005: TASTy AST tag coverage in `TreeUnpickler` matches the decomposition axis per Q-003 with no remaining `Tree.Unknown` emission for tags emitted by Scala 3.6+ TASTy v28.8 output.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TreeUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 250.

---

## Phase 19a: Bump snapshot minor version

Depends on: Phase 18e.

`SnapshotFormat.minorVersion` increments 2 to 3; `sectionNames` adds `"TPARAMS_"`. Format-level prep step before populating symbol-relationship section payloads. Produces INV-003 (reaffirmed) and INV-023.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala` (58, 67).

  ```scala
  // BEFORE
  val minorVersion: Int = 2
  val sectionNames: Array[String] = Array("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "FILES", "BODYBYTE", "ERRORS")
  // AFTER
  val minorVersion: Int = 3
  val sectionNames: Array[String] = Array("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "TPARAMS_", "FILES", "BODYBYTE", "ERRORS")
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
- `SnapshotFormat.minorVersion`: 2 to 3.
- `SnapshotFormat.sectionNames`: adds `"TPARAMS_"`.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/SnapshotFormatTest.scala` (new).

1. `SnapshotFormatTest.scala`: minor version is 3
   - Given: constant `SnapshotFormat.minorVersion`.
   - When: read.
   - Then: equals `3`.
   - Pins: INV-023.

2. `SnapshotFormatTest.scala`: sectionNames includes TPARAMS_
   - Given: `SnapshotFormat.sectionNames`.
   - When: read.
   - Then: contains the literal `"TPARAMS_"`.
   - Pins: INV-023, INV-003 add-only.

### Consumed invariants
None.

### Produced invariants
- INV-003: Snapshot format major bump invalidates old snapshots; minor bump is add-only per `SnapshotFormat.scala:42-44`.
- INV-023: Snapshot minorVersion increments from 2 to 3; readers at minor 2 still parse new sections.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SnapshotFormatTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 19b: Serialize symbol relationship sections

Depends on: Phase 19a.

`SnapshotWriter` populates the PARENTS, MEMBERS, and TPARAMS_ sections with real symbol-relationship references; `SnapshotReader` deserializes them and populates `_parents`, `_typeParams`, `_declarations` chunks. Old snapshots without populated sections fall back to `Chunk.empty`. Removes the `Symbol.body stub("Symbol.body")` guard at `Tasty.scala:709` (M10) because `home.isAssigned` is invariant after `Classpath.open` returns. One conceptual change: "serialize the symbol-relationship data added in the new snapshot version". Produces INV-015.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala`: populate PARENTS, MEMBERS, TPARAMS_ section bodies.

  ```scala
  // BEFORE
  writeSection(out, "PARENTS", Array.emptyByteArray)
  writeSection(out, "MEMBERS", Array.emptyByteArray)
  // AFTER
  writeSection(out, "PARENTS",  serializeSymbolRefLists(syms.map(s => (s.id, s.parents.map(typeIdOf)))))
  writeSection(out, "MEMBERS",  serializeSymbolRefLists(syms.map(s => (s.id, s.declarations.map(_.id)))))
  writeSection(out, "TPARAMS_", serializeSymbolRefLists(syms.map(s => (s.id, s.typeParams.map(_.id)))))

  private def serializeSymbolRefLists(entries: Chunk[(Int, Chunk[Int])]): Array[Byte] =
      val out = new java.io.ByteArrayOutputStream()
      writeInt32LE(out, entries.size)
      entries.foreach { (symId, refs) =>
          writeInt32LE(out, symId)
          writeInt32LE(out, refs.size)
          refs.foreach(r => writeInt32LE(out, r))
      }
      out.toByteArray
  ```

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala` (170-177).

  ```scala
  // BEFORE
  syms.foreach(_._parents.set(Chunk.empty))
  syms.foreach(_._typeParams.set(Chunk.empty))
  syms.foreach(_._declarations.set(Chunk.empty))
  // AFTER
  sectionMap.get("PARENTS") match
      case Some((off, len)) if len > 0 =>
          deserializeRefLists(buf, off, len, syms, typesArr, (sym, refs) => sym._parents.set(Chunk(refs.map(typesArr(_))*)))
      case _ => syms.foreach(_._parents.set(Chunk.empty))

  sectionMap.get("TPARAMS_") match
      case Some((off, len)) if len > 0 =>
          deserializeRefLists(buf, off, len, syms, typesArr, (sym, refs) => sym._typeParams.set(Chunk(refs.map(syms(_))*)))
      case _ => syms.foreach(_._typeParams.set(Chunk.empty))

  sectionMap.get("MEMBERS") match
      case Some((off, len)) if len > 0 =>
          deserializeRefLists(buf, off, len, syms, typesArr, (sym, refs) => sym._declarations.set(Chunk(refs.map(syms(_))*)))
      case _ => syms.foreach(_._declarations.set(Chunk.empty))

  private def deserializeRefLists(
      buf: Array[Byte], off: Int, len: Int,
      syms: Array[Symbol], typesArr: Array[Type],
      assign: (Symbol, Array[Int]) => Unit
  ): Unit =
      val cur = new ByteCursor(buf, off, off + len)
      val n = cur.readInt32LE()
      var i = 0
      while i < n do
          val symId = cur.readInt32LE()
          val count = cur.readInt32LE()
          val refs = new Array[Int](count)
          var j = 0
          while j < count do
              refs(j) = cur.readInt32LE()
              j += 1
          assign(syms(symId), refs)
          i += 1
  ```

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` (709): remove `stub("Symbol.body")` defensive guard.

  ```scala
  // BEFORE
  if !home.isAssigned then stub("Symbol.body")
  else
      home.get().checkOpen.andThen:
          ...
  // AFTER
  // home.isAssigned is invariant=true after Classpath.open returns (assignHomes guarantees it).
  home.get().checkOpen.andThen:
      ...
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 4. Tests live in `kyo-tasty/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`, `SnapshotWriterTest.scala` (new), `SnapshotReaderTest.scala` (new), `TastyTest.scala`.

1. `SnapshotWriterTest.scala`: PARENTS/MEMBERS/TPARAMS_ sections length > 0
   - Given: Classpath with class `Foo extends Bar { def m = 1 }` plus type param `T`; write snapshot.
   - When: read the snapshot file's section table.
   - Then: each of `PARENTS`, `MEMBERS`, `TPARAMS_` has `len > 0`.
   - Pins: INV-015 writer side.

2. `SnapshotRoundTripTest.scala`: parents/typeParams/declarations round-trip preserves chunks
   - Given: Classpath with `Foo extends Bar with Baz` (parents) plus type params `[A, B]` plus members `[m, n]`; write snapshot; read back as `cp2`.
   - When: `cp2.findClass("Foo").get.{parents,typeParams,declarations}.map(_.show or _.name.asString)`.
   - Then: parents returns `Chunk("Bar", "Baz")`; typeParams returns `Chunk("A", "B")`; declarations contains `"m"` and `"n"`.
   - Pins: INV-015, M4.

3. `SnapshotReaderTest.scala`: minor=2 snapshot loads with empty parents
   - Given: pre-recorded snapshot at minorVersion 2 (empty PARENTS/MEMBERS/TPARAMS_).
   - When: load.
   - Then: load succeeds; `cp.findClass("Foo").get.parents == Chunk.empty`; no `SnapshotVersionMismatch`.
   - Pins: INV-023 forward-compat.

4. `TastyTest.scala`: no `stub("Symbol.body")` in production source
   - Given: source `Tasty.scala` read as text.
   - When: substring scan.
   - Then: `"stub(\"Symbol.body\")"` appears 0 times.
   - Pins: M10 (Tasty.scala:709 legacy-stub removal).

### Consumed invariants
- INV-003, INV-023.

### Produced invariants
- INV-015: Snapshot warm-cache restore returns the full `_parents`, `_typeParams`, `_declarations` chunks.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SnapshotRoundTripTest kyo.SnapshotWriterTest kyo.SnapshotReaderTest kyo.TastyTest'`. JS / Native equivalents.

estimated_loc: 250.

---

## Phase 20a: Wire Native InflateHook through java.util.zip

Depends on: Phase 14a.

Native `InflateHook` delegates to `java.util.zip.InflaterInputStream` (already in scala-native javalib per Q-002). One-liner equivalent of the JVM hook.

### Files to produce
None.

### Files to modify
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`.

  ```scala
  // BEFORE
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Abort.fail(TastyError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala Native"))
  end InflateHook
  // AFTER
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Sync.defer:
              try
                  val inflater = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed))
                  val out = new java.io.ByteArrayOutputStream()
                  val buf = new Array[Byte](4096)
                  var n = inflater.read(buf)
                  while n > 0 do
                      out.write(buf, 0, n)
                      n = inflater.read(buf)
                  inflater.close()
                  Right(out.toByteArray)
              catch
                  case ex: java.util.zip.ZipException =>
                      Left(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, 0L))
                  case ex: java.io.IOException =>
                      Left(TastyError.CorruptedFile("Scala2Inflate", 0L, ex.getMessage))
          .map:
              case Right(b) => b
              case Left(e)  => Abort.fail(e)
  end InflateHook
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/InflateHookTest.scala` (new).

1. `InflateHookTest.scala` (Native): inflate produces same bytes as JVM reference
   - Given: fixed RFC 1950 input `data` (captured 1024-byte Scala 2 Scala-attribute payload).
   - When: run `InflateHook.inflate(data)` on Native and JVM.
   - Then: byte arrays equal via `Arrays.equals`.
   - Pins: M5 Native.

### Consumed invariants
- INV-006.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, native]

### Verification command
`sbt 'kyo-tastyNative/Test/compile' 'kyo-tastyNative/testOnly kyo.InflateHookTest'`.

estimated_loc: 50.

---

## Phase 20b: Build BitStream primitive for JS

Depends on: none.

New shared module `PortableInflate.BitStream`: bit-level reader over a byte array. Used by all subsequent in-tree RFC 1951 decoders.

### Files to produce
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`: skeleton with BitStream and object stub.

  Matching test: `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala` (new prefix-match).

  ```scala
  // kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala
  package kyo.internal.tasty.scala2

  /** Pure-Scala RFC 1950 (ZLIB) inflate. No JVM dependencies. */
  object PortableInflate:

      final class InflateException(msg: String, val byteOffset: Long) extends RuntimeException(msg)

      /** Bit-level reader over a byte array. LSB-first per DEFLATE spec. */
      private[scala2] final class BitStream(buf: Array[Byte], var bitOffset: Long):
          def byteOffset: Long = bitOffset >> 3

          def readBit(): Int =
              val byte = buf((bitOffset >> 3).toInt) & 0xff
              val bit  = (byte >> (bitOffset & 7).toInt) & 1
              bitOffset += 1
              bit

          def readBits(n: Int): Int =
              var result = 0
              var i      = 0
              while i < n do
                  result |= (readBit() << i)
                  i += 1
              result

          def alignToByte(): Int =
              val rem = bitOffset & 7
              if rem != 0 then bitOffset += (8 - rem)
              (bitOffset >> 3).toInt

          def readBytes(out: scala.collection.mutable.ArrayBuffer[Byte], len: Int): Unit =
              alignToByte()
              val start = (bitOffset >> 3).toInt
              var i = 0
              while i < len do
                  out += buf(start + i)
                  i += 1
              bitOffset += (len * 8)
      end BitStream

      // Placeholder for the full inflate; subsequent phases add Huffman, blocks, ZLIB wrapper.

  end PortableInflate
  ```

### Files to modify
None.

### Files to delete
None.

### Public API additions
None (package-private).

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala` (new).

1. `PortableInflateTest.scala`: BitStream reads single bits LSB-first
   - Given: byte array `[0b10110100]`; BitStream at bitOffset 0.
   - When: 8 `readBit()` calls.
   - Then: returned values `[0, 0, 1, 0, 1, 1, 0, 1]` (LSB first).
   - Pins: M5 BitStream.

2. `PortableInflateTest.scala`: BitStream.readBits(n) packs LSB-first
   - Given: byte `[0b11010110]`; BitStream at offset 0.
   - When: `readBits(4)` then `readBits(4)`.
   - Then: returns `0b0110` then `0b1101`.
   - Pins: M5 BitStream.

### Consumed invariants
None.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PortableInflateTest'`. JS / Native equivalents.

estimated_loc: 90.

---

## Phase 20c: Build Huffman decoder for JS

Depends on: Phase 20b.

`PortableInflate` adds `HuffmanTree.fromCodeLengths` and `HuffmanTree.decodeOne` per RFC 1951 §3.2.2 canonical Huffman.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`: add HuffmanTree.

  ```scala
  // ADDED
  private[scala2] final class HuffmanTree private (
      val maxBits: Int,
      val codeToSymbol: Array[Int],
      val bitLengthCounts: Array[Int]
  ):
      def decodeOne(stream: BitStream): Int =
          var code = 0
          var first = 0
          var index = 0
          var len = 1
          while len <= maxBits do
              code = (code << 1) | stream.readBit()
              val count = bitLengthCounts(len)
              if code - count < first then
                  return codeToSymbol(index + (code - first))
              index += count
              first  = (first + count) << 1
              len += 1
          throw new InflateException(s"invalid Huffman code at bit ${stream.bitOffset}", stream.byteOffset)
  end HuffmanTree

  private[scala2] object HuffmanTree:
      def fromCodeLengths(lengths: Array[Int]): HuffmanTree =
          val maxBits = lengths.max
          val bitLengthCounts = new Array[Int](maxBits + 1)
          var i = 0
          while i < lengths.length do
              bitLengthCounts(lengths(i)) += 1
              i += 1
          bitLengthCounts(0) = 0
          val codeToSymbol = new Array[Int](lengths.length)
          val offsets = new Array[Int](maxBits + 1)
          var sum = 0
          var len = 1
          while len <= maxBits do
              offsets(len) = sum
              sum += bitLengthCounts(len)
              len += 1
          var sym = 0
          while sym < lengths.length do
              val l = lengths(sym)
              if l != 0 then
                  codeToSymbol(offsets(l)) = sym
                  offsets(l) += 1
              sym += 1
          new HuffmanTree(maxBits, codeToSymbol, bitLengthCounts)
  end HuffmanTree
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala`.

1. `PortableInflateTest.scala`: Huffman builds from RFC 1951 §3.2.2 example
   - Given: code lengths `[3, 3, 3, 3, 3, 2, 4, 4]` (RFC example).
   - When: `HuffmanTree.fromCodeLengths(lengths)`.
   - Then: decoding stream `[0b010, 0b011, 0b100, ...]` returns symbols `[0, 1, 2, ...]`.
   - Pins: M5 Huffman.

2. `PortableInflateTest.scala`: invalid Huffman code throws InflateException
   - Given: a HuffmanTree with no code prefix matching the bit stream.
   - When: `decodeOne(stream)`.
   - Then: `InflateException` thrown.
   - Pins: M5 Huffman negative.

### Consumed invariants
None.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PortableInflateTest'`. JS / Native equivalents.

estimated_loc: 130.

---

## Phase 20d: Decode RFC 1951 deflate blocks

Depends on: Phase 20c.

`PortableInflate` adds decoders for all three RFC 1951 §3.2 block types: stored (type 0), fixed Huffman (type 1), dynamic Huffman (type 2). One conceptual change: "decode all deflate block types per RFC 1951 §3.2".

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`: add stored, fixed-Huffman, dynamic-Huffman decoders.

  ```scala
  // ADDED: §3.2.4 stored block
  private def decodeStoredBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
      val byteStart = stream.alignToByte()
      val lenLow  = stream.readBits(8)
      val lenHigh = stream.readBits(8)
      val len     = lenLow | (lenHigh << 8)
      val nlenLow  = stream.readBits(8)
      val nlenHigh = stream.readBits(8)
      val nlen     = nlenLow | (nlenHigh << 8)
      if (len ^ nlen) != 0xffff then
          throw new InflateException(s"stored block LEN ^ NLEN != 0xffff (LEN=$len NLEN=$nlen)", stream.byteOffset)
      stream.readBytes(out, len)

  // ADDED: §3.2.6 fixed-Huffman block
  private val fixedLiteralLengths: Array[Int] =
      Array.tabulate(288) { i =>
          if i < 144 then 8 else if i < 256 then 9 else if i < 280 then 7 else 8
      }
  private val fixedDistanceLengths: Array[Int] = Array.fill(30)(5)
  private lazy val fixedLiteralTree: HuffmanTree = HuffmanTree.fromCodeLengths(fixedLiteralLengths)
  private lazy val fixedDistanceTree: HuffmanTree = HuffmanTree.fromCodeLengths(fixedDistanceLengths)

  private def decodeFixedHuffmanBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
      decodeHuffmanBlock(stream, out, fixedLiteralTree, fixedDistanceTree)

  private def decodeHuffmanBlock(
      stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte],
      litTree: HuffmanTree, distTree: HuffmanTree
  ): Unit =
      var done = false
      while !done do
          val sym = litTree.decodeOne(stream)
          if sym < 256 then
              out += sym.toByte
          else if sym == 256 then
              done = true
          else
              val (length, lExtra) = lengthCode(sym)
              val len = length + (if lExtra > 0 then stream.readBits(lExtra) else 0)
              val distSym = distTree.decodeOne(stream)
              val (distBase, dExtra) = distanceCode(distSym)
              val dist = distBase + (if dExtra > 0 then stream.readBits(dExtra) else 0)
              copyBack(out, dist, len)

  private def lengthCode(sym: Int): (Int, Int) = ??? // RFC 1951 §3.2.5 length code table
  private def distanceCode(sym: Int): (Int, Int) = ???
  private def copyBack(out: scala.collection.mutable.ArrayBuffer[Byte], dist: Int, len: Int): Unit =
      var i = 0
      val start = out.length
      while i < len do
          out += out(start - dist + i)
          i += 1

  // ADDED: §3.2.7 dynamic-Huffman block
  private val codeLengthOrder: Array[Int] = Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

  private def decodeDynamicHuffmanBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
      val hlit  = stream.readBits(5) + 257
      val hdist = stream.readBits(5) + 1
      val hclen = stream.readBits(4) + 4
      val codeLengths = new Array[Int](19)
      var i = 0
      while i < hclen do
          codeLengths(codeLengthOrder(i)) = stream.readBits(3)
          i += 1
      val codeLengthTree = HuffmanTree.fromCodeLengths(codeLengths)
      val combined = decodeCodeLengths(stream, codeLengthTree, hlit + hdist)
      val litLens  = combined.slice(0, hlit)
      val distLens = combined.slice(hlit, hlit + hdist)
      val litTree  = HuffmanTree.fromCodeLengths(litLens)
      val distTree = HuffmanTree.fromCodeLengths(distLens)
      decodeHuffmanBlock(stream, out, litTree, distTree)

  private def decodeCodeLengths(stream: BitStream, tree: HuffmanTree, total: Int): Array[Int] =
      val arr = new Array[Int](total)
      var i = 0
      while i < total do
          val sym = tree.decodeOne(stream)
          if sym <= 15 then
              arr(i) = sym
              i += 1
          else if sym == 16 then
              val n = stream.readBits(2) + 3
              val v = arr(i - 1)
              var k = 0
              while k < n do
                  arr(i + k) = v
                  k += 1
              i += n
          else if sym == 17 then
              val n = stream.readBits(3) + 3
              i += n
          else
              val n = stream.readBits(7) + 11
              i += n
      arr
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala`.

1. `PortableInflateTest.scala`: stored block decodes raw bytes
   - Given: a stored block with LEN=3, NLEN=~LEN, payload `[0x41, 0x42, 0x43]` (ASCII "ABC").
   - When: `decodeStoredBlock(stream, out)`.
   - Then: `out.toArray` equals `[0x41, 0x42, 0x43]`.
   - Pins: M5 stored.

2. `PortableInflateTest.scala`: fixed Huffman block decodes "AAA"
   - Given: a DEFLATE fixed-Huffman block compressed from `"AAA"`.
   - When: `decodeFixedHuffmanBlock(stream, out)`.
   - Then: `out.toArray` equals `[0x41, 0x41, 0x41]`.
   - Pins: M5 fixed Huffman.

3. `PortableInflateTest.scala`: dynamic Huffman block decodes "the quick brown fox"
   - Given: ZLIB stream with a dynamic Huffman block compressed from `"the quick brown fox"`.
   - When: full `PortableInflate.inflate(stream)` (relies on Phase 20e for the wrapper; this test runs end-to-end after 20e).
   - Then: returned bytes equal `"the quick brown fox".getBytes(StandardCharsets.UTF_8)`.
   - Pins: M5 dynamic Huffman.

### Consumed invariants
None.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PortableInflateTest'`. JS / Native equivalents.

estimated_loc: 360.

---

## Phase 20e: Wrap deflate in ZLIB framing with Adler-32

Depends on: Phase 20d.

`PortableInflate.inflate` reads the RFC 1950 CMF/FLG header, dispatches to block decoders, and verifies the Adler-32 trailer.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala`.

  ```scala
  // ADDED
  def inflate(compressed: Array[Byte]): Array[Byte] =
      if compressed.length < 6 then
          throw new InflateException("ZLIB input too short (< 6 bytes)", 0L)
      val cmf = compressed(0) & 0xff
      val flg = compressed(1) & 0xff
      if (cmf & 0x0f) != 8 then
          throw new InflateException(s"unsupported compression method ${cmf & 0x0f}", 0L)
      if ((cmf << 8) | flg) % 31 != 0 then
          throw new InflateException("ZLIB header checksum failed", 0L)
      if (flg & 0x20) != 0 then
          throw new InflateException("ZLIB preset dictionary not supported", 1L)
      val stream = new BitStream(compressed, bitOffset = 16)
      val out    = new scala.collection.mutable.ArrayBuffer[Byte](compressed.length * 4)
      var lastBlock = false
      while !lastBlock do
          lastBlock = stream.readBit() == 1
          val blockType = stream.readBits(2)
          blockType match
              case 0 => decodeStoredBlock(stream, out)
              case 1 => decodeFixedHuffmanBlock(stream, out)
              case 2 => decodeDynamicHuffmanBlock(stream, out)
              case 3 => throw new InflateException("reserved DEFLATE block type 3", stream.byteOffset)
      val tail = stream.alignToByte()
      val expectedAdler = readU32BE(compressed, tail)
      val actualAdler   = adler32(out.toArray)
      if expectedAdler != actualAdler then
          throw new InflateException(s"Adler-32 mismatch: expected $expectedAdler got $actualAdler", tail.toLong)
      out.toArray
  end inflate

  private def adler32(data: Array[Byte]): Long =
      var a = 1L
      var b = 0L
      var i = 0
      while i < data.length do
          a = (a + (data(i) & 0xff)) % 65521
          b = (b + a) % 65521
          i += 1
      (b << 16) | a

  private def readU32BE(buf: Array[Byte], offset: Int): Long =
      ((buf(offset) & 0xffL) << 24) | ((buf(offset + 1) & 0xffL) << 16) | ((buf(offset + 2) & 0xffL) << 8) | (buf(offset + 3) & 0xffL)
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PortableInflateTest.scala`.

1. `PortableInflateTest.scala`: ZLIB input shorter than 6 bytes rejected
   - Given: 4 bytes.
   - When: `PortableInflate.inflate(bytes)`.
   - Then: `InflateException("ZLIB input too short (< 6 bytes)", 0L)`.
   - Pins: M5 ZLIB wrapper.

2. `PortableInflateTest.scala`: Adler-32 mismatch rejected
   - Given: valid stored block with corrupted trailing Adler-32.
   - When: `PortableInflate.inflate(bytes)`.
   - Then: `InflateException("Adler-32 mismatch: ...", _)`.
   - Pins: M5 Adler verification.

### Consumed invariants
None.

### Produced invariants
None yet.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PortableInflateTest'`. JS / Native equivalents.

estimated_loc: 110.

---

## Phase 20f: Wire JS InflateHook through PortableInflate

Depends on: Phase 20e.

JS `InflateHook` delegates to the in-tree `PortableInflate.inflate`. Produces INV-017 and INV-024.

### Files to produce
None.

### Files to modify
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`.

  ```scala
  // BEFORE
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Abort.fail(TastyError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala.js"))
  end InflateHook
  // AFTER
  object InflateHook extends InflateHookImpl:
      def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
          Sync.defer:
              try Right(kyo.internal.tasty.scala2.PortableInflate.inflate(compressed))
              catch
                  case ex: kyo.internal.tasty.scala2.PortableInflate.InflateException =>
                      Left(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, ex.byteOffset))
          .map:
              case Right(b) => b
              case Left(e)  => Abort.fail(e)
  end InflateHook
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/InflateHookTest.scala` and `Scala2PickleTest.scala`.

1. `InflateHookTest.scala` (JS): JS hook produces same bytes as JVM reference
   - Given: fixed 1024-byte RFC 1950 input.
   - When: run on JS and JVM.
   - Then: byte-equal via `Arrays.equals`.
   - Pins: INV-017, INV-024.

2. `InflateHookTest.scala`: corrupted ZLIB header returns MalformedSection
   - Given: stream whose first byte is `0xff` (invalid CMF).
   - When: `InflateHook.inflate` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Scala2Inflate", _, _))`.
   - Pins: INV-017 error path.

3. `Scala2PickleTest.scala`: end-to-end inflate-then-decode parity across platforms
   - Given: real Scala 2 classfile with Scala-attribute ZLIB payload.
   - When: load via `Classpath.open` on JS, Native, JVM.
   - Then: resulting Symbol tables identical across all three platforms (same FQNs and kinds).
   - Pins: INV-024 cross-platform parity.

### Consumed invariants
- INV-006.

### Produced invariants
- INV-017: JS and Native `InflateHook` implementations produce byte-for-byte parity with the JVM reference on valid RFC 1950 input.
- INV-024: JVM and Native `InflateHook` use `java.util.zip.InflaterInputStream`; JS uses in-tree pure-Scala RFC 1950 inflate matching JVM byte-for-byte.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InflateHookTest kyo.Scala2PickleTest' 'kyo-tastyJS/testOnly kyo.InflateHookTest' 'kyo-tastyNative/testOnly kyo.InflateHookTest kyo.Scala2PickleTest'`.

estimated_loc: 50.

---

## Phase 21a: Test binary subsystem

Depends on: Phase 03a.

Adds T2 coverage for the binary subsystem: Varint round-trip scenarios beyond the bounds checks landed in 03a. One conceptual change: dedicated coverage of the binary primitives.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala`.

1. `VarintTest.scala`: writeNat then readNat round-trip
   - Given: `Varint.writeNat(out, 1234)` then `Varint.readNat(view)`.
   - When: round-trip.
   - Then: returned Int equals `1234`.
   - Pins: T2.

2. `VarintTest.scala`: writeLongNat then readLongNat round-trip
   - Given: `Varint.writeLongNat(out, 9_999_999_999L)`.
   - When: read back.
   - Then: returned Long equals `9_999_999_999L`.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.VarintTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 21b: Test classfile subsystem

Depends on: Phase 09.

Adds T2 coverage for ConstantPool entry kinds beyond 09's typed-accessor scenarios, plus dedicated tests for JavaAnnotationUnpickler.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 4. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala` and `JavaAnnotationUnpicklerTest.scala` (new).

1. `ConstantPoolTest.scala`: entry out of range throws
   - Given: ConstantPool with 5 entries; `pool.entry(99)`.
   - When: invoked.
   - Then: `ClassfileFormatException` containing `"index 99 out of range"`.
   - Pins: T2.

2. `ConstantPoolTest.scala`: ClassRef.nameIdx resolves to Utf8
   - Given: pool with `entries(5) = ClassRef(nameIdx = 6)` and `entries(6) = Utf8("scala/Int")`.
   - When: `pool.utf8At(pool.classRefAt(5).nameIdx)`.
   - Then: returns `"scala/Int"`.
   - Pins: T2.

3. `JavaAnnotationUnpicklerTest.scala`: simple annotation reads correctly
   - Given: classfile bytestream of `@Deprecated`; pool indexes set up.
   - When: `JavaAnnotationUnpickler.read(view, pool)`.
   - Then: `JavaAnnotation(annClass, Map.empty)` where `annClass.fullName.asString == "java.lang.Deprecated"`.
   - Pins: T2.

4. `JavaAnnotationUnpicklerTest.scala`: annotation with array value
   - Given: bytes for `@Foo({"a", "b"})`.
   - When: parse.
   - Then: `JavaAnnotation(_, Map(Name("value") -> ArrayVal(Chunk(StringVal("a"), StringVal("b")))))`.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ConstantPoolTest kyo.JavaAnnotationUnpicklerTest'`. JS / Native equivalents.

estimated_loc: 120.

---

## Phase 21c: Test query subsystem

Depends on: Phase 02c, Phase 07b.

Adds T2 coverage for query subsystem internals: ClasspathRef, UnresolvedRef, TastyStat, PerfCounters extended scenarios.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 5. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ClasspathRefTest.scala`, `UnresolvedRefTest.scala` (new), `TastyStatTest.scala` (new), `PerfCountersTest.scala`.

1. `ClasspathRefTest.scala`: unassigned ref get throws
   - Given: fresh `ClasspathRef` with no assignment.
   - When: `ref.get()` with AllowUnsafe.
   - Then: `IllegalStateException` containing `"not assigned"`.
   - Pins: T2.

2. `ClasspathRefTest.scala`: second assign throws
   - Given: `ClasspathRef`; first `ref.assign(cp1)` succeeds.
   - When: second `ref.assign(cp2)`.
   - Then: `IllegalStateException` containing `"already assigned"`.
   - Pins: T2.

3. `UnresolvedRefTest.scala`: `UnresolvedRef.make` produces Unresolved Symbol
   - Given: `fqn = "missing.X"`, `classpathRef`.
   - When: `UnresolvedRef.make(fqn, classpathRef)`.
   - Then: returned Symbol has `kind == SymbolKind.Unresolved`; `fullName.asString == "missing.X"`.
   - Pins: T2.

4. `TastyStatTest.scala`: scope traceSpan invokes block with attributes
   - Given: side-effect counter; `TastyStat.scope.traceSpan("test", Attributes.empty) { counter.incrementAndGet() }`.
   - When: invoked.
   - Then: `counter.get() == 1`.
   - Pins: T2.

5. `PerfCountersTest.scala`: increment monotonically grows counters
   - Given: fresh PerfCounters.
   - When: `incJarOpen` 5 times; `incEntryRead` 3 times.
   - Then: `snapshot().jarOpenCount == 5` and `entryReadCount == 3`.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ClasspathRefTest kyo.UnresolvedRefTest kyo.TastyStatTest kyo.PerfCountersTest'`. JS / Native equivalents.

estimated_loc: 150.

---

## Phase 21d: Test reader subsystem

Depends on: Phase 03a.

Adds T2 coverage for SectionIndex lookup behavior beyond bounds checks landed in 03a.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/SectionIndexTest.scala`.

1. `SectionIndexTest.scala`: lookup returns offset and length
   - Given: bytes encoding two sections `(NAMES, len=10)` then `(ASTs, len=20)`; `names(0) = Name("NAMES")`, `names(1) = Name("ASTs")`.
   - When: `SectionIndex.read(view, names).lookup("ASTs")`.
   - Then: returns `Some((offset, 20))` matching the second section's payload start.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.SectionIndexTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 21e: Test scala2 subsystem

Depends on: Phase 20f.

Adds T2 coverage for InflateHook (extended cross-platform behavior beyond 20a/20f basics).

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/InflateHookTest.scala`.

1. `InflateHookTest.scala`: empty input produces empty output
   - Given: a 6-byte ZLIB stream that decodes to zero bytes (header plus Adler).
   - When: `InflateHook.inflate(input)`.
   - Then: returns `Array.emptyByteArray`.
   - Pins: T2.

### Consumed invariants
- INV-017.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.InflateHookTest'`. JS / Native equivalents.

estimated_loc: 25.

---

## Phase 21f: Test snapshot subsystem

Depends on: Phase 19a.

Adds T2 coverage for DigestComputer and SnapshotFormat constants.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/DigestComputerTest.scala` (new) and `SnapshotFormatTest.scala`.

1. `DigestComputerTest.scala`: same input produces same digest
   - Given: byte array `[1, 2, 3]`.
   - When: compute digest twice.
   - Then: digests equal.
   - Pins: T2.

2. `DigestComputerTest.scala`: different input produces different digest
   - Given: `[1, 2, 3]` and `[1, 2, 4]`.
   - When: compute both.
   - Then: digests differ.
   - Pins: T2.

3. `SnapshotFormatTest.scala`: magic bytes are `kRfl`
   - Given: `SnapshotFormat.magic`.
   - When: `new String(magic, StandardCharsets.US_ASCII)`.
   - Then: equals `"kRfl"`.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.DigestComputerTest kyo.SnapshotFormatTest'`. JS / Native equivalents.

estimated_loc: 60.

---

## Phase 21g: Test symbol subsystem

Depends on: Phase 02a, Phase 06, Phase 16.

Adds T2 coverage for symbol subsystem internals: Constant, FqnCanonicalizer, OnceCell extended, SingleAssign, Symbol direct construction, SymbolKind enum.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 7. Tests live in `kyo-tasty/shared/src/test/scala/kyo/ConstantTest.scala` (new), `FqnCanonicalizerTest.scala` (new), `OnceCellTest.scala`, `SingleAssignTest.scala` (new), `TastySymbolTest.scala`, `SymbolKindTest.scala` (new).

1. `ConstantTest.scala`: STRINGconst decodes via name table
   - Given: bytes `[STRINGconst, <nameRef 2>]` with `names(2) = Name("hello")`.
   - When: `Constant.fromTastyTag(STRINGconst, view, decodeCtx)`.
   - Then: returns `Constant.StringConst("hello")`.
   - Pins: T2.

2. `ConstantTest.scala`: NULLconst returns canonical NullConst
   - Given: bytes `[NULLconst]`.
   - When: decode.
   - Then: returns `Constant.NullConst`.
   - Pins: T2.

3. `FqnCanonicalizerTest.scala`: dotted form is canonical
   - Given: `"com/example/Foo$Inner"` (JVM binary form).
   - When: `FqnCanonicalizer.toDotted(input)`.
   - Then: returns `"com.example.Foo.Inner"`.
   - Pins: T2.

4. `OnceCellTest.scala`: cell holding null-valued reference accepts the reference
   - Given: `OnceCell[String](() => null.asInstanceOf[String])`.
   - When: `cell.get()` with AllowUnsafe.
   - Then: returns `null` (not throwing); second `cell.get()` returns the same null.
   - Pins: T2.

5. `SingleAssignTest.scala`: set/get round trip
   - Given: `SingleAssign[Int]`.
   - When: `slot.set(7); slot.get()`.
   - Then: returns `7`.
   - Pins: T2.

6. `SingleAssignTest.scala`: second set throws
   - Given: `SingleAssign[Int]` with prior `set(7)`.
   - When: second `set(8)`.
   - Then: `IllegalStateException` containing `"already assigned"`.
   - Pins: T2.

7. `TastySymbolTest.scala`: Symbol.make produces Symbol with correct kind and name
   - Given: `sym = Tasty.Symbol.make(name = Name("Foo"), kind = SymbolKind.Class, owner = rootOwner, flags = Flag.empty, origin = Origin.Synthetic)`.
   - When: read `sym.name`, `sym.kind`, `sym.owner`.
   - Then: name equals `Name("Foo")`; kind equals `SymbolKind.Class`; owner equals `rootOwner`.
   - Pins: T2.

8. `SymbolKindTest.scala`: enum has the expected case set
   - Given: `Tasty.SymbolKind.values`.
   - When: convert to set.
   - Then: set contains every public SymbolKind case (Class, Trait, Module, Package, Method, Val, Var, Type, TypeParam, ParamVal, ParamType, Constructor, Unresolved, ...); `values.length` matches the count of public cases.
   - Pins: T2.

### Consumed invariants
- INV-001, INV-009.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.ConstantTest kyo.FqnCanonicalizerTest kyo.OnceCellTest kyo.SingleAssignTest kyo.TastySymbolTest kyo.SymbolKindTest'`. JS / Native equivalents.

estimated_loc: 200.

---

## Phase 21h: Test type subsystem

Depends on: none.

Adds T2 coverage for PlatformHashingState (cross-platform stable hashing primitive used by the type interner).

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/PlatformHashingStateTest.scala` (new).

1. `PlatformHashingStateTest.scala`: hashing produces stable output across platforms
   - Given: byte array `[1, 2, 3]`.
   - When: hash via `PlatformHashingState`.
   - Then: returned Long matches the canonical FNV-1a 64-bit golden value captured ahead of time.
   - Pins: T2.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.PlatformHashingStateTest'`. JS / Native equivalents.

estimated_loc: 30.

---

## Phase 22a: Test UTF-8 edge inputs

Depends on: none.

Edge-input tests for the UTF-8 decoder: 4-byte supplementary characters (surrogate pairs), modified-UTF-8 overlong null `0xC0 0x80`, highest valid code point U+10FFFF.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/Utf8Test.scala`.

1. `Utf8Test.scala`: UTF-8 surrogate pair decodes to two-char String
   - Given: bytes `[0xf0, 0x9f, 0x98, 0x80]` (U+1F600 grinning emoji).
   - When: `Utf8.decode(bytes, 0, 4)`.
   - Then: returned String length 2; `codePointAt(0) == 0x1F600`.
   - Pins: T4.

2. `Utf8Test.scala`: modified-UTF-8 null rejected
   - Given: bytes `[0xc0, 0x80]`.
   - When: `Utf8.decode(bytes, 0, 2)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("Utf8", reason, _))` containing `"overlong"`.
   - Pins: T4.

3. `Utf8Test.scala`: 4-byte UTF-8 at U+10FFFF
   - Given: bytes `[0xf4, 0x8f, 0xbf, 0xbf]`.
   - When: decode.
   - Then: returned String length 2 (surrogate pair); `codePointAt(0) == 0x10FFFF`.
   - Pins: T4.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.Utf8Test'`. JS / Native equivalents.

estimated_loc: 75.

---

## Phase 22b: Test type-graph edges

Depends on: Phase 02a, Phase 08a.

Edge-input tests for the type-graph extremes: Rec at depth boundary, cyclic Rec self-reference, root-owned symbols.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TypeArenaTest.scala` and `TastySymbolTest.scala`.

1. `TypeArenaTest.scala`: Rec type at exactly MaxDepth-1 succeeds
   - Given: nesting depth exactly `TypeArena.MaxDepth - 1`.
   - When: `merge(Map("k" -> t))`.
   - Then: `Result.Success(_)`; no DepthExceededException.
   - Pins: T4.

2. `TypeArenaTest.scala`: cyclic Rec type handled
   - Given: synthetic Rec type whose RecThis references itself at depth 2.
   - When: intern via `TypeArena.merge`.
   - Then: succeeds; canonical map's value is reference-equal under repeated `internRec` calls.
   - Pins: T4.

3. `TastySymbolTest.scala`: root-owned symbol returns empty FQN
   - Given: synthetic root sentinel Symbol with `owner eq this`.
   - When: `sym.fullName.asString` and `sym.binaryName`.
   - Then: both return `""`.
   - Pins: T4.

### Consumed invariants
- INV-001, INV-019.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TypeArenaTest kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 75.

---

## Phase 22c: Test JAR archive edges

Depends on: Phase 04a.

Edge-input tests for JAR archive corner cases: Zip64 CD past 2GB, multi-disk rejection, JMOD handling.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`.

1. `JarCentralDirectoryTest.scala`: Zip64 JAR with CD past 2GB
   - Given: synthetic Zip64 JAR; Zip64 EOCD points to CD at byte `3_000_000_000L`.
   - When: `JarCentralDirectory.read(jar)`.
   - Then: returned entries are non-empty; first entry's `lfhOffset` matches expected.
   - Pins: T4.

2. `JarCentralDirectoryTest.scala`: multi-disk archive rejected
   - Given: synthetic JAR EOCD with `diskNumber = 2`.
   - When: `JarCentralDirectory.read(jar)` via `Abort.run`.
   - Then: `Result.Failure(TastyError.MalformedSection("jar", reason, _))` containing `"multi-disk"`.
   - Pins: T4.

3. `JarCentralDirectoryTest.scala`: JMOD archive recognized
   - Given: a `.jmod` file whose central directory follows Zip layout; a golden JAR with identical content.
   - When: read both.
   - Then: returned Chunks of entries are identical (same names, same offsets).
   - Pins: T4.

### Consumed invariants
- INV-012.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JarCentralDirectoryTest'`.

estimated_loc: 75.

---

## Phase 22d: Test deep symbol structures

Depends on: Phase 02a.

Edge-input tests for deeply nested Symbol structures: nested-class binaryName ladders.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 1. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`.

1. `TastySymbolTest.scala`: deeply nested inner class binaryName
   - Given: classpath with `class A { class B { class C { class D { class E } } } }`; `sym = cp.findClass("A.B.C.D.E").get`.
   - When: `sym.binaryName`.
   - Then: returns `"A$B$C$D$E"`.
   - Pins: T4.

### Consumed invariants
- INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 50.

---

## Phase 23a: Test JS platform-specific paths

Depends on: Phase 20f.

Cross-platform parity tests for JS-only paths: JsFileSource ArrayBuffer behavior, JS Utf8 path, JS InflateHook delegation through PortableInflate.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/JsFileSourceTest.scala` (new), `Utf8Test.scala`, `InflateHookTest.scala`.

1. `JsFileSourceTest.scala`: ArrayBuffer-backed read returns expected bytes
   - Given: a JS ArrayBuffer of 100 bytes from a known source.
   - When: `JsFileSource.read(path, 0, 50)`.
   - Then: returns 50 bytes equal to the source's first 50 bytes.
   - Pins: T5.

2. `Utf8Test.scala` (JS gate): JS Utf8 path matches JVM
   - Given: input bytes for `"hello world"`.
   - When: `Utf8.decode` on JS.
   - Then: returns `"hello world"` char-by-char equal to JVM reference.
   - Pins: T5.

3. `InflateHookTest.scala` (JS gate): JS InflateHook delegates to PortableInflate
   - Given: a known RFC 1950 input.
   - When: `JsInflateHook.inflate(input)` and `PortableInflate.inflate(input)` directly.
   - Then: byte-equal results.
   - Pins: T5, INV-024.

### Consumed invariants
- INV-017, INV-024.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [js]

### Verification command
`sbt 'kyo-tastyJS/Test/compile' 'kyo-tastyJS/testOnly kyo.JsFileSourceTest kyo.Utf8Test kyo.InflateHookTest'`.

estimated_loc: 100.

---

## Phase 23b: Test Native platform-specific paths

Depends on: none.

Cross-platform parity tests for Native-only paths: NativeFileSource POSIX, NativeMmapReader signal safety, Native Utf8 path.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 4. Tests live in `kyo-tasty/shared/src/test/scala/kyo/NativeFileSourceTest.scala` (new), `NativeMmapReaderTest.scala` (new), `Utf8Test.scala`.

1. `NativeFileSourceTest.scala`: POSIX file open/close leaves no descriptor leak
   - Given: temp file written via `Files.write`.
   - When: `NativeFileSource.read(path)`.
   - Then: bytes match the written content; pre-call and post-call fd count are equal.
   - Pins: T5.

2. `NativeMmapReaderTest.scala`: page-fault on closed arena raises IllegalStateException
   - Given: mmap-backed ByteView; `closed = true` after first read.
   - When: second read attempt.
   - Then: throws `IllegalStateException` with `"mmap arena closed"`.
   - Pins: T5.

3. `NativeMmapReaderTest.scala`: signal-safety under concurrent unmap
   - Given: mmap region; concurrent reader fiber; harness triggers forced unmap during read.
   - When: reader fiber completes.
   - Then: throws `IllegalStateException("mmap arena closed")`; no SIGSEGV terminates the runtime.
   - Pins: T5.

4. `Utf8Test.scala` (Native gate): Native Utf8 path matches JVM
   - Given: same `"hello world"` input bytes.
   - When: `Utf8.decode` on Native.
   - Then: char-by-char equal to JVM.
   - Pins: T5.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [native]

### Verification command
`sbt 'kyo-tastyNative/Test/compile' 'kyo-tastyNative/testOnly kyo.NativeFileSourceTest kyo.NativeMmapReaderTest kyo.Utf8Test'`.

estimated_loc: 120.

---

## Phase 24a: Test unsafe-tier concurrency

Depends on: Phase 06, Phase 08a.

Concurrency tests for the unsafe-tier primitives: OnceCell concurrent first-call, SingleAssign concurrent set, TypeArena concurrent interning. One conceptual change: the unsafe-tier primitives behave correctly under multi-fiber race.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala`, `SingleAssignTest.scala`, `TypeArenaTest.scala`.

1. `OnceCellTest.scala`: 64-fiber concurrent first-call returns same value
   - Given: `OnceCell[Long](() => System.nanoTime())`.
   - When: 64 fibers concurrently call `cell.get()`.
   - Then: all 64 returned Longs are equal.
   - Pins: T7, INV-009.

2. `SingleAssignTest.scala`: 16-fiber concurrent set sees one winner
   - Given: `SingleAssign[Int]`; 16 fibers each try `slot.set(fiberIndex)`.
   - When: all complete.
   - Then: exactly one fiber's set succeeded; 15 caught `IllegalStateException("already assigned")`; `slot.get()` equals the winning fiber's index.
   - Pins: T7.

3. `TypeArenaTest.scala`: 8-fiber concurrent interning preserves canonicality
   - Given: empty `TypeArena`; 8 fibers each call `arena.internRec(t)` with same `t`.
   - When: all complete.
   - Then: all 8 returned references are `eq`; `arena.values.size == 1`.
   - Pins: T7.

### Consumed invariants
- INV-009, INV-019.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.OnceCellTest kyo.SingleAssignTest kyo.TypeArenaTest'`. JS / Native equivalents.

estimated_loc: 90.

---

## Phase 24b: Test resource lifecycle cleanup

Depends on: Phase 05a.

Resource-cleanup tests: JAR pool exhaustion under 50-fiber load, classpath close during pending body decode, mmap arena close during `Symbol.body` access. One conceptual change: resource cleanup behaves correctly across pool/classpath/arena lifecycles.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala` and `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`.

1. `JvmFileSourceTest.scala`: JAR pool exhaustion under 50-fiber load
   - Given: `JvmFileSource` with `maxPoolSize = 2`; 50 fibers each request a reader for the same JAR.
   - When: all complete.
   - Then: every fiber's read returns the correct content; pool's `activeCount` returns to 0 at end.
   - Pins: T8.

2. `ClasspathOrchestratorPipelineTest.scala`: classpath close during pending body decode
   - Given: a Classpath; fiber A calls `sym.body`; fiber B calls `Classpath.close(cp)` concurrently.
   - When: both complete in either order.
   - Then: fiber A either returns the decoded Tree OR fails with `TastyError.ClasspathClosed`; never throws an uncaught exception.
   - Pins: T8.

3. `JvmFileSourceTest.scala`: mmap arena close during Symbol.body access
   - Given: mmap-backed Symbol; harness triggers arena close while reading `sym.body`.
   - When: read completes.
   - Then: returns `TastyError.ClasspathClosed` (mapped from inner `IllegalStateException("mmap arena closed")`).
   - Pins: T8.

### Consumed invariants
None.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.JvmFileSourceTest kyo.ClasspathOrchestratorPipelineTest'`. JS / Native equivalents.

estimated_loc: 90.

---

## Phase 25a: Add doctests on public API

Depends on: Phase 02a.

Adds runnable doctest scaladoc examples on five public API entry points: `Name.apply`, `Flags.empty`, `Classpath.findClass`, `Classpath.topLevelClasses`, `Classpath.packages`. One conceptual change: doctest the public API surface gaps from L6.

### Files to produce
None.

### Files to modify
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: add `{{{ ... }}}` doctest examples on the five entry points (Name.apply at 48, Flags.empty at 72, Classpath.findClass at 1014, Classpath.topLevelClasses at 1023, Classpath.packages at 1031; line numbers approximate).

  ```scala
  // BEFORE
  /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes. */
  def apply(s: String): Name = ...

  /** The empty Flags set. */
  val empty: Flags = ...

  /** Look up a class by FQN. */
  def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[TastyError]) = ...

  // AFTER (representative; same shape on all five)
  /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes.
    *
    * Example:
    * {{{
    *   val n = Tasty.Name("scala.Predef")
    *   n.asString == "scala.Predef"
    * }}}
    */
  def apply(s: String): Name = ...

  /** The empty Flags set.
    *
    * Example:
    * {{{
    *   Tasty.Flags.empty.isEmpty == true
    * }}}
    */
  val empty: Flags = ...

  /** Look up a class by fully-qualified name. Returns `Maybe.Absent` if not present.
    *
    * Example:
    * {{{
    *   val sym = Tasty.Classpath.findClass("scala.Predef")
    *   sym.isPresent == true
    * }}}
    */
  def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[TastyError]) = ...

  // Same shape for topLevelClasses and packages.
  ```

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 5. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`.

1. `TastyTest.scala`: Name.apply doctest extracts and compiles
   - Given: the doctest fenced block on `Tasty.Name.apply`.
   - When: `kyo.Test` doctest extractor runs.
   - Then: the block compiles AND evaluating `n.asString == "scala.Predef"` returns `true`.
   - Pins: L6.

2. `TastyTest.scala`: Flags.empty doctest extracts
   - Given: doctest block on `Tasty.Flags.empty`.
   - When: extractor runs.
   - Then: compiles AND `Tasty.Flags.empty.isEmpty == true` returns `true`.
   - Pins: L6.

3. `TastyTest.scala`: Classpath.findClass doctest extracts
   - Given: doctest block on `Classpath.findClass`.
   - When: extractor runs.
   - Then: compiles AND the body evaluates without exception against a fixture classpath containing `scala.Predef`.
   - Pins: L6.

4. `TastyTest.scala`: Classpath.topLevelClasses doctest extracts
   - Given: doctest block on `Classpath.topLevelClasses`.
   - When: extractor runs.
   - Then: compiles AND returns a non-empty result for the fixture classpath's `scala` package.
   - Pins: L6.

5. `TastyTest.scala`: Classpath.packages doctest extracts
   - Given: doctest block on `Classpath.packages`.
   - When: extractor runs.
   - Then: compiles AND returns a Chunk that contains the fixture's `"scala"` package.
   - Pins: L6.

### Consumed invariants
- INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents.

estimated_loc: 150.

---

## Phase 25b: Add seeded generative tests

Depends on: Phase 03a, Phase 02a.

Adds seeded `scala.util.Random` generative property tests for Varint round-trip, UTF-8 round-trip, Symbol fullName builder. No new dependency. One conceptual change: generative property coverage with seeded determinism per steering's no-new-test-framework rule.

### Files to produce
None.

### Files to modify
None (tests only).

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala`, `Utf8Test.scala`, `TastySymbolTest.scala`.

1. `VarintTest.scala`: Varint round-trip on 100 seeded inputs
   - Given: `scala.util.Random(seed = 0L)`; 100 random non-negative Long values in `[0, Long.MaxValue]`.
   - When: for each `n`: `writeLongNat(out, n)` then `readLongNat(view)`.
   - Then: every round-tripped value equals its input.
   - Pins: T6.

2. `Utf8Test.scala`: UTF-8 round-trip on 100 seeded strings
   - Given: `scala.util.Random(seed = 0L)`; 100 random strings of length 0-128 drawn from the BMP plus 10% supplementary chars.
   - When: encode via `Utf8.encode` then `Utf8.decode`.
   - Then: every round-tripped String equals its input.
   - Pins: T6.

3. `TastySymbolTest.scala`: Symbol fullName builder round-trip on 100 seeded chains
   - Given: `scala.util.Random(seed = 0L)`; 100 random ownership chains of 1-10 segments.
   - When: build a Symbol chain and call `sym.fullName.asString`.
   - Then: equals the dot-joined segments.
   - Pins: T6.

### Consumed invariants
- INV-010, INV-001.

### Produced invariants
None.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.VarintTest kyo.Utf8Test kyo.TastySymbolTest'`. JS / Native equivalents.

estimated_loc: 100.

---

## Phase 26: Extract kyo-tasty-examples module

Depends on: none.

Creates sibling sbt module `kyo-tasty-examples` per Q-009. Moves four example files; updates `build.sbt`. Produces INV-022.

### Files to produce
- `kyo-tasty-examples/shared/src/main/scala/examples/CodegenExample.scala`: moved file; `package examples`.

  ```scala
  package examples

  import kyo.*

  /** Example: walk a Classpath and codegen TS facades. (Body identical to the original; only the package declaration changes.) */
  object CodegenExample:
      @main def main(): Unit = ()
  end CodegenExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/IdeHoverExample.scala`.

  ```scala
  package examples

  import kyo.*

  object IdeHoverExample:
      @main def main(): Unit = ()
  end IdeHoverExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/JavaScalaBridgeExample.scala`.

  ```scala
  package examples

  import kyo.*

  object JavaScalaBridgeExample:
      @main def main(): Unit = ()
  end JavaScalaBridgeExample
  ```

- `kyo-tasty-examples/shared/src/main/scala/examples/RuntimeReflectionExample.scala`.

  ```scala
  package examples

  import kyo.*

  object RuntimeReflectionExample:
      @main def main(): Unit = ()
  end RuntimeReflectionExample
  ```

### Files to modify
- `build.sbt`: add `kyo-tasty-examples` module entry.

  ```sbt
  // BEFORE (around line 518)
  lazy val `kyo-tasty-bench` = ...
  // AFTER
  lazy val `kyo-tasty-bench` = ...

  lazy val `kyo-tasty-examples` = crossProject(JVMPlatform, JSPlatform, NativePlatform)
      .crossType(CrossType.Full)
      .in(file("kyo-tasty-examples"))
      .dependsOn(`kyo-tasty`)
      .settings(
          name           := "kyo-tasty-examples",
          libraryName    := "kyo-tasty-examples",
          publish / skip := true
      )
      .jvmSettings(`kyo-settings`*)
      .jsSettings(`kyo-settings`*)
      .nativeSettings(`kyo-settings`*)
  ```

### Files to delete
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/IdeHoverExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/JavaScalaBridgeExample.scala`.
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/RuntimeReflectionExample.scala`.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 3. Tests live in `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`.

1. `TastyTest.scala`: examples no longer ship in main kyo-tasty jar
   - Given: resource scan over `kyo-tasty/jvm/target/scala-3.*/kyo-tasty_3-*.jar`.
   - When: list entries.
   - Then: no entry under `kyo/tasty/examples/` exists in the JAR.
   - Pins: INV-022.

2. `TastyTest.scala`: kyo-tasty-examples sources at expected path
   - Given: directory `kyo-tasty-examples/shared/src/main/scala/examples`.
   - When: list `.scala` files.
   - Then: exactly four files exist matching the names above; each declares `package examples` at line 1.
   - Pins: INV-022.

3. `TastyTest.scala`: kyo-tasty-examples builds against kyo-tasty
   - Given: the sbt module `kyo-tasty-examples` JVM platform.
   - When: a synthetic example file calling `Tasty.Classpath.open(Seq("/tmp"))` is compiled.
   - Then: compile succeeds; import resolves to `kyo.Tasty.*`.
   - Pins: INV-022.

### Consumed invariants
None.

### Produced invariants
- INV-022: `kyo.tasty.examples` package does not ship in `kyo-tasty`; sibling `kyo-tasty-examples` module is the publication point with top-level `package examples`.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm, js, native]

### Verification command
`sbt 'kyo-tasty-examplesJVM/compile' 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.TastyTest'`. JS / Native equivalents.

estimated_loc: 150.

---

## Phase 27: Benchmark regression sweep

Depends on: every prior phase.

Runs `kyo-tasty-bench` benchmarks to verify INV-027 (no perf regression vs pre-campaign baseline). Produces INV-027.

### Files to produce
None.

### Files to modify
None.

### Files to delete
None.

### Public API additions
None.

### Public API modifications
None.

### Tests
Total: 2. Tests live in `kyo-tasty/jvm/src/test/scala/kyo/BenchmarkRegressionTest.scala` (new).

1. `BenchmarkRegressionTest.scala`: cold-load median within tolerance
   - Given: pre-campaign baseline cold-load median captured at `kyo-tasty/bench-baselines/cold-load.json`; post-campaign cold-load median from `kyo-tasty-bench/jmh:run -i 3 -wi 3 -f 1 ColdLoadBench`.
   - When: read both files; compute ratio.
   - Then: `(post / pre) < 1.05`; test prints the actual ratio.
   - Pins: INV-027.

2. `BenchmarkRegressionTest.scala`: warm-cache snapshot read within tolerance
   - Given: pre-campaign warm-cache median; post-campaign warm-cache median from `TastyQueryCompareBench` warm-mode.
   - When: compare.
   - Then: `(post / pre) < 1.05`.
   - Pins: INV-027.

### Consumed invariants
- Every prior INV.

### Produced invariants
- INV-027: No phase regresses kyo-tasty cold-load or warm-cache benchmark medians beyond steering tolerance vs the pre-campaign baseline.

### Convention sweep
[em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set
platforms: [jvm]

### Verification command
`sbt 'kyo-tasty-benchJVM/Jmh/run -i 3 -wi 3 -f 1 ColdLoadBench TastyQueryCompareBench' 'kyo-tastyJVM/testOnly kyo.BenchmarkRegressionTest'`.

estimated_loc: 60.

---
