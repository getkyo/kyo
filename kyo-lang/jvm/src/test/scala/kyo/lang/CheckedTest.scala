package kyo.lang

import kyo.*

/** The `Checked` metadata across compilations: it cannot be declared by hand, and separately compiled `.kyo` definitions reach each
  * other and ordinary Scala through it with their effect contracts intact.
  */
class CheckedTest extends kyo.test.Test[Any]:

    "a .scala source compiled with the plugin cannot declare the metadata" - {
        "on a method" in rejectsScala(
            "Spoof.scala",
            """object Spoof:
              |    @kyo.lang.Checked def bad: Int = 42
              |""".stripMargin
        )("Checked metadata is generated for .kyo definitions; it cannot be declared in .scala")

        "on an object" in rejectsScala(
            "SpoofObject.scala",
            """@kyo.lang.Checked object SpoofObject:
              |    val value = 42
              |""".stripMargin
        )("Checked metadata is generated for .kyo definitions; it cannot be declared in .scala")
    }

    "separate compilation" - {
        "a .kyo file compiles against another's output, and ordinary Scala consumes both without the plugin" in {
            Path.run {
                for
                    dir      <- Compilation.workspace
                    programs <- fixture(dir, "Programs.kyo")
                    more     <- fixture(dir, "More.kyo")
                    first    <- Compilation.compile(Chunk(programs), dir / "programs", Chunk.empty, withPlugin = true)
                    second   <- Compilation.compile(Chunk(more), dir / "more", Chunk(dir / "programs"), withPlugin = true)
                    consumer <- Compilation.write(dir, "Consumer.scala", consumerSource)
                    third    <- Compilation.compile(
                        Chunk(consumer),
                        dir / "consumer",
                        Chunk(dir / "programs", dir / "more"),
                        withPlugin = false
                    )
                    loader <- Compilation.loader(Chunk(dir / "programs", dir / "more", dir / "consumer"))
                    result <- Sync.defer(loader.loadClass("kyolang.separate.Consumer").getMethod("run").invoke(null).toString)
                yield
                    assert(!first.hasErrors, first.errors.mkString("\n"))
                    assert(!second.hasErrors, second.errors.mkString("\n"))
                    assert(!third.hasErrors, third.errors.mkString("\n"))
                    assert(result == expectedResults)
            }
        }

        "ordinary Scala cannot weaken a compiled contract" - {
            "a pending result is not a value" in rejectsInterop(
                "InteropPure.scala",
                "val result: Int = Programs.repeated"
            )("Required: Int")

            "a row cannot drop Abort" in rejectsInterop(
                "InteropMissingAbort.scala",
                "val result: Int < Var[Int] = Programs.mixed"
            )("Required: Int < kyo.Var[Int]")

            "a row compiled separately cannot drop Var" in rejectsInterop(
                "InteropMissingVar.scala",
                "val result: Int < Abort[String] = More.mixed"
            )("Required: Int < kyo.Abort[String]")

            "an unhandled effect cannot be evaluated" in rejectsInterop(
                "InteropUnhandled.scala",
                "val result = Var.run(0)(Programs.mixed).eval"
            )("value eval is not a member of Int < kyo.Abort[String]")

            "the signature check itself rejects a wrong expectation" in rejectsInterop(
                "InteropWrongSignature.scala",
                """val check = Signatures.exact[Int < Var[Int]]("kyolang.separate.Programs", "mixed")"""
            )("Signature mismatch")
        }
    }

    private def rejectsScala(name: String, source: String)(expected: String)(using
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

    /** Compiles the fixtures with the plugin, then `body` without it against their output, expecting a rejection. */
    private def rejectsInterop(name: String, body: String)(expected: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Sync & Abort[FileSystemException] & Scope) =
        Path.run {
            for
                dir      <- Compilation.workspace
                programs <- fixture(dir, "Programs.kyo")
                more     <- fixture(dir, "More.kyo")
                first    <- Compilation.compile(Chunk(programs), dir / "programs", Chunk.empty, withPlugin = true)
                second   <- Compilation.compile(Chunk(more), dir / "more", Chunk(dir / "programs"), withPlugin = true)
                file     <- Compilation.write(
                    dir,
                    name,
                    s"""package kyolang.separate
                       |import kyo.*
                       |import kyo.lang.Signatures
                       |$body
                       |""".stripMargin
                )
                outcome <- Compilation.compile(Chunk(file), dir / "classes", Chunk(dir / "programs", dir / "more"), withPlugin = false)
            yield
                assert(!first.hasErrors, first.errors.mkString("\n"))
                assert(!second.hasErrors, second.errors.mkString("\n"))
                assert(outcome.hasErrors)
                assert(outcome.reports(expected), outcome.errors.mkString("\n"))
        }

    /** A copy of a shared `.kyo` fixture in its own package, so its classes never shadow or are shadowed by the ones this suite's
      * classpath already carries.
      */
    private def fixture(dir: Path, name: String)(using Frame): Path < (PathWrite & Sync & Abort[Nothing]) =
        for
            sources <- Compilation.sources
            content <- (sources / "kyolang" / name).read
            file    <- Compilation.write(dir, name, content.replace("package kyolang", "package kyolang.separate"))
        yield file

    private val consumerSource =
        """package kyolang.separate
          |
          |import kyo.*
          |import kyo.lang.Signatures
          |
          |object Consumer:
          |    def run(): String =
          |        Signatures.exact[Int]("kyolang.separate.Programs", "pure")
          |        Signatures.exact[Int < Var[Int]]("kyolang.separate.Programs", "repeated")
          |        Signatures.exact[Int < (Var[Int] & Abort[String])]("kyolang.separate.Programs", "mixed")
          |        Signatures.exact[Int < Var[Int]]("kyolang.separate.More", "repeated")
          |        Signatures.exact[Int < (Var[Int] & Abort[String])]("kyolang.separate.More", "mixed")
          |        List(
          |            Var.runTuple(0)(Programs.repeated).eval,
          |            Abort.run(Var.run(1)(Programs.mixed)).eval,
          |            Abort.run(Var.run(0)(More.mixed)).eval,
          |            Var.run(0)(More.repeated).eval,
          |            Var.run(0)(topLevel).eval,
          |            Programs.pure
          |        ).mkString(",")
          |""".stripMargin

    private val expectedResults =
        List(
            (1, 2),
            Result.fail("failure"),
            Result.succeed(2),
            5,
            4,
            42
        ).mkString(",")
end CheckedTest
