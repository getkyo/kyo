package kyo.lang

import kyo.*

/** Declarations and bodies the plugin's preparation phase, the body macro, and kyo-direct's restrictions reject. */
class PreparationTest extends kyo.test.Test[Any]:

    private def rejects(name: String, source: String)(expected: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Sync & Abort[FileSystemException] & Scope) =
        Path.run {
            for
                dir     <- Compilation.workspace
                file    <- Compilation.write(dir, name, source)
                outcome <- Compilation.compile(Chunk(file), dir / "classes", Chunk.empty, withPlugin = true)
            yield
                assert(outcome.hasErrors)
                assert(outcome.reports(expected), outcome.errors.mkString("\n"))
        }

    "declarations" - {
        "a default argument calling an unadmitted API" in rejects(
            "DefaultArgument.kyo",
            """object DefaultArgument:
              |    def bad(value: Unit = println("must not execute")): Int = 42
              |""".stripMargin
        )("API is not admitted in .kyo: scala.Predef.println")

        "an object initialization statement" in rejects(
            "Initialization.kyo",
            """object Initialization:
              |    println("must not execute")
              |""".stripMargin
        )("Initialization statements are not supported in .kyo")

        "an open class" in rejects(
            "Class.kyo",
            """class Bad(value: Int):
              |    def twice = value + value
              |""".stripMargin
        )("A class in .kyo must be final")

        "an unsealed trait" in rejects(
            "Inheritance.kyo",
            """trait Base
              |object Bad extends Base
              |""".stripMargin
        )("A trait or abstract class in .kyo must be sealed")

        "a parent that is not admitted" in rejects(
            "Parent.kyo",
            """object Bad extends scala.collection.mutable.ArrayBuffer[Int]
              |""".stripMargin
        )("Parent is not admitted in .kyo: scala.collection.mutable.ArrayBuffer")

        "a field initializer calling a method of the object under construction" in rejects(
            "Initializer.kyo",
            """object Bad:
              |    val first = second + 1
              |    def second = third
              |    val third  = 1
              |""".stripMargin
        )("An initializer may not call second; the object is not initialized yet")

        "a field initializer reading a later field" in rejects(
            "Later.kyo",
            """object Bad:
              |    val first  = second + 1
              |    val second = 1
              |""".stripMargin
        )("An initializer may not read second before it is initialized")

        "this escaping an initializer" in rejects(
            "Escape.kyo",
            """final case class Bad(value: Int):
              |    val self = List(this)
              |""".stripMargin
        )("this may not escape while the object is initialized")

        "a mutable field" in rejects(
            "MutableField.kyo",
            """object Bad:
              |    var count = 0
              |""".stripMargin
        )("Mutable and lazy fields are not supported in .kyo")

        "an inline method" in rejects(
            "Inline.kyo",
            """object Bad:
              |    inline def twice(value: Int) = value + value
              |""".stripMargin
        )("Inline and implicit definitions are not supported in .kyo")
    }

    "imports" - {
        "kyo.* is in scope without an import, and an explicit import is harmless" in {
            Path.run {
                for
                    dir      <- Compilation.workspace
                    implied  <- Compilation.write(dir, "Implied.kyo", "object Implied:\n    def read = Var.get[Int] + 1\n")
                    explicit <- Compilation.write(dir, "Explicit.kyo", "import kyo.*\nobject Explicit:\n    def read = Var.get[Int] + 1\n")
                    outcome  <- Compilation.compile(Chunk(implied, explicit), dir / "classes", Chunk.empty, withPlugin = true)
                yield assert(!outcome.hasErrors, outcome.errors.mkString("\n"))
            }
        }
    }

    "bodies" - {
        "an effectful field" in rejects(
            "EffectfulField.kyo",
            """object EffectfulField:
              |    val bad = Var.get[Int]
              |""".stripMargin
        )("Effectful fields are not supported in .kyo")

        "an effectful callback to a method without a Kyo counterpart" in rejects(
            "Callback.kyo",
            """object Callback:
              |    def twice(f: Int => Int): Int = f(f(1))
              |    def bad = twice(x => Var.get[Int] + x)
              |""".stripMargin
        )("A lambda containing .now is given to a method without a Kyo counterpart")

        "an effectful by-name argument" in rejects(
            "ByName.kyo",
            """object ByName:
              |    def ignore(value: => Int): Int = 42
              |    def bad = ignore(Var.get[Int])
              |""".stripMargin
        )("cannot appear inside a")
    }
end PreparationTest
