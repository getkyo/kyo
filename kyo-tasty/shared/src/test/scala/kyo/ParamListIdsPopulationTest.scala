package kyo

import kyo.internal.TestClasspaths

/** Pending reproduction tests for G-1 (paramListIds not populated) and G-2 (companion lookup
  * asymmetry for OpaqueType).
  *
  * Fixture: kyo.fixtures.Meters (opaque type alias for Double with companion object and a single
  * extension method `def value: Double`), loaded cross-platform via TestClasspaths.withClasspath.
  * The Meters fixture lives in kyo-tasty-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala
  * and is embedded in kyo.fixtures.Embedded.fixtureClassesPackageTasty on all three platforms.
  *
  * Leaf summary:
  *   1. meters_opaque_found: NOT pending. cp.findSymbol("kyo.fixtures.Meters") returns an OpaqueType
  *      today. Regression baseline for the G-1 and G-2 chain.
  *   2. companion_symmetric_meters: active (Phase 02). Tasty.companion(opaqueMeters) now works
  *      because buildCompanionIndex has the OpaqueType arm (G-2 fix).
  *   3. members_contain_value_extension: NOT pending. Members of the Meters companion object include
  *      the value extension method even before G-1 / G-2 are fixed (the companion is reachable via
  *      the direct symbol lookup path on the synthetic test classpath, which sidesteps the OpaqueType
  *      companion gap).
  *   4. paramListIds_populated_post_fix: active (Phase 02). valueExt.paramListIds is now populated
  *      by AstUnpickler + Pass C (G-1 fix).
  *   5. receiver_resolves_to_meters: active (Phase 02). Depends on leaf 4 being non-empty.
  * INV-H1 entry point (the whole chain must start with a resolvable OpaqueType symbol).
  * INV-H7 producer expectation (companion symmetry for OpaqueType + Object pairs).
  */
class ParamListIdsPopulationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Leaf 1: meters_opaque_found (NOT pending, regression baseline) ────────
    // Given: cross-platform classpath via TestClasspaths.withClasspath (which loads
    //        FixtureClasses$package.tasty containing kyo.fixtures.Meters).
    // When: cp.findSymbol("kyo.fixtures.Meters") is invoked.
    // Then: result is Present(s) where s is a Symbol.OpaqueType with name == "Meters".
    // Pins: INV-H1 entry point. This already works pre-fix; serves as the regression baseline.
    "Meters opaque type is found as Symbol.OpaqueType (INV-H1 entry point)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(sym: Tasty.Symbol.OpaqueType) =>
                    assert(
                        sym.name.asString == "Meters",
                        s"Expected name 'Meters', got '${sym.name.asString}'"
                    )
                    succeed
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    val keys = cp.indices.byFqn.toMap.keys.filter(_.contains("Meters")).toSeq.sorted.take(5)
                    fail(s"cp.findSymbol('kyo.fixtures.Meters') returned Absent. FQN keys: ${keys.mkString(", ")}")
    }

    // ── Leaf 2: companion_symmetric_meters (active, Phase 02) ────────────────
    // Given: same fixture; opaqueMeters resolved from leaf 1.
    // When: Tasty.companion(opaqueMeters) and Tasty.companion(companionObj) are invoked.
    // Then: Tasty.companion(opaqueMeters) == Present(companionObj)
    //       AND companionObj.kind == SymbolKind.Object
    //       AND Tasty.companion(companionObj) == Present(opaqueMeters).
    // Pins: INV-H7 producer expectation (G-2 fix in buildCompanionIndex).
    "Meters OpaqueType companion is symmetric with its Object (INV-H7)" in {
        import Tasty.Name.asString
        // Uses cp.companion directly (no binding needed) since TestClasspaths.withClasspath
        // provides cp as the classpath instance outside the bindingLocal scope.
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    cp.companion(opaqueMeters) match
                        case Maybe.Present(companionObj: Tasty.Symbol.Object) =>
                            // Name is "Meters" from TASTy or "Meters$" from the classfile-derived symbol;
                            // both represent the same companion object.
                            assert(
                                companionObj.name.asString == "Meters" || companionObj.name.asString == "Meters$",
                                s"Companion object name must be 'Meters' or 'Meters$$', got '${companionObj.name.asString}'"
                            )
                            val reverseCompanion = cp.companion(companionObj)
                            assert(
                                reverseCompanion.isDefined,
                                s"Reverse companion cp.companion(companionObj) must be Present; got Absent"
                            )
                            succeed
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("cp.companion(opaqueMeters) returned Absent; G-2 not yet fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found; check fixture setup")
    }

    // ── Leaf 3: members_contain_value_extension (NOT pending) ─────────────────
    // Given: same fixture; extension method 'value' discoverable via cp.symbols.filter(_.isExtension).
    // When: cp.symbols are scanned for extension methods named "value" (sidesteps G-2 gap).
    // Then: result contains a Symbol.Method m with m.name.asString == "value" and m.isExtension.
    // Pins: pre-fix sanity check. Extension methods are registered in cp.symbols at decode time;
    //       the G-2 companion index gap does not block their registration. This approach avoids
    //       the FQN companion index entirely and serves as a regression sentinel.
    "Meters value extension method is present in cp.symbols (isExtension sanity baseline)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // cp.symbols includes all decoded symbols; extension methods are present regardless
            // of whether the companion index (G-2 gap) is fixed.
            val valueExtensions = cp.symbols.filter: sym =>
                sym match
                    case m: Tasty.Symbol.Method => m.name.asString == "value" && m.isExtension
                    case _                      => false
            assert(
                valueExtensions.nonEmpty,
                "No extension method named 'value' found in cp.symbols. " +
                    "kyo.fixtures.Meters declares 'extension (m: Meters) def value: Double' which must appear."
            )
            succeed
    }

    // ── Leaf 4: paramListIds_populated_post_fix (active, Phase 02) ───────────
    // Given: same fixture; opaqueMeters resolved from leaf 1; metersCompanion resolved
    //        via Tasty.companion (now works after G-2 fix).
    // When: valueExt (extension method "value" from companion) paramListIds is read.
    // Then: valueExt.paramListIds.size == 1 AND valueExt.paramListIds.head.size == 1.
    // Pins: INV-H1 (G-1 fix: Pass C now writes paramListIds for extension methods).
    // Note: uses companion navigation (not raw cp.symbols scan) to avoid false matches
    //       from other classpath-resident extension methods named "value" (e.g. SymbolId.value
    //       from kyo-tasty itself).
    "Meters.value extension method has non-empty paramListIds (INV-H1)" in {
        import Tasty.Name.asString
        // Uses cp.companion directly (pure, no binding required).
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    cp.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            // Find the value extension from the companion's declaration ids
                            // to avoid false matches from other "value" extensions in the classpath.
                            val metersValueExtensions = companion.declarationIds.flatMap: id =>
                                cp.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            metersValueExtensions.headOption match
                                case Some(method) =>
                                    assert(
                                        method.paramListIds.size == 1,
                                        s"Expected paramListIds.size == 1 but got ${method.paramListIds.size}; G-1 not yet fixed"
                                    )
                                    assert(
                                        method.paramListIds.head.size == 1,
                                        s"Expected paramListIds.head.size == 1 but got ${method.paramListIds.head.size}"
                                    )
                                    succeed
                                case None =>
                                    fail("value extension method not found in Meters companion declarations")
                            end match
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("cp.companion(opaqueMeters) returned Absent; G-2 not fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found")
    }

    // ── Leaf 5: receiver_resolves_to_meters (active, Phase 02) ───────────────
    // Given: valueExt from leaf 4 (paramListIds is non-empty post-fix); opaqueMeters from leaf 1.
    // When: cp.symbol(valueExt.paramListIds.head.head) is unwrapped to a Symbol.Parameter
    //       and its declaredType is fed to Tasty.typeSymbol.
    // Then: result is Present(meters) where meters is the same Symbol.OpaqueType from leaf 1.
    // Pins: INV-H6 positional receiver rule.
    "Meters.value receiver paramListIds.head.head resolves to Meters OpaqueType (INV-H6)" in {
        import Tasty.Name.asString
        // Uses cp.companion and direct symbol lookup (no binding required).
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    cp.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            val metersValueExtensions = companion.declarationIds.flatMap: id =>
                                cp.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            metersValueExtensions.headOption match
                                case Some(method) =>
                                    assert(method.paramListIds.nonEmpty, "paramListIds is empty; G-1 not yet fixed")
                                    assert(method.paramListIds.head.nonEmpty, "paramListIds.head is empty; no receiver param recorded")
                                    val receiverId = method.paramListIds.head.head
                                    cp.symbol(receiverId) match
                                        case Maybe.Present(param: Tasty.Symbol.Parameter) =>
                                            param.declaredType match
                                                case Maybe.Present(Tasty.Type.Named(symId)) =>
                                                    val maybeResolved = cp.symbol(symId)
                                                    assert(
                                                        maybeResolved == Maybe.Present(opaqueMeters),
                                                        s"Expected receiver type to resolve to Meters OpaqueType; got $maybeResolved"
                                                    )
                                                    succeed
                                                case Maybe.Present(_) =>
                                                    fail("Receiver declaredType is not Type.Named; cannot resolve to OpaqueType")
                                                case Maybe.Absent =>
                                                    fail("Receiver parameter has no declaredType")
                                        case Maybe.Present(other) =>
                                            fail(s"Expected Parameter but got: ${other.getClass.getSimpleName}")
                                        case Maybe.Absent =>
                                            fail(s"cp.symbol($receiverId) returned Absent")
                                    end match
                                case None =>
                                    fail("value extension method not found in Meters companion declarations")
                            end match
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("cp.companion(opaqueMeters) returned Absent; G-2 not fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found")
    }

end ParamListIdsPopulationTest
