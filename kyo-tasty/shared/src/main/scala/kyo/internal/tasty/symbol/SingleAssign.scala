package kyo.internal.tasty.symbol

import kyo.AllowUnsafe
import kyo.AtomicRef

/** A write-once slot that throws if assigned a second time.
  *
  * Thread-safe: the first successful `compareAndSet` wins; subsequent `set` calls throw `IllegalStateException`. The `get` call throws if
  * the value has not been assigned.
  *
  * WARNING: This is an unsafe-tier helper. `set()` and `get()` execute side effects on an AtomicRef.Unsafe. Callers must hold an
  * `AllowUnsafe` proof, or invoke from inside a `Sync.Unsafe.defer` block.
  */
final class SingleAssign[A] private (private val ref: AtomicRef.Unsafe[AnyRef]):

    /** Assign the value. Throws `IllegalStateException` if already assigned.
      *
      * Requires AllowUnsafe: this method writes an AtomicRef.Unsafe as a side effect.
      */
    def set(a: A)(using AllowUnsafe): Unit =
        // AsInstanceOf justified: A is stored as AnyRef to use AtomicRef.Unsafe with the Unset sentinel;
        // the union type A | Unset.type cannot be expressed without boxing in Scala 3 on AtomicRef.Unsafe[AnyRef].
        if !ref.compareAndSet(SingleAssign.Unset, a.asInstanceOf[AnyRef]) then
            throw new IllegalStateException("SingleAssign already set")

    /** Retrieve the value. Throws `IllegalStateException` if not yet assigned.
      *
      * Requires AllowUnsafe: this method reads an AtomicRef.Unsafe as a side effect.
      */
    def get()(using AllowUnsafe): A =
        val v = ref.get()
        if v eq SingleAssign.Unset then throw new IllegalStateException("SingleAssign not yet set")
        else
            // AsInstanceOf justified: AnyRef sentinel pattern; ref holds either SingleAssign.Unset or an A stored as AnyRef;
            // the ne-Unset guard guarantees the value is the A we stored.
            v.asInstanceOf[A]
        end if
    end get

    /** Returns true if the value has been assigned, false if still unset.
      *
      * Requires AllowUnsafe: this method reads an AtomicRef.Unsafe as a side effect.
      */
    def isSet(using AllowUnsafe): Boolean =
        ref.get() ne SingleAssign.Unset

end SingleAssign

object SingleAssign:
    private val Unset: AnyRef = new AnyRef

    /** Allocate a fresh unset slot. Requires AllowUnsafe because AtomicRef.Unsafe.init is an unsafe-tier allocation. */
    def init[A]()(using AllowUnsafe): SingleAssign[A] =
        new SingleAssign(AtomicRef.Unsafe.init[AnyRef](Unset))

end SingleAssign
