package kyo.ffi.sbt

import java.io.File
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CCompilerTest extends AnyFunSuite with Matchers {

    // --- Compiler-family detection -------------------------------------------

    test("detectFamily: gcc") {
        CCompiler.detectFamily("cc") shouldBe CCompiler.Gcc
        CCompiler.detectFamily("gcc") shouldBe CCompiler.Gcc
        CCompiler.detectFamily("/usr/bin/gcc") shouldBe CCompiler.Gcc
        CCompiler.detectFamily("/usr/local/bin/gcc-14") shouldBe CCompiler.Gcc
    }

    test("detectFamily: clang") {
        CCompiler.detectFamily("clang") shouldBe CCompiler.Clang
        CCompiler.detectFamily("/usr/bin/clang") shouldBe CCompiler.Clang
        CCompiler.detectFamily("clang-18") shouldBe CCompiler.Clang
    }

    test("detectFamily: MSVC (cl.exe)") {
        CCompiler.detectFamily("cl.exe") shouldBe CCompiler.Msvc
        CCompiler.detectFamily("C:\\VS\\cl.exe") shouldBe CCompiler.Msvc
        CCompiler.detectFamily("C:/tools/bin/cl.exe") shouldBe CCompiler.Msvc
        CCompiler.detectFamily("cl") shouldBe CCompiler.Msvc
    }

    test("detectFamily: zig cc") {
        CCompiler.detectFamily("zig cc") shouldBe CCompiler.ZigCc
        CCompiler.detectFamily("/usr/bin/zig cc") shouldBe CCompiler.ZigCc
        CCompiler.detectFamily("zig") shouldBe CCompiler.ZigCc
    }

    // --- MSVC flag translation -----------------------------------------------

    test("translateFlagMsvc: -shared → /LD") {
        CCompiler.translateFlagMsvc("-shared") shouldBe Seq("/LD")
    }

    test("translateFlagMsvc: -fPIC drops") {
        CCompiler.translateFlagMsvc("-fPIC") shouldBe Nil
    }

    test("translateFlagMsvc: -O2 → /O2") {
        CCompiler.translateFlagMsvc("-O2") shouldBe Seq("/O2")
    }

    test("translateFlagMsvc: -Wall → /W3") {
        CCompiler.translateFlagMsvc("-Wall") shouldBe Seq("/W3")
    }

    test("translateFlagMsvc: -I<dir> → /I<dir>") {
        CCompiler.translateFlagMsvc("-I/path/to/headers") shouldBe Seq("/I/path/to/headers")
    }

    test("translateFlagMsvc: -l<name> → <name>.lib") {
        CCompiler.translateFlagMsvc("-lssl") shouldBe Seq("ssl.lib")
    }

    test("translateFlagMsvc: unknown flag passes through") {
        CCompiler.translateFlagMsvc("-DFOO=1") shouldBe Seq("-DFOO=1")
    }

    // --- buildCommand shape: gcc ---------------------------------------------

    test("buildCommand: gcc POSIX shape") {
        val src = new File("/tmp/foo.c")
        val out = new File("/tmp/libkyo_tcp-linux-x86_64.so")
        val inc = new File("/tmp/include")
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Seq("-O2", "-fPIC", "-Wall"),
            linkFlags = Seq("-pthread"),
            linkLibs = Seq("ssl", "crypto"),
            sources = Seq(src),
            includes = Seq(inc),
            outFile = out,
            staticLink = false
        )
        cmd.head shouldBe "gcc"
        cmd should contain("-shared")
        cmd should contain("-O2")
        cmd should contain("-fPIC")
        cmd should contain("-Wall")
        cmd.containsSlice(Seq("-I", inc.getAbsolutePath)) shouldBe true
        cmd should contain(src.getAbsolutePath)
        cmd.containsSlice(Seq("-o", out.getAbsolutePath)) shouldBe true
        cmd should contain("-lssl")
        cmd should contain("-lcrypto")
        cmd should contain("-pthread")
    }

    test("buildCommand: linkFlags (C++ runtime) come AFTER linkLibs so GNU ld resolves archive C++ symbols") {
        // Regression: a vendored C++ static archive (e.g. BoringSSL) needs its C++ runtime (-lstdc++ / -lc++)
        // AFTER the archive on the GNU ld command line; before, ld leaves the archive's C++ symbols undefined
        // and the loadable .so fails to dlopen. The runtime is passed via linkFlags; the archives via linkLibs.
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Nil,
            linkFlags = Seq("-lstdc++"),
            linkLibs = Seq("ssl", "crypto"),
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = true,
            libDirs = Seq(new File("/tmp/staged/lib")),
            os = "linux"
        )
        cmd.indexOf("-lstdc++") should be > cmd.indexOf("-lssl")
        cmd.indexOf("-lstdc++") should be > cmd.indexOf("-lcrypto")
    }

    test("buildCommand: clang same POSIX shape as gcc") {
        val src = new File("/tmp/foo.c")
        val out = new File("/tmp/libfoo.dylib")
        val cmd = CCompiler.buildCommand(
            cc = "clang",
            family = CCompiler.Clang,
            cFlags = Seq("-O2"),
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(src),
            includes = Nil,
            outFile = out,
            staticLink = false
        )
        cmd.head shouldBe "clang"
        cmd should contain("-shared")
        cmd should contain("-O2")
        cmd should contain(src.getAbsolutePath)
        cmd.containsSlice(Seq("-o", out.getAbsolutePath)) shouldBe true
    }

    test("buildCommand: zig cc uses gcc/clang-style flags but splits into 2 argv tokens") {
        val src = new File("/tmp/foo.c")
        val out = new File("/tmp/libfoo.so")
        val cmd = CCompiler.buildCommand(
            cc = "zig cc",
            family = CCompiler.ZigCc,
            cFlags = Seq("-O2", "-fPIC"),
            linkFlags = Nil,
            linkLibs = Seq("m"),
            sources = Seq(src),
            includes = Nil,
            outFile = out,
            staticLink = false
        )
        cmd.take(2) shouldBe Seq("zig", "cc")
        cmd should contain("-shared")
        cmd should contain("-fPIC")
        cmd should contain("-lm")
    }

    // --- buildCommand shape: MSVC --------------------------------------------

    test("buildCommand: MSVC cl.exe shape") {
        val src = new File("C:/tmp/foo.c")
        val out = new File("C:/tmp/kyo_tcp-windows-x86_64.dll")
        val inc = new File("C:/tmp/include")
        val cmd = CCompiler.buildCommand(
            cc = "cl.exe",
            family = CCompiler.Msvc,
            cFlags = Seq("-O2", "-fPIC", "-Wall"),
            linkFlags = Nil,
            linkLibs = Seq("ws2_32"),
            sources = Seq(src),
            includes = Seq(inc),
            outFile = out,
            staticLink = false
        )
        cmd.head shouldBe "cl.exe"
        cmd should contain("/LD")
        cmd should contain("/O2")
        cmd should contain("/W3")
        cmd should contain("/I" + inc.getAbsolutePath)
        cmd should contain("/Fe:" + out.getAbsolutePath)
        cmd should contain("ws2_32.lib")
        // -fPIC is dropped on Windows (PIC is default for DLLs):
        cmd.exists(_.toLowerCase.contains("fpic")) shouldBe false
    }

    // --- Static-link flag matrix --------------------------------------------

    test("staticLink: gcc folds named libs via -Wl,-Bstatic … -Wl,-Bdynamic (no bare -static)") {
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Seq("uring"),
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = true
        )
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")) shouldBe true
        cmd should not contain ("-static")
        cmd should not contain ("-static-libgcc")
        cmd should not contain ("-static-libstdc++")
    }

    test("staticLink: clang folds named libs via -Wl,-Bstatic … -Wl,-Bdynamic (no bare -static)") {
        val cmd = CCompiler.buildCommand(
            cc = "clang",
            family = CCompiler.Clang,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Seq("uring"),
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = true
        )
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")) shouldBe true
        cmd should not contain ("-static")
    }

    test("staticLink: zig folds named libs via -Wl,-Bstatic … -Wl,-Bdynamic (no bare -static)") {
        val cmd = CCompiler.buildCommand(
            cc = "zig cc",
            family = CCompiler.ZigCc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Seq("m"),
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = true
        )
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-lm", "-Wl,-Bdynamic")) shouldBe true
        cmd should not contain ("-static")
    }

    test("staticLink: no-op when linkLibs is empty (gcc), no -Wl,-Bstatic, no -static") {
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = true
        )
        cmd should not contain ("-Wl,-Bstatic")
        cmd should not contain ("-Wl,-Bdynamic")
        cmd should not contain ("-static")
    }

    test("staticLink: io_uring regression, gcc + linkLibs=uring produces a valid -shared link") {
        // Reproduces the real failure: `-static` + `-shared` pulled libc.a into the .so and
        // GNU ld failed on __fini_array_* / _dl_debug_state. The fix scopes static linking to
        // liburing only via the -Bstatic/-Bdynamic toggle so libc stays dynamic.
        val cmd = CCompiler.buildCommand(
            cc = "cc",
            family = CCompiler.Gcc,
            cFlags = Seq("-O2", "-fPIC"),
            linkFlags = Nil,
            linkLibs = Seq("uring"),
            sources = Seq(new File("/tmp/kyo_uring.c")),
            includes = Nil,
            outFile = new File("/tmp/libkyonet_posix_uring-linux-aarch64.so"),
            staticLink = true
        )
        cmd should contain("-shared")
        // liburing is folded statically; libc/everything-else stays dynamic:
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")) shouldBe true
        cmd should not contain ("-static")
        // -luring must appear inside the -Bstatic window, never as a bare dynamic -l:
        val bStatic = cmd.indexOf("-Wl,-Bstatic")
        val luring  = cmd.indexOf("-luring")
        val bDyn    = cmd.indexOf("-Wl,-Bdynamic")
        assert(bStatic >= 0 && luring > bStatic && bDyn > luring)
    }

    test("staticLink: MSVC adds /MT") {
        val cmd = CCompiler.buildCommand(
            cc = "cl.exe",
            family = CCompiler.Msvc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(new File("C:/tmp/foo.c")),
            includes = Nil,
            outFile = new File("C:/tmp/out.dll"),
            staticLink = true
        )
        cmd should contain("/MT")
    }

    test("staticLink: false produces no static flags (gcc)") {
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(new File("/tmp/foo.c")),
            includes = Nil,
            outFile = new File("/tmp/out.so"),
            staticLink = false
        )
        cmd should not contain ("-static")
        cmd should not contain ("-static-libgcc")
        cmd should not contain ("-static-libstdc++")
    }

    test("foldedLinkLibFlags: static folds via -Wl,-Bstatic … -Wl,-Bdynamic; non-static is plain; empty is Nil") {
        CCompiler.foldedLinkLibFlags(Seq("uring"), staticLink = true) shouldBe
            Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")
        CCompiler.foldedLinkLibFlags(Seq("uring"), staticLink = false) shouldBe
            Seq("-luring")
        CCompiler.foldedLinkLibFlags(Nil, staticLink = true) shouldBe Nil
    }

    // --- Vendored static-archive link flags (BoringSSL: libDirs + os-dependent toggle) --------

    test("vendoredArchiveLinkFlags: linux static emits -L<dir> + the -Bstatic fold") {
        val libDir = new File("/tmp/bssl/lib")
        val flags  = CCompiler.vendoredArchiveLinkFlags(Seq(libDir), Seq("ssl", "crypto"), staticLink = true, os = "linux")
        flags shouldBe Seq(
            s"-L${libDir.getAbsolutePath}",
            "-Wl,-Bstatic",
            "-lssl",
            "-lcrypto",
            "-Wl,-Bdynamic"
        )
    }

    test("vendoredArchiveLinkFlags: darwin static links each .a by full path (no -Bstatic)") {
        // ld64 has no -Bstatic; the staged archive is named by full path so the link is static.
        // The path is a HOST filesystem path (the linker runs on the host), so the expectation
        // is derived through File rather than a hardcoded separator style.
        val libDir = new File("/tmp/bssl/lib")
        val flags  = CCompiler.vendoredArchiveLinkFlags(Seq(libDir), Seq("ssl", "crypto"), staticLink = true, os = "darwin")
        // Archives are named by absolute path under libDir; no -L, no -Bstatic on darwin.
        flags should have size 2
        flags(0) shouldBe new File(libDir, "libssl.a").getAbsolutePath
        flags(1) shouldBe new File(libDir, "libcrypto.a").getAbsolutePath
        flags should not contain ("-Wl,-Bstatic")
        flags should not contain ("-Wl,-Bdynamic")
        flags.foreach(f => f should not startWith ("-L"))
    }

    test("vendoredArchiveLinkFlags: non-static emits plain -L<dir> + -l<name> on every OS") {
        val libDir = new File("/tmp/bssl/lib")
        CCompiler.vendoredArchiveLinkFlags(Seq(libDir), Seq("ssl"), staticLink = false, os = "darwin") shouldBe
            Seq(s"-L${libDir.getAbsolutePath}", "-lssl")
        CCompiler.vendoredArchiveLinkFlags(Seq(libDir), Seq("ssl"), staticLink = false, os = "linux") shouldBe
            Seq(s"-L${libDir.getAbsolutePath}", "-lssl")
    }

    test("vendoredArchiveLinkFlags: no libs is Nil regardless of dirs/os") {
        CCompiler.vendoredArchiveLinkFlags(Seq(new File("/tmp/lib")), Nil, staticLink = true, os = "linux") shouldBe Nil
        CCompiler.vendoredArchiveLinkFlags(Seq(new File("/tmp/lib")), Nil, staticLink = true, os = "darwin") shouldBe Nil
    }

    test("vendoredArchiveForceLoadFlags: linux static force-loads via -Wl,--whole-archive so the link is order-independent") {
        // Regression: Scala Native places the bundled C objects AFTER nativeConfig.linkingOptions, so a plain
        // -Wl,-Bstatic -lssl fold is searched before the object that references it and GNU ld drops every member
        // as unreferenced (undefined reference to SSL_CTX_new ...). --whole-archive forces all members in.
        val libDir = new File("/tmp/bssl/lib")
        val flags  = CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Seq("ssl", "crypto"), staticLink = true, os = "linux")
        flags shouldBe Seq(
            s"-L${libDir.getAbsolutePath}",
            "-Wl,--whole-archive",
            "-lssl",
            "-lcrypto",
            "-Wl,--no-whole-archive"
        )
    }

    test("vendoredArchiveForceLoadFlags: darwin static force-loads each .a by full path via -Wl,-force_load") {
        // ld64 has no --whole-archive; -force_load pulls every object out of the named archive regardless of position.
        val libDir = new File("/tmp/bssl/lib")
        val flags  = CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Seq("ssl", "crypto"), staticLink = true, os = "darwin")
        flags should have size 2
        flags(0) shouldBe s"-Wl,-force_load,${new File(libDir, "libssl.a").getAbsolutePath}"
        flags(1) shouldBe s"-Wl,-force_load,${new File(libDir, "libcrypto.a").getAbsolutePath}"
        flags should not contain ("-Wl,--whole-archive")
    }

    test("vendoredArchiveForceLoadFlags: non-static / no-libs falls back to the plain vendoredArchiveLinkFlags shape") {
        val libDir = new File("/tmp/bssl/lib")
        // Non-static: plain -L + -l, no force-load, on both OSes.
        CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Seq("ssl"), staticLink = false, os = "linux") shouldBe
            Seq(s"-L${libDir.getAbsolutePath}", "-lssl")
        CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Seq("ssl"), staticLink = false, os = "darwin") shouldBe
            Seq(s"-L${libDir.getAbsolutePath}", "-lssl")
        // No libs: Nil on both OSes.
        CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Nil, staticLink = true, os = "linux") shouldBe Nil
        CCompiler.vendoredArchiveForceLoadFlags(Seq(libDir), Nil, staticLink = true, os = "darwin") shouldBe Nil
    }

    test("buildCommand: libDirs + static on linux folds the vendored archives via -L + -Bstatic") {
        val src    = new File("/tmp/kyo_net_tls.c")
        val out    = new File("/tmp/libkyonet_boringssl-linux-aarch64.so")
        val incDir = new File("/tmp/bssl/include")
        val libDir = new File("/tmp/bssl/lib")
        val cmd = CCompiler.buildCommand(
            cc = "cc",
            family = CCompiler.Gcc,
            cFlags = Seq("-O2", "-fPIC"),
            linkFlags = Nil,
            linkLibs = Seq("ssl", "crypto"),
            sources = Seq(src),
            includes = Seq(incDir),
            outFile = out,
            staticLink = true,
            libDirs = Seq(libDir),
            os = "linux"
        )
        cmd should contain("-shared")
        cmd.containsSlice(Seq("-I", incDir.getAbsolutePath)) shouldBe true
        cmd should contain(s"-L${libDir.getAbsolutePath}")
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-lssl", "-lcrypto", "-Wl,-Bdynamic")) shouldBe true
        cmd should not contain ("-static")
    }

    test("buildCommand: libDirs + static on darwin links each .a by full path, no -Bstatic") {
        val src    = new File("/tmp/kyo_net_tls.c")
        val out    = new File("/tmp/libkyonet_boringssl-darwin-aarch64.dylib")
        val incDir = new File("/tmp/bssl/include")
        val libDir = new File("/tmp/bssl/lib")
        val cmd = CCompiler.buildCommand(
            cc = "clang",
            family = CCompiler.Clang,
            cFlags = Seq("-O2", "-fPIC"),
            linkFlags = Nil,
            linkLibs = Seq("ssl", "crypto"),
            sources = Seq(src),
            includes = Seq(incDir),
            outFile = out,
            staticLink = true,
            libDirs = Seq(libDir),
            os = "darwin"
        )
        cmd should contain("-shared")
        cmd.containsSlice(Seq("-I", incDir.getAbsolutePath)) shouldBe true
        cmd should contain(new File(libDir, "libssl.a").getAbsolutePath)
        cmd should contain(new File(libDir, "libcrypto.a").getAbsolutePath)
        cmd should not contain ("-Wl,-Bstatic")
        cmd should not contain ("-static")
    }

    test("buildCommand: empty libDirs keeps the original io_uring fold (unchanged)") {
        // Regression guard: the io_uring path declares no libDirs, so its link must stay
        // byte-for-byte the prior `foldedLinkLibFlags` shape.
        val cmd = CCompiler.buildCommand(
            cc = "cc",
            family = CCompiler.Gcc,
            cFlags = Seq("-O2", "-fPIC"),
            linkFlags = Nil,
            linkLibs = Seq("uring"),
            sources = Seq(new File("/tmp/kyo_uring.c")),
            includes = Nil,
            outFile = new File("/tmp/libkyonet_posix_uring-linux-aarch64.so"),
            staticLink = true,
            libDirs = Nil,
            os = "linux"
        )
        cmd.containsSlice(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")) shouldBe true
        cmd.exists(_.startsWith("-L")) shouldBe false
    }

    // --- Includes translation across families --------------------------------

    test("includes: multiple -I dirs (POSIX)") {
        val a = new File("/tmp/inc-a")
        val b = new File("/tmp/inc-b")
        val cmd = CCompiler.buildCommand(
            cc = "gcc",
            family = CCompiler.Gcc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(new File("/tmp/foo.c")),
            includes = Seq(a, b),
            outFile = new File("/tmp/out.so"),
            staticLink = false
        )
        cmd.containsSlice(Seq("-I", a.getAbsolutePath)) shouldBe true
        cmd.containsSlice(Seq("-I", b.getAbsolutePath)) shouldBe true
    }

    test("includes: multiple /I dirs (MSVC)") {
        val a = new File("C:/tmp/inc-a")
        val b = new File("C:/tmp/inc-b")
        val cmd = CCompiler.buildCommand(
            cc = "cl.exe",
            family = CCompiler.Msvc,
            cFlags = Nil,
            linkFlags = Nil,
            linkLibs = Nil,
            sources = Seq(new File("C:/tmp/foo.c")),
            includes = Seq(a, b),
            outFile = new File("C:/tmp/out.dll"),
            staticLink = false
        )
        cmd should contain("/I" + a.getAbsolutePath)
        cmd should contain("/I" + b.getAbsolutePath)
    }

    // --- splitCc -------------------------------------------------------------

    test("splitCc: single token") {
        CCompiler.splitCc("cc") shouldBe Seq("cc")
    }

    test("splitCc: two tokens (zig cc)") {
        CCompiler.splitCc("zig cc") shouldBe Seq("zig", "cc")
    }

    test("splitCc: trimmed whitespace") {
        CCompiler.splitCc("  clang  ") shouldBe Seq("clang")
    }

    // --- detectOs musl probing ------------------------------------------------
    //
    // Mirrors NativeLoader.detectOs (kyo-ffi/jvm runtime): Linux + presence of a musl loader
    // under /lib/ld-musl-<arch>.so.1 → "linux-musl"; absence → "linux".

    test("detectOs: plain linux (no musl loader)") {
        CCompiler.detectOsWith("Linux", _ => false) shouldBe "linux"
    }

    test("detectOs: linux-musl via x86_64 loader") {
        val present: String => Boolean = _ == "/lib/ld-musl-x86_64.so.1"
        CCompiler.detectOsWith("Linux", present) shouldBe "linux-musl"
    }

    test("detectOs: linux-musl via aarch64 loader") {
        val present: String => Boolean = _ == "/lib/ld-musl-aarch64.so.1"
        CCompiler.detectOsWith("Linux", present) shouldBe "linux-musl"
    }

    test("detectOs: macOS unaffected by musl probe") {
        CCompiler.detectOsWith("Mac OS X", _ => true) shouldBe "darwin"
    }

    test("detectOs: windows unaffected by musl probe") {
        CCompiler.detectOsWith("Windows 11", _ => true) shouldBe "windows"
    }
}
