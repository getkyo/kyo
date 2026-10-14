package kyo.net.internal.transport

import kyo.*

/** One promise a driver has parked on a handle for one entry path (a read, a writable wait, a connect, ...), held in a single atomic cell
  * owned by the handle's [[HandleLife]].
  *
  * The slot is the only way to park, so every driver gets the same guarantees by construction:
  *   - A park after the handle's release claim fails at once. [[park]] deposits first and re-checks the claim second; the release takes the
  *     claim first and sweeps second. Whichever order the two interleave in, either the park sees the claim or the sweep sees the deposit.
  *   - No sweep can erase a later park. A sweep takes with `getAndSet` and a delivering completion clears with [[clearIf]], a compare-and-set
  *     on the promise it delivered, so a promise re-parked between a read of the slot and its clear is never dropped.
  *   - A cancel reaches a promise a dispatch is working on. [[beginDispatch]] keeps the promise in the slot, marked as dispatching, so a
  *     cancel's sweep still fails it, and [[repark]] puts it back only if no sweep took it meanwhile. A dispatch that took the promise out
  *     of the slot instead would hold it where no cancel can see it, and its re-park would leave a cancelled read live.
  */
final private[kyo] class ParkedSlot[A, E >: Closed] private[transport] (
    cell: AtomicRef.Unsafe[ParkedSlot.State[A, E]],
    life: HandleLife
):
    import ParkedSlot.*

    /** Parks `p`. Returns `false` when the handle's release claim is already taken, in which case `p` is already failed when this returns
      * and the caller must not arm anything for it.
      */
    def park(p: Promise.Unsafe[A, Abort[E]], closed: => Closed)(using AllowUnsafe, Frame): Boolean =
        val parked = Parked(p)
        cell.set(parked)
        recheckClaim(parked, closed)
    end park

    /** As [[park]], swapping out the previous occupant in the same step and failing it with `displaced`, so no park can land between the
      * occupant's removal and `p`'s deposit.
      */
    def displace(p: Promise.Unsafe[A, Abort[E]], displaced: => Closed, closed: => Closed)(using AllowUnsafe, Frame): Boolean =
        val parked = Parked(p)
        cell.getAndSet(parked).promise.foreach(prev => if !prev.equals(p) then prev.completeDiscard(Result.fail(displaced)))
        recheckClaim(parked, closed)
    end displace

    /** Takes the parked promise, for the side that completes it. A promise under dispatch stays with its dispatch. */
    def take()(using AllowUnsafe): Maybe[Promise.Unsafe[A, Abort[E]]] =
        @scala.annotation.tailrec
        def loop(): Maybe[Promise.Unsafe[A, Abort[E]]] =
            cell.get() match
                case current: Parked[A, E] @unchecked =>
                    if cell.compareAndSet(current, empty) then Present(current.p) else loop()
                case _ => Absent
        loop()
    end take

    /** Marks the parked promise as under dispatch and returns it, leaving it visible to a sweep until [[repark]] or [[endDispatch]]. */
    def beginDispatch()(using AllowUnsafe): Maybe[Promise.Unsafe[A, Abort[E]]] =
        @scala.annotation.tailrec
        def loop(): Maybe[Promise.Unsafe[A, Abort[E]]] =
            cell.get() match
                case current: Parked[A, E] @unchecked =>
                    if cell.compareAndSet(current, Dispatching(current.p)) then Present(current.p) else loop()
                case _ => Absent
        loop()
    end beginDispatch

    /** Parks a promise under dispatch again. Returns `false`, with `p` failed, when a sweep or a later park replaced it during the dispatch
      * or the release claim is taken; the caller must not arm anything for it then.
      */
    def repark(p: Promise.Unsafe[A, Abort[E]], closed: => Closed)(using AllowUnsafe, Frame): Boolean =
        cell.get() match
            case current: Dispatching[A, E] @unchecked if current.p.equals(p) =>
                val parked = Parked(p)
                if cell.compareAndSet(current, parked) then recheckClaim(parked, closed)
                else
                    p.completeDiscard(Result.fail(closed))
                    false
                end if
            case _ =>
                p.completeDiscard(Result.fail(closed))
                false
    end repark

    /** Ends a dispatch that completes its promise. */
    def endDispatch(p: Promise.Unsafe[A, Abort[E]])(using AllowUnsafe): Unit =
        cell.get() match
            case current: Dispatching[A, E] @unchecked if current.p.equals(p) => discard(cell.compareAndSet(current, empty))
            case _                                                            => ()

    /** Clears the slot only if it still holds `p` parked, so a promise parked after `p` stays parked. */
    def clearIf(p: Promise.Unsafe[A, Abort[E]])(using AllowUnsafe): Boolean =
        cell.get() match
            case current: Parked[A, E] @unchecked if current.p.equals(p) => cell.compareAndSet(current, empty)
            case _                                                       => false

    /** The parked promise, or the one under dispatch, left in place. */
    def current(using AllowUnsafe): Maybe[Promise.Unsafe[A, Abort[E]]] = cell.get().promise

    /** Takes and fails whatever is parked or under dispatch. */
    def fail(closed: Closed)(using AllowUnsafe, Frame): Unit =
        cell.getAndSet(empty).promise.foreach(_.completeDiscard(Result.fail(closed)))

    // Fails the promise here even when a sweep already took it: the sweep may still be between its take and its completion on another
    // carrier, and the caller reads a park after the claim as failed the moment it returns.
    private def recheckClaim(parked: Parked[A, E], closed: => Closed)(using AllowUnsafe, Frame): Boolean =
        if life.armable then true
        else
            discard(cell.compareAndSet(parked, empty))
            parked.p.completeDiscard(Result.fail(closed))
            false
    end recheckClaim

    private def empty: State[A, E] = emptyState

end ParkedSlot

private[kyo] object ParkedSlot:

    /** What a slot holds. Compared by reference: every transition swaps in a fresh instance, so a compare-and-set never matches a state that
      * was replaced and restored in between.
      */
    sealed abstract private[transport] class State[A, E]:
        def promise: Maybe[Promise.Unsafe[A, Abort[E]]]

    final private[transport] class Parked[A, E](val p: Promise.Unsafe[A, Abort[E]]) extends State[A, E]:
        def promise: Maybe[Promise.Unsafe[A, Abort[E]]] = Present(p)

    final private[transport] class Dispatching[A, E](val p: Promise.Unsafe[A, Abort[E]]) extends State[A, E]:
        def promise: Maybe[Promise.Unsafe[A, Abort[E]]] = Present(p)

    private[transport] object Empty extends State[Any, Any]:
        def promise: Maybe[Promise.Unsafe[Any, Abort[Any]]] = Absent

    private[transport] def emptyState[A, E]: State[A, E] = Empty.asInstanceOf[State[A, E]]

end ParkedSlot
