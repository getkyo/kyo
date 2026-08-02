package kyo.internal.client

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.SqlConfig.TlsMode
import kyo.db.Connection
import kyo.net.NetAddress
import kyo.net.NetTlsConfig
import kyo.net.internal.ConnectionPool
import scala.util.control.NonFatal

/** The connection pool, and the four layers every statement passes through: retry, slot, lease, reclaim.
  *
  * Generic over the connection type, and blind to which engine is behind it: a [[Connection.Factory]] supplied at construction is the only
  * thing here that knows how to open one, and [[kyo.db.Connection]] is the only vocabulary the layers speak. That is what lets one
  * pool serve both engines rather than one pool per engine duplicating the retry, slot, and timeout policy.
  *
  * ==The four layers==
  *
  *   1. Retry, when `retrySchedule` is set: re-runs the layers below it for a [[kyo.SqlConnectionException]], recording each re-entry.
  *   2. Slot: a per-address [[kyo.Channel]] of `maxConnections` permits, so concurrency is bounded before any connection is touched, and
  *      `acquireTimeout` bounds the wait for a permit.
  *   3. Lease: takes an idle connection or opens one, hands it to the caller, and decides on exit whether it goes back, gets destroyed, or
  *      gets reclaimed.
  *   4. Reclaim: after an interrupt, tells the server to stop the statement and puts the session back to idle, all inside one
  *      `cancelTimeout` budget.
  *
  * ==Reclaim, and why it is spawned==
  *
  * When the fiber holding a lease is interrupted while a statement is in flight, that fiber must return at once: making it wait on cancel
  * wire work would defeat the interrupt. So the exit path hands the reclaim chain to a fresh unsupervised carrier and returns. The chain then
  * runs [[kyo.db.Connection.cancelInFlight]], then [[kyo.db.Connection.drainToIdle]], and only then
  * [[kyo.db.Connection.rollbackIfOpenTransaction]], all under a single `cancelTimeout`; the connection goes back to the pool only if
  * the drain reports the session reusable, and is destroyed otherwise.
  *
  * The drain precedes the rollback, and that order is load-bearing rather than incidental. The cancelled statement has usually left unread
  * response bytes on the socket, so writing a ROLLBACK before draining them puts the next read out of frame and desynchronises the
  * connection. Draining first is also what makes the rollback conditional meaningful: a drain that reports the session unusable skips the
  * rollback entirely, because there is nothing to write to and destroying the connection ends the transaction server-side anyway.
  *
  * Between the interrupt and that decision the connection is quarantined: it is in neither the idle ring nor closed. `cancelsInFlight` is
  * that state made observable, which is why [[closeAll]] waits on it as well as on the slot channels: a reclaim spawned this way can outlive
  * the [[closeAll]] that raced it, and a connection handed back to a pool that has finished closing is destroyed rather than pooled.
  *
  * ==Unsafe usage==
  *
  * [[ConnectionPool]]'s `poll`, `release`, `discard`, `tryReserve`, `unreserve`, and `close` all require `AllowUnsafe`: they CAS directly on
  * atomic longs in a lock-free ring buffer. Every call site carries a `// Unsafe:` justification. The `isAlive` and `discard` callbacks
  * [[SqlConnectionPool.init]] hands the pool are invoked from inside the pool while already in an `AllowUnsafe` context and outside any fiber
  * suspension, which is why they use [[kyo.db.Connection.closeNow]] and `Sync.Unsafe.evalOrThrow` rather than suspending.
  *
  * Nothing that reaches the server may be written into either callback, and that is an invariant rather than a preference. A round trip
  * answered from a position that cannot suspend can only be answered by parking the carrier, which raises
  * `UnsupportedOperationException` on JS and Wasm and, on JS, would deadlock even if it did not, because the callback holds the event loop
  * the round trip needs. The `connectionTestQuery` probe therefore lives in [[healthy]], one layer out in [[acquireOrReserve]], where a
  * suspension is legal on every platform.
  */
final private[kyo] class SqlConnectionPool[C <: Connection](
    private val pool: ConnectionPool[SqlConnectionPool.Endpoint, C],
    private val slotChans: ConcurrentHashMap[SqlConfig.Address, Channel[Unit]],
    private val cancelsInFlight: AtomicInt.Unsafe,
    private val drainPolls: AtomicInt.Unsafe,
    private val factory: Connection.Factory[C],
    private val connectTimeout: Maybe[Duration],
    val metrics: SqlClient.Metrics
):

    // --- Leases ---

    /** Runs `op` on a pooled connection under the statement policy: retry, per-statement timeout, and the query instruments.
      *
      * The entry point for every statement a caller sends without holding a connection of its own.
      */
    def leaseStatement[A](address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(
        op: C => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        metrics.timedQuery(retrying(address, password, config)(conn => bounded(config)(op(conn))))

    /** Runs `op` on a connection pinned for the whole call, with no retry and no per-statement timeout.
      *
      * The entry point for anything that needs the same session across several statements: a transaction, an advisory lock, a `COPY`, a
      * session reset. Retry is wrong here because re-running the body would re-run work the first attempt may have committed, and a
      * per-statement timeout is wrong because the unit of work is the whole body.
      */
    def lease[A, S](address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(
        op: C => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        Clock.stopwatch.flatMap { leaseClock =>
            // Unsafe: getOrCreateSlotChan runs Channel.initUnscoped synchronously on the hot path.
            // Unsafe: lazy initialisation of a Channel that is pure-Sync.
            Sync.Unsafe.defer(getOrCreateSlotChan(address, config.maxConnections)).flatMap { slotCh =>
                withSlot(slotCh, config) {
                    acquireAndRun(address, password, config, leaseClock)(op)
                }
            }
        }

    /** Acquires a connection held for the lifetime of the enclosing [[kyo.Scope]], for a stream that reads across many round-trips.
      *
      * Retry is absent by construction: resuming a stream mid-cursor would need a cursor position the [[kyo.Stream]] API does not carry.
      *
      * One stopwatch serves both lease instruments, because both measure from the moment the caller asked. The wait for a permit ends where
      * it is observed below, and the wait for a connection ends in [[acquireScoped]]; the statement path takes two stopwatches only because
      * its two intervals are measured in different methods with no shared origin.
      */
    def leaseScoped(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): C < (Async & Abort[SqlException] & Scope) =
        val netKey = SqlConnectionPool.Endpoint(address, config)
        Clock.stopwatch.flatMap { leaseClock =>
            // Unsafe: getOrCreateSlotChan and every pool operation require AllowUnsafe.
            // Unsafe: bridging to kyo-net ConnectionPool.
            Sync.Unsafe.defer(getOrCreateSlotChan(address, config.maxConnections)).flatMap { slotCh =>
                // Take the slot and register its release in Scope.ensure BEFORE attempting to connect. If the
                // connect fails, the surrounding Scope still closes and still returns the slot, which is what
                // prevents a slot leak: without it, a connect failure after a server restart would strand the slot
                // and eventually deadlock the pool.
                Abort.run[SqlException](takeSlot(slotCh, config)).flatMap {
                    case Result.Failure(e: SqlConnectionAcquireTimeoutException) =>
                        Log.warn(
                            s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                        ).andThen(Abort.fail(e))
                    case Result.Failure(e) => Abort.fail(e)
                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    case Result.Success(()) =>
                        leaseClock.elapsed.flatMap(dur => metrics.recordPoolAcquireWait(dur.toMillis)).andThen {
                            Scope.ensure {
                                Sync.Unsafe.defer {
                                    discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                                }
                            }.andThen(acquireScoped(address, password, netKey, config, leaseClock))
                        }
                }
            }
        }
    end leaseScoped

    // --- Pool lifecycle ---

    /** Opens `n` connections concurrently and releases them to the idle ring, so the first statement does not pay for them.
      *
      * Every connect runs under its own `Abort.run` so one failure does not short-circuit the rest, and the successes are closed before the
      * first failure propagates, which is what keeps a partial warm-up from leaking file descriptors. The typed [[kyo.SqlException]] is
      * preserved as-is: a caller matching on [[kyo.SqlServerException]] for an auth or permission failure must see that class and not a
      * blanket connection wrap.
      */
    def warmUp(address: SqlConfig.Address, password: Maybe[String], n: Int, config: SqlConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        if n <= 0 then ()
        else
            val netKey = SqlConnectionPool.Endpoint(address, config)
            Async.fill(n, concurrency = n) {
                Abort.run[SqlException](connect(address, password, config))
            }.flatMap { results =>
                val successes = results.collect { case Result.Success(conn) => conn }
                val firstFailure = results.collectFirst {
                    case Result.Failure(e: SqlException) => e
                    case Result.Panic(t)                 => SqlConnectionWarmupPanicException(t)
                }
                firstFailure match
                    case None =>
                        // Unsafe: pool.release requires AllowUnsafe.
                        Sync.Unsafe.defer(successes.foreach(conn => pool.release(netKey, conn)))
                    case Some(e) =>
                        // Unsafe: closeNow is the non-suspending close; this runs on the failure path where
                        // the only remaining job is to not leak the descriptors already opened.
                        Sync.Unsafe.defer(successes.foreach(_.closeNow)).andThen(Abort.fail(e))
                end match
            }

    /** Marks the pool closed, waits up to `gracePeriod` for in-flight work to finish, then force-closes whatever is left.
      *
      * The wait ends when every slot channel is back at capacity AND no reclaim is still running, because either one still holds a
      * connection. After the grace period the remaining connections are closed synchronously rather than through `Kyo.foreach`, so an
      * interrupt landing mid-drain cannot leave a connection open.
      */
    def closeAll(gracePeriod: Duration)(using Frame): Unit < Async =
        // Unsafe: pool.close() is a lock-free drain and requires AllowUnsafe.
        // The pool is marked closed first so tryReserve stops handing out slots and no new connection opens.
        // The slot channels stay open through the grace period so in-flight callers can hand their slots back.
        Sync.Unsafe.defer(pool.close()).flatMap { idleConns =>
            // The force-close runs as a Sync.ensure finalizer, not a plain andThen, so a fiber interrupt during
            // the grace poll still closes the idle connections pool.close() already extracted. Without it the
            // interrupt abandons the continuation and leaks them, and the pool is already closed so no retry
            // reclaims them. Sync.ensure covers the interrupt and panic edges; drain carries no typed abort (it
            // swallows its own Timeout), so those are the only edges. The force-close stays one Sync.Unsafe.defer,
            // so it is atomic once reached.
            Sync.ensure {
                // Unsafe: channel.close and the final connection closes require AllowUnsafe.
                Sync.Unsafe.defer {
                    slotChans.forEach { (_, ch) =>
                        discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.close)))
                    }
                    slotChans.clear()
                    idleConns.foreach(_.closeNow)
                }
            }(drain(gracePeriod))
        }

    /** How many reclaim chains are running right now. Zero once every interrupted lease has been resolved. */
    // Unsafe: read-only view of an already-Unsafe atomic, no suspension needed.
    def cancelsInFlightCount(using AllowUnsafe): Int = cancelsInFlight.get()

    /** How many times a grace-period drain has begun. Zero until [[closeAll]] has passed connection extraction and
      * entered the drain, so a close-interrupt test waits on this before interrupting to land the interrupt inside
      * the grace window rather than before extraction. Read-only, it gates no behavior.
      */
    // Unsafe: read-only view of an already-Unsafe atomic, no suspension needed.
    def drainPollCount(using AllowUnsafe): Int = drainPolls.get()

    /** Permits currently available in `address`'s slot channel, or [[Absent]] if no statement has reached that address yet.
      *
      * The direct signal for "did that lease give its permit back". A leak is otherwise only observable by exhausting the pool and watching a
      * later acquire time out, which needs the caller to know the real capacity and to spend `acquireTimeout` finding out. This accessor makes
      * a returned permit directly observable.
      */
    // Unsafe: read-only view of the lock-free slot map and its channel size, no suspension needed.
    private[kyo] def slotPermits(address: SqlConfig.Address)(using Frame, AllowUnsafe): Maybe[Int] =
        Maybe(slotChans.get(address)).map(ch => Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.size)).getOrElse(0))

    /** Capacity of `address`'s slot channel, which is what [[slotPermits]] returns to when every lease has resolved. */
    // Unsafe: read-only view of the lock-free slot map, no suspension needed.
    private[kyo] def slotCapacity(address: SqlConfig.Address)(using AllowUnsafe): Maybe[Int] =
        Maybe(slotChans.get(address)).map(_.capacity)

    private def drain(gracePeriod: Duration)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then ()
        else
            // Snapshotted before the wait, so a channel created for a new address during the grace period is not
            // waited on. One client serves one address, so there is nothing else to snapshot in practice; the final
            // sweep in closeAll closes whatever appeared regardless.
            val chanSnapshots: Chunk[Channel[Unit]] =
                val buf = ChunkBuilder.init[Channel[Unit]]
                slotChans.forEach((_, ch) => buf.addOne(ch))
                buf.result()
            end chanSnapshots
            // Each slot channel starts at capacity and an in-flight caller holds a taken slot, so a channel
            // back at capacity means every caller that took from it has returned.
            def allDrained(using Frame): Boolean < Sync =
                Sync.Unsafe.defer {
                    cancelsInFlight.get() == 0 && chanSnapshots.forall { ch =>
                        Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.size)) match
                            case Result.Success(sz) => sz >= ch.capacity
                            case _                  => true // already closed, treat as drained
                    }
                }
            def pollLoop(using Frame): Unit < Async =
                allDrained.flatMap {
                    case true  => ()
                    case false => Async.sleep(10.millis).andThen(pollLoop)
                }
            // The timeout is ignored on purpose: after the grace period the caller force-closes regardless.
            // drainPolls records that the drain has begun; closeAll's pool.close() extraction is already done by
            // here, so a close-interrupt test waits on it to land its interrupt inside the grace window rather
            // than before extraction. Read-only bookkeeping, it gates nothing.
            Sync.Unsafe.defer(discard(drainPolls.incrementAndGet())).andThen {
                Abort.run[Timeout](Async.timeout(gracePeriod)(pollLoop)).unit
            }
        end if
    end drain

    // --- Layer 1: retry ---

    private def retrying[A](address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(
        op: C => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        config.retrySchedule match
            case Absent            => lease(address, password, config)(op)
            case Present(schedule) =>
                // The counter is incremented before each attempt, so the first one (prev == 0) is not a retry
                // and every later one is.
                AtomicInt.init(0).flatMap { counter =>
                    Retry[SqlConnectionException](schedule) {
                        counter.getAndUpdate(_ + 1).flatMap { prev =>
                            val recordRetry: Unit < (Async & Abort[SqlException]) =
                                if prev == 0 then ()
                                else
                                    Log.warn(
                                        s"kyo.sql: retrying after connection failure attempt=$prev schedule=$schedule"
                                    ).andThen(metrics.recordRetry)
                            recordRetry.andThen(lease(address, password, config)(op))
                        }
                    }
                }

    /** Bounds one statement by `queryTimeout`.
      *
      * The failure is a connection-level one rather than a request-level one on purpose: an interrupted statement leaves the wire with an
      * undrained response, so the exit path must treat the connection as needing a reclaim. Filing it as a request failure would mark the
      * connection reusable and hand the next borrower a half-consumed response.
      */
    private def bounded[A](config: SqlConfig)(body: A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        if config.queryTimeout == Duration.Infinity then body
        else
            Async.timeoutWithError(
                config.queryTimeout,
                Result.Failure(SqlConnectionQueryTimeoutException(config.queryTimeout))
            )(body)

    // --- Layer 2: slot ---

    // Unsafe: requires AllowUnsafe to call Sync.Unsafe.evalOrThrow.
    // Unsafe: lazy resource initialisation run synchronously from inside Sync.Unsafe.defer.
    private def getOrCreateSlotChan(address: SqlConfig.Address, maxConns: Int)(using Frame, AllowUnsafe): Channel[Unit] =
        slotChans.computeIfAbsent(
            address,
            _ =>
                // Exactly `maxConnections` permits, because this channel is what actually bounds concurrency and a
                // caller setting 1 means it. Clamping the value here as well would silently hand a pool asked for 1
                // two concurrent sessions, on a public field.
                //
                // This is the only bound on how many connections may be IN USE, and the idle ring bounds only how
                // many may be KEPT. They are allowed to differ, and there are two independent reasons they do.
                // `ConnectionPool.init` requires `maxConnectionsPerHost >= 2` (`ConnectionPool.scala:90`), which the
                // Vyukov buffer needs, so a pool asked for 1 still gets a ring of 2. And the ring is keyed by
                // `Endpoint`, which includes the transport security, so one address under several TLS configs owns
                // several buckets. Both raise retention above `maxConnections` and neither raises concurrency,
                // because every lease passes through this channel first.
                val capacity = maxConns.max(1)
                val ch       = Sync.Unsafe.evalOrThrow(Channel.initUnscoped[Unit](capacity))
                var i        = 0
                while i < capacity do
                    discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.offer(()))))
                    i += 1
                ch
        )

    private def takeSlot(slotCh: Channel[Unit], config: SqlConfig)(using Frame): Unit < (Async & Abort[SqlException]) =
        val take: Unit < (Async & Abort[SqlException]) =
            Abort.run[Closed](slotCh.take).flatMap {
                case Result.Success(()) => ()
                case Result.Failure(_)  => Abort.fail(SqlConnectionPoolClosedException())
                case Result.Panic(t)    => Abort.error(Result.Panic(t))
            }
        if config.acquireTimeout == Duration.Infinity then take
        else
            Async.timeoutWithError(
                config.acquireTimeout,
                Result.Failure(SqlConnectionAcquireTimeoutException(config.acquireTimeout))
            )(take)
        end if
    end takeSlot

    private def withSlot[A, S](slotCh: Channel[Unit], config: SqlConfig)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        // The stopwatch times the wait for a permit, which is the time spent blocked on a saturated pool.
        Clock.stopwatch.flatMap { sw =>
            Abort.run[SqlException](takeSlot(slotCh, config)).flatMap {
                case Result.Failure(e: SqlConnectionAcquireTimeoutException) =>
                    Log.warn(
                        s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                    ).andThen(Abort.fail(e))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                case Result.Success(()) =>
                    sw.elapsed.flatMap(dur => metrics.recordPoolAcquireWait(dur.toMillis)).andThen {
                        // The permit is returned by a Scope finalizer, not a `Sync.ensure` one. `Sync.ensure` covers
                        // the interrupt and panic edges but NOT a typed `Abort` handled outside its region: that
                        // finalizer parks until the calling FIBER ends, so an ordinary statement failure would strand
                        // the permit until the caller's whole program finished. `Scope.run` closes its scope as part
                        // of evaluating the body, so the finalizer fires on that edge too.
                        Scope.run {
                            Scope.ensure {
                                Sync.Unsafe.defer {
                                    discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                                }
                            }.andThen {
                                Abort.run[SqlException](body).flatMap {
                                    case Result.Success(a) => a
                                    case Result.Failure(e: SqlServerException) =>
                                        Log.error(s"kyo.sql: server error sqlState=${e.sqlState} msg=${e.serverMessage}")
                                            .andThen(Abort.fail[SqlException](e))
                                    case Result.Failure(e) => Abort.fail[SqlException](e)
                                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                                }
                            }
                        }
                    }
            }
        }
    end withSlot

    // --- Layer 3: lease ---

    private def acquireAndRun[A, S](
        address: SqlConfig.Address,
        password: Maybe[String],
        config: SqlConfig,
        leaseClock: Clock.Stopwatch
    )(op: C => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        val netKey = SqlConnectionPool.Endpoint(address, config)
        acquireOrReserve(netKey, config).flatMap {
            case Present(conn) =>
                metrics.recordAcquire
                    .andThen(leaseClock.elapsed.flatMap(d => metrics.recordLeaseAcquired(d.toMillis)))
                    .andThen(onLease(netKey, conn, config)(op(conn)))
            case Absent =>
                // Per-attempt release of the in-flight reservation, on every edge including an interrupt.
                //
                // Under Retry the body is re-evaluated per attempt, and a finalizer registered against the outer
                // computation fires once, on the outermost completion. After N failed connect attempts the pool's
                // in-flight count would sit at maxConnections, tryReserve would return false forever, and the next
                // acquireOrReserve would spin on poll-Absent / tryReserve-false: an unbounded CPU spin that hangs the
                // fiber and burns a core. Resolving per attempt is what keeps that count honest, and the original
                // failure is re-raised unchanged for the caller and for Retry.
                // Unsafe: pool.unreserve CASes the ring's in-flight count, an AllowUnsafe pool operation like the rest.
                resolvingOnce(_ => Sync.Unsafe.defer(pool.unreserve(netKey)))(
                    connectAndRun(address, password, netKey, config, leaseClock)(op)
                )
        }
    end acquireAndRun

    private def connectAndRun[A, S](
        address: SqlConfig.Address,
        password: Maybe[String],
        netKey: SqlConnectionPool.Endpoint,
        config: SqlConfig,
        leaseClock: Clock.Stopwatch
    )(op: C => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        connect(address, password, config).flatMap { conn =>
            Log.debug(
                s"kyo.sql: opened connection id=${conn.id} host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
            )
                .andThen(metrics.recordAcquire)
                .andThen(leaseClock.elapsed.flatMap(d => metrics.recordLeaseAcquired(d.toMillis)))
                .andThen(onLease(netKey, conn, config)(op(conn)))
        }

    /** What one attempt at the ring found, so the loop below can branch on it outside the unsafe block. */
    private enum RingAttempt derives CanEqual:
        case Took(conn: C)
        case Reserved
        case PoolClosed
        case InTransit
    end RingAttempt

    /** Takes an idle connection, or a reservation to open one, suspending between attempts.
      *
      * Three outcomes. The closed-pool outcome is load-bearing: [[ConnectionPool]]'s `poll` and `tryReserve` both short-circuit to empty and
      * false once it is closed, so a loop whose only exits are those two can never leave. Both ways in are reachable. [[closeAll]] keeps the
      * slot channels open through the grace period so in-flight callers can hand their permits back, and it clears them afterwards, which means
      * a later lease recreates a fully-permitted channel; either way a statement arrives here holding a permit against a pool that will never
      * serve it. Without the closed-pool exit this loop has no suspension point once the pool is closed, so it would burn a carrier at 100% CPU
      * and, on JS's single thread, hang the runtime outright. [[kyo.SqlClient.close]] documents post-close operations as surfacing a
      * [[kyo.SqlConnectionException]], which is what [[kyo.SqlConnectionPoolClosedException]] delivers.
      *
      * The in-transit retry suspends rather than spinning. `tryReserve` refusing while the ring is empty means another fiber is mid-flight
      * putting a connection in, which resolves in microseconds, but only if this fiber yields so that fiber can run.
      *
      * The retry is also bounded, because "another fiber is about to fill the ring" is not the only way to reach it: a stranded reservation
      * makes `tryReserve` refuse permanently with an empty ring, and no future event releases it. Bounding by `acquireTimeout` turns that from
      * an unkillable loop into the same timeout a caller waiting for a permit already gets.
      *
      * A connection the ring hands over is probed here rather than inside the ring's callback, because a `connectionTestQuery` is a server
      * round trip and this is the first position on the path that may suspend. [[healthy]] carries that, along with the destroy a connection
      * failing the probe needs: the ring has already vacated its slot by then, so its own discard callback will never fire for it. A failed
      * probe re-enters the loop under the same `acquireTimeout` budget the in-transit path uses, which is what
      * [[withinAcquireBudget]] exists to share.
      */
    private def acquireOrReserve(netKey: SqlConnectionPool.Endpoint, config: SqlConfig)(using
        Frame
    ): Maybe[C] < (Async & Abort[SqlException]) =
        Clock.stopwatch.flatMap { transitClock =>
            Loop(()) { _ =>
                // Unsafe: isClosed, poll and tryReserve are lock-free ring operations requiring AllowUnsafe,
                // and none of them suspends.
                Sync.Unsafe.defer {
                    if pool.isClosed then RingAttempt.PoolClosed
                    else
                        pool.poll(netKey) match
                            case Present(conn) => RingAttempt.Took(conn)
                            case Absent        => if pool.tryReserve(netKey) then RingAttempt.Reserved else RingAttempt.InTransit
                }.flatMap {
                    case RingAttempt.Took(conn) =>
                        healthy(conn, config).flatMap {
                            case true  => Loop.done[Unit, Maybe[C]](Present(conn))
                            case false => withinAcquireBudget(transitClock, config).andThen(Loop.continue(()))
                        }
                    case RingAttempt.Reserved   => Loop.done[Unit, Maybe[C]](Absent)
                    case RingAttempt.PoolClosed => Abort.fail(SqlConnectionPoolClosedException())
                    case RingAttempt.InTransit =>
                        withinAcquireBudget(transitClock, config).andThen(Async.sleep(1.milli).andThen(Loop.continue(())))
                }
            }
        }

    /** Raises [[kyo.SqlConnectionAcquireTimeoutException]] once this acquire has spent its budget.
      *
      * Both paths that re-enter the ring consult it. The in-transit path always could; the probe path needs it too, because a peer releasing
      * dead connections as fast as this fiber discards them would otherwise loop forever on a pool that will never serve it.
      */
    private def withinAcquireBudget(transitClock: Clock.Stopwatch, config: SqlConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        if config.acquireTimeout == Duration.Infinity then ()
        else
            transitClock.elapsed.flatMap { waited =>
                if waited >= config.acquireTimeout then Abort.fail(SqlConnectionAcquireTimeoutException(config.acquireTimeout))
                else ()
            }

    /** Runs the configured `connectionTestQuery` on a connection just taken from the idle ring, and destroys it when the probe does not come
      * back clean.
      *
      * Here rather than in the ring's `isAlive` callback because a probe is a server round trip. That callback is a plain `C => Boolean`, so a
      * query in it can only be answered by parking the carrier, and that raises `UnsupportedOperationException` on JS and Wasm. The callback
      * would swallow that failure and report every healthy connection dead, so configuring a probe would discard and reopen every connection
      * and the query would never reach the server. `pool.poll`'s only caller is this method's own loop, which is already inside `Async`, so
      * the probe has a legal place to suspend and needs no blocking bridge.
      *
      * [[Absent]] answers `true` without suspending, so the default path costs a match and nothing else. Adding a round trip there would add one
      * to every statement that reuses a pooled connection; a caller who wants a real server probe has [[kyo.SqlClient.ping]] and
      * [[kyo.SqlClient.isAlive]].
      *
      * The connection is out of the ring and owned by nobody while the probe runs, so an interrupt or a timeout landing here would leak the
      * socket and the place it held. [[resolvingOnce]] closes that: the destroy runs on whichever edge the probe leaves by, and exactly once.
      *
      * A panic is re-raised rather than read as "not alive". Reporting a `StackOverflowError` as a dead connection would make a platform
      * fault silent, and the three-way match is the one [[takeSlot]] and [[withSlot]] already use for the same reason.
      */
    private def healthy(conn: C, config: SqlConfig)(using Frame): Boolean < (Async & Abort[SqlException]) =
        config.connectionTestQuery match
            case Absent => true
            case Present(sql) =>
                Log.use { logger =>
                    Abort.run[SqlException] {
                        resolvingOnce { error =>
                            if error.isEmpty then ()
                            else
                                // Unsafe: destroyAndFreeSlot drives the pool's discard callback, which requires
                                // AllowUnsafe and does not suspend.
                                Sync.Unsafe.defer(destroyAndFreeSlot(conn, logger))
                        }(bounded(config)(conn.simpleExecute(sql)).unit)
                    }.flatMap {
                        case Result.Success(_) => true
                        case Result.Failure(e) =>
                            // The destroy has already happened, in the finalizer above. `Scope.run` closes the scope
                            // with this failure before it reaches here, so this arm reports and nothing else. Do not
                            // destroy here as well: the finalizer's compare-and-set guards only invocations routed
                            // through it, so a direct second call would close the socket twice and count one
                            // eviction as two.
                            //
                            // `bounded` is what makes a probe that never answers arrive here as a typed
                            // `SqlConnectionQueryTimeoutException` under `queryTimeout`, so the reason is recorded
                            // rather than being indistinguishable from a connection that answered "dead".
                            Log.debug(
                                s"kyo.sql: connectionTestQuery failed on connection id=${conn.id}, discarded: ${e.getMessage}"
                            ).andThen(false)
                        case Result.Panic(t) => Abort.error(Result.Panic(t))
                    }
                }

    /** Opens one connection, refusing first if the config demands encryption and carries nothing to encrypt with, then bounding the open by
      * the URL's `connectTimeout` when it declared one and by `acquireTimeout` otherwise.
      *
      * The encryption check lives here because this is the only place that calls `factory.open`, so [[warmUp]], `connectAndRun`, and
      * `acquireScoped` all reach it, both engines are covered, and a third would be covered without its adapter participating. The engines
      * cannot be trusted with it individually: each reads `config.tls` to decide the transport and neither consults `config.tlsMode`, so an
      * absent context under a mandatory mode reads as plaintext at both. Fixing it in the two adapters would leave the next adapter to
      * remember.
      *
      * It is deliberately a refusal and not a repair. Deriving the missing settings here would make the connection succeed and would leave
      * `SqlConfig` able to hold the contradiction, so every future route to one would need the same derivation; refusing means the state is
      * unrepresentable at the only point where it matters, wherever the config came from.
      *
      * Some bound on the open is mandatory. A TCP connect or a startup handshake against a partially-recovered server (the kernel accepts
      * the SYN but the server has not bound yet, or is not yet answering startup) can hang forever, and each retry attempt would hold its
      * slot for as long, eventually starving the pool and stalling the whole retry schedule.
      *
      * `acquireTimeout` is the fallback rather than the answer: its meaning is "how long to wait for a connection to become available",
      * which covers establishment only because when reconnecting the connect is the dominant cost. A URL that declares `connectTimeout` has
      * said something more specific about this endpoint, so it wins, the same precedence the URL's `sslmode` has over the config's
      * `tlsMode`.
      */
    private def connect(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): C < (Async & Abort[SqlException]) =
        if config.tlsMode.demandsEncryption && config.tls.isEmpty then
            Abort.fail(SqlConnectionTlsNotConfiguredException(config.tlsMode))
        else
            val budget = connectTimeout.getOrElse(config.acquireTimeout)
            if budget == Duration.Infinity then factory.open(address, password, config)
            else
                Async.timeoutWithError(
                    budget,
                    Result.Failure(SqlConnectionEstablishTimeoutException(budget, address.host, address.port))
                )(factory.open(address, password, config))
            end if
    end connect

    /** Opens a connection outside the pool's lease machinery, for a subsystem that owns it for the connection's whole life and never
      * returns it: the notification listener is the one caller.
      *
      * Delegates to [[connect]], so the mandatory-encryption refusal, the engine factory's TLS mode routing, and the connect budget cover
      * this open exactly as they cover a pooled one. Bypassing the pool's lease must not mean bypassing its guards: a path that opened its
      * own socket outside [[connect]] would hand a `verify-full` caller a plaintext listener. Takes no slot and enters no ring, because
      * the concurrency and retention bounds are about statements and a dedicated listener is neither; the caller owns the close. Refuses
      * on a closed pool so the one operation that does not lease still respects [[kyo.SqlClient.close]].
      */
    private[kyo] def openDedicated(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): C < (Async & Abort[SqlException]) =
        // Unsafe: isClosed reads the pool's closed flag without suspending.
        Sync.Unsafe.defer(pool.isClosed).flatMap {
            case true  => Abort.fail(SqlConnectionPoolClosedException())
            case false => connect(address, password, config)
        }

    // --- Layer 4: exit and reclaim ---

    /** Resolves one lease exactly once, on whichever edge `body` leaves by, whatever type that edge carries.
      *
      * A [[kyo.Scope]] finalizer, deliberately, and NOT a `Sync.ensure` one. `Sync.ensure` fires on an interrupt or a panic but not on a typed
      * `Abort` whose handler sits outside its region: there the failure is a suspension that skips the continuation, so the finalizer is parked
      * on the fiber and runs only when that fiber completes. For a pool that would mean an ordinary statement failure strands a permit and a
      * connection until the caller's whole program ends. `Scope.run` closes its scope as part of evaluating the body, so the same finalizer
      * covers the typed edge as well.
      *
      * The reason this is a scope rather than a catch-and-re-raise is the INTERRUPT edge, not the typed one. `Abort.run[Any]` plus a re-raise
      * does cover typed failures and panics: [[kyo.SqlClient]]'s `reraise` performs exactly that re-raise, and `kyo.Scope.run` does the same at
      * `Scope.scala:140`. What `Abort.run[Any]` does NOT cover is a fiber interrupt, which the scheduler delivers by abandoning the computation
      * rather than raising, while `Sync.ensure` covers the interrupt and misses the typed edge. A scope composes both. Running the body in a
      * child fiber, whose completion is observable for any error type, needs an `Isolate` for the opaque `S` these methods accept, which would
      * become a `using` requirement on `transaction`, `withAdvisoryLock` and `pipeline`. A scope needs neither: it never names the error, so a
      * caller's own typed failure smuggled through `S` resolves the lease exactly as a [[kyo.SqlException]] does.
      *
      * Only one path fires, yet the compare-and-set is kept: it costs a flag and makes the exactly-once property hold by construction rather
      * than by how the finalizers happen to be arranged, which is the property the rest of this file relies on: no double-release into the ring,
      * and no double-destroy.
      */
    private def resolvingOnce[A, S](finish: Maybe[Result.Error[Any]] => Unit < Sync)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        // Unsafe: a plain flag, read and written only by the resolution path below.
        Sync.Unsafe.defer(AtomicBoolean.Unsafe.init(false)).flatMap { resolved =>
            def once(error: Maybe[Result.Error[Any]])(using Frame): Unit < Sync =
                Sync.Unsafe.defer(resolved.compareAndSet(false, true)).flatMap {
                    case true  => finish(error)
                    case false => ()
                }
            Scope.run(Scope.ensure(error => once(error)).andThen(body))
        }

    /** Registers the exit decision for one lease.
      *
      * The permit is returned by the enclosing [[withSlot]], which fires unconditionally; this owns only the connection's fate. Do not offer
      * the permit back here as well or the slot channel over-fills past `maxConnections`.
      */
    private def onLease[A, S](netKey: SqlConnectionPool.Endpoint, conn: C, config: SqlConfig)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        // The logger is captured now, inside whatever Log.let scope is in force, because the resolution runs after
        // the Local context has unwound and would otherwise log to a different sink.
        Log.use { logger =>
            resolvingOnce(error => decideExit(netKey, conn, config, logger, error))(body)
        }

    /** Decides what happens to `conn` now that its lease has ended, and is shared by the [[onLease]] and [[acquireScoped]] paths so a
      * statement and a stream resolve an interrupt the same way.
      */
    private def decideExit(
        netKey: SqlConnectionPool.Endpoint,
        conn: C,
        config: SqlConfig,
        logger: Log,
        error: Maybe[Result.Error[Any]]
    )(using Frame): Unit < Sync =
        // Unsafe: every pool operation requires AllowUnsafe, and this runs outside fiber suspension.
        Sync.Unsafe.defer {
            discard(Sync.Unsafe.evalOrThrow(metrics.recordLeaseReleased))
            // A closed socket owes no cancel and has nothing to drain: the server saw the EOF and ended the
            // statement itself. Asking anyway would spend the whole cancel budget on a connection that is already
            // gone, which is how a transport failure would come to cost two seconds instead of nothing.
            val reclaimable = Sync.Unsafe.evalOrThrow(conn.isOpen)
            error match
                case Present(_) if reclaimable && (conn.inFlight || conn.inOpenTransaction) =>
                    // Quarantine. The connection is in neither the idle ring nor closed until the chain spawned
                    // below decides which, and cancelsInFlight is what makes that visible to closeAll.
                    discard(cancelsInFlight.incrementAndGet())
                    discard(Sync.Unsafe.evalOrThrow(metrics.recordCancelFired))
                    logger.unsafe.debug(s"kyo.sql: cancelling connection id=${conn.id}")
                    val supervised: Unit < Async =
                        Sync.ensure(_ => Sync.Unsafe.defer(discard(cancelsInFlight.decrementAndGet())))(
                            cancelAndReclaim(netKey, conn, config, logger)
                        )
                    // Unsafe: the reclaim has to outlive the fiber that is already unwinding, so it goes to a fresh
                    // unsupervised carrier. Handing it to the interrupted fiber would make the interrupt wait on
                    // cancel wire work, which is the thing the interrupt asked to stop.
                    discard(Fiber.Unsafe.init(supervised))
                case _ =>
                    // `reclaimable` (the socket is still open) gates the release as well as the reclaim: a COPY
                    // cleanup that overruns its budget closes the socket as it marks the channel corrupted, and
                    // a session that exited cleanly by every other measure must still not re-enter the ring
                    // with a dead transport. Without this the ring takes it back and evicts it only at the next
                    // poll's liveness check, counting a release that never really happened.
                    if reclaimable && Connection.leftSessionIdle(error) then releaseToPool(netKey, conn, logger)
                    else destroyAndFreeSlot(conn, logger)
            end match
        }
    end decideExit

    /** Stops the statement on the server and puts the session back to idle, inside one `cancelTimeout` budget.
      *
      * One budget for the whole chain, not one per step: any step that overruns it raises
      * [[kyo.SqlConnectionCancelTimeoutException]] carrying that budget, `Abort.run` normalises it into a [[kyo.Result]], and every outcome other
      * than a drain that reported the session reusable destroys the connection and frees its place in the pool.
      *
      * The drain comes before the rollback, and that order is load-bearing rather than incidental. `ROLLBACK` is an ordinary statement, so
      * writing it while the cancelled statement's response is still queued would make the rollback's own exchange read that response as its
      * answer and leave its real one behind: the desync the reclaim exists to prevent. Draining first puts the session on a known boundary,
      * and the rollback then goes out on a clean wire. A drain that reports the session unusable skips the rollback entirely, because there is
      * nothing to write to and destroying the connection ends the transaction server-side anyway.
      */
    private def cancelAndReclaim(netKey: SqlConnectionPool.Endpoint, conn: C, config: SqlConfig, logger: Log)(using Frame): Unit < Async =
        val reclaim: Boolean < (Async & Abort[SqlException]) =
            conn.cancelInFlight.andThen {
                conn.drainToIdle.flatMap {
                    case false => false
                    case true  => conn.rollbackIfOpenTransaction.andThen(true)
                }
            }
        Async.timeoutWithError(
            config.cancelTimeout,
            Result.Failure(SqlConnectionCancelTimeoutException(config.cancelTimeout))
        )(reclaim)
            .handle(Abort.run[SqlException](_))
            .map { outcome =>
                // Unsafe: the pool operations below require AllowUnsafe, and this runs on a carrier of its own
                // rather than inside any caller's fiber.
                Sync.Unsafe.defer {
                    outcome match
                        case Result.Success(true) =>
                            releaseToPool(netKey, conn, logger)
                        case other =>
                            other match
                                case Result.Failure(_: SqlConnectionCancelTimeoutException) =>
                                    discard(Sync.Unsafe.evalOrThrow(metrics.recordCancelTimedOut))
                                case _ => ()
                            end match
                            destroyAndFreeSlot(conn, logger)
                    end match
                }
            }
    end cancelAndReclaim

    /** Returns a healthy connection to the idle ring.
      *
      * A pool that has finished closing cannot take one back, and a reclaim spawned before the close can arrive after it, so this destroys
      * instead of pooling in that case and reports it as such rather than counting a release that did not happen.
      */
    private def releaseToPool(netKey: SqlConnectionPool.Endpoint, conn: C, logger: Log)(using Frame, AllowUnsafe): Unit =
        if pool.isClosed then destroyAndFreeSlot(conn, logger)
        else
            pool.release(netKey, conn)
            discard(Sync.Unsafe.evalOrThrow(metrics.recordRelease))
            // Not "closed": this connection is going back into the ring alive. The sibling in destroyAndFreeSlot is
            // the one that closes.
            logger.unsafe.debug(s"kyo.sql: pooled connection id=${conn.id}")

    /** Closes a connection instead of pooling it, freeing the place it held. */
    private def destroyAndFreeSlot(conn: C, logger: Log)(using Frame, AllowUnsafe): Unit =
        pool.discard(conn)
        discard(Sync.Unsafe.evalOrThrow(metrics.recordDiscard))
        logger.unsafe.debug(s"kyo.sql: closed connection id=${conn.id} reason=discarded")
    end destroyAndFreeSlot

    private def acquireScoped(
        address: SqlConfig.Address,
        password: Maybe[String],
        netKey: SqlConnectionPool.Endpoint,
        config: SqlConfig,
        leaseClock: Clock.Stopwatch
    )(using Frame): C < (Async & Abort[SqlException] & Scope) =
        // The permit is owned by the outer Scope.ensure in leaseScoped; the per-connection finalizer here owns
        // only the connection's fate, and resolves it through the same decision a statement's lease uses.
        Log.use { logger =>
            // Instruments owed once a connection is in hand (a permit is not a connection): `decideExit`
            // decrements `leases_in_flight` on every exit edge, so this path must do the increment or N streams
            // drive the gauge to -N. Recorded AFTER the exit finalizer registers, the reverse of the statement
            // path: an interrupt between the two either loses a count or loses the finalizer, and a momentarily
            // low gauge costs less than a socket with no reclaim.
            def held(using Frame): Unit < Async =
                metrics.recordAcquire.andThen(leaseClock.elapsed.flatMap(d => metrics.recordLeaseAcquired(d.toMillis)))
            acquireOrReserve(netKey, config).flatMap {
                case Present(conn) =>
                    Scope.ensure(error => decideExit(netKey, conn, config, logger, error)).andThen(held).andThen(conn)
                case Absent =>
                    // The reservation ends when the connection exists; the connection ends when the caller is done
                    // reading. Two lifetimes, two scopes: `resolvingOnce` releases the reservation on every exit
                    // edge (a stranded reservation is `tryReserve` refusing forever once maxConnections of them
                    // accumulate), while the connection's exit registers OUTSIDE it, on the caller's scope. Inside,
                    // it would bind to `resolvingOnce`'s own Scope.run and fire the moment the connection is
                    // produced: back to the idle ring before the caller reads a row, and nothing left for an
                    // interrupt to reclaim.
                    // Unsafe: pool.unreserve CASes the ring's in-flight count, an AllowUnsafe pool operation like the rest.
                    resolvingOnce(_ => Sync.Unsafe.defer(pool.unreserve(netKey))) {
                        connect(address, password, config).flatMap { conn =>
                            Log.debug(
                                s"kyo.sql: opened connection id=${conn.id} host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
                            ).andThen(conn)
                        }
                    }.flatMap { conn =>
                        Scope.ensure(error => decideExit(netKey, conn, config, logger, error)).andThen(held).andThen(conn)
                    }
            }
        }
    end acquireScoped

end SqlConnectionPool

private[kyo] object SqlConnectionPool:

    /** What makes two idle connections interchangeable: the same wire, opened under the same transport security.
      *
      * Two leases against one endpoint can carry different transport security, because [[kyo.DB.withConfig]] adjusts the config per statement. If
      * the address alone were the key, a plaintext connection opened under `disable` would sit in the same bucket as one a `verify-full` lease is
      * asking for, and the ring would hand it over. Refusing a malformed config does not reach that: the demanding config here is well formed, and
      * the session it is given was opened before it existed.
      *
      * Encryption is part of the KEY rather than a property compared when a connection is polled, and the choice is deliberate. Comparing at lease
      * time needs the connection to report the transport it was opened under, which is a new [[kyo.db.Connection]] member every adapter
      * has to set and keep truthful across an upgrade, a negotiation fallback, and a reconnect: a property maintained by convention, which is
      * exactly the fragility a key avoids. As a key nothing is maintained, because the identity IS the lookup and a connection cannot be served under
      * one it was not filed under. A mismatch policy would also have to choose between discarding healthy connections one at a time under mixed
      * configs and failing a caller over another caller's config; partitioning does neither.
      *
      * `tls` participates rather than a boolean, because `verify-full` against one CA is not interchangeable with `verify-full` against another,
      * and `kyo.net.NetTlsConfig` is a case class deriving `CanEqual`, so structural equality is already the right comparison.
      *
      * The cost is that one address can own several ring buckets, so idle RETENTION per address can exceed `maxConnections` while concurrency
      * cannot. `getOrCreateSlotChan` carries that asymmetry and both of its causes; this is the second one. A client with one config, which is the
      * ordinary case, has exactly one bucket and is unaffected.
      */
    private[kyo] case class Endpoint(
        net: NetAddress,
        tlsMode: TlsMode,
        tls: Maybe[NetTlsConfig]
    ) derives CanEqual

    private[kyo] object Endpoint:
        def apply(address: SqlConfig.Address, config: SqlConfig): Endpoint =
            Endpoint(NetAddress.Tcp(address.host, address.port), config.tlsMode, config.tls)

    /** Builds a pool around a fresh [[ConnectionPool]] for the connection type `factory` opens.
      *
      * @param config
      *   pool parameters. The idle ring's capacity is floored at 2, which [[ConnectionPool]]'s Vyukov buffer
      *   requires; the slot channel is NOT floored and holds exactly `maxConnections` permits. The ring bounds how
      *   many idle connections may be kept, the channel bounds how many may be in use at once, and it is the channel
      *   a caller is talking about when it sets `maxConnections`. Flooring both would silently give a pool asked for
      *   one connection two concurrent sessions.
      * @param connectTimeout
      *   the URL's own budget for establishing a connection, or [[Absent]] to fall back to `acquireTimeout`
      * @param frame
      *   captured at the call site so the pool's isAlive and discard callbacks carry a real source location
      */
    def init[C <: Connection](
        config: SqlConfig,
        factory: Connection.Factory[C],
        connectTimeout: Maybe[Duration],
        frame: Frame
    )(using AllowUnsafe): SqlConnectionPool[C] =
        given capturedFrame: Frame = frame
        val pool = ConnectionPool.init[SqlConnectionPool.Endpoint, C](
            maxConnectionsPerHost = config.maxConnections.max(2),
            idleConnectionTimeout = config.idleTimeout,
            // Unsafe: isAlive is called from pool.poll, a non-Kyo context that already carries AllowUnsafe.
            // Only a socket check can live here: this is a plain `C => Boolean` the ring calls without suspending,
            // so a server round trip cannot be answered in this position on any platform (`healthy` runs the
            // connectionTestQuery one layer out, where suspension is legal). NonFatal only, because reporting a
            // VirtualMachineError as "not alive" would silently discard and reopen every connection forever; the
            // fatal throw is safe to let escape since the ring vacates the slot before calling.
            isAlive = conn =>
                try Sync.Unsafe.evalOrThrow(conn.isOpen)
                catch
                    case ex: Throwable if NonFatal(ex) => false,
            // Unsafe: discard is called from the pool's eviction path, a non-Kyo context that already carries
            // AllowUnsafe. closeNow is the close that does not suspend, which is the only kind available here.
            //
            // Narrowed to NonFatal for the same reason as the callback above. The two cases differ in severity,
            // since a discard that swallows an error merely logs and continues a teardown that changes nothing
            // about the pool's state, but that does not make catching an OutOfMemoryError and then allocating a
            // log string to report it correct. SqlBackendDiscovery states the rule without a severity carve-out.
            discard = conn =>
                try conn.closeNow
                catch
                    case ex: Throwable if NonFatal(ex) =>
                        Log.live.unsafe.error(s"kyo.sql: SqlConnectionPool.discard: error closing connection: ${ex.getMessage}")
        )
        new SqlConnectionPool[C](
            pool,
            new ConcurrentHashMap[SqlConfig.Address, Channel[Unit]](),
            AtomicInt.Unsafe.init(0),
            AtomicInt.Unsafe.init(0),
            factory,
            connectTimeout,
            SqlClient.Metrics(config.metricsEnabled, config.metricsScope)
        )
    end init

end SqlConnectionPool
