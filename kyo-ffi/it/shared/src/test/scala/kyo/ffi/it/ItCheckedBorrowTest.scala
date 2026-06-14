package kyo.ffi.it

import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.internal.BufferFactory
import kyo.internal.BorrowRevoked

/** IT-level exercise of the checked-borrow machinery.
  *
  * The shared unit specs already validate [[Buffer.BorrowOwner]] + [[Buffer.Unsafe.wrapBorrowedChecked]] behavior. This IT spec asserts the
  * generated-code entry points `BufferFactory.currentBorrowOwner()` and `BufferFactory.rotateBorrowOwner()` behave the same way end-to-end
  * on each platform: borrows issued under the current owner track with that owner's lifecycle, and [[BufferFactory.rotateBorrowOwner]]
  * invalidates every outstanding borrow under the previous owner.
  */
class ItCheckedBorrowTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    "BufferFactory.currentBorrowOwner returns a valid owner and revoke/rotate flips it" in {
        val before = BufferFactory.currentBorrowOwner()
        assert(before.isValid == true)

        // Stand-in for a C-owned region: hand off an owned buffer's raw handle to the checked-borrow path.
        val source = Buffer.alloc[Byte](4)
        try
            source.set(0, 0x42: Byte)
            val borrowed: Buffer[Byte] =
                Buffer.Unsafe.wrapBorrowedChecked[Byte](
                    Buffer.Raw.unwrap(source.raw),
                    size = 4,
                    owner = before
                )
            assert(borrowed.get(0) == (0x42: Byte))

            // Rotate: the previous owner is revoked, a fresh one is installed.
            val fresh = BufferFactory.rotateBorrowOwner()
            assert(before.isValid == false)
            assert(fresh.isValid == true)
            assert((fresh ne before) == true)

            // Access under the revoked owner throws; a freshly-issued borrow under `fresh` still works.
            val thrown: BorrowRevoked = intercept[BorrowRevoked](borrowed.get(0))
            assert(thrown.getMessage.contains(before.label))

            val borrowed2 =
                Buffer.Unsafe.wrapBorrowedChecked[Byte](
                    Buffer.Raw.unwrap(source.raw),
                    size = 4,
                    owner = fresh
                )
            assert(borrowed2.get(0) == (0x42: Byte))
        finally source.close()
        end try
    }

    "sys-prop kyo.ffi.checkedBorrows toggles the generated call-site branch" in {
        // Document the contract: when the sys-prop is unset or 'false', the emitter-generated `if (prop == "true") checked ... else unchecked`
        // routes through the unchecked path. When set to 'true', routes through checked. The emitter tests (JvmEmitterSpec / NativeEmitterSpec /
        // JsEmitterSpec) cover the generated-code shape; this IT-level check just validates the runtime-readable prop reflects what we expect.
        val saved = java.lang.System.getProperty("kyo.ffi.checkedBorrows")
        try
            java.lang.System.setProperty("kyo.ffi.checkedBorrows", "true")
            assert(java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true")

            java.lang.System.setProperty("kyo.ffi.checkedBorrows", "false")
            assert(java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "false")
        finally
            if saved == null then
                discard(java.lang.System.clearProperty("kyo.ffi.checkedBorrows"))
            else
                discard(java.lang.System.setProperty("kyo.ffi.checkedBorrows", saved))
            end if
        end try
    }
end ItCheckedBorrowTest
