package kyo.natives.sbt

import kyo.ffi.sbt.NativeTargets
import sbt.Keys._
import sbt._

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

        val kyoNativesResolvedTargets = taskKey[Seq[String]]("The targets in effect, after deriving the ones `kyoNativesTargets` left open.")

        val kyoNativesJars = taskKey[Seq[File]]("The artifacts carrying this project's libraries, resolved for every target in effect.")

        val kyoNativesLibraries = taskKey[Seq[File]]("The shared libraries, unpacked from `kyoNativesJars`.")

        val kyoNativesReport = taskKey[Unit]("Print what each library resolved to, and what it is wired into.")

        type NativesSource = kyo.natives.sbt.NativesSource
        val NativesSource = kyo.natives.sbt.NativesSource
    }

    import autoImport._

    /** The libraries, grouped by the target they were unpacked for. Read by the per-platform plugins, which need the
      * grouping that [[kyoNativesLibraries]] flattens away.
      */
    private[sbt] val kyoNativesFetched = taskKey[Seq[(String, Delivery.Fetched)]]("Every library this project delivers, with its target.")

    /** What the dependencies declare, before anything is resolved. This is what separates a build that asked for
      * libraries from one that never asked: [[kyoNativesFetched]] is equally empty when a release carries no library
      * for the target, which is the case where a misconfigured target most needs to be reported.
      */
    private[sbt] val kyoNativesRequests =
        taskKey[Seq[(String, Delivery.Request)]]("Every library this project's dependencies declare, with the target it is wanted for.")

    override def projectSettings: Seq[Setting[_]] = Seq(
        kyoNativesTargets   := Nil,
        kyoNativesSource    := NativesSource.Auto,
        kyoNativesDirectory := target.value / "kyo-natives",
        // The host, which is the only target a build knows without being told. The Native plugin replaces this with
        // the one its target triple names, since cross-compiling there is ordinary.
        kyoNativesResolvedTargets := {
            val explicit = kyoNativesTargets.value
            if (explicit.nonEmpty) explicit else Seq(NativeTargets.host)
        },
        kyoNativesRequests  := requestsTask.value,
        kyoNativesFetched   := fetchTask.value,
        kyoNativesJars      := kyoNativesFetched.value.map(_._2.jar).distinct,
        kyoNativesLibraries := kyoNativesFetched.value.map(_._2.library).distinct,
        kyoNativesReport    := reportTask.value,
        // The JVM loader extracts from the classpath, so the classifier jars go on it. Through `unmanagedJars` rather
        // than `libraryDependencies`: a dependency reaches `makePom`, which would pin this build host's architecture
        // onto everyone who then depends on this project. `unmanagedJars` reaches `Runtime` and `Test` `fullClasspath`,
        // which is what `run`, `test`, sbt-assembly and sbt-native-packager read, and reaches neither `makePom` nor
        // `packageBin`. `Compile` as well, for a task that reads the compile classpath to decide what a build holds.
        Compile / unmanagedJars ++= jvmJars.value,
        Runtime / unmanagedJars ++= jvmJars.value,
        Test / unmanagedJars ++= jvmJars.value
    )

    /** The carrier jars, on the JVM only. The Native and JS legs link or copy the libraries instead, and a jar on their
      * classpath would put a second copy of every native into the application's own artifact.
      *
      * Each carries the coordinate it was resolved from, and the artifact within it. sbt-assembly's dedup reads
      * `moduleID`; sbt-native-packager's `lib/` naming builds a name from the two TOGETHER and falls back to the bare
      * file name when either is missing. The artifact carries the classifier, which is what keeps kyo-net's two apart.
      */
    private def jvmJars: Def.Initialize[Task[Seq[Attributed[File]]]] = Def.task {
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet)
        val fetched  = kyoNativesFetched.value
        if (platform != Platform.Jvm) Nil
        else
            fetched.map(_._2).groupBy(_.jar).toSeq.map { case (jar, group) =>
                val module     = group.head.module
                val classifier = module.explicitArtifacts.flatMap(_.classifier).headOption
                val artifact   = classifier.foldLeft(Artifact(module.name))((a, c) => a.withClassifier(Some(c)))
                Attributed.blank(jar).put(Keys.moduleID.key, module).put(Keys.artifact.key, artifact)
            }
    }

    private def requestsTask: Def.Initialize[Task[Seq[(String, Delivery.Request)]]] = Def.task {
        val source   = kyoNativesSource.value
        val targets  = kyoNativesResolvedTargets.value
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet).declarationName
        val modules = update.value.configuration(Configurations.Compile).toSeq.flatMap(_.modules).flatMap { report =>
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

    private def fetchTask: Def.Initialize[Task[Seq[(String, Delivery.Fetched)]]] = Def.task {
        val log     = streams.value.log
        val source  = kyoNativesSource.value
        val depRes  = dependencyResolution.value
        val outRoot = kyoNativesDirectory.value
        kyoNativesRequests.value.flatMap { case (osArch, request) =>
            val os = NativeTargets.osOf(osArch)
            val fetched = Delivery.resolve(depRes, request.module, log).right.flatMap { jar =>
                Delivery.unpack(jar, request.libId, osArch, os, outRoot / osArch)
                    .map(lib => Delivery.Fetched(request.libId, lib, jar, request.module))
                    .toRight(s"${jar.getName} carries no ${request.libId} for $osArch")
            }
            fetched match {
                case Right(f) => Seq(osArch -> f)
                case Left(why) =>
                    if (source == NativesSource.Jar)
                        sys.error(s"[kyo-natives] $why. Set kyoNativesSource := NativesSource.Auto to build without it.")
                    // A warning, not information: the build asked for this library by enabling the plugin, and
                    // what it gets instead is the capability reporting itself unavailable at run time.
                    log.warn(s"[kyo-natives] $why; building without it")
                    Nil
            }
        }
    }

    private def reportTask: Def.Initialize[Task[Unit]] = Def.task {
        val log      = streams.value.log
        val fetched  = kyoNativesFetched.value
        val platform = Platform.of(thisProject.value.autoPlugins.map(_.label).toSet)
        val targets  = kyoNativesResolvedTargets.value
        log.info(s"[kyo-natives] ${kyoNativesSource.value}, platform $platform, target(s) ${targets.mkString(", ")}")
        // A JVM classpath is portable and the default target is not: an image built here and run on another OS
        // carries this machine's pole and falls back to whatever floor the module has. Nothing else says so, since
        // the delivery itself succeeds.
        if (platform == Platform.Jvm && kyoNativesTargets.value.isEmpty)
            log.info(
                s"[kyo-natives] ${targets.mkString(", ")} is this build host; an image that runs on another OS or " +
                    "architecture needs that target named in kyoNativesTargets"
            )
        if (fetched.isEmpty) log.info("[kyo-natives] no libraries delivered")
        else {
            fetched.foreach { case (osArch, f) =>
                log.info(s"[kyo-natives]   ${f.libId} ($osArch) from ${f.jar.getName} -> ${f.library}")
            }
            platform match {
                case Platform.Jvm =>
                    log.info("[kyo-natives] on the runtime and test classpaths; the POM is untouched")
                case Platform.Native =>
                    log.info("[kyo-natives] linked into the binary, which looks for them beside itself and needs them there to run")
                case Platform.Js =>
                    log.info("[kyo-natives] under target/node_modules, where koffi resolves them")
            }
        }
    }
}
