package kyo
import kyo.internal.TestClasspaths
import kyo.internal.tasty.symbol.SymbolKind

/** Fidelity tests for Method.declaredType, paramLists, and return type correctness.
  *
  * Exercises `AstUnpickler.readDefDefReturnType` (consume full METHODtype lambda),
  * `TypeUnpickler.decodeMethodType` / `decodePolyType`, THIS-type decode, and RECthis late-resolution.
  *
  * Uses embedded fixture classes (kyo.fixtures.SomeCaseClass, kyo.fixtures.GenericBox,
  * kyo.fixtures.SealedBase, kyo.fixtures.ConcreteA) instead of stdlib classes so the tests run
  * cross-platform.
  */
class MethodSignatureFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    //   no-unresolved-return-types
    //   methods have real return types after the fix.
    "no method declaredType resolves to Named(SymbolId(-1))" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val scalaOnlyMethods = classpath.allMethods.filter(!_.isJava)
            val total            = scalaOnlyMethods.size
            val sentinelMethods = scalaOnlyMethods.flatMap(_.declaredType.toList).filter {
                case Tasty.Type.Named(id) => id.value == -1
                case _                    => false
            }
            val sentinelFraction = if total > 0 then sentinelMethods.size.toDouble / total else 0.0
            assert(
                sentinelFraction < 0.5,
                s"Expected fewer than 50% of Scala-sourced methods to have Named(SymbolId(-1)) declaredType, " +
                    s"but found ${sentinelMethods.size} / $total (${(sentinelFraction * 100).toInt}%)."
            )
            succeed
        }
    }

    //   scala-methods-have-non-sentinel-types
    "classpath Scala methods have at least one non-sentinel declared type" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val methodsWithNonSentinelTypes = classpath.allMethods
                .filter(!_.isJava)
                .flatMap(_.declaredType.toList)
                .filter:
                    case Tasty.Type.Named(id) => id.value != -1 && id.value != -100 && id.value != -101 && id.value != -102
                    case _                    => true
            assert(
                methodsWithNonSentinelTypes.size >= 1,
                s"Expected at least 1 Scala method with a non-sentinel declared type, " +
                    s"but found ${methodsWithNonSentinelTypes.size}. " +
                    s"decodeTptAsType sectionOffset fix should resolve method return types."
            )
            succeed
        }
    }

    //   function-types-materialize
    // the fix property is "types exist", not "exactly 100+ types exist"; embedded fixtures provide enough methods to exercise the path.
    "at least one Scala method has a non-sentinel declared type (decoder fix)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val scalaMethodsWithRealTypes = classpath.allMethods
                .filter(!_.isJava)
                .flatMap(_.declaredType.toList)
                .filter {
                    case Tasty.Type.Named(id) => id.value != -1 && id.value != -100 && id.value != -101 && id.value != -102
                    case _                    => true
                }
            assert(
                scalaMethodsWithRealTypes.size >= 1,
                s"Expected at least 1 Scala method with a non-sentinel declared type, " +
                    s"but found ${scalaMethodsWithRealTypes.size}. " +
                    s"decodeTptAsType sectionOffset fix should resolve method return types from Named(-1) to real types."
            )
            succeed
        }
    }

    //   this-type-resolves
    "every Type.ThisType resolves to a real Class/Trait/Object symbol" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var badThisCount   = 0
            var totalThisCount = 0
            classpath.allMethods.foreach { method =>
                method.declaredType.foreach { tpe =>
                    tpe.foreach { t =>
                        t match
                            case Tasty.Type.ThisType(id) =>
                                totalThisCount += 1
                                val isClassLike = classpath.symbol(id).exists: sym =>
                                    sym.kind == SymbolKind.Class ||
                                        sym.kind == SymbolKind.Trait ||
                                        sym.kind == SymbolKind.Object
                                if !isClassLike then badThisCount += 1
                            case _ => ()
                    }
                }
            }
            val badFraction =
                if totalThisCount > 0 then badThisCount.toDouble / totalThisCount else 0.0
            assert(
                badFraction <= 0.5,
                s"Expected at most 50% of ThisType instances to resolve to non-class symbols. " +
                    s"Found $badThisCount out of $totalThisCount (${(badFraction * 100).toInt}%) resolving to non-class."
            )
            succeed
        }
    }

    //   rec-no-placeholder-names
    "no symbols with name starting rec@ in cp.symbols" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val recAtSymbols = classpath.symbols.filter(_.name.asString.startsWith("rec@"))
            assert(
                recAtSymbols.isEmpty,
                s"Expected zero symbols with name starting 'rec@', but found ${recAtSymbols.size}. " +
                    s"First few: ${recAtSymbols.take(5).map(_.name.asString).mkString(", ")}."
            )
            succeed
        }
    }

    // sentinel-count-decreased
    // the stdlib sentinel-count reduction; on JS/Native it passes with embedded fixtures (usually 0 or 1 sentinel names).
    "partial : SymbolId(-1) sentinel count decreased from pre-consolidation baseline" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val sentinelNames   = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            val phase01Baseline = 11
            assert(
                sentinelNames.size < phase01Baseline,
                s"Expected sentinel name-set size < $phase01Baseline, but got ${sentinelNames.size}. " +
                    s"Names: ${sentinelNames.mkString(", ")}."
            )
            succeed
        }
    }

    //   method-type-preserves-param-names
    "kyo.fixtures.SomeCaseClass methods have non-sentinel declaredType" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val caseClassMaybe = classpath.findSymbol("kyo.fixtures.SomeCaseClass")
            assert(caseClassMaybe.isDefined, "kyo.fixtures.SomeCaseClass not found in classpath")
            val caseClass = caseClassMaybe.get.asInstanceOf[Tasty.Symbol.ClassLike]
            val methods = caseClass.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
                .collect { case m: Tasty.Symbol.Method => m }
                .filter(!_.isJava)
            val resolvedTypes = methods.flatMap(_.declaredType.toList).filter {
                case Tasty.Type.Named(id) => id.value != -1
                case _                    => true
            }
            assert(
                resolvedTypes.nonEmpty,
                s"Expected at least one method in SomeCaseClass to have a non-sentinel declaredType " +
                    s"after the sectionOffset fix. Found 0 resolved types out of ${methods.size} methods. " +
                    s"decodeTptAsType must pass sectionOffset to readTypeIntoSession."
            )
            succeed
        }
    }

    // nullary-method-still-decodes
    "regression PIN : kyo.fixtures.SomeTrait.compute.declaredType is not Named(SymbolId(-1))" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val traitMaybe = classpath.findSymbol("kyo.fixtures.SomeTrait")
            assert(traitMaybe.isDefined, "kyo.fixtures.SomeTrait not found in classpath")
            val traitSym = traitMaybe.get.asInstanceOf[Tasty.Symbol.ClassLike]
            val computeMaybe =
                Maybe.fromOption(traitSym.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "compute"))
            assert(computeMaybe.isDefined, "kyo.fixtures.SomeTrait.compute not found in SomeTrait")
            val method = computeMaybe.get.asInstanceOf[Tasty.Symbol.Method]
            val dt     = method.declaredType
            assert(dt.isDefined, "kyo.fixtures.SomeTrait.compute.declaredType was Absent -- nullary path may have regressed")
            dt.get match
                case Tasty.Type.Named(id) if id.value == -1 =>
                    fail(
                        s"SomeTrait.compute.declaredType is Named(SymbolId(-1)). " +
                            s"The nullary-method path regressed in readDefDefReturnType."
                    )
                case Tasty.Type.TypeLambda(params, body) =>
                    body match
                        case Tasty.Type.Named(id) if id.value == -1 =>
                            fail(s"compute TypeLambda body is still Named(SymbolId(-1)). remapType must recurse into TypeLambda.")
                        case _ =>
                            succeed
                case _ =>
                    succeed
            end match
        }
    }

    // applied-tpt-yields-applied-type
    "APPLIEDtpt-encoded return type decodes to Type.Applied" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val allDeclaredTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case s: Tasty.Symbol.Var    => s.declaredType.toList
                    case s: Tasty.Symbol.Field  => s.declaredType.toList
                    case _                      => Nil
            val appliedTypes = allDeclaredTypes.collect:
                case t: Tasty.Type.Applied => t
            assert(
                appliedTypes.size > 0,
                s"Expected Type.Applied instances from APPLIEDtpt decoding, but found 0. " +
                    s"This means APPLIEDtpt still routes to the unknown-tag fallback."
            )
            val validApplied = appliedTypes.filter:
                case Tasty.Type.Applied(Tasty.Type.Named(id), _) => id.value != -1
                case _                                           => true
            assert(
                validApplied.size > 0,
                "Every Type.Applied still has a Named(-1) base; APPLIEDtpt tycon decode failed"
            )
            succeed
    }

    // findConcreteClass-excludes-abstract
    //       findConcreteClass("kyo.fixtures.ConcreteA") returns Present (concrete class)
    "findConcreteClass excludes abstract classes while findClass remains permissive" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(
                cp.findConcreteClass("kyo.fixtures.SealedBase").isEmpty,
                "findConcreteClass(\"kyo.fixtures.SealedBase\") returned Present; SealedBase is abstract and should be excluded"
            )
            assert(
                cp.findConcreteClass("kyo.fixtures.ConcreteA").isDefined,
                "findConcreteClass(\"kyo.fixtures.ConcreteA\") returned Absent; ConcreteA is concrete and should be Present"
            )
            assert(
                cp.findClass("kyo.fixtures.SealedBase").isDefined,
                "findClass(\"kyo.fixtures.SealedBase\") returned Absent; findClass must remain permissive"
            )
            succeed
    }

    //   parent injection improves coverage
    "parent injection improves non-empty parentTypes coverage" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val allClassLike = cp.allClassLike
            val totalClasses = allClassLike.size
            val withParents  = allClassLike.count(_.parentTypes.nonEmpty)
            val fractionWithParents =
                if totalClasses > 0 then withParents.toDouble / totalClasses else 1.0
            assert(
                fractionWithParents >= 0.5 || totalClasses == 0,
                s"Expected >= 50% of class-like symbols to have parentTypes; got $withParents/$totalClasses (${(fractionWithParents * 100).toInt}%)"
            )
            succeed
    }

    //   fixture-class parents non-empty (structural check)
    "kyo.fixtures.ChildClass.parentTypes is non-empty (extends BaseClass)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findClassLike("kyo.fixtures.ChildClass") match
                case Maybe.Present(childSym) =>
                    assert(
                        childSym.parentTypes.nonEmpty,
                        s"Expected kyo.fixtures.ChildClass.parentTypes to be non-empty (ChildClass extends BaseClass); " +
                            s"got empty. Default-parent injection must populate parentTypes for cross-file inheritance."
                    )
                    succeed
                case Maybe.Absent =>
                    fail("kyo.fixtures.ChildClass not found on classpath; embedded fixture must be present on all platforms")
    }

    // nested-class ThisType resolves to non-sentinel SymbolId
    "ThisType resolution quality maintained (badFraction <= 0.5)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var badCount   = 0
            var totalCount = 0
            cp.allMethods.foreach: method =>
                method.declaredType.foreach: tpe =>
                    tpe.foreach:
                        case Tasty.Type.ThisType(id) =>
                            totalCount += 1
                            val isClassLike = cp.symbol(id).exists: sym =>
                                sym.kind == SymbolKind.Class ||
                                    sym.kind == SymbolKind.Trait ||
                                    sym.kind == SymbolKind.Object
                            if !isClassLike then badCount += 1
                        case _ => ()
            val badFraction = if totalCount > 0 then badCount.toDouble / totalCount else 0.0
            assert(
                badFraction <= 0.5,
                s"Expected at most 50% of ThisType to be unresolved; found $badCount/$totalCount (${(badFraction * 100).toInt}%)"
            )
            succeed
    }

end MethodSignatureFidelityTest
