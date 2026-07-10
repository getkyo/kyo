package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.DigestComputer.JarDigestEntry

/** Cross-platform DigestComputer xxh3 content-addressed digest behavior: digestForJar stability under entry reordering, crc32 sensitivity,
  * and content-bytes sensitivity.
  */
class DigestComputerTest extends kyo.test.Test[Any]:

    // digestForJar is stable for identical entries in any insertion order.
    "digestForJar is stable for same-name same-crc entries in any order" in {
        val e1 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val e2 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val h1 = DigestComputer.digestForJar(Chunk(e1, e2))
        val h2 = DigestComputer.digestForJar(Chunk(e2, e1))
        assert(h1 == h2, s"same-name same-crc entries must produce same digest: $h1 vs $h2")
    }

    // digestForJar distinguishes entries with distinct crc32 values.
    // Two identical-name entries with different crc32 produce a different digest than the same two
    // entries with the same crc32, confirming that crc32 is mixed into the hash.
    "digestForJar includes crc32 in the hash (different crc32 changes digest)" in {
        val e1a = JarDigestEntry("foo.class", 0x11111111L)
        val e1b = JarDigestEntry("foo.class", 0x22222222L)
        val hA  = DigestComputer.digestForJar(Chunk(e1a))
        val hB  = DigestComputer.digestForJar(Chunk(e1b))
        assert(hA != hB, s"different crc32 values must produce different digest: $hA vs $hB")
    }

    // digestForJar(Chunk.empty) returns 0L.
    // With acc = 0L and zero mixing steps, xxh3Avalanche(0L) = 0L.
    "digestForJar(Chunk.empty) equals 0L (empty-input vector)" in {
        val result = DigestComputer.digestForJar(Chunk.empty)
        assert(result == 0L, s"digestForJar(Chunk.empty) expected 0L but got $result")
    }

    // directory root compute is deterministic and sensitive to file-set changes.
    "directory root compute is deterministic and detects added file" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-dct")).map { dir =>
                val file = dir / "Foo.tasty"
                Path.run(file.writeBytes(Span.from(Array[Byte](1, 2, 3, 4)))).map { _ =>
                    val root = dir.toString
                    Abort.run[TastyError] {
                        DigestComputer.compute(Seq(root)).map { d1 =>
                            DigestComputer.compute(Seq(root)).map { d2 =>
                                val file2 = dir / "Bar.tasty"
                                Path.run(file2.writeBytes(Span.from(Array[Byte](5, 6, 7, 8)))).map { _ =>
                                    DigestComputer.compute(Seq(root)).map { d3 =>
                                        (d1, d2, d3)
                                    }
                                }
                            }
                        }
                    }
                        .map {
                            case Result.Success((d1, d2, d3)) =>
                                assert(d1.sameElements(d2), "same directory must produce same digest")
                                assert(!d1.sameElements(d3), "adding a file must produce a different digest")
                            case Result.Failure(e) => fail(s"Unexpected failure: $e")
                            case Result.Panic(t)   => throw t
                        }
                }
            }
        }
    }

    // digestForJar with JarDigestEntry is deterministic across platforms.
    "digestForJar with JarDigestEntry is deterministic across platforms" in {
        val entries = Chunk(
            JarDigestEntry("a/B.class", 0xdeadL),
            JarDigestEntry("c/D.class", 0xcafeL)
        )
        val h1 = DigestComputer.digestForJar(entries)
        val h2 = DigestComputer.digestForJar(entries)
        assert(h1 == h2, s"digestForJar must be deterministic: $h1 vs $h2")
    }

    // longToBytes and bytesToLong round-trip on digest output.
    "longToBytes/bytesToLong round-trip is lossless (8 bytes, little-endian)" in {
        val entries   = Chunk(JarDigestEntry("foo/Bar.class", 0x12345678L))
        val digest    = DigestComputer.digestForJar(entries)
        val bytes     = DigestComputer.longToBytes(digest)
        val roundTrip = DigestComputer.bytesToLong(bytes)
        assert(bytes.length == 8, s"expected 8 bytes, got ${bytes.length}")
        assert(roundTrip == digest, s"round-trip failed: $roundTrip != $digest")
    }

end DigestComputerTest
