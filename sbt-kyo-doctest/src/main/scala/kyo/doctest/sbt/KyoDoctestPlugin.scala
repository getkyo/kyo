package kyo.doctest.sbt

import sbt._
import sbt.Keys._

/** sbt plugin for kyo-doctest Markdown code block validation.
  *
  * Auto-enables on any JVM project. Projects that do not want doctest must opt out with `.disablePlugins(KyoDoctestPlugin)`.
  *
  * By default validates README.md: first checks the project's own base directory, then one level up (to handle cross-project
  * JVM sub-directories such as `kyo-data/jvm/` resolving to `kyo-data/README.md`).
  *
  * Example:
  * {{{
  * lazy val myProject = project
  *     .settings(
  *         doctestSources := (baseDirectory.value ** "*.md").get
  *     )
  * }}}
  */
object KyoDoctestPlugin extends AutoPlugin {

    // Auto-enable on any project that satisfies the requires chain (JvmPlugin).
    // Projects that do not want doctest must call .disablePlugins(KyoDoctestPlugin).
    override def trigger = allRequirements

    override def requires = sbt.plugins.JvmPlugin

    // Task tag used to cap concurrent doctest forks across the whole build.
    // Each module's doctest forks a JVM and uses its full doctestParallel budget
    // internally; without a limit, the aggregate `doctest` command would launch
    // one fork per enabled project simultaneously and oversubscribe the machine.
    private val DoctestTag = Tags.Tag("doctest")

    object autoImport {

        /** Markdown files to scan for scala code blocks. Defaults to README.md in the project base directory. */
        val doctestSources: SettingKey[Seq[File]] = settingKey[Seq[File]](
            "Markdown files to validate with doctest (default: README.md in the project base directory)."
        )

        /** Additional scalac options forwarded to the compiler for each block. */
        val doctestScalacOptions: SettingKey[Seq[String]] = settingKey[Seq[String]](
            "Additional scalac options forwarded to the doctest compiler (default: [\"-release\", \"17\"])."
        )

        /** Directory used for the content-hash cache. Created if absent. */
        val doctestCacheDir: SettingKey[File] = settingKey[File](
            "Cache directory for doctest results (default: target/doctest-cache)."
        )

        /** Maximum number of blocks compiled concurrently. */
        val doctestParallel: SettingKey[Int] = settingKey[Int](
            "Maximum number of blocks compiled concurrently (default: available processor count)."
        )

        /** Lines auto-injected at the top of every block's wrapped source, before the user body.
          *
          * Visible to ALL block scopes including `scope=env:NAME` groups. Each element becomes one line. Empty by default; downstream
          * projects (e.g. kyo-* modules) set their own common imports here.
          */
        val doctestPredef: SettingKey[Seq[String]] = settingKey[Seq[String]](
            "Lines auto-injected at the top of every block's wrapped source (default: empty)."
        )

        /** Rebuild the dotty Compiler per block instead of reusing one warm instance. Required for modules whose macros register
          * denotations in the compiler's symbol table (notably dotty-cps-async); without this, a "denotation invalid in run N" assertion
          * fires on the second compile. Significantly slower; opt in only when needed.
          */
        val doctestFreshDriver: SettingKey[Boolean] = settingKey[Boolean](
            "When true, rebuild the dotty Compiler per block (default: false). Set to true for modules using dotty-cps-async."
        )

        /** JVM options forwarded to the forked dotty driver. Default sets -Xmx8G + -Xss10M; raise the heap for inline-heavy macro
          * expansion (kyo-http, kyo-flow).
          */
        val doctestForkJavaOptions: SettingKey[Seq[String]] = settingKey[Seq[String]](
            "JVM options forwarded to the forked doctest driver (default: -Xmx8G -Xss10M)."
        )

        /** Run validation; exit 1 on any block failure. Writes the cache. */
        val doctest: TaskKey[Unit] = taskKey[Unit](
            "Validate all scala code blocks in doctestSources. Exits 1 on any failure."
        )

        /** Run validation in a throwaway cache directory. Useful for CI configurations that want guaranteed cold runs. */
        val doctestFresh: TaskKey[Unit] = taskKey[Unit](
            "Run doctest with a fresh throwaway cache directory."
        )

        /** Empty the cache directory. The next doctest run will be fully cold. */
        val doctestClean: TaskKey[Unit] = taskKey[Unit](
            "Empty the doctest cache directory."
        )
    }

    import autoImport._

    // Sub-directory names produced by sbt-crossproject for non-JVM platforms.
    // The doctest task forks a JVM and would crash on scala-native or scala-js
    // compiled .class files (UndefinedBehaviorError, NoClassDefFoundError),
    // so the aggregate skips these.
    private val nonJvmCrossDirs = Set("native", "js")

    private def projectsWithDoctest(state: State): Seq[ProjectRef] = {
        val structure = Project.extract(state).structure
        structure.allProjectRefs.filter { ref =>
            structure.allProjects.find(_.id == ref.project).exists { p =>
                p.autoPlugins.contains(KyoDoctestPlugin) && !nonJvmCrossDirs.contains(p.base.getName)
            }
        }
    }

    private def aggregateCommand(name: String): Command =
        Command.command(name) { state =>
            val refs = projectsWithDoctest(state)
            if (refs.isEmpty) {
                state.log.warn(s"$name: no projects have KyoDoctestPlugin enabled")
                state
            } else {
                // Schedule all module doctest tasks as a single `all` task so sbt
                // executes them in its task engine, where the DoctestTag limit
                // (see globalSettings) caps concurrent forks. Each module's
                // doctest forks a JVM that uses its own doctestParallel budget
                // internally; the global cap prevents N concurrent forks each
                // using all cores from oversubscribing the machine.
                val cmds = refs.map(r => s"${r.project}/$name").mkString(" ")
                s"all $cmds" :: state
            }
        }

    override lazy val globalSettings: Seq[Setting[_]] = Seq(
        commands ++= Seq(
            aggregateCommand("doctest"),
            aggregateCommand("doctestFresh"),
            aggregateCommand("doctestClean")
        ),
        // Cap concurrent doctest task instances across the whole build. Each
        // task forks a JVM that runs a dotty driver, so unbounded parallelism
        // (one fork per enabled project at once) oversubscribes the machine.
        // 2 keeps total parallel forks small while letting cross-module work
        // overlap and serves cache hits cheaply.
        concurrentRestrictions += Tags.limit(DoctestTag, 2)
    )

    /** Version of the kyo-doctest library to inject.
      *
      * Reads from the system property `plugin.version` when set (the sbt scripted framework sets this to `version.value` at launch time).
      * Falls back to the jar manifest implementation version for production use (when the jar is loaded from a published artifact). The
      * plugin and kyo-doctest always share the same version since they live in the same repository.
      */
    private[sbt] val pluginVersion: String =
        sys.props.getOrElse(
            "plugin.version",
            Option(getClass.getPackage.getImplementationVersion).getOrElse("0.0.0+SNAPSHOT")
        )

    override lazy val projectSettings: Seq[Setting[_]] = Seq(
        // Inject kyo-doctest library into Test scope so Test/fullClasspath includes it.
        // This ensures the forked JVM launched by Runner can find kyo.doctest.internal.cli.Main.
        libraryDependencies += "io.getkyo" %% "kyo-doctest" % pluginVersion % Test,

        doctestSources := {
            val base   = baseDirectory.value
            val direct = base / "README.md"
            val parent = base / ".." / "README.md"
            if (direct.exists()) Seq(direct)
            else if (parent.exists()) Seq(parent)
            else Seq.empty
        },
        doctestScalacOptions := Seq("-release", "17"),
        doctestCacheDir      := target.value / "doctest-cache",
        // Per-module block compile concurrency. The aggregate `doctest` command
        // runs multiple modules through sbt's task engine concurrently (capped
        // by DoctestTag in globalSettings), and each module's forked JVM uses
        // this budget internally. The default halves availableProcessors so the
        // product across modules stays bounded; override per-module when a
        // README has many independent blocks and is run in isolation.
        doctestParallel      := math.max(1, java.lang.Runtime.getRuntime.availableProcessors / 2),
        doctestPredef        := Seq.empty[String],
        doctestFreshDriver   := false,
        doctestForkJavaOptions := Seq("-Xmx8G", "-Xss10M"),

        doctest := (Def.task {
            val log         = streams.value.log
            val sources     = doctestSources.value
            val classpath   = (Test / fullClasspath).value.files
            val scalacOpts  = doctestScalacOptions.value
            val cacheDir    = doctestCacheDir.value
            val parallel    = doctestParallel.value
            val predef      = doctestPredef.value
            val freshDriver = doctestFreshDriver.value
            val forkOpts    = doctestForkJavaOptions.value
            Runner.run(
                sources         = sources,
                classpath       = classpath,
                scalacOpts      = scalacOpts,
                cacheDir        = cacheDir,
                parallel        = parallel,
                predef          = predef,
                freshDriver     = freshDriver,
                forkJavaOptions = forkOpts,
                writeCache  = true,
                log         = log
            )
        }).tag(DoctestTag).value,

        doctestFresh := (Def.task {
            val log         = streams.value.log
            val sources     = doctestSources.value
            val classpath   = (Test / fullClasspath).value.files
            val scalacOpts  = doctestScalacOptions.value
            val cacheDir    = doctestCacheDir.value
            val parallel    = doctestParallel.value
            val predef      = doctestPredef.value
            val freshDriver = doctestFreshDriver.value
            val forkOpts    = doctestForkJavaOptions.value
            Runner.run(
                sources         = sources,
                classpath       = classpath,
                scalacOpts      = scalacOpts,
                cacheDir        = cacheDir,
                parallel        = parallel,
                predef          = predef,
                freshDriver     = freshDriver,
                forkJavaOptions = forkOpts,
                writeCache  = false,
                log         = log
            )
        }).tag(DoctestTag).value,

        doctestClean := {
            val log      = streams.value.log
            val cacheDir = doctestCacheDir.value
            if (cacheDir.exists()) {
                IO.delete(cacheDir)
                log.info(s"doctest: cleaned cache at $cacheDir")
            } else {
                log.info(s"doctest: cache dir $cacheDir does not exist, nothing to clean")
            }
        }
    )
}
