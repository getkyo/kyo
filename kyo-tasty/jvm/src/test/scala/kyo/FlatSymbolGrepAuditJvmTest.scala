package kyo

/** JVM-specific companion to FlatSymbolGrepAuditTest (leaf 174): performs an actual file-system grep to confirm that no
  * `final case class Symbol ` declaration survives in `kyo-tasty/shared/src/main`.
  *
  * This test is JVM-only because `java.io.File` and `scala.io.Source.fromFile` are not available on Scala.js.
  */
class FlatSymbolGrepAuditJvmTest extends Test:

    "FlatSymbolGrepAuditJvmTest: no flat `final case class Symbol ` in main sources (JVM file-scan)" in run {
        val guesses = Seq(
            "kyo-tasty/shared/src/main/scala",
            "../kyo-tasty/shared/src/main/scala"
        )
        val srcRoot = guesses
            .map(p => new java.io.File(p).getAbsolutePath)
            .find(p => new java.io.File(p).isDirectory)

        srcRoot match
            case None =>
                // Binary-only classpath or unusual working directory; skip.
                succeed
            case Some(path) =>
                var hits = 0
                def scan(dir: java.io.File): Unit =
                    if dir.isDirectory then
                        val entries = dir.listFiles()
                        if entries ne null then
                            entries.foreach: f =>
                                if f.isDirectory then scan(f)
                                else if f.getName.endsWith(".scala") then
                                    val src = scala.io.Source.fromFile(f)
                                    try
                                        src.getLines().foreach: line =>
                                            if line.contains("final case class Symbol ") then
                                                hits += 1
                                    finally src.close()
                                    end try
                        end if
                end scan

                scan(new java.io.File(path))
                assert(
                    hits == 0,
                    s"Found $hits occurrence(s) of 'final case class Symbol ' in $path; flat Symbol shape must be deleted"
                )
                succeed
        end match
    }

end FlatSymbolGrepAuditJvmTest
