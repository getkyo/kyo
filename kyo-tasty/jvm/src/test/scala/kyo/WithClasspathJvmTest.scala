package kyo

/** JVM-only leaves for WithClasspathTest that require java.nio.file or java.io.File.
  *
  * Leaf 6: withClasspath(roots, Present(cacheDir)) requires java.nio.file.Files.createTempDirectory
  * and java.io.File, which are not available in Scala.js or Scala Native. This leaf is kept here
  * rather than in shared/src/test so that fastLinkJS does not fail with unresolved symbol errors.
  *
  * no JS/Native equivalent (concrete blocker).
  */
class WithClasspathJvmTest extends kyo.test.Test[Any]:

    // jvmOnly: temp directories (java.nio.file.Files.createTempDirectory) and java.io.File
    // are not available in Scala.js or Scala Native.
    "Leaf 6 (JVM): withClasspath(roots, Present(cacheDir)) writes snapshot on miss, reads on hit" in {
        val tmpDir = java.nio.file.Files.createTempDirectory("kyo-wc-leaf6-").toAbsolutePath.toString
        // Discover kyo-tasty-fixtures from the JVM classpath (the smallest available fixtures jar/dir).
        val cpRoots: Seq[String] =
            sys.props
                .getOrElse("java.class.path", "")
                .split(java.io.File.pathSeparatorChar)
                .filter(p => p.contains("kyo-tasty-fixtures") && (p.endsWith(".jar") || p.endsWith("/classes")))
                .toSeq
        // Fall back to all classpath entries if the fixtures jar is not separately discoverable.
        val roots: Seq[String] =
            if cpRoots.nonEmpty then cpRoots
            else
                sys.props
                    .getOrElse("java.class.path", "")
                    .split(java.io.File.pathSeparatorChar)
                    .filter(p =>
                        val f = new java.io.File(p)
                        f.exists && ((f.isFile && p.endsWith(".jar")) || (f.isDirectory))
                    )
                    .take(1)
                    .toSeq
        Abort.run[TastyError](
            Tasty.withClasspath(roots, Maybe.Present(tmpDir)):
                Tasty.classpath.map(_.symbols.size)
            .flatMap: n1 =>
                Tasty.withClasspath(roots, Maybe.Present(tmpDir)):
                    Tasty.classpath.map(_.symbols.size)
                .map: n2 =>
                    val krflFiles = new java.io.File(tmpDir).listFiles()
                    val krflCount = if krflFiles == null then 0 else krflFiles.count(_.getName.endsWith(".krfl"))
                    assert(krflCount >= 1, s"at least one .krfl file must be written to $tmpDir; got $krflCount")
                    assert(n1 == n2, s"both withClasspath calls must return same symbol count; got $n1 vs $n2")
                    succeed
        ).map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
    }

end WithClasspathJvmTest
