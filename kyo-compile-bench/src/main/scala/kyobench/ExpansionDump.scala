package kyobench

import java.io.File
import java.nio.file.Files

/** Dumps the tree of a fixture after a named compiler phase, against the kernel's classes.
  *
  * Separate from CompileBench on purpose: that harness passes a fixed flag array and adding a print flag to it would change what the
  * benchmark measures. This is the diagnostic tool, run by hand.
  *
  * Usage: ExpansionDump <fixture> [phase]
  *   fixture: a file in kyo-compile-bench/fixtures-expansion, without .scala
  *   phase:   compiler phase to print after, default "inlining"
  *
  * The three fixtures differ in one variable each, so the diff between two dumps is the cost of one thing: BareValue fires the implicit
  * lift, BareSingleton additionally expands the CanLift splice macro, and AlreadyPending fires neither.
  */
object ExpansionDump:

    def main(args: Array[String]): Unit =
        val fixture = args.headOption.getOrElse(sys.error("usage: ExpansionDump <fixture> [phase]"))
        val phase   = args.lift(1).getOrElse("inlining")

        val root =
            Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
                .takeWhile(_ != null)
                .find(d => new File(d, "kyo-compile-bench/fixtures-expansion").isDirectory)
                .getOrElse(sys.error("repo root not found from " + new File(".").getAbsolutePath))

        val file = new File(root, s"kyo-compile-bench/fixtures-expansion/$fixture.scala")
        require(file.isFile, s"missing fixture: $file")

        val classes = new File(root, "kyo-kernel/jvm/target/scala-3.8.4/classes")
        require(classes.isDirectory, s"missing kernel classes: $classes")

        val cp  = sys.props("java.class.path") + File.pathSeparator + classes.getAbsolutePath
        val out = Files.createTempDirectory("kyo-expansion").toFile

        val rep = dotty.tools.dotc.Main.process(
            Array("-classpath", cp, "-d", out.getAbsolutePath, s"-Xprint:$phase", file.getAbsolutePath)
        )
        if rep.hasErrors then sys.error(s"$fixture failed to compile")
    end main

end ExpansionDump
