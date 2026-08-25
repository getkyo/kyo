package kyo

/** Cross-platform, because `SecureRandom` resolves a different entropy source on every platform: the JDK provider on the JVM, a character
  * device on Native, and a probed host global on JS and WebAssembly. Every leaf here asserts a property of the source rather than of one
  * implementation, so running them on all four platforms is what exercises each platform's `liveUnsafe`. The per-platform probing and
  * failure behavior lives in `SecureRandomPlatformSpecificTest` under `native` and `js-wasm`.
  */
class SecureRandomTest extends kyo.test.Test[Any]:

    "nextBytes" - {

        "returns a sequence of the requested length" in {
            SecureRandom.nextBytes(32).map { bytes =>
                assert(bytes.size == 32)
            }
        }

        "two consecutive calls produce distinct sequences" in {
            for
                bytes1 <- SecureRandom.nextBytes(32)
                bytes2 <- SecureRandom.nextBytes(32)
            yield assert(!bytes1.is(bytes2))
        }

        "fills with at least one non-zero byte" in {
            SecureRandom.nextBytes(32).map { bytes =>
                assert(bytes.exists(_ != 0.toByte), "32 zero bytes from a secure source is not entropy")
            }
        }

        "two distinct requests produce independent streams" in {
            for
                bytes1 <- SecureRandom.nextBytes(64)
                bytes2 <- SecureRandom.nextBytes(64)
            yield assert(!bytes1.is(bytes2), "64-byte sequences should be independent")
        }

        "under fiber forking via Async.gather returns distinct streams per fiber" in {
            val tasks: Seq[Span[Byte] < Async] = Seq.fill(10)(SecureRandom.nextBytes(32))
            Async.gather(tasks).map { results =>
                val seqs = results.map(_.toArray.toSeq)
                assert(seqs.distinct.length == seqs.length, "all fiber-forked sequences should be independent")
            }
        }
    }

    "live" - {

        "the live instance draws from the secure source" in {
            for
                bytes1 <- SecureRandom.live.nextBytes(32)
                bytes2 <- SecureRandom.live.nextBytes(32)
            yield
                assert(bytes1.size == 32)
                assert(bytes1.exists(_ != 0.toByte))
                assert(!bytes1.is(bytes2))
        }

        // `safe` is declared to return `SecureRandom`, so the assertion is about what the round-tripped generator PRODUCES, not its type.
        "unsafe/safe round-trip yields a working generator" in {
            for
                safeInst <- Sync.defer(SecureRandom.live.unsafe.safe)
                bytes1   <- safeInst.nextBytes(32)
                bytes2   <- safeInst.nextBytes(32)
            yield
                assert(bytes1.size == 32, "the round-tripped generator must fill the requested width")
                assert(bytes1.exists(_ != 0.toByte), "32 zero bytes from a secure generator is not entropy")
                assert(!bytes1.is(bytes2), "consecutive draws from the round-tripped generator must differ")
        }

        "unsafe fills with at least one non-zero byte" in {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val bytes = SecureRandom.live.unsafe.nextBytes(32)
                assert(bytes.exists(_ != 0.toByte))
            }
        }

        "1024 samples have byte distribution within 3 sigma of uniform" in {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val u       = SecureRandom.live.unsafe
                val buckets = Array.ofDim[Int](256)
                var i       = 0
                while i < 1024 do
                    val bytes = u.nextBytes(32)
                    var j     = 0
                    while j < bytes.size do
                        buckets((bytes(j) & 0xff).toInt) += 1
                        j += 1
                    i += 1
                end while
                // Chi-square test: 1024 * 32 = 32768 bytes total, 256 buckets, expected 128 per bucket.
                // For 255 DOF, 3-sigma threshold ~= 355 (p > 0.001).
                val expected = 32768.0 / 256
                val chiSquare = buckets.foldLeft(0.0) { (acc, obs) =>
                    val delta = obs - expected
                    acc + (delta * delta) / expected
                }
                assert(chiSquare < 355.0, s"Chi-square $chiSquare exceeds 3-sigma threshold (255 DOF)")
            }
        }
    }

    "let installs a deterministic instance" in {
        val fixed = SecureRandom(
            new SecureRandom.Unsafe:
                def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                    Span.from(Array.fill(length)(7.toByte))
        )
        SecureRandom.let(fixed)(SecureRandom.nextBytes(4)).map { bytes =>
            assert(bytes.toArray.toSeq == Seq.fill(4)(7.toByte))
        }
    }

    // A request past 65536, the largest the Web Crypto API accepts in one call. `nextBytes` takes a caller-supplied length, so a source with
    // a per-call ceiling has to fill a larger buffer in more than one pass, and the three ways that goes wrong are a rejected request, a
    // buffer whose tail is never written, and every pass writing the same window.
    "large requests" - {

        val largeLength = 70000
        val window      = 65536

        "a request past the per-call ceiling is satisfied to its full length" in {
            SecureRandom.nextBytes(largeLength).map { bytes =>
                val tail = bytes.slice(window, largeLength)
                assert(bytes.size == largeLength)
                assert(tail.size == largeLength - window)
                assert(tail.exists(_ != 0.toByte), "bytes past the per-call ceiling were never written")
            }
        }

        "a multi-pass fill does not write the same window twice" in {
            SecureRandom.nextBytes(largeLength).map { bytes =>
                val head = bytes.slice(0, largeLength - window)
                val tail = bytes.slice(window, largeLength)
                assert(head.size == tail.size)
                assert(!head.is(tail), "two passes of one fill produced identical bytes")
            }
        }

        "every 4096-byte region of a 70000-byte request carries a non-zero byte" in {
            SecureRandom.nextBytes(largeLength).map { bytes =>
                val emptyRegions = bytes.toArray.grouped(4096).zipWithIndex.collect {
                    case (region, index) if !region.exists(_ != 0.toByte) => index
                }.toSeq
                assert(emptyRegions == Seq.empty[Int], s"regions left unwritten at 4096-byte indices $emptyRegions")
            }
        }
    }

end SecureRandomTest
