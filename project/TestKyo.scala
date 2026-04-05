import sbt.*
import sbt.Keys.*
import sbt.internal.BuildDependencies
import scala.sys.process.*

/** Unified test command for CI and local use.
  *
  * Usage: testKyo diff vs origin/main, all platforms, current Scala testKyo JVM diff vs origin/main, JVM only testKyo --all full test, all
  * platforms testKyo --all JVM full test, JVM only testKyo --scala 2.13.18 JVM diff, Scala 2.13, JVM only (auto-discovers modules) testKyo
  * --all --scala 2.13.18 JVM full test, Scala 2.13, JVM only testKyo origin/feature JVM diff vs specific ref, JVM only
  */
object TestKyo {

    private val platformNames = Set("JVM", "JS", "Native")

    private def log(msg: String): Unit = println(s"[testKyo] $msg")

    def command: Command = Command.args("testKyo", "") { (state, args) =>
        val isAll           = args.contains("--all")
        val scalaIdx        = args.indexOf("--scala")
        val scalaVersionArg = if (scalaIdx >= 0 && scalaIdx + 1 < args.length) Some(args(scalaIdx + 1)) else None

        val remaining = args
            .filterNot(_ == "--all")
            .filterNot(a => a == "--scala" || (scalaIdx >= 0 && args.indexOf(a) == scalaIdx + 1))

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

        // When a specific --scala version is set, always use runAll (filters by version).
        // runDiff's full-test fallback (kyoJVM/test) would include modules that don't support the version.
        val state2 =
            if (isAll || scalaVersionOpt.isDefined) runAll(state1, platform, targetScala)
            else runDiff(state1, baseRef, platform)

        // If no --scala specified, also run Scala 2.x cross-build modules
        if (runBothScala) {
            val scala2 = findScala2Version(extracted)
            scala2 match {
                case Some(v) =>
                    log(s"switching to Scala $v for cross-build modules")
                    val state3 = Command.process(s"++$v", state2, msg => state2.log.error(msg))
                    val state4 = runAll(state3, platform, v)
                    // Restore Scala 3 so sbt doesn't resolve the root project under 2.x,
                    // which causes cross-version conflicts (e.g. kyo-dataJS scala-java-time)
                    log(s"restoring Scala $scala3")
                    Command.process(s"++$scala3", state4, msg => state4.log.error(msg))
                case None =>
                    log("no Scala 2.x cross-build modules found")
                    state2
            }
        } else {
            state2
        }
    }

    // --- Full test mode ---

    private def runAll(state: State, platform: Option[String], scalaVersion: String): State = {
        val extracted = Project.extract(state)
        val structure = extracted.structure
        val allRefs   = structure.allProjectRefs

        // Exclude aggregate projects
        val excluded = Set("kyoJVM", "kyoJS", "kyoNative")
        val testable = allRefs.filter { ref =>
            val name        = ref.project
            val versions    = (ref / crossScalaVersions).get(structure.data).getOrElse(Nil)
            val isAggregate = excluded.contains(name)
            val matchesPlatform = if (isAggregate) false
            else platform match {
                case Some("JVM")    => !name.endsWith("JS") && !name.endsWith("Native")
                case Some("JS")     => name.endsWith("JS")
                case Some("Native") => name.endsWith("Native")
                case None           => true
                case _              => false
            }
            val matchesScala = versions.contains(scalaVersion)
            matchesPlatform && matchesScala
        }

        if (testable.isEmpty) {
            log("no projects found for current Scala version and platform")
            state
        } else {
            val sorted = testable.map(_.project).sorted
            log(s"testing ${sorted.size} projects: ${sorted.mkString(", ")}")
            val commands = sorted.map(name => s"$name/test").mkString("; ")
            log(s"running: $commands")
            Command.process(commands, state, msg => state.log.error(msg))
        }
    }

    // --- Diff test mode ---

    private def runDiff(state: State, baseRef: String, platform: Option[String]): State = {
        val changedFiles = diffFiles(baseRef)
        if (changedFiles.isEmpty) {
            log(s"no changed files vs $baseRef — skipping tests")
            state
        } else {
            log(s"${changedFiles.size} changed files vs $baseRef:")
            changedFiles.foreach(f => log(s"  $f"))

            if (buildConfigChanged(changedFiles))
                runDiffFull(state, platform, changedFiles)
            else
                runDiffAffected(state, changedFiles, platform)
        }
    }

    private def runDiffFull(state: State, platform: Option[String], changedFiles: Seq[String]): State = {
        val trigger = changedFiles.filter(f => f == "build.sbt" || f.startsWith("project/") || f.startsWith(".github/"))
        log(s"build/CI config changed (${trigger.mkString(", ")}) — running full test")
        val cmd = platform match {
            case Some(p) => s"kyo$p/test"
            case None    => "test"
        }
        log(s"running: $cmd")
        Command.process(cmd, state, msg => state.log.error(msg))
    }

    private def runDiffAffected(state: State, changedFiles: Seq[String], platform: Option[String]): State = {
        val extracted = Project.extract(state)
        val structure = extracted.structure
        val allRefs   = structure.allProjectRefs
        val allNames  = allRefs.map(_.project).toSet
        val bd        = extracted.get(buildDependencies)

        val directlyChanged = changedFiles.flatMap(fileToProjects(_, allNames)).toSet

        val filtered = platform match {
            case Some(p) => directlyChanged.filter(_.endsWith(p))
            case None    => directlyChanged
        }

        if (filtered.isEmpty) {
            log("no affected projects found — skipping tests")
            state
        } else {
            val dependentMap = transitiveDependents(allRefs, bd)
            val allAffected = filtered.flatMap { name =>
                allRefs.find(_.project == name) match {
                    case Some(ref) => dependentMap.getOrElse(ref, Set.empty).map(_.project) + name
                    case None      => Set(name)
                }
            }

            val toTest = platform match {
                case Some(p) => allAffected.filter(_.endsWith(p))
                case None    => allAffected
            }

            if (toTest.isEmpty) {
                log("no testable affected projects found — skipping tests")
                state
            } else {
                val sorted = toTest.toSeq.sorted
                log(s"directly changed: ${filtered.toSeq.sorted.mkString(", ")}")
                log(s"with dependents: ${sorted.mkString(", ")}")
                val commands = sorted.map(name => s"$name/test").mkString("; ")
                log(s"running: $commands")
                Command.process(commands, state, msg => state.log.error(msg))
            }
        }
    }

    // --- Helpers ---

    /** Map a changed file path to affected sbt project names.
      *
      * Cross-projects use shared/jvm/js/native subdirectories. Modules with flat src/ layout (e.g. kyo-bench) hit the default case which
      * tries all platforms; the .filter(allProjectNames.contains) ensures only actually-existing projects are returned.
      */
    private def fileToProjects(file: String, allProjectNames: Set[String]): Set[String] = {
        val parts = file.split("/").toList
        parts match {
            case module :: sub :: _ =>
                val affectedPlatforms = sub match {
                    case "shared" => Seq("JVM", "JS", "Native")
                    case "jvm"    => Seq("JVM")
                    case "js"     => Seq("JS")
                    case "native" => Seq("Native")
                    case _        => Seq("JVM", "JS", "Native")
                }
                affectedPlatforms.map(p => s"$module$p").filter(allProjectNames.contains).toSet
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

    private def buildConfigChanged(files: Seq[String]): Boolean =
        files.exists { f =>
            f == "build.sbt" ||
            f.startsWith("project/") ||
            f.startsWith(".github/")
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
}
