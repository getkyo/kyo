package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.STM.RefLog
import kyo.STM.internal.*
import scala.annotation.tailrec

/** A transactional reference that can be modified within STM transactions. Provides atomic read and write operations with strong
  * consistency guarantees.
  *
  * @param id
  *   Unique identifier for this reference
  * @param state
  *   The current state of the reference
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
    with TRef[A]:

    @volatile private var currentState = initialState

    private[kyo] def state(using AllowUnsafe): Write[A] = currentState

    def use[B, S](f: A => B < S)(using Frame): B < (STM & S) =
        Var.use[RefLog] { log =>
            val refAny = this.asInstanceOf[TRef[Any]]
            if log.contains(refAny) then
                // TRef is already in the log, return value
                f(log(refAny).value.asInstanceOf[A])
            else
                useRequiredTid { tid =>
                    IO {
                        val state = currentState
                        if state.tid > tid then
                            // Early retry if the TRef is concurrently modified
                            STM.retry
                        else
                            // Append Read to the log and return value
                            val entry = Read[Any](state.tid, state.value)
                            Var.setAndThen(log + (refAny -> entry))(f(state.value))
                        end if
                    }
                }
            end if
        }

    def set(v: A)(using Frame): Unit < STM =
        Var.use[RefLog] { log =>
            val refAny = this.asInstanceOf[TRef[Any]]
            if log.contains(refAny) then
                // TRef is already in the log, update it
                val prev  = log(refAny)
                val entry = Write[Any](prev.tid, v)
                Var.setDiscard(log + (refAny -> entry))
            else
                useRequiredTid { tid =>
                    IO {
                        val state = currentState
                        if state.tid > tid then
                            // Early retry if the TRef is concurrently modified
                            STM.retry
                        else
                            // Append Write to the log
                            val entry = Write[Any](state.tid, v)
                            Var.setDiscard(log + (refAny -> entry))
                        end if
                    }
                }
            end if
        }

    @tailrec private[kyo] def lock(entry: Entry[A])(using AllowUnsafe): Boolean =
        currentState.tid == entry.tid && {
            val lockState = super.get()
            entry match
                case Read(tid, value) =>
                    // Read locks can stack if no write lock
                    lockState != Int.MaxValue && (super.compareAndSet(lockState, lockState + 1) || lock(entry))
                case Write(tid, value) =>
                    // Write lock requires no existing locks
                    lockState == 0 && (super.compareAndSet(lockState, Int.MaxValue) || lock(entry))
            end match
        }

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
end TRefImpl

object TRef:

    /** Creates a new transactional reference outside of a transaction.
      *
      * @param value
      *   The initial value
      * @return
      *   A new reference containing the value
      */
    def init[A](value: A)(using Frame): TRef[A] < IO =
        IO.Unsafe(TRef.Unsafe.init(value))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](value: A)(using AllowUnsafe): TRef[A] =
            init(STM.internal.nextTid.incrementAndGet(), value)

        private[kyo] def init[A](tid: Long, value: A)(using AllowUnsafe): TRef[A] =
            new TRefImpl(Write(tid, value))
    end Unsafe
end TRef
