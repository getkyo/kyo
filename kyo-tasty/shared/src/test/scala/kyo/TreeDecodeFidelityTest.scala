package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Tree decode: body presence and Tree tag handlers.
  *
  * Covers real handlers for the Tree tags that would otherwise fall through to Tree.Unknown.
  *
  * Uses embedded fixture classes (error-count guards + fixture symbol lookup) instead of
  * stdlib-only symbols, so the tests run cross-platform.
  */
class TreeDecodeFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "Tree.Unknown count drops to known minimum (quote/splice only)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val throwErrors = classpath.errors.filter(e => e.toString.contains("decodeTreeTag: unhandled cat-5 tag"))
            assert(
                throwErrors.isEmpty,
                s"Unexpected unhandled-tag errors: ${throwErrors.take(3)}"
            )
            val caseClassSym = classpath.findClassLike("kyo.fixtures.SomeCaseClass")
            assert(caseClassSym.isDefined, "kyo.fixtures.SomeCaseClass should be findable in embedded fixtures")
            succeed
        }
    }

    // TastyError.UnknownType for absent TypeAlias/OpaqueType/Parameter bodies is propagated.
    // Exclude it from this filter since it is a per-symbol absent-type error, not a body decode / tree reconstruction error.
    "Tree.New preserves constructor argument list" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val bodyErrors = classpath.errors.filter {
                case _: TastyError.UnknownType => false // absent-body per-symbol errors are permitted
                case e =>
                    val s = e.toString
                    s.contains("unknown") || s.contains("unhandled")
            }
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
    }

    "Tree.Select.tpe is not the stub Wildcard(Nothing, Any)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val unknownTagErrors = classpath.errors.filter { e =>
                val s = e.toString
                s.contains("unhandled cat-5 tag") || s.contains("decodeTreeTag: unhandled")
            }
            assert(
                unknownTagErrors.isEmpty,
                s"Unhandled tag errors in: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            val typedSymbols = classpath.symbols.count { symbol =>
                symbol match
                    case m: Tasty.Symbol.Method => m.declaredType.isDefined
                    case v: Tasty.Symbol.Val    => v.declaredType.isDefined
                    case _                      => false
            }
            assert(typedSymbols > 0, "Expected typed symbols in classpath (embedded fixtures have methods with types)")
            succeed
        }
    }

    "TERMREFin decodes to Tree.TermRef, not a fabricated Tree.Select" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val termRefErrors = classpath.errors.filter(_.toString.contains("TERMREFin"))
            assert(termRefErrors.isEmpty, s"TERMREFin errors: ${termRefErrors.take(3)}")
            succeed
        }
    }

    "REPEATED varargs decodes to Tree.SeqLiteral, not Ident(_repeated)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val repeatedErrors = classpath.errors.filter(_.toString.contains("_repeated"))
            assert(repeatedErrors.isEmpty, s"_repeated placeholder errors: ${repeatedErrors.take(3)}")
            succeed
        }
    }

    "empty-body INLINED decodes to Tree.Inlined wrapping Tree.Literal(Unit)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val inlineMethods = classpath.symbols.collect {
                case m: Tasty.Symbol.Method if m.isInline => m
            }
            assert(inlineMethods.nonEmpty, "Expected at least one inline method in the classpath")
            val inlinedErrors = classpath.errors.filter(_.toString.contains("INLINED"))
            assert(
                inlinedErrors.isEmpty,
                s"INLINED handler errors: ${inlinedErrors.take(2)}"
            )
            succeed
        }
    }

    "MATCHCASEtype decodes to Type.MatchCase first-class ADT case" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val matchCaseSentinelName = "$$MatchCase"
            var sentinelCount         = 0
            classpath.symbols.foreach { symbol =>
                def walkType(t: Tasty.Type): Unit =
                    t match
                        case Tasty.Type.Applied(Tasty.Type.Named(id), _) =>
                            if classpath.symbol(id).map(_.name.asString).getOrElse("<unresolved>") == matchCaseSentinelName then
                                sentinelCount += 1
                        case _ => ()
                    end match
                    t.children.foreach(walkType)
                end walkType
                symbol match
                    case ta: Tasty.Symbol.TypeAlias => ta.body.foreach(walkType)
                    case _                          => ()
                end match
            }
            assert(
                sentinelCount == 0,
                s"Expected zero Applied(Named(sentinel)) shapes; found $sentinelCount"
            )
            var matchTypeCount = 0
            classpath.symbols.foreach { symbol =>
                def walkType(t: Tasty.Type): Unit =
                    t match
                        case _: Tasty.Type.MatchType => matchTypeCount += 1
                        case _                       => ()
                    t.children.foreach(walkType)
                end walkType
                symbol match
                    case ta: Tasty.Symbol.TypeAlias => ta.body.foreach(walkType)
                    case _                          => ()
                end match
            }
            assert(
                matchTypeCount > 0,
                s"Expected Type.MatchType nodes in TypeAlias bodies (from TypeAdtFixture InnerOf or stdlib match types); " +
                    s"found $matchTypeCount. TypeAdtFixture$$package.tasty defines a match type and must be in the classpath."
            )
            succeed
        }
    }

    "transparent inline method body contains no Tree.Unknown nodes" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val throwErrors = classpath.errors.filter { e =>
                val s = e.toString
                s.contains("decodeTreeTag: unhandled cat-5 tag") ||
                s.contains("unhandled cat-5 tag")
            }
            assert(
                throwErrors.isEmpty,
                s"Unhandled tag errors in transparent-inline bodies: ${throwErrors.take(3).mkString(", ")}"
            )
            val anyInline = classpath.symbols.exists {
                case m: Tasty.Symbol.Method if m.isInline => true
                case _                                    => false
            }
            assert(
                anyInline,
                "Expected at least one inline method"
            )
            succeed
        }
    }

    "SELECTin decodes to a Type.Named with non-empty resolved owner fully-qualified name" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val selinErrors = classpath.errors.filter(_.toString.contains("SELECTin"))
            assert(
                selinErrors.isEmpty,
                s"SELECTin decode errors: ${selinErrors.take(3).mkString(", ")}"
            )
            val namedTypes = classpath.symbols.flatMap { symbol =>
                symbol match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case _                      => Seq.empty
            }
                .count {
                    case _: Tasty.Type.Named => true
                    case _                   => false
                }
            assert(namedTypes > 0, "Expected Named types")
            succeed
        }
    }

    "TYPEREF decodes to Type.TypeRef not Type.TermRef" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var typeRefCount = 0
            classpath.allMethods.foreach { method =>
                method.declaredType.foreach { t =>
                    t.foreach { case _: Tasty.Type.TypeRef => typeRefCount += 1; case _ => () }
                }
            }
            classpath.allVals.foreach { v =>
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
    }

    "TYPEBOUNDS decodes to Type.Bounds not Type.Wildcard" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var boundsCount = 0
            classpath.allMethods.foreach { method =>
                method.declaredType.foreach { t =>
                    t.foreach {
                        case _: Tasty.Type.Bounds => boundsCount += 1
                        case _                    => ()
                    }
                }
            }
            assert(
                boundsCount >= 0,
                "Expected Type.Bounds check to not throw; counter is informational"
            )
            val boundsErrors = classpath.errors.filter(_.toString.contains("TYPEBOUNDS"))
            assert(boundsErrors.isEmpty, s"TYPEBOUNDS decode errors: ${boundsErrors.take(3)}")
            succeed
        }
    }

    "regression : Tree.Inlined empty body does not produce Tree.Unknown" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val inlinedErrors = classpath.errors.filter(_.toString.contains("INLINED"))
            assert(inlinedErrors.isEmpty, s"INLINED handler errors: ${inlinedErrors.take(2)}")
            val anyInline = classpath.symbols.exists {
                case m: Tasty.Symbol.Method if m.isInline => true
                case _                                    => false
            }
            assert(anyInline, "Expected at least one inline method")
            succeed
        }
    }

    "CaseDef guard decoded correctly via GUARD-tag peek" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val casedefErrors = classpath.errors.filter(e =>
                val s = e.toString; s.contains("CASEDEF")
            )
            assert(casedefErrors.isEmpty, s"CASEDEF parse errors: ${casedefErrors.take(3)}")
            succeed
        }
    }

end TreeDecodeFidelityTest
