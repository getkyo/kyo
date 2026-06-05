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
    //
    // Exposed via autoImport.DoctestTag so a build that replaces sbt's
    // `Global / concurrentRestrictions` wholesale (e.g. with `:=` rather than
    // `+=`) can include the doctest limit explicitly. The plugin still adds the
    // limit via `+=` in globalSettings, but `:=` in a downstream build will
    // override it without an explicit reference.
    private[sbt] val DoctestTag: Tags.Tag = Tags.Tag("doctest")

    object autoImport {

        /** Task tag attached to every `doctest` / `doctestFresh` task. A build that replaces sbt's
          * `Global / concurrentRestrictions` with `:=` (instead of `+=`) should include
          * `Tags.limit(KyoDoctestPlugin.DoctestTag, N)` in the replacement list to keep the
          * per-fork concurrency bound the plugin would otherwise add.
          */
        val DoctestTag: Tags.Tag = KyoDoctestPlugin.DoctestTag

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

        /** Extra jars appended to the doctest fork's classpath, used to inject the kyo-doctest library without going through
          * `Test / unmanagedJars` (which would leak onto Test compile and crash dotty on Scala 3 LTS fallback modules).
          *
          * When an entry here contributes a `scala3-library_3-*.jar`, the plugin drops mismatched copies coming from
          * `Test / fullClasspath` so the fork sees exactly one scala3-library, matching the dotty driver inside this classpath.
          */
        val doctestExtraClasspath: TaskKey[Seq[File]] = taskKey[Seq[File]](
            "Extra jars appended to the doctest fork's classpath (default: empty)."
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
    // The doctest task forks a JVM and would crash on scala-native, scala-js, or
    // scala-js/WebAssembly compiled .class files (UndefinedBehaviorError,
    // NoClassDefFoundError, "native JS type called on the JVM"), so the aggregate
    // skips these. "wasm" is the Scala.js WebAssembly backend
    // (WasmPlatform.identifier), in the same JVM-incompatible category as "js".
    private val nonJvmCrossDirs = Set("native", "js", "wasm")

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
            } else if (name == "doctestClean") {
                // doctestClean has no upstream compile dependency, just emit it.
                val cmds = refs.map(r => s"${r.project}/$name").mkString(" ")
                s"all $cmds" :: state
            } else {
                // Two-phase scheduling. Phase 1: bring every doctest-enabled
                // module's Test scope up to date under sbt's normal task
                // parallelism, so the compile cascade is its own bounded burst
                // rather than interleaving with doctest forks. Phase 2: run
                // doctest tasks; Test/fullClasspath is already up-to-date so
                // the only work is the forks themselves, subject to
                // Tags.limit(DoctestTag, 1) in globalSettings.
                val compileCmds = refs.map(r => s"${r.project}/Test/compile").mkString(" ")
                val doctestCmds = refs.map(r => s"${r.project}/$name").mkString(" ")
                s"all $compileCmds" :: s"all $doctestCmds" :: state
            }
        }

    override lazy val globalSettings: Seq[Setting[?]] = Seq(
        commands ++= Seq(
            aggregateCommand("doctest"),
            aggregateCommand("doctestFresh"),
            aggregateCommand("doctestClean")
        ),
        // Cap concurrent doctest task instances across the whole build to 1.
        // Each task forks a JVM that runs a dotty driver; serialising at the
        // tag level keeps the plugin's CPU contribution bounded to a single
        // fork regardless of how many modules have doctest enabled. The fork
        // itself is further capped by doctestForkJavaOptions's
        // -XX:ActiveProcessorCount=2.
        concurrentRestrictions += Tags.limit(DoctestTag, 1)
    )

    private val scala3LibPattern = """^scala3-library_3-.*\.jar$""".r

    /** Merges `base` (project's Test/fullClasspath) with `extra` (doctest framework classpath). When both bring a
      * `scala3-library_3-*.jar` at different versions, only `extra`'s is kept (the dotty driver inside `extra` was compiled
      * against it).
      */
    private[sbt] def reconcileClasspath(base: Seq[File], extra: Seq[File]): Seq[File] = {
        val extraScala3Lib = extra.find(f => scala3LibPattern.findFirstIn(f.getName).isDefined).map(_.getName)
        val keptBase = extraScala3Lib match {
            case Some(keepName) =>
                base.filterNot(f => scala3LibPattern.findFirstIn(f.getName).isDefined && f.getName != keepName)
            case None => base
        }
        keptBase ++ extra
    }

    override lazy val projectSettings: Seq[Setting[?]] = Seq(
        doctestExtraClasspath := Seq.empty,
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
        // Per-module fiber concurrency inside the doctest fork. Block compiles
        // are serialised on a single compiler-thread executor because dotty's
        // ContextBase has a thread-ownership assertion, so a higher value here
        // adds fibers that all queue on the same single thread (no speedup on
        // cold runs, measured 10s for both 1 and 6 on a 54-block kyo-core
        // compile). Cache lookups are fast enough that the IO concurrency from
        // a higher setting is not measurable. Default of 1 keeps the fiber
        // count equal to the actual compile concurrency.
        doctestParallel    := 1,
        doctestPredef      := Seq.empty[String],
        doctestFreshDriver := false,
        // -XX:ActiveProcessorCount caps the JVM's view of available processors,
        // so dotty's internal worker pools (backend, parallel phases, JIT
        // compilation) size themselves to that number. Keeps a fork's CPU
        // contribution bounded to ~2 cores regardless of the host's core count.
        doctestForkJavaOptions := Seq("-Xmx8G", "-Xss10M", "-XX:ActiveProcessorCount=2"),
        doctest := Def.task {
            val log         = streams.value.log
            val sources     = doctestSources.value
            val baseCp      = (Test / fullClasspath).value.files
            val extraCp     = doctestExtraClasspath.value
            val classpath   = reconcileClasspath(baseCp, extraCp)
            val scalacOpts  = doctestScalacOptions.value
            val cacheDir    = doctestCacheDir.value
            val parallel    = doctestParallel.value
            val predef      = doctestPredef.value
            val freshDriver = doctestFreshDriver.value
            val forkOpts    = doctestForkJavaOptions.value
            val scalafmtCfg = (LocalRootProject / baseDirectory).value / ".scalafmt.conf"
            // Auto-format the markdown blocks in place before validating, so doc examples stay in the
            // codebase's scalafmt style without a separate command. No-op when no .scalafmt.conf is
            // present; blocks that fail to parse (or carry a bare `noformat` fence token) are left as-is.
            Formatter.run(sources, scalafmtCfg, log)
            Runner.run(
                sources = sources,
                classpath = classpath,
                scalacOpts = scalacOpts,
                cacheDir = cacheDir,
                parallel = parallel,
                predef = predef,
                freshDriver = freshDriver,
                forkJavaOptions = forkOpts,
                writeCache = true,
                log = log
            )
        }.tag(DoctestTag).value,
        doctestFresh := Def.task {
            val log         = streams.value.log
            val sources     = doctestSources.value
            val baseCp      = (Test / fullClasspath).value.files
            val extraCp     = doctestExtraClasspath.value
            val classpath   = reconcileClasspath(baseCp, extraCp)
            val scalacOpts  = doctestScalacOptions.value
            val cacheDir    = doctestCacheDir.value
            val parallel    = doctestParallel.value
            val predef      = doctestPredef.value
            val freshDriver = doctestFreshDriver.value
            val forkOpts    = doctestForkJavaOptions.value
            Runner.run(
                sources = sources,
                classpath = classpath,
                scalacOpts = scalacOpts,
                cacheDir = cacheDir,
                parallel = parallel,
                predef = predef,
                freshDriver = freshDriver,
                forkJavaOptions = forkOpts,
                writeCache = false,
                log = log
            )
        }.tag(DoctestTag).value,
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
