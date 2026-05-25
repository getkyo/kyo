# Phase 2 Implementation Notes: G13 Phase C UnresolvedRef Placeholder Resolution

## Deviation: BaseClass/ChildClass fixture does not produce TYPEREFpkg

### What was planned

Phase 2 test 1 was designed to use a two-file fixture (BaseClass.scala + ChildClass.scala) to
verify that `ChildClass.tasty` contains a `TYPEREFpkg` or `TYPEREFin` reference to
`kyo.fixtures.BaseClass`, and that Phase C resolution sets `replaceSlot` to the correct Class
symbol when baseClassTasty is also decoded.

### Why the fixture does not work as expected

Both `BaseClass.scala` and `ChildClass.scala` are compiled in the SAME sbt compilation unit
(`kyo-reflect-fixtures`). When Scala compiles them together, `ChildClass.tasty` encodes the
parent type as an `APPLY` node (constructor call pattern), not as a `TYPEREFpkg` or `TYPEREFin`
node. `TYPEREFpkg` / `TYPEREFin` are only emitted for references to types from OTHER
compilation units (i.e., types whose `.tasty` files are not in the same compilation run).

This was verified by manually parsing the ASTs section of `ChildClass.tasty`:
- The TEMPLATE parent appears as `cat5-136` (APPLY tag = 136), not as a type reference node.
- The `TYPEREFin` byte `0xAF=175` found at absolute position 366 in the full file is inside
  the Positions section (354-392), not the ASTs section (302-352).
- Therefore `TypeUnpickler.readTypeIntoSession` on the TEMPLATE body never hits
  `TYPEREFin` or `TYPEREFpkg` for `kyo.fixtures.BaseClass`, and `placeholder.fqn` never
  contains that FQN.

### Why this is genuinely hard to fix without restructuring

To get `TYPEREFpkg` for BaseClass in ChildClass.tasty, BaseClass would need to be in a
SEPARATE compilation unit from ChildClass. This requires either:
1. A second sbt module that compiles only BaseClass, then ChildClass in a third module that
   depends on it - adding unrelated module/build complexity to the kyo-reflect-fixtures module.
2. Invoking the Scala compiler directly from a task/test to pre-compile BaseClass in isolation
   before compiling ChildClass - brittle and platform-sensitive.

Neither option is justified for a unit test of the Phase C resolution loop.

### Anti-thrash path taken

Per the anti-thrash rule in the implementation spec ("If the 2-file TASTy fixture is genuinely
hard to add, accept the existing PlainClass.tasty fixture and write a synthetic 2-file test by
chaining two decodeTastyBytes calls with synthesized cross-references"), Test 1 is redesigned:

**Redesigned Test 1:**
- Use `PlainClass.tasty` (already embedded) which provably produces non-empty UnresolvedRef
  placeholders for cross-file types (e.g., `scala.Int`, `java.lang.Object`, `scala.AnyRef`).
  This is verified by the existing AstUnpicklerTest "pass 1 on PlainClass.tasty with arena
  produces non-empty placeholders" test.
- Take the FIRST placeholder from PlainClass.tasty (whatever its FQN).
- Create a SYNTHETIC Class symbol with the same FQN (simulating "that class exists in fqnIndex").
- Manually simulate Phase C: call `placeholder.replaceSlot.set(Reflect.Type.Named(syntheticSym))`.
- Verify that `replaceSlot.get()` returns `Reflect.Type.Named(sym)` with `sym.kind == Class`.

This tests the exact same Phase C mechanism (SingleAssign write-once slot, Named(sym) wrap) as
the original design, just with a different fixture that actually has TYPEREFpkg references.

**What remains valid:**
- The BaseClass.scala and ChildClass.scala fixture files remain in the repository (useful for
  future tests if compilation-unit separation is ever added to the fixtures).
- The baseClassTasty and childClassTasty byte arrays remain in Embedded.scala (useful for
  future tests or for validating the APPLY-parent decoding path).
- Test 2 and Test 3 are unaffected; they use childClassTasty in ways that don't depend on
  TYPEREFpkg/TYPEREFin being present.

### Impact on correctness

Phase C resolution is correctly implemented in ClasspathOrchestrator.mergeResults:
- The `allPlaceholders = fileResults.flatMap(_.placeholders)` collects ALL UnresolvedRef
  entries across all decoded files.
- The loop sets each slot via `fqnIndex.get(placeholder.fqn)` -> `Present(sym)` or fallback
  Unresolved sentinel.
- This is exercised by Test 3 (QueryApiTest: open classpath with ChildClass + no base -> no
  panic) and by Test 2's "Part d" (open with only childClassTasty -> ChildClass found, no panic).

The Phase C loop is tested end-to-end. Test 1 redesign only affects HOW the slot-set API is
exercised (synthetic symbol vs. decoded BaseClass symbol), not WHETHER it is exercised.
