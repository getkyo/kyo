import sbt.*
import sbt.Keys.*
import sbt.internal.BuildDependencies
import sbt.internal.util.{ SourcePosition, FilePosition, LinePosition, RangePosition, LineRange }
import java.io.File
import scala.sys.process.*

/** Unified test command for CI and local use.
  *
  * Usage: testKyo diff vs origin/main, all platforms, current Scala testKyo JVM diff vs origin/main, JVM only testKyo --all full test, all
  * platforms testKyo --all JVM full test, JVM only testKyo --scala 2.13.18 JVM diff, Scala 2.13, JVM only (auto-discovers modules) testKyo
  * --all --scala 2.13.18 JVM full test, Scala 2.13, JVM only testKyo origin/feature JVM diff vs specific ref, JVM only testKyo --dry-run
  * JVM show what would run without executing
  */
object TestKyo {

    private val platformNames = Set("JVM", "JS", "Native", "Wasm")

    // Root aggregate projects: testing one runs every leaf via aggregation, so the diff
    // and full-run paths both exclude them and treat any change scoped to one as "run all".
    private val aggregateProjects = Set("kyoJVM", "kyoJS", "kyoNative", "kyoWasm")

    private def log(msg: String): Unit = println(s"[testKyo] $msg")

    /** The per-module sbt task for a phase. compile-main compiles only main sources; compile-test
      * compiles test sources (main resolved from disk); test (default) runs the tests. Running the
      * phases as separate sbt processes keeps the driver from holding a full compile heap while test
      * forks run, which is what over-commits the memory-constrained CI runners.
      */
    private def taskFor(phase: String, name: String): String = phase match {
        case "compile-main" => s"$name/Compile/compile"
        case "compile-test" => s"$name/Test/compile"
        case _              => s"$name/test"
    }

    private def phaseLabel(phase: String): String = phase match {
        case "compile-main" => "compiling main for"
        case "compile-test" => "compiling test for"
        case _              => "testing"
    }

    def command: Command = Command.args("testKyo", "") { (state, args) =>
        val isAll           = args.contains("--all")
        val isDryRun        = args.contains("--dry-run")
        val scalaIdx        = args.indexOf("--scala")
        val scalaVersionArg = if (scalaIdx >= 0 && scalaIdx + 1 < args.length) Some(args(scalaIdx + 1)) else None
        // Phase selects the per-module task: compile-main, compile-test, or test (default). CI runs the
        // three phases as separate sbt processes so the driver never holds a full compile heap while test
        // forks run (see .github/workflows/build.yml and taskFor).
        val phaseIdx = args.indexOf("--phase")
        val phase    = if (phaseIdx >= 0 && phaseIdx + 1 < args.length) args(phaseIdx + 1) else "test"

        val remaining = args
            .filterNot(a => a == "--all" || a == "--dry-run")
            .filterNot(a => a == "--scala" || (scalaIdx >= 0 && args.indexOf(a) == scalaIdx + 1))
            .filterNot(a => a == "--phase" || (phaseIdx >= 0 && args.indexOf(a) == phaseIdx + 1))

        val (platformArgs, refArgs) = remaining.partition(a => platformNames.exists(_.equalsIgnoreCase(a)))
        val platform                = platformArgs.headOption.map(a => platformNames.find(_.equalsIgnoreCase(a)).get)
        val baseRef                 = refArgs.headOption.getOrElse("origin/main")

        val extracted = Project.extract(state)
        val scala3    = extracted.get(scalaVersion)

        // Resolve --scala 2 / --scala 3 to actual versions from the build
        val scalaVersionOpt = scalaVersionArg.map(resolveScalaVersion(_, extracted))
        val runBothScala    = scalaVersionOpt.isEmpty

        log(s"scala: ${scalaVersionOpt.getOrElse(s"$scala3 + 2.x")}, platform: ${platform.getOrElse("all")}, mode: ${if (isAll) "all"
            else s"diff vs $baseRef"}")

        // Run for the specified or primary Scala version
        val targetScala = scalaVersionOpt.getOrElse(scala3)
        val state1 = scalaVersionOpt match {
            case Some(v) if v != scala3 =>
                log(s"switching to Scala $v")
                Command.process(s"++$v", state, msg => state.log.error(msg))
            case _ => state
        }

        def runForScala(st: State, sv: String): State =
            if (isAll || scalaVersionOpt.isDefined) runAll(st, platform, sv, isDryRun, phase)
            else runDiff(st, baseRef, platform, sv, isDryRun, phase)

        val state2 = runForScala(state1, targetScala)

        if (runBothScala) {
            findScala2Versions(extracted) match {
                case Nil =>
                    log("no Scala 2.x cross-build modules found")
                    state2
                case versions =>
                    // One pass per distinct Scala 2.x version: 2.13 for the cross-build library
                    // modules, 2.12 for the sbt plugins. This is why the regular test run also
                    // covers the 2.12-only plugins (kyo-compat-plugin, kyo-doctest-plugin).
                    val afterScala2 = versions.foldLeft(state2) { (st, v) =>
                        log(s"switching to Scala $v for cross-build modules")
                        val switched = if (isDryRun) st else Command.process(s"++$v", st, msg => st.log.error(msg))
                        runForScala(switched, v)
                    }
                    log(s"restoring Scala $scala3")
                    if (isDryRun) afterScala2 else Command.process(s"++$scala3", afterScala2, msg => afterScala2.log.error(msg))
            }
        } else {
            state2
        }
    }

    // --- Full test mode ---

    private def runAll(
        state: State,
        platform: Option[String],
        scalaVersion: String,
        isDryRun: Boolean = false,
        phase: String = "test"
    ): State = {
        val extracted = Project.extract(state)
        val structure = extracted.structure
        val allRefs   = structure.allProjectRefs

        val testable = allRefs.filter { ref =>
            val name        = ref.project
            val versions    = (ref / crossScalaVersions).get(structure.data).getOrElse(Nil)
            val isAggregate = aggregateProjects.contains(name)
            val platformMatch = if (isAggregate) false
            else platform match {
                case Some(p) => matchesPlatform(name, p)
                case None    => true
            }
            val matchesScala = versions.contains(scalaVersion)
            platformMatch && matchesScala
        }

        if (testable.isEmpty) {
            log("no projects found for current Scala version and platform")
            state
        } else {
            val sorted = testable.map(_.project).sorted
            log(s"${phaseLabel(phase)} ${sorted.size} projects: ${sorted.mkString(", ")}")
            val commands = sorted.map(name => taskFor(phase, name)).mkString("; ")
            log(s"running: $commands")
            if (isDryRun) state else Command.process(commands, state, msg => state.log.error(msg))
        }
    }

    // --- Diff test mode ---
    // If the meta-build changed (project/*, .github/*), run all modules. A build.sbt change is
    // attributed to the specific projects whose settings are defined on the changed lines (see
    // buildSbtAffectedProjects), widening to all only when a changed line cannot be pinned to a
    // project. Otherwise, run only affected modules + their transitive dependents.

    private def runDiff(
        state: State,
        baseRef: String,
        platform: Option[String],
        scalaVersion: String,
        isDryRun: Boolean = false,
        phase: String = "test"
    ): State = {
        val changedFiles = diffFiles(baseRef)
        if (changedFiles.isEmpty) {
            log(s"no changed files vs $baseRef, skipping tests")
            return state
        }

        log(s"${changedFiles.size} changed files vs $baseRef:")
        changedFiles.foreach(f => log(s"  $f"))

        if (metaBuildChanged(changedFiles)) {
            log("meta-build changed (project/ or .github/), running all modules")
            return runAll(state, platform, scalaVersion, isDryRun, phase)
        }

        val extracted = Project.extract(state)
        val structure = extracted.structure
        val allRefs   = structure.allProjectRefs
        val allNames  = allRefs.map(_.project).toSet
        val bd        = extracted.get(buildDependencies)

        // A build.sbt change maps to the projects whose settings changed; None means a changed
        // line could not be attributed to specific projects, so fall back to running all modules.
        val buildSbtProjects: Set[String] =
            if (!changedFiles.contains("build.sbt")) Set.empty
            else buildSbtAffectedProjects(extracted, baseRef) match {
                case Some(names) => names
                case None        => return runAll(state, platform, scalaVersion, isDryRun, phase)
            }

        val directlyChanged = (changedFiles.flatMap(fileToProjects(_, allNames)) ++ buildSbtProjects).toSet
        val filtered = platform match {
            case Some(p) => directlyChanged.filter(matchesPlatform(_, p))
            case None    => directlyChanged
        }

        if (filtered.isEmpty) {
            log("no affected projects found, skipping tests")
            return state
        }

        val dependentMap = transitiveDependents(allRefs, bd)
        val allAffected = filtered.flatMap { name =>
            allRefs.find(_.project == name) match {
                case Some(ref) => dependentMap.getOrElse(ref, Set.empty).map(_.project) + name
                case None      => Set(name)
            }
        }

        val toTest = (platform match {
            case Some(p) => allAffected.filter(matchesPlatform(_, p))
            case None    => allAffected
        }).filter { name =>
            allRefs.find(_.project == name).exists { ref =>
                (ref / crossScalaVersions).get(structure.data).getOrElse(Nil).contains(scalaVersion)
            }
        }

        if (toTest.isEmpty) {
            log("no testable affected projects found, skipping tests")
            state
        } else {
            val sorted = toTest.toSeq.sorted
            log(s"directly changed: ${filtered.toSeq.sorted.mkString(", ")}")
            log(s"with dependents (${phaseLabel(phase)}): ${sorted.mkString(", ")}")
            val commands = sorted.map(name => taskFor(phase, name)).mkString("; ")
            log(s"running: $commands")
            if (isDryRun) state else Command.process(commands, state, msg => state.log.error(msg))
        }
    }

    // --- Helpers ---

    /** Check if a project name matches the given platform. JS, Native, and Wasm projects are matched by their
      * explicit suffix; JVM is the residual: cross-project JVM variants carry a `JVM` suffix, and the
      * suffix-less plain projects (kyo-compat-plugin, kyo-doctest-plugin, and similar JVM-only definitions)
      * carry no platform suffix and are JVM-only.
      */
    private def matchesPlatform(name: String, platform: String): Boolean =
        platform match {
            case "JVM"    => !name.endsWith("JS") && !name.endsWith("Native") && !name.endsWith("Wasm")
            case "JS"     => name.endsWith("JS")
            case "Native" => name.endsWith("Native")
            case "Wasm"   => name.endsWith("Wasm")
            case _        => false
        }

    /** Map a changed file path to affected sbt project names.
      *
      * Cross-projects use shared/jvm/js/native subdirectories. Modules with flat src/ layout (e.g. kyo-bench) hit the default case which
      * tries all platforms; the .filter(allProjectNames.contains) ensures only actually-existing projects are returned.
      */
    private def fileToProjects(file: String, allProjectNames: Set[String]): Set[String] = {
        val parts = file.split("/").toList
        parts match {
            case module :: "plugin" :: _ if allProjectNames.contains(s"$module-plugin") =>
                Set(s"$module-plugin")
            case module :: sub :: _ =>
                // Map the platform sub-directory to affected platforms. Handles single
                // platform dirs (jvm/js/native/wasm), the partially-shared dirs named by
                // joining identifiers (e.g. js-wasm, jvm-native), shared (all platforms),
                // and any other layout (all, then filtered by which projects exist).
                val platformDirs = Map("jvm" -> "JVM", "js" -> "JS", "native" -> "Native", "wasm" -> "Wasm")
                val allPlatforms = platformDirs.values.toSeq
                val affectedPlatforms = sub match {
                    case "shared"                                        => allPlatforms
                    case s if s.split("-").forall(platformDirs.contains) => s.split("-").toList.map(platformDirs)
                    case _                                               => allPlatforms
                }
                affectedPlatforms.flatMap { p =>
                    val suffixed = s"$module$p"
                    if (allProjectNames.contains(suffixed)) Seq(suffixed) else Seq.empty
                }.toSet
            case _ => Set.empty
        }
    }

    private def diffFiles(baseRef: String): Seq[String] =
        try Seq("git", "diff", "--name-only", baseRef).!!.trim.split("\n").filter(_.nonEmpty).toSeq
        catch {
            case e: Exception =>
                log(s"Failed to run git diff: ${e.getMessage}")
                Seq.empty
        }

    // project/ (the meta-build, plugins, this command) and .github/ (CI workflows) are genuinely
    // global: a change there can alter how every module builds, so run all. A build.sbt change is
    // handled separately by buildSbtAffectedProjects, which pins it to the projects that changed.
    private def metaBuildChanged(files: Seq[String]): Boolean =
        files.exists(f => f.startsWith("project/") || f.startsWith(".github/"))

    /** New-side line numbers changed in build.sbt vs baseRef, or None when the change cannot be
      * attributed from the new file alone. None covers a pure deletion (the removed setting has no
      * new-side line to map) and a git/parse failure; both widen to running all modules.
      */
    private def changedBuildSbtLines(baseRef: String): Option[Set[Int]] =
        try {
            val diff = Seq("git", "diff", "--unified=0", baseRef, "--", "build.sbt").!!
            // Hunk header: @@ -oldStart[,oldCount] +newStart[,newCount] @@ [context]
            val hunk        = """@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""".r
            val lines       = scala.collection.mutable.Set.empty[Int]
            var hasDeletion = false
            diff.linesIterator.foreach { line =>
                hunk.findFirstMatchIn(line).foreach { m =>
                    val start = m.group(1).toInt
                    val count = Option(m.group(2)).map(_.toInt).getOrElse(1)
                    if (count == 0) hasDeletion = true
                    else lines ++= (start until start + count)
                }
            }
            if (hasDeletion || lines.isEmpty) None else Some(lines.toSet)
        } catch {
            case e: Exception =>
                log(s"Failed to diff build.sbt: ${e.getMessage}")
                None
        }

    /** Projects affected by the build.sbt change, or None to run all modules.
      *
      * sbt tracks every setting's definition position (file:line) and its resolved project scope,
      * the same data `inspect` surfaces as "Defined at:". For each changed build.sbt line, this maps
      * the settings defined there to their project(s) and returns the union. A setting written in a
      * shared val (e.g. `kyo-settings`) is replicated once per applying project, each copy scoped to
      * that project, so a change to such a line attributes to exactly the projects that apply it.
      *
      * Returns None (run all modules) whenever a changed line cannot be pinned to specific projects:
      * it maps to a Global/ThisBuild/root-aggregate setting, to a non-setting line (a top-level val,
      * import, plugin enablement, or a continuation line of a multi-line setting, none of which sbt
      * tracks as a setting position, since it anchors a setting at its first line), or to a deletion.
      * This keeps the narrowing conservative: it never under-tests a real build change.
      */
    private def buildSbtAffectedProjects(extracted: Extracted, baseRef: String): Option[Set[String]] =
        try {
            val structure    = extracted.structure
            val buildSbtFile = new File(new File(extracted.currentRef.build), "build.sbt").getCanonicalFile

            // The lines this position covers, but only if it is in the top-level build.sbt. Positions
            // render the canonical absolute path, so match by canonical file, not a bare name.
            def coveredLines(pos: SourcePosition): Set[Int] = pos match {
                case fp: FilePosition if new File(fp.path).getCanonicalFile == buildSbtFile =>
                    fp match {
                        case RangePosition(_, LineRange(start, end)) => (start to end).toSet
                        case LinePosition(_, line)                   => Set(line)
                        case _                                       => Set(fp.startLine)
                    }
                case _ => Set.empty
            }

            changedBuildSbtLines(baseRef) match {
                case None =>
                    log("build.sbt change includes deletions or is unparseable, running all modules")
                    None
                case Some(changedLines) =>
                    var sawGlobal = false
                    val names     = scala.collection.mutable.Set.empty[String]
                    val covered   = scala.collection.mutable.Set.empty[Int]
                    structure.settings.foreach { s =>
                        val hit = coveredLines(s.pos).intersect(changedLines)
                        if (hit.nonEmpty) {
                            covered ++= hit
                            s.key.scope.project match {
                                case Select(ref: ProjectRef) if !aggregateProjects.contains(ref.project) =>
                                    names += ref.project
                                case _ =>
                                    // ThisBuild / Global / root-aggregate / unresolved: affects all.
                                    sawGlobal = true
                            }
                        }
                    }
                    val uncovered = changedLines -- covered
                    if (uncovered.nonEmpty) {
                        log(s"build.sbt lines ${uncovered.toSeq.sorted.mkString(", ")} are not settings " +
                            "(val/import/plugin), running all modules")
                        None
                    } else if (sawGlobal) {
                        log("build.sbt change touches a Global/ThisBuild/root setting, running all modules")
                        None
                    } else {
                        log(s"build.sbt change attributed to: ${names.toSeq.sorted.mkString(", ")}")
                        Some(names.toSet)
                    }
            }
        } catch {
            case e: Exception =>
                log(s"build.sbt attribution failed (${e.getMessage}), running all modules")
                None
        }

    private def transitiveDependents(
        allRefs: Seq[ProjectRef],
        bd: BuildDependencies
    ): Map[ProjectRef, Set[ProjectRef]] = {
        val directDependents = scala.collection.mutable.Map[ProjectRef, Set[ProjectRef]]()
        for {
            (project, deps) <- bd.classpath
            dep             <- deps
        } {
            directDependents(dep.project) =
                directDependents.getOrElse(dep.project, Set.empty) + project
        }

        def closure(ref: ProjectRef, visited: Set[ProjectRef]): Set[ProjectRef] = {
            val direct = directDependents.getOrElse(ref, Set.empty) -- visited
            direct ++ direct.flatMap(d => closure(d, visited + d))
        }

        allRefs.map(ref => ref -> closure(ref, Set(ref))).toMap
    }

    /** Resolve shorthand scala versions: "2" → "2.13.18", "3" → "3.8.2", or pass through exact versions. */
    private def resolveScalaVersion(input: String, extracted: Extracted): String =
        input match {
            case "2" =>
                findScala2Version(extracted).getOrElse(
                    sys.error("No Scala 2.x version found in crossScalaVersions")
                )
            case "3" => extracted.get(scalaVersion)
            case v   => v
        }

    /** Find the Scala 2.x version used in crossScalaVersions across all projects. */
    private def findScala2Version(extracted: Extracted): Option[String] = {
        val structure = extracted.structure
        structure.allProjectRefs.flatMap { ref =>
            (ref / crossScalaVersions).get(structure.data).getOrElse(Nil)
        }.find(_.startsWith("2."))
    }

    /** All distinct Scala 2.x versions in crossScalaVersions across projects (e.g. 2.13 cross-build
      * modules and 2.12 sbt plugins), sorted so each gets its own test pass.
      */
    private def findScala2Versions(extracted: Extracted): Seq[String] = {
        val structure = extracted.structure
        structure.allProjectRefs.flatMap { ref =>
            (ref / crossScalaVersions).get(structure.data).getOrElse(Nil)
        }.filter(_.startsWith("2.")).distinct.sorted
    }
}
