package kyo.ffi.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for `FfiLibrary.resolvedLinkLibs`, the OS-specific link-lib
  * resolution that lets a library link a system lib (e.g. `uring`) on one OS
  * without leaking the `-l` flag onto another OS's compile command. Exercised
  * without spinning up a scripted sbt invocation.
  */
class FfiLibraryTest extends AnyFunSuite with Matchers {

    private def lib(linkLibs: Seq[String] = Nil, byOs: Map[String, Seq[String]] = Map.empty): FfiLibrary =
        FfiLibrary(id = "demo", cSources = Nil, linkLibs = linkLibs, linkLibsByOs = byOs)

    test("no OS-specific libs: resolvedLinkLibs returns the always-on libs unchanged") {
        val l = lib(linkLibs = Seq("c", "m"))
        l.resolvedLinkLibs("linux") shouldBe Seq("c", "m")
        l.resolvedLinkLibs("darwin") shouldBe Seq("c", "m")
    }

    test("buildsOn is true on every OS when no osTargets are declared") {
        val l = lib()
        l.buildsOn("linux") shouldBe true
        l.buildsOn("darwin") shouldBe true
        l.buildsOn("windows") shouldBe true
    }

    test("buildsOn is true only for a declared osTarget") {
        // machine_macos is the case this exists for: its C is #ifdef-guarded to same-signature stubs off
        // macOS, so it compiles on a Linux release runner and the build shipped a Linux artifact for a
        // binding that can only ever be called on macOS, while shipping no macOS artifact at all.
        val l = FfiLibrary(id = "machine_macos", cSources = Nil, osTargets = Seq("darwin"))
        l.buildsOn("darwin") shouldBe true
        l.buildsOn("linux") shouldBe false
        l.buildsOn("windows") shouldBe false
    }

    // musl is a first-class OS everywhere else in the system (artifact tags, staging directories,
    // NativeLoader.Os, both manifests), so osTargets names it explicitly or excludes it. Folding it
    // onto the linux key would make every Seq("linux") claim a musl support nobody attested.
    test("buildsOn matches linux-musl exactly, not through the linux key") {
        val l = FfiLibrary(id = "demo", cSources = Nil, osTargets = Seq("linux"))
        l.buildsOn("linux") shouldBe true
        l.buildsOn("linux-musl") shouldBe false
        l.buildsOn("darwin") shouldBe false
    }

    test("buildsOn covers musl when osTargets names it") {
        val l = FfiLibrary(id = "demo", cSources = Nil, osTargets = Seq("linux", "linux-musl"))
        l.buildsOn("linux") shouldBe true
        l.buildsOn("linux-musl") shouldBe true
        l.buildsOn("darwin") shouldBe false
    }

    // linkLibsByOs and compilerByOs keep folding: a musl toolchain genuinely is "linux" for
    // link-flag and compiler-selection purposes, which is a different question from what the
    // release bundles.
    test("resolvedLinkLibs still folds linux-musl onto the linux key") {
        val l = lib(byOs = Map("linux" -> Seq("uring")))
        l.resolvedLinkLibs("linux-musl") shouldBe Seq("uring")
    }

    test("unknownOsTargets names entries that are not supported OS tags") {
        FfiLibrary(id = "demo", cSources = Nil, osTargets = Seq("macos")).unknownOsTargets shouldBe Seq("macos")
        FfiLibrary(id = "demo", cSources = Nil, osTargets = Seq("darwin")).unknownOsTargets shouldBe empty
        FfiLibrary(id = "demo", cSources = Nil).unknownOsTargets shouldBe empty
    }

    test("buildsOnTarget is true for every tag when nothing is declared") {
        val l = FfiLibrary(id = "demo", cSources = Nil)
        CCompiler.supportedOsArchTags.foreach(tag => l.buildsOnTarget(tag) shouldBe true)
    }

    // The case osArchTargets exists for: an engine published for one arch of an OS and not the other.
    // osTargets cannot express it, since excluding "windows" would drop the working x64 native too.
    test("osArchTargets excludes one arch of an OS while keeping the other") {
        val l = FfiLibrary(id = "kyo_doltlite", cSources = Nil, osArchTargets = Seq("windows-x86_64", "darwin-aarch64"))
        l.buildsOnTarget("windows-x86_64") shouldBe true
        l.buildsOnTarget("windows-aarch64") shouldBe false
        l.buildsOnTarget("darwin-aarch64") shouldBe true
        l.buildsOnTarget("linux-x86_64") shouldBe false
        // The OS-level predicate still answers for the OS, which is why both are consulted.
        l.buildsOn("windows") shouldBe true
    }

    test("osTargets and osArchTargets both narrow") {
        val l = FfiLibrary(
            id = "demo",
            cSources = Nil,
            osTargets = Seq("linux"),
            osArchTargets = Seq("linux-x86_64", "darwin-aarch64")
        )
        l.buildsOnTarget("linux-x86_64") shouldBe true
        l.buildsOnTarget("linux-aarch64") shouldBe false
        // Allowed by the arch list, excluded by the OS list. The plugin rejects this contradiction at
        // resolution time; the predicate itself answers false rather than picking a winner.
        l.buildsOnTarget("darwin-aarch64") shouldBe false
    }

    test("unknownOsArchTargets names entries that are not supported os-arch tags") {
        FfiLibrary(id = "demo", cSources = Nil, osArchTargets = Seq("win-x64")).unknownOsArchTargets shouldBe Seq(
            "win-x64"
        )
        FfiLibrary(id = "demo", cSources = Nil, osArchTargets = Seq("windows")).unknownOsArchTargets shouldBe Seq(
            "windows"
        )
        FfiLibrary(id = "demo", cSources = Nil, osArchTargets = Seq("windows-x86_64")).unknownOsArchTargets shouldBe empty
        FfiLibrary(id = "demo", cSources = Nil).unknownOsArchTargets shouldBe empty
    }

    test("OS-specific lib is appended only on the matching OS") {
        val l = lib(byOs = Map("linux" -> Seq("uring")))
        l.resolvedLinkLibs("linux") shouldBe Seq("uring")
        l.resolvedLinkLibs("darwin") shouldBe empty
        l.resolvedLinkLibs("windows") shouldBe empty
    }

    test("always-on and OS-specific libs merge, always-on first") {
        val l = lib(linkLibs = Seq("c"), byOs = Map("linux" -> Seq("uring")))
        l.resolvedLinkLibs("linux") shouldBe Seq("c", "uring")
        l.resolvedLinkLibs("darwin") shouldBe Seq("c")
    }

    test("the linux key also covers linux-musl") {
        val l = lib(byOs = Map("linux" -> Seq("uring")))
        l.resolvedLinkLibs("linux-musl") shouldBe Seq("uring")
    }

    test("distinct: a lib named in both always-on and OS-specific is emitted once") {
        val l = lib(linkLibs = Seq("uring"), byOs = Map("linux" -> Seq("uring")))
        l.resolvedLinkLibs("linux") shouldBe Seq("uring")
    }

    test("linkedDefine upper-cases the id and replaces every non-alphanumeric character") {
        FfiLibrary(id = "kyonet_posix_uring", cSources = Nil).linkedDefine shouldBe "KYO_FFI_LINKED_KYONET_POSIX_URING"
        FfiLibrary(id = "my-lib.v2", cSources = Nil).linkedDefine shouldBe "KYO_FFI_LINKED_MY_LIB_V2"
    }

    test("linkedDefineFlags follows the link libs resolved for the OS") {
        // The shim's real branch compiles exactly where its library reaches the link: uring on Linux, nowhere else.
        val l = FfiLibrary(id = "kyonet_posix_uring", cSources = Nil, linkLibsByOs = Map("linux" -> Seq("uring")))
        l.linkedDefineFlags("linux") shouldBe Seq("-DKYO_FFI_LINKED_KYONET_POSIX_URING")
        l.linkedDefineFlags("linux-musl") shouldBe Seq("-DKYO_FFI_LINKED_KYONET_POSIX_URING")
        l.linkedDefineFlags("darwin") shouldBe empty
        l.linkedDefineFlags("windows") shouldBe empty
    }

    test("a library that links nothing gets no define") {
        FfiLibrary(id = "kyonet_boringssl", cSources = Nil).linkedDefineFlags("linux") shouldBe empty
    }

    test("a library that links an archive by path gets the define on every OS") {
        // kyo_doltlite links its prebuilt engine as `<staged>/libdoltlite.a` in linkFlags, with no linkLibs at all.
        val l = FfiLibrary(id = "kyo_doltlite", cSources = Nil, linkFlags = Seq("/staged/libdoltlite.a"))
        l.linkedDefineFlags("linux") shouldBe Seq("-DKYO_FFI_LINKED_KYO_DOLTLITE")
        l.linkedDefineFlags("darwin") shouldBe Seq("-DKYO_FFI_LINKED_KYO_DOLTLITE")
    }

    test("different OS keys are honored independently") {
        val l = lib(byOs = Map("linux" -> Seq("uring"), "windows" -> Seq("ws2_32")))
        l.resolvedLinkLibs("linux") shouldBe Seq("uring")
        l.resolvedLinkLibs("windows") shouldBe Seq("ws2_32")
        l.resolvedLinkLibs("darwin") shouldBe empty
    }
}
