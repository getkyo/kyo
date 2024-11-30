package kyo

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
@safePublish final class TRef[A] private[kyo] (
    @volatile private[kyo] var state: Write[A]
):
    // Int.MaxValue => write lock
    // > 0          => read lock
    private val lock =
        import AllowUnsafe.embrace.danger
        AtomicInt.Unsafe.init(0)

    /** Gets the current value of the reference within a transaction.
      *
      * @return
      *   The current value
      */
    def get(using Frame): A < STM =
        use(identity)

    def use[B, S](f: A => B < S)(using Frame): B < (STM & S) =
        Var.use[RefLog] { log =>
            val refAny = this.asInstanceOf[TRef[Any]]
            if log.contains(refAny) then
                // TRef is already in the log, return value
                f(log(refAny).value.asInstanceOf[A])
            else
                useRequiredTid { tid =>
                    IO {
                        val state = this.state
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

    /** Sets a new value for the reference within a transaction.
      *
      * @param v
      *   The new value to set
      */
    def set(v: A)(using Frame): Unit < STM =
        Var.use[RefLog] { log =>
            val refAny = this.asInstanceOf[TRef[Any]]
            if log.contains(refAny) then
                // TRef is already in the log, update it
                val prev  = log(refAny)
                val entry = Write[Any](prev.tid, v)
                Var.setDiscard(log + (refAny -> entry))
            else
                IO {
                    useRequiredTid { tid =>
                        val state = this.state
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

    /** Updates the reference's value by applying a function to the current value within a transaction.
      *
      * @param f
      *   The function to transform the current value into the new value
      * @return
      *   Unit, as this is a modification operation
      */
    def update[S](f: A => A < S)(using Frame): Unit < (STM & S) =
        use(f(_).map(set))

    @tailrec private[kyo] def lock(entry: Entry[A])(using AllowUnsafe): Boolean =
        state.tid == entry.tid && {
            val l = lock.get()
            entry match
                case Read(tid, value) =>
                    // Read locks can stack if no write lock
                    l != Int.MaxValue && (lock.cas(l, l + 1) || lock(entry))
                case Write(tid, value) =>
                    // Write lock requires no existing locks
                    l == 0 && (lock.cas(l, Int.MaxValue) || lock(entry))
            end match
        }

    private[kyo] def commit(tid: Long, entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case Write(_, value) =>
                // Only need to commit Write entries
                state = Write(tid, value)
            case _ =>

    private[kyo] def unlock(entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case Read(tid, value) =>
                // Release read lock
                discard(lock.decrementAndGet())
            case Write(tid, value) =>
                // Release write lock
                lock.set(0)
        end match
    end unlock
end TRef

object TRef:
    /** Creates a new transactional reference outside of a transaction.
      *
      * @param value
      *   The initial value
      * @return
      *   A new reference containing the value
      */
    def init[A](value: A)(using Frame): TRef[A] < IO =
        IO.Unsafe(initRefNow(value))
end TRef
