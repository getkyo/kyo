package kyo.natives.sbt

import kyo.ffi.sbt.FfiLibrary
import kyo.ffi.sbt.NativeSystemLibraries
import kyo.ffi.sbt.NativeTargets
import kyo.ffi.sbt.ServiceProviders
import sbt._
import sbt.Keys._
import scala.scalanative.build.Discover
import scala.scalanative.build.Mode
import scala.scalanative.build.NativeConfig
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** The Scala Native half of [[KyoNativesPlugin]]: give the binary every native thing its kyo dependencies need.
  *
  * Three separate needs, because a shim can sit over either kind of library, a module may have both, and Scala Native
  * resolves one more thing at link time that every other platform resolves at run time.
  *
  * A shim over a VENDORED library (BoringSSL, Aeron) needs the library kyo published. Scala Native has no runtime
  * loader, so it cannot be extracted and opened the way the JVM does it. Instead the artifact's C shim, which Scala
  * Native compiles into the binary from the sources the Native jar ships, is compiled to nothing, and the binary is
  * linked against the prebuilt library directly. Without that the link holds two definitions of every entry point,
  * the shim's stubs and the library's real ones.
  *
  * A shim over a SYSTEM library (OpenSSL, liburing) needs the library on the machine doing the linking, so the
  * artifact carries only what to look for and this build probes for it. That probe has to run here rather than in the
  * producing module because `nativeConfig` is per-project and does not cross a dependency edge, while the C does.
  *
  * A SERVICE PROVIDER needs naming in the link-time allowlist, because Scala Native resolves `ServiceLoader` when it
  * links and drops any class nothing references. A module whose provider is not enlisted links clean and never
  * registers, with no error and no warning, so the allowlist is filled from what the jars already declare rather than
  * left to each application to retype. See [[ServiceProviders]].
  *
  * The binary records `@rpath/lib<id>.dylib` (`\$ORIGIN` on linux) and the libraries are staged beside it, so a linked
  * binary runs from anywhere and deploys as a directory. That directory has to travel with it: a Native application
  * using kyo's natives is not a single file, in the same way a JVM application is not a single file.
  */
object KyoNativesNativePlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaNativePlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched
    import KyoNativesPlugin.kyoNativesRequests

    object autoImport {
        val kyoNativesSystemLibraries = taskKey[Seq[NativeSystemLibraries.Resolved]](
            "System libraries this project's kyo dependencies declare, resolved against this machine."
        )

        val kyoNativesServiceProviders = taskKey[Map[String, Seq[String]]](
            "Service providers the dependency jars declare, enlisted so Scala Native's link-time ServiceLoader finds them."
        )

        val kyoNativesEnlistServices = settingKey[Boolean](
            "Whether to enlist the dependencies' declared service providers. On by default: it is what makes Native " +
                "match the providers ServiceLoader would find on a JVM classpath. Turn it off to name the allowlist by hand."
        )
    }

    import autoImport._

    override def projectSettings: Seq[Setting[?]] = Seq(
        // A binary is built for one target, so more than one is a contradiction rather than a wider delivery.
        //
        // The target comes from the compiler rather than from `nativeConfig`, which would be the more direct source
        // and is not available: this plugin contributes TO `nativeConfig`, so reading it here would make the setting
        // depend on itself. A build that cross-compiles by setting `targetTriple` names `kyoNativesTargets` too, and
        // `crossTargetCheck` fails the link when the two disagree.
        kyoNativesResolvedTargets := {
            val log      = streams.value.log
            val explicit = kyoNativesTargets.value
            if (explicit.size > 1)
                sys.error(s"[kyo-natives] a Native binary has one target; kyoNativesTargets names ${explicit.mkString(", ")}.")
            val target = explicit.headOption match {
                case some @ Some(_) => some
                case None           =>
                    val triple  = Discover.targetTriple(Discover.clang())
                    val derived = NativeTargets.ofTriple(triple)
                    if (derived.isEmpty)
                        log.info(s"[kyo-natives] no target kyo publishes for matches $triple; delivering nothing")
                    derived
            }
            target.flatMap { t =>
                undeliverable(t) match {
                    case Some(why) =>
                        log.info(s"[kyo-natives] $why; delivering nothing")
                        None
                    case None => Some(t)
                }
            }.toSeq
        },
        kyoNativesEnlistServices  := true,
        kyoNativesSystemLibraries := {
            val log = streams.value.log
            val cp  = (Compile / dependencyClasspath).value.map(_.data)
            val dir = target.value / "kyo-natives" / "system-library-probes" / "compile"
            probeSystemLibraries(NativeSystemLibraries.readJars(cp), kyoNativesSource.value, kyoNativesTargets.value, dir, log)
        },
        kyoNativesServiceProviders := serviceProvidersTask(Compile).value,
        // A test binary links its own classpath, where a test-only dependency can declare a library or a provider of
        // its own. The production config stays on Compile: enlisting a class or linking a library that is not on the
        // production classpath would fail that link rather than degrade.
        //
        // Contributed to `Test / nativeLink / nativeConfig`, the key `nativeLinkCachedTask` reads by name, rather
        // than to `Test / nativeConfig`. Scala Native applies `withBuildTarget(application)` to the latter for the
        // test configuration, so a `:=` there would replace that transformation along with everything else, and a
        // test binary would link for the wrong build target. Reading it and writing the link's own key keeps it.
        // The mode variants are named for the same reason: each holds its own copy of the config.
        Test / kyoNativesSystemLibraries := {
            val log        = streams.value.log
            val production = (Compile / kyoNativesSystemLibraries).value
            val shared     = NativeSystemLibraries.readJars((Compile / dependencyClasspath).value.map(_.data)).map(_.id).toSet
            val declared   = NativeSystemLibraries.readJars((Test / dependencyClasspath).value.map(_.data)).filterNot(d => shared(d.id))
            val dir        = target.value / "kyo-natives" / "system-library-probes" / "test"
            production ++ probeSystemLibraries(declared, kyoNativesSource.value, kyoNativesTargets.value, dir, log)
        },
        Test / kyoNativesServiceProviders           := serviceProvidersTask(Test).value,
        Test / nativeLink / nativeConfig            := testConfig.value,
        Test / nativeLinkReleaseFast / nativeConfig := testConfig.value.withMode(Mode.releaseFast),
        Test / nativeLinkReleaseFull / nativeConfig := testConfig.value.withMode(Mode.releaseFull),
        nativeConfig                                := {
            val base     = nativeConfig.value
            val services = kyoNativesServiceProviders.value
            // Delivered and system libraries are independent: a module can declare both, either, or neither, and an
            // empty delivery says nothing about whether the machine has the system library this binary still needs.
            val withFlags =
                withLibraries(base, Seq(deliveredFlags(kyoNativesFetched.value), systemFlags(kyoNativesSystemLibraries.value)))
            if (services.isEmpty) withFlags
            else withFlags.withServiceProviders(ServiceProviders.merge(withFlags.serviceProviders, services))
        }
    ) ++ linkSettings

    /** Every link task in both configurations gets the same checks and the same staging, each reading the config that
      * task links with. The release links are the ones a deployment ships, so a link task left out would be the one
      * whose binary finds nothing beside itself, or links with a config that lost the fold.
      */
    private def linkSettings: Seq[Setting[?]] =
        for {
            configuration <- Seq(Compile, Test)
            link          <- Seq(nativeLink, nativeLinkReleaseFast, nativeLinkReleaseFull)
        } yield configuration / link := stageBeside(
            (configuration / link).dependsOn(
                crossTargetCheck(configuration / link / nativeConfig, configuration),
                foldCheck(configuration / link / nativeConfig, configuration)
            ),
            configuration
        ).value

    /** Fails the link when the config it reads lost the libraries this plugin folded into it.
      *
      * A project's `nativeConfig := ...` applies after this plugin's and replaces the value rather than building on it,
      * and a `Test / nativeLink / nativeConfig := ...` does the same to the test link. The binary still links, with
      * every shim compiled to its stub, and the capability reports itself unavailable at run time, which reads as a
      * machine missing a library rather than a build that discarded one.
      */
    private def foldCheck(config: Def.Initialize[Task[NativeConfig]], configuration: Configuration): Def.Initialize[Task[Unit]] =
        Def.task {
            val delivered = (configuration / kyoNativesFetched).value.map(f => FfiLibrary.externalDefineFor(f._2.libId))
            val system    = (configuration / kyoNativesSystemLibraries).value.map(r => FfiLibrary.linkedDefineFor(r.id))
            foldError(config.value.compileOptions, delivered ++ system).foreach(sys.error)
        }

    /** The error a link gets when `compileOptions` lacks a define this plugin folded in, or None when all are there. */
    private[sbt] def foldError(compileOptions: Seq[String], defines: Seq[String]): Option[String] = {
        val missing = defines.distinct.filterNot(d => compileOptions.contains("-D" + d))
        if (missing.isEmpty) None
        else
            Some(
                s"[kyo-natives] the nativeConfig this link reads lacks ${missing.mkString(", ")}, which kyo-natives-plugin " +
                    "added, so a `nativeConfig := ...` replaced its contribution and every shim would compile its stub. " +
                    "Build on the existing value (`nativeConfig ~= (_.with...)`, or `nativeConfig.value.with...`), or set " +
                    "kyoNativesSource := NativesSource.Disabled to wire the libraries by hand."
            )
    }

    /** `config` with each stage's compile and link flags folded in.
      *
      * Compile flags and `-L` directories go FIRST. Scala Native's defaults name `/usr/local`, `/opt/local` and
      * `/opt/homebrew`'s `include` and `lib`, and the probe resolved a library under the prefix it names without them, so
      * another version in one of those directories would otherwise be compiled against, or linked in place of, the one the
      * probe found. Every other link flag goes after: the linker resolves a `-l` against the objects before it, so a
      * static library named ahead of the code that calls it contributes nothing.
      */
    private[sbt] def withLibraries(config: NativeConfig, stages: Seq[(Seq[String], Seq[String])]): NativeConfig =
        stages.foldLeft(config) { case (c, (compile, link)) =>
            if (compile.isEmpty && link.isEmpty) c
            else {
                val (dirs, rest) = link.partition(_.startsWith("-L"))
                c.withCompileOptions(compile ++ c.compileOptions).withLinkingOptions(dirs ++ c.linkingOptions ++ rest)
            }
        }

    /** The compile and link flags for the libraries kyo published and this build fetched. */
    private def deliveredFlags(fetched: Seq[(String, Delivery.Fetched)]): (Seq[String], Seq[String]) =
        if (fetched.isEmpty) (Nil, Nil)
        else {
            val dirs    = fetched.map(_._2.library.getParentFile).distinct
            val defines = fetched.map { case (_, f) => "-D" + FfiLibrary.externalDefineFor(f.libId) }.distinct
            val links   = fetched.map { case (_, f) => "-l" + f.libId }.distinct
            // Both forms mean "beside the binary", so the link records no path from this machine.
            val rpaths = fetched.map(_._1).distinct.map { t =>
                if (NativeTargets.osOf(t) == "darwin") "-Wl,-rpath,@loader_path" else "-Wl,-rpath,$ORIGIN"
            }.distinct
            (defines, dirs.map("-L" + _.getAbsolutePath) ++ links ++ rpaths)
        }

    /** The compile and link flags for the system libraries this machine turned out to have.
      *
      * Link flags are NOT deduped. A static resolution renders as an ordered `-Wl,-Bstatic ... -Wl,-Bdynamic` window
      * around its libraries, and dropping a repeated marker would leave the window unbalanced and pull every later
      * library in statically. Compile flags are plain defines and includes, where a repeat is only noise.
      */
    private def systemFlags(resolved: Seq[NativeSystemLibraries.Resolved]): (Seq[String], Seq[String]) =
        (resolved.flatMap(_.compileFlags).distinct, resolved.flatMap(_.linkFlags))

    /** Probes this machine for each of `declarations`, in `workDir`.
      *
      * The library names are the target OS's, not this machine's, so a build naming another target is told which
      * libraries it would need. The link that decides whether they exist is this machine's own, with the clang on the
      * PATH and its default search paths, which cannot answer for another target; `crossTargetCheck` refuses a link
      * where the two differ. A target this build cannot name at all probes for nothing, rather than guessing.
      *
      * A declared library that does not link is not an error. The producer's shim compiles its stub branch and the
      * capability reports itself unavailable at runtime, which is the same outcome every other platform gives for a
      * library that is not present.
      */
    private def probeSystemLibraries(
        declarations: Seq[NativeSystemLibraries.Declared],
        source: NativesSource,
        named: Seq[String],
        workDir: File,
        log: Logger
    ): Seq[NativeSystemLibraries.Resolved] =
        if (source == NativesSource.Disabled || declarations.isEmpty) Nil
        else {
            // Discovered once: each call shells out to find the toolchain, and the probe below asks the same question
            // for every declared library.
            val clang = Discover.clang()
            val os    = named.headOption.orElse(NativeTargets.ofTriple(Discover.targetTriple(clang))).map(NativeTargets.osOf)
            os.toSeq.flatMap { targetOs =>
                declarations.flatMap { declared =>
                    val probe    = NativeSystemLibraries.probeWith(clang.toString, declared.system.headers, workDir / declared.id, log)
                    val resolved = NativeSystemLibraries.resolve(declared, targetOs, probe)
                    val libs     = declared.system.resolvedLinkLibs(targetOs)
                    resolved match {
                        case Some(r) =>
                            log.info(s"[kyo-natives] ${r.id}: linking the system library (${r.linkFlags.mkString(" ")})")
                        // An empty library list means the declaration names nothing for this OS, so nothing was
                        // probed. Saying it "does not link" would send someone installing a package that would not
                        // have been used on this target anyway.
                        case None if libs.isEmpty =>
                            log.info(s"[kyo-natives] ${declared.id}: not declared for $targetOs; its shim compiles stubs.")
                        case None =>
                            log.info(
                                s"[kyo-natives] ${declared.id}: ${declared.system.headers.mkString(", ")} with " +
                                    s"${libs.mkString(", ")} does not link on this machine; its shim compiles stubs."
                            )
                    }
                    resolved
                }
            }
        }

    /** The test link's config: the project's own, plus the libraries and providers only the test classpath declares.
      *
      * Reads `Test / nativeConfig`, which delegates to the project-scope value this plugin already built, so the
      * production libraries and providers are carried through rather than rebuilt here, and Scala Native's own
      * test-configuration transformation survives. Only what the Test configuration adds is folded on top.
      *
      * A build that sets `Test / nativeLink / nativeConfig` itself, which is the documented way to tune one link,
      * replaces this and takes the test-only libraries and providers with it. Such a build folds them in by hand.
      */
    private def testConfig: Def.Initialize[Task[NativeConfig]] = Def.task {
        val base      = (Test / nativeConfig).value
        val declared  = (Test / kyoNativesServiceProviders).value
        val delivered = (Test / kyoNativesFetched).value.filterNot((Compile / kyoNativesFetched).value.contains)
        val system    = (Test / kyoNativesSystemLibraries).value.filterNot((Compile / kyoNativesSystemLibraries).value.contains)
        val withFlags = withLibraries(base, Seq(deliveredFlags(delivered), systemFlags(system)))
        if (declared.isEmpty) withFlags
        else withFlags.withServiceProviders(ServiceProviders.merge(withFlags.serviceProviders, declared))
    }

    /** Every service provider the entries on `configuration`'s dependency classpath declare.
      *
      * Not filtered to kyo's own artifacts, and enlisting one that nothing loads is free rather than merely safe. The
      * linker examines a config entry only where a reachable `ServiceLoader.load` names its service, so an entry for
      * a service nothing loads is never looked at and dead-code elimination still decides what is linked. That is the
      * argument for reading the whole classpath: it cannot over-link, and under-reading drops a provider with no
      * symptom at all.
      *
      * The project's own output is included as well as its dependencies. `Test / dependencyClasspath` carries this
      * project's classes while `Compile / dependencyClasspath` does not, so reading only that would enlist an
      * application's own provider in its test binary and not in the one it ships.
      *
      * `kyoNativesEnlistServices := false` is for a build that would rather name the set itself.
      */
    private def serviceProvidersTask(configuration: Configuration): Def.Initialize[Task[Map[String, Seq[String]]]] =
        Def.task {
            val log     = streams.value.log
            val enabled = kyoNativesEnlistServices.value && kyoNativesSource.value != NativesSource.Disabled
            // Read outside the branch, because a regular task evaluates every `.value` whatever the branch decides.
            // Keeping the lookup here says so, rather than reading as a guard that does not guard.
            val classpath = (configuration / dependencyClasspath).value.map(_.data) ++
                (configuration / exportedProducts).value.map(_.data)
            if (!enabled) Map.empty[String, Seq[String]]
            else {
                val declared = ServiceProviders.read(classpath)
                declared.toSeq.sortBy(_._1).foreach { case (iface, impls) =>
                    log.info(s"[kyo-natives] service provider $iface: ${impls.mkString(", ")}")
                }
                declared
            }
        }

    /** Why `target`'s libraries cannot reach a Scala Native link, or None when they can.
      *
      * A Windows release carries `<id>.dll` and no import library, and `-l<id>` against a bare DLL resolves nothing,
      * so the delivery would end in a linker error naming a library the release does not publish in a linkable form.
      * Saying so and delivering nothing leaves the binary in the state it is in on every other platform where a
      * library is missing: it links, and the capability reports itself unavailable when it is used.
      *
      * A target kyo publishes nothing at all for passes through rather than being reported here, so the message it
      * gets is the one that names the supported set.
      */
    private[sbt] def undeliverable(target: String): Option[String] =
        if (NativeTargets.supported.contains(target) && NativeTargets.osOf(target) == "windows")
            Some(s"$target publishes a DLL and no import library, which a Native link cannot use")
        else None

    /** Fails the build before the link when the target the binary is built for and the libraries about to be linked
      * into it disagree.
      *
      * This runs at link time, not while building `nativeConfig`, because an auto-plugin's settings are applied before
      * the project's own: inside this plugin's `nativeConfig :=`, `nativeConfig.value` is sbt-scala-native's default
      * and an application's `withTargetTriple` has not been applied yet, so the triple read there is always empty. A
      * task reads the finished setting instead, and `dependsOn` puts it ahead of the link, so the error arrives before
      * the linker's own. Reading the finished config also covers a `withClang`, which the clang the target was derived
      * from would otherwise ignore. It is the config the link itself reads, so a `withClang` or `withTargetTriple` set
      * on one link task is checked for that link.
      */
    private def crossTargetCheck(
        linkConfig: Def.Initialize[Task[NativeConfig]],
        configuration: Configuration
    ): Def.Initialize[Task[Unit]] = Def.task {
        val config   = linkConfig.value
        val targets  = kyoNativesResolvedTargets.value
        val requests = (configuration / kyoNativesRequests).value
        val resolved = (configuration / kyoNativesSystemLibraries).value
        // The compiler the finished config names, which is the one that will run, rather than the one the delivery
        // derived its target from.
        val compilerTarget = NativeTargets.ofTriple(Discover.targetTriple(config.clang))
        crossTargetError(config.targetTriple, compilerTarget, targets.headOption, requests.nonEmpty, kyoNativesTargets.value.nonEmpty)
            .foreach(sys.error)
        // The probe ran with the clang on the PATH, the one `probeSystemLibraries` discovers.
        val probeClang = Discover.clang()
        val probeHost  = NativeTargets.ofTriple(Discover.targetTriple(probeClang))
        val options    = config.compileOptions ++ config.linkingOptions
        // Asked only when a sysroot flag is present: it runs the compiler, and most links name none.
        lazy val ownSysroot = compilerSysroot(probeClang.toString)
        systemProbeError(
            resolved.map(_.id),
            config.targetTriple,
            targets.headOption,
            compilerTarget,
            probeHost,
            options,
            path => sameSysroot(path, ownSysroot)
        ).foreach(sys.error)
    }

    /** Flags that point a compile or a link at another system's headers and libraries while leaving the triple alone,
      * which is the shape of a build for an older distribution of the same architecture.
      */
    private val sysrootFlags = Seq("--sysroot", "-isysroot", "-Wl,--sysroot")
    private val targetFlags  = Seq("-target", "--target")

    /** Each of `flags` in `options` with the value it names: the next option for the spaced spelling, the text after
      * `=` for the joined one.
      */
    private[sbt] def flagValues(options: Seq[String], flags: Seq[String]): Seq[(String, String)] =
        options.zipWithIndex.flatMap { case (option, i) =>
            flags.collectFirst {
                case f if option == f                => (f, options.lift(i + 1).getOrElse(""))
                case f if option.startsWith(f + "=") => (f, option.drop(f.length + 1))
            }
        }

    /** The sysroot `clang` compiles against when no flag names one, or None for the host root.
      *
      * Read from the `-cc1` line of a `-###` dry run, which carries `-isysroot` wherever the driver picked one: the SDK
      * `xcrun` selects on darwin, a sysroot configured into the toolchain elsewhere. `-print-sysroot` is GCC's and
      * Apple's clang rejects it.
      */
    private[sbt] def compilerSysroot(clang: String): Option[String] = {
        val out = new StringBuilder
        val log = scala.sys.process.ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n'))
        val in  = new java.io.ByteArrayInputStream(Array.emptyByteArray)
        scala.util.Try(scala.sys.process.Process(Seq(clang, "-###", "-x", "c", "-c", "-")).#<(in).!(log))
        sysrootOfDryRun(out.toString)
    }

    /** The `-isysroot` value on a `clang -###` dry run's `-cc1` line, or None when it names none. */
    private[sbt] def sysrootOfDryRun(output: String): Option[String] =
        """"-isysroot" "([^"]*)"""".r.findFirstMatchIn(output).map(_.group(1))

    /** Whether `path` names the system the probe compiled against: the host root, or the compiler's own sysroot.
      * Compared by real path, so the SDK symlink `xcrun` prints and the versioned directory it points at are one system.
      */
    private[sbt] def sameSysroot(path: String, own: => Option[String]): Boolean = {
        def real(p: String) = scala.util.Try(new File(p).getCanonicalPath).getOrElse(p)
        path.nonEmpty && {
            val target = real(path)
            target == real("/") || own.exists(real(_) == target)
        }
    }

    /** The error a build gets when system libraries were resolved by a probe that could not answer for the binary being
      * linked, or None when the probe and the link agree or nothing was resolved.
      *
      * The probe links against this machine with the clang on the PATH and its default search paths. It runs while
      * `nativeConfig` is being built, so it cannot read the finished config's target triple, sysroot or `withClang`
      * without the setting depending on itself. A link for another target, through a compiler for another target, or
      * against a sysroot would then carry defines and flags for libraries the probe found on the host: a file-format
      * error from the linker at best, and at worst a library no probe checked, found in a sysroot that happens to hold
      * one. Compilers are compared by the target they build for, not by path, since two paths can name one compiler.
      *
      * A sysroot or `-target` naming the probe's own system changes nothing the probe answered, so it passes:
      * `-isysroot $(xcrun --show-sdk-path)` is the usual repair for a macOS compile that lost its SDK headers, and
      * refusing it would cost every build with a resolved system library its probe. `ownSysroot` says whether a path
      * is that system.
      */
    private[sbt] def systemProbeError(
        resolved: Seq[String],
        triple: Option[String],
        wanted: Option[String],
        compilerTarget: Option[String],
        probeHost: Option[String],
        options: Seq[String],
        ownSysroot: String => Boolean
    ): Option[String] =
        if (resolved.isEmpty) None
        else {
            val host       = probeHost.getOrElse("an unknown target")
            val retargeted =
                (flagValues(options, sysrootFlags).filterNot(f => ownSysroot(f._2)) ++
                    flagValues(options, targetFlags).filter(f => probeHost.isEmpty || NativeTargets.ofTriple(f._2) != probeHost))
                    .headOption.map { case (f, v) => s"$f $v" }
            val why =
                compilerTarget.filter(t => !probeHost.contains(t)).map(t => s"this build links with a compiler for $t")
                    .orElse(
                        triple.filter(t => NativeTargets.ofTriple(t).isEmpty || NativeTargets.ofTriple(t) != probeHost)
                            .map(t => s"the binary is built for the target triple $t")
                    )
                    .orElse(wanted.filter(w => !probeHost.contains(w)).map(w => s"the libraries are wanted for $w"))
                    .orElse(retargeted.map(f => s"the link names $f, so it reads another system's headers and libraries"))
            why.map { reason =>
                s"[kyo-natives] the system libraries ${resolved.mkString(", ")} were resolved by linking a probe for $host " +
                    s"with the clang on the PATH, but $reason, which that probe cannot answer for. To cross-compile with " +
                    "system libraries, set kyoNativesSource := NativesSource.Disabled and pass their flags in nativeConfig."
            }
        }

    /** The error a build gets when the target its binary is built for and the libraries it asks to have linked into it
      * disagree, or None when they agree or the build asks for no libraries at all.
      *
      * A build states its target in two places, `targetTriple` and `kyoNativesTargets`, and the delivery reads only the
      * second. Either one naming a pole the other does not is a binary linked against another pole's libraries, which
      * fails in the linker with a file-format error at best and loads and crashes at worst when the poles share an
      * architecture, as glibc and musl do.
      *
      * `requested` is whether the dependencies declare any library, not whether one was found. A release that carries
      * nothing for the named pole produces the same empty delivery as a correct build with nothing to deliver, and it
      * is precisely the build that named an impossible pole which needs to be told so rather than quietly handed a
      * binary missing the capability.
      *
      * `named` distinguishes a target the build wrote from one derived from the clang on the PATH, which is what a
      * `withClang` pointing at another toolchain produces: telling that build to change a setting it never wrote sends
      * it looking in the wrong place.
      */
    private[sbt] def crossTargetError(
        triple: Option[String],
        compilerTarget: Option[String],
        wanted: Option[String],
        requested: Boolean,
        named: Boolean
    ): Option[String] =
        if (!requested) None
        else
            wanted.flatMap { target =>
                triple match {
                    case Some(t) =>
                        NativeTargets.ofTriple(t) match {
                            case Some(fromTriple) if fromTriple != target =>
                                Some(
                                    s"[kyo-natives] the target triple $t is $fromTriple, but the libraries are " +
                                        s"wanted for $target. Set kyoNativesTargets to $fromTriple."
                                )
                            case None =>
                                Some(
                                    s"[kyo-natives] the target triple $t is not a target kyo publishes natives for, " +
                                        s"so the libraries wanted are $target's and would be linked into a binary " +
                                        "for another. Set kyoNativesSource to NativesSource.Disabled to build without them."
                                )
                            case _ => None
                        }
                    case None =>
                        compilerTarget.filter(_ != target).map { host =>
                            val source =
                                if (named) s"kyoNativesTargets names $target"
                                else s"the libraries were resolved for $target, derived from the clang on the PATH"
                            s"[kyo-natives] $source, but the compiler this build uses targets $host and no " +
                                s"targetTriple says otherwise, so $target's libraries would be linked into a $host binary."
                        }
                }
            }

    /** Copies the libraries next to the binary `link` produced, so the directory it sits in is runnable as it stands.
      *
      * Without this the rpath resolves nothing and the first run fails in `dyld`, which is a poor way to learn that a
      * Native binary using kyo's natives travels with them.
      */
    private def stageBeside(link: Def.Initialize[Task[File]], configuration: Configuration): Def.Initialize[Task[File]] = Def.task {
        val binary  = link.value
        val fetched = (configuration / kyoNativesFetched).value
        fetched.foreach { case (_, f) =>
            IO.copyFile(f.library, binary.getParentFile / f.library.getName, preserveLastModified = true)
        }
        binary
    }
}
