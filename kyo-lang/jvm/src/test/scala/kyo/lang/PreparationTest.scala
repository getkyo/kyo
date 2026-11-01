package kyo.lang

import kyo.*

/** Declarations and bodies the plugin's preparation phase and the lowering macro reject. */
class PreparationTest extends kyo.test.Test[Any]:

    private def rejects(name: String, source: String)(expected: String)(using Frame, kyo.test.AssertScope): Unit < (Sync & Scope) =
        for
            dir     <- Compilation.workspace
            file    <- Compilation.write(dir, name, source)
            outcome <- Compilation.compile(List(file), dir.resolve("classes"), Nil, withPlugin = true)
        yield
            assert(outcome.hasErrors)
            assert(outcome.reports(expected), outcome.errors.mkString("\n"))

    "declarations" - {
        "a default parameter" in rejects(
            "DefaultArgument.kyo",
            """object DefaultArgument:
              |    def bad(value: Unit = println("must not execute")): Int = 42
              |""".stripMargin
        )("Default parameters are not supported in .kyo")

        "an object initialization statement" in rejects(
            "Initialization.kyo",
            """object Initialization:
              |    println("must not execute")
              |""".stripMargin
        )("Object initialization statements are not supported in .kyo")

        "a class" in rejects(
            "Class.kyo",
            """class Bad(value: Int):
              |    def twice = value + value
              |""".stripMargin
        )("Classes and traits are not supported in .kyo")

        "object inheritance" in rejects(
            "Inheritance.kyo",
            """trait Base
              |object Bad extends Base
              |""".stripMargin
        )("Object inheritance is not supported in .kyo")

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

    "bodies" - {
        "an effectful field" in rejects(
            "EffectfulField.kyo",
            """import kyo.*
              |object EffectfulField:
              |    val bad = Var.get[Int]
              |""".stripMargin
        )("Effectful fields are not supported in .kyo")

        "an effectful callback" in rejects(
            "Callback.kyo",
            """import kyo.*
              |object Callback:
              |    def bad = List(1).map(x => Var.get[Int])
              |""".stripMargin
        )("Effectful callbacks are not supported in .kyo")

        "an effectful by-name argument" in rejects(
            "ByName.kyo",
            """import kyo.*
              |object ByName:
              |    def ignore(value: => Int): Int = 42
              |    def bad = ignore(Var.get[Int])
              |""".stripMargin
        )("Effectful by-name arguments are not supported in .kyo")
    }
end PreparationTest
