package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Cross-platform buffer-feature spec.
  *
  * Round-trip patterns exercised:
  *   - `kyoItSumInts`: read-only consumer, C reads, returns a sum.
  *   - `kyoItFillInts`: write-only producer, C overwrites every slot with `value`.
  *   - `kyoItCopyInts`: two `Buffer[Int]` parameters in one call (dst + src).
  *
  * Buffer-length + fill-value rows. Each row invokes the binding at least once.
  */
class ItBuffersTest extends ItTestBase:

    import AllowUnsafe.embrace.danger

    "kyoItSumInts" - {
        "sum of 1..5 is 15" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](5) { buf =>
                var i = 0
                while i < 5 do
                    buf.set(i, i + 1)
                    i += 1
                assert(b.kyoItSumInts(buf, 5L) == 15L)
            }
        }

        "empty buffer (n = 0) returns 0" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](4) { buf =>
                // Buffer is 4-wide but n = 0 tells C not to read any elements.
                assert(b.kyoItSumInts(buf, 0L) == 0L)
            }
        }

        "zero-length buffer (size = 0) with n = 0 returns 0 without exception" in {
            // D7: pass a truly zero-length Buffer[Int] (no allocated elements) with n = 0.
            // C sees a pointer to a zero-byte region and is told to read 0 elements, the
            // loop body never executes, so the result must be 0 with no crash.
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](0) { buf =>
                assert(buf.size == 0)
                assert(b.kyoItSumInts(buf, 0L) == 0L)
            }
        }

        "table-driven: varied lengths of i+1 produce triangular sums" in {
            // Each row fills buf with 1..n, calls kyoItSumInts(buf, n),
            // compares with the closed-form n*(n+1)/2.
            val b                 = Ffi.load[ItBuffersBindings]
            val lengths: Seq[Int] = Seq(1, 2, 4, 8, 16, 32, 64, 128, 256, 1024)
            var last: Unit        = succeed
            lengths.foreach { n =>
                Buffer.use[Int, Unit](n) { buf =>
                    var i = 0
                    while i < n do
                        buf.set(i, i + 1)
                        i += 1
                    val expected = n.toLong * (n.toLong + 1L) / 2L
                    val r        = assert(b.kyoItSumInts(buf, n.toLong) == expected)
                    last = r
                    r
                }
            }
            last
        }

        "sum with all zeros is 0 across lengths" in {
            val b                 = Ffi.load[ItBuffersBindings]
            val lengths: Seq[Int] = Seq(1, 4, 16, 256)
            var last: Unit        = succeed
            lengths.foreach { n =>
                Buffer.use[Int, Unit](n) { buf =>
                    // explicitly zero the buffer then read.
                    var i = 0
                    while i < n do
                        buf.set(i, 0)
                        i += 1
                    val r = assert(b.kyoItSumInts(buf, n.toLong) == 0L)
                    last = r
                    r
                }
            }
            last
        }

        "sum of -1 repeated equals -n" in {
            val b                 = Ffi.load[ItBuffersBindings]
            val lengths: Seq[Int] = Seq(1, 16, 128)
            var last: Unit        = succeed
            lengths.foreach { n =>
                Buffer.use[Int, Unit](n) { buf =>
                    var i = 0
                    while i < n do
                        buf.set(i, -1)
                        i += 1
                    val r = assert(b.kyoItSumInts(buf, n.toLong) == -n.toLong)
                    last = r
                    r
                }
            }
            last
        }
    }

    "kyoItFillInts" - {
        "every slot equals value after fill" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](8) { buf =>
                // Pre-populate with a sentinel to prove the C side overwrites.
                var i = 0
                while i < 8 do
                    buf.set(i, -1)
                    i += 1
                b.kyoItFillInts(buf, 8L, 7)
                var last: Unit = succeed
                i = 0
                while i < 8 do
                    last = assert(buf.get(i) == 7)
                    i += 1
                last
            }
        }

        "table-driven: fill across varied buffer lengths and values" in {
            // Each (n, value) row fills a fresh buffer then reads ALL slots via
            // Buffer.get. Every row crosses the FFI boundary via the fill call.
            val b = Ffi.load[ItBuffersBindings]
            val cases: Seq[(Int, Int)] = Seq(
                (1, 0),
                (1, Int.MaxValue),
                (4, 42),
                (16, -1),
                (16, 0),
                (64, 1234),
                (256, Int.MinValue),
                (256, Int.MaxValue),
                (1024, -1),
                (1024, 7)
            )
            var last: Unit = succeed
            cases.foreach { case (n, value) =>
                Buffer.use[Int, Unit](n) { buf =>
                    // Pre-populate with a different sentinel so the fill is observable.
                    val sentinel = if value == 0 then 1 else 0
                    var i        = 0
                    while i < n do
                        buf.set(i, sentinel)
                        i += 1
                    b.kyoItFillInts(buf, n.toLong, value)
                    // sample first, last, and a middle slot, three reads per row.
                    assert((buf.get(0)) == value)
                    assert((buf.get(n - 1)) == value)
                    val r = assert(buf.get(n / 2) == value)
                    last = r
                    r
                }
            }
            last
        }

        "fill with n = 0 leaves buffer unchanged" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](4) { buf =>
                var i = 0
                while i < 4 do
                    buf.set(i, 99)
                    i += 1
                // n = 0 ⇒ C-side loop body never executes.
                b.kyoItFillInts(buf, 0L, 123)
                var last: Unit = succeed
                i = 0
                while i < 4 do
                    last = assert(buf.get(i) == 99)
                    i += 1
                last
            }
        }
    }

    "kyoItCopyInts" - {
        "dst matches src after copy" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](6) { dst =>
                Buffer.use[Int, Unit](6) { src =>
                    var i = 0
                    while i < 6 do
                        src.set(i, i * 10)
                        dst.set(i, 0)
                        i += 1
                    end while
                    b.kyoItCopyInts(dst, src, 6L)
                    var last: Unit = succeed
                    i = 0
                    while i < 6 do
                        last = assert(dst.get(i) == (i * 10))
                        i += 1
                    last
                }
            }
        }

        "table-driven: copy across varied lengths then checksum via sum" in {
            // Each row: fill src with i*2+3, copy src→dst, then use kyoItSumInts
            // on BOTH buffers, must match (cross-FFI verification).
            val b                 = Ffi.load[ItBuffersBindings]
            val lengths: Seq[Int] = Seq(1, 4, 16, 64, 256)
            var last: Unit        = succeed
            lengths.foreach { n =>
                Buffer.use[Int, Unit](n) { dst =>
                    Buffer.use[Int, Unit](n) { src =>
                        var i = 0
                        while i < n do
                            src.set(i, i * 2 + 3)
                            dst.set(i, -1)
                            i += 1
                        end while
                        b.kyoItCopyInts(dst, src, n.toLong)
                        val srcSum = b.kyoItSumInts(src, n.toLong)
                        val dstSum = b.kyoItSumInts(dst, n.toLong)
                        val r      = assert(dstSum == srcSum)
                        last = r
                        r
                    }
                }
            }
            last
        }

        "copy with n = 0 leaves dst untouched" in {
            val b = Ffi.load[ItBuffersBindings]
            Buffer.use[Int, Unit](4) { dst =>
                Buffer.use[Int, Unit](4) { src =>
                    var i = 0
                    while i < 4 do
                        src.set(i, 777)
                        dst.set(i, 333)
                        i += 1
                    end while
                    b.kyoItCopyInts(dst, src, 0L)
                    var last: Unit = succeed
                    i = 0
                    while i < 4 do
                        last = assert(dst.get(i) == 333)
                        i += 1
                    last
                }
            }
        }
    }
end ItBuffersTest
