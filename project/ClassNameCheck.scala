import java.io.File
import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbtcrossproject.CrossPlugin.autoImport.crossProjectPlatform

/** The rule that a class name has one producing project per platform.
  *
  * A classpath holding two classes under one name keeps the first and drops the rest, in silence: the JVM classloader, the Scala.js
  * linker (`IRLoader`, "Remove duplicates. Just like the JVM") and the Scala Native linker (`ClassLoader.load`, `collectFirst`) all do.
  * A compile error follows only where code resolves the name and finds the wrong members, so the duplicate itself has to be what fails.
  *
  * Usage: `checkClassNames JVM` (also JS, Native, Wasm), or `checkClassNames --self-test` for the fixtures.
  */
object ClassNameCheck {

    val classNameGroup: SettingKey[Option[String]] =
        settingKey[Option[String]](
            "Group whose projects produce the same classes by design, as kyo-compat's bindings do. Members may share a class name, as long as no classpath holds two copies of it."
        )

    val checkClassNames: InputKey[Unit] =
        inputKey[Unit]("Fails when two projects produce the same class. Takes one platform: JVM, JS, Native or Wasm.")

    private val platforms = Seq("JVM", "JS", "Native", "Wasm")

    private val selfTestArg = "--self-test"

    /** Added with `inThisBuild`, so `checkClassNames` resolves from any project and `classNameGroup` falls back to None. */
    val settings: Seq[Setting[?]] = Seq(
        classNameGroup  := None,
        checkClassNames := checkTask.evaluated,
        // The task reads every project itself, so the root's aggregation would only run the same scan once per aggregated project.
        checkClassNames / aggregate := false
    )

    /** A (project, configuration) pair and the directory it writes its classes to. */
    final case class Producer(project: String, config: String, namespace: String, group: Option[String], classes: File) {
        def name: String = project + " (" + (if (config == "compile") "main" else config) + ")"
    }

    /** One class file path produced by more than one (project, configuration) pair. */
    final case class Collision(namespace: String, path: String, producers: Seq[Producer]) {

        /** True when every producer declares the same group, the one case where the duplicate is intended. */
        def sharedByGroup: Boolean = {
            val groups = producers.map(_.group).distinct
            groups.size == 1 && groups.head.isDefined
        }

        def group: String = producers.head.group.get

        /** The top level definition the class file belongs to, which is what a reader renames. */
        def owner: String = {
            val full = path.dropRight(".class".length).replace('/', '.')
            val at   = full.indexOf('$')
            if (at < 0) full
            else {
                val head = full.substring(0, at)
                val rest = full.substring(at)
                if (rest.startsWith("$package")) head + "$package" else head
            }
        }
    }

    private def argParser: Parser[String] = (Space ~> token(StringBasic, "<platform>")).examples(platforms :+ selfTestArg: _*)

    /** The namespaces a platform argument selects. The sbt plugins are JVM projects, and they share a classpath with each other inside a
      * user's build definition rather than with anything running on the JVM, so they are their own namespace.
      */
    private def namespacesOf(platform: String): Set[String] =
        if (platform == "jvm") Set("jvm", "sbt") else Set(platform)

    private val producerInfo: Def.Initialize[Task[(String, String, Option[String], File)]] =
        Def.task {
            val _ = (Keys.compile).value
            (thisProjectRef.value.project, configuration.value.name, classNameGroup.value, classDirectory.value)
        }

    /** What each project's test classpath can reach, as (project, configuration) pairs.
      *
      * Read from the project graph rather than from `fullClasspath`, whose products run every project's resource generators, which build
      * the FFI modules' C shims. The graph misses what arrives through `unmanagedClasspath`, which is the kyo-test runner alone, and no
      * group member arrives that way.
      */
    private def reachableOf(extracted: Extracted, refs: Seq[ProjectRef]): Map[String, Set[(String, String)]] = {
        val data = extracted.structure.data
        val deps = extracted.get(buildDependencies)
        refs.map { ref =>
            val closure = Classpaths.interSort(ref, Test, data, deps).map { case (r, c) => (r.project, c) }.toSet
            // A project's own two outputs are on its own test classpath whatever the closure says.
            ref.project -> (closure + ((ref.project, "compile")) + ((ref.project, "test")))
        }.toMap
    }

    private def checkTask: Def.Initialize[InputTask[Unit]] = Def.inputTaskDyn {
        val requested = argParser.parsed
        val extracted = Project.extract(state.value)

        if (requested == selfTestArg) Def.task(selfTest(state.value.log))
        else {
            val platform = platforms.find(_.equalsIgnoreCase(requested)).map(_.toLowerCase).getOrElse {
                sys.error("checkClassNames: unknown argument '" + requested + "', expected " + (platforms :+ selfTestArg).mkString(", "))
            }
            val namespaces = namespacesOf(platform)

            // A cross project carries its platform; a plain project is an sbt plugin or a JVM project.
            val classified = extracted.structure.allProjectRefs.map { ref =>
                val fromCross = extracted.getOpt(ref / crossProjectPlatform).map(_.identifier)
                val isPlugin  = extracted.getOpt(ref / sbtPlugin).getOrElse(false)
                ref -> fromCross.getOrElse(if (isPlugin) "sbt" else "jvm")
            }
            // A project with no sources produces no classes, and compiling it can fail for its own reasons: the platform aggregates
            // hold nothing but an aggregate list, and `kyoNative / update` cannot resolve their dependencies.
            def hasSources(ref: ProjectRef): Boolean =
                Seq(Compile, Test).exists { config =>
                    extracted.getOpt(ref / config / unmanagedSourceDirectories).toSeq.flatten.exists { dir =>
                        dir.isDirectory && (dir ** (GlobFilter("*.scala") | GlobFilter("*.java"))).get.nonEmpty
                    }
                }

            val inNamespace        = classified.collect { case (ref, ns) if namespaces.contains(ns) => ref }.filter(hasSources)
            val namespaceOfProject = classified.map { case (ref, ns) => ref.project -> ns }.toMap

            val reachableAll = reachableOf(extracted, inNamespace)
            val scalaOf      = classified.map { case (ref, _) => ref.project -> extracted.get(ref / scalaVersion) }.toMap

            // A project pinned to a Scala version its dependencies are not built at cannot compile in one session: kyo-compat-tests is
            // 3.3 while the bindings it depends on default to the primary version, so reading their TASTy fails. `testKyo` runs the
            // primary version plus the 2.x cross-builds, so no pass selects it either, and what it holds is covered in CI through
            // kyo-compat-plugin's scripted suite. Skipping it is stated, never silent.
            val (selected, mixed) = inNamespace.partition { ref =>
                reachableAll(ref.project).forall { case (project, _) => scalaOf.get(project).forall(_ == scalaOf(ref.project)) }
            }

            val inProducers = ScopeFilter(inProjects(selected*), inConfigurations(Compile, Test))
            val reachable   = reachableOf(extracted, selected)

            Def.task {
                val log = state.value.log
                log.info("checkClassNames " + requested + ": " + selected.size + " projects")
                if (mixed.nonEmpty)
                    log.info(
                        "checkClassNames " + requested + ": skipping " + mixed.map(_.project).sorted.mkString(", ") +
                            ", pinned to a Scala version their dependencies are not built at"
                    )

                val produced = producerInfo.all(inProducers).value.map { case (project, config, group, classes) =>
                    Producer(project, config, namespaceOfProject(project), group, classes)
                }

                val lines = report(collisions(produced), reachable)
                if (lines.nonEmpty) sys.error(lines.mkString(System.lineSeparator))
                else log.info("checkClassNames " + requested + ": no duplicate class names")
            }
        }
    }

    /** Every class file path produced by more than one (project, configuration) pair, per namespace. A name on one platform and the same
      * name on another never share a classpath, so the namespace is part of the key.
      */
    def collisions(producers: Seq[Producer]): Seq[Collision] = {
        val byPath = new scala.collection.mutable.HashMap[(String, String), List[Producer]]
        producers.foreach { producer =>
            classFilesOf(producer.classes).foreach { path =>
                val key = (producer.namespace, path)
                byPath.update(key, producer :: byPath.getOrElse(key, Nil))
            }
        }
        byPath.toSeq.collect {
            case ((namespace, path), owners) if owners.size > 1 => Collision(namespace, path, owners.sortBy(_.name))
        }.sortBy(c => (c.namespace, c.owner, c.path))
    }

    /** The failure report, empty when the build is clean.
      *
      * Main output ships, and users combine artifacts this build never combines, so a name it produces has one owner whatever the
      * project graph says. Test output ships nowhere: two test classes under one name can only meet through this build's own
      * `test->test` edges, which the graph shows in full, so what fails there is the meeting rather than the name.
      */
    def report(found: Seq[Collision], reachable: Map[String, Set[(String, String)]]): Seq[String] = {
        val (mainOnly, withTest)   = found.partition(_.producers.forall(_.config == "compile"))
        val (byDesign, duplicates) = mainOnly.partition(_.sharedByGroup)

        val produced =
            if (duplicates.isEmpty) Nil
            else
                Seq("Class names produced by more than one project. Every classpath keeps the first and drops the others.") ++
                    duplicates
                        .groupBy(c => (c.namespace, c.owner))
                        .toSeq
                        .sortBy(_._1)
                        .map { case ((namespace, owner), group) =>
                            "  " + namespace + "  " + owner + "  " + group.head.producers.map(_.name).mkString(", ")
                        } ++
                    Seq("Rename one of them. Visibility does not help: a name in kyo or kyo.internal is shared by every kyo module.")

        val met    = onOneClasspath(byDesign ++ withTest, reachable)
        val shared =
            if (met.isEmpty) Nil
            else Seq("Class names that two projects put on one classpath, where the first wins and the other is dropped:") ++ met

        produced ++ shared
    }

    /** One name whose producers a project can reach twice. For a group this is the contract the group records; for test output it is
      * the whole rule.
      */
    private def onOneClasspath(found: Seq[Collision], reachable: Map[String, Set[(String, String)]]): Seq[String] =
        reachable.toSeq.sortBy(_._1).flatMap { case (project, entries) =>
            found
                .groupBy(c => (c.namespace, c.owner))
                .toSeq
                .sortBy(_._1)
                .flatMap { case ((_, owner), group) =>
                    val collision = group.head
                    val present   = collision.producers.filter(p => entries.contains((p.project, p.config)))
                    if (present.size < 2) None
                    else {
                        val note = if (collision.sharedByGroup) " (group " + collision.group + ")" else ""
                        Some("  " + project + "  " + owner + note + "  " + present.map(_.name).mkString(", "))
                    }
                }
        }

    /** Class files under `dir`, relative to it. `module-info.class` is one per artifact by design. */
    private def classFilesOf(dir: File): Seq[String] =
        if (!dir.isDirectory) Nil
        else
            (dir ** "*.class").get
                .flatMap(file => IO.relativize(dir, file))
                .filter(_ != "module-info.class")
                .map(_.replace(File.separatorChar, '/'))

    /** Fixtures over synthetic class directories, so the check keeps being tested once the build itself is clean. */
    private def selfTest(log: Logger): Unit = {
        val base    = IO.createTemporaryDirectory
        val results = new scala.collection.mutable.ListBuffer[String]

        def producer(project: String, config: String, namespace: String, group: Option[String], classes: String*): Producer = {
            val dir = base / project / config
            classes.foreach(path => IO.write(dir / path, ""))
            Producer(project, config, namespace, group, dir)
        }

        def check(name: String, holds: Boolean): Unit = {
            results += ((if (holds) "ok " else "NO ") + name)
            if (!holds) log.error("self-test failed: " + name)
        }

        def reportOf(producers: Seq[Producer], reachable: Map[String, Set[(String, String)]] = Map.empty): String =
            report(collisions(producers), reachable).mkString("\n")

        try {
            val a = producer("a", "compile", "jvm", None, "p/X.class", "p/X$.class")
            val b = producer("b", "compile", "jvm", None, "p/X.class")
            check("two projects producing one class collide", reportOf(Seq(a, b)).contains("p.X"))
            check(
                "the report names both producers", {
                    val out = reportOf(Seq(a, b))
                    out.contains("a (main)") && out.contains("b (main)")
                }
            )
            check("nested classes report their top level owner", !reportOf(Seq(a, b)).contains("p.X$"))

            val aTest = producer("a", "test", "jvm", None, "p/XTest.class")
            val bTest = producer("b", "test", "jvm", None, "p/XTest.class")
            check("test output two projects never combine is fine", reportOf(Seq(aTest, bTest)).isEmpty)
            check(
                "test output two projects do combine fails", {
                    val out = reportOf(Seq(aTest, bTest), Map("b" -> Set(("b", "test"), ("a", "test"))))
                    out.contains("p.XTest") && out.contains("a (test)")
                }
            )

            val cMain = producer("c", "compile", "jvm", None, "p/Y.class")
            val cTest = producer("c", "test", "jvm", None, "p/Y.class")
            check(
                "a project's test output repeating its own main name fails",
                reportOf(Seq(cMain, cTest), Map("c" -> Set(("c", "compile"), ("c", "test")))).contains("p.Y")
            )
            val shadowTest = producer("shadow", "test", "jvm", None, "p/W.class")
            val shadowMain = producer("shadowed", "compile", "jvm", None, "p/W.class")
            check(
                "a test class shadowing another project's main class fails where they meet",
                reportOf(
                    Seq(shadowTest, shadowMain),
                    Map("shadow" -> Set(("shadow", "test"), ("shadowed", "compile")))
                ).contains("p.W")
            )
            check("the same pair, never combined, is fine", reportOf(Seq(shadowTest, shadowMain)).isEmpty)

            val jsX  = producer("d", "compile", "js", None, "p/X.class")
            val jvmX = producer("e", "compile", "jvm", None, "p/X.class")
            check("one name on two platforms is not a collision", reportOf(Seq(jsX, jvmX)).isEmpty)

            val fPkg = producer("f", "compile", "jvm", None, "p/Util$package.class", "p/Util$package$.class")
            val gPkg = producer("g", "compile", "jvm", None, "p/Util$package.class")
            check("top level definitions collide under their $package class", reportOf(Seq(fPkg, gPkg)).contains("p.Util$package"))

            val onlyModuleInfoA = producer("h", "compile", "jvm", None, "module-info.class")
            val onlyModuleInfoB = producer("i", "compile", "jvm", None, "module-info.class")
            check("module-info.class is not a collision", reportOf(Seq(onlyModuleInfoA, onlyModuleInfoB)).isEmpty)

            val groupA = producer("ga", "compile", "jvm", Some("grp"), "p/Z.class")
            val groupB = producer("gb", "compile", "jvm", Some("grp"), "p/Z.class")
            check("group members may produce one class name", reportOf(Seq(groupA, groupB)).isEmpty)
            check(
                "a group's class on one classpath fails", {
                    val out = reportOf(Seq(groupA, groupB), Map("consumer" -> Set(("ga", "compile"), ("gb", "compile"))))
                    out.contains("consumer") && out.contains("p.Z") && out.contains("grp")
                }
            )
            check(
                "a classpath with one group member is fine",
                reportOf(Seq(groupA, groupB), Map("consumer" -> Set(("ga", "compile")))).isEmpty
            )
            check(
                // kyo-compat-tests: it compiles the bindings' shared suite once more and depends on one of them.
                "a member holding only the shared suite, and depending on one other member, is fine", {
                    val memberAMain = producer("ma", "compile", "jvm", Some("grp"), "p/Api.class")
                    val memberBMain = producer("mb", "compile", "jvm", Some("grp"), "p/Api.class")
                    val memberATest = producer("ma", "test", "jvm", Some("grp"), "p/SuiteTest.class")
                    val anchorTest  = producer("anchor", "test", "jvm", Some("grp"), "p/SuiteTest.class")
                    reportOf(
                        Seq(memberAMain, memberBMain, memberATest, anchorTest),
                        Map("anchor" -> Set(("anchor", "test"), ("anchor", "compile"), ("ma", "compile")))
                    ).isEmpty
                }
            )

            val outsider = producer("out", "compile", "jvm", None, "p/Z.class")
            check("a group member and an outsider collide", reportOf(Seq(groupA, groupB, outsider)).contains("p.Z"))

            val otherGroup = producer("og", "compile", "jvm", Some("other"), "p/Z.class")
            check("members of two groups collide", reportOf(Seq(groupA, otherGroup)).contains("p.Z"))

            val clean = producer("clean", "compile", "jvm", None, "p/Unique.class")
            check("a build with no duplicate reports nothing", reportOf(Seq(clean, groupA)).isEmpty)

            val failures = results.count(_.startsWith("NO "))
            results.foreach(line => log.info("  " + line))
            if (failures > 0) sys.error("checkClassNames --self-test: " + failures + " of " + results.size + " checks failed")
            else log.info("checkClassNames --self-test: " + results.size + " checks passed")
        } finally IO.delete(base)
    }
}
