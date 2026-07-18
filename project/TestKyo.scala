import java.io.File
import sbt.*
import sbt.Keys.*
import sbt.internal.BuildDependencies
import sbt.internal.util.FilePosition
import sbt.internal.util.LinePosition
import sbt.internal.util.LineRange
import sbt.internal.util.RangePosition
import sbt.internal.util.SourcePosition
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
        val extracted   = Project.extract(state)
        val structure   = extracted.structure
        val allRefs     = structure.allProjectRefs
        val jsNames     = projectNamesWithPlugin(structure, scalaJSPluginClass)
        val nativeNames = projectNamesWithPlugin(structure, scalaNativePluginClass)

        val testable = allRefs.filter { ref =>
            val name        = ref.project
            val versions    = (ref / crossScalaVersions).get(structure.data).getOrElse(Nil)
            val isAggregate = aggregateProjects.contains(name)
            val platformMatch = if (isAggregate) false
            else platform match {
                case Some(p) => matchesPlatform(name, p, jsNames, nativeNames)
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
    // attributed to the specific projects whose settings, or whose `lazy val` blocks, cover the
    // changed lines (see buildSbtAffectedProjects), widening to all only when a changed line cannot
    // be pinned to a project. Otherwise, run only affected modules + their transitive dependents.

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

        val extracted   = Project.extract(state)
        val structure   = extracted.structure
        val allRefs     = structure.allProjectRefs
        val allNames    = allRefs.map(_.project).toSet
        val bd          = extracted.get(buildDependencies)
        val jsNames     = projectNamesWithPlugin(structure, scalaJSPluginClass)
        val nativeNames = projectNamesWithPlugin(structure, scalaNativePluginClass)

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
            case Some(p) => directlyChanged.filter(matchesPlatform(_, p, jsNames, nativeNames))
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
            case Some(p) => allAffected.filter(matchesPlatform(_, p, jsNames, nativeNames))
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

    /** Names of the projects that enable `pluginClass`, resolved from the loaded build.
      *
      * A cross-project variant announces its platform in its name suffix, but a plain `project` enabling
      * ScalaJSPlugin directly has no suffix to announce it. Classifying those by their enabled plugins is
      * what keeps them out of the JVM residual below.
      */
    private def projectNamesWithPlugin(structure: sbt.internal.BuildStructure, pluginClass: String): Set[String] =
        structure.allProjectRefs.flatMap { ref =>
            Project.getProjectForReference(ref, structure).collect {
                case p if p.autoPlugins.exists(_.getClass.getName.startsWith(pluginClass)) => ref.project
            }
        }.toSet

    private val scalaJSPluginClass     = "org.scalajs.sbtplugin.ScalaJSPlugin"
    private val scalaNativePluginClass = "scala.scalanative.sbtplugin.ScalaNativePlugin"

    /** Check if a project matches the given platform.
      *
      * Cross-project variants are matched by their explicit suffix. A suffix-less project is matched by the
      * plugins it enables, because JVM is the RESIDUAL: treating "no suffix" as JVM silently swept every
      * plain Scala.js project (a demo bundle, a fixtures bundle) into the JVM pass, where sbt-scalajs links
      * it and starts Node eagerly even with zero tests, so it failed on a missing npm package that the JVM
      * pass never installs. Wasm is checked first because the Wasm backend enables ScalaJSPlugin too, so
      * only the suffix separates the two.
      */
    private def matchesPlatform(name: String, platform: String, jsNames: Set[String], nativeNames: Set[String]): Boolean = {
        val actual =
            if (name.endsWith("Wasm")) "Wasm"
            else if (name.endsWith("Native") || nativeNames.contains(name)) "Native"
            else if (name.endsWith("JS") || jsNames.contains(name)) "JS"
            else "JVM"
        actual == platform
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
      * Lines that no setting position covers (a `.dependsOn`/`.crossType`/comment inside a module's
      * block, a new module's `lazy val` block, an aggregate-registration line) are handed to
      * attributeUncoveredBuildSbtLines, which attributes them from build.sbt's block structure. The
      * union of both passes is returned.
      *
      * Returns None (run all modules) whenever a changed line cannot be pinned to specific projects:
      * it maps to a Global/ThisBuild/root-aggregate setting, a deletion, or a line neither pass can
      * attribute (a top-level val/import, a non-project shared val, an existing module's aggregate
      * registration). This keeps the narrowing conservative: it never under-tests a real build change.
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
                    if (sawGlobal) {
                        log("build.sbt change touches a Global/ThisBuild/root setting, running all modules")
                        None
                    } else {
                        val uncovered = changedLines -- covered
                        val allNames  = structure.allProjectRefs.map(_.project).toSet
                        attributeUncoveredBuildSbtLines(uncovered, buildSbtFile, baseRef, allNames) match {
                            case None => None // logged inside
                            case Some(extra) =>
                                val all = (names ++ extra).toSet
                                log(s"build.sbt change attributed to: ${all.toSeq.sorted.mkString(", ")}")
                                Some(all)
                        }
                    }
            }
        } catch {
            case e: Exception =>
                log(s"build.sbt attribution failed (${e.getMessage}), running all modules")
                None
        }

    /** Attribute the changed build.sbt lines that no sbt setting position covers (a module's
      * `lazy val` block body, an aggregate-registration line) to their project(s), or None to run
      * all modules. sbt tracks a setting's position but not a project's definition block, so a
      * module configured purely through shared setting vals (`kyo-settings`, `mimaCheck(...)`) has
      * no per-project setting position in its own block; build.sbt's own structure is the reliable
      * signal. Every top-level definition starts at column 0, so a changed line's owning module is
      * the nearest column-0 `lazy val` above it.
      *
      *   - owner is a real (non-aggregate) module: attribute to it. A `.dependsOn`, a setting, a
      *     comment, a platform-list edit inside a module's block affects only that module (the
      *     existing machinery then adds its transitive dependents), new or existing alike.
      *   - owner is a root aggregate (`kyoJVM`/...): benign only if the line registers a brand-NEW
      *     module (`kyo-mcp`.jvm,). A registration or `inProjects` exclusion of an EXISTING module,
      *     or any other aggregate setting, runs all. Newness is what tells an `.aggregate(+new)`
      *     line from an `inProjects(+existing)` line: they are otherwise identical in shape.
      *   - owner is a non-project val/def/import, a top-level comment, or a global: runs all.
      */
    private def attributeUncoveredBuildSbtLines(
        uncovered: Set[Int],
        buildSbtFile: File,
        baseRef: String,
        allNames: Set[String]
    ): Option[Set[String]] = {
        if (uncovered.isEmpty) return Some(Set.empty)
        val buildLines = IO.readLines(buildSbtFile).toIndexedSeq

        def isCol0(l: String): Boolean = l.nonEmpty && !l.charAt(0).isWhitespace
        val headerRe                   = """^lazy val (?:`([^`]+)`|([A-Za-z0-9_]+))""".r
        def headerName(l: String): Option[String] =
            if (l.startsWith("lazy val ")) headerRe.findFirstMatchIn(l).map(m => Option(m.group(1)).getOrElse(m.group(2)))
            else None

        // Project names (a `lazy val` whose block builds a crossProject or a project) in a build.sbt.
        val crossRe = """crossProject\(""".r
        val projRe  = """=\s*\(?\s*project\b""".r
        def projectNames(text: IndexedSeq[String]): Set[String] = {
            val ns = scala.collection.mutable.Set.empty[String]
            var i  = 0
            while (i < text.length) {
                headerName(text(i)) match {
                    case Some(name) =>
                        var j  = i + 1
                        val sb = new StringBuilder(text(i))
                        while (j < text.length && !isCol0(text(j))) { sb.append('\n').append(text(j)); j += 1 }
                        val block = sb.toString
                        if (crossRe.findFirstIn(block).isDefined || projRe.findFirstIn(block).isDefined) ns += name
                        i = j
                    case None => i += 1
                }
            }
            ns.toSet
        }

        // A module is "new" if its `lazy val` exists now but not at baseRef. Used only to gate the
        // aggregate-registration branch (see scaladoc), where shape alone cannot tell new from old.
        val baseLines =
            scala.util.Try(Seq("git", "show", s"$baseRef:build.sbt").!!.linesIterator.toIndexedSeq).getOrElse(IndexedSeq.empty)
        if (baseLines.isEmpty) {
            log(s"could not read build.sbt at $baseRef, running all modules")
            return None
        }
        val newNames = projectNames(buildLines) -- projectNames(baseLines)

        def resolve(name: String): Set[String] = {
            val cross = Seq("JVM", "JS", "Native", "Wasm").map(name + _).filter(allNames.contains)
            if (cross.nonEmpty) cross.toSet
            else if (allNames.contains(name)) Set(name)
            else Set.empty
        }

        // The nearest column-0 line at or above n: Some(name) if it is a `lazy val`, else None.
        def ownerLazyVal(n: Int): Option[String] = {
            var i = n
            while (i >= 1) {
                val l = buildLines(i - 1)
                if (isCol0(l)) return headerName(l)
                i -= 1
            }
            None
        }

        val regLineRe = """^\s*`?([A-Za-z0-9_.-]+)`?\.(jvm|js|native|wasm)\s*,?\s*$""".r
        val accSuffix = Map("jvm" -> "JVM", "js" -> "JS", "native" -> "Native", "wasm" -> "Wasm")

        val names = scala.collection.mutable.Set.empty[String]
        val it    = uncovered.iterator
        while (it.hasNext) {
            val ln    = it.next()
            val owner = ownerLazyVal(ln)
            val owned = owner.map(resolve).getOrElse(Set.empty)
            if (owned.nonEmpty && !owned.subsetOf(aggregateProjects))
                names ++= owned
            else if (owned.nonEmpty)
                buildLines(ln - 1) match {
                    case regLineRe(modName, acc)
                        if newNames.contains(modName) && resolve(modName).contains(modName + accSuffix(acc)) =>
                        names += modName + accSuffix(acc)
                    case _ =>
                        log(s"build.sbt line $ln registers/changes an existing module in an aggregate, running all modules")
                        return None
                }
            else {
                log(s"build.sbt line $ln is not inside a module block (val/def/import/global), running all modules")
                return None
            }
        }
        Some(names.toSet)
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
