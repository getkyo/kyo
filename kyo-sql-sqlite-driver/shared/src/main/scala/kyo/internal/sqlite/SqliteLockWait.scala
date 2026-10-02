package kyo.internal.sqlite

import kyo.*
import kyo.ffi.Ffi

/** Where a SQLite call spends a wait for another connection's lock: suspended on its fiber, not asleep on a native thread.
  *
  * SQLite's busy timeout sleeps inside the call. On JS every blocking call runs on Node's libuv worker pool, four threads by default and
  * shared with everything else in the process, so four statements waiting on a lock stall every other SQLite call, including the COMMIT that
  * would release that lock. A call run through [[deferring]] has its busy handler decline the wait (see `kyo_sqlite.c`), returns
  * `SQLITE_BUSY` at once, and is retried here after an `Async.sleep`, on SQLite's own schedule and within the same busy timeout.
  *
  * A retry is sound only when the failed attempt had no effect, so one happens only when all three hold:
  *   - the handler declined a wait. SQLite consults it only where waiting can help, so a busy without it (a deadlock, a stale WAL snapshot)
  *     is final, as it was under the native wait.
  *   - the transaction state is what it was before the call. A statement that failed leaves no trace within a transaction that is still
  *     open; one whose transaction SQLite rolled back cannot be rerun outside it.
  *   - the call has not yet produced a row. A step after one would restart its statement, so such a step goes through [[inNative]], which
  *     keeps the native wait.
  */
private[kyo] object SqliteLockWait:

    /** SQLite's busy-handler delays, in milliseconds, followed by the last one repeated until the timeout is spent. */
    private val Delays: Chunk[Int] = Chunk(1, 2, 5, 10, 15, 20, 25, 25, 25, 50, 50, 100)

    private val Busy = 5

    // Unsafe: one counter for the process, created when the object loads; it is only ever incremented and decremented atomically.
    private val waitingCount: AtomicInt.Unsafe = AtomicInt.Unsafe.init(using AllowUnsafe.embrace.danger)

    /** How many statements are waiting for another connection's lock right now, on their fibers. */
    def waiting(using AllowUnsafe): Int = waitingCount.get()

    /** Whether a result code is `SQLITE_BUSY`, in any of its extended forms. */
    def isBusy(code: Int): Boolean = (code & 0xff) == Busy

    /** Runs `call` with lock waits suspended on the fiber, retrying it while `busy` says it met a lock SQLite would have waited for. */
    def deferring[A](bindings: SqliteBindings, db: Ffi.Handle[SqliteDb], timeoutMillis: Int)(call: => A < Async)(busy: A => Boolean)(using
        Frame
    ): A < Async =
        given AllowUnsafe      = AllowUnsafe.embrace.danger
        def attempt: A < Async = Sync.Unsafe.defer(bindings.busyDefer(db, 1)).andThen(call)
        Sync.Unsafe.defer(bindings.getAutocommit(db)).map { autocommit =>
            def retryable(a: A): Boolean < Sync =
                Sync.Unsafe.defer(busy(a) && bindings.busyDeferred(db) == 1 && bindings.getAutocommit(db) == autocommit)
            attempt.map { first =>
                retryable(first).map {
                    case false => first
                    case true  =>
                        Sync.Unsafe.defer(discard(waitingCount.incrementAndGet())).andThen {
                            Sync.ensure(Sync.Unsafe.defer(discard(waitingCount.decrementAndGet()))) {
                                Loop(first, 0, 0) { (last, retries, slept) =>
                                    val delay = nextDelay(retries, slept, timeoutMillis)
                                    if delay <= 0 then Loop.done(last)
                                    else
                                        Async.sleep(delay.millis).andThen(attempt).map { next =>
                                            retryable(next).map {
                                                case true  => Loop.continue(next, retries + 1, slept + delay)
                                                case false => Loop.done(next)
                                            }
                                        }
                                    end if
                                }
                            }
                        }
                }
            }
        }
    end deferring

    /** Runs `call` with lock waits spent in SQLite's busy handler, for a call a retry would not leave unchanged. */
    def inNative[A](bindings: SqliteBindings, db: Ffi.Handle[SqliteDb])(call: => A < Async)(using Frame): A < Async =
        Sync.Unsafe.defer(bindings.busyDefer(db, 0)(using AllowUnsafe.embrace.danger)).andThen(call)

    /** The next sleep after `retries` retries that slept `slept` ms in total, cut to what remains of `timeoutMillis`; zero or less means the
      * timeout is spent.
      */
    private def nextDelay(retries: Int, slept: Int, timeoutMillis: Int): Int =
        val delay = Delays(math.min(retries, Delays.size - 1))
        if slept + delay > timeoutMillis then timeoutMillis - slept else delay

end SqliteLockWait
