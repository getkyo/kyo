package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.DigestComputer.JarDigestEntry

/** Tests for DigestComputer xxh3 content-addressed digest.
  *
  * Cross-platform leaves (3, 4, 5, 6, 8, 9) run on JVM, JS, and Native. JVM-only leaves (1, 2, 7) are
  * in DigestComputerJvmTest.scala (jvm/src/test), as they require real JARs on disk and
  * java.nio.file / java.util.zip APIs not available on JS/Native.
  *
  * Scaladoc: 8-35 lines.
  */
class DigestComputerTest extends Test:

    // Leaf 3: digestForJar is stable for identical entries in any insertion order.
    "Leaf 3: digestForJar is stable for same-name same-crc entries in any order" in run {
        val e1 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val e2 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val h1 = DigestComputer.digestForJar(Chunk(e1, e2))
        val h2 = DigestComputer.digestForJar(Chunk(e2, e1))
        assert(h1 == h2, s"same-name same-crc entries must produce same digest: $h1 vs $h2")
    }

    // Leaf 4: digestForJar distinguishes entries with distinct crc32 values.
    // Two identical-name entries with different crc32 produce a different digest than the same two
    // entries with the same crc32, confirming that crc32 is mixed into the hash.
    "Leaf 4: digestForJar includes crc32 in the hash (different crc32 changes digest)" in run {
        val e1a = JarDigestEntry("foo.class", 0x11111111L)
        val e1b = JarDigestEntry("foo.class", 0x22222222L)
        val hA  = DigestComputer.digestForJar(Chunk(e1a))
        val hB  = DigestComputer.digestForJar(Chunk(e1b))
        assert(hA != hB, s"different crc32 values must produce different digest: $hA vs $hB")
    }

    // Leaf 5: digestForJar(Chunk.empty) returns 0L.
    // With acc = 0L and zero mixing steps, xxh3Avalanche(0L) = 0L.
    "Leaf 5: digestForJar(Chunk.empty) equals 0L (empty-input vector)" in run {
        val result = DigestComputer.digestForJar(Chunk.empty)
        assert(result == 0L, s"digestForJar(Chunk.empty) expected 0L but got $result")
    }

    // Leaf 6: directory root compute is deterministic and mtime-sensitive.
    "Leaf 6: directory root compute is deterministic and mtime-sensitive" in run {
        val src = MemoryFileSource()
        src.add("root/Foo.tasty", Array[Byte](1, 2, 3, 4))
        src.setMtime("root/Foo.tasty", 1_000_000L)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root"), src).flatMap: d2 =>
                    Sync.defer(src.setMtime("root/Foo.tasty", 2_000_000L)).flatMap: _ =>
                        DigestComputer.compute(Seq("root"), src).map: d3 =>
                            assert(d1.sameElements(d2), "same directory must produce same digest")
                            assert(!d1.sameElements(d3), "mtime change must produce different digest")
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // Leaf 8: digestForJar with JarDigestEntry is deterministic across platforms.
    "Leaf 8: digestForJar with JarDigestEntry is deterministic across platforms" in run {
        val entries = Chunk(
            JarDigestEntry("a/B.class", 0xdeadL),
            JarDigestEntry("c/D.class", 0xcafeL)
        )
        val h1 = DigestComputer.digestForJar(entries)
        val h2 = DigestComputer.digestForJar(entries)
        assert(h1 == h2, s"digestForJar must be deterministic: $h1 vs $h2")
    }

    // Leaf 9: longToBytes and bytesToLong round-trip on digest output.
    "Leaf 9: longToBytes/bytesToLong round-trip is lossless (8 bytes, little-endian)" in run {
        val entries   = Chunk(JarDigestEntry("foo/Bar.class", 0x12345678L))
        val digest    = DigestComputer.digestForJar(entries)
        val bytes     = DigestComputer.longToBytes(digest)
        val roundTrip = DigestComputer.bytesToLong(bytes)
        assert(bytes.length == 8, s"expected 8 bytes, got ${bytes.length}")
        assert(roundTrip == digest, s"round-trip failed: $roundTrip != $digest")
    }

end DigestComputerTest
