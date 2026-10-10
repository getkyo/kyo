package kyo.natives.sbt

import kyo.ffi.sbt.NativeTargets
import sbt._
import sbt.Keys._

/** Delivers the shared libraries kyo's published artifacts carry to the application that depends on them.
  *
  * Enable it once, on any platform:
  *
  * {{{
  * lazy val app = project.enablePlugins(KyoNativesPlugin)
  * }}}
  *
  * What that means differs by platform, because the three runtimes load a library in three different ways. On the JVM
  * the loader extracts from the classpath, so the plugin puts the per-target classifier jars on it. On Scala Native
  * there is no loader at all, so the plugin unpacks each library and links the binary against it. On Node koffi reads
  * the filesystem rather than the classpath, so the plugin writes the libraries where koffi looks. The keys below are
  * shared; [[KyoNativesNativePlugin]] and [[KyoNativesJSPlugin]] add the per-platform wiring and enable themselves
  * wherever their platform plugin is.
  *
  * Nothing here knows which kyo modules exist. Each artifact declares the shared libraries it delivers and the
  * classifier carrying them, and this reads those declarations off the project's own classpath.
  *
  * It reads that classpath and writes under that project's `target`, so in a multi-project build it is enabled on
  * each project that runs or packages the natives, not once at the root. A root aggregate has no classpath of its
  * own and delivers nothing. On a crossProject, `enablePlugins` covers every leg and
  * `.nativeConfigure(_.enablePlugins(KyoNativesPlugin))` covers one.
  */
object KyoNativesPlugin extends AutoPlugin {

    override def trigger  = noTrigger
    override def requires = plugins.JvmPlugin

    object autoImport {

        val kyoNativesTargets = settingKey[Seq[String]](
            "The `<os>-<arch>` targets to deliver libraries for; empty (the default) means the one this build is for. " +
                "A JVM classpath and a Node bundle are portable and resolve their library at runtime, so naming more than " +
                "one there is how an artifact built on one machine runs on another. A linked binary is built for exactly " +
                "one target, so more than one is an error there."
        )

        val kyoNativesSource = settingKey[NativesSource](
            "Where the libraries come from and what a missing one costs: Auto (deliver what the artifacts carry), " +
                "Jar (fail the build when one is missing for the target), Disabled (contribute nothing)."
        )

        val kyoNativesDirectory = settingKey[File]("Directory the unpacked libraries are written under, one subdirectory per target.")

        val kyoNativesResolvedTargets =
            taskKey[Seq[String]]("The targets in effect, after deriving the ones `kyoNativesTargets` left open.")

        val kyoNativesReport = taskKey[Unit]("Print what each library resolved to, and what it is wired into.")

        type NativesSource = kyo.natives.sbt.NativesSource
        val NativesSource = kyo.natives.sbt.NativesSource
    }

    import autoImport._

    /** The libraries, grouped by the target they were unpacked for. `kyoNativesReport` is how a build asks what it
      * got; this is what the per-platform plugins link, stage and materialize from.
      */
    private[sbt] val kyoNativesFetched = taskKey[Seq[(String, Delivery.Fetched)]]("Every library this project delivers, with its target.")

    /** What the dependencies declare, before anything is resolved. This is what separates a build that asked for
      * libraries from one that never asked: [[kyoNativesFetched]] is equally empty when a release carries no library
      * for the target, which is the case where a misconfigured target most needs to be reported.
      */
    private[sbt] val kyoNativesRequests =
        taskKey[Seq[(String, Delivery.Request)]]("Every library this project's dependencies declare, with the target it is wanted for.")

    override def projectSettings: Seq[Setting[?]] = Seq(
        kyoNativesTargets   := Nil,
        kyoNativesSource    := NativesSource.Auto,
        kyoNativesDirectory := target.value / "kyo-natives",
        // The host, which is the only target a build knows without being told. The Native plugin replaces this with
        // the one its target triple names, since cross-compiling there is ordinary.
        kyoNativesResolvedTargets := {
            val explicit = kyoNativesTargets.value
            if (explicit.nonEmpty) explicit else Seq(NativeTargets.host)
        },
        kyoNativesRequests := requestsTask(Compile).value,
        kyoNativesFetched  := {
            val log = streams.value.log
            fetch(kyoNativesRequests.value, kyoNativesSource.value, dependencyResolution.value, kyoNativesDirectory.value, log)
        },
        // A test binary, a test classpath and a Node test run read the Test configuration, where a test-only dependency
        // declares libraries the production artifact never sees. Those are fetched apart from the production ones, under
        // their own directory, so a test-only library can neither reach the shipped artifact nor race the production
        // fetch writing the same file.
        Test / kyoNativesRequests := requestsTask(Test).value,
        Test / kyoNativesFetched  := {
            val log        = streams.value.log
            val production = (Compile / kyoNativesFetched).value
            val shared     = (Compile / kyoNativesRequests).value.toSet
            val testOnly   = (Test / kyoNativesRequests).value.filterNot(shared)
            production ++ fetch(testOnly, kyoNativesSource.value, dependencyResolution.value, kyoNativesDirectory.value / "test", log)
        },
        kyoNativesReport := reportTask.value,
        // The JVM loader extracts from the classpath, so the classifier jars go on it. Through `unmanagedJars` rather
        // than `libraryDependencies`: a dependency reaches `makePom`, which would pin this build host's architecture
        // onto everyone who then depends on this project. `unmanagedJars` reaches `fullClasspath`, which is what `run`,
        // `test`, sbt-assembly and sbt-native-packager read, and reaches neither `makePom` nor `packageBin`. All three
        // scopes, because a packaging tool reads whichever one it was written against; the jars hold resources and no
        // classes, so a scope that does not need them pays a directory scan and nothing else.
        Compile / unmanagedJars ++= jvmJars(kyoNativesFetched).value,
        Runtime / unmanagedJars ++= jvmJars(kyoNativesFetched).value,
        Test / unmanagedJars ++= jvmJars(Test / kyoNativesFetched).value
    )

    /** The carrier jars, on the JVM only. The Native and JS legs link or copy the libraries instead, and a jar on their
      * classpath would put a second copy of every native into the application's own artifact.
      *
      * Each carries the coordinate it was resolved from, and the artifact within it. sbt-assembly's dedup reads
      * `moduleID`; sbt-native-packager's `lib/` naming builds a name from the two TOGETHER and falls back to the bare
      * file name when either is missing. The artifact carries the classifier, which is what keeps kyo-net's two apart.
      */
    private def jvmJars(delivered: TaskKey[Seq[(String, Delivery.Fetched)]]): Def.Initialize[Task[Seq[Attributed[File]]]] = Def.task {
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet)
        val fetched  = delivered.value
        if (platform != Platform.Jvm) Nil
        else
            fetched.map(_._2).groupBy(_.jar).toSeq.map { case (jar, group) =>
                val module     = group.head.module
                val classifier = module.explicitArtifacts.flatMap(_.classifier).headOption
                val artifact   = classifier.foldLeft(Artifact(module.name))((a, c) => a.withClassifier(Some(c)))
                Attributed.blank(jar).put(Keys.moduleID.key, module).put(Keys.artifact.key, artifact)
            }
    }

    private def requestsTask(configuration: Configuration): Def.Initialize[Task[Seq[(String, Delivery.Request)]]] = Def.task {
        val source   = kyoNativesSource.value
        val targets  = kyoNativesResolvedTargets.value
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet).delivery
        val modules  = update.value.configuration(configuration).toSeq.flatMap(_.modules).flatMap { report =>
            report.artifacts.map { case (_, file) => report.module -> file }
        }
        if (source == NativesSource.Disabled) Nil
        else {
            val unsupported = targets.filterNot(NativeTargets.supported.contains)
            if (unsupported.nonEmpty)
                sys.error(
                    s"[kyo-natives] unknown target(s): ${unsupported.mkString(", ")}. " +
                        s"Supported: ${NativeTargets.supported.mkString(", ")}."
                )
            targets.flatMap(osArch => Delivery.requests(modules, osArch, platform).map(osArch -> _))
        }
    }

    private def fetch(
        requests: Seq[(String, Delivery.Request)],
        source: NativesSource,
        depRes: sbt.librarymanagement.DependencyResolution,
        outRoot: File,
        log: Logger
    ): Seq[(String, Delivery.Fetched)] =
        requests.flatMap { case (osArch, request) =>
            val os      = NativeTargets.osOf(osArch)
            val fetched = Delivery.resolve(depRes, request.module, request.libId, log).flatMap { jar =>
                Delivery.unpack(jar, request.libId, osArch, os, outRoot / osArch)
                    .map(lib => Delivery.Fetched(request.libId, lib, jar, request.module))
                    .toRight(s"${jar.getName} carries no ${request.libId} for $osArch")
            }
            fetched match {
                case Right(f)  => Seq(osArch -> f)
                case Left(why) =>
                    if (source == NativesSource.Jar)
                        sys.error(s"[kyo-natives] $why. Set kyoNativesSource := NativesSource.Auto to build without it.")
                    // A warning, not information: the build asked for this library by enabling the plugin, and
                    // what it gets instead is the capability reporting itself unavailable at run time.
                    log.warn(s"[kyo-natives] $why; building without it")
                    Nil
            }
        }

    private def reportTask: Def.Initialize[Task[Unit]] = Def.task {
        val log      = streams.value.log
        val fetched  = kyoNativesFetched.value
        val testOnly = (Test / kyoNativesFetched).value.filterNot(fetched.contains)
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet)
        val targets  = kyoNativesResolvedTargets.value
        log.info(s"[kyo-natives] ${kyoNativesSource.value}, platform $platform, target(s) ${targets.mkString(", ")}")
        // A JVM classpath is portable and the default target is not: an image built here and run on another OS
        // carries this machine's pole and falls back to whatever floor the module has. Nothing else says so, since
        // the delivery itself succeeds. Only where something was delivered, since it is those jars that are
        // host-shaped; a module keeping its natives in the main artifact carries every pole and is not delivered here.
        if (platform == Platform.Jvm && kyoNativesTargets.value.isEmpty && fetched.nonEmpty)
            log.info(
                s"[kyo-natives] ${targets.mkString(", ")} is this build host; an image that runs on another OS or " +
                    "architecture needs that target named in kyoNativesTargets"
            )
        if (fetched.isEmpty && testOnly.isEmpty) log.info("[kyo-natives] no libraries delivered")
        else {
            fetched.foreach { case (osArch, f) =>
                log.info(s"[kyo-natives]   ${f.libId} ($osArch) from ${f.jar.getName} -> ${f.library}")
            }
            testOnly.foreach { case (osArch, f) =>
                log.info(s"[kyo-natives]   ${f.libId} ($osArch, tests only) from ${f.jar.getName} -> ${f.library}")
            }
            platform match {
                case Platform.Jvm =>
                    log.info("[kyo-natives] on the runtime and test classpaths; the POM is untouched")
                case Platform.Native =>
                    log.info("[kyo-natives] linked into the binary, which looks for them beside itself and needs them there to run")
                case Platform.Js =>
                    if (testOnly.isEmpty) log.info("[kyo-natives] under target/node_modules, where koffi resolves them")
                    else
                        log.info(
                            "[kyo-natives] under target/node_modules, where koffi resolves them; the test run's set, " +
                                "with the libraries marked tests only, under target/kyo-natives-test-node/node_modules"
                        )
            }
        }
    }
}
