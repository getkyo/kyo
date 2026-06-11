package kyo.demos

import kyo.*
import kyo.Tasty.*
import kyo.internal.TestClasspaths

/** IDE-style hover query: resolve a symbol and render its kind and signature, plus a composed "find all sealed classes"
  * query. This is the canonical kyo-lsp shaped use case: the classpath open carries effects, but all symbol traversal is
  * pure data access (`topLevelClasses`, `declarationIds`, `flags`, `sourcePosition`, `scaladoc`).
  *
  * Assertions pin the resolved kind for a known fixture member and the membership of the sealed fixtures `Animal` and
  * `Vehicle` in the sealed-class query result.
  */
class IdeHoverDemoTest extends kyo.test.Test[Any]:

    /** Return a short string identifying the symbol's kind via sealed pattern matching. */
    private def symbolKindStr(symbol: Tasty.Symbol): String =
        symbol match
            case _: Tasty.Symbol.Class     => "Class"
            case _: Tasty.Symbol.Trait     => "Trait"
            case _: Tasty.Symbol.Object    => "Object"
            case _: Tasty.Symbol.Method    => "Method"
            case _: Tasty.Symbol.Val       => "Val"
            case _: Tasty.Symbol.Var       => "Var"
            case _: Tasty.Symbol.Field     => "Field"
            case _: Tasty.Symbol.Package   => "Package"
            case _: Tasty.Symbol.TypeAlias => "TypeAlias"
            case _: Tasty.Symbol.Parameter => "Parameter"
            case _                         => "Unknown"

    /** Produce a human-readable type signature for any symbol. */
    private def symbolSignature(symbol: Tasty.Symbol): String =
        symbol match
            case m: Tasty.Symbol.Method =>
                m.declaredType match
                    case Maybe.Present(t) => t.toString
                    case Maybe.Absent     => m.name.asString
            case v: Tasty.Symbol.Val =>
                v.declaredType match
                    case Maybe.Present(t) => t.toString
                    case Maybe.Absent     => v.name.asString
            case other =>
                other.name.asString

    /** Look up a member by name within a class and render its kind + signature. */
    private def hoverByName(classpath: Tasty.Classpath, fullName: String, member: String): Maybe[String] =
        classpath.findClass(fullName) match
            case Absent => Maybe.Absent
            case Present(cls) =>
                val decls = cls.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
                Maybe.fromOption(decls.find(_.name.asString == member))
                    .map(s => s"${symbolKindStr(s)} ${s.name.asString}: ${symbolSignature(s)}")

    "hoverByName resolves the value parameter of a case class" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            hoverByName(classpath, "kyo.fixtures.SomeCaseClass", "name") match
                case Present(rendered) =>
                    assert(
                        rendered.startsWith("Parameter name:"),
                        s"Expected a Parameter hover for 'name', got: $rendered"
                    )
                    succeed
                case Absent =>
                    fail("hoverByName(kyo.fixtures.SomeCaseClass, name) returned Absent")
        }
    }

    "hoverByName resolves the synthesized constructor as a Method" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            hoverByName(classpath, "kyo.fixtures.SomeCaseClass", "<init>") match
                case Present(rendered) =>
                    assert(
                        rendered.startsWith("Method <init>:"),
                        s"Expected a Method hover for '<init>', got: $rendered"
                    )
                    succeed
                case Absent =>
                    fail("hoverByName(kyo.fixtures.SomeCaseClass, <init>) returned Absent")
        }
    }

    "findSealed returns the sealed fixture traits Animal and Vehicle" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val sealedClasses = classpath.topLevelClasses.filter(_.flags.contains(Tasty.Flag.Sealed))
            val names         = sealedClasses.map(s => classpath.fullName(s).asString)
            assert(
                names.contains("kyo.fixtures.Animal") && names.contains("kyo.fixtures.Vehicle"),
                s"Expected sealed traits 'kyo.fixtures.Animal' and 'kyo.fixtures.Vehicle' among: ${names.filter(_.startsWith("kyo.fixtures")).mkString(", ")}"
            )
            succeed
        }
    }

end IdeHoverDemoTest
