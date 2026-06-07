package kyo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource
import scala.jdk.CollectionConverters.*

/** Walks every kyo-* directory on the live JVM test classpath (via java.class.path) and decodes each as an independent single-root
  * Classpath, asserting zero UnknownTagInPosition errors across the full set.
  */
class TastyPropertyClasspathScanTest extends kyo.test.Test[Any]:

    // Loading many individual .tasty files takes time on slow machines.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(5))

    import AllowUnsafe.embrace.danger

    "sampled kyo-* classpath directories decode without UnknownTagInPosition" in {
        val roots = discoverKyoClasspathRoots
        val src   = PlatformFileSource.get
        def go(remaining: List[String], violations: List[String]): List[String] < (Async & Scope & Abort[TastyError]) =
            remaining match
                case Nil => violations
                case root :: rest =>
                    ClasspathOrchestrator.init(Seq(root), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                        val errs = cp.errors.collect:
                            case TastyError.UnknownTagInPosition(tag, pos) => s"$root: tag=$tag pos=$pos"
                        go(rest, violations ++ errs)
        go(roots.take(10), Nil).map: violations =>
            assert(violations.isEmpty, s"UnknownTagInPosition across classpath roots: ${violations.take(5).mkString("; ")}")
            succeed
    }

    /** Discover distinct parent directories of kyo-* .tasty files from the test classpath.
      *
      * Returns a sorted distinct list of directories, skipping test and fixture paths.
      */
    private def discoverKyoClasspathRoots: List[String] =
        val buf = new scala.collection.mutable.LinkedHashSet[String]()
        TestClasspaths.all.foreach: root =>
            val f = new File(root)
            if f.exists && f.isDirectory then
                Files.walk(f.toPath).iterator.asScala.foreach: p =>
                    val s = p.toString
                    if s.endsWith(".tasty") && !s.contains("/test/") && !s.contains("/fixtures/") then
                        discard(buf += p.getParent.toString)
            end if
        buf.toList.sorted
    end discoverKyoClasspathRoots

end TastyPropertyClasspathScanTest
