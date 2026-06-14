package kyo

import kyo.internal.TestClasspaths

/** Tests for paramListIds population and companion lookup symmetry for opaque types.
  *
  * Fixture: kyo.fixtures.Meters (opaque type alias for Double with companion object and a single
  * extension method `def value: Double`), loaded cross-platform via TestClasspaths.withClasspath.
  */
class ParamListIdsPopulationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "Meters opaque type is found as Symbol.OpaqueType" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(symbol: Tasty.Symbol.OpaqueType) =>
                    assert(
                        symbol.name.asString == "Meters",
                        s"Expected name 'Meters', got '${symbol.name.asString}'"
                    )
                    succeed
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    val keys = classpath.indices.byFullName.toMap.keys.filter(_.contains("Meters")).toSeq.sorted.take(5)
                    fail(s"classpath.findSymbol('kyo.fixtures.Meters') returned Absent. fully-qualified name keys: ${keys.mkString(", ")}")
        }
    }

    "Meters OpaqueType companion is symmetric with its Object" in {
        import Tasty.Name.asString
        // Uses classpath.companion directly (no binding needed) since TestClasspaths.withClasspath
        // provides classpath as the classpath instance outside the bindingLocal scope.
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    classpath.companion(opaqueMeters) match
                        case Maybe.Present(companionObj: Tasty.Symbol.Object) =>
                            // Name is "Meters" from TASTy or "Meters$" from the classfile-derived symbol;
                            // both represent the same companion object.
                            assert(
                                companionObj.name.asString == "Meters" || companionObj.name.asString == "Meters$",
                                s"Companion object name must be 'Meters' or 'Meters$$', got '${companionObj.name.asString}'"
                            )
                            val reverseCompanion = classpath.companion(companionObj)
                            assert(
                                reverseCompanion.isDefined,
                                s"Reverse companion classpath.companion(companionObj) must be Present; got Absent"
                            )
                            succeed
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("classpath.companion(opaqueMeters) returned Absent")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found; check fixture setup")
        }
    }

    "Meters value extension method is present in classpath.symbols (isExtension sanity baseline)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            // classpath.symbols includes all decoded symbols; extension methods are present regardless
            // of whether the companion index is populated.
            val valueExtensions = classpath.symbols.filter { symbol =>
                symbol match
                    case m: Tasty.Symbol.Method => m.name.asString == "value" && m.isExtension
                    case _                      => false
            }
            assert(
                valueExtensions.nonEmpty,
                "No extension method named 'value' found in classpath.symbols. " +
                    "kyo.fixtures.Meters declares 'extension (m: Meters) def value: Double' which must appear."
            )
            succeed
        }
    }

    "Meters.value extension method has non-empty paramListIds" in {
        import Tasty.Name.asString
        // Uses classpath.companion directly (pure, no binding required).
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    classpath.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            // Find the value extension from the companion's declaration ids
                            // to avoid false matches from other "value" extensions in the classpath.
                            val metersValueExtensions = companion.declarationIds.flatMap { id =>
                                classpath.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            }
                            metersValueExtensions.headOption match
                                case Some(method) =>
                                    assert(
                                        method.paramListIds.size == 1,
                                        s"Expected paramListIds.size == 1 but got ${method.paramListIds.size}"
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
                            fail("classpath.companion(opaqueMeters) returned Absent; G-2 not fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found")
        }
    }

    "Meters.value receiver paramListIds.head.head resolves to Meters OpaqueType" in {
        import Tasty.Name.asString
        // Uses classpath.companion and direct symbol lookup (no binding required).
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    classpath.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            val metersValueExtensions = companion.declarationIds.flatMap { id =>
                                classpath.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            }
                            metersValueExtensions.headOption match
                                case Some(method) =>
                                    assert(method.paramListIds.nonEmpty, "paramListIds is empty")
                                    assert(method.paramListIds.head.nonEmpty, "paramListIds.head is empty; no receiver param recorded")
                                    val receiverId = method.paramListIds.head.head
                                    classpath.symbol(receiverId) match
                                        case Maybe.Present(param: Tasty.Symbol.Parameter) =>
                                            param.declaredType match
                                                case Maybe.Present(Tasty.Type.Named(symId)) =>
                                                    val maybeResolved = classpath.symbol(symId)
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
                                            fail(s"classpath.symbol($receiverId) returned Absent")
                                    end match
                                case None =>
                                    fail("value extension method not found in Meters companion declarations")
                            end match
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("classpath.companion(opaqueMeters) returned Absent")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found")
        }
    }

end ParamListIdsPopulationTest
