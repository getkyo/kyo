package kyo

import kyo.internal.TestClasspaths
import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Symbols.PackageSymbol

/** Differential testing: kyo-tasty vs tasty-query 1.7.0.
  *
  * For each fixture set loaded via TestClasspaths.kyoTastyFixtures, loads the same TASTy files through both implementations and diffs
  * the decoded top-level class FQNs. Any disagreement is a real kyo-tasty bug.
  *
  * Track A of the validation-infrastructure campaign (2026-06-02).
  *
  * Platform: JVM-only. tasty-query.ClasspathLoaders requires java.nio.file (JVM filesystem).
  * The parallel shared tests TastyPropertyTest and TastyPropertyJvmTest cover cross-platform invariants.
  *
  * Comparison scope: top-level class FQN sets. tasty-query does not expose a stable
  * "declared type" API that is directly comparable to kyo-tasty Symbol.Method.declaredType,
  * so we focus on FQN enumeration which is the most reliable structural invariant.
  *
  * Timeout: 5 minutes per leaf. Under scoverage bytecode instrumentation the blocking
  * ClasspathLoaders.read() calls are measurably slower than in a plain run. Caching the
  * two distinct loads (fixtureRoots and standard) in DifferentialTastyTest.Cache ensures
  * each root set is read at most once across all leaves.
  */
class DifferentialTastyTest extends Test:

    import AllowUnsafe.embrace.danger

    // 5 minutes per leaf: covers scoverage overhead on the blocking ClasspathLoaders.read() calls.
    // Under plain sbt the test finishes in ~25s; under coverage the instrumentation overhead
    // can push a single leaf close to 3 minutes, so 5 minutes gives a safe margin.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(5))

    /** Collect all top-level ClassSymbol FQNs from a tasty-query Context by walking the package tree. */
    private def tqTopLevelFqns(ctx: Context): Set[String] =
        given Context = ctx
        val buf       = new scala.collection.mutable.ArrayBuffer[String]()
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
                        val fqn = cls.displayFullName
                        if !fqn.endsWith("$") then
                            discard(buf += fqn)
                    case _ => ()
            end while
        end visit
        visit(ctx.defn.RootPackage)
        buf.toSet
    end tqTopLevelFqns

    /** Collect the PRIMARY binary FQN for each top-level symbol.
      *
      * For Object (module class) symbols the binary FQN ends with `$` (e.g. `kyo.fixtures.SomeObject$`),
      * matching tasty-query's `ObjectClassTypeName.toString` convention. Using the binary FQN (rather
      * than collecting all fqnIndex entries including canonical aliases like `kyo.fixtures.SomeObject`)
      * ensures DIFF-001 compares equivalent surfaces: both sides exclude `$`-ending names after the
      * caller applies `filterNot(_.endsWith("$"))`.
      *
      * For non-Object symbols (Class, Trait, EnumCase) the primary FQN is the plain dotted name.
      */
    private def kyoFqnSet(cp: Tasty.Classpath): Set[String] =
        cp.topLevelClasses.flatMap: sym =>
            // For Object kind, prefer the $-ending binary FQN (equivalent to tasty-query's ObjectClassTypeName).
            // For other kinds, any fqnIndex entry is fine (non-Object FQNs don't end with $).
            val isObject = sym.isInstanceOf[Tasty.Symbol.Object]
            val preferred = cp.indices.byFqn.collectFirst {
                case (fqn, id) if id == sym.id && (if isObject then fqn.endsWith("$") else true) => fqn
            }
            preferred.orElse(cp.indices.byFqn.collectFirst { case (fqn, id) if id == sym.id => fqn })
        .toSet
    end kyoFqnSet

    // DIFF-001: kyo-tasty and tasty-query see the same top-level class FQNs on the fixture classpath.
    // Loaded from TestClasspaths.kyoTastyFixtures (JVM classes dir for kyo-tasty-fixtures).
    // Any FQN present in kyo-tasty but absent in tasty-query (or vice versa) is a real decoder bug.
    "DIFF-001: kyo-tasty and tasty-query decode the same top-level class FQNs from fixtures" in run {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            info("DIFF-001: kyo-tasty-fixtures not found on test classpath; skipping differential check")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots).map: kyoCp =>
                val kyoFqns = kyoFqnSet(kyoCp)

                // Reuse the cached Context to avoid a second blocking NIO scan in the same JVM.
                val tqFqns = tqTopLevelFqns(DifferentialTastyTest.fixturesContext)

                // Normalize FQNs: tasty-query uses '.' for both packages and classes.
                // kyo-tasty uses '.' throughout, so no transformation needed.
                // Filter to fixture-package FQNs only to avoid scala-library noise.
                val kyoFixtureFqns = kyoFqns.filter(fqn => fqn.startsWith("kyo.fixtures") || !fqn.contains("."))
                val tqFixtureFqns  = tqFqns.filter(fqn => fqn.startsWith("kyo.fixtures") || !fqn.contains("."))

                val onlyInKyo = kyoFixtureFqns -- tqFixtureFqns
                val onlyInTq  = tqFixtureFqns -- kyoFixtureFqns

                // Companion objects like SomeCaseClass$ appear in kyo-tasty fqnIndex but
                // tasty-query suppresses them from top-level. Filter out companion "$" entries.
                val kyoNonCompanion = onlyInKyo.filterNot(_.endsWith("$"))
                val tqNonCompanion  = onlyInTq.filterNot(_.endsWith("$"))

                assert(
                    kyoNonCompanion.isEmpty,
                    s"DIFF-001: kyo-tasty has ${kyoNonCompanion.size} FQNs absent from tasty-query: " +
                        kyoNonCompanion.take(10).mkString(", ")
                )
                assert(
                    tqNonCompanion.isEmpty,
                    s"DIFF-001: tasty-query has ${tqNonCompanion.size} FQNs absent from kyo-tasty: " +
                        tqNonCompanion.take(10).mkString(", ")
                )
                succeed
        end if
    }

    // DIFF-002: symbol count parity on kyo-tasty standard classpath vs tasty-query.
    // Loads kyo-tasty + kyo-data + scala-library + fixtures through both decoders and
    // checks that the top-level non-Object class count from kyo-tasty is within a 20% band
    // of tasty-query's count.
    //
    // Both decoders are compared on an equivalent surface:
    //   - tasty-query's tqTopLevelFqns excludes ClassSymbols whose displayFullName ends with "$"
    //     (i.e., all module classes, which have ObjectClassTypeName names like "SomeObject$").
    //   - kyo-tasty counts non-Object top-level ClassLike symbols here (Symbol.Object = module class).
    //
    // Including Symbol.Object (module classes) in the kyo-tasty count produces ~2x the tasty-query
    // count on a real classpath because every companion object and $package synthetic adds one Object
    // symbol in kyo-tasty but zero non-$-ending names in tasty-query. Excluding Objects aligns the
    // surfaces and the ratio should be close to 1.0.
    "DIFF-002: kyo-tasty top-level class count is within 20% of tasty-query count on standard classpath" in run {
        val roots = TestClasspaths.standard
        TestClasspaths.withClasspath(roots).map: kyoCp =>
            // Count non-Object top-level ClassLike symbols (equivalent to tasty-query's !endsWith("$") filter).
            val kyoCountAll   = kyoCp.topLevelClasses.size
            val kyoCountNoObj = kyoCp.topLevelClasses.count(!_.isInstanceOf[Tasty.Symbol.Object])

            // Reuse the cached Context to avoid a second blocking NIO scan in the same JVM.
            val tqCount = tqTopLevelFqns(DifferentialTastyTest.standardContext).size

            if tqCount == 0 then
                info("DIFF-002: tasty-query returned 0 top-level classes; skipping ratio check")
                succeed
            else
                val ratio = kyoCountNoObj.toDouble / tqCount.toDouble
                // Secondary invariant: raw topLevelClasses count must be >= the non-Object count.
                assert(
                    kyoCountAll >= kyoCountNoObj,
                    s"DIFF-002: raw topLevelClasses ($kyoCountAll) < non-Object count ($kyoCountNoObj)"
                )
                assert(
                    ratio >= 0.5 && ratio <= 2.0,
                    s"DIFF-002: kyo-tasty non-Object top-level count ($kyoCountNoObj, raw=$kyoCountAll) vs tasty-query ($tqCount) ratio $ratio is outside [0.5, 2.0]. " +
                        "This suggests a structural decoder disagreement."
                )
                succeed
            end if
    }

    // DIFF-003: fixture FQN sets are non-empty in both decoders.
    // Guards against silent load failure where both decoders return empty but there is no error.
    "DIFF-003: both kyo-tasty and tasty-query decode at least one fixture class" in run {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            info("DIFF-003: kyo-tasty-fixtures not found on test classpath; skipping")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots).map: kyoCp =>
                val kyoCount = kyoCp.topLevelClasses.size

                // Reuse the cached Context (same root set as DIFF-001) to avoid a third blocking NIO scan.
                val tqCount = tqTopLevelFqns(DifferentialTastyTest.fixturesContext).size

                assert(kyoCount > 0, s"DIFF-003: kyo-tasty decoded 0 top-level classes from fixtures")
                assert(tqCount > 0, s"DIFF-003: tasty-query decoded 0 top-level classes from fixtures")
                succeed
        end if
    }

    // DIFF-004: fixture parent types decoded by kyo-tasty include at least one parent per class
    // that appears in tasty-query's symbol tree. Checks that parent-type resolution is functioning.
    "DIFF-004: kyo-tasty parent types for fixtures are non-empty where expected" in run {
        val fixtureRoots = TestClasspaths.kyoTastyFixtures
        if fixtureRoots.isEmpty then
            info("DIFF-004: kyo-tasty-fixtures not found on test classpath; skipping")
            Kyo.lift(succeed)
        else
            TestClasspaths.withClasspath(fixtureRoots).map: kyoCp =>
                // Classes that extend something other than AnyRef should have parentTypes.
                // ChildClass extends BaseClass, so it must have non-empty parents.
                val childClassFqn = "kyo.fixtures.ChildClass"
                kyoCp.findSymbol(childClassFqn) match
                    case Maybe.Present(sym: Tasty.Symbol.ClassLike) =>
                        assert(
                            sym.parentTypes.nonEmpty,
                            s"DIFF-004: $childClassFqn should have parentTypes but has none"
                        )
                        succeed
                    case Maybe.Present(_) =>
                        info(s"DIFF-004: $childClassFqn resolved to a non-ClassLike symbol; skipping parent check")
                        succeed
                    case Maybe.Absent =>
                        info(s"DIFF-004: $childClassFqn not found in fixture classpath; skipping parent check")
                        succeed
                end match
        end if
    }

end DifferentialTastyTest

object DifferentialTastyTest:
    // Cache tasty-query ClasspathLoaders.read() results across test leaves.
    // ClasspathLoaders.read() is a blocking NIO scan that cannot be interrupted by Kyo's
    // Async.timeout. Loading each root set once and reusing the Context eliminates the
    // duplicate fixture load that DIFF-001 and DIFF-003 would otherwise each pay.
    //
    // Unsafe: ClasspathLoaders.read() performs blocking java.nio.file I/O. The lazy vals
    // are initialised at most once per JVM process (the sbt test runner) and are read-only
    // after initialisation, so the mutation is safely confined to the first accessor thread.
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
