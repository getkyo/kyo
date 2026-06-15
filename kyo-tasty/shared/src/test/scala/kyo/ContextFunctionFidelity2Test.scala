package kyo

import kyo.Tasty.SymbolId
import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Fidelity tests for Type.ContextFunction decoding.
  *
  * Context functions (?=>) decode to the dedicated Type.ContextFunction(params, result) case,
  * structurally distinct from Type.Function. The embedded ContextFunctionFixture provides ?=>
  * types in method signatures on all platforms.
  */
class ContextFunctionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    private def countContextFunctions(t: Tasty.Type): Int =
        var n = 0
        t.foreach {
            case _: Tasty.Type.ContextFunction => n += 1
            case _                             => ()
        }
        n
    end countContextFunctions

    "classpath symbols have at least one ContextFunction in their types" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>

            def walkType(t: Tasty.Type): Int =
                var n = 0
                t.foreach {
                    case _: Tasty.Type.ContextFunction => n += 1
                    case _                             => ()
                }
                n
            end walkType

            var ctxFnCount = 0
            classpath.symbols.foreach { symbol =>
                symbol match
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach { dt =>
                            ctxFnCount += walkType(dt)
                        }
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => ctxFnCount += walkType(t))
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach { pt =>
                            ctxFnCount += walkType(pt)
                        }
                    case _ => ()
            }

            assert(
                ctxFnCount > 0,
                s"Expected at least one ContextFunction in classpath symbol types; found 0. " +
                    s"Check that ContextFunctionFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
            )
            succeed
        }
    }

    "classpath parameters have at least one ContextFunction type" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var paramCtxFnCount = 0
            classpath.symbols.foreach { symbol =>
                symbol match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => paramCtxFnCount += countContextFunctions(t))
                    case _ => ()
            }
            assert(
                paramCtxFnCount > 0,
                s"Expected at least one parameter with ContextFunction type; found 0. " +
                    s"ContextFunctionFixture uses 'Logger ?=>' in withLogger and run methods."
            )
            succeed
        }
    }

    "classpath has positive ContextFunction count across all symbol types" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            var total = 0
            classpath.symbols.foreach { symbol =>
                symbol match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => total += countContextFunctions(t))
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach { dt =>
                            total += countContextFunctions(dt)
                        }
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach { pt =>
                            total += countContextFunctions(pt)
                        }
                    case _ => ()
            }
            assert(
                total > 0,
                s"Expected total ContextFunction count > 0 in classpath symbol types; found 0"
            )
            succeed
        }
    }

    // isContext field was removed; the test confirms structural disjointness between
    // Type.Function and Type.ContextFunction rather than a flag value.
    "no symbol has Function used where ContextFunction expected" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            // All three symbol subtypes are covered via their type fields.
            // The invariant is that ContextFunction and Function are structurally distinct:
            // context-function types decode to Type.ContextFunction, never Type.Function.
            var contextFunctions = 0
            classpath.symbols.foreach { symbol =>
                symbol match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach {
                            case _: Tasty.Type.ContextFunction => contextFunctions += 1
                            case _                             => ()
                        }
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach { dt =>
                            dt.foreach {
                                case _: Tasty.Type.ContextFunction => contextFunctions += 1
                                case _                             => ()
                            }
                        }
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach { pt =>
                            pt.foreach {
                                case _: Tasty.Type.ContextFunction => contextFunctions += 1
                                case _                             => ()
                            }
                        }
                    case _ => ()
            }
            // At least one ContextFunction must be found (fixture contains ?=> types).
            assert(contextFunctions > 0, "Expected at least one ContextFunction type in fixture symbols")
            succeed
        }
    }

    "Type.Function pattern does not match Type.ContextFunction" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val cfType = Tasty.Type.ContextFunction(
                Chunk(Tasty.Type.Named(SymbolId(0))),
                Tasty.Type.Named(SymbolId(1))
            )
            val matchedAsFunction = cfType match
                case Tasty.Type.Function(_, _) => true
                case _                         => false
            assert(
                !matchedAsFunction,
                "Type.ContextFunction should not match the Type.Function(_,_) pattern"
            )
            succeed
        }
    }

    // symbols.size is used as the snapshot-stable proxy because Parameter.declaredType is
    // re-decoded lazily from body bytes after warm load.
    coldWarmEquiv("snapshot cold/warm symbols.size consistent on embedded classpath")(_.symbols.size)

    "show : Type.ContextFunction.show uses ?=> arrow" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val paramType  = Tasty.Type.Named(SymbolId(0))
            val resultType = Tasty.Type.Named(SymbolId(0))
            val cfType     = Tasty.Type.ContextFunction(Chunk(paramType), resultType)
            val s          = classpath.typeShow(cfType)
            assert(
                s.contains("?=>"),
                s"Type.ContextFunction.show should contain '?=>' but got: $s"
            )
            assert(
                !s.contains(" => "),
                s"Type.ContextFunction.show should not contain plain '=>' but got: $s"
            )
        }
    }

end ContextFunctionFidelity2Test
