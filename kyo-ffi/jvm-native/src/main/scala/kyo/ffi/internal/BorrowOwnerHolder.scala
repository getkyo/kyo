package kyo.ffi.internal

import kyo.internal.BorrowOwner

private[ffi] class BorrowOwnerHolder(label: String):
    // Per thread and found by the thread's identity (see ThreadOwned): a borrow issued under the owner must find that same owner at
    // the next call, and on Scala Native a ThreadLocal entry alone can vanish in between.
    private val owner = new ThreadOwned[BorrowOwner](_ => new BorrowOwner(label))

    def currentBorrowOwner(): BorrowOwner = owner.get()

    def rotateBorrowOwner(): BorrowOwner =
        val old = owner.get()
        old.revoke()
        val fresh = new BorrowOwner(old.label)
        owner.set(fresh)
        fresh
    end rotateBorrowOwner
end BorrowOwnerHolder
