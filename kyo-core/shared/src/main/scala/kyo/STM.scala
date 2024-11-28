package kyo

import STM.internal.*
import scala.annotation.tailrec

/** A FailedTransaction exception that is thrown when a transaction fails to commit. Contains the frame where the failure occurred.
  */
case class FailedTransaction(frame: Frame) extends Exception(frame.position.show)

/** Software Transactional Memory (STM) provides concurrent access to shared state using optimistic locking. Rather than acquiring locks
  * upfront, transactions execute speculatively and automatically retry if conflicts are detected during commit. While this enables better
  * composability than manual locking, applications must be designed to handle potentially frequent transaction retries.
  *
  * Transactions are atomic, isolated, and composable but may retry multiple times before success. Side effects (like I/O) inside
  * transactions must be used with caution as they will be re-executed on retry. Pure operations that only modify transactional references
  * are safe and encouraged, while external side effects should be performed after the transaction commits.
  *
  * The core operations are:
  *   - STM.initRef and Ref.init create transactional references that can be shared between threads
  *   - Ref.get and Ref.set read and modify references within transactions
  *   - STM.run executes transactions that either fully commit or rollback
  *   - STM.retry and STM.retryIf provide manual control over transaction retry behavior
  *   - Configurable retry schedules via STM.run's retrySchedule parameter
  *
  * STM is most effective for operations that rarely conflict and complete quickly. Long-running transactions or high contention scenarios
  * may face performance challenges from repeated retries.
  */
opaque type STM <: (Var[STM.RefLog] & Abort[FailedTransaction] & Async) =
    Var[STM.RefLog] & Abort[FailedTransaction] & Async

// Export Ref to the kyo package
export STM.Ref

object STM:

    // The default retry schedule for failed transactions
    val defaultRetrySchedule = Schedule.fixed(1.millis * 0.5).take(20)

    /** Forces a transaction retry by aborting the current transaction and rolling back all changes. This is useful when a transaction
      * detects that it cannot proceed due to invalid state.
      *
      * @return
      *   Nothing, as this operation always aborts the transaction
      */
    def retry(using frame: Frame): Nothing < STM = Abort.fail(FailedTransaction(frame))

    /** Conditionally retries a transaction based on a boolean condition. If the condition is true, the transaction will be retried.
      * Otherwise, execution continues normally.
      *
      * @param cond
      *   The condition that determines whether to retry
      */
    def retryIf(cond: Boolean)(using Frame): Unit < STM = if cond then retry else ()

    /** Initializes a new transactional reference (Ref) with an initial value. The reference is created within the current transaction.
      *
      * @param value
      *   The initial value to store in the reference
      * @return
      *   A new transactional reference containing the initial value
      */
    def initRef[A](value: A)(using Frame): Ref[A] < STM =
        useRequiredTid { tid =>
            Var.use[RefLog] { log =>
                IO.Unsafe {
                    val ref    = initRefNow(tid, value)
                    val refAny = ref.asInstanceOf[Ref[Any]]
                    Var.setAndThen(log + (refAny -> refAny.state))(ref)
                }
            }
        }

    /** Executes a transactional computation with explicit state isolation. This version of run supports additional effects beyond Abort and
      * Async through the provided isolate, which ensures proper state management during transaction retries and rollbacks.
      *
      * @param isolate
      *   The isolation scope for the transaction
      * @param retrySchedule
      *   The schedule for retrying failed transactions
      * @param v
      *   The transactional computation to run
      * @return
      *   The result of the computation if successful
      */
    def run[E, A: Flat, S](isolate: Isolate[S], retrySchedule: Schedule = defaultRetrySchedule)(v: A < (STM & Abort[E] & Async & S))(
        using frame: Frame
    ): A < (S & Async & Abort[E | FailedTransaction]) =
        isolate.use { st =>
            run(retrySchedule)(isolate.resume(st, v)).map(isolate.restore(_, _))
        }

    /** Executes a transactional computation with default retry behavior. This version only supports Abort and Async effects within the
      * transaction, but provides a simpler interface when additional effect isolation is not needed.
      *
      * @param v
      *   The transactional computation to run
      * @return
      *   The result of the computation if successful
      */
    def run[E, A: Flat](v: A < (STM & Abort[E] & Async))(using frame: Frame): A < (Async & Abort[E | FailedTransaction]) =
        run(defaultRetrySchedule)(v)

    /** Executes a transactional computation with custom retry behavior. Like the version above, this only supports Abort and Async effects
      * but allows configuring how transaction conflicts are retried.
      *
      * @param retrySchedule
      *   The schedule for retrying failed transactions
      * @param v
      *   The transactional computation to run
      * @return
      *   The result of the computation if successful
      */
    def run[E, A: Flat](retrySchedule: Schedule)(v: A < (STM & Abort[E] & Async))(
        using frame: Frame
    ): A < (Async & Abort[E | FailedTransaction]) =
        useTid {
            case -1L =>
                // New transaction without a parent, use regular commit flow
                Retry[FailedTransaction](retrySchedule) {
                    withNewTid { tid =>
                        Var.runWith(Map.empty)(v) { (log, result) =>
                            IO.Unsafe {
                                // Attempt to acquire locks and commit the transaction
                                val (locked, unlocked) =
                                    // Sort references by ID to prevent deadlocks
                                    log.toSeq.sortBy(_._1.id)
                                        .span((ref, entry) => ref.lock(entry))

                                if unlocked.nonEmpty then
                                    // Failed to acquire some locks - rollback and retry
                                    locked.foreach((ref, entry) => ref.unlock(entry))
                                    Abort.fail(FailedTransaction(frame))
                                else
                                    // Successfully locked all references - commit changes
                                    locked.foreach((ref, entry) => ref.commit(tid, entry))
                                    // Release all locks
                                    locked.foreach((ref, entry) => ref.unlock(entry))
                                    result
                                end if
                            }
                        }
                    }
                }
            case parent =>
                // Nested transaction inherits parent's transaction context but isolates RefLog.
                // On success: changes propagate to parent. On failure: changes are rolled back
                // without affecting parent's state.
                val result = Var.isolate.update[RefLog].run(v)

                // Can't return `result` directly since it has a pending STM effect
                // but it's safe to cast because, if there's a parent transaction,
                // then there's a frame upper in the stack that will handle the
                // STM effect in the parent transaction's `run`.
                result.asInstanceOf[A < (Async & Abort[E | FailedTransaction])]
        }

    end run

    /** A transactional reference that can be modified within STM transactions. Provides atomic read and write operations with strong
      * consistency guarantees.
      *
      * @param id
      *   Unique identifier for this reference
      * @param state
      *   The current state of the reference
      */
    @safePublish final class Ref[A] private[STM] (
        private[STM] val id: Long,
        @volatile private[STM] var state: Write[A]
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
            Var.use[RefLog] { log =>
                val refAny = this.asInstanceOf[Ref[Any]]
                if log.contains(refAny) then
                    // Ref is already in the log, return value
                    log(refAny).value.asInstanceOf[A]
                else
                    useRequiredTid { tid =>
                        IO {
                            val state = this.state
                            if state.tid > tid then
                                // Early retry if the Ref is concurrently modified
                                retry
                            else
                                // Append Read to the log and return value
                                val entry = Read[Any](state.tid, state.value)
                                Var.setAndThen(log + (refAny -> entry))(state.value)
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
                val refAny = this.asInstanceOf[Ref[Any]]
                if log.contains(refAny) then
                    // Ref is already in the log, update it
                    val prev  = log(refAny)
                    val entry = Write[Any](prev.tid, v)
                    Var.setDiscard(log + (refAny -> entry))
                else
                    IO {
                        useRequiredTid { tid =>
                            val state = this.state
                            if state.tid > tid then
                                // Early retry if the Ref is concurrently modified
                                retry
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
        def update(f: A => A)(using Frame): Unit < STM =
            get.map(f(_)).map(set)

        @tailrec private[STM] def lock(entry: Entry[A])(using AllowUnsafe): Boolean =
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

        private[STM] def commit(tid: Long, entry: Entry[A])(using AllowUnsafe): Unit =
            entry match
                case Write(_, value) =>
                    // Only need to commit Write entries
                    state = Write(tid, value)
                case _ =>

        private[STM] def unlock(entry: Entry[A])(using AllowUnsafe): Unit =
            entry match
                case Read(tid, value) =>
                    // Release read lock
                    discard(lock.decrementAndGet())
                case Write(tid, value) =>
                    // Release write lock
                    lock.set(0)
            end match
        end unlock
    end Ref

    object Ref:
        /** Creates a new transactional reference outside of a transaction.
          *
          * @param value
          *   The initial value
          * @return
          *   A new reference containing the value
          */
        def init[A](value: A)(using Frame): Ref[A] < IO =
            IO.Unsafe(initRefNow(value))
    end Ref

    type RefLog = Map[Ref[Any], Entry[Any]]

    private[kyo] object internal:

        // Unique transaction and reference ID generation
        private val (nextTid, nextRefId) =
            import AllowUnsafe.embrace.danger
            (AtomicLong.Unsafe.init(0), AtomicLong.Unsafe.init(0))

        private val tidLocal = Local.init(-1L)

        def initRefNow[A](tid: Long, value: A)(using AllowUnsafe): Ref[A] =
            new Ref(nextRefId.incrementAndGet(), Write(tid, value))

        def initRefNow[A](value: A)(using AllowUnsafe): Ref[A] =
            new Ref(nextRefId.incrementAndGet(), Write(nextTid.incrementAndGet(), value))

        def withNewTid[A, S](f: Long => A < S)(using Frame): A < (S & IO) =
            IO.Unsafe {
                val tid = nextTid.incrementAndGet()
                tidLocal.let(tid)(f(tid))
            }

        def useTid[A, S](f: Long => A < S)(using Frame): A < S =
            tidLocal.use(f)

        def useRequiredTid[A, S](f: Long => A < S)(using Frame): A < S =
            tidLocal.use {
                case -1L => bug("STM operation attempted outside of STM.run - this should be impossible due to effect typing")
                case tid => f(tid)
            }

        sealed trait Entry[A]:
            def tid: Long
            def value: A

        @safePublish case class Read[A](tid: Long, value: A)  extends Entry[A]
        @safePublish case class Write[A](tid: Long, value: A) extends Entry[A]
    end internal
end STM
