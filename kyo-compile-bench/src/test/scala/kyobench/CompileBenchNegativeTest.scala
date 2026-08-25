package kyobench

import java.io.File
import org.scalatest.freespec.AnyFreeSpec

/** Compiles the fixtures-negative files with a real dotc against the new kernel's classes
  * and asserts each fails with its expected guided message. These are the compile-error
  * contracts that compiletime.testing cannot pin reliably: aborts raised during inline
  * expansion (the issue-903 Unit row trap) are captured only by a real compile.
  */
class CompileBenchNegativeTest extends AnyFreeSpec:

    private val root: File =
        Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
            .takeWhile(_ != null)
            .find(d => new File(d, "kyo-compile-bench/fixtures-negative").isDirectory)
            .getOrElse(sys.error("repo root not found from " + new File(".").getAbsolutePath))

    private def compileErrors(fixture: String): List[String] =
        val file = new File(root, s"kyo-compile-bench/fixtures-negative/$fixture.scala")
        require(file.isFile, s"missing fixture: $file")
        val kernel = new File(root, "kyo-kernel/jvm/target/scala-3.8.4/classes")
        require(kernel.isDirectory, s"missing kernel classes: $kernel")
        val cp     = sys.props("java.class.path") + File.pathSeparator + kernel.getAbsolutePath
        val out    = java.nio.file.Files.createTempDirectory("kyocb-neg").toFile
        val errors = scala.collection.mutable.ListBuffer[String]()
        val reporter = new dotty.tools.dotc.reporting.Reporter:
            def doReport(dia: dotty.tools.dotc.reporting.Diagnostic)(using dotty.tools.dotc.core.Contexts.Context): Unit =
                if dia.level >= dotty.tools.dotc.interfaces.Diagnostic.ERROR then
                    errors += dia.message
        val rep = dotty.tools.dotc.Main.process(
            Array("-classpath", cp, "-d", out.getAbsolutePath, file.getAbsolutePath),
            reporter
        )
        assert(rep.hasErrors, s"$fixture compiled, expected a failure")
        errors.toList
    end compileErrors

    "a Unit row mismatch aborts with the issue-903 guidance" in {
        val errors = compileErrors("UnitRowMismatch")
        assert(errors.exists(_.contains("Cannot lift `Unit < ")), errors.mkString("\n"))
    }

end CompileBenchNegativeTest
