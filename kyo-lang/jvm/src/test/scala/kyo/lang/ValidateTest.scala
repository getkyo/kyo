package kyo.lang

import kyo.*

/** Programs the validator rejects: unadmitted APIs and references, `null`, and mutable state. */
class ValidateTest extends kyo.test.Test[Any]:

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

    /** Compiles an ordinary Scala `Bridge` object together with a `.kyo` file that reaches into it. */
    private def rejectsWithBridge(name: String, source: String)(expected: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Sync & Abort[FileSystemException] & Scope) =
        Path.run {
            for
                dir     <- Compilation.workspace
                bridge  <- Compilation.write(dir, "Bridge.scala", bridgeSource)
                bad     <- Compilation.write(dir, name, source)
                outcome <- Compilation.compile(Chunk(bridge, bad), dir / "classes", Chunk.empty, withPlugin = true)
            yield
                assert(outcome.hasErrors)
                assert(outcome.reports(expected), outcome.errors.mkString("\n"))
        }

    "an unadmitted API" in rejects(
        "UnsafeCall.kyo",
        """object UnsafeCall:
          |    def bad = println("must not execute")
          |""".stripMargin
    )("API is not admitted in .kyo: scala.Predef.println")

    "a static Java API" in rejects(
        "JavaStatic.kyo",
        """object JavaStatic:
          |    def bad = java.lang.System.currentTimeMillis()
          |""".stripMargin
    )("API is not admitted in .kyo: java.lang.System.currentTimeMillis")

    "an effectful argument to an ordinary by-name parameter" in rejects(
        "Delayed.kyo",
        """object Delayed:
          |    def delayed(value: => Int) = value + value
          |    def bad = delayed(Var.get[Int])
          |""".stripMargin
    )("by-name")

    "an unadmitted parameterless method on an ordinary Scala object" in rejectsWithBridge(
        "Parameterless.kyo",
        """object Parameterless:
          |    def bad = Bridge.parameterless
          |""".stripMargin
    )("API is not admitted in .kyo: Bridge.parameterless")

    "an unadmitted field on an ordinary Scala object" in rejectsWithBridge(
        "UnapprovedField.kyo",
        """object UnapprovedField:
          |    def bad = Bridge.field
          |""".stripMargin
    )("Reference is not admitted in .kyo: Bridge.field")

    "an inserted conversion from an ordinary Scala object is not admitted" in rejectsWithBridge(
        "InsertedConversion.kyo",
        """import Bridge.given
          |object InsertedConversion:
          |    def bad: String = 42
          |""".stripMargin
    )("is not admitted in .kyo: Bridge.toText")

    "an implicit AllowUnsafe in scope does not admit an unsafe API" in rejects(
        "ImplicitUnsafe.kyo",
        """import AllowUnsafe.embrace.danger
          |object ImplicitUnsafe:
          |    def bad = AtomicInt.Unsafe.init(0)
          |""".stripMargin
    )("is not admitted in .kyo: kyo.AllowUnsafe")

    "an aliased AllowUnsafe requirement is recognized" in rejectsWithBridge(
        "AliasedUnsafe.kyo",
        """object AliasedUnsafe:
          |    def bad = Bridge.aliased(using AllowUnsafe.embrace.danger)
          |""".stripMargin
    )("API is not admitted in .kyo: Bridge.aliased")

    "an unadmitted call inside a callback to a collection method" in rejects(
        "ProbeA.kyo",
        """object ProbeA:
          |    def bad = List(1).map(n => n + java.lang.System.currentTimeMillis().toInt)
          |""".stripMargin
    )("API is not admitted in .kyo: java.lang.System.currentTimeMillis")

    "an unadmitted call inside a stored closure" in rejects(
        "ProbeB.kyo",
        """object ProbeB:
          |    def bad =
          |        val f = (n: Int) => n + java.lang.System.currentTimeMillis().toInt
          |        f(1)
          |""".stripMargin
    )("API is not admitted in .kyo: java.lang.System.currentTimeMillis")

    "an unadmitted call after an effect, inside the generated continuation" in rejects(
        "ProbeC.kyo",
        """object ProbeC:
          |    def bad =
          |        val n = Var.get[Int]
          |        n + java.lang.System.currentTimeMillis().toInt
          |""".stripMargin
    )("API is not admitted in .kyo: java.lang.System.currentTimeMillis")

    "a source callback inside an inline Kyo API is checked" in rejects(
        "InlineCallback.kyo",
        """object InlineCallback:
          |    def bad = Var.update[Int](n => n + java.lang.System.currentTimeMillis().toInt)
          |""".stripMargin
    )("API is not admitted in .kyo: java.lang.System.currentTimeMillis")

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
        """object PendingMethods:
          |    def badMap = Var.get[Int].map(x => x + 1)
          |    def badUnit = Var.get[Int].unit
          |""".stripMargin
    )("value map is not a member of Int")

    "an annotation cannot erase or replace a required effect" in rejects(
        "EffectRow.kyo",
        """object EffectRow:
          |    def bad: Int < Async = Var.get[Int]
          |""".stripMargin
    )("Required: Int < kyo.Async")

    private val bridgeSource =
        """import kyo.*
          |object Bridge:
          |    def parameterless: Int = 42
          |    val field: Int = 42
          |    type Danger = AllowUnsafe
          |    def aliased(using Danger): Int = 42
          |    given toText: Conversion[Int, String] = (value: Int) => value.toString
          |""".stripMargin
end ValidateTest
