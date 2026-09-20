package kyo.natives.sbt

import kyo.ffi.sbt.NativeDelivery
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sbt._

/** Unit coverage for turning a classpath into the set of artifacts to fetch, and for pulling a library back out of
  * one. Both halves are file-shaped rather than pure, so each test builds a real jar: a test that agreed with the
  * code about a path neither had ever read from a jar would prove nothing.
  */
class DeliveryTest extends AnyFunSuite with Matchers {

    private def withJar(entries: Seq[(String, String)])(check: File => Unit): Unit = {
        IO.withTemporaryDirectory { dir =>
            val files = entries.map { case (path, content) =>
                val f = dir / "content" / path.replace('/', '_')
                IO.write(f, content)
                f -> path
            }
            val jar = dir / "artifact.jar"
            IO.zip(files, jar, None)
            check(jar)
        }
    }

    private def deliveryEntry(delivery: Map[String, NativeDelivery.Entry]): (String, String) =
        NativeDelivery.dir.mkString("/") + "/demo.properties" -> NativeDelivery.render(delivery).mkString("\n")

    test("a Native jar's declaration yields the JVM artifact of the same module and version") {
        withJar(Seq(deliveryEntry(Map("kyo_aeron" -> NativeDelivery.Entry("", NativeDelivery.allPlatforms))))) { jar =>
            val module   = "io.getkyo" % "kyo-aeron_native0.5_3" % "1.2.3"
            val requests = Delivery.requests(Seq(module -> jar), "darwin-aarch64", "native")
            requests.map(_.libId) shouldBe Seq("kyo_aeron")
            val carrier = requests.head.module
            carrier.organization shouldBe "io.getkyo"
            carrier.name shouldBe "kyo-aeron_3"
            carrier.revision shouldBe "1.2.3"
            carrier.explicitArtifacts.flatMap(_.classifier) shouldBe Vector.empty
            carrier.isTransitive shouldBe false
        }
    }

    test("a sliced module's declaration names the classifier for the target asked for") {
        val boringssl = Map("kyonet_boringssl" -> NativeDelivery.Entry("<os-arch>-boringssl", NativeDelivery.allPlatforms))
        withJar(Seq(deliveryEntry(boringssl))) { jar =>
            val module  = "io.getkyo" % "kyo-net_native0.5_3" % "1.2.3"
            val carrier = Delivery.requests(Seq(module -> jar), "linux-x86_64", "native").head.module
            carrier.name shouldBe "kyo-net_3"
            carrier.explicitArtifacts.flatMap(_.classifier) shouldBe Vector("linux-x86_64-boringssl")
        }
    }

    test("a library the declaration does not deliver to this platform is not requested") {
        // kyo-net's shape: the transport's C compiles into a Native binary already, the TLS shim's library does not.
        val delivery = Map(
            "kyonet_posix_uring" -> NativeDelivery.Entry("<os-arch>"),
            "kyonet_boringssl"   -> NativeDelivery.Entry("<os-arch>-boringssl", NativeDelivery.allPlatforms)
        )
        withJar(Seq(deliveryEntry(delivery))) { jar =>
            val module = "io.getkyo" % "kyo-net_native0.5_3" % "1.2.3"
            Delivery.requests(Seq(module -> jar), "linux-x86_64", "native").map(_.libId) shouldBe Seq("kyonet_boringssl")
            Delivery.requests(Seq(module -> jar), "linux-x86_64", "jvm").map(_.libId).sorted shouldBe
                Seq("kyonet_boringssl", "kyonet_posix_uring")
        }
    }

    test("a jar carrying no declaration asks for nothing") {
        withJar(Seq("kyo/Something.class" -> "irrelevant")) { jar =>
            Delivery.requests(Seq(("org" % "thing_3" % "1") -> jar), "darwin-aarch64", "jvm") shouldBe Nil
        }
    }

    test("a classpath entry that is a directory rather than a jar is skipped") {
        IO.withTemporaryDirectory { dir =>
            Delivery.requests(Seq(("org" % "thing_3" % "1") -> dir), "darwin-aarch64", "jvm") shouldBe Nil
        }
    }

    test("library file names follow each platform's convention") {
        Delivery.libraryFileName("kyo_aeron", "darwin") shouldBe "libkyo_aeron.dylib"
        Delivery.libraryFileName("kyo_aeron", "linux") shouldBe "libkyo_aeron.so"
        Delivery.libraryFileName("kyo_aeron", "linux-musl") shouldBe "libkyo_aeron.so"
        Delivery.libraryFileName("kyo_aeron", "windows") shouldBe "kyo_aeron.dll"
    }

    test("unpack writes the library flat, because -L names one directory") {
        val path = Delivery.entryPath("kyo_aeron", "linux-x86_64", "linux")
        path shouldBe "META-INF/native/linux-x86_64/libkyo_aeron.so"
        withJar(Seq(path -> "ELF-ish")) { jar =>
            IO.withTemporaryDirectory { out =>
                val unpacked = Delivery.unpack(jar, "kyo_aeron", "linux-x86_64", "linux", out)
                unpacked.map(_.getName) shouldBe Some("libkyo_aeron.so")
                unpacked.map(_.getParentFile) shouldBe Some(out)
                unpacked.map(IO.read(_)) shouldBe Some("ELF-ish")
            }
        }
    }

    test("unpack of a target the jar does not carry yields nothing rather than an empty file") {
        withJar(Seq(Delivery.entryPath("kyo_aeron", "linux-x86_64", "linux") -> "ELF-ish")) { jar =>
            IO.withTemporaryDirectory { out =>
                Delivery.unpack(jar, "kyo_aeron", "darwin-aarch64", "darwin", out) shouldBe None
                out.listFiles() shouldBe Array.empty[File]
            }
        }
    }
}
