package kyo.ffi

import kyo.*
import kyo.discard
import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked

/** Verifies the `-Dkyo.ffi.checkedBorrows=true` system-property path.
  *
  * The `kyo.ffi.checkedBorrows` property is consulted at call-site runtime by *generated* FFI binding code -- it is not read by any library
  * function. When the property is `"true"` and the per-binding `Ffi.Config.checkedBorrows` is `false`, generated call sites emit:
  * {{{
  *   if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true")
  *     Buffer.Unsafe.wrapBorrowedChecked(..., BufferFactory.currentBorrowOwner())
  *   else
  *     Buffer.Unsafe.wrapBorrowed(...)
  * }}}
  *
  * These tests verify:
  *   1. The system property can be set and read back correctly in the same process.
  *   2. [[Buffer.Unsafe.wrapBorrowedChecked]] (the checked path) and [[Buffer.Unsafe.wrapBorrowed]] (the unchecked path) behave as expected
  *      with and without the property set -- confirming the two paths the generated code switches between are functional.
  *   3. The property is properly isolated (restored after each leaf).
  *
  * Full end-to-end validation of the property being honoured by generated code lives in the IT suite (`ItCheckedBorrowSpec`), which
  * exercises an actual FFI binding compiled with `Ffi.Config.checkedBorrows = false` and the process-wide property set.
  */
class CheckedBorrowsGlobalTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    private val propKey = "kyo.ffi.checkedBorrows"

    // Each leaf mutates the `kyo.ffi.checkedBorrows` system property; save it before the body and restore it after,
    // isolating leaves from one another (the kyo-test equivalent of the old beforeAll/afterAll save-restore pair).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer {
            val savedProp = java.lang.System.getProperty(propKey)
            Scope.ensure {
                if savedProp == null then discard(java.lang.System.clearProperty(propKey))
                else discard(java.lang.System.setProperty(propKey, savedProp))
            }.andThen(body)
        }

    "kyo.ffi.checkedBorrows system property" - {

        "property is absent by default and reads as null" in {
            discard(java.lang.System.clearProperty(propKey))
            assert(java.lang.System.getProperty(propKey) == null)
        }

        "property can be set to true and read back by the generated-code pattern" in {
            discard(java.lang.System.setProperty(propKey, "true"))
            // Replicate the exact check emitted by the kyo-ffi code generator.
            val checkedBorrowsEnabled = java.lang.System.getProperty(propKey) == "true"
            assert(checkedBorrowsEnabled == true)
            discard(java.lang.System.clearProperty(propKey))
        }

        "property set to false is not equal to true" in {
            discard(java.lang.System.setProperty(propKey, "false"))
            val checkedBorrowsEnabled = java.lang.System.getProperty(propKey) == "true"
            assert(checkedBorrowsEnabled == false)
            discard(java.lang.System.clearProperty(propKey))
        }

        "unchecked path (wrapBorrowed) succeeds when property is unset" in {
            discard(java.lang.System.clearProperty(propKey))
            // Simulate the unchecked path that generated code takes when the property is absent.
            Buffer.use[Byte, Unit](16) { owned =>
                // Obtain a zero-sized raw segment from the owned buffer's address.
                import java.lang.foreign.MemorySegment
                val raw      = owned.raw
                val seg      = Buffer.Raw.unwrap(raw).asInstanceOf[MemorySegment]
                val addrSeg  = MemorySegment.ofAddress(seg.address())
                val borrowed = Buffer.Unsafe.wrapBorrowed[Byte](addrSeg, size = 16)
                try
                    assert(borrowed.size == 16)
                finally borrowed.close()
                end try
            }
            succeed
        }

        "checked path (wrapBorrowedChecked) detects revocation when property is set" in {
            discard(java.lang.System.setProperty(propKey, "true"))
            // Simulate the checked path that generated code takes when the property is "true".
            try
                Buffer.use[Byte, Unit](16) { owned =>
                    import java.lang.foreign.MemorySegment
                    val raw     = owned.raw
                    val seg     = Buffer.Raw.unwrap(raw).asInstanceOf[MemorySegment]
                    val addrSeg = MemorySegment.ofAddress(seg.address())
                    val owner   = new BorrowOwner("global-checked-borrows-test")
                    val borrowed =
                        Buffer.Unsafe.wrapBorrowedChecked[Byte](addrSeg, size = 16, owner)
                    try
                        // Before revoke: access succeeds.
                        discard(borrowed.get(0))
                        // After revoke: access throws BorrowRevoked.
                        owner.revoke()
                        val ex = intercept[BorrowRevoked](borrowed.get(0))
                        assert(ex.getMessage.contains("global-checked-borrows-test"))
                    finally borrowed.close()
                    end try
                }
            finally
                discard(java.lang.System.clearProperty(propKey))
            end try
            succeed
        }
    }
end CheckedBorrowsGlobalTest
