package kyo.internal.reflect.symbol

import java.util.concurrent.atomic.AtomicReference

/** A lazy one-time computation that is computed on first access and cached for subsequent calls.
  *
  * Thread-safe: if two threads race to compute, one wins the CAS and the other reads the winner's result. The `init` function may be called
  * more than once under concurrent access, but only one computed value is ever stored. Both threads return the same stored value.
  */
final class Memo[A](init: () => A):
    // Store as AnyRef to avoid strict-null comparison issues.
    private val ref = new AtomicReference[AnyRef](Memo.Unset)

    /** Return the cached value, computing it on first call. */
    def get(): A =
        val cached = ref.get()
        if cached ne Memo.Unset then cached.asInstanceOf[A]
        else
            val v = init().asInstanceOf[AnyRef]
            ref.compareAndSet(Memo.Unset, v)
            ref.get().asInstanceOf[A]
        end if
    end get

end Memo

object Memo:
    private val Unset: AnyRef = new AnyRef
end Memo
