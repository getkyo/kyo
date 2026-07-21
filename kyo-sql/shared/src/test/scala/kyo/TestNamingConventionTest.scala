package kyo

import kyo.*

/** Self-enforcing test for the kyo-sql test-file naming convention.
  *
  * Walks `kyo-sql/{shared,jvm}/src/test/scala/` and verifies that every `*Test.scala` satisfies ONE of:
  *
  *   1. **Unit-test rule**: the stem (filename minus `Test`) has at least one production source-file basename in
  *      `kyo-sql/{shared,jvm,native,js}/src/main/scala/` that is a prefix of the stem.
  *   2. **Categorical rule**: the stem ends in one of `Integration`, `Consistency`, `RoundTrip`, or `Messages`.
  *
  * Exempted files: `SqlDbTest.scala`, `Test.scala`.
  *
  * See `TestNamingConvention.scala` (main source stub) and `CONTRIBUTING.md` for the full rule text.
  */
class TestNamingConventionTest extends kyo.Test:

    private val categoricalSuffixes = List("Integration", "Consistency", "RoundTrip", "Messages")

    private val exemptedBasenames = Set("SqlDbTest", "Test")

    private val conventionText =
        """|Test-file naming convention (kyo-sql):
           |
           |A test file at kyo-sql/{shared,jvm}/src/test/scala/.../FooTest.scala must satisfy ONE of:
           |
           |  1. Unit-test rule: Foo (the basename minus the Test suffix) has at least one production
           |     source-file basename in kyo-sql/{shared,jvm,native,js}/src/main/scala/ that is a
           |     prefix of Foo.
           |  2. Categorical rule: the test file's name ends in one of:
           |       IntegrationTest, ConsistencyTest, RoundTripTest, MessagesTest.
           |
           |Exempted infrastructure files: SqlDbTest.scala, Test.scala.
           |
           |See CONTRIBUTING.md for the full rule text and TestNamingConventionTest.scala for enforcement.
           |""".stripMargin

    /** Finds the kyo-sql module root.
      *
      * sbt sets `user.dir` to the per-project baseDirectory when forking. Depending on which sub-project runs, this may be the repo root,
      * `kyo-sql/`, or `kyo-sql/{jvm,native,js}`. Handles all three layouts. Falls back to `.` when the property is unavailable (e.g. JS
      * runtime), in which case the test will fail loudly during walk if the directory does not exist, that surfaces a real environment
      * problem rather than silently passing.
      */
    private def findKyoSqlRoot(using Frame): Path < Sync =
        kyo.System.property[String]("user.dir").map { maybeDir =>
            val baseDir = maybeDir.getOrElse(".")
            val dir     = Path(baseDir)
            val parts   = dir.parts
            val last    = parts.lastOption
            val parent  = parts.dropRight(1).lastOption
            // Case 1: running from kyo-sql/<platform>, kyo-sql root is the parent.
            if last.exists(p => p == "jvm" || p == "native" || p == "js") && parent.contains("kyo-sql") then
                dir.parent.getOrElse(dir)
            // Case 2: already at kyo-sql/.
            else if last.contains("kyo-sql") then dir
            // Case 3: at repo root.
            else dir / "kyo-sql"
            end if
        }

    "every *Test.scala under kyo-sql satisfies the naming convention" in {
        findKyoSqlRoot.flatMap { sqlRoot =>
            // Collect all test file paths from shared and jvm test directories
            val testDirs = List(
                sqlRoot / "shared" / "src" / "test" / "scala",
                sqlRoot / "jvm" / "src" / "test" / "scala"
            )

            // Collect all source file basenames from all platform directories
            val sourceDirs = List(
                sqlRoot / "shared" / "src" / "main" / "scala",
                sqlRoot / "jvm" / "src" / "main" / "scala",
                sqlRoot / "native" / "src" / "main" / "scala",
                sqlRoot / "js" / "src" / "main" / "scala"
            )

            def collectTestFiles(dirs: List[Path])(using Frame): Chunk[Path] < (Async & Abort[FileFsException]) =
                Kyo.foreach(dirs) { dir =>
                    dir.exists.map { exists =>
                        if !exists then Chunk.empty[Path]
                        else
                            Scope.run {
                                dir.walk.run
                            }.map { allPaths =>
                                allPaths.filter { p =>
                                    p.name.exists(n => n.endsWith("Test.scala"))
                                }
                            }
                    }
                }.map(_.foldLeft(Chunk.empty[Path])(_ ++ _))

            def collectSourceBasenames(dirs: List[Path])(using Frame): Chunk[String] < (Async & Abort[FileFsException]) =
                Kyo.foreach(dirs) { dir =>
                    dir.exists.map { exists =>
                        if !exists then Chunk.empty[String]
                        else
                            Scope.run {
                                dir.walk.run
                            }.map { allPaths =>
                                allPaths.flatMap { p =>
                                    p.name match
                                        case Present(n) if n.endsWith(".scala") =>
                                            Chunk(n.stripSuffix(".scala"))
                                        case _ => Chunk.empty[String]
                                }
                            }
                    }
                }.map(_.foldLeft(Chunk.empty[String])(_ ++ _))

            for
                testFiles       <- collectTestFiles(testDirs)
                sourceBasenames <- collectSourceBasenames(sourceDirs)
            yield
                if testFiles.isEmpty then
                    fail(
                        s"No *Test.scala files found under $sqlRoot, path discovery may have failed. " +
                            s"working directory: $sqlRoot"
                    )
                else
                    // Extract stem (remove "Test.scala" suffix) and check against exemptions and rules
                    val violations = testFiles.toList.flatMap { testPath =>
                        val basename = testPath.name.getOrElse("")
                        val stem     = basename.stripSuffix("Test.scala")
                        if exemptedBasenames.contains(stem + "Test") then None
                        else
                            // Rule 1: any source basename is a prefix of stem
                            val satisfiesRule1 = sourceBasenames.exists(prefix => stem.startsWith(prefix) && prefix.nonEmpty)
                            // Rule 2: stem ends in one of the categorical suffixes
                            val satisfiesRule2 = categoricalSuffixes.exists(suffix => stem.endsWith(suffix))
                            if satisfiesRule1 || satisfiesRule2 then None
                            else
                                Some(
                                    s"  - $testPath\n" +
                                        s"    Stem: $stem\n" +
                                        s"    Reason: Does not match rule 1 (no source prefix is a prefix of stem) and does not match rule 2 (name does not end in IntegrationTest, ConsistencyTest, RoundTripTest, or MessagesTest)\n"
                                )
                            end if
                        end if
                    }
                    if violations.nonEmpty then
                        val msg = s"Test naming convention violations found (${violations.size} file(s)):\n" +
                            violations.mkString("\n") + "\n" + conventionText
                        fail(msg)
                    else
                        succeed
                    end if
                end if
            end for
        }
    }

end TestNamingConventionTest
