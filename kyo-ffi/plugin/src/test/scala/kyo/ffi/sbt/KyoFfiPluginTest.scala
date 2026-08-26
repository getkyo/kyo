package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
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

    // A vendored library's -l names carry no path, so the path test keeps them, and on their own they are worse than useless: without the
    // -L that finds the vendored tree, `-lssl -lcrypto` resolve against the consumer's SYSTEM OpenSSL under the vendored library's name.
    // That links, runs, and reports the wrong provider. They leave with the tree.
    test("partitionPortableFlags: a vendored library's link libs leave with its tree") {
        val flags = Seq(
            "-L/build/boringssl/staged/linux-x86_64/lib",
            "-Wl,--whole-archive",
            "-lssl",
            "-lcrypto",
            "-Wl,--no-whole-archive",
            "-lstdc++",
            "-Wl,-Bstatic",
            "-luring",
            "-Wl,-Bdynamic"
        )
        val (portable, dropped) = KyoFfiPlugin.partitionPortableFlags(flags, Set("ssl", "crypto"))
        portable shouldBe Seq("-Wl,--whole-archive", "-Wl,--no-whole-archive", "-lstdc++", "-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")
        dropped shouldBe Seq("-L/build/boringssl/staged/linux-x86_64/lib", "-lssl", "-lcrypto")
    }

    test("partitionPortableFlags: a system link lib with the same spelling as no vendored lib is kept") {
        KyoFfiPlugin.partitionPortableFlags(Seq("-lssl"), Set("crypto"))._1 shouldBe Seq("-lssl")
    }
}
