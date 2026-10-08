package kyo.ffi.sbt

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Artifact attribution and prebuilt staging, the two pieces that decide WHICH library and WHICH
  * platform a native is packaged as.
  *
  * Both matter once a build can carry natives it did not compile: an artifact staged through
  * `ffiPrebuiltDir` is attributed by its filename, and packaged under the platform that filename
  * declares, never by list position or by the build host.
  */
class KyoFfiPluginTest extends AnyFunSuite with Matchers {

    private def lib(id: String, sources: Seq[File] = Seq(new File("/tmp/x.c"))): FfiLibrary =
        FfiLibrary(id = id, cSources = sources)

    private def dirWith(names: String*): File = {
        val dir = Files.createTempDirectory("kyo-ffi-prebuilt-").toFile
        names.foreach { n =>
            val f = new File(dir, n)
            f.getParentFile.mkdirs()
            Files.write(f.toPath, "native".getBytes)
        }
        dir
    }

    // -------------------------------------------------------------------------
    // groupArtifactsByLibrary
    // -------------------------------------------------------------------------

    test("groupArtifactsByLibrary: attributes each artifact to the library its name carries") {
        val artifacts = Seq(
            new File("/t/libalpha-linux-x86_64.so"),
            new File("/t/libbeta-linux-x86_64.so")
        )
        KyoFfiPlugin.groupArtifactsByLibrary(artifacts, Seq(lib("alpha"), lib("beta"))) shouldBe Seq(
            "alpha" -> Seq(artifacts(0)),
            "beta"  -> Seq(artifacts(1))
        )
    }

    test("groupArtifactsByLibrary: a single declared library still name-matches") {
        // The old one-library fast path handed EVERY artifact to the only declared id without
        // looking at names, so a foreign native merged in from ffiPrebuiltDir would be packaged
        // under the wrong canonical name (libalpha.so containing libbeta's code).
        val mine    = new File("/t/libalpha-linux-x86_64.so")
        val foreign = new File("/t/libbeta-darwin-aarch64.dylib")
        KyoFfiPlugin.groupArtifactsByLibrary(Seq(mine, foreign), Seq(lib("alpha"))) shouldBe Seq(
            "alpha" -> Seq(mine)
        )
    }

    test("groupArtifactsByLibrary: attribution is by id, not by prefix overlap") {
        // `liba-` is a prefix of `liba-b-...`; matching on the parsed id keeps them apart.
        val a  = new File("/t/liba-linux-x86_64.so")
        val ab = new File("/t/liba-b-linux-x86_64.so")
        KyoFfiPlugin.groupArtifactsByLibrary(Seq(a, ab), Seq(lib("a"), lib("a-b"))) shouldBe Seq(
            "a"   -> Seq(a),
            "a-b" -> Seq(ab)
        )
    }

    test("groupArtifactsByLibrary: a name outside the convention falls back to the prefix match") {
        val plain = new File("/t/libalpha-custom.so")
        KyoFfiPlugin.groupArtifactsByLibrary(Seq(plain), Seq(lib("alpha"))) shouldBe Seq("alpha" -> Seq(plain))
    }

    test("groupArtifactsByLibrary: a library with no matching artifact gets an empty list") {
        val only = new File("/t/libalpha-linux-x86_64.so")
        KyoFfiPlugin.groupArtifactsByLibrary(Seq(only), Seq(lib("alpha"), lib("beta"))) shouldBe Seq(
            "alpha" -> Seq(only),
            "beta"  -> Nil
        )
    }

    // -------------------------------------------------------------------------
    // prebuiltNatives
    // -------------------------------------------------------------------------

    test("prebuiltNatives: unset stages nothing") {
        KyoFfiPlugin.prebuiltNatives(None) shouldBe Nil
    }

    test("prebuiltNatives: each artifact carries the platform ITS OWN name declares") {
        val dir = dirWith(
            "libkyonet_posix_uring-darwin-x86_64.dylib",
            "libkyonet_posix_uring-linux-musl-aarch64.so",
            "kyonet_posix_uring-windows-x86_64.dll"
        )
        val staged = KyoFfiPlugin.prebuiltNatives(Some(dir)).map(p => (p.libraryId, p.os, p.arch)).sorted
        staged shouldBe Seq(
            ("kyonet_posix_uring", "darwin", "x86_64"),
            ("kyonet_posix_uring", "linux-musl", "aarch64"),
            ("kyonet_posix_uring", "windows", "x86_64")
        ).sorted
    }

    test("prebuiltNatives: finds artifacts in subdirectories (a downloaded per-leg artifact tree)") {
        val dir = dirWith("darwin-x86_64/libalpha-darwin-x86_64.dylib", "linux-aarch64/libalpha-linux-aarch64.so")
        KyoFfiPlugin.prebuiltNatives(Some(dir)).map(p => s"${p.os}-${p.arch}").sorted shouldBe
            Seq("darwin-x86_64", "linux-aarch64")
    }

    test("prebuiltNatives: hidden files are ignored") {
        val dir = dirWith("libalpha-linux-x86_64.so", ".DS_Store")
        KyoFfiPlugin.prebuiltNatives(Some(dir)) should have length 1
    }

    test("prebuiltNatives: a file outside the naming convention is a hard error, not a silent skip") {
        // Skipping it would publish a jar quietly missing the platform someone staged.
        val dir = dirWith("libalpha.so")
        val e   = intercept[RuntimeException](KyoFfiPlugin.prebuiltNatives(Some(dir)))
        e.getMessage should include("libalpha.so")
        e.getMessage should include("lib<id>-<os>-<arch>")
    }

    test("prebuiltNatives: a missing directory is a hard error") {
        val missing = new File("/nonexistent-path/kyo-ffi-prebuilt")
        intercept[RuntimeException](KyoFfiPlugin.prebuiltNatives(Some(missing)))
    }

    // -------------------------------------------------------------------------
    // prebuiltOverriddenCompiled (prebuilt-wins collision rule)
    // -------------------------------------------------------------------------

    private def prebuilt(name: String): KyoFfiPlugin.PrebuiltNative =
        CCompiler.parseArtifactName(name) match {
            case Some((id, os, arch)) => KyoFfiPlugin.PrebuiltNative(new File("/p/" + name), id, os, arch)
            case None                 => fail(s"test fixture '$name' is not a valid artifact name")
        }

    test("prebuiltOverriddenCompiled: a prebuilt for the same (id, os, arch) overrides the local artifact") {
        val compiled = Seq(
            new File("/t/libkyonet_posix_uring-linux-x86_64.so"),
            new File("/t/libkyonet_boringssl-linux-x86_64.so")
        )
        val staged = Seq(
            prebuilt("libkyonet_posix_uring-linux-x86_64.so"),
            prebuilt("libkyonet_boringssl-linux-x86_64.so")
        )
        KyoFfiPlugin.prebuiltOverriddenCompiled(compiled, staged) shouldBe compiled
    }

    test("prebuiltOverriddenCompiled: a prebuilt for a different os-arch overrides nothing") {
        val compiled = Seq(new File("/t/libkyonet_posix_uring-linux-x86_64.so"))
        val staged   = Seq(prebuilt("libkyonet_posix_uring-darwin-aarch64.dylib"))
        KyoFfiPlugin.prebuiltOverriddenCompiled(compiled, staged) shouldBe Nil
    }

    test("prebuiltOverriddenCompiled: a prebuilt for a different id at the same os-arch overrides nothing") {
        val compiled = Seq(new File("/t/libkyonet_posix_uring-linux-x86_64.so"))
        val staged   = Seq(prebuilt("libkyonet_boringssl-linux-x86_64.so"))
        KyoFfiPlugin.prebuiltOverriddenCompiled(compiled, staged) shouldBe Nil
    }

    test("prebuiltOverriddenCompiled: no prebuilts overrides nothing") {
        val compiled = Seq(new File("/t/libkyonet_posix_uring-linux-x86_64.so"))
        KyoFfiPlugin.prebuiltOverriddenCompiled(compiled, Nil) shouldBe Nil
    }

    test("prebuiltOverriddenCompiled: only the colliding local artifact is dropped, not its siblings") {
        val uring     = new File("/t/libkyonet_posix_uring-linux-x86_64.so")
        val boringssl = new File("/t/libkyonet_boringssl-linux-x86_64.so")
        val staged    = Seq(prebuilt("libkyonet_boringssl-linux-x86_64.so"))
        KyoFfiPlugin.prebuiltOverriddenCompiled(Seq(uring, boringssl), staged) shouldBe Seq(boringssl)
    }

    // The native-flag manifests are packaged, so they reach machines that have never seen the build host's
    // filesystem. A release shipped `-L/home/runner/work/kyo/kyo/.../boringssl/staged/linux-x86_64/lib` inside
    // its jar, naming a tree no consumer has and archives the artifact does not carry; the only flags that can
    // travel are the ones that name no file.
    test("nativeCompileOptions: carries each library's include dirs and preprocessor defines") {
        val shim = FfiLibrary(
            id = "shim",
            cSources = Seq(new File("/src/shim.c")),
            includeDirs = Seq(new File("/src"), new File("/staged")),
            cFlags = Seq("-DKYO_SQLITE_HEADER=\"doltlite.h\"", "-O2", "-UNDEBUG")
        )
        val other =
            FfiLibrary(id = "other", cSources = Seq(new File("/src/other.c")), includeDirs = Seq(new File("/src")), cFlags = Seq("/MD"))
        KyoFfiPlugin.nativeCompileOptions(Seq(shim, other)) shouldBe Seq(
            s"-I${new File("/src").getAbsolutePath}",
            s"-I${new File("/staged").getAbsolutePath}",
            "-DKYO_SQLITE_HEADER=\"doltlite.h\"",
            "-UNDEBUG"
        )
    }

    test("partitionPortableFlags: keeps flags that name no file") {
        val (portable, dropped) =
            KyoFfiPlugin.partitionPortableFlags(Seq("-luring", "-lc++", "-Wl,--whole-archive", "-Wl,--no-whole-archive"))
        portable shouldBe Seq("-luring", "-lc++", "-Wl,--whole-archive", "-Wl,--no-whole-archive")
        dropped shouldBe empty
    }

    test("partitionPortableFlags: drops a path wherever it sits in the flag") {
        val flags = Seq(
            "-L/home/runner/work/kyo/kyo/kyo-net/native/../build/boringssl/staged/linux-x86_64/lib",
            "-I/home/runner/work/kyo/kyo/kyo-net/native/../build/boringssl/staged/linux-x86_64/include",
            "-Wl,-force_load,/Users/dev/kyo/kyo-net/build/boringssl/staged/darwin-aarch64/lib/libssl.a",
            "-I../relative/include",
            "-lssl"
        )
        val (portable, dropped) = KyoFfiPlugin.partitionPortableFlags(flags)
        portable shouldBe Seq("-lssl")
        dropped should have size 4
    }

    test("partitionPortableFlags: an empty flag set stays empty on both sides") {
        KyoFfiPlugin.partitionPortableFlags(Nil) shouldBe ((Nil, Nil))
    }

    // -------------------------------------------------------------------------
    // readNativeFlagManifests
    // -------------------------------------------------------------------------

    private val packagedLinkDir = KyoFfiPlugin.ffiNativeLinkFlagsDir.mkString("/")
    private val inBuildLinkDir  = KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir.mkString("/")

    private def readLinkFlags(cp: Seq[File], targetOs: String): Seq[String] =
        KyoFfiPlugin.readNativeFlagManifests(cp, KyoFfiPlugin.ffiNativeLinkFlagsDir, KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir, targetOs)

    private def jarWith(entries: (String, Seq[String])*): File = {
        val jar = Files.createTempDirectory("kyo-ffi-manifest-jar-").resolve("dep.jar").toFile
        val out = new JarOutputStream(new FileOutputStream(jar))
        try {
            entries.foreach { case (path, lines) =>
                out.putNextEntry(new JarEntry(path))
                out.write(lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        } finally out.close()
        jar
    }

    private def classDirWith(entries: (String, Seq[String])*): File = {
        val dir = Files.createTempDirectory("kyo-ffi-manifest-dir-").toFile
        entries.foreach { case (path, lines) =>
            val f = new File(dir, path)
            f.getParentFile.mkdirs()
            Files.write(f.toPath, lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))
        }
        dir
    }

    private val uringWindow = Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")

    test("readNativeFlagManifests: a resolved dependency jar contributes its target OS's flags") {
        val jar = jarWith(
            s"$packagedLinkDir/m-linux.flags"  -> uringWindow,
            s"$packagedLinkDir/m-darwin.flags" -> Seq("-lc++")
        )
        readLinkFlags(Seq(jar), "linux") shouldBe uringWindow
        readLinkFlags(Seq(jar), "darwin") shouldBe Seq("-lc++")
    }

    // A global distinct keeps the first module's window and folds the second's bounds into it, so `-lb` lands after the first
    // `-Wl,-Bdynamic` and links dynamically.
    test("readNativeFlagManifests: two modules' static windows both survive, in classpath order") {
        val a = classDirWith(s"$packagedLinkDir/a-linux.flags" -> Seq("-Wl,-Bstatic", "-la", "-Wl,-Bdynamic"))
        val b = classDirWith(s"$packagedLinkDir/b-linux.flags" -> Seq("-Wl,-Bstatic", "-lb", "-Wl,-Bdynamic"))
        readLinkFlags(Seq(a, b), "linux") shouldBe
            Seq("-Wl,-Bstatic", "-la", "-Wl,-Bdynamic", "-Wl,-Bstatic", "-lb", "-Wl,-Bdynamic")
    }

    test("readNativeFlagManifests: a module reached twice on the classpath contributes its flags once") {
        val a    = classDirWith(s"$packagedLinkDir/a-linux.flags" -> Seq("-Wl,-Bstatic", "-la", "-Wl,-Bdynamic"))
        val aJar = jarWith(s"$packagedLinkDir/a-linux.flags" -> Seq("-Wl,-Bstatic", "-la", "-Wl,-Bdynamic"))
        val b    = classDirWith(s"$packagedLinkDir/b-linux.flags" -> Seq("-lb"))
        readLinkFlags(Seq(a, b, aJar), "linux") shouldBe Seq("-Wl,-Bstatic", "-la", "-Wl,-Bdynamic", "-lb")
    }

    test("readNativeFlagManifests: a build-local entry reads its in-build manifest over its packaged one") {
        val dir = classDirWith(
            s"$inBuildLinkDir/m-linux.flags"  -> Seq("-L/staged/lib", "-lssl"),
            s"$packagedLinkDir/m-linux.flags" -> Seq("-lstdc++")
        )
        readLinkFlags(Seq(dir), "linux") shouldBe Seq("-L/staged/lib", "-lssl")
    }

    test("readNativeFlagManifests: an empty in-build manifest still takes precedence over the packaged one") {
        val dir = classDirWith(
            s"$inBuildLinkDir/m-darwin.flags"          -> Nil,
            s"$packagedLinkDir/m-darwin.guarded-flags" -> Seq("?<openssl/ssl.h> -lssl -lcrypto"),
            s"$packagedLinkDir/m-darwin.flags"         -> Seq("-lm")
        )
        KyoFfiPlugin.readNativeFlagManifests(
            Seq(dir),
            KyoFfiPlugin.ffiNativeLinkFlagsDir,
            KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir,
            "darwin",
            _ => true
        ) shouldBe Nil
    }

    // -------------------------------------------------------------------------
    // guarded link flags
    // -------------------------------------------------------------------------

    private def readGuarded(cp: Seq[File], targetOs: String, visible: Set[String]): Seq[String] =
        KyoFfiPlugin.readNativeFlagManifests(
            cp,
            KyoFfiPlugin.ffiNativeLinkFlagsDir,
            KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir,
            targetOs,
            visible.contains
        )

    private val netJar = () =>
        jarWith(
            s"$packagedLinkDir/net-linux.flags"         -> Seq("-ldl"),
            s"$packagedLinkDir/net-linux.guarded-flags" -> Seq(
                "?<liburing.h> -Wl,-Bstatic -luring -Wl,-Bdynamic",
                "?<openssl/ssl.h> -lssl -lcrypto"
            )
        )

    test("guarded flags: a group is linked only when its header is visible, after the module's unconditional flags") {
        val jar = netJar()
        readGuarded(Seq(jar), "linux", Set("liburing.h", "openssl/ssl.h")) shouldBe
            Seq("-ldl", "-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic", "-lssl", "-lcrypto")
        readGuarded(Seq(jar), "linux", Set("openssl/ssl.h")) shouldBe Seq("-ldl", "-lssl", "-lcrypto")
        readGuarded(Seq(jar), "linux", Set.empty) shouldBe Seq("-ldl")
    }

    test("guarded flags: each header is probed once per read, however many modules guard on it") {
        val a      = classDirWith(s"$packagedLinkDir/a-linux.guarded-flags" -> Seq("?<openssl/ssl.h> -lssl"))
        val b      = classDirWith(s"$packagedLinkDir/b-linux.guarded-flags" -> Seq("?<openssl/ssl.h> -lcrypto"))
        val probed = scala.collection.mutable.ListBuffer.empty[String]
        KyoFfiPlugin.readNativeFlagManifests(
            Seq(a, b),
            KyoFfiPlugin.ffiNativeLinkFlagsDir,
            KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir,
            "linux",
            h => { probed += h; true }
        ) shouldBe Seq("-lssl", "-lcrypto")
        probed.toList shouldBe List("openssl/ssl.h")
    }

    test("guarded flags: a reader given no probe fails on a guarded line instead of guessing") {
        val err = intercept[RuntimeException](readLinkFlags(Seq(netJar()), "linux"))
        err.getMessage should include("<liburing.h>")
    }

    // A reader that predates guards lists `*-<os>.flags` and hands every line to clang as one flag, so the guarded lines must not live
    // in a file that listing matches.
    test("guarded flags: the file holding them is invisible to a reader that lists only *-<os>.flags") {
        val dir = classDirWith(
            s"$packagedLinkDir/net-linux.flags"         -> Seq("-ldl"),
            s"$packagedLinkDir/net-linux.guarded-flags" -> Seq("?<openssl/ssl.h> -lssl -lcrypto")
        )
        val packaged = KyoFfiPlugin.ffiNativeLinkFlagsDir.foldLeft(dir)(new File(_, _))
        sbt.IO.listFiles(packaged).map(_.getName).filter(_.matches(".*-linux\\.flags")).toList shouldBe List("net-linux.flags")
    }

    test("guarded flags: a malformed guarded line is an error naming the file") {
        val dir = classDirWith(s"$packagedLinkDir/m-linux.guarded-flags" -> Seq("-lssl"))
        val err = intercept[RuntimeException](readGuarded(Seq(dir), "linux", Set.empty))
        err.getMessage should include("m-linux.guarded-flags")
    }

    test("guarded flags: rendering and parsing round-trip") {
        val g = KyoFfiPlugin.GuardedFlags("openssl/ssl.h", Seq("-lssl", "-lcrypto"))
        KyoFfiPlugin.renderGuardedFlags(g) shouldBe "?<openssl/ssl.h> -lssl -lcrypto"
        KyoFfiPlugin.parseGuardedFlags(KyoFfiPlugin.renderGuardedFlags(g), "test") shouldBe g
    }

    test("validGuardHeader: a header as written inside #include <...>, nothing else") {
        KyoFfiPlugin.validGuardHeader("openssl/ssl.h") shouldBe true
        KyoFfiPlugin.validGuardHeader("liburing.h") shouldBe true
        KyoFfiPlugin.validGuardHeader("") shouldBe false
        KyoFfiPlugin.validGuardHeader("<openssl/ssl.h>") shouldBe false
        KyoFfiPlugin.validGuardHeader("\"ssl.h\"") shouldBe false
        KyoFfiPlugin.validGuardHeader("ssl .h") shouldBe false
    }

    // -------------------------------------------------------------------------
    // nativeHeaderProbe: runs the host's C compiler
    // -------------------------------------------------------------------------

    // The probe drives Scala Native's clang, which kyo's Windows runners neither use nor provide as `cc`.
    test("nativeHeaderProbe: answers what the compiler sees, including through the given -I") {
        assume(!sys.props.getOrElse("os.name", "").toLowerCase.contains("win"))
        val dir = Files.createTempDirectory("kyo-ffi-probe-inc-").toFile
        Files.write(new File(dir, "kyo_ffi_probe_only.h").toPath, "int kyo_ffi_probe_only;\n".getBytes(StandardCharsets.UTF_8))
        KyoFfiPlugin.nativeHeaderProbe("cc", Nil)("stdio.h") shouldBe true
        KyoFfiPlugin.nativeHeaderProbe("cc", Nil)("kyo_ffi_probe_only.h") shouldBe false
        KyoFfiPlugin.nativeHeaderProbe("cc", Seq(s"-I${dir.getAbsolutePath}"))("kyo_ffi_probe_only.h") shouldBe true
    }

    // -------------------------------------------------------------------------
    // packagedNativeLinkFlags: the manifest a published artifact carries per OS
    // -------------------------------------------------------------------------

    private val uring = FfiLibrary(
        id = "uring",
        cSources = Seq(new File("/tmp/uring.c")),
        linkLibsByOs = Map("linux" -> Seq("uring")),
        staticLink = true,
        linkLibsGuard = Some("liburing.h")
    )
    private val openssl = FfiLibrary(
        id = "openssl",
        cSources = Seq(new File("/tmp/openssl.c")),
        linkLibs = Seq("ssl", "crypto"),
        linkLibsGuard = Some("openssl/ssl.h")
    )
    private val vendored = FfiLibrary(
        id = "boringssl",
        cSources = Seq(new File("/tmp/boringssl.c")),
        libDirs = Seq(new File("/staged/lib")),
        linkLibs = Seq("ssl", "crypto"),
        linkFlags = Seq("-lstdc++"),
        staticLink = true
    )
    private val plain = FfiLibrary(id = "plain", cSources = Seq(new File("/tmp/plain.c")), linkLibs = Seq("m"))

    test("packagedNativeLinkFlags: answers for each OS from the declarations, whatever host packages it") {
        val libs = Seq(plain, uring, vendored, openssl)
        KyoFfiPlugin.packagedNativeLinkFlags(libs, "linux") shouldBe (
            Seq("-lm"),
            Seq(
                KyoFfiPlugin.GuardedFlags("liburing.h", Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")),
                KyoFfiPlugin.GuardedFlags("openssl/ssl.h", Seq("-lssl", "-lcrypto"))
            )
        )
        KyoFfiPlugin.packagedNativeLinkFlags(libs, "linux-musl") shouldBe KyoFfiPlugin.packagedNativeLinkFlags(libs, "linux")
        KyoFfiPlugin.packagedNativeLinkFlags(libs, "darwin") shouldBe (
            Seq("-lm"),
            Seq(KyoFfiPlugin.GuardedFlags("openssl/ssl.h", Seq("-lssl", "-lcrypto")))
        )
    }

    // A vendored library's -l names carry no path, and on their own they are worse than useless: without the -L that finds the vendored
    // tree, `-lssl -lcrypto` resolve against the consumer's SYSTEM OpenSSL under the vendored library's name, which links, runs, and
    // reports the wrong provider. The force-load window and the C++ runtime mean nothing without the archives either.
    test("packagedNativeLinkFlags: a vendored library contributes nothing, not even its C++ runtime") {
        KyoFfiPlugin.packagedNativeLinkFlags(Seq(vendored), "linux") shouldBe ((Nil, Nil))
        KyoFfiPlugin.packagedNativeLinkFlags(Seq(vendored), "darwin") shouldBe ((Nil, Nil))
        KyoFfiPlugin.packagedNativeLinkFlags(Seq(vendored, plain), "linux") shouldBe ((Seq("-lm"), Nil))
    }

    // A vendored library's define describes a compile against its vendored headers. kyo-net's staged BoringSSL passes one that turns any
    // other OpenSSL's headers into an #error, and a consumer, which has no vendored tree, would then fail to compile the shipped C.
    test("packagedNativeCompileFlags: a vendored library's defines stay in the build, a plain library's travel") {
        val requiring = vendored.copy(cFlags = Seq("-DKYO_NET_REQUIRE_BORINGSSL"), includeDirs = Seq(new File("/staged/include")))
        val feature   = plain.copy(cFlags = Seq("-DPLAIN_FEATURE", "-O2"))
        KyoFfiPlugin.packagedNativeCompileFlags(Seq(requiring)) shouldBe Nil
        KyoFfiPlugin.packagedNativeCompileFlags(Seq(requiring, feature)) shouldBe Seq("-DPLAIN_FEATURE")
    }

    test("nativeHostLinkingOptions: the host's own link ignores guards and force-loads vendored archives") {
        KyoFfiPlugin.nativeHostLinkingOptions(Seq(uring, vendored), "linux") shouldBe Seq(
            "-Wl,-Bstatic",
            "-luring",
            "-Wl,-Bdynamic",
            "-L/staged/lib",
            "-Wl,--whole-archive",
            "-lssl",
            "-lcrypto",
            "-Wl,--no-whole-archive",
            "-lstdc++"
        )
    }

    // -------------------------------------------------------------------------
    // appendMissingUnits: wiring the dependency flags twice
    // -------------------------------------------------------------------------

    test("appendMissingUnits: units already present are not appended again") {
        val units    = Seq(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic"), Seq("-lssl", "-lcrypto"))
        val explicit = Seq("-L/opt/lib") ++ units.flatten
        KyoFfiPlugin.appendMissingUnits(explicit, units) shouldBe explicit
        KyoFfiPlugin.appendMissingUnits(Seq("-L/opt/lib"), units) shouldBe explicit
        KyoFfiPlugin.appendMissingUnits(KyoFfiPlugin.appendMissingUnits(Nil, units), units) shouldBe units.flatten
    }

    test("appendMissingUnits: a unit whose flags are present but split apart is appended whole") {
        KyoFfiPlugin.appendMissingUnits(Seq("-lssl", "-lz", "-lcrypto"), Seq(Seq("-lssl", "-lcrypto"))) shouldBe
            Seq("-lssl", "-lz", "-lcrypto", "-lssl", "-lcrypto")
    }

    // -------------------------------------------------------------------------
    // codegenUnneeded: when ffiGenerate may skip the codegen
    // -------------------------------------------------------------------------

    private val noLibrary = FfiLibrary(id = "kyo_ffi", cSources = Nil)

    test("codegenUnneeded: no library and no kyo-ffi on the classpath skips the codegen") {
        KyoFfiPlugin.codegenUnneeded(Seq(noLibrary), Seq(classDirWith("other/Thing.class" -> Nil))) shouldBe true
    }

    test("codegenUnneeded: kyo-ffi on the classpath, as a directory or a jar, keeps the codegen") {
        KyoFfiPlugin.codegenUnneeded(Seq(noLibrary), Seq(classDirWith("kyo/ffi/Ffi.class" -> Nil))) shouldBe false
        KyoFfiPlugin.codegenUnneeded(Seq(noLibrary), Seq(jarWith("kyo/ffi/Ffi.class" -> Nil))) shouldBe false
    }

    test("codegenUnneeded: a declared library keeps the codegen") {
        KyoFfiPlugin.codegenUnneeded(Seq(plain), Nil) shouldBe false
        KyoFfiPlugin.codegenUnneeded(Seq(noLibrary.copy(linkLibs = Seq("m"))), Nil) shouldBe false
    }
}

/** The completeness half of `ffiPackagingCheck`: which (library, platform) pairs a release is still
  * owed. Requiredness comes from the declared libraries rather than from the staged tree, which is
  * what lets it report a native that is missing everywhere.
  */
class MissingRequiredNativesTest extends AnyFunSuite with Matchers {

    private def lib(id: String, osTargets: Seq[String] = Nil, osArchTargets: Seq[String] = Nil) =
        FfiLibrary(
            id = id,
            cSources = Seq(new File(s"$id.c")),
            osTargets = osTargets,
            osArchTargets = osArchTargets
        )

    private val allKeys = Seq("linux-x86_64", "darwin-aarch64", "windows-x86_64")

    test("a library with no native anywhere is reported for every required key") {
        // A tree-derived check cannot report this: with no artifact anywhere the library contributes
        // no observed id, so nothing is required of it and the release ships a jar with a hole.
        val missing = KyoFfiPlugin.missingRequiredNatives(Seq(lib("ghost")), allKeys, Set.empty)
        missing should contain theSameElementsAs Seq(
            "ghost has no native for linux-x86_64",
            "ghost has no native for darwin-aarch64",
            "ghost has no native for windows-x86_64"
        )
    }

    test("a complete library is reported for nothing") {
        val have = allKeys.map("full" -> _).toSet
        KyoFfiPlugin.missingRequiredNatives(Seq(lib("full")), allKeys, have) shouldBe empty
    }

    test("only the platform a library actually lacks is reported") {
        val have = Set("partial" -> "linux-x86_64", "partial" -> "darwin-aarch64")
        KyoFfiPlugin.missingRequiredNatives(Seq(lib("partial")), allKeys, have) shouldBe
            Seq("partial has no native for windows-x86_64")
    }

    test("osTargets narrows what a library is required for, per key") {
        // A darwin-only library owes nothing on linux or windows, even though its siblings do.
        val have = Set("mac_only" -> "darwin-aarch64")
        KyoFfiPlugin.missingRequiredNatives(Seq(lib("mac_only", Seq("darwin"))), allKeys, have) shouldBe empty
    }

    test("osArchTargets narrows within one OS, per key") {
        // kyo_doltlite's shape: an engine published for one arch of an OS and not the other. osTargets
        // could only have excluded windows entirely, taking the working x64 native with it.
        val declared = Seq(lib("engine", osArchTargets = Seq("linux-x86_64", "darwin-aarch64", "windows-x86_64")))
        val have     = Set("engine" -> "linux-x86_64", "engine" -> "darwin-aarch64", "engine" -> "windows-x86_64")
        val keys     = allKeys :+ "windows-aarch64"
        KyoFfiPlugin.missingRequiredNatives(declared, keys, have) shouldBe empty
    }

    test("a library is still reported for an arch its osArchTargets DOES name") {
        // The narrowing must not become a blanket excuse: the platforms it claims are still owed.
        val declared = Seq(lib("engine", osArchTargets = Seq("linux-x86_64", "windows-x86_64")))
        KyoFfiPlugin.missingRequiredNatives(declared, allKeys :+ "windows-aarch64", Set.empty) should
            contain theSameElementsAs Seq(
                "engine has no native for linux-x86_64",
                "engine has no native for windows-x86_64"
            )
    }

    test("a darwin-only library missing its own platform is still reported") {
        // The publish host is linux; buildsOn must be asked about each required key rather than
        // about the host, or this library is excused exactly when it is broken.
        KyoFfiPlugin.missingRequiredNatives(Seq(lib("mac_only", Seq("darwin"))), allKeys, Set.empty) shouldBe
            Seq("mac_only has no native for darwin-aarch64")
    }

    test("musl is required only when osTargets names it") {
        val keys = Seq("linux-x86_64", "linux-musl-x86_64")
        val have = Set("glibc_only" -> "linux-x86_64")
        KyoFfiPlugin.missingRequiredNatives(Seq(lib("glibc_only", Seq("linux"))), keys, have) shouldBe empty
        KyoFfiPlugin.missingRequiredNatives(
            Seq(lib("both", Seq("linux", "linux-musl"))),
            keys,
            Set("both" -> "linux-x86_64")
        ) shouldBe Seq("both has no native for linux-musl-x86_64")
    }

    test("a library declaring no C sources is required of nothing") {
        val declared = Seq(FfiLibrary(id = "absent_on_purpose", cSources = Nil))
        KyoFfiPlugin.missingRequiredNatives(declared, allKeys, Set.empty) shouldBe empty
    }

    test("every declared library is checked, not just the ones with artifacts") {
        val have    = allKeys.map("present" -> _).toSet
        val missing = KyoFfiPlugin.missingRequiredNatives(Seq(lib("present"), lib("ghost")), allKeys, have)
        missing.map(_.takeWhile(_ != ' ')).distinct shouldBe Seq("ghost")
        missing.size shouldBe 3
    }
}
