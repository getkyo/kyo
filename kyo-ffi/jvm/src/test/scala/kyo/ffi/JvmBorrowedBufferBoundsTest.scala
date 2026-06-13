package kyo.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kyo.*
import kyo.discard
import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked

/** JVM unit spec for the borrowed-buffer reinterpret cap.
  *
  * `BufferFactory.wrapBorrowed` now caps the reinterpret size for zero-sized incoming segments at `-Dkyo.ffi.borrowedBufferMaxBytes=`
  * (default 1 GiB). Under a 16-byte cap, `Buffer.Unsafe.wrapBorrowed[Byte](rawZeroSegment, 64)` wraps 16 bytes -- reads within that window
  * succeed; reads at offsets >= 16 throw Panama's `IndexOutOfBoundsException`.
  */
class JvmBorrowedBufferBoundsTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    private val propKey = "kyo.ffi.borrowedBufferMaxBytes"

    // Each leaf mutates the `kyo.ffi.borrowedBufferMaxBytes` system property; save it before the body and restore it
    // after, isolating leaves from one another (the kyo-test equivalent of the old beforeEach/afterEach pair).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer {
            val savedProp = java.lang.System.getProperty(propKey)
            Scope.ensure {
                if savedProp == null then discard(java.lang.System.clearProperty(propKey))
                else discard(java.lang.System.setProperty(propKey, savedProp))
            }.andThen(body)
        }

    "wrapBorrowed" - {
        "under a 16-byte cap, reads inside the cap succeed and reads past it throw" in {
            // Allocate 64 real bytes in a shared arena; take its base address; hand an address-only
            // (zero-sized) MemorySegment into wrapBorrowed so the cap path triggers.
            val arena = Arena.ofShared()
            try
                val real = arena.allocate(64)
                // Write a distinguishing marker at offset 0 so the inside-bounds read has something to check.
                real.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, 0x7f: Byte)
                real.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 15L, 0x42: Byte)
                real.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 17L, 0x21: Byte)

                val zeroSized = MemorySegment.ofAddress(real.address())

                discard(java.lang.System.setProperty(propKey, "16"))
                val buf = Buffer.Unsafe.wrapBorrowed[Byte](zeroSized, size = 64)

                try
                    assert(buf.get(0) == (0x7f: Byte))
                    assert(buf.get(15) == (0x42: Byte))
                    interceptThrown[IndexOutOfBoundsException](buf.get(17))
                finally buf.close()
                end try
            finally arena.close()
            end try
        }

        "default cap (unset sys-prop) reports 1 GiB" in {
            discard(java.lang.System.clearProperty(propKey))
            assert(kyo.ffi.internal.BufferFactory.borrowedBufferMaxBytes == (1L << 30))
        }

        "a non-numeric cap falls back to 1 GiB" in {
            discard(java.lang.System.setProperty(propKey, "not-a-number"))
            assert(kyo.ffi.internal.BufferFactory.borrowedBufferMaxBytes == (1L << 30))
        }

        "checked-borrow mode (F6) interacts with the 16-byte cap correctly" in {
            // Checked mode keeps the finding-#22 bound-check semantics: reads past the cap throw
            // Panama's IndexOutOfBoundsException; reads within the cap succeed until owner.revoke()
            // flips subsequent accesses to BorrowRevoked.
            val arena = Arena.ofShared()
            try
                val real = arena.allocate(64)
                real.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, 0x55: Byte)
                real.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 15L, 0x66: Byte)

                val zeroSized = MemorySegment.ofAddress(real.address())

                val _           = java.lang.System.setProperty(propKey, "16")
                val borrowOwner = new BorrowOwner("jvm-checked-owner")
                val buf         = Buffer.Unsafe.wrapBorrowedChecked[Byte](zeroSized, size = 64, borrowOwner)
                try
                    assert(buf.get(0) == (0x55: Byte))
                    assert(buf.get(15) == (0x66: Byte))
                    interceptThrown[IndexOutOfBoundsException](buf.get(17))
                    borrowOwner.revoke()
                    val thrown = intercept[BorrowRevoked](buf.get(0))
                    assert(thrown.getMessage.contains("jvm-checked-owner"))
                finally buf.close()
                end try
            finally arena.close()
            end try
        }
    }
end JvmBorrowedBufferBoundsTest
