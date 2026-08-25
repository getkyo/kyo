package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Concurrency fidelity for annotation fully-qualified name resolution.
  *
  * Cross-file references to symbols absent from the classpath (e.g. `scala.deprecated` when scala-library is not a
  * root) are decoded per file with negative SymbolIds from a per-file counter that always starts at -2, so distinct
  * files reuse the same negId values for different fully-qualified names. finalizeMerge must give each unresolved
  * reference a classpath-unique id; otherwise the per-file negIds collide in the shared
  * `unresolvedFullNameByNegId` map and the surviving fully-qualified name depends on the non-deterministic order in
  * which the concurrent decoders' results reach the merger.
  *
  * The first test's roots exclude scala-library so every `scala.*` annotation takes the unresolved path; the second
  * includes it to guard address-based tycon resolution against the per-scope jar-pool race under concurrent loads.
  * JVM-only (needs real filesystem roots and concurrency control); the embedded path is covered by
  * AnnotationFidelityTest and the deterministic ClasspathOrchestratorNegIdTest.
  */
class AnnotationFidelityConcurrencyTest extends kyo.test.Test[Any]:

    "symbolsAnnotatedWith(scala.deprecated) resolves identically across concurrent loads without scala-library" in {
        import Tasty.Name.asString
        // standard minus scala-library: many files, all scala.* refs unresolved -> abundant cross-file negId collisions.
        val roots = TestClasspaths.kyoTasty ++ TestClasspaths.kyoData ++ TestClasspaths.kyoTastyFixtures
        System.availableProcessors.map { cpus =>
            val concurrency = cpus.max(2)
            Kyo.foreach(1 to 50) { _ =>
                Scope.run(Abort.run[TastyError](
                    ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, concurrency)
                )).map {
                    case Result.Success(cp) => cp.symbolsAnnotatedWith("scala.deprecated").map(_.name.asString).toList.sorted
                    case other              => List(s"load-failed: $other")
                }
            }.map { perLoadNames =>
                val distinct = perLoadNames.distinct
                // A single set of bytes must resolve to a single annotation set, independent of the concurrent
                // decoders' merge order. Before the fix the per-file negId collisions made this vary (the surviving
                // fully-qualified name for a reused negId depended on merge order), so the count drifted across loads.
                assert(
                    distinct.size == 1,
                    s"symbolsAnnotatedWith(scala.deprecated) must be identical across all 50 loads at " +
                        s"concurrency=$concurrency; got ${distinct.size} distinct results: $distinct"
                )
                // And the resolution must be correct: the @deprecated top-level fixture method is found by name.
                assert(
                    distinct.head.contains("deprecatedTopLevel"),
                    s"expected the @deprecated fixture method to resolve to scala.deprecated; got ${distinct.head}"
                )
                succeed
            }
        }
    }

    "symbolsAnnotatedWith(scala.annotation.tailrec) resolves >= 1 across concurrent loads with scala-library" in {
        // Regression guard for computeFullName determinism under concurrent merge. scala-library pickles nested PACKAGE
        // nodes and the merge writes a collapsed package's owner last-write-wins in decode-arrival order, so
        // scala.annotation can end up owned by a same-prefix scala package. Query-side computeFullName must stop at the
        // first Package like the registration side, else the prefix doubles ("scala.scala.annotation.tailrec") and
        // symbolsAnnotatedWith intermittently returns 0 (the linux-x64 JVM TailrecAnnotationTest failure). Concurrency
        // stresses decode-arrival order; every load must resolve the full stdlib @tailrec set.
        val roots = TestClasspaths.standard
        System.availableProcessors.map { cpus =>
            val concurrency = cpus.max(2).min(4)
            Async.foreach(Chunk.fill(24)(()), concurrency) { _ =>
                Scope.run(Abort.run[TastyError](
                    ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, concurrency)
                )).map {
                    case Result.Success(cp) => cp.symbolsAnnotatedWith("scala.annotation.tailrec").size
                    case other              => -1
                }
            }.map { counts =>
                assert(
                    counts.forall(_ >= 1),
                    s"every concurrent load must resolve >= 1 @tailrec symbol; a 0 is the computeFullName " +
                        s"prefix-doubling race. got ${counts.toList}"
                )
                succeed
            }
        }
    }

end AnnotationFidelityConcurrencyTest
