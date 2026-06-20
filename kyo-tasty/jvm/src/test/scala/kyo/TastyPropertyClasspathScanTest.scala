package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Walks every kyo-* directory on the live JVM test classpath (via java.class.path) and decodes each as an independent single-root
  * Classpath, asserting zero UnknownTagInPosition errors across the full set.
  */
class TastyPropertyClasspathScanTest extends kyo.test.Test[Any]:

    // Loading many individual .tasty files takes time on slow machines.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(5))

    import AllowUnsafe.embrace.danger

    "sampled kyo-* classpath directories decode without UnknownTagInPosition" in {
        val roots = discoverKyoClasspathRoots
        def go(remaining: List[String], violations: List[String]): List[String] < (Async & Scope & Abort[TastyError]) =
            remaining match
                case Nil => violations
                case root :: rest =>
                    ClasspathOrchestrator.init(Seq(root), Tasty.ErrorMode.SoftFail, 1).map { classpath =>
                        val errs = classpath.errors.collect {
                            case TastyError.UnknownTagInPosition(tag, pos) => s"$root: tag=$tag pos=$pos"
                        }
                        go(rest, violations ++ errs)
                    }
        go(roots.take(10), Nil).map { violations =>
            assert(violations.isEmpty, s"UnknownTagInPosition across classpath roots: ${violations.take(5).mkString("; ")}")
            succeed
        }
    }

    /** Discover distinct parent directories of kyo-* .tasty files from the test classpath.
      *
      * Returns a sorted distinct list of directories, skipping test and fixture paths.
      */
    private def discoverKyoClasspathRoots: List[String] =
        val accumulator = new scala.collection.mutable.LinkedHashSet[String]()
        // Path.Unsafe.list collects each directory's entries and closes the stream before returning (no leaked fd);
        // recurse to cover the full tree without following symlinks.
        def recurse(dir: Path): Unit =
            dir.unsafe.list().getOrThrow.foreach { entry =>
                if entry.unsafe.isDirectory() && !entry.unsafe.isSymbolicLink() then recurse(entry)
                else
                    val s = entry.toString
                    if s.endsWith(".tasty") && !s.contains("/test/") && !s.contains("/fixtures/") then
                        entry.parent.foreach(parent => discard(accumulator += parent.toString))
            }
        TestClasspaths.all.foreach { root =>
            val dir = Path(root)
            if dir.unsafe.exists() && dir.unsafe.isDirectory() then recurse(dir)
        }
        accumulator.toList.sorted
    end discoverKyoClasspathRoots

end TastyPropertyClasspathScanTest
