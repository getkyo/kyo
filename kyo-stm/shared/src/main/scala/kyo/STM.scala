package kyo

import kyo.Result.Failure
import scala.annotation.tailrec
import scala.util.boundary

/** Software Transactional Memory (STM) provides concurrent access to shared state using optimistic locking. Rather than acquiring locks
  * upfront, transactions execute speculatively and automatically retry if conflicts are detected during commit. While this enables better
  * composability than manual locking, applications must be designed to handle potentially frequent transaction retries.
  *
  * > IMPORTANT: Transactions are atomic, isolated, and composable but may retry multiple times before success. Side effects (like I/O)
  * inside transactions must be used with caution as they will be re-executed on retry. Pure operations that only modify transactional
  * references are safe and encouraged, while external side effects should be performed after the transaction commits.
  *
  * The core operations are:
  *   - TRef.init creates transactional references that can be shared between threads
  *   - TRef.get and TRef.set read and modify references within transactions
  *   - STM.run executes transactions that either fully commit or rollback
  *   - STM.retry and STM.retryIf provide manual control over transaction retry behavior
  *   - Configurable retry schedules via STM.run's retrySchedule parameter
  *
  * The implementation uses optimistic execution with lock-based validation during commit:
  *   - Transactions execute without acquiring locks, tracking reads and writes in a local log
  *   - During commit, read-write locks are acquired on affected TRefs to ensure consistency:
  *     - Multiple readers can hold shared locks on a TRef during commit
  *     - Writers require an exclusive lock during commit
  *     - No global locks are used - operations on different refs can commit independently
  *     - Lock acquisition is ordered by TRef identity to prevent deadlocks
  *     - Early conflict detection aborts transactions that would fail validation
  *
  * STM is most effective for operations that rarely conflict and complete quickly. Long-running transactions or high contention scenarios
  * may face performance challenges from repeated retries. The approach particularly excels at read-heavy workloads due to its support for
  * concurrent readers, while write-heavy workloads may experience more contention due to the need for exclusive write access. The
  * fine-grained locking strategy means that transactions only conflict if they actually touch the same references, allowing for high
  * concurrency when different transactions operate on different refs.
  */
opaque type STM <: (Var[TRefLog] & Abort[FailedTransaction] & Async) =
    Var[TRefLog] & Abort[FailedTransaction] & Async

object STM:

    /** The default retry schedule for failed transactions */
    val defaultRetrySchedule = Schedule.fixed(1.millis).jitter(0.5).take(20)

    /** Forces a transaction retry by aborting the current transaction and rolling back all changes. This is useful when a transaction
      * detects that it cannot proceed due to invalid state.
      *
      * @return
      *   Nothing, as this operation always aborts the transaction
      */
    def retry(using Frame): Nothing < STM = Abort.fail(FailedTransaction())

    /** Conditionally retries a transaction based on a boolean condition. If the condition is true, the transaction will be retried.
      * Otherwise, execution continues normally.
      *
      * @param cond
      *   The condition that determines whether to retry
      */
    def retryIf(cond: Boolean)(using Frame): Unit < STM = Abort.when(cond)(FailedTransaction())

    /** Executes a transactional computation with state isolation and the default retry schedule.
      *
      * @param isolate
      *   The isolation scope for the transaction
      * @param v
      *   The transactional computation to run
      * @return
      *   The result of the computation if successful
      */
    def run[E: SafeClassTag, A, S](
        using Isolate.Stateful[S, Async & Abort[E | FailedTransaction]]
    )(v: A < (STM & Abort[E] & Async & S))(using frame: Frame): A < (S & Async & Abort[E | FailedTransaction]) =
        run(defaultRetrySchedule)(v)

    /** Executes a transactional computation with state isolation and the a custom retry schedule.
      *
      * @param retrySchedule
      *   The schedule for retrying failed transactions
      * @param v
      *   The transactional computation to run
      * @return
      *   The result of the computation if successful
      */
    def run[E: SafeClassTag, A, S](
        using isolate: Isolate.Stateful[S, Async & Abort[E | FailedTransaction]]
    )(retrySchedule: Schedule)(v: A < (STM & Abort[E] & Async & S))(
        using frame: Frame
    ): A < (S & Async & Abort[E | FailedTransaction]) =
        isolate.capture { st =>
            isolate.restore(run(retrySchedule)(isolate.isolate(st, v)))
        }

    private def run[E: SafeClassTag, A](retrySchedule: Schedule)(v: A < (STM & Abort[E] & Async))(
        using Frame
    ): A < (Async & Abort[E | FailedTransaction]) =
        TID.useIO {
            case -1L =>
                TID.useNew { tid =>
                    v.handle(
                        Abort.recoverError[E] { error =>
                            // Retry arbitrary E failures in case the transaction is inconsistent
                            Var.use[TRefLog] { log =>
                                Sync.Unsafe {
                                    if !commit(tid, log, probe = true) then
                                        // The ref log shows inconsistency, retry the transaction
                                        Abort.fail(FailedTransaction(Present(error)))
                                    else
                                        // No inconsistency detected, just propagate the error
                                        Abort.error(error)
                                }
                            }
                        },
                        Var.runTuple(TRefLog.empty)
                    ).map { (log, result) =>
                        Sync.Unsafe {
                            if !commit(tid, log) then
                                Abort.fail(FailedTransaction())
                            else
                                result
                        }
                    }
                }.handle(
                    Retry[FailedTransaction](retrySchedule)
                )
            case parent =>
                // Nested transaction inherits parent's transaction context but isolates RefLog.
                // On success: changes propagate to parent. On failure: changes are rolled back
                // without affecting parent's state.
                val result = TRefLog.isolate.run(v)

                // Can't return `result` directly since it has a pending STM effect
                // but it's safe to cast because, if there's a parent transaction,
                // then there's a frame upper in the stack that will handle the
                // STM effect in the parent transaction's `run`.
                result.asInstanceOf[A < (Async & Abort[E | FailedTransaction])]
        }

    end run

    private def commit[A, S](tid: Long, log: TRefLog, probe: Boolean = false)(using AllowUnsafe): Boolean =
        val logMap = log.toMap
        logMap.size match
            case 0 =>
                // Nothing to commit
                true
            case 1 =>
                // Fast-path for a single ref
                val (ref, entry) = logMap.head
                // No need to pre-validate since `lock` validates and
                // there's a single ref
                val ok = ref.lock(entry)
                if ok then
                    if !probe then ref.commit(tid, entry)
                    ref.unlock(entry)
                ok
            case size =>
                // Commit multiple refs
                // Flattened representation of the log
                val array = new Array[Any](size * 2)

                boundary {

                    var i = 0
                    // Pre-validate and dump the log to the flat array
                    logMap.foreachEntry { (ref, entry) =>
                        // This code uses `boundary`/`break` because
                        // foreachEntry is the only way to traverse the
                        // map without allocating tuples, so throwing via `break`
                        // is the workaround to short circuit
                        if !ref.validate(entry) then boundary.break(false)
                        array(i) = ref
                        array(i + 1) = entry
                        i += 2
                    }

                    // Sort references by identity to prevent deadlocks
                    quickSort(array, size)

                    // Convenience accessors to the flat log
                    inline def ref(idx: Int)   = array(idx * 2).asInstanceOf[TRef[Any]]
                    inline def entry(idx: Int) = array(idx * 2 + 1).asInstanceOf[TRefLog.Entry[Any]]

                    @tailrec def lock(idx: Int): Int =
                        if idx == size then size
                        else if !ref(idx).lock(entry(idx)) then idx
                        else lock(idx + 1)

                    @tailrec def unlock(idx: Int, upTo: Int): Unit =
                        if idx < upTo then
                            ref(idx).unlock(entry(idx))
                            unlock(idx + 1, upTo)

                    @tailrec def commit(idx: Int): Unit =
                        if idx < size then
                            ref(idx).commit(tid, entry(idx))
                            commit(idx + 1)

                    val acquired = lock(0)
                    if acquired != size then
                        // Failed to acquire some locks - rollback and retry
                        unlock(0, acquired)
                        boundary.break(false)
                    end if

                    // Successfully locked all references - commit changes
                    if !probe then commit(0)

                    // Release all locks
                    unlock(0, size)
                    true
                }
        end match
    end commit

    private def quickSort(array: Array[Any], size: Int): Unit =
        def swap(i: Int, j: Int): Unit =
            val temp = array(i)
            array(i) = array(j)
            array(j) = temp
            val temp2 = array(i + 1)
            array(i + 1) = array(j + 1)
            array(j + 1) = temp2
        end swap

        def getHash(idx: Int): Int =
            array(idx * 2).hashCode()

        @tailrec def partitionLoop(low: Int, hi: Int, pivot: Int, i: Int, j: Int): Int =
            if j >= hi then
                swap(i * 2, pivot * 2)
                i
            else if getHash(j) < getHash(pivot) then
                swap(i * 2, j * 2)
                partitionLoop(low, hi, pivot, i + 1, j + 1)
            else
                partitionLoop(low, hi, pivot, i, j + 1)

        def partition(low: Int, hi: Int): Int =
            partitionLoop(low, hi, hi, low, low)

        def loop(low: Int, hi: Int): Unit =
            if low < hi then
                val p = partition(low, hi)
                loop(low, p - 1)
                loop(p + 1, hi)

        if size > 0 then
            loop(0, size - 1)
    end quickSort
end STM

/** A FailedTransaction exception that is thrown when a transaction fails to commit. Contains the frame where the failure occurred.
  */
final class FailedTransaction(error: Maybe[Result.Error[?]] = Absent)(using Frame)
    extends KyoException(
        s"STM transaction failed!",
        error.fold("") {
            _.failureOrPanic match
                case ex: Throwable => ex
                case _             => error.show
        }
    )
