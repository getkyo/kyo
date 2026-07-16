package kyo

/** JVM-only classpath cache test: discovers fixture jars from `java.class.path`, which has no JS/Native equivalent. */
class WithClasspathCacheDirTest extends kyo.test.Test[Any]:

    "withClasspath(roots, Present(cacheDir)) writes snapshot on miss, reads on hit" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-wc-cachedir")).map { tmpDirPath =>
                val tmpDir = tmpDirPath.toString
                System.property[String]("java.class.path").map { maybeCp =>
                    val cpRaw = maybeCp.getOrElse("")
                    // Discover the internal fixtures from the JVM classpath (the smallest available fixtures jar/dir).
                    val cpRoots: Seq[String] =
                        cpRaw
                            .split(Path.pathSeparator)
                            .filter(p =>
                                (p.contains("kyo-tasty/fixtures") || p.contains("kyo-tasty-fixtures-internal")) &&
                                    (p.endsWith(".jar") || p.endsWith("/classes"))
                            )
                            .toSeq
                    // Fall back to all classpath entries if the fixtures jar is not separately discoverable.
                    val rootsK: Chunk[Path] < (Sync & Abort[FileException]) =
                        if cpRoots.nonEmpty then Chunk.from(cpRoots).map(p => Path(p))
                        else
                            val candidates = cpRaw.split(Path.pathSeparator).toSeq
                            Kyo.collect(Chunk.from(candidates)) { p =>
                                val pp = Path(p)
                                Path.runReadOnly {
                                    pp.isRegularFile.map { isFile =>
                                        pp.isDirectory.map { isDir =>
                                            if (isFile && p.endsWith(".jar")) || isDir then Maybe(pp)
                                            else Maybe.Absent
                                        }
                                    }
                                }
                            }.map(_.take(1))
                    rootsK.map { rootsChunk =>
                        val roots = rootsChunk.map(_.toString).toSeq
                        Abort.run[TastyError](
                            Tasty.withClasspath(roots, Maybe.Present(tmpDir)) {
                                Tasty.classpath.map(_.symbols.size)
                            }.map { n1 =>
                                Tasty.withClasspath(roots, Maybe.Present(tmpDir)) {
                                    Tasty.classpath.map(_.symbols.size)
                                }.map { n2 =>
                                    Path.runReadOnly(tmpDirPath.list("*.krfl")).map { krflFiles =>
                                        val krflCount = krflFiles.size
                                        assert(krflCount >= 1, s"at least one .krfl file must be written to $tmpDir; got $krflCount")
                                        assert(n1 == n2, s"both withClasspath calls must return same symbol count; got $n1 vs $n2")
                                        succeed
                                    }
                                }
                            }
                        ).map {
                            case Result.Success(r) => r
                            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                            case Result.Panic(t)   => throw t
                        }
                    }
                }
            }
        }
    }

end WithClasspathCacheDirTest
