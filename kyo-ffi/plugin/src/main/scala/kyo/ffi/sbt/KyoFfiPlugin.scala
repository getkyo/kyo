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
        val ffiTargetOsArch = settingKey[Option[String]](
            "Target '<os>-<arch>' for the produced natives, e.g. Some(\"darwin-x86_64\") on an arm64 Mac. " +
                "It names the compiled artifact, the META-INF/native/<os>-<arch>/ directory it is packaged " +
                "into, and the OS the per-OS link libs and compiler overrides are resolved for. None (the " +
                "default, or -Dkyo.ffi.targetOsArch) means the build host, so an unset build behaves exactly " +
                "as a host-only build. Supported: " + CCompiler.supportedOsArchTags.mkString(", ") + ". " +
                "This setting does NOT make the compiler emit foreign code; pass the toolchain's own cross " +
                "flags too (e.g. ffiCFlags += \"-arch x86_64\" for darwin-x86_64 on an arm64 Mac)."
        )
        val ffiPrebuiltDir = settingKey[Option[File]](
            "Directory of prebuilt foreign-platform natives to merge into the packaged resources alongside " +
                "the locally-compiled ones. Each file must follow the compile-output convention " +
                "lib<id>-<os>-<arch>.<ext> (<id>-<os>-<arch>.dll on Windows) and is staged under ITS OWN " +
                "<os>-<arch> directory, parsed from that suffix. Every id must be declared in ffiLibraries / " +
                "ffiLibraryId. None (the default) merges nothing."
        )
        val ffiStubLibraries = settingKey[Seq[String]](
            "Library ids whose declared C sources are a placeholder rather than the real binding (e.g. a TLS " +
                "shim compiled with no vendored library staged). Recorded as 'stub' in the library-state " +
                "manifest so a packaging completeness check can tell a placeholder artifact from a real one."
        )
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
        val ffiNativeCompileOptions = taskKey[Seq[String]](
            "Scala Native compileOptions for ffiLibraries: the `-I` include dirs the bundled C is compiled " +
                "against (e.g. a staged BoringSSL include tree). A downstream Native module that recompiles " +
                "this module's bundled C needs the same headers, so it resolves the same functions the link " +
                "libs provide. Wire into nativeConfig.compileOptions in a Native project."
        )
        val ffiNativeDependencyLinkingOptions = taskKey[Seq[String]](
            "Scala Native linkingOptions the DEPENDENCIES on this project's classpath declare for their own " +
                "bundled C, read from the flag manifests they ship. Scala Native compiles a dependency's " +
                "bundled C into this binary, so this binary is the one that has to link its libraries " +
                "(e.g. -luring for kyo-net's io_uring shim). nativeConfig does not propagate across a " +
                "dependency, so wire this into nativeConfig.linkingOptions alongside ffiNativeLinkingOptions."
        )
        val ffiNativeDependencyCompileOptions = taskKey[Seq[String]](
            "Scala Native compileOptions the DEPENDENCIES on this project's classpath declare for their own " +
                "bundled C, read from the flag manifests they ship. The counterpart of " +
                "ffiNativeDependencyLinkingOptions for the compile side; wire into nativeConfig.compileOptions."
        )

        /** Diagnostic: return the resolved `cc` command line(s) the plugin would
          * invoke for the current library configuration, without executing it. One
          * `Seq[String]` per library (in multi-lib mode) with argv elements ready
          * for `ProcessBuilder`. Useful in tests and for debugging build issues.
          */
        val ffiDumpCcCommand = taskKey[Seq[Seq[String]]]("Return the cc command-line that ffiCompile would invoke.")

        /** The build host's `<os>-<arch>` tag, e.g. `darwin-aarch64`, or `linux-musl-x86_64` on a
          * musl distro. This is the plugin's own host resolver, exposed so a build that has to name
          * the same platform outside the plugin's tasks (staging a vendored dependency under
          * `staged/<os>-<arch>/`, pointing a test at a compiled artifact) derives it exactly once,
          * including the musl probe. Deriving it independently is how a musl leg ends up staging
          * under `linux-x86_64` while the packaged resources say `linux-musl-x86_64`.
          */
        def ffiHostOsArch: String = CCompiler.detectOs() + "-" + CCompiler.detectArch()

        /** The artifact filename `ffiCompile` produces for `libraryId` on `osArch`, e.g.
          * `ffiArtifactName("kyo_tcp", "linux-musl-x86_64") == "libkyo_tcp-linux-musl-x86_64.so"`.
          * Same reason as `ffiHostOsArch`: one spelling of the naming convention.
          */
        def ffiArtifactName(libraryId: String, osArch: String): String = {
            val (os, arch) = CCompiler.parseOsArch(osArch)
            CCompiler.artifactName(libraryId, os, arch)
        }

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
        // Unset means the host: every producer resolves the target through
        // CCompiler.resolveTargetOsArch, so a build that never sets this behaves exactly as before.
        // The system property lets a CI matrix leg name its target without a build edit.
        ffiTargetOsArch  := sys.props.get("kyo.ffi.targetOsArch"),
        ffiPrebuiltDir   := None,
        ffiStubLibraries := Nil,
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
            val genTarget = target.value
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
                        // No bindings remain: the persisted trait -> library-id index is stale, drop it so the
                        // native manifest stops indexing traits that no longer exist.
                        writeTraitLibraryIndex(genTarget, Nil)
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
                        // Persist the reflection-free binding-trait -> library-id index the native manifest
                        // generator reads. The codegen knows each binding's `Ffi.Config.library` here; the manifest
                        // resource generator runs as a separate task and cannot re-derive it, so it is written to a
                        // stable file under `target` that survives cache hits (rewritten only on a codegen miss).
                        writeTraitLibraryIndex(genTarget, generated.traits.map(t => t.fqcn -> t.library))
                        // GraalVM native-image reachability metadata for this module's JVM impls (empty for JS/Native),
                        // persisted to a stable file under target that survives cache hits exactly like the index above; the
                        // reachability-metadata resource generator copies it into managed resources.
                        writeReachabilityMetadata(genTarget, generated.reachabilityMetadata)
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
            // The platform the artifacts are FOR: the host unless ffiTargetOsArch names another.
            val (targetOs, targetArch) = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)

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
                    // Per-OS link libs and compiler overrides resolve against the TARGET OS (the host
                    // unless ffiTargetOsArch overrides it), so a Linux-only system lib (e.g. uring) is
                    // omitted from the macOS / Windows command, and IS included when a build targets
                    // Linux from elsewhere.
                    libs.zipWithIndex.flatMap { case (lib, idx) =>
                        if (lib.cSources.isEmpty) {
                            // Declared with no C sources: an INTENTIONALLY-ABSENT native, not a failed
                            // build. The library-state manifest records it as `absent` so a packaging
                            // completeness check does not read the missing artifact as a broken leg.
                            log.info(s"[kyo-ffi-plugin] ffiCompile: ${lib.id} declares no C sources (intentionally absent); skipping.")
                            Nil
                        } else {
                            val perLibCacheDir = cacheDir / s"lib-${idx}-${lib.id}"
                            IO.createDirectory(perLibCacheDir)
                            // A library may override the global compiler for the OS being built
                            // (e.g. Aeron, which supports Windows only under MSVC); the default empty
                            // map leaves every other library on the global `cc`, unchanged.
                            val libCc      = lib.compilerFor(targetOs).getOrElse(cc)
                            val flags      = globalFlags ++ lib.cFlags
                            val linkFlags  = globalLinkFlags ++ lib.linkFlags
                            val linkLibs   = lib.resolvedLinkLibs(targetOs)
                            val staticLink = lib.staticLink
                            // Derive -I dirs from header file parent directories + explicit
                            // ffiIncludes + the library's vendored includeDirs (e.g. the staged
                            // BoringSSL include/ tree). -L dirs come from the library's libDirs.
                            val headerDirs = lib.cHeaders.map(_.getParentFile).distinct
                            val includes   = (globalIncludes ++ headerDirs ++ lib.includeDirs).distinct
                            val libDirs    = lib.libDirs.distinct

                            // `target=` keys the cache on the resolved os/arch: the artifact NAME is
                            // derived from it, so without it a re-run under a different
                            // ffiTargetOsArch would hit the cache and hand back the previous
                            // target's file.
                            val configHash =
                                s"$libCc|${flags.mkString(",")}|${linkFlags.mkString(",")}|${linkLibs.mkString(",")}|${lib.id}|${includes.map(_.getAbsolutePath).mkString(",")}|libdirs=${libDirs.map(_.getAbsolutePath).mkString(",")}|static=$staticLink|target=$targetOs-$targetArch"
                            val configSentinel = perLibCacheDir / "config.hash"
                            IO.write(configSentinel, configHash)

                            val cached = FileFunction.cached(perLibCacheDir, FilesInfo.hash, FilesInfo.exists) { _ =>
                                log.info(s"[kyo-ffi-plugin] ffiCompile: cc invocation for ${lib.id}.")
                                CCompiler.compile(
                                    cc = libCc,
                                    cFlags = flags,
                                    linkFlags = linkFlags,
                                    linkLibs = linkLibs,
                                    sources = lib.cSources,
                                    libraryId = lib.id,
                                    os = targetOs,
                                    arch = targetArch,
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

        // ffiPackage: explicit task that copies artifacts; same body as the resource generator below.
        ffiPackage := ffiPackagedNatives.value,

        // Copy compiled artifacts (and any staged prebuilts) into the resource tree automatically.
        // On Native this is a no-op (ffiCompile returns Nil). On JVM and JS the
        // artifacts land under META-INF/native/{os}-{arch}/ for NativeLoader/koffi.
        Compile / resourceGenerators += ffiPackagedNatives.taskValue,

        // JVM/JS only: record what this build packages for each declared library id, so a packaging
        // completeness check reads a declaration instead of guessing from a file that is not there.
        Compile / resourceGenerators += ffiLibraryStateManifestGenerator.taskValue,

        // JVM/JS only: emit the native manifest the runtime reads as DATA for the direct-load pre-check --
        // per library id its bundled `<os>-<arch>` platforms, version and minRuntime, plus a reflection-free
        // binding-trait -> library-id index. See `ffiNativeManifestGenerator`.
        Compile / resourceGenerators += ffiNativeManifestGenerator.taskValue,

        // GraalVM native-image: ship the codegen-emitted reachability-metadata.json (JVM only) so a downstream
        // native-image build discovers the FFM downcalls/upcalls and the generated impls' reflection with no agent.
        Compile / resourceGenerators += ffiReachabilityMetadataGenerator.taskValue,

        // Native only: copy each library's C sources into `resourceManaged/scala-native/`.
        // sbt's `copyResources` then folds managed resources into the compile `classDirectory`,
        // which is on `fullClasspath`; Scala Native scans every `scala-native` directory on the
        // classpath at `nativeLink` and compiles the C into the binary itself. This is the
        // mechanism that lets a `nativeBundled` binding (no `@link`) resolve its C symbols
        // without a `-l<library>` the linker can't find. No-op on JVM / JS.
        Compile / resourceGenerators += ffiNativeResourceGenerator.taskValue,

        // Native only: write this module's FFI link + compile flags to classpath manifests so a downstream
        // Native module that recompiles this module's bundled C compiles and links it the same way. See
        // `ffiNativeFlagsManifestGenerator`. No-op elsewhere.
        Compile / resourceGenerators += ffiNativeFlagsManifestGenerator.taskValue,

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
            // Same target resolution and same artifact naming as ffiCompile, so the dumped command
            // is the command that would actually run (this used to re-derive the os→ext mapping and
            // hard-error on linux-musl).
            val (os, arc) = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)
            libs.map { lib =>
                val libCc      = lib.compilerFor(os).getOrElse(cc)
                val libFamily  = CCompiler.detectFamily(libCc)
                val headerDirs = lib.cHeaders.map(_.getParentFile).distinct
                val includes   = (globalIncludes ++ headerDirs ++ lib.includeDirs).distinct
                val outFile    = new File(targetDir, CCompiler.artifactName(lib.id, os, arc))
                CCompiler.buildCommand(
                    cc = libCc,
                    family = libFamily,
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
                // Same target OS ffiCompile resolves its link libs for (the host unless
                // ffiTargetOsArch overrides it), so the two never disagree about which per-OS libs
                // a build needs.
                val buildOs = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)._1
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
        },

        // ffiNativeCompileOptions: only meaningful on Native (Nil elsewhere). The `-I` include dirs the
        // bundled C is compiled against (e.g. a staged BoringSSL include tree). A downstream Native module
        // that recompiles this module's bundled C (Scala Native scans every `scala-native` dir on the
        // classpath) MUST compile it against the SAME headers so its `SSL_*` macro/function references
        // resolve to the same library the link libs provide. Otherwise the bundled C compiles against the
        // system openssl headers (`SSL_CTX_set_min_proto_version` -> the `SSL_CTX_ctrl` macro) while the
        // link resolves against a BoringSSL archive that does not export `SSL_CTX_ctrl`, and nativeLink
        // fails with `undefined reference to SSL_CTX_ctrl`.
        ffiNativeCompileOptions := {
            val platform = ffiTargetPlatform.value
            val libs     = ffiLibrariesResolved.value
            if (platform != "Native") Nil
            else libs.flatMap(_.includeDirs).distinct.map(d => s"-I${d.getAbsolutePath}")
        },

        // The flags this project's DEPENDENCIES declare, read off their manifests. Without these a consumer
        // links a dependency's bundled C with none of the libraries that C needs: kyo-net's io_uring shim is
        // compiled into the consumer's binary but `-luring` never reaches the link, and it fails on symbols the
        // consumer never wrote. `nativeConfig` is per-project and does not cross a dependency edge, which is
        // what the manifests exist to bridge.
        ffiNativeDependencyLinkingOptions := {
            val platform = ffiTargetPlatform.value
            val cp       = (Compile / dependencyClasspath).value.map(_.data)
            val targetOs = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)._1
            if (platform != "Native") Nil
            else readNativeFlagManifests(cp, ffiNativeLinkFlagsDir, ffiNativeInBuildLinkFlagsDir, targetOs)
        },
        ffiNativeDependencyCompileOptions := {
            val platform = ffiTargetPlatform.value
            val cp       = (Compile / dependencyClasspath).value.map(_.data)
            val targetOs = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)._1
            if (platform != "Native") Nil
            else readNativeFlagManifests(cp, ffiNativeCompileFlagsDir, ffiNativeInBuildCompileFlagsDir, targetOs)
        }
    ) ++ ffiPackageBinFlagsFilter

    /** Read the native-flag manifests every entry of `cp` carries for `targetOs`, one flag per line, deduped first-seen.
      *
      * Each entry is read from its IN-BUILD directory when it has one and from the packaged one otherwise. A module built alongside this one
      * shares its filesystem, so the vendored tree it compiled against is a real path here and the unfiltered answer is the right one; a
      * module resolved as a published artifact carries only the portable answer, because the paths in the other one name a machine this
      * build has never seen.
      *
      * Only `targetOs`'s files are read. The classpath carries published artifacts too, and a flag set produced for another OS does not
      * merely fail to help: `-luring` and the GNU-ld options ld64 rejects break a Darwin link outright.
      */
    def readNativeFlagManifests(
        cp: Seq[File],
        relDir: Seq[String],
        inBuildRelDir: Seq[String],
        targetOs: String
    ): Seq[String] =
        cp.flatMap { entry =>
            val inBuild = inBuildRelDir.foldLeft(entry)(_ / _)
            val dir     = if (inBuild.isDirectory) inBuild else relDir.foldLeft(entry)(_ / _)
            if (dir.isDirectory) (dir * s"*-$targetOs.flags").get.flatMap(IO.readLines(_)) else Seq.empty[String]
        }.map(_.trim).filter(_.nonEmpty).distinct

    // --- helpers (settings/task fragments) --------------------------------------

    /** A prebuilt native staged through `ffiPrebuiltDir`, with the library id and the platform its
      * own filename declares. Nothing here is derived from the build host.
      */
    final private[sbt] case class PrebuiltNative(file: File, libraryId: String, os: String, arch: String)

    /** The prebuilt natives under `dir`, one `PrebuiltNative` per file.
      *
      * Hidden files are ignored (a downloaded artifact tree carries `.DS_Store` and friends);
      * anything else that does not follow the `lib<id>-<os>-<arch>.<ext>` convention is a hard
      * error, as is a missing `dir`. Skipping an unparseable file would publish a jar quietly
      * missing the platform whoever set this setting staged, which is the failure this input exists
      * to make impossible.
      */
    private[sbt] def prebuiltNatives(dir: Option[File]): Seq[PrebuiltNative] = dir match {
        case None => Nil
        case Some(d) =>
            if (!d.isDirectory)
                sys.error(s"[kyo-ffi-plugin] ffiPrebuiltDir is not an existing directory: ${d.getAbsolutePath}")
            val files = (d ** "*").get.filter(f => f.isFile && !f.getName.startsWith("."))
            files.map { f =>
                CCompiler.parseArtifactName(f.getName) match {
                    case Some((id, os, arch)) => PrebuiltNative(f, id, os, arch)
                    case None =>
                        sys.error(
                            s"[kyo-ffi-plugin] ffiPrebuiltDir: '${f.getName}' does not follow the " +
                                s"lib<id>-<os>-<arch>.<ext> naming ffiCompile produces, so the platform it is for " +
                                s"cannot be determined. Rename it (os-arch one of: " +
                                s"${CCompiler.supportedOsArchTags.mkString(", ")}) or remove it from " +
                                s"${d.getAbsolutePath}."
                        )
                }
            }
    }

    /** The locally-compiled artifacts a staged prebuilt overrides: those whose parsed
      * `(id, os, arch)` a prebuilt also supplies. Under the P3 publish topology the release host both
      * compiles its own os-arch and downloads every producer's native into `ffiPrebuiltDir`, so its
      * own os-arch collides; the prebuilt is authoritative (a dedicated producer staged BoringSSL for
      * that os-arch, the publish host may not), so the colliding local artifact is the one dropped.
      * An artifact whose name carries no recognized `<os>-<arch>` suffix is never overridden.
      */
    private[sbt] def prebuiltOverriddenCompiled(compiled: Seq[File], prebuilt: Seq[PrebuiltNative]): Seq[File] = {
        val prebuiltKeys = prebuilt.map(p => (p.libraryId, p.os, p.arch)).toSet
        compiled.filter(f => CCompiler.parseArtifactName(f.getName).exists(prebuiltKeys.contains))
    }

    /** Stage every native this build packages into the runtime resource layout, and return the
      * destinations. Shared by `ffiPackage` and the `Compile / resourceGenerators` entry.
      *
      * Two sources, each landing under the platform it is actually for:
      *   - the locally-compiled artifacts, under the resolved build target (`ffiTargetOsArch`,
      *     defaulting to the host);
      *   - the prebuilts staged through `ffiPrebuiltDir`, each under the `<os>-<arch>` its own
      *     filename declares, so a publish host can fold in natives built on other runners.
      *
      * A prebuilt naming an undeclared library id fails the build (it would package under a
      * name no runtime looks up). A prebuilt colliding with a locally-compiled artifact for the
      * same (id, os, arch) does NOT fail: the prebuilt wins and the local one is dropped (logged).
      * The P3 publish host both compiles its own os-arch and downloads every producer's native into
      * `ffiPrebuiltDir`, including its own; the dedicated producer staged BoringSSL for that os-arch
      * while the publish host may not, so the downloaded prebuilt is the authoritative artifact.
      */
    private def ffiPackagedNatives: Def.Initialize[Task[Seq[File]]] = Def.task {
        val log         = streams.value.log
        val compiled    = ffiCompile.value
        val resDir      = (Compile / resourceManaged).value / "META-INF" / "native"
        val platform    = ffiTargetPlatform.value
        val libs        = ffiLibrariesResolved.value
        val prebuiltDir = ffiPrebuiltDir.value
        val prebuilt    = prebuiltNatives(prebuiltDir)

        val (targetOs, targetArch) = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)

        val declared   = libs.map(_.id).toSet
        val undeclared = prebuilt.filterNot(p => declared.contains(p.libraryId))
        if (undeclared.nonEmpty)
            sys.error(
                s"[kyo-ffi-plugin] ffiPrebuiltDir stages natives for undeclared library ids: " +
                    s"${undeclared.map(p => s"${p.file.getName} (id '${p.libraryId}')").mkString(", ")}. " +
                    s"Declared ids: ${declared.toSeq.sorted.mkString("[", ", ", "]")}. Declare the id (an entry with " +
                    s"no cSources is enough when only the prebuilt supplies it) or fix the filename."
            )

        // Prebuilt wins on a collision: drop each locally-compiled artifact whose (id, os, arch) a
        // prebuilt also supplies, so the staged prebuilt is the one that lands in the resource path.
        val overridden = prebuiltOverriddenCompiled(compiled, prebuilt)
        if (overridden.nonEmpty)
            log.info(
                s"[kyo-ffi-plugin] ffiPackage: ${overridden.size} locally-compiled native(s) overridden by a staged " +
                    s"prebuilt for the same (id, os, arch): ${overridden.map(_.getName).sorted.mkString(", ")}."
            )
        val compiledKept = compiled.filterNot(overridden.contains)

        val local =
            if (compiledKept.isEmpty) Seq.empty[File]
            else Packager.copyForPlatformMulti(platform, groupArtifactsByLibrary(compiledKept, libs), resDir, targetOs, targetArch)

        val staged = prebuilt.groupBy(p => (p.os, p.arch)).toSeq.sortBy(_._1).flatMap {
            case ((os, arch), group) =>
                Packager.copyForPlatformMulti(platform, groupArtifactsByLibrary(group.map(_.file), libs), resDir, os, arch)
        }
        if (staged.nonEmpty)
            log.info(
                s"[kyo-ffi-plugin] ffiPackage: staged ${staged.size} prebuilt native(s) from " +
                    s"${prebuiltDir.map(_.getAbsolutePath).getOrElse("")} for " +
                    s"${prebuilt.map(p => s"${p.os}-${p.arch}").distinct.sorted.mkString(", ")}."
            )
        local ++ staged
    }

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

    /** The classpath-relative directories KyoFfiPlugin writes each Native module's link- and compile-flag
      * manifests into (one `<module>.flags` file per FFI module in each). A downstream Native module reads
      * every dependency's manifests from these directories on its classpath and folds the flags into its own
      * `nativeConfig.linkingOptions` / `compileOptions`. Kept in sync with the reader in build.sbt's
      * `native-settings`.
      */
    val ffiNativeLinkFlagsDir: Seq[String]    = Seq("META-INF", "kyo-ffi", "native-link-flags")
    val ffiNativeCompileFlagsDir: Seq[String] = Seq("META-INF", "kyo-ffi", "native-compile-flags")

    /** The sibling directories carrying the SAME flags unfiltered, for a downstream module in the same build.
      *
      * Two audiences want different answers to "what flags does this module's bundled C need". A module built alongside this one shares its
      * filesystem, so the staged BoringSSL tree this module compiled against is a real path it can use. A module that resolves this one as a
      * published artifact does not: that path names a machine it has never seen. The packaged manifests answer the second question and these
      * answer the first, which is why these are dropped from `packageBin` (see [[ffiPackageBinFlagsFilter]]) and never leave the build.
      */
    val ffiNativeInBuildLinkFlagsDir: Seq[String]    = Seq("META-INF", "kyo-ffi", "native-link-flags-inbuild")
    val ffiNativeInBuildCompileFlagsDir: Seq[String] = Seq("META-INF", "kyo-ffi", "native-compile-flags-inbuild")

    /** The OS tag the native-flag manifests are named by: `ffiTargetOsArch`'s OS when it is set, this host's otherwise.
      *
      * Public because a reader outside a task context needs it to pick its own target's file, and reading the system property mirrors what
      * `ffiTargetOsArch` defaults to. A reader with a task context should prefer the setting.
      */
    def ffiManifestTargetOs: String = CCompiler.resolveTargetOsArch(sys.props.get("kyo.ffi.targetOsArch"))._1

    /** Native-only resource generator: write this module's Scala Native FFI link flags
      * (`ffiNativeLinkingOptions`, e.g. `-Wl,-Bstatic -luring -Wl,-Bdynamic` on Linux, plus the staged
      * BoringSSL force-load) and compile flags (`ffiNativeCompileOptions`, the `-I` include dirs the bundled
      * C is compiled against) to classpath manifests under the `resourceManaged` `META-INF/kyo-ffi`
      * subtrees (one flag per line).
      *
      * A `nativeBundled` binding's C sources already ride the classpath (via `ffiNativeResourceGenerator`)
      * and Scala Native compiles them into ANY downstream binary, so a module that depends on this one both
      * COMPILES that C (needs the same `-I` headers, or a `SSL_*` reference resolves to the wrong library)
      * and LINKS its symbols (needs the same `-l` libs, e.g. `io_uring_*`). But `nativeConfig` is per-project
      * and does NOT propagate across a project dependency, so the downstream build would fail (compile
      * against system openssl headers -> `SSL_CTX_ctrl` macro, then `undefined reference to SSL_CTX_ctrl`
      * against a BoringSSL archive that does not export it; or `undefined reference to io_uring_*`). These
      * manifests carry the same flags across the classpath; build.sbt's `native-settings` reads every
      * dependency's manifests and folds them into the downstream `nativeConfig`, mirroring how the bundled C
      * itself propagates.
      *
      * The manifests are PACKAGED, so they also travel to machines that have never seen this filesystem, and two things follow. The file is
      * named `<module>-<targetOs>.flags` and a reader keeps only its own target's, because a flag set produced for Linux (`-luring`, GNU-ld
      * options ld64 rejects) is not merely useless on Darwin, it breaks the link. And the flags are filtered through
      * [[partitionPortableFlags]], because a path is a fact about the machine that wrote it: a released artifact used to carry the release
      * runner's `-L/home/runner/work/kyo/kyo/.../boringssl/staged/linux-x86_64/lib` verbatim, pointing at archives no consumer has and the
      * artifact does not ship. What survives is what means the same thing anywhere: `-l<name>` and bare linker options.
      *
      * No-op on JVM / JS (they load a shared library at runtime, so there are no native build flags). An
      * empty flag set (e.g. macOS, where liburing does not apply) writes no file and removes a stale one, so
      * a now-flagless build does not leak a previous build's flags downstream.
      */
    private def ffiNativeFlagsManifestGenerator: Def.Initialize[Task[Seq[File]]] = Def.task {
        // All task/setting lookups are hoisted out of the `if` (sbt evaluates task dependencies eagerly
        // regardless of branch; on JVM/JS the FFI-flag tasks return Nil, so this is a cheap no-op).
        val platform     = ffiTargetPlatform.value
        val linkFlags    = ffiNativeLinkingOptions.value
        val compileFlags = ffiNativeCompileOptions.value
        val resManaged   = (Compile / resourceManaged).value
        val moduleName   = name.value
        val targetOs     = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)._1
        val log          = streams.value.log
        // The `-l` names that only resolve inside a vendored tree this artifact does not ship. They travel with the tree, so they leave
        // with it: see `partitionPortableFlags`.
        val vendoredLinkLibs =
            ffiLibrariesResolved.value.filter(_.libDirs.nonEmpty).flatMap(_.resolvedLinkLibs(targetOs)).distinct.toSet
        if (platform != "Native") Seq.empty[File]
        else {
            val (portableLink, droppedLink)       = partitionPortableFlags(linkFlags, vendoredLinkLibs)
            val (portableCompile, droppedCompile) = partitionPortableFlags(compileFlags, vendoredLinkLibs)
            val dropped                           = (droppedLink ++ droppedCompile).distinct
            if (dropped.nonEmpty)
                log.debug(
                    s"[kyo-ffi-plugin] $moduleName: ${dropped.size} host-specific flag(s) kept out of the packaged " +
                        s"native-flag manifest: ${dropped.mkString(" ")}"
                )
            writeFfiManifest(resManaged, ffiNativeLinkFlagsDir, s"$moduleName-$targetOs.flags", portableLink) ++
                writeFfiManifest(resManaged, ffiNativeCompileFlagsDir, s"$moduleName-$targetOs.flags", portableCompile) ++
                writeFfiManifest(resManaged, ffiNativeInBuildLinkFlagsDir, s"$moduleName-$targetOs.flags", linkFlags) ++
                writeFfiManifest(resManaged, ffiNativeInBuildCompileFlagsDir, s"$moduleName-$targetOs.flags", compileFlags)
        }
    }

    /** Drops the in-build flag manifests from `packageBin`, so the paths they name never leave this machine.
      *
      * They are generated resources and therefore packaged by default, which is how a release came to ship
      * `-L/home/runner/work/kyo/kyo/kyo-net/native/../build/boringssl/staged/linux-x86_64/lib` inside its jar. The classpath a downstream
      * module reads carries the generated `classDirectory` for a project dependency and the jar for a resolved one, so filtering here keeps
      * the in-build answer available exactly where it is true.
      */
    private def ffiPackageBinFlagsFilter: Seq[Setting[?]] = Seq(
        Compile / packageBin / mappings := {
            val inBuild = Set(ffiNativeInBuildLinkFlagsDir.mkString("/"), ffiNativeInBuildCompileFlagsDir.mkString("/"))
            (Compile / packageBin / mappings).value.filterNot { case (_, path) =>
                inBuild.exists(dir => path.replace('\\', '/').startsWith(dir + "/"))
            }
        }
    )

    /** Split `flags` into the ones that mean the same thing on any machine and the ones that name a path on THIS one.
      *
      * The manifests are packaged, so they travel to machines that have never seen this filesystem. A released artifact used to carry the
      * release runner's own tree verbatim, `-L/home/runner/work/kyo/kyo/kyo-net/native/../build/boringssl/staged/linux-x86_64/lib`, an `-I`
      * beside it, and `-Wl,-force_load,<abs path to libssl.a>` on Darwin: none of those exist on a consumer's machine, and they point at
      * archives the artifact does not ship anyway.
      *
      * The first test is whether the flag contains a path separator at all, rather than whether it starts with one. A path can sit anywhere
      * in a flag (`-Wl,-force_load,<path>` carries it third) and a relative path is no more portable than an absolute one, since the
      * reader's working directory is not this one either.
      *
      * The second is `vendoredLinkLibs`: the `-l` names belonging to a library whose archives live in a vendored tree. Those names carry no
      * path and would survive the first test, but they only mean anything next to the `-L` that finds the tree, and the artifact ships
      * neither. Left in, `-lssl -lcrypto` would quietly resolve against a consumer's SYSTEM OpenSSL under the vendored library's name,
      * which links and runs and reports the wrong provider. A vendored library's flags therefore leave as a set, with the tree they need.
      *
      * What survives is what names no file and needs no tree: a system `-l<name>` such as `-luring`, and bare linker options.
      *
      * The dropped flags are not lost to the build that produced them; they are written to the in-build manifests, which
      * [[ffiPackageBinFlagsFilter]] keeps out of the jar.
      */
    private[sbt] def partitionPortableFlags(flags: Seq[String], vendoredLinkLibs: Set[String] = Set.empty): (Seq[String], Seq[String]) =
        flags.partition { flag =>
            val namesVendoredLib = flag.startsWith("-l") && vendoredLinkLibs.contains(flag.drop(2))
            !namesVendoredLib && !flag.contains('/') && !flag.contains('\\')
        }

    /** The classpath-relative directory KyoFfiPlugin writes each module's library-state manifest into
      * (one `<module>.state` file per FFI module).
      */
    val ffiLibraryStateDir: Seq[String] = Seq("META-INF", "kyo-ffi", "library-state")

    /** JVM/JS resource generator: write one `<id>=<state>` line per declared library id, recording what
      * this build packages for it. Emitted next to the natives themselves so a packaging completeness
      * check reads a declaration rather than inferring intent from a file that is not there.
      *
      * Without it, "no `META-INF/native/<os>-<arch>/lib<id>.<ext>`" is ambiguous between a library that
      * is deliberately empty, one whose native this leg failed to build, and one carrying a placeholder;
      * a check keyed on the layout alone either false-positives on the first or misses the third. The
      * four states, in the order they are decided:
      *   - `stub`: C sources are declared but they are a placeholder (`ffiStubLibraries`), e.g. a TLS
      *     shim compiled with no vendored library staged. An artifact exists; it does nothing.
      *   - `native`: C sources are declared and compiled into the real artifact.
      *   - `prebuilt`: no C sources here; the artifact comes from `ffiPrebuiltDir` (a native another
      *     runner built).
      *   - `absent`: no C sources and no prebuilt. The id is declared (so bindings referencing it
      *     validate) and carries no native ON PURPOSE.
      *
      * This describes the DECLARATION, so it stays readable when a leg fails before packaging: a
      * `native` id with no file in the layout is precisely the broken-leg case a check should reject.
      */
    private def ffiLibraryStateManifestGenerator: Def.Initialize[Task[Seq[File]]] = Def.task {
        val platform   = ffiTargetPlatform.value
        val libs       = ffiLibrariesResolved.value
        val stubs      = ffiStubLibraries.value.toSet
        val prebuilt   = prebuiltNatives(ffiPrebuiltDir.value).map(_.libraryId).toSet
        val resManaged = (Compile / resourceManaged).value
        val moduleName = name.value
        if (platform == "Native") Seq.empty[File]
        else {
            val states = libs.map { lib =>
                val state =
                    if (lib.cSources.nonEmpty) { if (stubs.contains(lib.id)) "stub" else "native" }
                    else if (prebuilt.contains(lib.id)) "prebuilt"
                    else "absent"
                s"${lib.id}=$state"
            }
            writeFfiManifest(resManaged, ffiLibraryStateDir, moduleName + ".state", states.sorted)
        }
    }

    /** Write `lines` (one per line) to `<resManaged>/<relDir>/<fileName>`, content-skipped so the
      * generated resource (and thus nativeLink's classpath hash) stays stable across no-change builds. An
      * empty list writes nothing and removes a stale file so it does not leak downstream.
      */
    private def writeFfiManifest(resManaged: File, relDir: Seq[String], fileName: String, lines: Seq[String]): Seq[File] = {
        val destDir = relDir.foldLeft(resManaged)(_ / _)
        val dest    = destDir / fileName
        if (lines.isEmpty) {
            if (dest.exists()) IO.delete(dest)
            Seq.empty[File]
        } else {
            IO.createDirectory(destDir)
            val content = lines.mkString("", "\n", "\n")
            if (!dest.exists() || IO.read(dest) != content) IO.write(dest, content)
            Seq(dest)
        }
    }

    /** The classpath-relative directory KyoFfiPlugin writes each module's native manifest into (one
      * `<module>.manifest` per FFI module). The runtime enumerates every file under this directory across the
      * classpath, so the per-module filename keeps kyo-net, kyo-aeron and other FFI modules from colliding.
      */
    val ffiNativeManifestDir: Seq[String] = Seq("META-INF", "kyo-ffi", "native-manifest")

    /** JVM/JS resource generator: emit the native manifest the runtime reads as DATA for the reflection-free
      * direct-load pre-check. One block per declared library id plus a binding-trait -> library-id index:
      * {{{
      * <id>.platforms=darwin-x86_64,...      # the <os>-<arch> this build bundles the native for
      * <id>.version=<kyo version>            # the release the native ships with (single source, lockstep)
      * <id>.minRuntime=<kyo version>         # the minimum kyo-ffi runtime the native requires
      * trait.<binding-trait-FQN>=<id>        # so `Ffi.load[T]` maps T -> id without touching the generated impl
      * }}}
      *
      * `platforms` mirrors what `ffiPackagedNatives` stages: the resolved build target (`ffiTargetOsArch`,
      * defaulting to the host) for a locally-compiled library, plus each `ffiPrebuiltDir` native's own
      * `<os>-<arch>`. An `absent` library (no C sources, no prebuilt) has an empty platform set; the pre-check
      * treats a native not bundled for the current platform as a graceful demote-to-fallback, not an error.
      *
      * The trait index comes from the codegen (`ffiGenerate`), which knows each binding's `Ffi.Config.library`
      * at generation time and persists the pairs to `target`; this reads them so the manifest stays
      * reflection-free. No-op on Native (which links its C at build time and loads nothing at runtime).
      */
    private def ffiNativeManifestGenerator: Def.Initialize[Task[Seq[File]]] = Def.task {
        // Force the codegen so the persisted trait -> library-id index is fresh, then read it.
        val _                      = ffiGenerate.value
        val platform               = ffiTargetPlatform.value
        val libs                   = ffiLibrariesResolved.value
        val prebuilt               = prebuiltNatives(ffiPrebuiltDir.value)
        val resManaged             = (Compile / resourceManaged).value
        val moduleName             = name.value
        val projTarget             = target.value
        val moduleVer              = version.value
        val (targetOs, targetArch) = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)
        if (platform == "Native") Seq.empty[File]
        else {
            val targetTag = s"$targetOs-$targetArch"
            val idBlocks = libs.sortBy(_.id).flatMap { lib =>
                val local     = if (lib.cSources.nonEmpty) Set(targetTag) else Set.empty[String]
                val staged    = prebuilt.filter(_.libraryId == lib.id).map(p => s"${p.os}-${p.arch}").toSet
                val platforms = (local ++ staged).toSeq.sorted
                Seq(
                    s"${lib.id}.platforms=${platforms.mkString(",")}",
                    s"${lib.id}.version=$moduleVer",
                    s"${lib.id}.minRuntime=$moduleVer"
                )
            }
            val traitLines =
                readTraitLibraryIndex(projTarget).sortBy(_._1).map { case (fqcn, library) => s"trait.$fqcn=$library" }
            writeFfiManifest(resManaged, ffiNativeManifestDir, moduleName + ".manifest", idBlocks ++ traitLines)
        }
    }

    /** Ship the GraalVM native-image reachability metadata that `ffiGenerate` emitted for this module's JVM impls (FFM
      * downcalls/upcalls plus the generated `*Impl` reflective instantiation), under
      * `META-INF/native-image/io.getkyo/<module>/reachability-metadata.json` so native-image auto-discovers it with no
      * tracing agent. JVM only: JS and Native modules persist no content, so this generates nothing.
      */
    private def ffiReachabilityMetadataGenerator: Def.Initialize[Task[Seq[File]]] = Def.task {
        // Force the codegen so the persisted reachability metadata is fresh, then copy it into managed resources.
        val _          = ffiGenerate.value
        val projTarget = target.value
        val resManaged = (Compile / resourceManaged).value
        val moduleName = name.value
        val src        = ffiReachabilityMetadataFile(projTarget)
        if (!src.exists()) Seq.empty[File]
        else {
            val content = IO.read(src)
            if (content.trim.isEmpty) Seq.empty[File]
            else {
                val dest =
                    resManaged / "META-INF" / "native-image" / "io.getkyo" / moduleName / "reachability-metadata.json"
                IO.createDirectory(dest.getParentFile)
                if (!dest.exists() || IO.read(dest) != content) IO.write(dest, content)
                Seq(dest)
            }
        }
    }

    /** File under `target` where `ffiGenerate` persists the binding-trait -> library-id pairs the native manifest
      * generator reads (one `<fqcn>=<library>` per line). Kept out of the resource tree so it is never itself
      * packaged; it is a cross-task hand-off, not a shipped artifact.
      */
    private def ffiTraitLibraryIndexFile(projTarget: File): File =
        projTarget / "kyo-ffi" / "trait-library-index.txt"

    /** File under `target` where `ffiGenerate` persists the JVM reachability-metadata.json content the native-image
      * resource generator ships. Kept out of the resource tree so it is never itself packaged; a cross-task hand-off.
      */
    private def ffiReachabilityMetadataFile(projTarget: File): File =
        projTarget / "kyo-ffi" / "reachability-metadata.json"

    /** Persist the GraalVM native-image reachability metadata for the resource generator, content-skipped. Empty content
      * removes a stale file (a JS/Native module, or a module whose bindings were all deleted).
      */
    private def writeReachabilityMetadata(projTarget: File, content: String): Unit = {
        val dest = ffiReachabilityMetadataFile(projTarget)
        if (content.isEmpty) {
            if (dest.exists()) IO.delete(dest): Unit
        } else {
            IO.createDirectory(dest.getParentFile)
            if (!dest.exists() || IO.read(dest) != content) IO.write(dest, content)
        }
    }

    /** Persist `pairs` (`<fqcn> -> <library>`) for the native manifest generator, content-skipped. An empty list
      * removes a stale index so a module whose bindings were all deleted stops indexing traits that are gone.
      */
    private def writeTraitLibraryIndex(projTarget: File, pairs: Seq[(String, String)]): Unit = {
        val dest = ffiTraitLibraryIndexFile(projTarget)
        if (pairs.isEmpty) {
            if (dest.exists()) IO.delete(dest): Unit
        } else {
            IO.createDirectory(dest.getParentFile)
            val content = pairs.sortBy(_._1).map { case (fqcn, library) => s"$fqcn=$library" }.mkString("", "\n", "\n")
            if (!dest.exists() || IO.read(dest) != content) IO.write(dest, content)
        }
    }

    /** Read the persisted binding-trait -> library-id pairs; empty when no index has been written yet. */
    private def readTraitLibraryIndex(projTarget: File): Seq[(String, String)] = {
        val src = ffiTraitLibraryIndexFile(projTarget)
        if (!src.exists()) Nil
        else
            IO.read(src).linesIterator.flatMap { line =>
                val i = line.indexOf('=')
                if (i > 0) Some(line.substring(0, i) -> line.substring(i + 1)) else None
            }.toList
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

    /** Group the flat artifact list by library id based on the naming convention encoded in
      * `CCompiler.artifactName` (`lib<id>-<os>-<arch>.<ext>` on POSIX, `<id>-<os>-<arch>.<ext>` on
      * Windows). An artifact whose name parses is attributed to the id the NAME carries; one that
      * does not parse falls back to the prefix convention.
      *
      * Attribution is always by name, including when the project declares a single library: the old
      * one-library fast path handed every artifact to that id by position, which would mis-attribute
      * a foreign native merged in from `ffiPrebuiltDir` (it would be packaged under the wrong
      * canonical name and no runtime lookup would find it).
      */
    private[sbt] def groupArtifactsByLibrary(artifacts: Seq[File], libs: Seq[FfiLibrary]): Seq[(String, Seq[File])] =
        libs.map { lib =>
            val prefixPosix = s"lib${lib.id}-"
            val prefixWin   = s"${lib.id}-"
            val matched = artifacts.filter { f =>
                val n = f.getName
                CCompiler.parseArtifactName(n) match {
                    case Some((id, _, _)) => id == lib.id
                    case None             => n.startsWith(prefixPosix) || n.startsWith(prefixWin)
                }
            }
            lib.id -> matched
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
