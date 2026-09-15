package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackend.ColumnType

/** What a transaction ISOLATES, compared across every registered backend: two concurrent sessions, one written program.
  *
  * The sibling suite proves the four levels are accepted and applied. Neither says what any of them MEANS, and a program that reads
  * differently on two engines has no symptom until its data is already wrong.
  *
  * Every leaf runs through [[SqlBackendTest.agreeAcrossBackends]], because a disagreement is the finding. Latches rather than sleeps, and the
  * second session is its own client on the same schema.
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
    private def concurrentIncrements(isolation: Maybe[SqlClient.IsolationLevel])(
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
                            _    <- barrier.release
                            _    <- barrier.await
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

    /** Reached without unusual code, and the levels themselves agree exactly (the two leaves below), so the disagreement is about which level
      * an unnamed one IS. That is session state the driver owes an answer for, so both backends pin `READ COMMITTED` at connect.
      */
    "a transaction that names no isolation level repeats a read the same way" in {
        agreeAcrossBackends(expected = Present("the two reads saw 1 then 99"))(reReadUnder(Absent))
    }

    "a transaction that names ReadCommitted repeats a read the same way" in {
        agreeAcrossBackends(expected = Present("the two reads saw 1 then 99"))(
            reReadUnder(Present(SqlClient.IsolationLevel.ReadCommitted))
        )
    }

    "a transaction that names RepeatableRead repeats a read the same way" in {
        agreeAcrossBackends(expected = Present("the two reads saw 1 then 1"))(
            reReadUnder(Present(SqlClient.IsolationLevel.RepeatableRead))
        )
    }

    /** A lost update at the level both engines now default to. Pinned rather than compared, so a change that made both engines lose the write
      * would still fail.
      */
    "two concurrent increments with no isolation level named reach the same total" in {
        agreeAcrossBackends(expected = Present("the counter reads 2, and the engine refused 0 of the two writers")) {
            (backend, client, schema) =>
                concurrentIncrements(Absent)(backend, client, schema).map { (total, refused) =>
                    s"the counter reads $total, and the engine refused $refused of the two writers"
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
        forEachBackend() { (backend, client, schema) =>
            concurrentIncrements(Present(SqlClient.IsolationLevel.Serializable))(backend, client, schema).map { (total, refused) =>
                assert(
                    total == 3 || refused > 0,
                    s"${backend.label}: the counter reads $total after two increments from 1 and no writer was refused, so a write was lost"
                )
            }
        }
    }

end SqlIsolationAnomalyConformanceTest
