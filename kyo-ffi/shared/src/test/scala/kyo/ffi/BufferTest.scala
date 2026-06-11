package kyo.ffi

import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked
import kyo.internal.UnsafeLayout

/** Cross-platform tests for the [[Buffer]] implementation.
  *
  * Covers allocation (shared + confined), primitive reads/writes, bounds checking, close idempotency, on-heap conversions, UTF-8 encoding,
  * and [[UnsafeLayout]] byte-size agreement with the public [[Buffer.byteSize]] projection.
  *
  * Runs identically on JVM, Native and JS. Platform-specific behaviour (JVM cross-thread `Arena.ofConfined` access) lives in a separate
  * JVM-only spec.
  */
class BufferTest extends Test:

    "Buffer.alloc[Byte]" - {
        "allocates and reads/writes" in {
            val b = Buffer.alloc[Byte](4)
            try
                b.set(0, 42)
                b.set(3, 99)
                assert(b.get(0) == (42: Byte))
                assert(b.get(3) == (99: Byte))
                assert(b.size == 4)
                assert(b.byteSize == 4L)
            finally b.close()
            end try
        }
    }

    "Buffer.alloc[Int]" - {
        "allocates and reads/writes" in {
            val b = Buffer.alloc[Int](8)
            try
                b.set(0, 1234567)
                b.set(7, -1)
                assert(b.get(0) == 1234567)
                assert(b.get(7) == -1)
                assert(b.size == 8)
                assert(b.byteSize == 32L)
            finally b.close()
            end try
        }
    }

    "Buffer.alloc[Long]" - {
        "allocates and reads/writes" in {
            val b = Buffer.alloc[Long](2)
            try
                b.set(0, Long.MaxValue)
                b.set(1, Long.MinValue)
                assert(b.get(0) == Long.MaxValue)
                assert(b.get(1) == Long.MinValue)
                assert(b.byteSize == 16L)
            finally b.close()
            end try
        }
    }

    "Buffer.alloc[Short]" - {
        "reads/writes" in {
            val b = Buffer.alloc[Short](3)
            try
                b.set(0, 1.toShort)
                b.set(1, (-1).toShort)
                b.set(2, Short.MaxValue)
                assert(b.get(0) == (1: Short))
                assert(b.get(1) == (-1: Short))
                assert(b.get(2) == Short.MaxValue)
                assert(b.byteSize == 6L)
            finally b.close()
            end try
        }
    }

    "Buffer.alloc[Float]" - {
        "reads/writes" in {
            val b = Buffer.alloc[Float](2)
            try
                b.set(0, 3.14f)
                b.set(1, -2.71f)
                assert(b.get(0) == 3.14f)
                assert(b.get(1) == -2.71f)
                assert(b.byteSize == 8L)
            finally b.close()
            end try
        }
    }

    "Buffer.alloc[Double]" - {
        "reads/writes" in {
            val b = Buffer.alloc[Double](2)
            try
                b.set(0, 1.0e100)
                b.set(1, -0.0)
                assert(b.get(0) == 1.0e100)
                assert(b.get(1) == -0.0)
                assert(b.byteSize == 16L)
            finally b.close()
            end try
        }
    }

    "Buffer.use" - {
        "closes on normal exit" in {
            val captured = Buffer.use[Int, Buffer[Int]](10) { b =>
                b.set(0, 100)
                b
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }

        "closes on exception" in {
            var captured: Buffer[Int] = null
            interceptThrown[RuntimeException] {
                Buffer.use[Int, Unit](10) { b =>
                    captured = b
                    throw new RuntimeException("boom")
                }
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }

        "returns computed value" in {
            val sum = Buffer.use[Int, Int](4) { b =>
                (0 until 4).foreach(i => b.set(i, i + 1))
                (0 until 4).map(b.get).sum
            }
            assert(sum == 10)
        }
    }

    "bounds checking" - {
        "get out of range throws" in {
            Buffer.use[Int, Unit](4) { b =>
                interceptThrown[IndexOutOfBoundsException](b.get(-1))
                interceptThrown[IndexOutOfBoundsException](b.get(4))
                interceptThrown[IndexOutOfBoundsException](b.get(Int.MaxValue))
            }
        }
        "set out of range throws" in {
            Buffer.use[Int, Unit](4) { b =>
                interceptThrown[IndexOutOfBoundsException](b.set(-1, 0))
                interceptThrown[IndexOutOfBoundsException](b.set(4, 0))
                interceptThrown[IndexOutOfBoundsException](b.set(Int.MinValue, 0))
            }
        }
        "size-0 buffer rejects all indices" in {
            Buffer.use[Byte, Unit](0) { b =>
                assert(b.size == 0)
                assert(b.byteSize == 0L)
                interceptThrown[IndexOutOfBoundsException](b.get(0))
                interceptThrown[IndexOutOfBoundsException](b.set(0, 0: Byte))
            }
        }
    }

    "close" - {
        "is idempotent" in {
            val b = Buffer.alloc[Int](4)
            b.close()
            b.close()
            succeed
        }

        "access after close throws" in {
            val b = Buffer.alloc[Int](4)
            b.set(0, 7)
            b.close()
            interceptThrown[IllegalStateException](b.get(0))
            interceptThrown[IllegalStateException](b.set(0, 1))
        }
    }

    "Buffer.fromArray" - {
        "copies in" in {
            val arr = Array[Int](1, 2, 3, 4, 5)
            val b   = Buffer.fromArray(arr)
            try
                (0 until 5).foreach(i => assert(b.get(i) == arr(i)))
                assert(b.size == 5)
            finally b.close()
            end try
        }

        "empty array yields empty buffer" in {
            val b = Buffer.fromArray(Array.empty[Byte])
            try
                assert(b.size == 0)
                assert(b.byteSize == 0L)
            finally b.close()
            end try
        }

        "mutating the source Array does not change the buffer" in {
            val arr = Array[Int](10, 20, 30)
            val b   = Buffer.fromArray(arr)
            try
                arr(0) = 999
                assert(b.get(0) == 10)
            finally b.close()
            end try
        }
    }

    "Buffer.copyToArray" - {
        "copies out" in {
            Buffer.use[Int, Unit](5) { b =>
                (0 until 5).foreach(i => b.set(i, i * 10))
                val out = Buffer.copyToArray(b, 1, 3)
                assert(out.toSeq == Seq(10, 20, 30))
            }
        }

        "copy entire range" in {
            Buffer.use[Byte, Unit](4) { b =>
                (0 until 4).foreach(i => b.set(i, i.toByte))
                val out = Buffer.copyToArray(b, 0, 4)
                assert(out.toSeq == Seq(0: Byte, 1: Byte, 2: Byte, 3: Byte))
            }
        }

        "zero-length copy yields empty array" in {
            Buffer.use[Int, Unit](4) { b =>
                val out = Buffer.copyToArray(b, 0, 0)
                assert(out.length == 0)
            }
        }
    }

    "Buffer.fromUtf8" - {
        "encodes + null-terminates ASCII" in {
            val b = Buffer.fromUtf8("hi")
            try
                assert(b.size == 3)
                assert(b.get(0) == 'h'.toByte)
                assert(b.get(1) == 'i'.toByte)
                assert(b.get(2) == (0: Byte))
            finally b.close()
            end try
        }

        "encodes multi-byte UTF-8" in {
            val b = Buffer.fromUtf8("é")
            try
                assert(b.size == 3)
                assert(b.get(0) == 0xc3.toByte)
                assert(b.get(1) == 0xa9.toByte)
                assert(b.get(2) == (0: Byte))
            finally b.close()
            end try
        }

        "empty string is one null byte" in {
            val b = Buffer.fromUtf8("")
            try
                assert(b.size == 1)
                assert(b.get(0) == (0: Byte))
            finally b.close()
            end try
        }
    }

    "Buffer.confinedUse" - {
        "works single-thread" in {
            Buffer.confinedUse[Int, Unit](4) { b =>
                b.set(0, 77)
                b.set(3, 88)
                assert(b.get(0) == 77)
                assert(b.get(3) == 88)
                assert(b.size == 4)
            }
        }

        "closes on normal exit" in {
            val captured = Buffer.confinedUse[Int, Buffer[Int]](4) { b =>
                b.set(0, 7)
                b
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }

        "closes on exception" in {
            var captured: Buffer[Int] = null
            interceptThrown[RuntimeException] {
                Buffer.confinedUse[Int, Unit](4) { b =>
                    captured = b
                    throw new RuntimeException("boom")
                }
            }
            interceptThrown[IllegalStateException](captured.get(0))
        }
    }

    "byteSize" - {
        "equals size * UnsafeLayout[A].size" in {
            Buffer.use[Byte, Unit](17) { b => assert(b.byteSize == 17L) }
            Buffer.use[Short, Unit](17) { b => assert(b.byteSize == 34L) }
            Buffer.use[Int, Unit](17) { b => assert(b.byteSize == 68L) }
            Buffer.use[Long, Unit](17) { b => assert(b.byteSize == 136L) }
            Buffer.use[Float, Unit](17) { b => assert(b.byteSize == 68L) }
            Buffer.use[Double, Unit](17) { b => assert(b.byteSize == 136L) }
        }
    }

    "UnsafeLayout givens" - {
        "size matches primitive sizes" in {
            assert(summon[UnsafeLayout[Byte]].size == 1)
            assert(summon[UnsafeLayout[Short]].size == 2)
            assert(summon[UnsafeLayout[Int]].size == 4)
            assert(summon[UnsafeLayout[Long]].size == 8)
            assert(summon[UnsafeLayout[Float]].size == 4)
            assert(summon[UnsafeLayout[Double]].size == 8)
        }
    }

    "raw" - {
        "is non-null for live buffer" in {
            Buffer.use[Int, Unit](4) { b =>
                val r: AnyRef = kyo.ffi.Buffer.Raw.unwrap(b.raw)
                assert(r != null)
            }
        }
    }

    "Buffer.Unsafe.wrapBorrowed" - {
        // wrapBorrowed mirrors the view the emitted FFI code uses to expose a pointer/field returned by C as a Scala-visible Buffer.
        // Ownership stays with the underlying source buffer here (stand-in for C-owned memory); close() on the borrowed wrapper must be
        // a no-op so the underlying source survives the wrapper's lifetime.

        "wraps another buffer's raw handle and reads the same values" in {
            val owner = Buffer.alloc[Int](4)
            try
                owner.set(0, 111)
                owner.set(1, 222)
                owner.set(2, 333)
                owner.set(3, 444)
                val borrowed = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(owner.raw), 4)
                assert(borrowed.size == 4)
                assert(borrowed.get(0) == 111)
                assert(borrowed.get(1) == 222)
                assert(borrowed.get(2) == 333)
                assert(borrowed.get(3) == 444)
            finally owner.close()
            end try
        }

        "close() is a no-op: the underlying memory stays live and can still be read" in {
            val owner = Buffer.alloc[Int](3)
            try
                owner.set(0, 10)
                owner.set(1, 20)
                owner.set(2, 30)
                val borrowed = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(owner.raw), 3)
                borrowed.close()
                // After close, reads still succeed -- the borrowed wrapper did not free the underlying memory.
                assert(borrowed.get(0) == 10)
                assert(borrowed.get(2) == 30)
            finally owner.close()
            end try
        }

        "close() is idempotent -- repeated calls do not throw" in {
            val owner = Buffer.alloc[Int](1)
            try
                owner.set(0, 7)
                val borrowed = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(owner.raw), 1)
                borrowed.close()
                borrowed.close()
                borrowed.close()
                assert(borrowed.get(0) == 7)
            finally owner.close()
            end try
        }

        "writes through the borrowed wrapper are visible on the underlying buffer" in {
            val owner = Buffer.alloc[Int](2)
            try
                val borrowed = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(owner.raw), 2)
                borrowed.set(0, 1234)
                borrowed.set(1, -1)
                assert(owner.get(0) == 1234)
                assert(owner.get(1) == -1)
            finally owner.close()
            end try
        }

        "bounds checking respects the `size` passed in, independent of the underlying region" in {
            val owner = Buffer.alloc[Int](10)
            try
                // Ask for size 3 -- the wrapper bounds-checks against 3, regardless of the owner's 10-element capacity.
                val borrowed = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(owner.raw), 3)
                assert(borrowed.size == 3)
                interceptThrown[IndexOutOfBoundsException](borrowed.get(3))
                interceptThrown[IndexOutOfBoundsException](borrowed.set(5, 1))
            finally owner.close()
            end try
        }

        "byteSize reflects size * UnsafeLayout[A].size" in {
            val owner = Buffer.alloc[Long](4)
            try
                val borrowed = Buffer.Unsafe.wrapBorrowed[Long](Buffer.Raw.unwrap(owner.raw), 4)
                assert(borrowed.byteSize == 32L)
            finally owner.close()
            end try
        }
    }

    "Buffer.Unsafe.wrapBorrowedChecked" - {
        // Opt-in checked-borrow mode. The returned Buffer carries a reference to a BorrowOwner;
        // on every get/set the Buffer verifies owner.isValid and throws BorrowRevoked otherwise.
        // The unchecked path (wrapBorrowed) is unaffected; default runtime behavior is unchanged.

        "access before revoke succeeds" in {
            val source = Buffer.alloc[Int](3)
            try
                source.set(0, 10)
                source.set(1, 20)
                source.set(2, 30)
                val borrowOwner = new BorrowOwner("test-owner")
                val borrowed    = Buffer.Unsafe.wrapBorrowedChecked[Int](Buffer.Raw.unwrap(source.raw), 3, borrowOwner)
                assert(borrowed.get(0) == 10)
                assert(borrowed.get(1) == 20)
                assert(borrowed.get(2) == 30)
                assert(borrowOwner.isValid == true)
            finally source.close()
            end try
        }

        "after revoke, get throws BorrowRevoked with owner label in message" in {
            val source = Buffer.alloc[Int](2)
            try
                source.set(0, 42)
                val borrowOwner = new BorrowOwner("checked-owner-abc")
                val borrowed    = Buffer.Unsafe.wrapBorrowedChecked[Int](Buffer.Raw.unwrap(source.raw), 2, borrowOwner)
                assert(borrowed.get(0) == 42)
                borrowOwner.revoke()
                assert(borrowOwner.isValid == false)
                val thrown = intercept[BorrowRevoked](borrowed.get(0))
                assert(thrown.getMessage.contains("checked-owner-abc"))
            finally source.close()
            end try
        }

        "after revoke, set throws BorrowRevoked" in {
            val source = Buffer.alloc[Int](2)
            try
                val borrowOwner = new BorrowOwner("set-label")
                val borrowed    = Buffer.Unsafe.wrapBorrowedChecked[Int](Buffer.Raw.unwrap(source.raw), 2, borrowOwner)
                borrowed.set(0, 1)
                borrowOwner.revoke()
                val thrown = intercept[BorrowRevoked](borrowed.set(0, 2))
                assert(thrown.getMessage.contains("set-label"))
            finally source.close()
            end try
        }

        "unchecked path (wrapBorrowed) is unaffected by any owner-revoke -- no BorrowRevoked thrown" in {
            val source = Buffer.alloc[Int](2)
            try
                source.set(0, 5)
                val unchecked = Buffer.Unsafe.wrapBorrowed[Int](Buffer.Raw.unwrap(source.raw), 2)
                // No owner attached -- owner checks are skipped entirely.
                assert(unchecked.get(0) == 5)
                // A separate BorrowOwner has no effect on this unchecked buffer.
                val unrelated = new BorrowOwner("unrelated")
                unrelated.revoke()
                assert(unchecked.get(0) == 5)
            finally source.close()
            end try
        }

        "revoke is idempotent and isValid flips once" in {
            val borrowOwner = new BorrowOwner("idem")
            assert(borrowOwner.isValid == true)
            borrowOwner.revoke()
            assert(borrowOwner.isValid == false)
            borrowOwner.revoke()
            assert(borrowOwner.isValid == false)
        }
    }
    // -------------------------------------------------------------------------
    // Buffer.useArray
    // -------------------------------------------------------------------------

    "Buffer.useArray" - {
        "creates buffer from array and returns result" in {
            val result = Buffer.useArray(Array(1, 2, 3)) { buf =>
                buf.get(0) + buf.get(1) + buf.get(2)
            }
            assert(result == 6)
        }

        "closes buffer even when block throws" in {
            var bufRef: Buffer[Int] = null
            try
                Buffer.useArray(Array(10, 20)) { buf =>
                    bufRef = buf
                    throw new RuntimeException("boom")
                }
            catch case _: RuntimeException => ()
            end try
            assert(bufRef.isClosed == true)
        }

        "works with empty array" in {
            val result = Buffer.useArray(Array.empty[Byte]) { buf =>
                buf.size
            }
            assert(result == 0)
        }

        "values in buffer match source array" in {
            val arr = Array[Long](100L, 200L, 300L, 400L)
            Buffer.useArray(arr) { buf =>
                assert(buf.size == 4)
                assert(buf.get(0) == 100L)
                assert(buf.get(1) == 200L)
                assert(buf.get(2) == 300L)
                assert(buf.get(3) == 400L)
            }
        }
    }
end BufferTest
