package kyo.lang

import kyo.*

/** The construct inventory's rejections, one case per row, each with the exact diagnostic the compiler produces for it.
  *
  * Two kinds of rows are here: Deny rows, which the language excludes on purpose, and Allow rows that have no lowering rule yet and are
  * rejected until they do (their cases say so in the name). The rows the language supports are in `BodyInventoryTest`.
  */
class KyoLangPluginInventoryTest extends kyo.test.Test[Any]:

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

    "lexical forms and compilation units" - {
        "L08 export clauses are not supported yet" in rejects(
            "Exported.kyo",
            """object Exported:
              |    export scala.collection.immutable.List.apply
              |""".stripMargin
        )("Export clauses are not supported in .kyo")

        "L10 package objects" in rejects(
            "Packaged.kyo",
            """package object packaged:
              |    val value = 1
              |""".stripMargin
        )("Package objects are not supported in .kyo")

        "L11 symbol literals" in rejects(
            "Symbols.kyo",
            """object Symbols:
              |    def bad = 'symbol
              |""".stripMargin
        )("symbol literal")

        "L11 procedure syntax" in rejects(
            "Procedure.kyo",
            """object Procedure:
              |    def bad { }
              |""".stripMargin
        )("'=' expected")
    }

    "types" - {
        "T10 structural selection" in rejects(
            "Structural.kyo",
            """import scala.reflect.Selectable.reflectiveSelectable
              |object Structural:
              |    def bad(record: { def value: Int }) = record.value
              |""".stripMargin
        )("API is not admitted in .kyo")

        "T11 casts" in rejects(
            "Casts.kyo",
            """object Casts:
              |    def bad(value: Any) = value.asInstanceOf[Int]
              |""".stripMargin
        )("API is not admitted in .kyo")

        "T11 runtime type inspection" in rejects(
            "Inspection.kyo",
            """object Inspection:
              |    def bad(value: Any) = value.isInstanceOf[Int]
              |""".stripMargin
        )("API is not admitted in .kyo")
    }

    "bindings and parameters" - {
        "D03 refutable value patterns" in rejects(
            "Refutable.kyo",
            """object Refutable:
              |    def bad(value: Option[Int]) =
              |        val Some(n) = value
              |        n
              |""".stripMargin
        )("more specialized")

        "D04 and E12 assignment" in rejects(
            "Assigned.kyo",
            """object Assigned:
              |    def bad =
              |        var count = 1
              |        count = 2
              |        count
              |""".stripMargin
        )("Assignment is not supported in .kyo")

        "D05 pattern bindings as fields are not supported yet" in rejects(
            "FieldPattern.kyo",
            """object FieldPattern:
              |    val (a, b) = (1, 2)
              |""".stripMargin
        )("Pattern bindings are supported only inside method bodies in .kyo")

        "D07 lazy locals" in rejects(
            "LazyLocal.kyo",
            """object LazyLocal:
              |    def bad =
              |        lazy val value = 1
              |        value
              |""".stripMargin
        )("Mutable and lazy locals are not supported in .kyo")

        "D13 effectful default arguments" in rejects(
            "EffectfulDefault.kyo",
            """object EffectfulDefault:
              |    def bad(value: Int = Var.get[Int]) = value
              |""".stripMargin
        )("Default parameters are not supported in .kyo")
    }

    "expressions" - {
        "E09 return" in rejects(
            "Returned.kyo",
            """object Returned:
              |    def bad: Int = return 1
              |""".stripMargin
        )("return is not supported in .kyo")

        "E11 throw" in rejects(
            "Thrown.kyo",
            """object Thrown:
              |    def bad = throw new Exception("failure")
              |""".stripMargin
        )("throw is not supported in .kyo")

        "E11 try" in rejects(
            "Tried.kyo",
            """object Tried:
              |    def bad = try 1 catch case e: Exception => 2
              |""".stripMargin
        )("try is not supported in .kyo")

        "E12 indexed update" in rejects(
            "Updated.kyo",
            """object Updated:
              |    def bad(values: Array[Int]) =
              |        values(0) = 1
              |        values
              |""".stripMargin
        )("Assignment is not supported in .kyo")

        "E13 an ordinary function type cannot hide a latent effect" in rejects(
            "HiddenEffect.kyo",
            """object HiddenEffect:
              |    def bad =
              |        val f: Int => Int = (x: Int) => x + Var.get[Int]
              |        f(1)
              |""".stripMargin
        )("Required: Int")

        "E13 an effectful callback to a method without a counterpart" in rejects(
            "Callback.kyo",
            """object Callback:
              |    def twice(f: Int => Int): Int = f(f(1))
              |    def bad = twice(x => x + Var.get[Int])
              |""".stripMargin
        )("A lambda containing .now is given to a method without a Kyo counterpart")

        "E15 partial function values" in rejects(
            "Partial.kyo",
            """object Partial:
              |    def bad =
              |        val pf: PartialFunction[Int, Int] = { case 1 => 2 }
              |        pf(1)
              |""".stripMargin
        )("Partial function values are not supported in .kyo")

        "E18 synchronized" in rejects(
            "Synchronized.kyo",
            """object Synchronized:
              |    def bad = synchronized(1)
              |""".stripMargin
        )("API is not admitted in .kyo")

        "S04 postfix calls" in rejects(
            "Postfix.kyo",
            """import scala.language.postfixOps
              |object Postfix:
              |    def bad = List(1) length
              |""".stripMargin
        )("Postfix calls are not canonical .kyo")

        "S10 placeholder lambdas" in rejects(
            "Placeholder.kyo",
            """object Placeholder:
              |    def bad = List(1).map(_ + 1)
              |""".stripMargin
        )("Placeholder lambda shorthand is not canonical .kyo")
    }

    "patterns" - {
        "P06 effectful extractors" in rejects(
            "Extractor.kyo",
            """object Extractor:
              |    object Positive:
              |        def unapply(value: Int): Option[Int] = if value > Var.get[Int] then Some(value) else None
              |""".stripMargin
        )("Required: Option[Int]")

        "P08 nonexhaustive matches" in rejects(
            "Nonexhaustive.kyo",
            """object Nonexhaustive:
              |    def bad(value: Option[Int]) =
              |        value match
              |            case Some(n) => n
              |""".stripMargin
        )("match may not be exhaustive")
    }

    "classes and objects" - {
        "O02 case classes are not supported yet" in rejects(
            "Data.kyo",
            """object Data:
              |    final case class Point(x: Int, y: Int)
              |""".stripMargin
        )("Classes and traits are not supported in .kyo")

        "O03 enums are not supported yet" in rejects(
            "Colors.kyo",
            """enum Color:
              |    case Red, Green
              |""".stripMargin
        )("Classes and traits are not supported in .kyo")

        "D15, O04, O05 traits with abstract members are not supported yet" in rejects(
            "Shapes.kyo",
            """trait Shape(val sides: Int):
              |    def area: Int
              |""".stripMargin
        )("Classes and traits are not supported in .kyo")

        "M05 derivation needs a class and is not supported yet" in rejects(
            "Derived.kyo",
            """object Derived:
              |    final case class Point(x: Int) derives CanEqual
              |""".stripMargin
        )("Classes and traits are not supported in .kyo")

        "O07 anonymous classes" in rejects(
            "Anonymous.kyo",
            """object Anonymous:
              |    def bad = new Object {}
              |""".stripMargin
        )("Anonymous classes are not supported in .kyo")
    }

    "contextual abstractions" - {
        "C02 givens are not supported yet" in rejects(
            "Givens.kyo",
            """object Givens:
              |    given Int = 1
              |""".stripMargin
        )("Inline and implicit definitions are not supported in .kyo")

        "C06 conversions need a given and are not supported yet" in rejects(
            "Conversions.kyo",
            """object Conversions:
              |    given Conversion[Int, String] = (value: Int) => "n"
              |""".stripMargin
        )("Inline and implicit definitions are not supported in .kyo")

        "C09 reference identity" in rejects(
            "Identity.kyo",
            """object Identity:
              |    def bad(a: String, b: String) = a.eq(b)
              |""".stripMargin
        )("API is not admitted in .kyo")
    }

    "inlining, annotations, and compiler features" - {
        "M03 constant inline values are not supported yet" in rejects(
            "Constant.kyo",
            """object Constant:
              |    inline val size = 1
              |""".stripMargin
        )("Inline and implicit definitions are not supported in .kyo")

        "M04 quotes" in rejects(
            "Quoted.kyo",
            """import scala.quoted.*
              |object Quoted:
              |    def bad(using Quotes) = '{ 1 }
              |""".stripMargin
        )("Expression form is not supported in .kyo")

        "M07 unchecked annotations" in rejects(
            "Unchecked.kyo",
            """object Unchecked:
              |    def bad(value: Option[Int]) =
              |        (value: @unchecked) match
              |            case Some(n) => n
              |""".stripMargin
        )("Expression form is not supported in .kyo")

        "M09 entry points" in rejects(
            "Entry.kyo",
            """object Entry:
              |    @main def run = ()
              |""".stripMargin
        )("Program entry points are not supported in .kyo")

        "M11 language imports" in rejects(
            "Language.kyo",
            """import scala.language.implicitConversions
              |object Language:
              |    def value = 1
              |""".stripMargin
        )("Language imports are not supported in .kyo")
    }

    "libraries" - {
        "A02 APIs requiring AllowUnsafe" in rejects(
            "Unsafe.kyo",
            """object Unsafe:
              |    def bad = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
              |""".stripMargin
        )("is not admitted in .kyo: kyo.AllowUnsafe")

        "A06 raw randomness" in rejects(
            "Randomness.kyo",
            """object Randomness:
              |    def bad = scala.util.Random.nextInt()
              |""".stripMargin
        )("API is not admitted in .kyo: scala.util.Random.nextInt")

        "A07 mutable collections" in rejects(
            "Mutable.kyo",
            """object Mutable:
              |    def bad = scala.collection.mutable.ArrayBuffer(1)
              |""".stripMargin
        )("is not admitted in .kyo: scala.collection.mutable.ArrayBuffer")

        "A08 partial operations" in rejects(
            "PartialOperation.kyo",
            """object PartialOperation:
              |    def bad = List(1).head
              |""".stripMargin
        )("API is not admitted in .kyo")
    }
end KyoLangPluginInventoryTest
