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
  * Usage:
  *   - `testKyo` diff vs origin/main, all platforms, every Scala version
  *   - `testKyo JVM` diff vs origin/main, JVM only
  *   - `testKyo --all Native` full test, Native only
  *   - `testKyo --scala 2.13.18 JVM` diff, Scala 2.13, JVM only (auto-discovers modules)
  *   - `testKyo --scala 3 --all JVM` full test, primary Scala 3 version only
  *   - `testKyo --cross JVM` the Scala 2.x cross-build passes only
  *   - `testKyo --exclude kyo-aeron,kyo-sql --all Native` full test minus those cross-projects
  *   - `testKyo --only kyo-schema-tests --all Native` only that cross-project
  *   - `testKyo --quick --all JVM` re-run only what sbt has not recorded as passing
  *   - `testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-coreNative Native` link exactly those modules
  *   - `testKyo --dry-run --plan-file /tmp/p Native` write the selected modules to /tmp/p, run nothing
  *   - `testKyo origin/feature JVM` diff vs a specific ref
  *   - `testKyo --dry-run JVM` show what would run without executing
  *
  * A run is a sequence of passes: the primary Scala version, then one per Scala 2.x cross-build
  * version. All passes go out as ONE `;`-chained command string (per pass: version switch, module
  * tasks, completion marker). sbt queues a submitted command string rather than running it in
  * place, so passes submitted as separate strings are unordered against each other and their tasks
  * run at whichever version the queue last selected.
  */
object TestKyo {

    private val platformNames = Set("JVM", "JS", "Native", "Wasm")

    // Root aggregate projects: testing one runs every leaf via aggregation, so the diff
    // and full-run paths both exclude them and treat any change scoped to one as "run all".
    private val aggregateProjects = Set("kyoJVM", "kyoJS", "kyoNative", "kyoWasm")

    private val phases = Seq("compile-main", "compile-test", "link", "test")

    private def log(msg: String): Unit = println(s"[testKyo] $msg")

    private val doneCommandName = "testKyoDone"

    /** Prints the line that marks a pass as run to completion. Each pass chains it after its own
      * tasks, so its absence from a run's output means the pass stopped short of its module list.
      * scripts/ci-test.sh reads it to tell a truncated Native pass from a green one.
      */
    def doneCommand: Command = Command.command(doneCommandName) { state =>
        log("completed")
        state
    }

    /** The per-module sbt task for a phase. compile-main compiles only main sources; compile-test
      * compiles test sources (main resolved from disk); link links the Native test binary; test
      * (default) runs the tests, as `testQuick` under `--quick` so a re-invocation re-runs only the
      * tests sbt did not record as passing. Running the phases as separate sbt processes keeps the
      * driver from holding a full compile heap while test forks run, which is what over-commits the
      * memory-constrained CI runners.
      */
    private def taskFor(phase: String, name: String, quick: Boolean): String = phase match {
        case "compile-main" => s"$name/Compile/compile"
        case "compile-test" => s"$name/Test/compile"
        case "link"         => s"$name/Test/nativeLink"
        case _              => if (quick) s"$name/testQuick" else s"$name/test"
    }

    private def phaseLabel(phase: String): String = phase match {
        case "compile-main" => "compiling main for"
        case "compile-test" => "compiling test for"
        case "link"         => "linking"
        case _              => "testing"
    }

    final private case class Args(
        isAll: Boolean,
        isDryRun: Boolean,
        isCross: Boolean,
        isQuick: Boolean,
        scalaArg: Option[String],
        phase: String,
        modules: Option[Seq[String]],
        exclude: Set[String],
        only: Set[String],
        planFile: Option[String],
        platform: Option[String],
        baseRef: String
    )

    def command: Command = Command.args("testKyo", "") { (state, args) =>
        parseArgs(args) match {
            case Left(err) =>
                state.log.error(s"testKyo: $err")
                state.fail
            case Right(parsed) => run(state, parsed)
        }
    }

    /** Parse the command line, or return the argument error that must fail the invocation. An
      * unknown `--flag` is an error rather than a base ref: a mistyped `--modules` silently
      * becoming a git ref would run the wrong module set.
      */
    private def parseArgs(args: Seq[String]): Either[String, Args] = {
        var isAll    = false
        var isDryRun = false
        var isCross  = false
        var isQuick  = false
        var scalaArg = Option.empty[String]
        var phase    = "test"
        var modules  = Option.empty[Seq[String]]
        var exclude  = Set.empty[String]
        var only     = Set.empty[String]
        var planFile = Option.empty[String]
        var error    = Option.empty[String]
        val rest     = collection.mutable.ListBuffer.empty[String]

        def valueAt(i: Int, flag: String): Option[String] =
            if (i + 1 < args.length && !args(i + 1).startsWith("--")) Some(args(i + 1))
            else {
                error = error.orElse(Some(s"$flag requires a value"))
                None
            }

        def csv(v: Option[String]): Seq[String] = v.toSeq.flatMap(_.split(",").toSeq.map(_.trim).filter(_.nonEmpty))

        var i = 0
        while (i < args.length) {
            args(i) match {
                case "--all" =>
                    isAll = true; i += 1
                case "--dry-run" =>
                    isDryRun = true; i += 1
                case "--cross" =>
                    isCross = true; i += 1
                case "--quick" =>
                    isQuick = true; i += 1
                case "--scala" =>
                    scalaArg = valueAt(i, "--scala"); i += 2
                case "--phase" =>
                    phase = valueAt(i, "--phase").getOrElse(phase); i += 2
                case "--plan-file" =>
                    planFile = valueAt(i, "--plan-file"); i += 2
                case "--modules" =>
                    modules = valueAt(i, "--modules").map(v => csv(Some(v)))
                    i += 2
                case "--exclude" =>
                    exclude = csv(valueAt(i, "--exclude")).toSet; i += 2
                case "--only" =>
                    only = csv(valueAt(i, "--only")).toSet; i += 2
                case a if a.startsWith("--") =>
                    error = error.orElse(Some(s"unknown argument: $a")); i += 1
                case a =>
                    rest += a; i += 1
            }
        }

        val (platformArgs, refArgs) = rest.toSeq.partition(a => platformNames.exists(_.equalsIgnoreCase(a)))
        val platform                = platformArgs.headOption.map(a => platformNames.find(_.equalsIgnoreCase(a)).get)
        val baseRef                 = refArgs.headOption.getOrElse("origin/main")

        val invalid =
            if (isCross && scalaArg.isDefined) Some("--cross and --scala select different passes; pass only one")
            else if (isCross && modules.isDefined) Some("--cross and --modules cannot be combined")
            else if (isAll && modules.isDefined) Some("--all and --modules cannot be combined")
            else if (modules.isDefined && (exclude.nonEmpty || only.nonEmpty))
                Some("--modules is an execution list, --exclude/--only filter a selection; pass only one")
            else if (modules.exists(_.isEmpty)) Some("--modules needs a comma-separated module list")
            else if (!phases.contains(phase)) Some(s"unknown phase '$phase', expected one of ${phases.mkString(", ")}")
            else None

        error.orElse(invalid)
            .toLeft(Args(isAll, isDryRun, isCross, isQuick, scalaArg, phase, modules, exclude, only, planFile, platform, baseRef))
    }

    private def run(state: State, a: Args): State = {
        val extracted = Project.extract(state)
        val scala3    = extracted.get(scalaVersion)

        // One pass per Scala version, in execution order. --cross drops the primary pass, --scala
        // and --modules pin a single one; the default is the primary version plus 2.13 for the
        // cross-build library modules and 2.12 for the sbt plugins.
        val versions =
            if (a.isCross) findScala2Versions(extracted)
            else a.scalaArg.map(resolveScalaVersion(_, extracted)) match {
                case Some(v)                     => Seq(v)
                case None if a.modules.isDefined => Seq(scala3)
                case None                        => scala3 +: findScala2Versions(extracted)
            }

        val mode =
            if (a.modules.isDefined) "explicit module list"
            else if (a.isAll) "all"
            else s"diff vs ${a.baseRef}"
        log(s"scala: ${if (versions.isEmpty) "none" else versions.mkString(" + ")}, platform: ${a.platform.getOrElse("all")}, mode: $mode")

        if (versions.isEmpty) {
            log("no Scala 2.x cross-build modules found")
            a.planFile.foreach(writePlan(_, Nil))
            log("completed")
            state
        } else
            selectPasses(state, a, versions) match {
                case Left(err) =>
                    state.log.error(s"testKyo: $err")
                    state.fail
                case Right(passes) =>
                    a.planFile.foreach(writePlan(_, passes.flatMap(_._2).distinct.sorted))
                    execute(state, a, scala3, passes)
            }
    }

    /** The modules each pass runs, in pass order. Selection reads crossScalaVersions and the
      * project graph, neither of which `++` changes, so every pass is resolved from the unswitched
      * state and the whole run can go out as one ordered command chain.
      *
      * `--exclude`/`--only` filter here, where selection happens, by cross-project base name;
      * `--modules` replaces selection outright and so is never combined with them.
      */
    private def selectPasses(
        state: State,
        a: Args,
        versions: Seq[String]
    ): Either[String, Seq[(String, Seq[String])]] = {
        val extracted = Project.extract(state)
        val structure = extracted.structure
        val allRefs   = structure.allProjectRefs

        def exists(name: String): Boolean = allRefs.exists(_.project == name)

        def crossVersions(name: String): Seq[String] =
            allRefs.find(_.project == name).flatMap(ref => (ref / crossScalaVersions).get(structure.data)).getOrElse(Nil)

        def platformMatch(name: String): Boolean =
            !aggregateProjects.contains(name) && (a.platform match {
                case Some(p) => matchesPlatform(name, p)
                case None    => true
            })

        def selected(name: String): Boolean =
            !a.exclude.contains(baseName(name)) && (a.only.isEmpty || a.only.contains(baseName(name)))

        a.modules match {
            case Some(names) =>
                // An explicit list is one pass and never a filter: an unusable name fails the
                // invocation so a batched runner cannot skip modules it believes it ran.
                val version     = versions.head
                val unknown     = names.filterNot(exists)
                val offPlatform = names.filter(n => exists(n) && !platformMatch(n))
                val offVersion  = names.filter(n => exists(n) && platformMatch(n) && !crossVersions(n).contains(version))
                if (unknown.nonEmpty) Left(s"unknown modules: ${unknown.mkString(", ")}")
                else if (offPlatform.nonEmpty)
                    Left(s"modules outside platform ${a.platform.getOrElse("all")}: ${offPlatform.mkString(", ")}")
                else if (offVersion.nonEmpty) Left(s"modules not cross-built for Scala $version: ${offVersion.mkString(", ")}")
                else Right(Seq(version -> names.sorted))
            case None =>
                val restrict = if (a.isAll) None else diffSelection(state, a, allRefs, extracted)
                Right(versions.map { v =>
                    val eligible =
                        allRefs.map(_.project).filter(n => platformMatch(n) && selected(n) && crossVersions(n).contains(v))
                    val chosen = restrict match {
                        case Some(selection) => eligible.filter(selection.contains)
                        case None            => eligible
                    }
                    v -> chosen.sorted
                })
        }
    }

    // --- Diff test mode ---
    // If the meta-build changed (project/*, .github/*), run all modules. A build.sbt change is
    // attributed to the specific projects whose settings, or whose `lazy val` blocks, cover the
    // changed lines (see buildSbtAffectedProjects), widening to all only when a changed line cannot
    // be pinned to a project. Otherwise, run only affected modules + their transitive dependents.

    /** The modules a diff selects, before the per-pass platform and Scala filters, or None when the
      * change is global and every module must run.
      */
    private def diffSelection(
        state: State,
        a: Args,
        allRefs: Seq[ProjectRef],
        extracted: Extracted
    ): Option[Set[String]] = {
        val changedFiles = diffFiles(a.baseRef)
        if (changedFiles.isEmpty) {
            log(s"no changed files vs ${a.baseRef}, skipping tests")
            return Some(Set.empty)
        }

        log(s"${changedFiles.size} changed files vs ${a.baseRef}:")
        changedFiles.foreach(f => log(s"  $f"))

        if (metaBuildChanged(changedFiles)) {
            log("meta-build changed (project/ or .github/), running all modules")
            return None
        }

        val allNames = allRefs.map(_.project).toSet
        val bd       = extracted.get(buildDependencies)

        // A build.sbt change maps to the projects whose settings changed; None means a changed
        // line could not be attributed to specific projects, so fall back to running all modules.
        val buildSbtProjects: Set[String] =
            if (!changedFiles.contains("build.sbt")) Set.empty
            else buildSbtAffectedProjects(extracted, a.baseRef) match {
                case Some(names) => names
                case None        => return None
            }

        val directlyChanged = (changedFiles.flatMap(fileToProjects(_, allNames)) ++ buildSbtProjects).toSet
        val filtered = a.platform match {
            case Some(p) => directlyChanged.filter(matchesPlatform(_, p))
            case None    => directlyChanged
        }

        if (filtered.isEmpty) {
            log("no affected projects found, skipping tests")
            return Some(Set.empty)
        }

        log(s"directly changed: ${filtered.toSeq.sorted.mkString(", ")}")
        val dependentMap = transitiveDependents(allRefs, bd)
        Some(filtered.flatMap { name =>
            allRefs.find(_.project == name) match {
                case Some(ref) => dependentMap.getOrElse(ref, Set.empty).map(_.project) + name
                case None      => Set(name)
            }
        })
    }

    /** Submit every pass as one `;`-chained command string: switch, tasks, completion marker, and
      * the restore switch after the last pass that moved off the primary version. A pass that
      * selects nothing contributes no tasks, so it prints its marker here.
      */
    private def execute(state: State, a: Args, scala3: String, passes: Seq[(String, Seq[String])]): State = {
        val chain   = collection.mutable.ListBuffer.empty[String]
        var current = scala3
        passes.foreach { case (version, modules) =>
            if (modules.isEmpty) {
                log(s"Scala $version: no modules selected")
                log("completed")
            } else {
                val switch = if (version == current) Nil else Seq(s"++$version")
                val parts  = (switch ++ modules.map(taskFor(a.phase, _, a.isQuick))) :+ doneCommandName
                current = version
                log(s"Scala $version, ${phaseLabel(a.phase)} ${modules.size} modules: ${modules.mkString(", ")}")
                log(s"pass: ${parts.mkString("; ")}")
                chain ++= parts
            }
        }
        if (current != scala3) {
            log(s"restoring Scala $scala3 after the last pass")
            chain += s"++$scala3"
        }
        if (chain.isEmpty || a.isDryRun) state
        else Command.process(chain.mkString("; "), state, msg => state.log.error(msg))
    }

    /** The selected modules, one per line, for the runner that partitions them into batches. */
    private def writePlan(path: String, modules: Seq[String]): Unit = {
        val file = new File(path)
        Option(file.getAbsoluteFile.getParentFile).foreach(IO.createDirectory)
        IO.writeLines(file, modules)
        log(s"plan: ${modules.size} modules written to $path")
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

    /** Strip the platform suffix to the cross-project base name, so `--exclude` names a module once
      * (`kyo-schema-tests`) and matches every platform variant. JVM-only projects (kyo-compat-plugin) return unchanged.
      */
    private def baseName(name: String): String =
        platformNames.find(p => name.endsWith(p)).map(p => name.dropRight(p.length)).getOrElse(name)

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
