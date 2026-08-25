package kyo.internal

import kyo.*
import kyo.Sql.BoundValue
import kyo.db.Connection
import kyo.db.Idiom
import kyo.internal.client.SqlConnectionPool

/** Pins the reclaim contract: interrupting a fiber that holds a lease fires the wire cancel, the reclaim chain is bounded, and a reclaim
  * that outlives a close destroys rather than pools.
  *
  * Driven by a probe [[Connection]] rather than a real server, because what is under test is the pool's decision-making, not either
  * engine's packets. The probe reports each reclaim step into a [[kyo.Channel]] the leaves read, so an assertion is a value comparison
  * against an exact sequence rather than a wait-and-hope. The probe also maintains the in-flight flag exactly as the real adapters do,
  * raising it before a statement and lowering it on everything except a panic, so the contract this suite pins is the one they implement.
  *
  * A statement that "hangs" is [[kyo.Async.never]], which cannot complete, so whichever interruption source a leaf drives always wins the
  * race. That is what makes every leaf here deterministic without a timing assumption.
  *
  * The barrier before reading a counter is [[untilCancelsSettled]], never `closeAll`. `closeAll` marks the pool closed on the way in, and
  * `releaseToPool` discards rather than pools once that holds, so using it as the barrier would decide the very outcome the counters are
  * meant to observe (for example a `connectionsDiscarded` that `closeAll` guarantees on its own).
  */
class SqlConnectionCancelTest extends kyo.Test:

    // --- probe ---

    /** Statement markers the probe recognises, so a leaf says what the connection should do by the SQL it sends. */
    private object Sql:
        /** Raises the in-flight flag, reports `statement`, and never completes. */
        val hang = "probe: hang"

        /** Raises the in-flight flag, reports `flagged`, and never completes, without reporting a write.
          *
          * The distinction is the window boundary the SPI pins: the flag must already be readable from outside the fiber before the first
          * request byte goes out, so an interrupt landing in that window still owes a cancel.
          */
        val flagOnly = "probe: flag only"

        /** Fails with a routine server error, the wire idle and the session reusable. */
        val serverError = "probe: server error"

        /** Fails with a transport error, the socket unusable. */
        val transportError = "probe: transport error"
    end Sql

    /** What a probe does when the reclaim chain reaches it.
      *
      * @param cancelHangs
      *   `cancelInFlight` reports `cancel` and never completes, so the cancel budget is what ends the chain
      * @param drainHangs
      *   `drainToIdle` reports `drain` and never completes
      * @param drainGate
      *   when [[Present]], `drainToIdle` reports `drain` and waits for the leaf to release the latch, which is how a leaf parks a reclaim
      *   mid-chain and closes the pool underneath it
      * @param drainReusable
      *   what `drainToIdle` answers once it gets there
      * @param openHangsOnce
      * the first `open` reports `connecting` and then never completes, so a leaf can interrupt a lease that has no connection yet. The
      * report is what makes that deterministic: the leaf waits for it before interrupting, rather than assuming the attempt has started
      */
    private case class Script(
        cancelHangs: Boolean = false,
        drainHangs: Boolean = false,
        drainUntilSocketClosed: Boolean = false,
        drainGate: Maybe[Latch] = Absent,
        drainReusable: Boolean = true,
        openHangsOnce: Boolean = false
    )

    final private class Probe(
        val id: Long,
        events: Channel[String],
        script: Script,
        requestInFlight: AtomicBoolean.Unsafe,
        transactionOpen: AtomicBoolean.Unsafe,
        socketOpen: AtomicBoolean.Unsafe
    ) extends Connection:

        private def emit(event: String)(using Frame): Unit < (Async & Abort[SqlException]) =
            Abort.run[Closed](events.offer(event)).unit

        // The probe stands in for a fixed server; the cancel tests never read this, but Connection requires it.
        def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
            Idiom.ServerVersion(1, 0, 0)

        // --- statements ---

        def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            tracked {
                if sql == Sql.flagOnly then emit("flagged").andThen(Async.never)
                else
                    emit("statement").andThen {
                        if sql == Sql.hang then Async.never
                        else if sql == Sql.serverError then Abort.fail(SqlServerException("23505", "ERROR", "duplicate key"))
                        else if sql == Sql.transportError then
                            // A real transport failure has already lost the socket, and the pool reads that to decide
                            // there is nothing left to cancel or drain.
                            Sync.Unsafe.defer(socketOpen.set(false)).andThen(Abort.fail(SqlConnectionClosedException("read")))
                        else Chunk.empty[SqlRow]
                    }
            }

        def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
            simpleQuery(sql).andThen(0L)

        // --- transactions ---

        def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using
            Frame
        ): Unit < (Async & Abort[SqlException]) =
            tracked(emit("begin")).andThen(Sync.Unsafe.defer(transactionOpen.set(true)))

        def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
            tracked(emit("commit")).andThen(Sync.Unsafe.defer(transactionOpen.set(false)))

        def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
            tracked(emit("rollback")).andThen(Sync.Unsafe.defer(transactionOpen.set(false)))

        // --- reclaim ---

        def inFlight(using AllowUnsafe): Boolean = requestInFlight.get()

        def inOpenTransaction(using AllowUnsafe): Boolean = transactionOpen.get()

        def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer(requestInFlight.get()).flatMap {
                case false => ()
                case true  => emit("cancel").andThen(if script.cancelHangs then Async.never else ())
            }

        def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer(transactionOpen.get()).flatMap {
                case false => ()
                case true  => rollbackTransaction
            }

        def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer(requestInFlight.get()).flatMap {
                case false => true
                case true =>
                    emit("drain").andThen {
                        if script.drainHangs then Async.never
                        else if script.drainUntilSocketClosed then drainUntilClosed
                        else
                            script.drainGate match
                                case Present(gate) => gate.await.andThen(settled)
                                case Absent        => settled
                    }
            }

        /** A drain read that never returns on its own, ending only when the socket is closed under it: so `closeAll`'s
          * force-close is the only thing that ends the reclaim, which is what the quarantine-sweep regression pins.
          */
        private def drainUntilClosed(using Frame): Boolean < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer(socketOpen.get()).flatMap {
                case true  => Async.sleep(5.millis).andThen(drainUntilClosed)
                case false => Abort.fail[SqlException](SqlConnectionClosedException("read"))
            }

        /** Lowers the in-flight flag the way a real drain does, then answers what the script says. */
        private def settled(using Frame): Boolean < Sync =
            Sync.Unsafe.defer {
                requestInFlight.set(false)
                script.drainReusable
            }

        // --- lifecycle ---

        def isOpen(using Frame): Boolean < Sync = Sync.Unsafe.defer(socketOpen.get())

        def close(using Frame): Unit < Async =
            Sync.Unsafe.defer(socketOpen.set(false)).andThen(Abort.run[SqlException](emit("closed")).unit)

        def closeNow(using Frame, AllowUnsafe): Unit =
            socketOpen.set(false)
            discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](events.offer("closed"))))

        // --- the in-flight window, maintained exactly as the real adapters maintain it ---

        private def tracked[A](body: A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer(requestInFlight.set(true)).andThen {
                Sync.ensure(error => Sync.Unsafe.defer(settle(error))) {
                    Abort.run[SqlException](body).flatMap { outcome =>
                        Sync.Unsafe.defer(settle(Connection.errorOf(outcome))).andThen {
                            outcome match
                                case Result.Success(a) => a
                                case Result.Failure(e) => Abort.fail[SqlException](e)
                                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                        }
                    }
                }
            }

        private def settle(error: Maybe[Result.Error[Any]])(using AllowUnsafe): Unit =
            if Connection.leftSessionIdle(error) then requestInFlight.set(false)

        // --- members this suite never drives ---

        private def unused[A](member: String)(using Frame): A < (Async & Abort[SqlException]) =
            Abort.panic(new AssertionError(s"SqlConnectionCancelTest probe: $member is not driven by this suite"))

        def extendedQuery(sql: String, params: Chunk[BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            unused("extendedQuery")
        def extendedExecute(sql: String, params: Chunk[BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
            unused("extendedExecute")
        def extendedExecuteInsert(sql: String, params: Chunk[BoundValue[?]])(using
            Frame
        ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) = unused("extendedExecuteInsert")
        def streamQuery(sql: String, params: Chunk[BoundValue[?]], batchSize: Int)(using
            Frame
        ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
            Stream[SqlRow, Async & Abort[SqlException] & Scope](unused("streamQuery"))
        def pipelined(stmts: Chunk[(String, Chunk[BoundValue[?]])])(using
            Frame
        ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) = unused("pipelined")
        def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = unused("savepoint")
        def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = unused("releaseSavepoint")
        def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = unused("rollbackToSavepoint")
        def ping(using Frame): Unit < (Async & Abort[SqlException])                              = unused("ping")
        def resetSession(using Frame): Unit < (Async & Abort[SqlException])                      = unused("resetSession")
        def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
            unused("acquireAdvisoryLock")
        def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) = unused("releaseAdvisoryLock")

    end Probe

    // --- harness ---

    private val address = SqlConfig.Address("probe", "127.0.0.1", 1, "probe", Present("probe"))

    /** No connect bound and no statement bound, so the leaf's own interruption source is the only one in play. */
    private def baseConfig(scope: String): SqlConfig =
        SqlConfig(
            maxConnections = 2,
            acquireTimeout = Duration.Infinity,
            queryTimeout = Duration.Infinity,
            cancelTimeout = 30.seconds,
            metricsScope = Present(s"kyo.sql.probe.$scope")
        )

    /** Builds a pool over probe connections and hands the leaf the pool plus the channel the probes report into.
      *
      * Each leaf gets its own metrics scope: instruments are registered by name in a process-wide registry, so two pools sharing a scope
      * would share their counters and every count assertion would depend on which leaf ran first.
      */
    private def withProbePool[A](scope: String, script: Script = Script())(
        f: (SqlConnectionPool[Probe], Channel[String]) => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        withProbePool(scope, script, baseConfig(scope))(f)

    private def withProbePool[A](scope: String, script: Script, config: SqlConfig)(
        f: (SqlConnectionPool[Probe], Channel[String]) => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        Channel.initUnscoped[String](64).flatMap { events =>
            AtomicLong.init(0).flatMap { ids =>
                val factory = new Connection.Factory[Probe]:
                    def open(a: SqlConfig.Address, password: Maybe[String], c: SqlConfig)(using
                        Frame
                    ): Probe < (Async & Abort[SqlException]) =
                        ids.incrementAndGet.flatMap { id =>
                            if script.openHangsOnce && id == 1L then
                                Abort.run[Closed](events.offer("connecting")).andThen(Async.never)
                            else
                                // Unsafe: two plain flags backing the in-flight window, created before the probe is visible.
                                Sync.Unsafe.defer(
                                    new Probe(
                                        id,
                                        events,
                                        script,
                                        AtomicBoolean.Unsafe.init(false),
                                        AtomicBoolean.Unsafe.init(false),
                                        AtomicBoolean.Unsafe.init(true)
                                    )
                                )
                        }
                // Unsafe: SqlConnectionPool.init uses AllowUnsafe for ring-buffer initialisation.
                Sync.Unsafe.defer(SqlConnectionPool.init(config, factory, Absent, summon[Frame])).flatMap(pool => f(pool, events))
            }
        }

    /** Reads exactly `n` reported events, in order. */
    private def report(events: Channel[String], n: Int)(using Frame): Chunk[String] < (Async & Abort[SqlException]) =
        Abort.run[Closed](Kyo.foreach(Chunk.from(0 until n))(_ => events.take)).flatMap {
            case Result.Success(seen) => seen
            case other                => Abort.panic(new AssertionError(s"probe event channel closed early: $other"))
        }

    /** Every event reported so far, without waiting for one that may never arrive.
      *
      * [[report]] blocks on `take` until its count is reached, which makes it the wrong reader for a leaf whose regression shows up as a
      * MISSING event. Those leaves end a destroy with "closed", so a regression that pools instead sends one event fewer and `report` waits
      * for it until the suite's own limit expires: the leaf fails as a two-minute TIMEOUT naming nothing rather than as the assertion it
      * wrote. A reclaim that ignores the drain's answer produces exactly that.
      *
      * Draining what is already queued turns "the connection was never closed" into a value a leaf can compare, and it reads the
      * DISCRIMINATING TAIL only. It is deterministic under two conditions, both of which a caller has to arrange:
      *
      *   - [[report]] has already awaited the prefix that every path sends, which is what proves the reclaim started. `decideExit` raises
      *     `cancelsInFlight` on a detached carrier some time after `interrupt` returns, so a leaf that goes straight to
      *     [[untilCancelsSettled]] can satisfy it against a reclaim that has not begun and then drain nothing at all.
      *   - [[untilCancelsSettled]] has then run, which is what proves the reclaim finished reporting.
      *
      * Skip either and this returns a prefix of the truth, which reads as a missing event and fails the leaf for the wrong reason.
      */
    private def drained(events: Channel[String])(using Frame): Chunk[String] < (Async & Abort[SqlException]) =
        Loop(Chunk.empty[String]) { seen =>
            Abort.run[Closed](events.poll).flatMap {
                case Result.Success(Present(event)) => Loop.continue(seen.append(event))
                case _                              => Loop.done(seen)
            }
        }

    private def lease[A](pool: SqlConnectionPool[Probe], config: SqlConfig)(
        op: Probe => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        pool.lease(address, Absent, config)(op)

    /** Waits until no reclaim chain is running, so a leaf can read the exit counters without perturbing them.
      *
      * A reclaim runs on a detached carrier, and the count it maintains is decremented after `cancelAndReclaim`
      * completes, which is after the chain has decided the connection's fate. So zero here means the release or the
      * destroy has already happened. `closeAll` also waits for this count, but it marks the pool closed on the way in
      * and that changes which fate the chain chooses, so it cannot be used as a barrier by a leaf that measures the
      * choice.
      */
    private def untilCancelsSettled(pool: SqlConnectionPool[Probe])(using Frame): Unit < Async =
        Loop(0) { attempt =>
            Sync.Unsafe.defer(pool.cancelsInFlightCount).flatMap { inFlight =>
                if inFlight == 0 || attempt >= 200 then Loop.done(())
                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
            }
        }

    // --- the five interruption sources ---

    "Async.timeout on a statement in flight fires the wire cancel" in {
        val config = baseConfig("timeout")
        withProbePool("timeout") { (pool, events) =>
            // Freeze time, wait for the statement in flight, then advance so Async.timeout (not connect latency) fires the
            // interrupt. A bare wall-clock timeout races lease-acquire and can fire pre-flight: no cancel owed, report hangs.
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(
                    Abort.run[Timeout](Async.timeout(100.millis)(lease(pool, config)(_.simpleQuery(Sql.hang))))
                ).map { fiber =>
                    report(events, 1).flatMap { first =>
                        assert(first == Chunk("statement"), s"expected the statement to reach the wire, saw $first")
                        control.advance(101.millis).andThen {
                            fiber.get.flatMap { outcome =>
                                report(events, 2).map { seen =>
                                    assert(seen == Chunk("cancel", "drain"), s"expected the reclaim chain to run, saw $seen")
                                    outcome match
                                        case Result.Failure(_: Timeout) => succeed
                                        case other                      => fail(s"expected the lease to end in a Timeout, got $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Scope teardown while a statement is in flight fires the wire cancel" in {
        val config = baseConfig("scope")
        withProbePool("scope") { (pool, events) =>
            Fiber.initUnscoped(
                Scope.run(
                    Fiber.init(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang))))
                        // The scope's body ends once the statement is reported in flight, which closes the scope and
                        // interrupts the child holding the lease. No sleep decides when that is.
                        .andThen(report(events, 1))
                )
            ).flatMap { outer =>
                outer.get.flatMap { first =>
                    report(events, 2).map { seen =>
                        assert(first == Chunk("statement"), s"expected the statement to reach the wire, saw $first")
                        assert(seen == Chunk("cancel", "drain"), s"expected Scope teardown to fire the reclaim, saw $seen")
                    }
                }
            }
        }
    }

    "interrupting the fiber holding the lease fires the wire cancel" in {
        val config = baseConfig("interrupt")
        withProbePool("interrupt") { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).flatMap { first =>
                    fiber.interrupt.flatMap { interrupted =>
                        report(events, 2).map { seen =>
                            assert(first == Chunk("statement"))
                            assert(interrupted, "interrupting the fiber running a statement must stop it")
                            assert(seen == Chunk("cancel", "drain"), s"expected the interrupt to fire the reclaim, saw $seen")
                        }
                    }
                }
            }
        }
    }

    "losing a race fires the wire cancel on the loser's lease" in {
        val config = baseConfig("race")
        withProbePool("race") { (pool, events) =>
            Async.race(
                Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang))).andThen("lease"),
                report(events, 1).andThen("observer")
            ).flatMap { winner =>
                report(events, 2).map { seen =>
                    assert(winner == "observer", s"the hanging lease must lose the race, winner was $winner")
                    assert(seen == Chunk("cancel", "drain"), s"expected the race loser's reclaim to run, saw $seen")
                }
            }
        }
    }

    "a parent fiber's death reaches the lease through its scope and fires the wire cancel" in {
        val config = baseConfig("parent")
        withProbePool("parent") { (pool, events) =>
            Fiber.initUnscoped(
                Scope.run(
                    Fiber.init(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).andThen(Async.never)
                )
            ).flatMap { parent =>
                report(events, 1).flatMap { first =>
                    parent.interrupt.flatMap { interrupted =>
                        report(events, 2).map { seen =>
                            assert(first == Chunk("statement"))
                            assert(interrupted, "interrupting the parent must collapse its scope")
                            assert(seen == Chunk("cancel", "drain"), s"expected the scoped chain to fire the reclaim, saw $seen")
                        }
                    }
                }
            }
        }
    }

    // --- the window boundary ---

    "an interrupt between the in-flight flip and the first request byte still fires the cancel" in {
        val config = baseConfig("window")
        withProbePool("window") { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.flagOnly)))).flatMap { fiber =>
                report(events, 1).flatMap { first =>
                    fiber.interrupt.andThen {
                        report(events, 2).map { seen =>
                            assert(first == Chunk("flagged"), s"expected the flag to be raised before any write, saw $first")
                            assert(
                                seen == Chunk("cancel", "drain"),
                                s"the pool cannot know whether a byte went out, so it must still cancel; saw $seen"
                            )
                        }
                    }
                }
            }
        }
    }

    "an interrupt while a lease is still connecting fires no cancel and leaves the pool usable" in {
        val config = baseConfig("acquire")
        withProbePool("acquire", Script(openHangsOnce = true)) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery("probe: ok")))).flatMap { fiber =>
                // Wait until the connect attempt has actually started. Interrupting before it does would leave the
                // fixture's one-shot hang unspent, and the second lease below would be the one that hangs.
                report(events, 1).flatMap { connecting =>
                    fiber.interrupt.flatMap { interrupted =>
                        // No connection was ever held, so there is nothing to cancel. The property that matters is
                        // that the abandoned attempt gave back its reservation: a second lease must get through.
                        lease(pool, config)(_.simpleQuery("probe: ok")).flatMap { rows =>
                            report(events, 1).map { seen =>
                                assert(connecting == Chunk("connecting"))
                                assert(interrupted, "interrupting a fiber blocked on connect must stop it")
                                assert(rows == Chunk.empty[SqlRow])
                                assert(
                                    seen == Chunk("statement"),
                                    s"the second lease must run, and no cancel may appear; saw $seen"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- the reclaim chain ---

    "the reclaim chain cancels, then drains, then rolls back the open transaction" in {
        val config = baseConfig("order")
        withProbePool("order") { (pool, events) =>
            Fiber.initUnscoped(
                Abort.run[SqlException](lease(pool, config)(conn =>
                    conn.beginTransaction(Absent, false).andThen(conn.simpleQuery(Sql.hang))
                ))
            ).flatMap { fiber =>
                report(events, 2).flatMap { first =>
                    fiber.interrupt.andThen {
                        report(events, 3).map { seen =>
                            assert(first == Chunk("begin", "statement"))
                            // The rollback comes after the drain, not before: ROLLBACK is an ordinary statement, and
                            // writing it while the cancelled statement's response is still queued would make the
                            // rollback read that response as its own.
                            assert(seen == Chunk("cancel", "drain", "rollback"), s"expected cancel, drain, rollback in order; saw $seen")
                        }
                    }
                }
            }
        }
    }

    "a mid-transaction interrupt rolls back and then returns the connection to the pool" in {
        val config = baseConfig("txrelease")
        withProbePool("txrelease") { (pool, events) =>
            Fiber.initUnscoped(
                Abort.run[SqlException](lease(pool, config)(conn =>
                    conn.beginTransaction(Absent, false).andThen(conn.simpleQuery(Sql.hang))
                ))
            ).flatMap { fiber =>
                report(events, 2).andThen {
                    fiber.interrupt.andThen {
                        report(events, 3).flatMap { seen =>
                            // The barrier is the reclaim's own count, NOT `closeAll`. `closeAll` marks the pool closed
                            // before it drains (`SqlConnectionPool.scala:161-163`), and `releaseToPool` discards instead
                            // of pooling once `isClosed` holds, so waiting on `closeAll` would convert the release this
                            // leaf measures into a discard. The "rollback" event is emitted before the chain reaches
                            // `releaseToPool`, so that window is real and a leaf barriered on `closeAll` lands in it
                            // intermittently.
                            //
                            // The leaf two dozen lines below, "a reclaim that outlives the close destroys the connection
                            // instead of pooling it", asserts that conversion deliberately. Do not reconcile the two by
                            // changing this leaf to expect a discard: that would make them duplicates and delete the
                            // only coverage that a reclaimed session is pooled.
                            untilCancelsSettled(pool).andThen {
                                pool.metrics.connectionsReleased.get.flatMap { released =>
                                    pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                        pool.closeAll(30.seconds).map { _ =>
                                            assert(seen == Chunk("cancel", "drain", "rollback"))
                                            assert(released == 1L, s"a reusable session must go back to the pool, released=$released")
                                            assert(discarded == 0L, s"nothing was owed a destroy here, discarded=$discarded")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a drain that reports the session unusable destroys the connection" in {
        val config = baseConfig("unusable")
        withProbePool("unusable", Script(drainReusable = false)) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).andThen {
                    fiber.interrupt.andThen {
                        // Read the events in two phases, then read the counters before `closeAll`. Each of those three
                        // choices is load-bearing and none is interchangeable with the others.
                        //
                        // Phase one AWAITS the prefix every path sends, "cancel" then "drain". That is also the only
                        // thing that proves the reclaim has begun: `decideExit` raises `cancelsInFlight` on a detached
                        // carrier some time after `interrupt` returns, so settling on the count alone can be satisfied
                        // by a reclaim that has not started, which observes nothing at all and reads an empty event
                        // list.
                        //
                        // Phase two DRAINS the tail, because the tail is what discriminates and a regression omits it.
                        // A reclaim that pooled an unusable session sends no "closed", so awaiting a third event waits
                        // for one that is never coming: the leaf then dies as a two-minute TIMEOUT naming nothing
                        // instead of as the assertion below. `untilCancelsSettled` between the phases is what makes the
                        // drain deterministic, since a settled count means the chain has finished reporting.
                        //
                        // The counters are read before `closeAll` because a closed pool sends `releaseToPool` down the
                        // destroy path, so `discarded == 1` would hold whatever the chain decided, and what this leaf
                        // exists to assert is that the DRAIN's answer decided it.
                        report(events, 2).flatMap { chain =>
                            untilCancelsSettled(pool).andThen {
                                drained(events).flatMap { tail =>
                                    pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                        pool.metrics.connectionsReleased.get.flatMap { released =>
                                            pool.closeAll(30.seconds).map { _ =>
                                                val seen = chain.concat(tail)
                                                assert(
                                                    seen == Chunk("cancel", "drain", "closed"),
                                                    s"expected the connection to be closed, saw $seen"
                                                )
                                                assert(discarded == 1L, s"an unusable session must be destroyed, discarded=$discarded")
                                                assert(released == 0L, s"it must not also be counted as released, released=$released")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a cancel that overruns the budget destroys the connection and counts as timed out" in {
        val config = baseConfig("cancelbudget").copy(cancelTimeout = 100.millis)
        withProbePool("cancelbudget", Script(cancelHangs = true), config) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).andThen {
                    fiber.interrupt.andThen {
                        // Two-phase event read and counters-before-close, both for the reasons spelled out at the
                        // unusable-drain leaf above. The awaited prefix here is just "cancel", since `cancelHangs`
                        // ends the chain there and no drain is ever reported.
                        //
                        // `cancelsTimedOut` is the assertion that carries this leaf: only the budget path increments
                        // it. Do not drop it as redundant with `connectionsDiscarded`, which is the weaker of the two
                        // and goes vacuous the moment `closeAll` becomes the barrier.
                        report(events, 1).flatMap { chain =>
                            untilCancelsSettled(pool).andThen {
                                drained(events).flatMap { tail =>
                                    pool.metrics.cancelsTimedOut.get.flatMap { timedOut =>
                                        pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                            pool.closeAll(30.seconds).map { _ =>
                                                val seen = chain.concat(tail)
                                                assert(
                                                    seen == Chunk("cancel", "closed"),
                                                    s"the budget must end the chain at the cancel, saw $seen"
                                                )
                                                assert(timedOut == 1L, s"cancels_timed_out must count the overrun, was $timedOut")
                                                assert(discarded == 1L, s"an unfinished reclaim must destroy, discarded=$discarded")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a drain that overruns the budget destroys the connection and counts as timed out" in {
        val config = baseConfig("drainbudget").copy(cancelTimeout = 100.millis)
        withProbePool("drainbudget", Script(drainHangs = true), config) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).andThen {
                    fiber.interrupt.andThen {
                        // Two-phase event read, counters before the close, and which assertion carries the leaf: all
                        // three as at the cancel-budget leaf above. `drainHangs` means the chain does report "drain"
                        // before parking, so the awaited prefix is two events rather than one.
                        report(events, 2).flatMap { chain =>
                            untilCancelsSettled(pool).andThen {
                                drained(events).flatMap { tail =>
                                    pool.metrics.cancelsTimedOut.get.flatMap { timedOut =>
                                        pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                            pool.closeAll(30.seconds).map { _ =>
                                                val seen = chain.concat(tail)
                                                assert(
                                                    seen == Chunk("cancel", "drain", "closed"),
                                                    s"the budget covers the whole chain, saw $seen"
                                                )
                                                assert(timedOut == 1L, s"cancels_timed_out must count the overrun, was $timedOut")
                                                assert(discarded == 1L, s"an unfinished reclaim must destroy, discarded=$discarded")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a reclaim that outlives the close destroys the connection instead of pooling it" in {
        val config = baseConfig("outlives")
        Latch.init(1).flatMap { gate =>
            withProbePool("outlives", Script(drainGate = Present(gate)), config) { (pool, events) =>
                Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                    report(events, 1).andThen {
                        fiber.interrupt.andThen {
                            // The reclaim is now parked inside the drain, holding the connection.
                            report(events, 2).flatMap { seen =>
                                // Zero grace, so the close completes without waiting for the parked reclaim.
                                pool.closeAll(Duration.Zero).andThen {
                                    gate.release.andThen {
                                        // The drain answers "reusable", so releasing to the pool is what the chain
                                        // tries; the pool having closed is what turns that into a destroy.
                                        report(events, 1).flatMap { after =>
                                            pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                                pool.metrics.connectionsReleased.get.map { released =>
                                                    assert(seen == Chunk("cancel", "drain"))
                                                    assert(after == Chunk("closed"), s"the reclaim must close the connection, saw $after")
                                                    assert(discarded == 1L, s"a closed pool cannot take one back, discarded=$discarded")
                                                    assert(
                                                        released == 0L,
                                                        s"and it must not be counted as pooled, released=$released"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "closeAll force-closes a connection whose reclaim never completes on its own" in {
        // The reclaim parks in a drain that ends only on socket close (grace zero), and its only owner is the detached reclaim
        // carrier (not the idle ring `pool.close` extracts), so closeAll's quarantine sweep alone frees the socket; else it leaks (the CI failure).
        val config = baseConfig("closeall-quarantine")
        withProbePool("closeall-quarantine", Script(drainUntilSocketClosed = true), config) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).flatMap { first =>
                    assert(first == Chunk("statement"), s"expected the statement to reach the wire, saw $first")
                    fiber.interrupt.flatMap { interrupted =>
                        assert(interrupted, "interrupting the fiber running a statement must stop it")
                        report(events, 2).flatMap { seen =>
                            assert(seen == Chunk("cancel", "drain"), s"expected the reclaim to reach the drain, saw $seen")
                            pool.closeAll(Duration.Zero).andThen {
                                untilCancelsSettled(pool).andThen {
                                    drained(events).map { tail =>
                                        assert(
                                            tail.contains("closed"),
                                            s"closeAll must force-close the quarantined connection whose reclaim never returns, saw $tail"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an interrupt landing after the pool has closed destroys the connection inline" in {
        // closeAll's sweep runs once, so a quarantine registration landing after it is swept by nothing and leaks if its reclaim
        // hangs. So the interrupt (pool already closed, zero grace) must destroy inline via decideExit's put-then-recheck, not register a stray carrier.
        val config = baseConfig("closed-then-interrupt")
        withProbePool("closed-then-interrupt", Script(drainUntilSocketClosed = true), config) { (pool, events) =>
            Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                report(events, 1).flatMap { first =>
                    assert(first == Chunk("statement"), s"expected the statement to reach the wire, saw $first")
                    pool.closeAll(Duration.Zero).andThen {
                        fiber.interrupt.flatMap { interrupted =>
                            assert(interrupted, "interrupting the fiber running a statement must stop it")
                            // Wait for the resolution event, not the queue: the destroy runs on the fiber's exit (cancelsInFlight
                            // net-zero, so untilCancelsSettled returns early). It emits "closed"; a spawned reclaim would emit "cancel", failing the assert.
                            report(events, 1).map { after =>
                                assert(
                                    after == Chunk("closed"),
                                    s"an interrupt after the pool closed must destroy the connection inline, saw $after"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "every reclaim is accounted for while it runs and released afterwards" in {
        val config = baseConfig("accounting")
        Latch.init(1).flatMap { gate =>
            withProbePool("accounting", Script(drainGate = Present(gate)), config) { (pool, events) =>
                Fiber.initUnscoped(Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.hang)))).flatMap { fiber =>
                    report(events, 1).andThen {
                        fiber.interrupt.andThen {
                            report(events, 2).flatMap { seen =>
                                // Parked inside the drain: the reclaim is in flight and must be visible as such.
                                Sync.Unsafe.defer(pool.cancelsInFlightCount).flatMap { during =>
                                    gate.release.andThen {
                                        pool.closeAll(30.seconds).andThen {
                                            Sync.Unsafe.defer(pool.cancelsInFlightCount).flatMap { after =>
                                                pool.metrics.cancelsFired.get.map { fired =>
                                                    assert(seen == Chunk("cancel", "drain"))
                                                    assert(during == 1, s"a running reclaim must be counted, was $during")
                                                    assert(after == 0, s"a finished reclaim must be uncounted, was $after")
                                                    assert(fired == 1L, s"cancels_fired must count the reclaim, was $fired")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- exits that owe no cancel ---

    "a routine server error returns the connection to the pool without firing a cancel" in {
        val config = baseConfig("clean")
        withProbePool("clean") { (pool, events) =>
            Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.serverError))).flatMap { outcome =>
                report(events, 1).flatMap { seen =>
                    pool.closeAll(30.seconds).andThen {
                        pool.metrics.cancelsFired.get.flatMap { fired =>
                            pool.metrics.connectionsReleased.get.map { released =>
                                assert(seen == Chunk("statement"), s"nothing beyond the statement may be reported, saw $seen")
                                assert(fired == 0L, s"a drained server error owes no cancel, cancels_fired=$fired")
                                assert(released == 1L, s"the session is reusable and must be pooled, released=$released")
                                outcome match
                                    case Result.Failure(e: SqlServerException) => assert(e.sqlState == "23505")
                                    case other                                 => fail(s"expected the server error to surface, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "a transport failure destroys the connection without firing a cancel" in {
        val config = baseConfig("fatal")
        withProbePool("fatal") { (pool, events) =>
            Abort.run[SqlException](lease(pool, config)(_.simpleQuery(Sql.transportError))).flatMap { outcome =>
                report(events, 2).flatMap { seen =>
                    pool.closeAll(30.seconds).andThen {
                        pool.metrics.cancelsFired.get.flatMap { fired =>
                            pool.metrics.connectionsDiscarded.get.map { discarded =>
                                assert(seen == Chunk("statement", "closed"), s"expected the connection to be closed, saw $seen")
                                assert(fired == 0L, s"a failed socket owes no cancel, cancels_fired=$fired")
                                assert(discarded == 1L, s"a protocol-fatal exit must destroy, discarded=$discarded")
                                outcome match
                                    case Result.Failure(_: SqlConnectionException) => succeed
                                    case other => fail(s"expected the transport error to surface, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "a statement that overruns queryTimeout fires the wire cancel" in {
        // The per-statement timeout interrupts the statement's fiber, so the exit path sees an in-flight
        // connection and owes the server a cancel exactly as any other interrupt does.
        val config = baseConfig("querytimeout").copy(queryTimeout = 100.millis)
        withProbePool("querytimeout", Script(), config) { (pool, events) =>
            // The per-statement timer arms before the statement reaches the wire, so a bare wall-clock 100ms races
            // connect. Freeze time, wait for the statement in flight, then advance so the query timeout interrupts.
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(
                    Abort.run[SqlException](pool.leaseStatement(address, Absent, config)(_.simpleQuery(Sql.hang)))
                ).map { fiber =>
                    report(events, 1).flatMap { first =>
                        assert(first == Chunk("statement"), s"expected the statement to reach the wire, saw $first")
                        control.advance(101.millis).andThen {
                            fiber.get.flatMap { outcome =>
                                report(events, 2).map { seen =>
                                    assert(seen == Chunk("cancel", "drain"), s"expected the timeout to fire the reclaim, saw $seen")
                                    outcome match
                                        case Result.Failure(_: SqlConnectionQueryTimeoutException) => succeed
                                        case other => fail(s"expected the query timeout to surface, got $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reproduces the fd leak at the pool boundary: an interrupt landing after `open` delivers a live connection but before the exit
      * finalizer registers drops it, so without a custody it is never reclaimed or closed. Driven without a driver: gate `open` on a latch,
      * interrupt the lease fiber racing the delivery, looped; a probe still open (socketOpen flag) after the pool closes is the leak. Its drain is non-reusable so every iteration re-enters connect (a reusable one would deadlock the gate).
      */
    private def handoverLeakTest(scope: String)(
        hold: (SqlConnectionPool[Probe], SqlConfig) => Any < (Async & Abort[SqlException])
    )(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[SqlException]) =
        val config = baseConfig(scope).copy(acquireTimeout = 30.seconds)
        Channel.initUnscoped[String](1024).flatMap { events =>
            AtomicLong.init(0).flatMap { ids =>
                Sync.Unsafe.defer(new java.util.concurrent.ConcurrentLinkedQueue[AtomicBoolean.Unsafe]()).flatMap { probes =>
                    AtomicRef.init(Maybe.empty[Latch]).flatMap { gateRef =>
                        AtomicRef.init(Maybe.empty[Latch]).flatMap { enteredRef =>
                            def closeProbe(p: Probe)(using Frame): Unit < Sync =
                                p.isOpen.map {
                                    case true  => Sync.Unsafe.defer(p.closeNow)
                                    case false => ()
                                }
                            // Build, claim, and track the probe synchronously: it stands in for the connection openSocket
                            // claims into the lease's custody at creation.
                            def createAndClaim(id: Long)(using Frame): Probe < (Async & Abort[SqlException]) =
                                Connection.custodyLocal.use { maybeCustody =>
                                    Sync.Unsafe.defer {
                                        val socketOpen = AtomicBoolean.Unsafe.init(true)
                                        val probe = new Probe(
                                            id,
                                            events,
                                            Script(drainReusable = false),
                                            AtomicBoolean.Unsafe.init(false),
                                            AtomicBoolean.Unsafe.init(false),
                                            socketOpen
                                        )
                                        maybeCustody match
                                            case Present(custody) => custody.claim(() => closeProbe(probe))
                                            case Absent           => ()
                                        probes.add(socketOpen)
                                        probe
                                    }
                                }
                            val factory = new Connection.Factory[Probe]:
                                def open(a: SqlConfig.Address, password: Maybe[String], c: SqlConfig)(using
                                    Frame
                                ): Probe < (Async & Abort[SqlException]) =
                                    // Gate after the claim so the interrupt lands at the connect->onLease handover with the claim
                                    // done, as a real connect does; gating before it would race the custody orphan against the claim, which a real handshake never does.
                                    ids.incrementAndGet.flatMap { id =>
                                        createAndClaim(id).flatMap { probe =>
                                            gateRef.get.flatMap {
                                                case Present(gate) =>
                                                    enteredRef.get.flatMap {
                                                        case Present(entered) => entered.release
                                                        case Absent           => ()
                                                    }.andThen(gate.await).andThen(probe)
                                                case Absent => probe
                                            }
                                        }
                                    }
                            Sync.Unsafe.defer(SqlConnectionPool.init(config, factory, Absent, summon[Frame])).flatMap { pool =>
                                Loop(0) { i =>
                                    if i >= 400 then Loop.done(())
                                    else
                                        Latch.init(1).flatMap { gate =>
                                            Latch.init(1).flatMap { entered =>
                                                gateRef.set(Present(gate)).andThen(enteredRef.set(Present(entered))).andThen {
                                                    Fiber.initUnscoped(
                                                        Abort.run[SqlException](hold(pool, config))
                                                    ).flatMap { fiber =>
                                                        entered.await
                                                            .andThen(gate.release)
                                                            .andThen(fiber.interrupt)
                                                            // Wait for the lease fiber to finish unwinding: untilCancelsSettled
                                                            // gates only the statement path's reclaim, so without this the loop can race a still-unwinding stream lease.
                                                            .andThen(fiber.getResult)
                                                            .andThen(untilCancelsSettled(pool))
                                                            .andThen(Loop.continue(i + 1))
                                                    }
                                                }
                                            }
                                        }
                                }.andThen {
                                    // Stop gating, then close the pool: it closes every reclaimed or pooled connection, so
                                    // a probe still open afterwards was leaked.
                                    gateRef.set(Absent).andThen(pool.closeAll(1.second)).andThen {
                                        // Poll until every probe closes: an interrupted lease closes on its own unwind (and,
                                        // for a statement, a detached reclaim), so one can still be closing here; a real leak never settles.
                                        Loop(0) { attempt =>
                                            Sync.Unsafe.defer {
                                                var leaked = 0
                                                val it     = probes.iterator()
                                                while it.hasNext do if it.next().get() then leaked += 1
                                                leaked
                                            }.flatMap { leaked =>
                                                if leaked == 0 || attempt >= 500 then Loop.done(leaked)
                                                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
                                            }
                                        }.flatMap { leaked =>
                                            Sync.Unsafe.defer {
                                                assert(
                                                    leaked == 0,
                                                    s"$leaked connection(s) leaked at the connect handover: opened, never reclaimed, never closed"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end handoverLeakTest

    "a statement lease interrupted at the connect handover closes the connection, never leaks it" in {
        handoverLeakTest("handover")((pool, config) =>
            pool.leaseStatement(address, Absent, config)(_.simpleQuery(Sql.hang))
        )
    }

    "a stream lease interrupted at the connect handover closes the connection, never leaks it" in {
        // The stream path (leaseScoped -> acquireScoped) owns the handover via the same withCustody as the statement
        // path; it was the leaking one, so it gets its own coverage.
        handoverLeakTest("handover-stream")((pool, config) =>
            Scope.run(pool.leaseScoped(address, Absent, config).andThen(Async.never))
        )
    }

end SqlConnectionCancelTest
