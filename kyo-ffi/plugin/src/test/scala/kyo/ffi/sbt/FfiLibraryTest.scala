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

    test("different OS keys are honored independently") {
        val l = lib(byOs = Map("linux" -> Seq("uring"), "windows" -> Seq("ws2_32")))
        l.resolvedLinkLibs("linux") shouldBe Seq("uring")
        l.resolvedLinkLibs("windows") shouldBe Seq("ws2_32")
        l.resolvedLinkLibs("darwin") shouldBe empty
    }
}
