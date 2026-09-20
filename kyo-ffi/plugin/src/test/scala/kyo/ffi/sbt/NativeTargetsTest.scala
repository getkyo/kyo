package kyo.ffi.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for reading a Scala Native target triple as one of the tags kyo packages by.
  *
  * A tag spelled differently resolves nothing, silently, so the mapping is worth pinning: musl in particular is a
  * separate pole, because a glibc library does not load under musl even on the same architecture.
  */
class NativeTargetsTest extends AnyFunSuite with Matchers {

    test("the host tag is one this plugin's packaging supports") {
        NativeTargets.supported should contain(NativeTargets.host)
    }

    test("an android triple is not a linux pole, though it names linux") {
        // bionic, not glibc: matching `linux` here would deliver glibc libraries into an Android binary, which is the
        // mistake musl would make and the one this whole tag scheme exists to prevent.
        NativeTargets.ofTriple("aarch64-linux-android") shouldBe None
        NativeTargets.ofTriple("aarch64-linux-android21") shouldBe None
        NativeTargets.ofTriple("x86_64-linux-android") shouldBe None
    }

    test("darwin triples") {
        NativeTargets.ofTriple("arm64-apple-darwin23.3.0") shouldBe Some("darwin-aarch64")
        NativeTargets.ofTriple("x86_64-apple-darwin23.3.0") shouldBe Some("darwin-x86_64")
    }

    test("linux triples, with musl a separate pole from glibc") {
        NativeTargets.ofTriple("x86_64-unknown-linux-gnu") shouldBe Some("linux-x86_64")
        NativeTargets.ofTriple("aarch64-unknown-linux-gnu") shouldBe Some("linux-aarch64")
        NativeTargets.ofTriple("x86_64-unknown-linux-musl") shouldBe Some("linux-musl-x86_64")
        NativeTargets.ofTriple("aarch64-unknown-linux-musl") shouldBe Some("linux-musl-aarch64")
    }

    test("windows triples") {
        NativeTargets.ofTriple("x86_64-pc-windows-msvc") shouldBe Some("windows-x86_64")
    }

    test("a triple for a platform kyo publishes no natives for yields nothing") {
        NativeTargets.ofTriple("x86_64-unknown-freebsd") shouldBe None
        NativeTargets.ofTriple("riscv64-unknown-linux-gnu") shouldBe None
    }

    test("osOf splits a tag, keeping musl attached to the os half") {
        NativeTargets.osOf("darwin-aarch64") shouldBe "darwin"
        NativeTargets.osOf("linux-x86_64") shouldBe "linux"
        NativeTargets.osOf("linux-musl-x86_64") shouldBe "linux-musl"
    }
}
