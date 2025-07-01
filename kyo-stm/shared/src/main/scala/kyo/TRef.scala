package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.TRefLog.*
import scala.annotation.tailrec

/** A transactional reference that can be modified within STM transactions. Provides atomic read and write operations with strong
  * consistency guarantees.
  */
sealed trait TRef[A]:

    /** Applies a function to the current value of the reference within a transaction.
      *
      * @param f
      *   A function that transforms the current value of type A into a result of type B, with effects S
      * @return
      *   The result of type B with combined STM and S effects
      */
    def use[B, S](f: A => B < S)(using Frame): B < (STM & S)

    /** Sets a new value for the reference within a transaction.
      *
      * @param v
      *   The new value to set
      */
    def set(v: A)(using Frame): Unit < STM

    /** Gets the current value of the reference within a transaction.
      *
      * @return
      *   The current value
      */
    final def get(using Frame): A < STM = use(identity)

    /** Updates the reference's value by applying a function to the current value within a transaction.
      *
      * @param f
      *   The function to transform the current value into the new value
      * @return
      *   Unit, as this is a modification operation
      */
    final def update[S](f: A => A < S)(using Frame): Unit < (STM & S) = use(f(_).map(set))

    private[kyo] def state(using AllowUnsafe): Write[A]
    private[kyo] def validate(entry: Entry[A])(using AllowUnsafe): Boolean
    private[kyo] def lock(entry: Entry[A])(using AllowUnsafe): Boolean
    private[kyo] def commit(tid: Long, entry: Entry[A])(using AllowUnsafe): Unit
    private[kyo] def unlock(entry: Entry[A])(using AllowUnsafe): Unit

end TRef

/** Implementation of a transactional reference. Extends AtomicInteger to avoid an extra allocation for lock state management.
  *
  * @param initialState
  *   The initial value and transaction ID for this reference
  */
final private class TRefImpl[A] private[kyo] (initialState: Write[A])
    extends AtomicInteger(0) // Atomic super class to keep the lock state
    with TRef[A]
    with Serializable:

    @volatile private var currentState = initialState

    private[kyo] def state(using AllowUnsafe): Write[A] = currentState

    def use[B, S](f: A => B < S)(using Frame): B < (STM & S) =
        Var.use[TRefLog] { log =>
            log.get(this) match
                case Present(entry) =>
                    f(entry.value)
                case Absent =>
                    TID.useIORequired { tid =>
                        val state = currentState
                        if state.tid > tid then
                            // Early retry if the TRef is concurrently modified
                            STM.retry
                        else
                            // Append Read to the log and return value
                            val entry = Read(state.tid, state.value)
                            Var.setWith(log.put(this, entry))(f(state.value))
                        end if
                    }
            end match
        }

    def set(v: A)(using Frame): Unit < STM =
        Var.use[TRefLog] { log =>
            log.get(this) match
                case Present(prev) =>
                    val entry = Write(prev.tid, v)
                    Var.setDiscard(log.put(this, entry))
                case Absent =>
                    TID.useIORequired { tid =>
                        val state = currentState
                        if state.tid > tid then
                            // Early retry if the TRef is concurrently modified
                            STM.retry
                        else
                            // Append Write to the log
                            val entry = Write(state.tid, v)
                            Var.setDiscard(log.put(this, entry))
                        end if
                    }
        }

    private[kyo] def validate(entry: Entry[A])(using AllowUnsafe): Boolean =
        currentState.tid == entry.tid

    private[kyo] def lock(entry: Entry[A])(using AllowUnsafe): Boolean =
        @tailrec def loop(): Boolean =
            validate(entry) && {
                val lockState = super.get()
                entry match
                    case Read(tid, value) =>
                        // Read locks can stack if no write lock
                        lockState != Int.MaxValue && (super.compareAndSet(lockState, lockState + 1) || loop())
                    case Write(tid, value) =>
                        // Write lock requires no existing locks
                        lockState == 0 && (super.compareAndSet(lockState, Int.MaxValue) || loop())
                end match
            }
        val locked = loop()
        if locked && !validate(entry) then
            // This branch handles the race condition where another fiber commits
            // after the initial `validate(entry)` check but before the
            // lock is acquired. If that's the case, roll back the lock.
            unlock(entry)
            false
        else
            locked
        end if
    end lock

    private[kyo] def commit(tid: Long, entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case Write(_, value) =>
                // Only need to commit Write entries
                currentState = Write(tid, value)
            case _ =>

    private[kyo] def unlock(entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case Read(tid, value) =>
                // Release read lock
                discard(super.decrementAndGet())
            case Write(tid, value) =>
                // Release write lock
                super.set(0)
        end match
    end unlock

    override def toString() =
        val lockState = get() match
            case 0            => "free"
            case Int.MaxValue => "writer"
            case i            => s"$i readers"
        s"TRef(state=$currentState, lock=$lockState)"
    end toString
end TRefImpl

object TRef:

    /** Creates a new TRef with the given initial value.
      *
      * @param value
      *   The initial value to store in the reference
      * @return
      *   A new TRef containing the value, within the Sync effect
      */
    def init[A](value: A)(using Frame): TRef[A] < Sync =
        initWith(value)(identity)

    /** Creates a new TRef and immediately applies a function to it.
      *
      * This is a more efficient way to initialize a TRef and perform operations on it, as it combines initialization and the first
      * operation in a single transaction.
      *
      * @param value
      *   The initial value to store in the reference
      * @param f
      *   The function to apply to the newly created TRef
      * @return
      *   The result of applying the function to the new TRef, within combined Sync and S effects
      */
    inline def initWith[A, B, S](inline value: A)(inline f: TRef[A] => B < S)(using inline frame: Frame): B < (Sync & S) =
        TID.useIOUnsafe { tid =>
            f(TRef.Unsafe.init(tid, value))
        }

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](value: A)(using AllowUnsafe): TRef[A] =
            init(TID.next, value)

        private[kyo] def init[A](tid: Long, value: A)(using AllowUnsafe): TRef[A] =
            val finalTid =
                if tid != -1 then tid
                else TID.next
            new TRefImpl(Write(finalTid, value))
        end init
    end Unsafe
end TRef
