package kyo

class RandomSecureTest extends Test:

    "secure" - {

        // Leaf 1: nextBytes returns 32-byte sequence
        "nextBytes(32) returns a 32-byte sequence" in run {
            Random.secure.nextBytes(32).map { bytes =>
                assert(bytes.length == 32)
            }
        }

        // Leaf 2: Two consecutive calls produce distinct sequences
        "two consecutive nextBytes(32) calls produce distinct sequences" in run {
            for
                bytes1 <- Random.secure.nextBytes(32)
                bytes2 <- Random.secure.nextBytes(32)
            yield assert(bytes1 != bytes2)
        }

        // Leaf 3: Round-trip via unsafe/safe
        "Random.secure.unsafe.safe round-trip is sane" in run {
            Sync.defer {
                val unsafeInst = Random.secure.unsafe
                val safeInst   = unsafeInst.safe
                assert(safeInst.isInstanceOf[Random])
            }
        }

        // Leaf 4: Unsafe.secure, nextBytes returns full-entropy bytes (at least one non-zero in 32 bytes)
        "Random.Unsafe.secure, nextBytes fills with at least one non-zero byte" in run {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val bytes = Random.Unsafe.secure.nextBytes(32)
                assert(bytes.exists(_ != 0.toByte))
            }
        }

        // Leaf 5: Unsafe.secure.safe returns a Random
        "Random.Unsafe.secure.safe returns a Random" in run {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val safeInst = Random.Unsafe.secure.safe
                assert(safeInst.isInstanceOf[Random])
            }
        }

        // Leaf 6: Each call to Unsafe.secure returns a fresh instance
        "each call to Random.Unsafe.secure returns a fresh Random.Unsafe" in run {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val u1 = Random.Unsafe.secure
                val u2 = Random.Unsafe.secure
                assert(u1 ne u2)
            }
        }

        // Leaf 7: 1024 samples via Random.Unsafe.secure.nextBytes have byte distribution within 3 sigma of uniform
        "1024 samples have byte distribution within 3 sigma of uniform" in run {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val u       = Random.Unsafe.secure
                val buckets = Array.ofDim[Int](256)
                var i       = 0
                while i < 1024 do
                    val bytes = u.nextBytes(32)
                    var j     = 0
                    while j < bytes.length do
                        buckets((bytes(j) & 0xff).toInt) += 1
                        j += 1
                    i += 1
                end while
                // Chi-square test: 1024 * 32 = 32768 bytes total, 256 buckets
                // Expected per bucket: 32768 / 256 = 128
                // For 255 DOF, 3-sigma threshold ≈ 355 (p > 0.001)
                val expected = 32768.0 / 256
                val chiSquare = buckets.foldLeft(0.0) { (acc, obs) =>
                    val delta = obs - expected
                    acc + (delta * delta) / expected
                }
                assert(chiSquare < 355.0, s"Chi-square $chiSquare exceeds 3-sigma threshold (255 DOF)")
            }
        }

        // Leaf 8: Two distinct Random.secure instances produce independent streams
        "two distinct Random.secure instances produce independent streams" in run {
            for
                bytes1 <- Random.secure.nextBytes(64)
                bytes2 <- Random.secure.nextBytes(64)
            yield assert(bytes1 != bytes2, "64-byte sequences should be independent")
        }

        // Leaf 9: nextLong produces both positive and negative values
        "Random.secure.nextLong produces both positive and negative values across 100 samples" in run {
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val u       = Random.Unsafe.secure
                val samples = Array.fill(100)(u.nextLong())
                assert(samples.exists(_ > 0L), "Expected at least one positive Long")
                assert(samples.exists(_ < 0L), "Expected at least one negative Long")
            }
        }

        // Leaf 10: Random.secure under fiber forking via Async.gather returns distinct streams per fiber
        "Random.secure under fiber forking via Async.gather returns distinct streams per fiber" in run {
            val tasks: Seq[Seq[Byte] < Async] = Seq.fill(10)(Random.secure.nextBytes(32))
            Async.gather(tasks).map { results =>
                assert(
                    results.distinct.length == results.length,
                    "All fiber-forked sequences should be independent"
                )
            }
        }
    }

end RandomSecureTest
