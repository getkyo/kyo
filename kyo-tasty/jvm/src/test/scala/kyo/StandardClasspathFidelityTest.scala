package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Characteristics of the live JVM standard classpath: given-instance count baseline, cacheDir write-collision handling, cold-init wall
  * time on a real 80k-symbol corpus, and JPMS module count via jrt:/.
  */
class StandardClasspathFidelityTest extends kyo.test.Test[Any]:

    "allSymbols.count(isGiven) ~= 570 on standard classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { cp =>
            val count = cp.symbols.count(_.isGiven)
            assert(
                count >= 555 && count <= 585,
                s"Expected ~570 given instances on standard classpath; found $count"
            )
            succeed
        }
    }

    "two concurrent cold-init writers to same cacheDir produce one .krfl file" in {
        val cacheDir = TestClasspaths2.createTempDir("kyo-df2-concurrent-writers")
        val roots    = TestClasspaths2.standardRoots
        Async.zip(
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath),
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath)
        ).map { (cp1, cp2) =>
            val krflFiles = TestClasspaths2.listFilesWithSuffix(cacheDir, ".krfl")
            assert(
                krflFiles.length == 1,
                s"Expected exactly 1 .krfl file after concurrent writes; found ${krflFiles.length}"
            )
            assert(
                cp1.symbols.size == cp2.symbols.size,
                s"Concurrent-written snapshots produce different symbol counts: ${cp1.symbols.size} vs ${cp2.symbols.size}"
            )
            assert(
                cp1.symbols.size > 0,
                s"Expected > 0 symbols after concurrent cold-init; got ${cp1.symbols.size}"
            )
            succeed
        }
    }

    "standard 81,569-symbol classpath cold-init median < 5,000 ms" in {
        val roots = TestClasspaths2.standardRoots
        def timedLoad: Duration < (Async & Abort[TastyError]) =
            Clock.nowMonotonic.map { start =>
                TestClasspaths.withClasspath(roots)(Tasty.classpath).map { cp =>
                    Clock.nowMonotonic.map { end =>
                        assert(cp.symbols.size >= 81000, s"Expected >= 81,000 symbols; got ${cp.symbols.size}")
                        end - start
                    }
                }
            }
        end timedLoad
        timedLoad.map { t1 =>
            timedLoad.map { t2 =>
                timedLoad.map { t3 =>
                    val times  = Chunk(t1, t2, t3).sortBy(_.toMillis)
                    val median = times(1)
                    assert(
                        median < 5.seconds,
                        s"Expected cold-init median < 5 seconds on standard classpath; got ${median.toMillis} ms (runs: ${t1.toMillis}, ${t2.toMillis}, ${t3.toMillis})"
                    )
                    succeed
                }
            }
        }
    }

    "JPMS module count == 69 on platform-modules classpath" in {
        TestClasspaths2.standardWithPlatformModules.map { cp =>
            val count = cp.indices.modulesIndex.size
            assert(
                count == 69,
                s"Expected exactly 69 JPMS modules; got $count"
            )
            succeed
        }
    }

end StandardClasspathFidelityTest
