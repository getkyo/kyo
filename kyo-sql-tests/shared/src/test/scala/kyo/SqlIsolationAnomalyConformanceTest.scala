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
  * Which engines a leaf compares is part of the claim: an anomaly is permitted or forbidden BY A LEVEL, so engines are comparable only at a
  * level they all honour, and an unnamed transaction only across engines that default alike.
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
            writer    <- Fiber.init {
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
      *   whether the two transactions synchronise while both are OPEN, which makes a lost update deterministic rather than a race. Only
      *   possible where two write transactions can be open at once: an engine taking the write lock at BEGIN deadlocks instead.
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

    /** Whether `level` permits a non-repeatable read, by the standard's definition. Owned by this suite rather than read from production
      * code, which would agree with itself and let a level start permitting the wrong anomaly unnoticed.
      */
    private def permitsNonRepeatableRead(level: SqlClient.IsolationLevel): Boolean =
        level match
            case SqlClient.IsolationLevel.ReadUncommitted | SqlClient.IsolationLevel.ReadCommitted => true
            case SqlClient.IsolationLevel.RepeatableRead | SqlClient.IsolationLevel.Serializable   => false

    /** What an unnamed transaction shows follows entirely from the level it runs at, so this splits on the descriptor's own default:
      * engines permitting the anomaly agree with each other, and engines forbidding it agree with each other.
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
      * Where the default permits a lost update the count is pinned and two engines agreeing on it is the finding. Where the default forbids
      * one, the assertion is the Serializable leaf's disjunction, since carrying both increments and refusing a writer are both correct.
      * That disjunction does not belong on the permitting side, where losing a write is allowed and `total == 3 || refused > 0` would call
      * correct behaviour a failure.
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
        // Read level AND write property, because one engine repeats reads and still loses updates. The complementary leaf below claims
        // the engines this excludes.
        forEachBackend(
            where = b => !permitsNonRepeatableRead(b.defaultIsolationLevel) && b.defaultPreventsLostUpdate,
            timeout = Present(60.seconds)
        ) {
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

    /** The other side: an engine declaring that its default does NOT prevent a lost update must actually exhibit one. Pinned rather than
      * skipped, so an engine that starts preventing the anomaly fails here and its capability answer gets deleted rather than going stale.
      */
    "two concurrent increments do lose a write where the default is declared not to prevent it" - {
        forEachBackend(where = b => !b.defaultPreventsLostUpdate, timeout = Present(60.seconds)) { (backend, client, schema) =>
            concurrentIncrements(Absent, rendezvous = backend.allowsConcurrentWriteTransactions)(backend, client, schema).map {
                (total, refused) =>
                    assert(
                        total == 2 && refused == 0,
                        s"${backend.label} declares its default does not prevent a lost update, so two increments from 1 must leave the " +
                            s"counter at 2 with neither writer refused; it read $total with $refused refused, and the declaration is stale"
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
