package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackend.ColumnType

/** What a transaction ISOLATES, compared across every registered backend: two concurrent sessions, one written program.
  *
  * The sibling suite proves the four levels are accepted and applied. Neither says what any of them MEANS, and a program that reads
  * differently on two engines has no symptom until its data is already wrong.
  *
  * Most leaves run through [[SqlBackendTest.agreeAcrossBackends]], because a disagreement is the finding. Latches rather than sleeps, and the
  * second session is its own client on the same schema.
  *
  * Which engines a leaf compares is itself part of the claim. An anomaly is permitted or forbidden BY A LEVEL, so engines are only comparable
  * at a level they all honour, and an unnamed transaction is only comparable across engines that default alike. Holding engines with
  * different defaults to one answer would report a real difference in defaults as a failure, when that difference is exactly the thing a
  * caller moving a program between them needs to be told.
  */
class SqlIsolationAnomalyConformanceTest extends SqlBackendTest:

    case class Counter(id: Int, v: Int) derives SqlSchema, CanEqual

    private def seed(backend: SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException] & DB) =
        client.executeRaw(
            s"CREATE TABLE counter (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY, v ${backend.columnType(ColumnType.Int)})"
        ).andThen(Sql.insert[Counter].values(Counter(1, 1)).run).unit

    private def readV(using Frame): Int < (Async & Abort[SqlException] & DB) =
        Sql.from[Counter]("c").where(_.c.id == 1).select(_.c.v).run.map(_.head)

    /** Reads one row twice inside a transaction at `isolation`, while another session commits a change between the two reads, and answers
      * what the two reads saw.
      */
    private def reReadUnder(isolation: Maybe[SqlClient.IsolationLevel])(
        backend: SqlTestBackend,
        client: SqlClient,
        schema: SqlTestBackend.Schema
    )(using Frame): String < (Async & Abort[SqlException] & Scope & DB) =
        for
            _         <- seed(backend, client)
            firstRead <- Latch.init(1)
            committed <- Latch.init(1)
            other     <- SqlClient.initUnscoped(schema.url, SqlConfig())
            _         <- Scope.ensure(other.close)
            writer <- Fiber.init {
                firstRead.await.andThen {
                    // Released whatever the write did. Releasing only on success leaves the reader waiting forever when
                    // the writer fails, so the leaf reports a timeout instead of the assertion that would name the cause.
                    Abort.run[SqlException](
                        DB.run(other)(other.transaction(Sql.update[Counter].set(_.v := 99).where(_.id == 1).run))
                    ).andThen(committed.release)
                }
            }
            reads <- client.transaction(isolation, false) {
                for
                    before <- readV
                    _      <- firstRead.release
                    _      <- committed.await
                    after  <- readV
                yield (before, after)
            }
            _ <- writer.get
        yield s"the two reads saw ${reads._1} then ${reads._2}"

    /** Runs two sessions that each read the counter and then write the value after it, both at `isolation`, and answers the total the
      * counter ends on together with how many of the two writers the engine refused.
      *
      * Both writers pass a shared barrier between their read and their write, so each has read before either writes. That is what makes the
      * outcome deterministic without a sleep, and on an engine whose reads take locks it is also what puts both writers in the wait cycle at
      * once, so the engine resolves it immediately rather than after a lock-wait timeout.
      *
      * The refusal count is part of the answer because it is the half a caller can act on. An engine that refuses a writer has protected the
      * invariant and asked to be retried; an engine that refuses nobody and still loses a write has not, and the two are only
      * distinguishable if the answer says which happened.
      */
    /** @param rendezvous
      *   whether the two transactions synchronise with each other while both are OPEN. That interleaving is what makes a lost update
      *   deterministic rather than a race, and it can only happen on an engine where two write transactions can be open at once: where the
      *   write lock is taken at BEGIN, the second transaction never reaches the rendezvous and the first waits for it forever. Without it
      *   the two still run concurrently, and which of them the engine refuses is the engine's business.
      */
    private def concurrentIncrements(isolation: Maybe[SqlClient.IsolationLevel], rendezvous: Boolean)(
        backend: SqlTestBackend,
        client: SqlClient,
        schema: SqlTestBackend.Schema
    )(using Frame): (Int, Int) < (Async & Abort[SqlException] & Scope & DB) =
        def increment(on: SqlClient, barrier: Latch)(using Frame) =
            DB.run(on) {
                Abort.run[SqlException] {
                    on.transaction(isolation, false) {
                        for
                            seen <- readV
                            _    <- if rendezvous then barrier.release.andThen(barrier.await) else Kyo.unit
                            _    <- Sql.update[Counter].set(_.v := seen + 1).where(_.id == 1).run
                        yield ()
                    }
                }
            }
        for
            _       <- seed(backend, client)
            barrier <- Latch.init(2)
            other   <- SqlClient.initUnscoped(schema.url, SqlConfig())
            _       <- Scope.ensure(other.close)
            first   <- Fiber.init(increment(client, barrier))
            second  <- Fiber.init(increment(other, barrier))
            a       <- first.get
            b       <- second.get
            total   <- DB.run(client)(readV)
        yield (total, Chunk(a, b).count(_.isFailure))
        end for
    end concurrentIncrements

    /** Whether `level` permits a non-repeatable read, by the standard's definition.
      *
      * Owned by this suite rather than read from production code, for the same reason `expectedName` is in the sibling suite: derived from
      * the implementation it would agree with itself, and a level that started permitting the wrong anomaly would go unnoticed.
      */
    private def permitsNonRepeatableRead(level: SqlClient.IsolationLevel): Boolean =
        level match
            case SqlClient.IsolationLevel.ReadUncommitted | SqlClient.IsolationLevel.ReadCommitted => true
            case SqlClient.IsolationLevel.RepeatableRead | SqlClient.IsolationLevel.Serializable   => false

    /** Reached without unusual code, and what an unnamed transaction shows follows entirely from which level it runs at.
      *
      * Two leaves rather than one, split on the descriptor's own default. Engines that pin a level permitting the anomaly must agree with
      * each other, and engines whose floor forbids it must agree with each other; requiring all of them to answer the same string would
      * report a real difference in defaults as a failure, which is the opposite of what a caller needs to learn here.
      */
    "a transaction that names no isolation level repeats a read the same way, where the default permits it" in {
        agreeAcrossBackends(
            expected = Present("the two reads saw 1 then 99"),
            where = b => permitsNonRepeatableRead(b.defaultIsolationLevel)
        )(reReadUnder(Absent))
    }

    "a transaction that names no isolation level repeats a read the same way, where the default forbids it" in {
        agreeAcrossBackends(
            expected = Present("the two reads saw 1 then 1"),
            where = b => !permitsNonRepeatableRead(b.defaultIsolationLevel)
        )(reReadUnder(Absent))
    }

    "a transaction that names ReadCommitted repeats a read the same way" in {
        agreeAcrossBackends(
            expected = Present("the two reads saw 1 then 99"),
            where = _.honouredIsolationLevels.contains(SqlClient.IsolationLevel.ReadCommitted)
        )(reReadUnder(Present(SqlClient.IsolationLevel.ReadCommitted)))
    }

    "a transaction that names RepeatableRead repeats a read the same way" in {
        agreeAcrossBackends(
            expected = Present("the two reads saw 1 then 1"),
            where = _.honouredIsolationLevels.contains(SqlClient.IsolationLevel.RepeatableRead)
        )(reReadUnder(Present(SqlClient.IsolationLevel.RepeatableRead)))
    }

    /** A lost update at the level both engines now default to. Pinned rather than compared, so a change that made both engines lose the write
      * would still fail.
      */
    /** What two concurrent increments do at whichever level an unnamed transaction runs at, which is not one answer.
      *
      * Design section 6.2.4 proposed replacing the pinned count with the disjunction the Serializable leaf uses, `total == 3 || refused > 0`.
      * Measured against the engines, that is wrong here: at READ COMMITTED the lost update is PERMITTED and both engines duly lose it,
      * answering 2 with nobody refused, so the disjunction fails on the engines the leaf was written for. The anomaly a level permits is
      * exactly what this leaf exists to pin.
      *
      * So it splits on the level the engine actually defaults to. Where that level permits the anomaly the count is pinned, because two
      * engines agreeing on a lost update is the finding. Where it forbids the anomaly the disjunction applies, because carrying both
      * increments and refusing a writer are both correct and an agreement leaf cannot express a two-armed answer.
      */
    "two concurrent increments with no isolation level named reach the same total" in {
        agreeAcrossBackends(
            expected = Present("the counter reads 2, and the engine refused 0 of the two writers"),
            where = b => permitsNonRepeatableRead(b.defaultIsolationLevel)
        ) { (backend, client, schema) =>
            concurrentIncrements(Absent, rendezvous = true)(backend, client, schema).map { (total, refused) =>
                s"the counter reads $total, and the engine refused $refused of the two writers"
            }
        }
    }

    "two concurrent increments with no isolation level named do not silently lose a write, where the default forbids it" - {
        forEachBackend(where = b => !permitsNonRepeatableRead(b.defaultIsolationLevel), timeout = Present(60.seconds)) {
            (backend, client, schema) =>
                concurrentIncrements(Absent, rendezvous = backend.allowsConcurrentWriteTransactions)(backend, client, schema).map {
                    (total, refused) =>
                        assert(
                            total == 3 || refused > 0,
                            s"${backend.label}: its default level forbids the anomaly, so the counter reading $total after two increments " +
                                s"from 1 with $refused writers refused means a write was lost"
                        )
                }
        }
    }

    /** Per backend rather than compared: naming Serializable and still losing a write is wrong on one engine whatever the other does, and an
      * agreement leaf would pass on two engines that were both wrong.
      *
      * Either arm is correct: carrying both increments, or refusing a writer and asking to be retried. What neither may do is answer 2 with no
      * refusal.
      */
    "under Serializable two concurrent increments do not silently lose a write" - {
        forEachBackend(timeout = Present(60.seconds)) { (backend, client, schema) =>
            concurrentIncrements(
                Present(SqlClient.IsolationLevel.Serializable),
                rendezvous = backend.allowsConcurrentWriteTransactions
            )(backend, client, schema).map { (total, refused) =>
                assert(
                    total == 3 || refused > 0,
                    s"${backend.label}: the counter reads $total after two increments from 1 and no writer was refused, so a write was lost"
                )
            }
        }
    }

end SqlIsolationAnomalyConformanceTest
