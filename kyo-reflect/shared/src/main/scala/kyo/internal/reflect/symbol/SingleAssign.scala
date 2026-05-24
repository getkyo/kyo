package kyo.internal.reflect.symbol

import java.util.concurrent.atomic.AtomicReference

/** A write-once slot that throws if assigned a second time.
  *
  * Thread-safe: the first successful `compareAndSet` wins; subsequent `set` calls throw `IllegalStateException`. The `get` call throws if
  * the value has not been assigned.
  */
final class SingleAssign[A]:
    // Store as AnyRef to avoid strict-null comparison issues. Unset is a sentinel.
    private val ref = new AtomicReference[AnyRef](SingleAssign.Unset)

    /** Assign the value. Throws `IllegalStateException` if already assigned. */
    def set(a: A): Unit =
        if !ref.compareAndSet(SingleAssign.Unset, a.asInstanceOf[AnyRef]) then
            throw new IllegalStateException("SingleAssign already set")

    /** Retrieve the value. Throws `IllegalStateException` if not yet assigned. */
    def get(): A =
        val v = ref.get()
        if v eq SingleAssign.Unset then throw new IllegalStateException("SingleAssign not yet set")
        else v.asInstanceOf[A]
    end get

end SingleAssign

object SingleAssign:
    private val Unset: AnyRef = new AnyRef
end SingleAssign
