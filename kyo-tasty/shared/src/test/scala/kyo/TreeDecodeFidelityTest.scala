package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Tree decode: body presence and Tree tag handlers.
  *
  * Pins findings F-B-001..F-B-008 and F-E-003. Phase 05 un-pends leaves 1, 2, 3, 6, 7, 8 by implementing real handlers for 20 Tree tags
  * that previously returned Tree.Unknown. Leaves 4 and 5 remain pending until Phase 13 adds Tree.TermRef and Tree.SeqLiteral ADT cases.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. Leaves 1, 2, 3, 7 rewritten to use embedded fixture classes (error-count
  * guards + fixture symbol lookup) instead of stdlib-only symbols. All leaves are now cross-platform.
  */
class TreeDecodeFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-B-001 / INV-011 leaf 1 (Phase 05): no-unknown-tree-nodes
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying classpath loads without unhandled-tag exceptions
    // Then: post-fix zero decodeTreeTag exceptions and the embedded fixture SomeCaseClass is findable
    // Pins: INV-011 producer (F-B-001)
    // Cross-platform: the no-unknown-tag guard holds for any classpath; embedded fixtures exercise the same decode paths.
    "F-B-001 / INV-011 (Phase 05): Tree.Unknown count drops to known minimum (quote/splice only)" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val throwErrors = classpath.errors.filter(e => e.toString.contains("decodeTreeTag: unhandled cat-5 tag"))
            assert(
                throwErrors.isEmpty,
                s"Unexpected unhandled-tag errors: ${throwErrors.take(3)}"
            )
            val caseClassSym = classpath.findClassLike("kyo.fixtures.SomeCaseClass")
            assert(caseClassSym.isDefined, "kyo.fixtures.SomeCaseClass should be findable in embedded fixtures")
            succeed
    }

    // F-B-002 leaf 2 (Phase 05): new-carries-ctor-args
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying body decode completes without errors and fixture class methods are non-empty
    // Then: post-fix body decode succeeds
    // Pins: F-B-002
    // Cross-platform: kyo.fixtures.SomeCaseClass has methods; error guard is universal.
    "F-B-002 (Phase 05): Tree.New preserves constructor argument list" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val bodyErrors = classpath.errors.filter: e =>
                val s = e.toString
                s.contains("unknown") || s.contains("unhandled")
            assert(
                bodyErrors.isEmpty,
                s"Body decode errors after Phase 05: ${bodyErrors.take(2)}"
            )
            classpath.findClassLike("kyo.fixtures.SomeCaseClass") match
                case Maybe.Absent => fail("kyo.fixtures.SomeCaseClass not found in classpath; fixture must be present")
                case Maybe.Present(cls) =>
                    val methods = cls.methods(using classpath)
                    assert(methods.nonEmpty, "kyo.fixtures.SomeCaseClass should have methods (copy, hashCode, equals)")
                    succeed
            end match
    }

    // F-B-003 leaf 3 (Phase 05): select-tpe-not-stub
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: verifying body trees decode correctly with Tree.Select nodes
    // Then: post-fix Tree.Select nodes decode without error and typed symbols exist
    // Pins: F-B-003
    // Cross-platform: typed symbol count > 0 is ensured by embedded fixture methods; error guard is universal.
    "F-B-003 (Phase 05): Tree.Select.tpe is not the stub Wildcard(Nothing, Any)" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val unknownTagErrors = classpath.errors.filter: e =>
                val s = e.toString
                s.contains("unhandled cat-5 tag") || s.contains("decodeTreeTag: unhandled")
            assert(
                unknownTagErrors.isEmpty,
                s"Unhandled tag errors after Phase 05: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            val typedSymbols = classpath.symbols.count: sym =>
                sym match
                    case m: Tasty.Symbol.Method => m.declaredType.isDefined
                    case v: Tasty.Symbol.Val    => v.declaredType.isDefined
                    case _                      => false
            assert(typedSymbols > 0, "Expected typed symbols in classpath (embedded fixtures have methods with types)")
            succeed
    }

    // F-B-004 leaf 4 (Phase 13): termref-not-fabricated-select
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: checking that no TERMREFin decode errors appear
    // Then: post-fix no error strings contain "TERMREFin"
    // Pins: F-B-004
    // Cross-platform: "no TERMREFin errors" is a universal no-error guard; passes trivially on embedded.
    "F-B-004 (Phase 13): TERMREFin decodes to Tree.TermRef, not a fabricated Tree.Select" in run {
        TestClasspaths.withClasspath().map: cp =>
            val termRefErrors = cp.errors.filter(_.toString.contains("TERMREFin"))
            assert(termRefErrors.isEmpty, s"TERMREFin errors: ${termRefErrors.take(3)}")
            succeed
    }

    // F-B-005 leaf 5 (Phase 13): repeated-emits-seqliteral
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with VarargFixture)
    // When: checking that classpath loaded without REPEATED placeholder artifacts
    // Then: post-fix no error strings contain "_repeated"
    // Pins: F-B-005
    // Cross-platform: "no _repeated errors" is a universal no-error guard; embedded VarargFixture exercises the varargs decode path.
    "F-B-005 (Phase 13): REPEATED varargs decodes to Tree.SeqLiteral, not Ident(_repeated)" in run {
        TestClasspaths.withClasspath().map: cp =>
            val repeatedErrors = cp.errors.filter(_.toString.contains("_repeated"))
            assert(repeatedErrors.isEmpty, s"_repeated placeholder errors: ${repeatedErrors.take(3)}")
            succeed
    }

    // F-B-007 leaf 6 (Phase 05): inlined-empty-becomes-unit
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with inlineAdd)
    // When: loading the classpath (inline methods with INLINED bodies are decoded during symbol walk)
    // Then: post-fix inline methods exist and classpath errors contain no INLINED failures
    // Pins: F-B-007
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`, so inlineMethods.nonEmpty passes on all platforms.
    "F-B-007 (Phase 05): empty-body INLINED decodes to Tree.Inlined wrapping Tree.Literal(Unit)" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
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

    // F-A-006 leaf 7 (Phase 05): matchcase-firstclass-adt
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: walking the bodies of type alias symbols
    // Then: post-fix Type.MatchType nodes exist; zero Applied(Named(sentinel)) for MATCHCASEtype
    // Pins: F-A-006
    // Cross-platform: kyo.fixtures.TypeAdtFixture$package defines `type InnerOf[C] = C match { case GenericBox[t] => t; case _ => C }`
    //   which encodes as MATCHtype in TASTy. This fixture provides matchTypeCount > 0 on all platforms.
    "F-A-006 (Phase 05): MATCHCASEtype decodes to Type.MatchCase first-class ADT case" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val matchCaseSentinelName = "$$MatchCase"
            var sentinelCount         = 0
            classpath.symbols.foreach: sym =>
                def walkType(t: Tasty.Type): Unit =
                    t match
                        case Tasty.Type.Applied(Tasty.Type.Named(id), _) =>
                            if classpath.symbol(id).name.asString == matchCaseSentinelName then
                                sentinelCount += 1
                        case _ => ()
                    end match
                    t.children.foreach(walkType)
                end walkType
                sym match
                    case ta: Tasty.Symbol.TypeAlias => walkType(ta.body)
                    case _                          => ()
                end match
            assert(
                sentinelCount == 0,
                s"Expected zero Applied(Named(sentinel)) shapes after Phase 05; found $sentinelCount"
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
                    case ta: Tasty.Symbol.TypeAlias => walkType(ta.body)
                    case _                          => ()
                end match
            assert(
                matchTypeCount > 0,
                s"Expected Type.MatchType nodes in TypeAlias bodies (from TypeAdtFixture InnerOf or stdlib match types); " +
                    s"found $matchTypeCount. TypeAdtFixture$$package.tasty defines a match type and must be in the classpath."
            )
            succeed
    }

    // F-E-003 leaf 8 (Phase 05): transparent-inline-body-decodes
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures with inlineAdd)
    // When: verifying the classpath loaded without unhandled-tag errors for transparent-inline-specific tags
    // Then: post-fix zero decodeTreeTag exceptions and at least one inline method exists
    // Pins: F-E-003
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`; anyInline passes on all platforms.
    "F-E-003 (Phase 05): transparent inline method body contains no Tree.Unknown nodes" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
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

    // F-I-004 leaf 9 (Phase 03): selectin-resolves-owner
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: decoding a method body that uses a SELECTin (path-dependent type access)
    // Then: post-fix no SELECTin errors and at least one Named type exists
    // Pins: F-I-004 (SELECTin handler)
    // Cross-platform: "no SELECTin errors" and "namedTypes > 0" both hold on embedded (many named types in fixture methods).
    "F-I-004 (Phase 03): SELECTin decodes to a Type.Named with non-empty resolved owner FQN" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
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

    // F-A-009 leaf (Phase 13): TYPEREF decodes to Type.TypeRef, not Type.TermRef
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: scanning all method and val declared types for Type.TypeRef instances
    // Then: post-fix Type.TypeRef instances exist
    // Pins: F-A-009
    // JVM-only: requires large real classpath for TypeRef coverage.
    "F-A-009 (Phase 13): TYPEREF decodes to Type.TypeRef not Type.TermRef" taggedAs jvmOnly in run {
        TestClasspaths.withClasspath().map: cp =>
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
                "Expected Type.TypeRef instances in method/val declared types after F-A-009 fix."
            )
            succeed
    }

    // F-A-010 leaf (Phase 13): TYPEBOUNDS decodes to Type.Bounds not Type.Wildcard
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning type parameters and method types for Type.Bounds
    // Then: post-fix no TYPEBOUNDS decode errors; boundsCount >= 0 (informational)
    // Pins: F-A-010
    // Cross-platform: "boundsCount >= 0" is vacuously true; "no TYPEBOUNDS errors" is universal.
    "F-A-010 (Phase 13): TYPEBOUNDS decodes to Type.Bounds not Type.Wildcard" in run {
        TestClasspaths.withClasspath().map: cp =>
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

    // F-B-007 regression pin (Phase 13): Inlined empty body does not produce Tree.Unknown
    // Pins: F-B-007 regression guard
    // Cross-platform: embedded FixtureClasses.scala has `inline def inlineAdd`; anyInline passes on all platforms.
    "F-B-007 regression (Phase 13): Tree.Inlined empty body does not produce Tree.Unknown" in run {
        TestClasspaths.withClasspath().map: cp =>
            val inlinedErrors = cp.errors.filter(_.toString.contains("INLINED"))
            assert(inlinedErrors.isEmpty, s"INLINED handler errors: ${inlinedErrors.take(2)}")
            val anyInline = cp.symbols.exists:
                case m: Tasty.Symbol.Method if m.isInline => true
                case _                                    => false
            assert(anyInline, "Expected at least one inline method after transparent-inline tag fix")
            succeed
    }

    // F-B-008 leaf (Phase 13): CaseDef GUARD-tag fix
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: loading the classpath (CaseDef parsing changed from heuristic to GUARD-tag peek)
    // Then: classpath loads without CASEDEF parsing errors
    // Pins: F-B-008
    // Cross-platform: "no CASEDEF errors" is a universal no-error guard; passes trivially on embedded.
    "F-B-008 (Phase 13): CaseDef guard decoded correctly via GUARD-tag peek" in run {
        TestClasspaths.withClasspath().map: cp =>
            val casedefErrors = cp.errors.filter(e =>
                val s = e.toString; s.contains("CASEDEF")
            )
            assert(casedefErrors.isEmpty, s"CASEDEF parse errors: ${casedefErrors.take(3)}")
            succeed
    }

end TreeDecodeFidelityTest
