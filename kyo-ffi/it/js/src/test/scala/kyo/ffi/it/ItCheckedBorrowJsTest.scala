package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.internal.BufferFactory
import kyo.internal.BorrowRevoked
import scala.scalajs.js as sjs

/** The process-wide `kyo.ffi.checkedBorrows` switch on JS, where no `-D` flag exists: the generated borrowed-return call site reads it from
  * the host configuration, so a program seeds it through `globalThis.KYO_CONFIG`.
  *
  * A checked borrow is tied to the current borrow owner, so rotating the owner revokes it; an unchecked one is not. Both leaves call the same
  * generated binding and differ only in the seed.
  */
class ItCheckedBorrowJsTest extends Test:

    // Rotates the process-wide borrow owner and installs a process-wide seed.
    override def config = super.config.sequential

    "a seeded kyo.ffi.checkedBorrows=true makes a generated borrowed return revocable" in {
        val bindings = Ffi.load[ItBorrowedBindings]
        val global   = sjs.Dynamic.global.globalThis
        global.updateDynamic("KYO_CONFIG")(sjs.Dynamic.literal(properties = sjs.Dynamic.literal("kyo.ffi.checkedBorrows" -> "true")))
        val borrowed =
            try bindings.kyoItMallocChunk(16L).value
            finally kyo.discard(sjs.special.delete(global, "KYO_CONFIG"))
        kyo.discard(BufferFactory.rotateBorrowOwner())
        intercept[BorrowRevoked](borrowed.get(0))
        succeed
    }

    "without the seed, a generated borrowed return is not revoked by a rotation" in {
        val bindings = Ffi.load[ItBorrowedBindings]
        val borrowed = bindings.kyoItMallocChunk(16L).value
        kyo.discard(BufferFactory.rotateBorrowOwner())
        assert(borrowed.get(0) == 0xab.toByte)
    }

end ItCheckedBorrowJsTest
