package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Characteristics of the live JVM standard classpath: given-instance count baseline, cacheDir write-collision handling, cold-init wall
  * time on a real 80k-symbol corpus, and JPMS module count via jrt:/.
  */
class StandardClasspathFidelityTest extends kyo.test.Test[Any]:

    // Each of these four leaves cold-inits the standard 81k-symbol classpath (one also walks java.base via
    // jrt:/). A single leaf is about a second uncontended, but by default the four run concurrently, and on a
    // contended CI box (4 vCPUs, two parallel forks) that pile-up of concurrent decodes starves every leaf
    // past the timeout. The leaves also share the jar reader pool (computeIfAbsent) and the jrt:/ singleton, so
    // concurrency serializes them on those too. It is not heap pressure (peak is about 1.6GB against the 5GB
    // fork cap). Run the leaves sequentially so each gets full CPU and no shared-resource contention.
    override def config = super.config.sequential

    // jrt:/ cold loads can still be slow on a contended runner; keep a generous per-leaf budget.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    "allSymbols.count(isGiven) ~= 570 on standard classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val count = classpath.symbols.count(_.isGiven)
            assert(
                count >= 555 && count <= 585,
                s"Expected ~570 given instances on standard classpath; found $count"
            )
            succeed
        }
    }

    "two concurrent cold-init writers to same cacheDir produce one .krfl file" in {
        val cacheDir = TestClasspaths2.createTempDir("kyo-concurrent-writers")
        val roots    = TestClasspaths2.standardRoots
        Async.zip(
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath),
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath)
        ).map { (classpath1, classpath2) =>
            val krflFiles = TestClasspaths2.listFilesWithSuffix(cacheDir, ".krfl")
            assert(
                krflFiles.length == 1,
                s"Expected exactly 1 .krfl file after concurrent writes; found ${krflFiles.length}"
            )
            assert(
                classpath1.symbols.size == classpath2.symbols.size,
                s"Concurrent-written snapshots produce different symbol counts: ${classpath1.symbols.size} vs ${classpath2.symbols.size}"
            )
            assert(
                classpath1.symbols.size > 0,
                s"Expected > 0 symbols after concurrent cold-init; got ${classpath1.symbols.size}"
            )
            succeed
        }
    }

    "standard 81,569-symbol classpath cold-init median < 5,000 ms" in {
        val roots = TestClasspaths2.standardRoots
        def timedLoad: Duration < (Async & Abort[TastyError]) =
            Clock.nowMonotonic.map { start =>
                TestClasspaths.withClasspath(roots)(Tasty.classpath).map { classpath =>
                    Clock.nowMonotonic.map { end =>
                        // >= 80,000: the standard classpath measures ~80,321 after finalizeMerge's package dedup
                        // removes the per-file duplicate Package partials (was ~81,569 with duplicates). Real classes/members are
                        // unaffected (unioned into the canonical package); only duplicate Package headers are collapsed.
                        assert(classpath.symbols.size >= 80000, s"Expected >= 80,000 symbols; got ${classpath.symbols.size}")
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

    "JPMS module count >= 69 on platform-modules classpath" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            val count = classpath.indices.modulesIndex.size
            assert(
                count >= 69,
                s"Expected at least 69 JPMS modules (baseline: Java 21); got $count"
            )
            succeed
        }
    }

end StandardClasspathFidelityTest
