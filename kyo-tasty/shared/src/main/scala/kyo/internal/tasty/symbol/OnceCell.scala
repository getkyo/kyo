package kyo.internal.tasty.symbol

import kyo.AllowUnsafe
import kyo.AtomicRef

/** A lazy one-time computation cell.
  *
  * On first access via `get()`, the supplied `init` function runs and the result is CAS-published. Subsequent reads return the cached
  * value.
  *
  * Concurrent first-access semantics: if two threads race on `get()` before either has CAS-published, BOTH run `init()` redundantly. One
  * CAS wins; the other's computed value is discarded. Both threads then return the same cached value.
  *
  * REQUIRES IDEMPOTENT INIT: if two threads race on `get()`, both run `init()` redundantly; the design assumes `init() == init()` modulo
  * equality. Debug mode: `-Dkyo.tasty.OnceCell.debug=true` flags non-idempotent init via `IllegalStateException`.
  *
  * This is distinct from `kyo.Cache.memo`, which uses a Promise to dedup concurrent first-callers (only one runs `init()`; others await the
  * Promise). `kyo.Cache.memo`'s dedup costs Async on the accessor's effect row. OnceCell's race-and-discard costs occasional redundant
  * init() calls but never blocks and never adds Async.
  *
  * For kyo-tasty's body decode and Name interning workloads, OnceCell is correct: the init() call is small and synchronous, redundant
  * first-access work is bounded to microseconds, and keeping the accessor effect row Sync-only is more valuable than dedup precision.
  *
  * WARNING: This is an unsafe-tier helper. `get()` reads and potentially writes an AtomicRef as a side effect. Callers must hold an
  * `AllowUnsafe` proof.
  */
object OnceCell:
    /** When true, a CAS-losing thread that computed a different value from the winner throws [[IllegalStateException]].
      *
      * Enable via `-Dkyo.tasty.OnceCell.debug=true`.
      */
    private[kyo] val debugIdempotent: Boolean =
        java.lang.System.getProperty("kyo.tasty.OnceCell.debug", "false").equalsIgnoreCase("true")

    private val Unset: AnyRef = new AnyRef

    def init[A](init: () => A)(using AllowUnsafe): OnceCell[A] =
        new OnceCell(init, AtomicRef.Unsafe.init[AnyRef](Unset))
end OnceCell

final class OnceCell[A] private (init: () => A, ref: AtomicRef.Unsafe[AnyRef]):

    /** Return the cached value, computing it on first call.
      *
      * Reads (and on first call writes) an `AtomicRef`, so the caller must hold an `AllowUnsafe` proof.
      * Post-init reads are referentially transparent (race-and-discard semantics).
      */
    def get()(using AllowUnsafe): A =
        // §839 case 3 -- memoized lazy; race-and-discard but post-init reads are referentially transparent.
        val cached = ref.get()
        if cached ne OnceCell.Unset then
            // Unsafe: AnyRef-sentinel pattern; ne-Unset guarantees the stored value is A.
            cached.asInstanceOf[A]
        else
            // Unsafe: we store A as AnyRef to coexist with the Unset sentinel in AtomicRef.Unsafe[AnyRef].
            val v   = init().asInstanceOf[AnyRef]
            val won = ref.compareAndSet(OnceCell.Unset, v)
            if !won && OnceCell.debugIdempotent then
                val winner = ref.get()
                if !winner.equals(v) then
                    throw new IllegalStateException(
                        s"OnceCell idempotence violated: init() returned $v but stored value is $winner"
                    )
                end if
            end if
            // Unsafe: same sentinel pattern; ref.get() now holds the CAS-winning value.
            ref.get().asInstanceOf[A]
        end if
    end get

end OnceCell
