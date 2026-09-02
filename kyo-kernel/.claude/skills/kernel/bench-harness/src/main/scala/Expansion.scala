import kyo.*

/** Prints the tree of an expansion fixture after a compiler phase, compiled in-process against the kernel's classes from an ordinary
  * sbt build (`Roots.classes`) and kyo-data's beside them. The fixtures under `fixtures-expansion` differ in one variable each, so the
  * diff between two dumps is the cost of one thing: BareValue fires the implicit lift, BareSingleton additionally expands the CanLift
  * splice macro, AlreadyPending fires neither, NoMap has no map at all.
  *
  * Separate from the benchmarks on purpose: they pass a fixed flag array, and a print flag would change what they measure. The
  * classpath handed to dotc is assembled by hand rather than taken from this JVM's, whose published kyo artifacts would shadow the
  * classes under measurement.
  */
object Expansion:

    private def scalaLibraryJars: Seq[String] =
        java.lang.System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .toSeq
            .filter(p => p.contains("scala3-library") || p.contains("scala-library"))

    def classpath: String =
        val kyoData = Roots.repo / "kyo-data" / "jvm" / "target" / "scala-3.8.4" / "classes"
        (scalaLibraryJars :+ kyoData.toString :+ Roots.classes.toString).mkString(java.io.File.pathSeparator)

    def dump(fixture: String, phase: String)(using Frame): Unit < (Sync & Abort[Bench.BracketFailed]) =
        val file = Roots.harness / "fixtures-expansion" / s"$fixture.scala"
        Sync.defer {
            val out = java.nio.file.Files.createTempDirectory("kyo-expansion").toString
            val rep = dotty.tools.dotc.Main.process(
                Array("-classpath", classpath, "-d", out, s"-Xprint:$phase", file.toString)
            )
            if rep.hasErrors then Abort.fail(Bench.BracketFailed(s"$fixture failed to compile against ${Roots.classes}"))
            else ()
        }
    end dump

end Expansion
