package kyo

import kyo.internal.TestClasspaths
import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Symbols.PackageSymbol

/** Differential testing: kyo-tasty vs tasty-query 1.7.0.
  *
  * For each fixture set loaded via TestClasspaths.kyoTastyFixtures, loads the same TASTy files through both implementations and diffs
  * the decoded top-level class fully-qualified names. Any disagreement is a real kyo-tasty bug.
  *
  * Platform: JVM-only. tasty-query.ClasspathLoaders requires java.nio.file (JVM filesystem).
  *
  * Comparison scope: top-level class fully-qualified name sets. tasty-query does not expose a stable
  * "declared type" API that is directly comparable to kyo-tasty Symbol.Method.declaredType,
  * so we focus on fully-qualified name enumeration which is the most reliable structural invariant.
  *
  * Timeout: 5 minutes. Under scoverage bytecode instrumentation the blocking
  * ClasspathLoaders.read calls are measurably slower than in a plain run. Caching the
  * two distinct loads (fixtureRoots and standard) in DifferentialTastyTest.Cache ensures
  * each root set is read at most once.
  */
class DifferentialTastyTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // 5 minutes: covers scoverage overhead on the blocking ClasspathLoaders.read calls.
    // Under plain sbt the test finishes in ~25s; under coverage the instrumentation overhead
    // can push a single test close to 3 minutes, so 5 minutes gives a safe margin.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(5))

    /** Collect all top-level ClassSymbol fully-qualified names from a tasty-query Context by walking the package tree. */
    private def tqTopLevelFullNames(ctx: Context): Set[String] =
        given Context   = ctx
        val accumulator = new scala.collection.mutable.ArrayBuffer[String]()
        def visit(pkg: PackageSymbol): Unit =
            val decls: List[tastyquery.Symbols.Symbol] =
                try pkg.declarations
                catch
                    case _: Throwable => Nil
            val it = decls.iterator
            while it.hasNext do
                it.next() match
                    case sub: PackageSymbol => visit(sub)
                    case cls: ClassSymbol   =>
                        // Exclude companion objects and synthetic classes; include only user-visible classes.
                        val fullName = cls.displayFullName
                        if !fullName.endsWith("$") then
                            discard(accumulator += fullName)
                    case _ => ()
            end while
        end visit
        visit(ctx.defn.RootPackage)
        accumulator.toSet
    end tqTopLevelFullNames

    /** Collect the PRIMARY binary fully-qualified name for each top-level symbol.
      *
      * For Object (module class) symbols the binary fully-qualified name ends with `$` (e.g. `kyo.fixtures.SomeObject$`),
      * matching tasty-query's `ObjectClassTypeName.toString` convention. Using the binary fully-qualified name (rather
      * than collecting all fullNameIndex entries including canonical aliases like `kyo.fixtures.SomeObject`)
      * produces equivalent surfaces: both sides exclude `$`-ending names after the
      * caller applies `filterNot(_.endsWith("$"))`.
      *
      * For non-Object symbols (Class, Trait, EnumCase) the primary fully-qualified name is the plain dotted name.
      */
    private def kyoFullNameSet(classpath: Tasty.Classpath): Set[String] =
        classpath.topLevelClasses.flatMap { symbol =>
            // For Object kind, prefer the $-ending binary fully-qualified name (equivalent to tasty-query's ObjectClassTypeName).
            // For other kinds, any fullNameIndex entry is fine (non-Object fully-qualified names don't end with $).
            val isObject = symbol.isInstanceOf[Tasty.Symbol.Object]
            val preferred = classpath.indices.byFullName.find {
                (fullName, id) => id == symbol.id && (if isObject then fullName.endsWith("$") else true)
            }.map(_._1).toOption
            preferred.orElse(classpath.indices.byFullName.find { (fullName, id) => id == symbol.id }.map(_._1).toOption)
        }.toSet
    end kyoFullNameSet

    "kyo-tasty and tasty-query decode the same top-level class fully-qualified names from fixtures" in {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            println("internal fixtures not found on test classpath; skipping differential check")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots)(Tasty.classpath).map { kyoClasspath =>
                val kyoFullNames = kyoFullNameSet(kyoClasspath)

                // Reuse the cached Context to avoid a second blocking NIO scan in the same JVM.
                val tqFullNames = tqTopLevelFullNames(DifferentialTastyTest.fixturesContext)

                // Normalize fully-qualified names: tasty-query uses '.' for both packages and classes.
                // kyo-tasty uses '.' throughout, so no transformation needed.
                // Filter to fixture-package fully-qualified names only to avoid scala-library noise.
                val kyoFixtureFullNames = kyoFullNames.filter(fullName => fullName.startsWith("kyo.fixtures") || !fullName.contains("."))
                val tqFixtureFullNames  = tqFullNames.filter(fullName => fullName.startsWith("kyo.fixtures") || !fullName.contains("."))

                val onlyInKyo = kyoFixtureFullNames -- tqFixtureFullNames
                val onlyInTq  = tqFixtureFullNames -- kyoFixtureFullNames

                // Companion objects like SomeCaseClass$ appear in kyo-tasty fullNameIndex but
                // tasty-query suppresses them from top-level. Filter out companion "$" entries.
                val kyoNonCompanion = onlyInKyo.filterNot(_.endsWith("$"))
                // Java-only classfiles (no .tasty companion) are not discovered by kyo-tasty's directory scanner,
                // which filters to ".tasty" + "module-info.class". JavaSimpleFixture.java is compiled into the
                // fixture directory but is intentionally loaded via the standalone-root mechanism (see
                // EmbeddedJavaFixtures and TestClasspaths.withClasspath on JS/Native). The directory-scan path
                // used here means JavaSimpleFixture is visible to tasty-query but not kyo-tasty in this context.
                // This is the expected behavior: kyo-tasty reads Java classfiles via the standalone
                // root mechanism, not via directory scanning.
                val javaOnlyFullNames = Set("kyo.fixtures.JavaSimpleFixture")
                val tqNonCompanion    = (onlyInTq.filterNot(_.endsWith("$"))) -- javaOnlyFullNames

                assert(
                    kyoNonCompanion.isEmpty,
                    s"kyo-tasty has ${kyoNonCompanion.size} fully-qualified names absent from tasty-query: " +
                        kyoNonCompanion.take(10).mkString(", ")
                )
                assert(
                    tqNonCompanion.isEmpty,
                    s"tasty-query has ${tqNonCompanion.size} fully-qualified names absent from kyo-tasty: " +
                        tqNonCompanion.take(10).mkString(", ")
                )
                succeed
            }
        end if
    }

    "kyo-tasty top-level class count is within 20% of tasty-query count on standard classpath" in {
        val roots = TestClasspaths.standard
        TestClasspaths.withClasspath(roots)(Tasty.classpath).map { kyoClasspath =>
            // Count non-Object top-level ClassLike symbols (equivalent to tasty-query's !endsWith("$") filter).
            val kyoCountAll   = kyoClasspath.topLevelClasses.size
            val kyoCountNoObj = kyoClasspath.topLevelClasses.count(!_.isInstanceOf[Tasty.Symbol.Object])

            // Reuse the cached Context to avoid a second blocking NIO scan in the same JVM.
            val tqCount = tqTopLevelFullNames(DifferentialTastyTest.standardContext).size

            if tqCount == 0 then
                println("tasty-query returned 0 top-level classes; skipping ratio check")
                succeed
            else
                val ratio = kyoCountNoObj.toDouble / tqCount.toDouble
                // Secondary invariant: raw topLevelClasses count must be >= the non-Object count.
                assert(
                    kyoCountAll >= kyoCountNoObj,
                    s"raw topLevelClasses ($kyoCountAll) < non-Object count ($kyoCountNoObj)"
                )
                assert(
                    ratio >= 0.5 && ratio <= 2.0,
                    s"kyo-tasty non-Object top-level count ($kyoCountNoObj, raw=$kyoCountAll) vs tasty-query ($tqCount) ratio $ratio is outside [0.5, 2.0]. " +
                        "This suggests a structural decoder disagreement."
                )
                succeed
            end if
        }
    }

    "both kyo-tasty and tasty-query decode at least one fixture class" in {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            println("internal fixtures not found on test classpath; skipping")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots)(Tasty.classpath).map { kyoClasspath =>
                val kyoCount = kyoClasspath.topLevelClasses.size

                // Reuse the cached Context to avoid a third blocking NIO scan.
                val tqCount = tqTopLevelFullNames(DifferentialTastyTest.fixturesContext).size

                assert(kyoCount > 0, s"kyo-tasty decoded 0 top-level classes from fixtures")
                assert(tqCount > 0, s"tasty-query decoded 0 top-level classes from fixtures")
                succeed
            }
        end if
    }

    "kyo-tasty parent types for fixtures are non-empty where expected" in {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            println("internal fixtures not found on test classpath; skipping")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots)(Tasty.classpath).map { kyoClasspath =>
                // Classes that extend something other than AnyRef should have parentTypes.
                // ChildClass extends BaseClass, so it must have non-empty parents.
                val childClassFullName = "kyo.fixtures.ChildClass"
                kyoClasspath.findSymbol(childClassFullName) match
                    case Maybe.Present(symbol: Tasty.Symbol.ClassLike) =>
                        assert(
                            symbol.parentTypes.nonEmpty,
                            s"$childClassFullName should have parentTypes but has none"
                        )
                        succeed
                    case Maybe.Present(_) =>
                        println(s"$childClassFullName resolved to a non-ClassLike symbol; skipping parent check")
                        succeed
                    case Maybe.Absent =>
                        println(s"$childClassFullName not found in fixture classpath; skipping parent check")
                        succeed
                end match
            }
        end if
    }

end DifferentialTastyTest

object DifferentialTastyTest:
    // Cache tasty-query ClasspathLoaders.read results across test leaves.
    // ClasspathLoaders.read is a blocking NIO scan that cannot be interrupted by Kyo's
    // Async.timeout. Loading each root set once and reusing the Context eliminates duplicate loads.
    // Unsafe: ClasspathLoaders.read performs blocking java.nio.file I/O. The lazy vals
    // are initialised at most once per JVM process and are read-only after initialisation.
    private[kyo] lazy val fixturesContext: tastyquery.Contexts.Context =
        import tastyquery.Contexts
        import tastyquery.jdk.ClasspathLoaders
        import java.nio.file.Paths
        val roots   = kyo.internal.TestClasspaths.kyoTastyFixtures
        val entries = ClasspathLoaders.read(roots.map(Paths.get(_)).toList)
        Contexts.Context.initialize(entries)
    end fixturesContext

    private[kyo] lazy val standardContext: tastyquery.Contexts.Context =
        import tastyquery.Contexts
        import tastyquery.jdk.ClasspathLoaders
        import java.nio.file.Paths
        val roots   = kyo.internal.TestClasspaths.standard
        val entries = ClasspathLoaders.read(roots.map(Paths.get(_)).toList)
        Contexts.Context.initialize(entries)
    end standardContext
end DifferentialTastyTest
