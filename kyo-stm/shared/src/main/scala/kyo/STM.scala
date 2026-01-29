package kyo

import kyo.Result.Failure
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

    /** Monotonic tick value for STM conflict detection */
    private[kyo] opaque type Tick <: Long = Long

    private[kyo] object Tick:
        given CanEqual[Tick, Tick] = CanEqual.derived

        private val counter = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        /** Generate a new tick value */
        def next()(using AllowUnsafe): Tick = counter.incrementAndGet()

        /** Set counter value. For testing only. */
        def testOnlySet(value: Long)(using AllowUnsafe): Unit = counter.set(value)

    end Tick

    private val currentTransaction = Local.initNoninheritable[Maybe[Tick]](Absent)

    /** Use current transaction's tick (fails if not in transaction) */
    private[kyo] inline def withCurrentTransaction[A, S](inline f: Tick => A < S)(using inline frame: Frame): A < (S & STM & Sync) =
        Sync.withLocal(currentTransaction) {
            case Absent        => bug("STM operation attempted outside of STM.run")
            case Present(tick) => f(tick)
        }

    /** Use current transaction, or commit in a new one if not in a transaction */
    private[kyo] inline def withCurrentTransactionOrNew[A, S](inline f: AllowUnsafe ?=> Tick => A < S)(using
        inline frame: Frame
    ): A < (S & Sync) =
        Sync.Unsafe.withLocal(currentTransaction) {
            case Absent        => f(Tick.next())
            case Present(tick) => f(tick)
        }

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
    def run[E: ConcreteTag, A, S](
        using Isolate[S, Async & Abort[E | FailedTransaction], S]
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
    def run[E: ConcreteTag, A, S](
        using isolate: Isolate[S, Async & Abort[E | FailedTransaction], S]
    )(retrySchedule: Schedule)(v: A < (STM & Abort[E] & Async & S))(
        using frame: Frame
    ): A < (S & Async & Abort[E | FailedTransaction]) =
        isolate.capture { st =>
            isolate.restore(run(retrySchedule)(isolate.isolate(st, v)))
        }

    private def run[E: ConcreteTag, A](retrySchedule: Schedule)(v: A < (STM & Abort[E] & Async))(
        using Frame
    ): A < (Async & Abort[E | FailedTransaction]) =
        Sync.Unsafe.withLocal(currentTransaction) {
            case Absent =>
                // Optimistic retry loop: execute the transaction body, attempt commit, retry on conflict
                def loop(schedule: Schedule)(using AllowUnsafe): A < (Async & Abort[E | FailedTransaction]) =
                    val tick = Tick.next()
                    // Consult the schedule for the next retry delay, or fail if exhausted
                    def retry: A < (Async & Abort[E | FailedTransaction]) =
                        schedule.next(Clock.live.unsafe.now()).map { (delay, next) =>
                            Async.delay(delay)(Sync.Unsafe(loop(next)))
                        }.getOrElse {
                            Abort.fail(FailedTransaction())
                        }
                    // Execute the transaction body with a fresh log, capturing the result
                    v.handle(
                        currentTransaction.let(Present(tick)),
                        Abort.run[E | FailedTransaction],
                        Var.runTuple(TRefLog.empty)
                    ).map { (log, result) =>
                        result match
                            case Result.Success(a) =>
                                // Try to commit; retry on conflict
                                if !commit(tick, log) then retry
                                else a
                            case Result.Failure(_: FailedTransaction) =>
                                // Explicit retry via STM.retry or early conflict detection
                                retry
                            case error: Result.Error[?] =>
                                // User error: probe-commit to check log validity.
                                // If stale, retry since the error may be from reading stale state.
                                if !commit(tick, log, probe = true) then
                                    retry
                                else
                                    Abort.error(error.asInstanceOf[Result.Error[E]])
                    }
                end loop
                loop(retrySchedule)
            case _ =>
                // Nested transaction inherits parent's transaction context but isolates RefLog.
                // On success: changes propagate to parent. On failure: changes are rolled back
                // without affecting parent's state.
                val result = TRefLog.isolate.run(v)
                // Safe to cast: the parent STM.run higher in the call stack handles the pending STM effect
                result.asInstanceOf[A < (Async & Abort[E | FailedTransaction])]
        }

    end run

    import CommitBuffer.*

    private def commit[A, S](tick: Tick, log: TRefLog, probe: Boolean = false)(using AllowUnsafe): Boolean =
        val logMap = log.toMap
        logMap.size match
            case 0 =>
                // Nothing to commit
                true
            case 1 =>
                // Fast-path for a single ref
                val (ref, entry) = logMap.head
                entry match
                    case _: TRefLog.Read[?] =>
                        // Read-only: just validate, no locking needed
                        ref.validate(entry)
                    case _ =>
                        // Has write: need to lock and commit
                        val ok = ref.lock(tick, entry)
                        if ok then
                            if !probe then ref.commit(Tick.next(), entry)
                            ref.unlock(entry)
                        ok
                end match
            case size =>
                // Commit multiple refs using a thread-local cached buffer
                CommitBuffer.withBuffer { buffer =>
                    boundary {
                        var hasWrites = false
                        // Pre-validate and dump the log to the buffer
                        logMap.foreachEntry { (ref, entry) =>
                            // This code uses `boundary`/`break` because
                            // foreachEntry is the only way to traverse the
                            // map without allocating tuples, so throwing via `break`
                            // is the workaround to short circuit
                            if !ref.validate(entry) then boundary.break(false)
                            hasWrites |= entry.isInstanceOf[TRefLog.Write[?]]
                            buffer.append(ref, entry)
                        }

                        // Read-only transaction: already validated, no locking needed
                        if !hasWrites then boundary.break(true)

                        // Sort references by id to prevent deadlocks
                        buffer.sort(size)

                        val acquired = buffer.lock(tick, size)
                        if acquired != size then
                            // Failed to acquire some locks - rollback and retry
                            buffer.unlock(acquired)
                            boundary.break(false)
                        end if

                        // Successfully locked all references - commit changes
                        if !probe then buffer.commit(Tick.next(), size)

                        // Release all locks
                        buffer.unlock(size)
                        true
                    }
                }
        end match
    end commit

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
