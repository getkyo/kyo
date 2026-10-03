package kyo.internal.sqlite

import kyo.*

/** The in-process admission to one database file's write lock: a connection in this process waits here, as a suspended fiber, before it
  * dispatches a native call that takes that lock.
  *
  * Without it a writer waits inside SQLite's busy handler, which parks the native thread running the call. On JS those calls run on
  * libuv's worker pool, four threads by default, so four parked writers leave no thread for the holder's COMMIT, the one call that would
  * free them, and the process wedges. With the gate a native thread only runs a write no other connection in this process holds the lock
  * against.
  *
  * Shared by every connection in the process that names the same file, whichever pool or factory opened it, since the worker pool is
  * process-wide too. Writers in other processes are invisible here and are still waited for in the busy handler.
  */
final private[sqlite] class SqliteWriteGate private (val path: String, meter: Meter):
    private[sqlite] def run[A](v: => A < (Async & Abort[Closed]))(using Frame): A < (Async & Abort[Closed]) = meter.run(v)
    private[sqlite] def waiters(using Frame): Int < (Async & Abort[Closed])                                 = meter.pendingWaiters

private[sqlite] object SqliteWriteGate:

    final private case class Entry(gate: SqliteWriteGate, references: Int)

    // Unsafe: process-wide state created at class loading. Connections from separate pools and factories share a file's gate only through
    // it, so it cannot be created per factory.
    private val registry: AtomicRef[Map[String, Entry]] =
        AtomicRef.Unsafe.init(Map.empty[String, Entry])(using AllowUnsafe.embrace.danger).safe

    /** The number of connections referencing the gate for `path`, zero once none does. */
    def references(path: String)(using Frame): Int < Sync =
        registry.get.map(_.get(path).fold(0)(_.references))

    private def reference(path: String, fresh: Meter)(using AllowUnsafe): SqliteWriteGate =
        registry.unsafe.updateAndGet { current =>
            current.updated(
                path,
                current.get(path).fold(Entry(new SqliteWriteGate(path, fresh), 1))(e => e.copy(references = e.references + 1))
            )
        }(path).gate

    private def unreference(path: String)(using AllowUnsafe): Unit =
        discard(registry.unsafe.getAndUpdate { current =>
            current.get(path) match
                case None                         => current
                case Some(e) if e.references <= 1 => current.removed(path)
                case Some(e)                      => current.updated(path, e.copy(references = e.references - 1))
        })

    /** One connection's hold on the gates of the files it can write, from the write that takes them to the end of the transaction that
      * wrote.
      *
      * A Meter lends its permit only for the duration of one `run`, and a transaction keeps the gates across many calls, so a holder fiber
      * runs the nested `run`s and parks inside them until [[release]] completes its promise. Interrupting the holder returns any permit it
      * took, through the Meter's own settlement.
      */
    final class Hold private (gates: AtomicRef[Chunk[SqliteWriteGate]], slot: AtomicRef[Maybe[Promise[Unit, Any]]]):

        /** Covers the files at `paths`, referencing their gates until [[close]]. An empty path, an in-memory or temporary database, has no
          * file lock and gets no gate.
          *
          * Sorted by path: a connection holding several takes them in this order, and two taking an overlapping pair in opposite orders
          * would deadlock.
          */
        def cover(paths: Chunk[String])(using Frame): Unit < Sync =
            val distinct = Chunk.from(paths.filter(_.nonEmpty).distinct.sorted)
            // Non-reentrant: the permit is held by a dedicated fiber per acquisition, never by the caller's.
            Kyo.foreach(distinct)(_ => Meter.initMutexUnscoped(reentrant = false)).map { fresh =>
                // One step, so no interrupt can land between referencing a gate and recording it for close to unreference.
                Sync.Unsafe.defer(gates.unsafe.set(distinct.zip(fresh).map((path, meter) => reference(path, meter))))
            }
        end cover

        def held(using Frame): Boolean < Sync = Sync.Unsafe.defer(unsafe.held())

        def covered(using Frame): Chunk[SqliteWriteGate] < Sync = gates.get

        /** Waits up to `limit` for every gate unless already held, answering whether they are held. Callers serialise on the connection's
          * statement meter, so two never race here.
          *
          * Bounded by the busy timeout because the wait replaces one inside SQLite's busy handler: a writer that waited past it was refused
          * with SQLITE_BUSY, and a caller holding a write transaction while it waits for another session's write relies on that refusal
          * rather than a wait that never ends.
          */
        def acquire(limit: Duration)(using Frame): Boolean < Async =
            covered.map { taking =>
                held.map { isHeld =>
                    if taking.isEmpty || isHeld then true
                    else
                        for
                            done     <- Promise.init[Unit, Any]
                            acquired <- Promise.init[Unit, Abort[Closed]]
                            body = taking.foldRight[Unit < (Async & Abort[Closed])](acquired.completeUnitDiscard.andThen(done.get)) {
                                (gate, inner) => gate.run(inner)
                            }
                            holder <- Fiber.initUnscoped(Abort.run[Closed](body).map {
                                case Result.Failure(closed) => acquired.completeDiscard(Result.fail(closed))
                                case _                      => Kyo.unit
                            })
                            // The slot is filled inside the guarded region, so an interrupt landing anywhere after the permits are taken
                            // still interrupts the holder, and leaves no promise in the slot for a later release to complete.
                            taken <-
                                Sync.ensure(error => if error.isEmpty then Kyo.unit else slot.set(Absent).andThen(holder.interrupt.unit)) {
                                    val waited: Result[Timeout, Result[Closed, Unit]] < Async =
                                        if limit == Duration.Infinity then Abort.run[Closed](acquired.get).map(r => Result.succeed(r))
                                        else Abort.run[Timeout](Async.timeout(limit)(Abort.run[Closed](acquired.get)))
                                    waited.map {
                                        case Result.Success(Result.Success(_)) => slot.set(Present(done)).andThen(true)
                                        // Interrupting the holder also returns a permit it took as the bound ran out.
                                        case Result.Failure(_) => holder.interrupt.andThen(false)
                                        // Unreachable while the registry holds the meters, none of which is ever closed.
                                        case Result.Success(Result.Failure(closed)) => Abort.panic(closed)
                                        case Result.Success(Result.Panic(t))        => Abort.panic(t)
                                        case Result.Panic(t)                        => Abort.panic(t)
                                    }
                                }
                        yield taken
                }
            }
        end acquire

        /** Gives the gates back if held. */
        def release(using Frame): Unit < Sync = Sync.Unsafe.defer(unsafe.release())

        /** Gives the gates back and drops the connection's references to them, once whichever close path calls it. */
        def close(using Frame): Unit < Sync = Sync.Unsafe.defer(unsafe.close())

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        object unsafe:
            def held()(using AllowUnsafe): Boolean = slot.unsafe.get().nonEmpty

            def release()(using AllowUnsafe): Unit =
                slot.unsafe.getAndSet(Absent).foreach(_.unsafe.completeUnitDiscard())

            def close()(using AllowUnsafe): Unit =
                release()
                gates.unsafe.getAndSet(Chunk.empty).foreach(gate => unreference(gate.path))
        end unsafe

    end Hold

    object Hold:
        def init(using Frame): Hold < Sync =
            for
                gates <- AtomicRef.init(Chunk.empty[SqliteWriteGate])
                slot  <- AtomicRef.init(Maybe.empty[Promise[Unit, Any]])
            yield new Hold(gates, slot)
    end Hold

end SqliteWriteGate
