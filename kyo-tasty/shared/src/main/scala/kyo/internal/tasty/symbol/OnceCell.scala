package kyo.internal.tasty.symbol

import java.util.concurrent.atomic.AtomicReference
import kyo.AllowUnsafe

/** A lazy one-time computation cell.
  *
  * On first access via `get()`, the supplied `init` function runs and the result is CAS-published. Subsequent reads return the cached
  * value.
  *
  * Concurrent first-access semantics: if two threads race on `get()` before either has CAS-published, BOTH run `init()` redundantly. One
  * CAS wins; the other's computed value is discarded. Both threads then return the same cached value.
  *
  * This is distinct from `kyo.Cache.memo`, which uses a Promise to dedup concurrent first-callers (only one runs `init()`; others await the
  * Promise). `kyo.Cache.memo`'s dedup costs Async on the accessor's effect row. OnceCell's race-and-discard costs occasional redundant
  * init() calls but never blocks and never adds Async.
  *
  * For kyo-tasty's body decode and Name interning workloads, OnceCell is correct: the init() call is small and synchronous, redundant
  * first-access work is bounded to microseconds, and keeping the accessor effect row Sync-only is more valuable than dedup precision.
  *
  * WARNING: This is an unsafe-tier helper. `get()` reads and potentially writes an AtomicReference as a side effect. Callers must hold an
  * `AllowUnsafe` proof.
  */
final class OnceCell[A](init: () => A):
    // Store as AnyRef to avoid strict-null comparison issues.
    private val ref = new AtomicReference[AnyRef](OnceCell.Unset)

    /** Return the cached value, computing it on first call.
      *
      * Requires AllowUnsafe: this method reads and potentially writes an AtomicReference as a side effect.
      */
    def get()(using AllowUnsafe): A =
        val cached = ref.get()
        if cached ne OnceCell.Unset then
            // Unsafe: AnyRef-sentinel pattern; ne-Unset guarantees the stored value is A.
            cached.asInstanceOf[A]
        else
            // Unsafe: we store A as AnyRef to coexist with the Unset sentinel in AtomicReference[AnyRef].
            val v = init().asInstanceOf[AnyRef]
            ref.compareAndSet(OnceCell.Unset, v)
            // Unsafe: same sentinel pattern; ref.get() now holds the CAS-winning value.
            ref.get().asInstanceOf[A]
        end if
    end get

end OnceCell

object OnceCell:
    private val Unset: AnyRef = new AnyRef
end OnceCell
