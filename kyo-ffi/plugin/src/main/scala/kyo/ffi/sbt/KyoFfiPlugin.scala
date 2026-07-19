package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import sbt._
import sbt.Keys._

/** Entry-point AutoPlugin for kyo-ffi.
  *
  * Opt in with `enablePlugins(KyoFfiPlugin)`. Wires build-time source generation
  * (via `kyo-ffi-codegen`) plus optional C compilation + packaging into
  * `META-INF/native/{os}-{arch}/`.
  *
  * Platform detection: `ffiTargetPlatform` defaults to the output of
  * [[PlatformDetect.detectFromAutoPlugins]], which inspects the enabled
  * auto-plugin list. Scala Native projects (via `ScalaNativePlugin`) yield
  * `Native`; Scala.js projects (via `ScalaJSPlugin`) yield `JS`; any other
  * shape falls back to `JVM`. Users inside a `crossProject` get the right
  * default without any extra wiring; they can still override explicitly.
  *
  * Modes:
  *   - Single-library (default): populate `ffiLibraryId`, `ffiCSources`, `ffiLinkLibs`
  *     and friends.
  *   - Multi-library: populate `ffiLibraries := Seq(FfiLibrary("a", ...), FfiLibrary("b", ...))`.
  *     When `ffiLibraries` is non-empty it takes precedence; otherwise the plugin
  *     synthesizes a single `FfiLibrary` from the top-level settings.
  */
object KyoFfiPlugin extends AutoPlugin {

    override def trigger = noTrigger // explicit opt-in via enablePlugins(KyoFfiPlugin)

    object autoImport {
        // Single-library settings
        val ffiLibraryId       = settingKey[String]("Identifier for the single library, e.g. 'kyo_tcp'.")
        val ffiCSources        = settingKey[Seq[File]]("C source files to compile. Defaults to src/main/c/**/*.c when present.")
        val ffiCHeaders        = settingKey[Seq[File]]("C header files for rebuild triggers. Defaults to src/main/c/**/*.h when present.")
        val ffiIncludes        = settingKey[Seq[File]]("-I include directories for C compilation. Defaults to src/main/c/ when present.")
        val ffiLinkLibs        = settingKey[Seq[String]]("Link libraries (-l flags).")
        val ffiCCompiler       = settingKey[String]("C compiler command (default 'cc').")
        val ffiCFlags          = settingKey[Seq[String]]("Additional C flags.")
        val ffiLinkFlags       = settingKey[Seq[String]]("Additional linker flags.")
        val ffiStaticLink      = settingKey[Boolean]("Statically link third-party libs (default false).")
        val ffiScratchSize     = settingKey[Int]("Scratch allocator size per thread (bytes).")
        val ffiExtractDir      = settingKey[Option[File]]("Override temp extraction dir (for native library load at runtime).")
        val ffiStrictBlocking  = settingKey[Boolean]("Promote blocking allowlist warnings to errors.")
        val ffiStrictCallbacks = settingKey[Boolean]("Promote callback-retention allowlist warnings to errors.")
        val ffiStrictDiscovery =
            settingKey[Boolean]("Fail the build when ffiGenerate's first-compile TASTy bootstrap discovers zero Ffi traits.")
        val ffiSystemLibraries = settingKey[Seq[String]](
            "Library ids that are valid even when not declared in ffiLibraries / ffiLibraryId. " +
                "Used for system-provided libraries (libc, libm, pthread, dl, ...) that the plugin " +
                "does not compile but that bindings can still reference. Defaults to common POSIX/Windows libs."
        )
        val ffiTargetPlatform = settingKey[String]("Target platform: 'JVM', 'Native', or 'JS'. Auto-detected.")
        val ffiCodegenClasspath = taskKey[Seq[File]](
            "Codegen classpath: kyo-ffi-codegen plus its Scala 3 toolchain. Defaults to resolving kyo-ffi-codegen from the project's resolvers; the in-repo integration test overrides it with the codegen project's classpath."
        )

        // Multi-library setting (DESIGN §3.4)
        val ffiLibraries = settingKey[Seq[FfiLibrary]]("Multi-library configuration. When non-empty, overrides single-lib settings.")

        // Expose the FfiLibrary case class to build.sbt consumers.
        type FfiLibrary = kyo.ffi.sbt.FfiLibrary
        val FfiLibrary = kyo.ffi.sbt.FfiLibrary

        // Tasks
        val ffiGenerate          = taskKey[Seq[File]]("Generate platform-specific impl sources from bindings.")
        val ffiCompile           = taskKey[Seq[File]]("Compile C sources into a platform-native shared library.")
        val ffiPackage           = taskKey[Seq[File]]("Copy the compiled library into META-INF/native/ in resources.")
        val ffiClean             = taskKey[Unit]("Clean generated sources + compiled libs.")
        val ffiCiWorkflow        = taskKey[File]("Emit a starter .github/workflows/ffi-native.yml template.")
        val ffiNpmBundleTemplate = taskKey[File]("Emit package.json pinning koffi to the supported range (Scala.js consumers).")
        val ffiNativeLinkingOptions = taskKey[Seq[String]](
            "Scala Native linkingOptions for ffiLibraries: the static-folded SYSTEM link libs " +
                "(e.g. -Wl,-Bstatic -luring -Wl,-Bdynamic on Linux). The bindings' own C is compiled " +
                "into the binary by Scala Native, not linked as an archive. Wire into " +
                "nativeConfig.linkingOptions in a Native project."
        )

        /** Diagnostic: return the resolved `cc` command line(s) the plugin would
          * invoke for the current library configuration, without executing it. One
          * `Seq[String]` per library (in multi-lib mode) with argv elements ready
          * for `ProcessBuilder`. Useful in tests and for debugging build issues.
          */
        val ffiDumpCcCommand = taskKey[Seq[Seq[String]]]("Return the cc command-line that ffiCompile would invoke.")

        /** koffi bootstrap for a Scala.js FFI consumer's TEST classpath: an idempotent `npm install` of
          * koffi, pinned to the range the runtime probe expects, hooked on `Test / compile` so `test`,
          * `testOnly` and `testQuick` all trigger it, and so it re-runs after a clean wipes node_modules.
          * `packageName` names the emitted package.json. Apply inside `.jsSettings`.
          *
          * The CommonJS linker setting stays with the consumer: this is a Scala 2.12 sbt plugin with no
          * sbt-scalajs dependency (it detects Scala.js by auto-plugin label), so `scalaJSLinkerConfig` and
          * `ModuleKind` do not resolve here. Each consumer keeps that one line in its own `.jsSettings`,
          * where `ScalaJSPlugin` is enabled and those types are in scope.
          */
        def ffiKoffiJsBootstrap(packageName: String): Seq[sbt.Def.Setting[?]] =
            Seq(
                Test / compile := (Test / compile).dependsOn(Def.task {
                    val log        = streams.value.log
                    val targetBase = target.value
                    val marker     = targetBase / "node_modules" / "koffi" / "package.json"
                    val koffiRange = NpmBundleTemplate.KoffiSupportedRange
                    val pjContent  = s"""{"name":"$packageName","private":true,"dependencies":{"koffi":"$koffiRange"}}"""
                    val pj         = targetBase / "package.json"
                    if (!pj.exists() || IO.read(pj) != pjContent) {
                        IO.createDirectory(targetBase)
                        IO.write(pj, pjContent)
                    }
                    if (!marker.exists()) {
                        log.info(s"[$packageName] installing koffi@$koffiRange into $targetBase ...")
                        // npm is npm.cmd on Windows, and CreateProcess resolves only .exe from a bare name.
                        val npm = if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win")) "npm.cmd" else "npm"
                        val rc = scala.sys.process.Process(
                            Seq(npm, "install", "--no-audit", "--no-fund", "--silent"),
                            targetBase
                        ).!
                        if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
                    }
                }).value
            )
    }

    import autoImport._

    override lazy val projectSettings: Seq[Setting[?]] = Seq(
        ffiLibraryId := "kyo_ffi",
        ffiCSources := {
            val cDir = (Compile / sourceDirectory).value / "c"
            if (cDir.exists()) (cDir ** "*.c").get else Nil
        },
        ffiCHeaders := {
            val cDir = (Compile / sourceDirectory).value / "c"
            if (cDir.exists()) (cDir ** "*.h").get else Nil
        },
        ffiIncludes := {
            val cDir = (Compile / sourceDirectory).value / "c"
            if (cDir.exists()) Seq(cDir) else Nil
        },
        ffiLinkLibs        := Nil,
        ffiCCompiler       := sys.env.getOrElse("CC", "cc"),
        ffiCFlags          := Seq("-O2", "-fPIC", "-Wall"),
        ffiLinkFlags       := Nil,
        ffiStaticLink      := false,
        ffiScratchSize     := 64 * 1024,
        ffiExtractDir      := None,
        ffiStrictBlocking  := false,
        ffiStrictCallbacks := false,
        ffiStrictDiscovery := sys.props.get("kyo.ffi.strictDiscovery").exists(_ == "true"),
        // Common system libraries that bindings may reference without the plugin
        // producing or packaging an artifact for them. Users can extend this list
        // to whitelist additional system-provided libraries.
        ffiSystemLibraries := Seq(
            "c",        // libc
            "m",        // libm (math)
            "pthread",  // POSIX threads
            "dl",       // dynamic loader
            "rt",       // POSIX realtime
            "util",     // POSIX util
            "crypt",    // POSIX crypt
            "resolv",   // POSIX resolver
            "nsl",      // POSIX naming service
            "kernel32", // Windows
            "user32",
            "ws2_32",
            "advapi32"
        ),
        ffiLibraries := Nil,
        // Resolve kyo-ffi-codegen (and its transitive Scala 3 toolchain) from the project's
        // resolvers, matched to this plugin's version. The in-repo integration test overrides this
        // with the codegen project's own classpath (no resolution, no publishLocal round-trip).
        ffiCodegenClasspath := {
            val log     = streams.value.log
            val depRes  = dependencyResolution.value
            val version = CodegenBridge.pluginVersion
            val codegen = "io.getkyo" % "kyo-ffi-codegen_3" % version
            val descriptor = depRes.moduleDescriptor(
                sbt.librarymanagement.ModuleDescriptorConfiguration(
                    "io.getkyo" % "kyo-ffi-codegen-resolver" % version,
                    sbt.librarymanagement.ModuleInfo("kyo-ffi-codegen-resolver")
                ).withDependencies(Vector(codegen))
                    .withConfigurations(Vector(sbt.librarymanagement.Configurations.Compile))
                    .withScalaModuleInfo(None)
            )
            val files = depRes.update(
                descriptor,
                sbt.librarymanagement.UpdateConfiguration()
                    .withLogging(sbt.librarymanagement.UpdateLogging.Quiet),
                sbt.librarymanagement.UnresolvedWarningConfiguration(),
                log
            ) match {
                case Right(report) => report.allFiles.distinct
                case Left(warn)    => throw warn.resolveException
            }
            if (files.isEmpty)
                sys.error(s"[kyo-ffi-plugin] resolved no artifacts for io.getkyo:kyo-ffi-codegen_3:$version")
            files
        },
        // Auto-detect: inspect the enabled auto-plugins. Scala Native (ScalaNativePlugin)
        // yields `Native`; Scala.js (ScalaJSPlugin) yields `JS`; otherwise JVM. The user
        // can still override explicitly.
        ffiTargetPlatform := {
            val plugins = Keys.thisProject.value.autoPlugins.map(_.label).toSet
            PlatformDetect.detectFromAutoPlugins(plugins).name
        },

        // ffiGenerate: invoke the codegen (Scala 3) via reflection to avoid
        // cross-version binary coupling between the 2.12 plugin and the 3.x codegen.
        //
        // Input tracking: we declare the user's trait source files AND the TASTy
        // already on the class directory. If Zinc has produced TASTy (second+ compile),
        // we use it directly; otherwise we eagerly compile the user's Scala sources to
        // scratch TASTy via the bundled Scala 3 compiler, this fixes the two-pass
        // problem where the first `compile` would yield no TASTy.
        //
        // Incremental caching: we hash the SHA-256 of every input source file plus the
        // platform/library-id config. Re-invocations with an unchanged hash short-circuit
        // and return the previously-generated files without re-running the codegen.
        ffiGenerate := {
            val log       = streams.value.log
            val out       = (Compile / sourceManaged).value / "kyo-ffi"
            val classesIn = (Compile / classDirectory).value
            val cp        = (Compile / dependencyClasspath).value.map(_.data.getAbsolutePath).toList
            val codegenCp = ffiCodegenClasspath.value.map(_.getAbsolutePath)
            val platform  = ffiTargetPlatform.value
            // Use unmanagedSources to avoid cyclic dependency with sourceGenerators.
            val userSrcs   = (Compile / unmanagedSources).value.filter(f => f.getName.endsWith(".scala"))
            val cacheDir   = streams.value.cacheDirectory / "kyo-ffi-generate"
            val libraryId  = ffiLibraryId.value
            val strictB    = ffiStrictBlocking.value
            val strictC    = ffiStrictCallbacks.value
            val strictDisc = ffiStrictDiscovery.value
            val systemLibs = ffiSystemLibraries.value.toSet
            val declaredLibIds: Set[String] = {
                val multi = ffiLibraries.value
                if (multi.nonEmpty) multi.map(_.id).toSet
                else Set(libraryId)
            }
            // -I dirs for the Native header-availability probe: a vendored header (e.g. a staged
            // BoringSSL tree) is off the system include path, so without these the probe would not
            // find it and the binding would be emitted as a throwing stub instead of @extern (RI-006).
            val probeIncludeDirs: Seq[String] = {
                val multi      = ffiLibraries.value
                val headerDirs = multi.flatMap(_.cHeaders).map(_.getParentFile)
                val libIncs    = multi.flatMap(_.includeDirs)
                (ffiIncludes.value ++ headerDirs ++ libIncs).distinct.map(_.getAbsolutePath)
            }

            // ffiGenerate is a sourceGenerator, so it runs BEFORE zinc's compile within the same
            // `compile` invocation. Any TASTy already on the class directory is therefore the
            // PREVIOUS compile's output, stale with respect to the binding-trait edit that triggered
            // this (cache-miss) run: reading it regenerates the impl for the OLD trait shape (#247).
            // On an added method that surfaces at the user's next compile as "class XImpl needs to be
            // abstract"; on a renamed trait it leaves an impl for a trait that no longer exists. So we
            // always compile the current sources to a FRESH scratch TASTy dir here, deleting any prior
            // scratch first so a removed or renamed trait leaves no orphan TASTy behind (which would
            // otherwise keep the deleted trait "discovered" and its stale impl alive). The enclosing
            // FileFunction.cached body only runs on a cache miss (a tracked source or the config
            // changed), so this does not recompile on a no-op build; the cost is a module scratch
            // compile only when a source actually changed.
            //
            // `isBootstrap` = true only on the genuine first compile (no class-dir TASTy exists yet),
            // independent of the scratch compile above, so the #35 strict-discovery diagnostic keeps
            // firing only there (zero traits on a later compile just means the project removed them).
            def resolveTasty(): (Seq[String], Boolean) = {
                val isBootstrap = collectTastyFiles(classesIn).isEmpty
                val scratch     = (target.value / "ffi-tasty").toPath
                IO.delete(scratch.toFile)
                val produced = CodegenBridge.compileSourcesToTasty(userSrcs, cp, scratch, log, codegenCp)
                if (produced.isEmpty)
                    log.info("[kyo-ffi-plugin] ffiGenerate: no TASTy produced from sources; generator skipped.")
                (produced, isBootstrap)
            }

            val trackInputs: Set[File] = userSrcs.toSet
            // `codegen=<fingerprint>` keys the cache on the bundled codegen version so a plugin or
            // codegen upgrade with unchanged binding sources still regenerates the impls instead of a
            // stale cache hit that survives until a manual `clean` (#255).
            val codegenFp = CodegenBridge.codegenFingerprint(codegenCp)
            val configHash: String =
                s"$platform|$libraryId|$strictB|$strictC|$strictDisc|${declaredLibIds.toSeq.sorted.mkString(",")}|${systemLibs.toSeq.sorted.mkString(",")}|${probeIncludeDirs.sorted.mkString(",")}|codegen=$codegenFp"

            val cached = FileFunction.cached(cacheDir, FilesInfo.hash, FilesInfo.exists) { _ =>
                val (tastyIn, isBootstrap) = resolveTasty()
                if (tastyIn.isEmpty) {
                    // No TASTy to run codegen over. If the user removed the last binding source
                    // (userSrcs empty), any previously-generated *Impl.scala is orphaned: its trait no
                    // longer exists, so remove it (#34) instead of leaving zinc to compile an impl for a
                    // trait that is gone. The normal #34 path below only runs when codegen produced a
                    // trait list; with zero sources there is nothing to compile, so handle it here.
                    // If userSrcs is non-empty but produced no TASTy, that is a transient scratch-compile
                    // failure (or a first compile that produced nothing yet): leave existing impls in
                    // place and let the real compile surface the error.
                    if (userSrcs.isEmpty && out.exists()) {
                        import scala.collection.JavaConverters._
                        val orphans = {
                            val s = Files.walk(out.toPath)
                            try s.iterator().asScala.filter(_.toString.endsWith("Impl.scala")).map(_.toFile).toList
                            finally s.close()
                        }
                        orphans.foreach { f =>
                            log.info(
                                s"[kyo-ffi-plugin] ffiGenerate: removing orphaned generated impl (no binding sources remain): ${f.getAbsolutePath}"
                            )
                            IO.delete(f)
                        }
                    }
                    Set.empty[File]
                } else {
                    try {
                        log.info(s"[kyo-ffi-plugin] ffiGenerate: codegen (platform=$platform, library=$libraryId).")
                        val generated = CodegenBridge.generate(
                            tastyFiles = tastyIn,
                            classpath = cp,
                            outputDir = out.toPath,
                            platform = platform,
                            libraryId = Some(libraryId),
                            strictBlocking = strictB,
                            strictCallbacks = strictC,
                            log = log,
                            includeDirs = probeIncludeDirs,
                            codegenClasspathOverride = codegenCp
                        )
                        // #35: loud warn (and optional fail) when first-compile TASTy bootstrap
                        // yielded zero Ffi-extending traits. On the normal (class-dir TASTy) path we
                        // stay silent, zero traits there just means the project has none.
                        if (isBootstrap && generated.traits.isEmpty) {
                            val msg =
                                "[kyo-ffi-plugin] No Ffi traits discovered on first-compile TASTy bootstrap. " +
                                    "If you expected traits to be generated, run 'sbt clean compile' or " +
                                    "enable -Dkyo.ffi.strictDiscovery=true (or `ffiStrictDiscovery := true`) to fail the build."
                            if (strictDisc)
                                sys.error(msg + " (ffiStrictDiscovery := true)")
                            else
                                log.warn(msg)
                        }
                        // #10: validate every trait's `Ffi.Config.library` literal against the declared
                        // set of library ids. A typo between binding (`library = "sqlite"`) and plugin
                        // config (`ffiLibraryId := "sqlite3"`) is a hard build error, the binding
                        // would otherwise attempt to load a nonexistent artifact at runtime. System
                        // libraries declared via `ffiSystemLibraries` bypass the check (libc, libm, ...).
                        val declared  = declaredLibIds
                        val offenders = generated.traits.filter(t => !declared.contains(t.library) && !systemLibs.contains(t.library))
                        if (offenders.nonEmpty) {
                            val declaredStr = declared.toSeq.sorted.mkString("[", ", ", "]")
                            val systemStr   = systemLibs.toSeq.sorted.mkString("[", ", ", "]")
                            val q           = "\""
                            val lines = offenders.map { t =>
                                val id = t.library
                                s"  - ${t.fqcn} declares library = $q$id$q " +
                                    s"which is not present in ffiLibraries / ffiLibraryId (declared: $declaredStr) " +
                                    s"and is not in ffiSystemLibraries (system: $systemStr). " +
                                    s"Fix the typo to match a declared id, or declare the library by one of: " +
                                    s"ffiLibraries += FfiLibrary($q$id$q, Seq(/* C sources */), linkLibs = Seq(/* vendored archives */)); " +
                                    s"or ffiLibraryId := $q$id$q (single self-compiled library); " +
                                    s"or ffiSystemLibraries += $q$id$q (an OS library such as libc/libm resolved by name)."
                            }
                            sys.error(
                                "[kyo-ffi-plugin] ffiGenerate: library-id validation failed:\n" + lines.mkString("\n")
                            )
                        }
                        // #34: detect stale generated impls. If a `*Impl.scala` file exists under
                        // `out` but the current TraitSpec list has no corresponding trait, the source
                        // trait was deleted and the generated file is stale, remove it. sbt will
                        // otherwise keep compiling (and running) an impl for a trait that no longer
                        // exists.
                        val expectedFiles: Set[File] = generated.traits.map { t =>
                            val pkgDir =
                                if (t.packageName.isEmpty) out
                                else t.packageName.split('.').foldLeft(out)((d, seg) => d / seg)
                            pkgDir / s"${t.simpleName}Impl.scala"
                        }.toSet
                        val present: Seq[File] =
                            if (!out.exists()) Nil
                            else {
                                import scala.collection.JavaConverters._
                                val s = Files.walk(out.toPath)
                                try {
                                    s.iterator().asScala
                                        .filter(p => p.toString.endsWith("Impl.scala"))
                                        .map(_.toFile)
                                        .toList
                                } finally s.close()
                            }
                        val stale = present.filterNot(expectedFiles.contains)
                        if (stale.nonEmpty) {
                            stale.foreach { f =>
                                log.info(s"[kyo-ffi-plugin] ffiGenerate: removing stale generated impl: ${f.getAbsolutePath}")
                                IO.delete(f)
                            }
                        }
                        generated.files.map(_.toFile).toSet
                    } catch {
                        case t: Throwable =>
                            log.error(s"[kyo-ffi-plugin] ffiGenerate failed: ${t.getMessage}")
                            throw t
                    }
                }
            }
            // Bake the config hash into the cached inputs by writing a sentinel file.
            IO.createDirectory(cacheDir)
            val configSentinel = cacheDir / "config.hash"
            IO.write(configSentinel, configHash)
            cached(trackInputs + configSentinel).toSeq
        },

        // Hook into sourceGenerators so sbt reruns the codegen when sources change.
        Compile / sourceGenerators += Def.task {
            ffiGenerate.value
        }.taskValue,

        // ffiCompile branches by ffiTargetPlatform:
        //   JVM: produce shared library via `cc` (Panama dlopen's it at runtime).
        //   Native: produce NOTHING here. Scala Native uses compile-time `@extern` linking
        //           (no runtime dlopen) and compiles the C into the binary itself: the C is
        //           copied under `resourceManaged/scala-native/` by `ffiNativeResourceGenerator`
        //           (Scala Native scans every `scala-native` dir on the classpath). The binding
        //           is generated WITHOUT `@link` (Ffi.Config.nativeBundled), so `ffiCompile`
        //           returns Nil on Native; only the static-folded SYSTEM link libs are surfaced
        //           via `ffiNativeLinkingOptions`.
        //   JS: produce shared library via `cc` (koffi loads it at runtime).
        //
        // In multi-library mode (`ffiLibraries` non-empty) we iterate each library,
        // producing N artifacts; otherwise we synthesize a single `FfiLibrary` from
        // the top-level settings.
        //
        // Incremental tracking: inputs are C sources + C headers + a hash of
        // (cc, flags, linkFlags, linkLibs, libraryId, staticLink, includes). When none
        // change the compiler is not re-invoked.
        ffiCompile := {
            val log       = streams.value.log
            val platform  = ffiTargetPlatform.value
            val targetDir = target.value / "ffi"
            IO.createDirectory(targetDir)
            val cc              = ffiCCompiler.value
            val globalFlags     = ffiCFlags.value
            val globalLinkFlags = ffiLinkFlags.value
            val globalStatic    = ffiStaticLink.value
            val globalIncludes  = ffiIncludes.value
            val cacheDir        = streams.value.cacheDirectory / "kyo-ffi-compile"

            val libsRaw: Seq[FfiLibrary] = {
                val multi = ffiLibraries.value
                if (multi.nonEmpty) multi
                else Seq(
                    FfiLibrary(
                        id = ffiLibraryId.value,
                        cSources = ffiCSources.value,
                        cHeaders = ffiCHeaders.value,
                        linkLibs = ffiLinkLibs.value,
                        cFlags = Nil,
                        linkFlags = Nil,
                        staticLink = globalStatic
                    )
                )
            }
            // #37: topologically order libraries by `dependsOn` so a library whose C
            // source #includes another's header is compiled after its dependency.
            val libs: Seq[FfiLibrary] = topoSortLibraries(libsRaw)

            platform match {
                case "Native" =>
                    // Scala Native compiles the C into the binary itself: there is no `.so`/`.a`
                    // to produce here. The C sources are copied under `resourceManaged/scala-native/`
                    // by `ffiNativeResourceGenerator` (Scala Native scans every `scala-native` dir on
                    // the classpath at `nativeLink`). System link libs (e.g. `-luring`) are surfaced
                    // separately via `ffiNativeLinkingOptions`. So `ffiCompile` produces no artifacts.
                    Nil
                case _ =>
                    // Resolve OS-specific link libs once for the building OS so a Linux-only
                    // system lib (e.g. uring) is omitted from the macOS / Windows command.
                    val buildOs = CCompiler.detectOs()
                    libs.zipWithIndex.flatMap { case (lib, idx) =>
                        if (lib.cSources.isEmpty) {
                            log.info(s"[kyo-ffi-plugin] ffiCompile: no C sources declared for ${lib.id}; skipping.")
                            Nil
                        } else {
                            val perLibCacheDir = cacheDir / s"lib-${idx}-${lib.id}"
                            IO.createDirectory(perLibCacheDir)
                            val flags      = globalFlags ++ lib.cFlags
                            val linkFlags  = globalLinkFlags ++ lib.linkFlags
                            val linkLibs   = lib.resolvedLinkLibs(buildOs)
                            val staticLink = lib.staticLink
                            // Derive -I dirs from header file parent directories + explicit
                            // ffiIncludes + the library's vendored includeDirs (e.g. the staged
                            // BoringSSL include/ tree). -L dirs come from the library's libDirs.
                            val headerDirs = lib.cHeaders.map(_.getParentFile).distinct
                            val includes   = (globalIncludes ++ headerDirs ++ lib.includeDirs).distinct
                            val libDirs    = lib.libDirs.distinct

                            val configHash =
                                s"$cc|${flags.mkString(",")}|${linkFlags.mkString(",")}|${linkLibs.mkString(",")}|${lib.id}|${includes.map(_.getAbsolutePath).mkString(",")}|libdirs=${libDirs.map(_.getAbsolutePath).mkString(",")}|static=$staticLink"
                            val configSentinel = perLibCacheDir / "config.hash"
                            IO.write(configSentinel, configHash)

                            val cached = FileFunction.cached(perLibCacheDir, FilesInfo.hash, FilesInfo.exists) { _ =>
                                log.info(s"[kyo-ffi-plugin] ffiCompile: cc invocation for ${lib.id}.")
                                CCompiler.compile(
                                    cc = cc,
                                    cFlags = flags,
                                    linkFlags = linkFlags,
                                    linkLibs = linkLibs,
                                    sources = lib.cSources,
                                    libraryId = lib.id,
                                    outputDir = targetDir,
                                    log = log,
                                    includes = includes,
                                    staticLink = staticLink,
                                    libDirs = libDirs
                                ).toSet
                            }
                            val trackInputs: Set[File] = lib.cSources.toSet ++ lib.cHeaders.toSet + configSentinel
                            cached(trackInputs).toSeq
                        }
                    }
            }
        },

        // ffiPackage: explicit task that copies artifacts; delegates to the resource generator path.
        //
        // In multi-library mode we need to know which artifact belongs to which library id
        // (to preserve canonical names like `libalpha.so` and `libbeta.so`). We reconstruct
        // that mapping from the artifact filename, which embeds `-<libraryId>-<os>-<arch>`.
        ffiPackage := {
            val artifacts = ffiCompile.value
            val resDir    = (Compile / resourceManaged).value / "META-INF" / "native"
            val platform  = ffiTargetPlatform.value
            val libs      = ffiLibrariesResolved.value
            if (artifacts.isEmpty) Nil
            else Packager.copyForPlatformMulti(platform, groupArtifactsByLibrary(artifacts, libs), resDir)
        },

        // Copy compiled artifacts into the resource tree automatically.
        // On Native this is a no-op (ffiCompile returns Nil). On JVM and JS the
        // artifacts land under META-INF/native/{os}-{arch}/ for NativeLoader/koffi.
        Compile / resourceGenerators += Def.task {
            val artifacts = ffiCompile.value
            val resDir    = (Compile / resourceManaged).value / "META-INF" / "native"
            val platform  = ffiTargetPlatform.value
            val libs      = ffiLibrariesResolved.value
            if (artifacts.isEmpty) Seq.empty[File]
            else Packager.copyForPlatformMulti(platform, groupArtifactsByLibrary(artifacts, libs), resDir)
        }.taskValue,

        // Native only: copy each library's C sources into `resourceManaged/scala-native/`.
        // sbt's `copyResources` then folds managed resources into the compile `classDirectory`,
        // which is on `fullClasspath`; Scala Native scans every `scala-native` directory on the
        // classpath at `nativeLink` and compiles the C into the binary itself. This is the
        // mechanism that lets a `nativeBundled` binding (no `@link`) resolve its C symbols
        // without a `-l<library>` the linker can't find. No-op on JVM / JS.
        Compile / resourceGenerators += ffiNativeResourceGenerator.taskValue,

        // Surface extract-dir + scratch-size as JVM system properties so consumer forks
        // pick them up at runtime. Covers Compile/run/Test javaOptions; consumers still
        // need to set `fork := true` to actually get a child JVM.
        Compile / javaOptions ++= ffiRuntimeJavaOptions.value,
        Test / javaOptions ++= ffiRuntimeJavaOptions.value,
        run / javaOptions ++= ffiRuntimeJavaOptions.value,
        ffiClean := {
            val log = streams.value.log
            val out = (Compile / sourceManaged).value / "kyo-ffi"
            val ff  = target.value / "ffi"
            IO.delete(out)
            IO.delete(ff)
            log.info("[kyo-ffi-plugin] ffiClean: removed generated sources and compiled libs.")
        },
        ffiCiWorkflow := {
            val out = baseDirectory.value / ".github" / "workflows" / "ffi-native.yml"
            CiWorkflow.writeTemplate(out, ffiLibraryId.value)
            streams.value.log.info(s"[kyo-ffi-plugin] Wrote CI workflow template to $out")
            out
        },

        // Emit a `package.json` at the project root pinning koffi to the supported
        // `^2.7` range. The runtime ABI probe in `kyo.ffi.internal.KoffiAbiProbe`
        // rejects any koffi outside this range, so the plugin-generated template and
        // the runtime check share the same contract.
        // No-op if the user already has a `package.json` (their customization wins).
        ffiNpmBundleTemplate := {
            val out = baseDirectory.value / "package.json"
            NpmBundleTemplate.writeTemplate(out, ffiLibraryId.value)
            streams.value.log.info(
                s"[kyo-ffi-plugin] Wrote npm bundle template (koffi pinned to ${NpmBundleTemplate.KoffiSupportedRange}) to $out"
            )
            out
        },
        ffiDumpCcCommand := {
            val cc              = ffiCCompiler.value
            val family          = CCompiler.detectFamily(cc)
            val globalFlags     = ffiCFlags.value
            val globalLinkFlags = ffiLinkFlags.value
            val globalIncludes  = ffiIncludes.value
            val globalStatic    = ffiStaticLink.value
            val targetDir       = target.value / "ffi"
            val libs = {
                val multi = ffiLibraries.value
                val raw = if (multi.nonEmpty) multi
                else Seq(
                    FfiLibrary(
                        id = ffiLibraryId.value,
                        cSources = ffiCSources.value,
                        cHeaders = ffiCHeaders.value,
                        linkLibs = ffiLinkLibs.value,
                        staticLink = globalStatic
                    )
                )
                topoSortLibraries(raw)
            }
            val os  = CCompiler.detectOs()
            val arc = CCompiler.detectArch()
            libs.map { lib =>
                val headerDirs = lib.cHeaders.map(_.getParentFile).distinct
                val includes   = (globalIncludes ++ headerDirs ++ lib.includeDirs).distinct
                val ext = os match {
                    case "linux"   => "so"
                    case "darwin"  => "dylib"
                    case "windows" => "dll"
                    case other     => sys.error(s"Unsupported OS: $other")
                }
                val prefix  = if (os == "windows") "" else "lib"
                val outFile = new File(targetDir, s"$prefix${lib.id}-$os-$arc.$ext")
                CCompiler.buildCommand(
                    cc = cc,
                    family = family,
                    cFlags = globalFlags ++ lib.cFlags,
                    linkFlags = globalLinkFlags ++ lib.linkFlags,
                    linkLibs = lib.resolvedLinkLibs(os),
                    sources = lib.cSources,
                    includes = includes,
                    outFile = outFile,
                    staticLink = lib.staticLink,
                    libDirs = lib.libDirs.distinct,
                    os = os
                )
            }
        },

        // ffiNativeLinkingOptions: only meaningful on Native (Nil elsewhere). Returns the
        // Scala Native linkingOptions a Native project wires into nativeConfig.linkingOptions:
        // ONLY the static-folded SYSTEM link libs (e.g. `-Wl,-Bstatic -luring -Wl,-Bdynamic`
        // on Linux; empty on macOS). The binding's own C is compiled into the binary by
        // Scala Native (copied under `resourceManaged/scala-native/` by the resource generator),
        // NOT linked as an archive, so there is no archive path here. Scala Native places the
        // compiled C objects BEFORE these linkingOptions in the clang command, so the C's
        // references to the system-lib symbols resolve against the `-l` flags that follow.
        ffiNativeLinkingOptions := {
            val platform = ffiTargetPlatform.value
            if (platform != "Native") Nil
            else {
                val buildOs = CCompiler.detectOs()
                val libs    = ffiLibrariesResolved.value
                libs.flatMap { lib =>
                    val libDirs = lib.libDirs.distinct
                    if (libDirs.nonEmpty)
                        // Vendored archives (e.g. staged BoringSSL): Scala Native's final clang link
                        // resolves the named archives from the staged -L tree. Scala Native places the
                        // bundled C objects AFTER these linkingOptions on the clang command, so a plain
                        // single-pass `-l` archive would be searched before the object that references it
                        // and ld would drop every member as unreferenced (undefined reference to SSL_*,
                        // BIO_*, X509_* ...). Force-load the archives so the link is order-independent:
                        // linux/GNU ld via -Wl,--whole-archive, darwin/ld64 via -Wl,-force_load per .a.
                        // The library's own `linkFlags` (the dynamic C++ runtime BoringSSL's C++ archives
                        // reference: -lc++ / -lstdc++) follow the archives so they resolve too.
                        CCompiler.vendoredArchiveForceLoadFlags(libDirs, lib.resolvedLinkLibs(buildOs), lib.staticLink, buildOs) ++
                            lib.linkFlags
                    else
                        CCompiler.foldedLinkLibFlags(lib.resolvedLinkLibs(buildOs), lib.staticLink)
                }
            }
        }
    )

    // --- helpers (settings/task fragments) --------------------------------------

    /** Native-only resource generator: copy each library's C sources into
      * `resourceManaged/scala-native/`. sbt's `copyResources` folds managed resources
      * into the compile `classDirectory` (on `fullClasspath`), and Scala Native scans
      * every `scala-native` directory on the classpath at `nativeLink`, compiling the
      * C into the binary. This is what lets a `nativeBundled` binding (emitted without
      * `@link`) resolve its C symbols with no `-l<library>` the linker can't find.
      *
      * No-op on JVM / JS (those load a shared library at runtime instead). Copies are
      * content-skipped: a destination identical to the source is left untouched so the
      * generator does not churn `nativeLink`'s input hash on every build.
      */
    private def ffiNativeResourceGenerator: Def.Initialize[Task[Seq[File]]] = Def.task {
        val platform = ffiTargetPlatform.value
        if (platform != "Native") Seq.empty[File]
        else {
            val log     = streams.value.log
            val libs    = ffiLibrariesResolved.value
            val destDir = (Compile / resourceManaged).value / "scala-native"
            val sources = libs.flatMap(_.cSources).distinct
            if (sources.isEmpty) Seq.empty[File]
            else {
                IO.createDirectory(destDir)
                sources.map { src =>
                    val dest = destDir / src.getName
                    // Only copy when content differs so the generated resource (and thus
                    // nativeLink's classpath hash) stays stable across no-change builds.
                    if (!dest.exists() || !IO.read(dest).equals(IO.read(src))) {
                        IO.copyFile(src, dest, preserveLastModified = true)
                        log.info(s"[kyo-ffi-plugin] Native: bundled C source ${src.getName} -> ${dest.getAbsolutePath}")
                    }
                    dest
                }
            }
        }
    }

    /** Resolve the list of libraries (multi-lib if non-empty, otherwise a single
      * synthesized entry). Used by packaging tasks that need the id→files mapping.
      */
    private def ffiLibrariesResolved: Def.Initialize[Seq[FfiLibrary]] = Def.setting {
        val multi = ffiLibraries.value
        val raw = if (multi.nonEmpty) multi
        else Seq(
            FfiLibrary(
                id = ffiLibraryId.value,
                cSources = ffiCSources.value,
                cHeaders = ffiCHeaders.value,
                linkLibs = ffiLinkLibs.value,
                staticLink = ffiStaticLink.value
            )
        )
        topoSortLibraries(raw)
    }

    /** #37: Deterministic topological sort by `dependsOn`. Returns libraries in
      * build order (dependencies before dependents). Input order is preserved
      * for independent libraries.
      *
      * Errors:
      *   - missing dependency id (not present in the declared set): reports the
      *     library name + missing id + declared ids.
      *   - dependency cycle: reports the cycle path (e.g. `a -> b -> a`).
      */
    private[sbt] def topoSortLibraries(libs: Seq[FfiLibrary]): Seq[FfiLibrary] = {
        if (libs.isEmpty) return libs
        if (libs.size == 1 && libs.head.dependsOn.isEmpty) return libs
        val byId: Map[String, FfiLibrary] = libs.map(l => l.id -> l).toMap
        if (byId.size != libs.size) {
            val dup = libs.groupBy(_.id).filter(_._2.size > 1).keys.mkString(", ")
            sys.error(s"[kyo-ffi-plugin] ffiLibraries has duplicate library ids: $dup")
        }
        libs.foreach { l =>
            l.dependsOn.foreach { dep =>
                if (!byId.contains(dep))
                    sys.error(
                        s"[kyo-ffi-plugin] FfiLibrary '${l.id}' depends on unknown library id '$dep'. " +
                            s"Declared ids: ${libs.map(_.id).mkString("[", ", ", "]")}"
                    )
            }
        }
        // Kahn's algorithm preserving input order among ready-to-emit nodes.
        val inputIndex = libs.zipWithIndex.map { case (l, i) => l.id -> i }.toMap
        val incoming   = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
        libs.foreach(l => incoming(l.id) = scala.collection.mutable.Set(l.dependsOn: _*))
        val result    = scala.collection.mutable.ListBuffer.empty[FfiLibrary]
        val remaining = scala.collection.mutable.Set.empty[String] ++ libs.map(_.id)
        while (remaining.nonEmpty) {
            val ready = remaining.filter(id => incoming(id).isEmpty).toSeq.sortBy(inputIndex)
            if (ready.isEmpty) {
                // Cycle. Find a cycle path by DFS from any remaining node.
                val start = remaining.head
                val path  = scala.collection.mutable.ListBuffer.empty[String]
                val seen  = scala.collection.mutable.LinkedHashSet.empty[String]
                var cur   = start
                while (!seen.contains(cur)) {
                    seen += cur
                    path += cur
                    val nextOpt = incoming(cur).headOption
                    nextOpt match {
                        case Some(n) => cur = n
                        case None    => cur = remaining.head
                    }
                }
                path += cur // close the cycle with the revisited node
                sys.error(
                    s"[kyo-ffi-plugin] FfiLibrary.dependsOn has a cycle: ${path.mkString(" -> ")}"
                )
            }
            ready.foreach { id =>
                result += byId(id)
                remaining -= id
                incoming.values.foreach(_ -= id)
            }
        }
        result.toList
    }

    /** Build the runtime JVM system properties that surface kyo-ffi settings to the
      * fork. Skips entries whose value is the default (no-op).
      */
    private def ffiRuntimeJavaOptions: Def.Initialize[Seq[String]] = Def.setting {
        val extractOpt = ffiExtractDir.value.map(f => s"-Dkyo.ffi.tmpdir=${f.getAbsolutePath}")
        val scratchOpt = Some(s"-Dkyo.ffi.scratch.size=${ffiScratchSize.value}")
        extractOpt.toSeq ++ scratchOpt.toSeq
    }

    /** Group the flat artifact list by library id based on the naming convention
      * encoded in `CCompiler.compile` (`lib<id>-<os>-<arch>.<ext>` on POSIX,
      * `<id>-<os>-<arch>.<ext>` on Windows). For each known library we pick the
      * artifacts whose filename starts with the library's prefix.
      */
    private def groupArtifactsByLibrary(artifacts: Seq[File], libs: Seq[FfiLibrary]): Seq[(String, Seq[File])] = {
        if (libs.size == 1) Seq(libs.head.id -> artifacts)
        else {
            libs.map { lib =>
                val prefixPosix = s"lib${lib.id}-"
                val prefixWin   = s"${lib.id}-"
                val matched = artifacts.filter { f =>
                    val n = f.getName
                    n.startsWith(prefixPosix) || n.startsWith(prefixWin)
                }
                lib.id -> matched
            }
        }
    }

    // Helpers
    private def collectTastyFiles(dir: File): Seq[String] = {
        if (!dir.exists()) Nil
        else {
            import scala.collection.JavaConverters._
            val stream = Files.walk(dir.toPath)
            try {
                stream.iterator().asScala
                    .filter(p => p.toString.endsWith(".tasty"))
                    .map(_.toAbsolutePath.toString)
                    .toList
            } finally stream.close()
        }
    }
}
