package kyo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import scala.jdk.CollectionConverters.*

/** Property test: every .tasty file from kyo-* modules on the classpath decodes without sentinels.
  *
  * Walks all .tasty files reachable from `TestClasspaths.all` kyo-* entries, decodes a batch as single-root classpaths, and asserts:
  *   - Zero unknown tags: no `TastyError.UnknownTagInPosition` in cp.errors.
  *   - Zero Named(-1): no method declaredType reachable from cp.allMethods carries SymbolId(-1).
  *
  * This catches the long tail of "fixture didn't exercise this tag in this position" bugs by covering all kyo-* .tasty files rather than
  * hand-curated fixtures.
  *
  * Proposal 5 of Phase 2.04-strict (HARD RULE 13). Lives in jvm/src/test because classpath discovery is JVM-only.
  */
class TastyPropertyTest extends Test:

    // Allow a longer timeout: loading many individual .tasty files takes time on slow machines.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(5))

    import AllowUnsafe.embrace.danger

    // Leaf 1: kyo-tasty .tasty files decode with zero unknown-tag errors (batch load)
    "PROP-001: kyo-tasty module .tasty files decode with zero UnknownTagInPosition errors" in run {
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            val unknownTag = cp.errors.collect:
                case TastyError.UnknownTagInPosition(tag, pos) =>
                    s"tag=$tag position=$pos"
            assert(
                unknownTag.isEmpty,
                s"kyo-tasty module produced UnknownTagInPosition errors: ${unknownTag.take(3).mkString(", ")}"
            )
            succeed
    }

    // Leaf 2: kyo-tasty module: zero Named(-1) in allMethods declaredType
    "PROP-002: kyo-tasty module: zero Named(-1) in allMethods declaredType" in run {
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            import kyo.internal.tasty.symbol.SymbolId.value as idValue
            var sentinelCount   = 0
            val sampleViolators = new scala.collection.mutable.ArrayBuffer[String]()
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: dt =>
                    dt.foreach:
                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                            sentinelCount += 1
                            if sampleViolators.size < 5 then
                                import Tasty.Name.asString
                                discard(sampleViolators += m.name.asString)
                        case _ => ()
            assert(
                sentinelCount == 0,
                s"kyo-tasty module: found $sentinelCount Named(-1) sentinels in allMethods declaredType. " +
                    s"Sample: ${sampleViolators.mkString(", ")}"
            )
            succeed
    }

    // Leaf 3: sampled kyo-* classpath directories decode without UnknownTagInPosition
    // Walk all kyo-* classpath directories; load each as an independent classpath; assert no unknown tags.
    "PROP-003: sampled kyo-* classpath directories decode without UnknownTagInPosition" in run {
        val roots = discoverKyoClasspathRoots
        def go(remaining: List[String], violations: List[String]): List[String] < (Async & Scope & Abort[TastyError]) =
            remaining match
                case Nil => violations
                case root :: rest =>
                    Tasty.Classpath.init(Seq(root), Tasty.ErrorMode.SoftFail).flatMap: cp =>
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

end TastyPropertyTest
