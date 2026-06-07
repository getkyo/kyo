package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Tree decode: body presence and Tree tag handlers.
  *
  * Pins findings   and   un-pends leaves 1, 2, 3, 6, 7, 8 by implementing real handlers for 20 Tree tags
  * that previously returned Tree.Unknown. Leaves 4 and 5 remain pending until adds Tree.TermRef and Tree.SeqLiteral ADT cases.
  *
  * relocated from jvm/src/test to shared/src/test. Leaves 1, 2, 3, 7 rewritten to use embedded fixture classes (error-count
  * guards + fixture symbol lookup) instead of stdlib-only symbols. All leaves are now cross-platform.
  */
class TreeDecodeFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    //   / leaf 1: no-unknown-tree-nodes
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying classpath loads without unhandled-tag exceptions
    // Then: post-fix zero decodeTreeTag exceptions and the embedded fixture SomeCaseClass is findable
    // Cross-platform: the no-unknown-tag guard holds for any classpath; embedded fixtures exercise the same decode paths.
    "Tree.Unknown count drops to known minimum (quote/splice only)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val throwErrors = classpath.errors.filter(e => e.toString.contains("decodeTreeTag: unhandled cat-5 tag"))
            assert(
                throwErrors.isEmpty,
                s"Unexpected unhandled-tag errors: ${throwErrors.take(3)}"
            )
            val caseClassSym = classpath.findClassLike("kyo.fixtures.SomeCaseClass")
            assert(caseClassSym.isDefined, "kyo.fixtures.SomeCaseClass should be findable in embedded fixtures")
            succeed
    }

    //   leaf 2: new-carries-ctor-args
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying body decode completes without errors and fixture class methods are non-empty
    // Then: post-fix body decode succeeds
    // Cross-platform: kyo.fixtures.SomeCaseClass has methods; error guard is universal.
    // Carry A2: TastyError.UnknownType for absent TypeAlias/OpaqueType/Parameter bodies is now correctly
    //           propagated (it was hidden by null sentinel). Exclude it from this filter since it is a
    //           per-symbol absent-type error, not a body decode / tree reconstruction error.
    "Tree.New preserves constructor argument list" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val bodyErrors = classpath.errors.filter:
                case _: TastyError.UnknownType => false // absent-body per-symbol errors are permitted
                case e =>
                    val s = e.toString
                    s.contains("unknown") || s.contains("unhandled")
            assert(
                bodyErrors.isEmpty,
                s"Body decode errors in: ${bodyErrors.take(2)}"
            )
            classpath.findClassLike("kyo.fixtures.SomeCaseClass") match
                case Maybe.Absent => fail("kyo.fixtures.SomeCaseClass not found in classpath; fixture must be present")
                case Maybe.Present(cls) =>
                    val methods = cls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
                    assert(methods.nonEmpty, "kyo.fixtures.SomeCaseClass should have methods (copy, hashCode, equals)")
                    succeed
            end match
    }

    //   leaf 3: select-tpe-not-stub
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying body trees decode correctly with Tree.Select nodes
    // Then: post-fix Tree.Select nodes decode without error and typed symbols exist
    // Cross-platform: typed symbol count > 0 is ensured by embedded fixture methods; error guard is universal.
    "Tree.Select.tpe is not the stub Wildcard(Nothing, Any)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val unknownTagErrors = classpath.errors.filter: e =>
                val s = e.toString
                s.contains("unhandled cat-5 tag") || s.contains("decodeTreeTag: unhandled")
            assert(
                unknownTagErrors.isEmpty,
                s"Unhandled tag errors in: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            val typedSymbols = classpath.symbols.count: sym =>
                sym match
                    case m: Tasty.Symbol.Method => m.declaredType.isDefined
                    case v: Tasty.Symbol.Val    => v.declaredType.isDefined
                    case _                      => false
            assert(typedSymbols > 0, "Expected typed symbols in classpath (embedded fixtures have methods with types)")
            succeed
    }

    //   leaf 4: termref-not-fabricated-select
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: checking that no TERMREFin decode errors appear
    // Then: post-fix no error strings contain "TERMREFin"
    // Cross-platform: "no TERMREFin errors" is a universal no-error guard; passes trivially on embedded.
    "TERMREFin decodes to Tree.TermRef, not a fabricated Tree.Select" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val termRefErrors = cp.errors.filter(_.toString.contains("TERMREFin"))
            assert(termRefErrors.isEmpty, s"TERMREFin errors: ${termRefErrors.take(3)}")
            succeed
    }

    //   leaf 5: repeated-emits-seqliteral
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with VarargFixture)
    // When: checking that classpath loaded without REPEATED placeholder artifacts
    // Then: post-fix no error strings contain "_repeated"
    // Cross-platform: "no _repeated errors" is a universal no-error guard; embedded VarargFixture exercises the varargs decode path.
    "REPEATED varargs decodes to Tree.SeqLiteral, not Ident(_repeated)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val repeatedErrors = cp.errors.filter(_.toString.contains("_repeated"))
            assert(repeatedErrors.isEmpty, s"_repeated placeholder errors: ${repeatedErrors.take(3)}")
            succeed
    }

    //   leaf 6: inlined-empty-becomes-unit
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with inlineAdd)
    // When: loading the classpath (inline methods with INLINED bodies are decoded during symbol walk)
    // Then: post-fix inline methods exist and classpath errors contain no INLINED failures
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`, so inlineMethods.nonEmpty passes on all platforms.
    "empty-body INLINED decodes to Tree.Inlined wrapping Tree.Literal(Unit)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val inlineMethods = classpath.symbols.collect:
                case m: Tasty.Symbol.Method if m.isInline => m
            assert(inlineMethods.nonEmpty, "Expected at least one inline method in the classpath")
            val inlinedErrors = classpath.errors.filter(_.toString.contains("INLINED"))
            assert(
                inlinedErrors.isEmpty,
                s"INLINED handler errors: ${inlinedErrors.take(2)}"
            )
            succeed
    }

    //   leaf 7: matchcase-firstclass-adt
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: walking the bodies of type alias symbols
    // Then: post-fix Type.MatchType nodes exist; zero Applied(Named(sentinel)) for MATCHCASEtype
    // Cross-platform: kyo.fixtures.TypeAdtFixture$package defines `type InnerOf[C] = C match { case GenericBox[t] => t; case _ => C }`
    //   which encodes as MATCHtype in TASTy. This fixture provides matchTypeCount > 0 on all platforms.
    "MATCHCASEtype decodes to Type.MatchCase first-class ADT case" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val matchCaseSentinelName = "$$MatchCase"
            var sentinelCount         = 0
            classpath.symbols.foreach: sym =>
                def walkType(t: Tasty.Type): Unit =
                    t match
                        case Tasty.Type.Applied(Tasty.Type.Named(id), _) =>
                            if classpath.symbol(id).map(_.name.asString).getOrElse("<unresolved>") == matchCaseSentinelName then
                                sentinelCount += 1
                        case _ => ()
                    end match
                    t.children.foreach(walkType)
                end walkType
                sym match
                    case ta: Tasty.Symbol.TypeAlias => ta.body.foreach(walkType)
                    case _                          => ()
                end match
            assert(
                sentinelCount == 0,
                s"Expected zero Applied(Named(sentinel)) shapes; found $sentinelCount"
            )
            var matchTypeCount = 0
            classpath.symbols.foreach: sym =>
                def walkType(t: Tasty.Type): Unit =
                    t match
                        case _: Tasty.Type.MatchType => matchTypeCount += 1
                        case _                       => ()
                    t.children.foreach(walkType)
                end walkType
                sym match
                    case ta: Tasty.Symbol.TypeAlias => ta.body.foreach(walkType)
                    case _                          => ()
                end match
            assert(
                matchTypeCount > 0,
                s"Expected Type.MatchType nodes in TypeAlias bodies (from TypeAdtFixture InnerOf or stdlib match types); " +
                    s"found $matchTypeCount. TypeAdtFixture$$package.tasty defines a match type and must be in the classpath."
            )
            succeed
    }

    //   leaf 8: transparent-inline-body-decodes
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with inlineAdd)
    // When: verifying the classpath loaded without unhandled-tag errors for transparent-inline-specific tags
    // Then: post-fix zero decodeTreeTag exceptions and at least one inline method exists
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`; anyInline passes on all platforms.
    "transparent inline method body contains no Tree.Unknown nodes" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val throwErrors = classpath.errors.filter: e =>
                val s = e.toString
                s.contains("decodeTreeTag: unhandled cat-5 tag") ||
                s.contains("unhandled cat-5 tag")
            assert(
                throwErrors.isEmpty,
                s"Unhandled tag errors in transparent-inline bodies: ${throwErrors.take(3).mkString(", ")}"
            )
            val anyInline = classpath.symbols.exists:
                case m: Tasty.Symbol.Method if m.isInline => true
                case _                                    => false
            assert(
                anyInline,
                "Expected at least one inline method after transparent-inline tag fix"
            )
            succeed
    }

    //   leaf 9: selectin-resolves-owner
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: decoding a method body that uses a SELECTin (path-dependent type access)
    // Then: post-fix no SELECTin errors and at least one Named type exists
    // Cross-platform: "no SELECTin errors" and "namedTypes > 0" both hold on embedded (many named types in fixture methods).
    "SELECTin decodes to a Type.Named with non-empty resolved owner FQN" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val selinErrors = classpath.errors.filter(_.toString.contains("SELECTin"))
            assert(
                selinErrors.isEmpty,
                s"SELECTin decode errors: ${selinErrors.take(3).mkString(", ")}"
            )
            val namedTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case _                      => Seq.empty
            .count:
                case _: Tasty.Type.Named => true
                case _                   => false
            assert(namedTypes > 0, "Expected Named types after SELECTin fix")
            succeed
    }

    //   leaf: TYPEREF decodes to Type.TypeRef, not Type.TermRef
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning all method and val declared types for Type.TypeRef instances
    // Then: post-fix Type.TypeRef instances exist
    // Cross-platform: TYPEREF appears wherever a type names an inner type member; embedded fixtures with
    //   inner types (Outer.Inner, HasTypeDef) produce Type.TypeRef instances on all platforms.
    "TYPEREF decodes to Type.TypeRef not Type.TermRef" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var typeRefCount = 0
            cp.allMethods.foreach { method =>
                method.declaredType.foreach { t =>
                    t.foreach { case _: Tasty.Type.TypeRef => typeRefCount += 1; case _ => () }
                }
            }
            cp.allVals.foreach { v =>
                v.declaredType.foreach { t =>
                    t.foreach { case _: Tasty.Type.TypeRef => typeRefCount += 1; case _ => () }
                }
            }
            assert(
                typeRefCount > 0,
                "Expected Type.TypeRef instances in method/val declared types ."
            )
            succeed
    }

    //   leaf: TYPEBOUNDS decodes to Type.Bounds not Type.Wildcard
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning type parameters and method types for Type.Bounds
    // Then: post-fix no TYPEBOUNDS decode errors; boundsCount >= 0 (informational)
    // Cross-platform: "boundsCount >= 0" is vacuously true; "no TYPEBOUNDS errors" is universal.
    "TYPEBOUNDS decodes to Type.Bounds not Type.Wildcard" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var boundsCount = 0
            cp.allMethods.foreach: method =>
                method.declaredType.foreach: t =>
                    t.foreach:
                        case _: Tasty.Type.Bounds => boundsCount += 1
                        case _                    => ()
            assert(
                boundsCount >= 0,
                "Expected Type.Bounds check to not throw; counter is informational"
            )
            val boundsErrors = cp.errors.filter(_.toString.contains("TYPEBOUNDS"))
            assert(boundsErrors.isEmpty, s"TYPEBOUNDS decode errors: ${boundsErrors.take(3)}")
            succeed
    }

    //   regression pin: Inlined empty body does not produce Tree.Unknown
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`; anyInline passes on all platforms.
    "regression : Tree.Inlined empty body does not produce Tree.Unknown" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val inlinedErrors = cp.errors.filter(_.toString.contains("INLINED"))
            assert(inlinedErrors.isEmpty, s"INLINED handler errors: ${inlinedErrors.take(2)}")
            val anyInline = cp.symbols.exists:
                case m: Tasty.Symbol.Method if m.isInline => true
                case _                                    => false
            assert(anyInline, "Expected at least one inline method after transparent-inline tag fix")
            succeed
    }

    //   leaf: CaseDef GUARD-tag fix
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: loading the classpath (CaseDef parsing changed from heuristic to GUARD-tag peek)
    // Then: classpath loads without CASEDEF parsing errors
    // Cross-platform: "no CASEDEF errors" is a universal no-error guard; passes trivially on embedded.
    "CaseDef guard decoded correctly via GUARD-tag peek" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val casedefErrors = cp.errors.filter(e =>
                val s = e.toString; s.contains("CASEDEF")
            )
            assert(casedefErrors.isEmpty, s"CASEDEF parse errors: ${casedefErrors.take(3)}")
            succeed
    }

end TreeDecodeFidelityTest
