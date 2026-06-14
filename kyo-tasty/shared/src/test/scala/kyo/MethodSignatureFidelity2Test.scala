package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths

/** Fidelity tests for Method.declaredType correctness.
  *
  * Verifies that no Named(-1) sentinels appear in method declaredTypes, that Type.Applied instances
  * are decoded correctly, and that declaredType.show produces a non-empty result on all platforms.
  *
  * On JS/Native the scala.Tuple symbol is not present (no stdlib), so those checks
  * produce succeed (symbol Absent). The all-stdlib-methods case exercises the embedded fixture set.
  */
class MethodSignatureFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    "scala.Tuple.splitAt declaredType contains no Named(-1)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("scala.Tuple") match
                case Absent =>
                    succeed
                case Present(tupleSym: Tasty.Symbol.ClassLike) =>
                    Maybe.fromOption(
                        tupleSym.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "splitAt")
                    ) match
                        case Absent =>
                            succeed
                        case Present(splitAt) =>
                            val sentinels = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            splitAt match
                                case m: Tasty.Symbol.Method =>
                                    m.declaredType.foreach { dt =>
                                        dt.foreach { t =>
                                            t match
                                                case Tasty.Type.Named(id) if id.value == -1 => discard(sentinels += t)
                                                case _                                      => ()
                                        }
                                    }
                                case _ => ()
                            end match
                            assert(
                                sentinels.isEmpty,
                                s"Expected no Named(-1) in scala.Tuple.splitAt declaredType, found ${sentinels.size}: $sentinels"
                            )
                            succeed
            end match
        }
    }

    "scala.Tuple.++ declaredType contains no Named(-1)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("scala.Tuple") match
                case Absent =>
                    succeed
                case Present(tupleSym: Tasty.Symbol.ClassLike) =>
                    Maybe.fromOption(tupleSym.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "++")) match
                        case Absent =>
                            succeed
                        case Present(plusPlus) =>
                            val sentinels = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            plusPlus match
                                case m: Tasty.Symbol.Method =>
                                    m.declaredType.foreach { dt =>
                                        dt.foreach { t =>
                                            t match
                                                case Tasty.Type.Named(id) if id.value == -1 => discard(sentinels += t)
                                                case _                                      => ()
                                        }
                                    }
                                case _ => ()
                            end match
                            assert(
                                sentinels.isEmpty,
                                s"Expected no Named(-1) in scala.Tuple.++ declaredType, found ${sentinels.size}: $sentinels"
                            )
                            succeed
            end match
        }
    }

    "all-stdlib-methods have zero Named(-1) in declaredType" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var sentinelCount   = 0
            val sampleViolators = new scala.collection.mutable.ArrayBuffer[String]()
            classpath.allMethods.foreach { m =>
                m.declaredType.foreach { dt =>
                    dt.foreach { t =>
                        t match
                            case Tasty.Type.Named(id) if id.value == -1 =>
                                sentinelCount += 1
                                if sampleViolators.size < 5 then
                                    import Tasty.Name.asString
                                    discard(sampleViolators += m.name.asString)
                            case _ => ()
                    }
                }
            }
            assert(
                sentinelCount == 0,
                s"Expected 0 Named(-1) in all method declaredTypes, " +
                    s"found $sentinelCount. Sample violators: ${sampleViolators.mkString(", ")}"
            )
            succeed
        }
    }

    "classpath.allMethods.headOption.declaredType.show is non-empty on all platforms" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.allMethods.headOption match
                case None =>
                    fail("Expected at least one method in the embedded fixture classpath; got 0. " +
                        "Embedded fixtures should contain methods from PlainClass, VarargFixture, etc.")
                case Some(m) =>
                    m.declaredType.foreach { dt =>
                        assert(
                            classpath.typeShow(dt).nonEmpty,
                            s"Expected non-empty show string for declaredType variant of method ${m.name}"
                        )
                    }
                    succeed
        }
    }

end MethodSignatureFidelity2Test
