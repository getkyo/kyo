package kyo.internal.reflect.symbol

import java.util.concurrent.atomic.AtomicReference
import kyo.AllowUnsafe

/** A lazy one-time computation that is computed on first access and cached for subsequent calls.
  *
  * Thread-safe: if two threads race to compute, one wins the CAS and the other reads the winner's result. The `init` function may be called
  * more than once under concurrent access, but only one computed value is ever stored. Both threads return the same stored value.
  *
  * WARNING: This is an unsafe-tier helper. `get()` executes a side effect (reads and potentially writes an AtomicReference). Callers must
  * hold an `AllowUnsafe` proof, or invoke from inside a `Sync.Unsafe.defer` block.
  */
final class Memo[A](init: () => A):
    // Store as AnyRef to avoid strict-null comparison issues.
    private val ref = new AtomicReference[AnyRef](Memo.Unset)

    /** Return the cached value, computing it on first call.
      *
      * Requires AllowUnsafe: this method reads and potentially writes an AtomicReference as a side effect.
      */
    def get()(using AllowUnsafe): A =
        val cached = ref.get()
        if cached ne Memo.Unset then
            // AsInstanceOf justified: AnyRef sentinel pattern; ref holds either Memo.Unset or an A stored as AnyRef;
            // the ne-Unset guard guarantees the value is the A we stored.
            cached.asInstanceOf[A]
        else
            // AsInstanceOf justified: we store A as AnyRef to use AtomicReference with the Unset sentinel;
            // the union type A | Unset.type cannot be expressed without boxing in Scala 3 on AtomicReference[AnyRef].
            val v = init().asInstanceOf[AnyRef]
            ref.compareAndSet(Memo.Unset, v)
            // AsInstanceOf justified: same as above; ref now holds either Memo.Unset (CAS lost, another thread won)
            // or the v we stored; in both cases the stored value is an A.
            ref.get().asInstanceOf[A]
        end if
    end get

end Memo

object Memo:
    private val Unset: AnyRef = new AnyRef
end Memo
