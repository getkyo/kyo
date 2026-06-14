package kyo.ffi.internal

import kyo.internal.BorrowOwner

private[ffi] class BorrowOwnerHolder(label: String):
    private val currentBorrowOwnerTL: ThreadLocal[BorrowOwner] =
        ThreadLocal.withInitial(() => new BorrowOwner(label))

    def currentBorrowOwner(): BorrowOwner =
        val o = currentBorrowOwnerTL.get().nn
        o

    def rotateBorrowOwner(): BorrowOwner =
        val old = currentBorrowOwnerTL.get().nn
        old.revoke()
        val fresh = new BorrowOwner(old.label)
        currentBorrowOwnerTL.set(fresh)
        fresh
    end rotateBorrowOwner
end BorrowOwnerHolder
