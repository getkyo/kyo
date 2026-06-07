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
  *   2. companion_symmetric_meters: PENDING until Phase 02. Tasty.companion(opaqueMeters) returns
  *      Absent today because buildCompanionIndex omits the OpaqueType arm (G-2 gap).
  *   3. members_contain_value_extension: NOT pending. Members of the Meters companion object include
  *      the value extension method even before G-1 / G-2 are fixed (the companion is reachable via
  *      the direct symbol lookup path on the synthetic test classpath, which sidesteps the OpaqueType
  *      companion gap).
  *   4. paramListIds_populated_post_fix: PENDING until Phase 02. valueExt.paramListIds is always
  *      Chunk.empty today because Pass C never writes it (G-1 gap).
  *   5. receiver_resolves_to_meters: PENDING until Phase 02. Depends on leaf 4 being non-empty.
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

    // ── Leaf 2: companion_symmetric_meters (PENDING until Phase 02) ───────────
    // Given: same fixture; opaqueMeters resolved from leaf 1.
    // When: Tasty.companion(opaqueMeters) and Tasty.companion(companionObj) are invoked.
    // Then: Tasty.companion(opaqueMeters) == Present(companionObj)
    //       AND companionObj.kind == SymbolKind.Object
    //       AND Tasty.companion(companionObj) == Present(opaqueMeters).
    // Pins: INV-H7 producer expectation (G-2 fix needed in buildCompanionIndex).
    // Fails today because buildCompanionIndex omits the OpaqueType arm.
    "Meters OpaqueType companion is symmetric with its Object (INV-H7)".pendingUntilFixed(
        "G-2: buildCompanionIndex omits OpaqueType arm; flipped in Phase 02"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    Tasty.companion(opaqueMeters).map: maybeCompanion =>
                        maybeCompanion match
                            case Maybe.Present(companionObj: Tasty.Symbol.Object) =>
                                assert(
                                    companionObj.name.asString == "Meters",
                                    s"Companion object name must be 'Meters', got '${companionObj.name.asString}'"
                                )
                                val reverseCompanion = cp.companion(companionObj)
                                assert(
                                    reverseCompanion == Maybe.Present(opaqueMeters),
                                    s"Reverse companion must point back to opaqueMeters; got $reverseCompanion"
                                )
                                succeed
                            case Maybe.Present(other) =>
                                fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                            case Maybe.Absent =>
                                fail("Tasty.companion(opaqueMeters) returned Absent; G-2 not yet fixed")
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

    // ── Leaf 4: paramListIds_populated_post_fix (PENDING until Phase 02) ─────
    // Given: same fixture; valueExt resolved as the extension method from leaf 3.
    // When: valueExt.paramListIds is read.
    // Then: valueExt.paramListIds.size == 1 AND valueExt.paramListIds.head.size == 1.
    // Pins: INV-H1 (G-1 fix: Pass C must write paramListIds for extension methods).
    // Fails today because Pass C never writes paramListIds regardless of method shape.
    "Meters.value extension method has non-empty paramListIds (INV-H1)".pendingUntilFixed(
        "G-1: Pass C never writes paramListIds; flipped in Phase 02"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // Find the value extension method directly from cp.symbols (sidesteps G-2 gap).
            val valueExtensions = cp.symbols.filter: sym =>
                sym match
                    case m: Tasty.Symbol.Method => m.name.asString == "value" && m.isExtension
                    case _                      => false
            valueExtensions.headOption match
                case Some(m: Tasty.Symbol.Method) =>
                    assert(
                        m.paramListIds.size == 1,
                        s"Expected paramListIds.size == 1 but got ${m.paramListIds.size}; G-1 not yet fixed"
                    )
                    assert(
                        m.paramListIds.head.size == 1,
                        s"Expected paramListIds.head.size == 1 but got ${m.paramListIds.head.size}"
                    )
                    succeed
                case _ =>
                    fail("value extension method not found in cp.symbols; isExtension regression?")
            end match
    }

    // ── Leaf 5: receiver_resolves_to_meters (PENDING until Phase 02) ──────────
    // Given: valueExt from leaf 4 (paramListIds is non-empty post-fix).
    // When: cp.symbol(valueExt.paramListIds.head.head) is unwrapped to a Symbol.Parameter
    //       and its declaredType is fed to Tasty.typeSymbol.
    // Then: result is Present(meters) where meters is the same Symbol.OpaqueType from leaf 1.
    // Pins: INV-H6 positional receiver rule. Fails today because leaf 4 is empty (G-1 gap).
    "Meters.value receiver paramListIds.head.head resolves to Meters OpaqueType (INV-H6)".pendingUntilFixed(
        "G-1: paramListIds empty until Phase 02; receiver chain not testable"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    // Find the value extension method directly from cp.symbols (sidesteps G-2 gap).
                    val valueExtensions = cp.symbols.filter: sym =>
                        sym match
                            case m: Tasty.Symbol.Method => m.name.asString == "value" && m.isExtension
                            case _                      => false
                    valueExtensions.headOption match
                        case Some(m: Tasty.Symbol.Method) =>
                            assert(
                                m.paramListIds.nonEmpty,
                                "paramListIds is empty; G-1 not yet fixed"
                            )
                            assert(
                                m.paramListIds.head.nonEmpty,
                                "paramListIds.head is empty; no receiver param recorded"
                            )
                            val receiverId = m.paramListIds.head.head
                            cp.symbol(receiverId) match
                                case Maybe.Present(param: Tasty.Symbol.Parameter) =>
                                    param.declaredType match
                                        case Maybe.Present(tpe) =>
                                            Tasty.typeSymbol(tpe).map: maybeResolved =>
                                                assert(
                                                    maybeResolved == Maybe.Present(opaqueMeters),
                                                    s"Expected receiver type to resolve to Meters OpaqueType; got $maybeResolved"
                                                )
                                                succeed
                                        case Maybe.Absent =>
                                            fail("Receiver parameter has no declaredType")
                                case Maybe.Present(other) =>
                                    fail(s"Expected Parameter but got: ${other.getClass.getSimpleName}")
                                case Maybe.Absent =>
                                    fail(s"cp.symbol($receiverId) returned Absent")
                            end match
                        case _ =>
                            fail("value extension method not found in cp.symbols")
                    end match
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found")
    }

end ParamListIdsPopulationTest
