package kyo.lang

import kyo.*

/** Programs the validator rejects: unadmitted APIs and references, `null`, and mutable state. */
class ValidateTest extends kyo.test.Test[Any]:

    private def rejects(name: String, source: String)(expected: String)(using Frame, kyo.test.AssertScope): Unit < (Sync & Scope) =
        for
            dir     <- Compilation.workspace
            file    <- Compilation.write(dir, name, source)
            outcome <- Compilation.compile(List(file), dir.resolve("classes"), Nil, withPlugin = true)
        yield
            assert(outcome.hasErrors)
            assert(outcome.reports(expected), outcome.errors.mkString("\n"))

    "an unadmitted API" in rejects(
        "UnsafeCall.kyo",
        """object UnsafeCall:
          |    def bad = println("must not execute")
          |""".stripMargin
    )("API is not admitted in .kyo: scala.Predef.println")

    "a static Java API" in rejects(
        "JavaStatic.kyo",
        """object JavaStatic:
          |    def bad = System.currentTimeMillis()
          |""".stripMargin
    )("not a value")

    "a handler" in rejects(
        "Handler.kyo",
        """import kyo.*
          |object Handler:
          |    def bad = Abort.run[String] {
          |        Abort.fail("failure")
          |        42
          |    }
          |""".stripMargin
    )("API is not admitted in .kyo")

    "an unadmitted parameterless method on an ordinary Scala object" in {
        for
            dir    <- Compilation.workspace
            bridge <- Compilation.write(dir, "Bridge.scala", bridgeSource)
            bad    <- Compilation.write(
                dir,
                "Parameterless.kyo",
                """object Parameterless:
                  |    def bad = Bridge.parameterless
                  |""".stripMargin
            )
            outcome <- Compilation.compile(List(bridge, bad), dir.resolve("classes"), Nil, withPlugin = true)
        yield
            assert(outcome.hasErrors)
            assert(outcome.reports("API is not admitted in .kyo: Bridge.parameterless"), outcome.errors.mkString("\n"))
    }

    "an unadmitted field on an ordinary Scala object" in {
        for
            dir    <- Compilation.workspace
            bridge <- Compilation.write(dir, "Bridge.scala", bridgeSource)
            bad    <- Compilation.write(
                dir,
                "UnapprovedField.kyo",
                """object UnapprovedField:
                  |    def bad = Bridge.field
                  |""".stripMargin
            )
            outcome <- Compilation.compile(List(bridge, bad), dir.resolve("classes"), Nil, withPlugin = true)
        yield
            assert(outcome.hasErrors)
            assert(outcome.reports("Reference is not admitted in .kyo: Bridge.field"), outcome.errors.mkString("\n"))
    }

    "null" in rejects(
        "Null.kyo",
        """object Null:
          |    def bad = null
          |""".stripMargin
    )("Null is not supported in .kyo")

    "a mutable local" in rejects(
        "Mutable.kyo",
        """object Mutable:
          |    def bad =
          |        var a = 1
          |        a
          |""".stripMargin
    )("Mutable and lazy locals are not supported in .kyo")

    "pending-value methods are not available on an exposed result" in rejects(
        "PendingMethods.kyo",
        """import kyo.*
          |object PendingMethods:
          |    def badMap = Var.get[Int].map(x => x + 1)
          |    def badUnit = Var.get[Int].unit
          |""".stripMargin
    )("value map is not a member of Int")

    "an annotation cannot erase or replace a required effect" in rejects(
        "EffectRow.kyo",
        """import kyo.*
          |object EffectRow:
          |    def bad: Int < Async = Var.get[Int]
          |""".stripMargin
    )("Required: Int < kyo.Async")

    private val bridgeSource =
        """object Bridge:
          |    def parameterless: Int = 42
          |    val field: Int = 42
          |""".stripMargin
end ValidateTest
