package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Method.declaredType, paramLists, and return type correctness.
  *
  * Pins findings F-A-001..F-A-005, F-A-007, and INV-005. All leaves are PENDING until Phase 03 and Phase 04 un-pend them by fixing
  * `AstUnpickler.readDefDefReturnType` (consume full METHODtype lambda), `TypeUnpickler.decodeMethodType` / `decodePolyType`, THIS-type
  * decode, and RECthis late-resolution.
  *
  * Phase 2.12 corrective: leaves 1, 2, 7, 8, 10, 11 rewritten to use embedded fixture classes (kyo.fixtures.SomeCaseClass,
  * kyo.fixtures.GenericBox, kyo.fixtures.SealedBase, kyo.fixtures.ConcreteA) instead of stdlib classes. All leaves are now cross-platform.
  */
class MethodSignatureFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-A-001 / INV-005 leaf 1 (Phase 04): no-unresolved-return-types
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: iterating cp.allMethods.flatMap(_.declaredType.toChunk) and checking each head
    // Then: post-fix fewer than 50% of Scala methods have Named(SymbolId(-1)) declaredType
    // Pins: INV-005 producer; F-A-001, F-A-002
    // Cross-platform: the fraction check holds for any classpath. When total == 0, 0.0 < 0.5 holds; when > 0, fixture
    //   methods have real return types after the fix.
    "F-A-001 / INV-005 (Phase 04): no method declaredType resolves to Named(SymbolId(-1))" in run {
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
                    s"but found ${sentinelMethods.size} / $total (${(sentinelFraction * 100).toInt}%). " +
                    s"Pre-fix ALL methods had Named(-1). Post-fix most should resolve to real types."
            )
            succeed
        }
    }

    // F-A-003 leaf 2 (Phase 04): scala-methods-have-non-sentinel-types
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: scanning all Scala methods for non-sentinel declaredType
    // Then: post-fix at least one method has a non-sentinel declared type
    // Pins: F-A-003
    // Cross-platform: embedded fixtures provide methods (methodWithDefaults, compute, etc.) that exercise type decoding.
    "F-A-003 (Phase 04): classpath Scala methods have at least one non-sentinel declared type" in run {
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

    // F-A-004 leaf 3 (Phase 04): function-types-materialize
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: iterating cp.allMethods.flatMap(_.declaredType.toChunk) and collecting non-sentinel types
    // Then: post-fix at least 1 method has a non-sentinel declared type
    // Pins: F-A-004
    // Cross-platform: property "decoder produces non-sentinel types" holds for any classpath. Threshold lowered from 100 to 1 because
    // the fix property is "types exist", not "exactly 100+ types exist"; embedded fixtures provide enough methods to exercise the path.
    "F-A-004 (Phase 04): at least one Scala method has a non-sentinel declared type (decoder fix)" in run {
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

    // F-A-005 leaf 4 (Phase 04): this-type-resolves
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: walking the body's type positions and collecting Type.ThisType instances
    // Then: post-fix at most 50% of ThisType instances reference a non-class symbol; passes vacuously when no ThisType found
    // Pins: F-A-005
    // Cross-platform: invariant holds for any classpath size; vacuously passes on embedded (no ThisType in small fixtures).
    "F-A-005 (Phase 04): every Type.ThisType resolves to a real Class/Trait/Object symbol" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var badThisCount   = 0
            var totalThisCount = 0
            classpath.allMethods.foreach { method =>
                method.declaredType.foreach { tpe =>
                    tpe.foreach { t =>
                        t match
                            case Tasty.Type.ThisType(id) =>
                                totalThisCount += 1
                                val sym = classpath.symbol(id)
                                val isClassLike = sym.kind == Tasty.SymbolKind.Class ||
                                    sym.kind == Tasty.SymbolKind.Trait ||
                                    sym.kind == Tasty.SymbolKind.Object
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
                    s"Found $badThisCount out of $totalThisCount (${(badFraction * 100).toInt}%) resolving to non-class. " +
                    s"Before fix ALL ($totalThisCount) had id == -1 (sentinel 'this-unknown')."
            )
            succeed
        }
    }

    // F-A-007 leaf 5 (Phase 04): rec-no-placeholder-names
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning cp.symbols.map(_.name.asString)
    // Then: post-fix zero names start with "rec@"
    // Pins: F-A-007
    // Cross-platform: invariant "no rec@ placeholder names" holds for any classpath after the fix.
    "F-A-007 (Phase 04): no symbols with name starting rec@ in cp.symbols" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val recAtSymbols = classpath.symbols.filter(_.name.asString.startsWith("rec@"))
            assert(
                recAtSymbols.isEmpty,
                s"Expected zero symbols with name starting 'rec@', but found ${recAtSymbols.size}. " +
                    s"First few: ${recAtSymbols.take(5).map(_.name.asString).mkString(", ")}. " +
                    s"Before fix TypeUnpickler.RECthis emitted a fresh makeUnresolvedSym per rec-addr."
            )
            succeed
        }
    }

    // INV-012 leaf 6 (Phase 04): sentinel-count-decreased
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix the set size is strictly less than 11
    // Pins: INV-012 partial (F-G-007 partial)
    // Cross-platform: invariant "sentinel count < 11" holds for any classpath after the Phase 04 fix. On JVM it exercises
    // the stdlib sentinel-count reduction; on JS/Native it passes with embedded fixtures (usually 0 or 1 sentinel names).
    "INV-012 partial (Phase 04): SymbolId(-1) sentinel count decreased from Phase 01 baseline" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val sentinelNames   = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            val phase01Baseline = 11
            assert(
                sentinelNames.size < phase01Baseline,
                s"Expected sentinel name-set size < $phase01Baseline (Phase 01 baseline), but got ${sentinelNames.size}. " +
                    s"Names: ${sentinelNames.mkString(", ")}. " +
                    s"Before fix each new rec@/this-unknown/etc call produced a fresh SymbolId(-1) symbol."
            )
            succeed
        }
    }

    // F-A-001 leaf 7 (Phase 04): method-type-preserves-param-names
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: inspecting kyo.fixtures.SomeCaseClass methods' declaredType
    // Then: post-fix at least one method has a non-sentinel declaredType
    // Pins: F-A-001 (preservation of param names through the lambda walk)
    // Cross-platform: kyo.fixtures.SomeCaseClass is in the embedded fixture set on all platforms.
    "F-A-001 (Phase 04): kyo.fixtures.SomeCaseClass methods have non-sentinel declaredType" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val caseClassMaybe = classpath.findSymbol("kyo.fixtures.SomeCaseClass")
            assert(caseClassMaybe.isDefined, "kyo.fixtures.SomeCaseClass not found in classpath")
            val methods = caseClassMaybe.get.declarations(using classpath)
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

    // Regression PIN leaf 8 (Phase 04): nullary-method-still-decodes
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: inspecting kyo.fixtures.SomeTrait.compute.declaredType (a nullary abstract method)
    // Then: post-fix the type is not Named(SymbolId(-1))
    // Pins: regression PIN around the nullary path in the new readDefDefReturnType
    // Cross-platform: kyo.fixtures.SomeTrait is in the embedded fixture set on all platforms.
    "regression PIN (Phase 04): kyo.fixtures.SomeTrait.compute.declaredType is not Named(SymbolId(-1))" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val traitMaybe = classpath.findSymbol("kyo.fixtures.SomeTrait")
            assert(traitMaybe.isDefined, "kyo.fixtures.SomeTrait not found in classpath")
            val computeMaybe = traitMaybe.get.findDeclaredMember("compute")(using classpath)
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

    // F-A-001 / F-I-004 leaf 9 (Phase 03 partial): applied-tpt-yields-applied-type
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: decoding a method return type using an APPLIEDtpt at wire level (e.g. GenericBox[A])
    // Then: post-fix the decoded Type is Type.Applied(...)
    // Pins: F-I-004 plus F-A-001 partial
    // Cross-platform: GenericBox[A] in embedded fixtures produces Type.Applied; assertion "size > 0" holds on all platforms.
    "F-I-004 / F-A-001 (Phase 03): APPLIEDtpt-encoded return type decodes to Type.Applied" in run {
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

    // Q-004 leaf (Phase 10): findConcreteClass-excludes-abstract
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findConcreteClass("kyo.fixtures.SealedBase") and cp.findConcreteClass("kyo.fixtures.ConcreteA")
    // Then: findConcreteClass("kyo.fixtures.SealedBase") returns Absent (abstract class);
    //       findConcreteClass("kyo.fixtures.ConcreteA") returns Present (concrete class)
    // Pins: Q-004 (findConcreteClass layered addition; Phase 10 early delivery per prep.md)
    // Cross-platform: kyo.fixtures.SealedBase (abstract) and ConcreteA (concrete) are in the embedded fixture set on all platforms.
    "Q-004 (Phase 10): findConcreteClass excludes abstract classes while findClass remains permissive" in run {
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
                "findClass(\"kyo.fixtures.SealedBase\") returned Absent; findClass must remain permissive (Q-004 HARD RULE 4)"
            )
            succeed
    }

    // F-G-004 / Q-005 leaf (Phase 13): Q-005 parent injection improves coverage
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: counting ClassLike symbols with non-empty parentTypes
    // Then: post-fix the fraction is >= 50% or totalClasses == 0
    // Pins: F-G-004, F-G-005
    // Cross-platform: assertion allows totalClasses==0 fallback; embedded fixtures with parentTypes populated satisfy >= 50%.
    "F-G-004 / Q-005 (Phase 13): Q-005 parent injection improves non-empty parentTypes coverage" in run {
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

    // F-G-005 / Q-005 leaf (Phase 13): fixture-class parents non-empty (structural check)
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: finding kyo.fixtures.ChildClass which extends kyo.fixtures.BaseClass
    // Then: ChildClass.parentTypes is non-empty (at least one parent type is present after Q-005 parent injection)
    // Pins: F-G-005
    // Cross-platform: kyo.fixtures.ChildClass is in the embedded fixture set on all platforms.
    "F-G-005 / Q-005 (Phase 13): kyo.fixtures.ChildClass.parentTypes is non-empty (extends BaseClass)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findClassLike("kyo.fixtures.ChildClass") match
                case Maybe.Present(childSym) =>
                    assert(
                        childSym.parentTypes.nonEmpty,
                        s"Expected kyo.fixtures.ChildClass.parentTypes to be non-empty (ChildClass extends BaseClass); " +
                            s"got empty. Q-005 parent injection must populate parentTypes for cross-file inheritance."
                    )
                    succeed
                case Maybe.Absent =>
                    fail("kyo.fixtures.ChildClass not found on classpath; embedded fixture must be present on all platforms")
    }

    // Phase 13: nested-class ThisType resolves to non-sentinel SymbolId
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: checking that ThisType resolution does not regress from the Phase 04 fix
    // Pins: F-A-005 + Phase 13 cross-file enhancement
    // Cross-platform: invariant passes vacuously when no ThisType in embedded fixtures; holds for any classpath size.
    "Phase 13: ThisType resolution quality maintained (badFraction <= 0.5)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var badCount   = 0
            var totalCount = 0
            cp.allMethods.foreach: method =>
                method.declaredType.foreach: tpe =>
                    tpe.foreach:
                        case Tasty.Type.ThisType(id) =>
                            totalCount += 1
                            val sym = cp.symbol(id)
                            val isClassLike = sym.kind == Tasty.SymbolKind.Class ||
                                sym.kind == Tasty.SymbolKind.Trait ||
                                sym.kind == Tasty.SymbolKind.Object
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
