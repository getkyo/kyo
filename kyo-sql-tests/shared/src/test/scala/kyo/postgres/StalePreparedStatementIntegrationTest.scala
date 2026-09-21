package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for recovering a cached prepared statement the server no longer holds.
  *
  * PostgreSQL-only, because on the MySQL lineage the scrub is the whole cause. It has no `0A000` analogue, since the server re-prepares on
  * a metadata change itself, and no SQL reachable through `executeRaw` drops a binary-protocol statement id: `DEALLOCATE PREPARE` names
  * only SQL-level `PREPARE`, and a revision-qualified `USE` keeps them across a real Dolt branch switch. With the cache dropped where the
  * scrub releases them, the only cause left there is an external pooler issuing `COM_RESET_CONNECTION` on the driver's behalf.
  *
  * Two causes, and which one a leaf needs is decided by whether the driver can SEE it. A `DEALLOCATE ALL` or `DISCARD ALL` the caller
  * issues is visible, because the server names it in the command tag and the cache is dropped before the next Bind; those leaves assert
  * that the drop happened rather than that a retry rescued it. DDL invalidating a cached plan is invisible until the server refuses the
  * Bind with `0A000`, so every leaf about the RETRY and its gates uses an `ALTER TABLE`, which also reaches the state from inside a
  * transaction block.
  *
  * The pool is pinned to a single connection throughout, because the cache is per connection: on two connections the second would start
  * empty and no statement would ever be stale.
  */
class StalePreparedStatementIntegrationTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    // Instruments no other suite shares: a `Metrics` asks the `Stat` registry for them by scope path and the
    // registry memoizes, so on the default scope every other client in this JVM moves the same counters.
    private val singleConn = SqlConfig.default.copy(
        maxConnections = 1,
        minConnections = 1,
        metricsEnabled = true,
        metricsScope = Present("kyo.sql.stale-prepared-statement")
    )

    private def withPg[A, S](
        f: SqlClient => A < (S & Async & Abort[SqlException] & DB)
    )(using Frame): A < (S & Async & Scope & Abort[SqlException] & Abort[SqlConnectionException] & Abort[ContainerException]) =
        SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
            val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
            SqlClient.init(url, singleConn).flatMap { client =>
                DB.run(client)(f(client))
            }
        }

    private def oneLong(rows: Chunk[SqlRow])(using Frame): Long < (Async & Abort[SqlException] & Abort[SqlDecodeException]) =
        rows.headMaybe match
            case Absent       => Abort.panic(new IllegalStateException("expected one row, got none"))
            case Present(row) => row.decode[Long](0)

    /** How many prepared statements this session holds, from the server's own session-local view. */
    private def serverStmtCount(client: SqlClient)(using
        Frame
    ): Long < (Async & Abort[SqlException] & Abort[SqlDecodeException]) =
        client.simpleQuery("SELECT count(*) FROM pg_prepared_statements").flatMap(oneLong)

    "a statement deallocated behind the driver's back resolves on the next call" in {
        Scope.run {
            withPg { client =>
                for
                    _      <- client.executeRaw("CREATE TABLE stale_probe (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _      <- client.executeRaw("INSERT INTO stale_probe VALUES (1, 101), (2, 102)")
                    before <- client.query(sql"SELECT amount FROM stale_probe WHERE id = ${1L}").flatMap(oneLong)
                    // Every server-side statement is gone; the cache still names them. Dropping the entry at the
                    // scrub cannot help here, because the driver never saw this one.
                    _      <- client.executeRaw("DEALLOCATE ALL")
                    after  <- client.query(sql"SELECT amount FROM stale_probe WHERE id = ${1L}").flatMap(oneLong)
                    again  <- client.query(sql"SELECT amount FROM stale_probe WHERE id = ${2L}").flatMap(oneLong)
                    healed <- client.query(sql"SELECT amount FROM stale_probe WHERE id = ${1L}").flatMap(oneLong)
                yield
                    assert(before == 101L, s"expected 101 before the deallocate, got $before")
                    assert(after == 101L, s"the statement must resolve after the deallocate, got $after")
                    assert(again == 102L, s"a second statement must resolve too, got $again")
                    assert(healed == 101L, s"the re-prepared statement must stay usable, got $healed")
                end for
            }
        }
    }

    "a deallocation the caller issued drops the cache instead of leaving it to the retry" in {
        Scope.run {
            withPg { client =>
                // The server reports what it ran, so a caller's own `DEALLOCATE ALL` is visible without reading
                // the SQL it was asked to run. Leaving it to the retry answers correctly too, at one dead Bind
                // and one re-parse for every statement the cache still named.
                val m = client.runtime.pool.metrics
                for
                    _ <- client.executeRaw("CREATE TABLE stale_watch (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_watch VALUES (1, 101)")
                    _ <- client.query(sql"SELECT amount FROM stale_watch WHERE id = ${1L}").flatMap(oneLong)
                    // Zero the baseline; `Counter.get` is a destructive read.
                    _          <- m.preparedStatementsReprepared.get
                    _          <- client.executeRaw("DEALLOCATE ALL")
                    after      <- client.query(sql"SELECT amount FROM stale_watch WHERE id = ${1L}").flatMap(oneLong)
                    reprepares <- m.preparedStatementsReprepared.get
                yield
                    assert(after == 101L, s"the statement must resolve after the caller's deallocate, got $after")
                    assert(
                        reprepares == 0L,
                        s"the driver saw the deallocation, so the next query must be a plain cache miss; it was recovered from instead ($reprepares)"
                    )
                end for
            }
        }
    }

    "a session the caller discarded drops the cache too" in {
        Scope.run {
            withPg { client =>
                // `DISCARD ALL` reaches the same state through a different tag, and is what a pool's own reset
                // query sends when the caller drives it rather than `SqlClient.reset`.
                val m = client.runtime.pool.metrics
                for
                    _          <- client.executeRaw("CREATE TABLE stale_discard (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _          <- client.executeRaw("INSERT INTO stale_discard VALUES (1, 101)")
                    _          <- client.query(sql"SELECT amount FROM stale_discard WHERE id = ${1L}").flatMap(oneLong)
                    _          <- m.preparedStatementsReprepared.get
                    _          <- client.executeRaw("DISCARD ALL")
                    after      <- client.query(sql"SELECT amount FROM stale_discard WHERE id = ${1L}").flatMap(oneLong)
                    reprepares <- m.preparedStatementsReprepared.get
                yield
                    assert(after == 101L, s"the statement must resolve after the caller's discard, got $after")
                    assert(
                        reprepares == 0L,
                        s"the driver saw the discard, so the next query must be a plain cache miss; it was recovered from instead ($reprepares)"
                    )
                end for
            }
        }
    }

    "the recovered statement is cached again rather than bypassed" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_cached (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_cached VALUES (1, 101)")
                    _ <- client.query(sql"SELECT amount FROM stale_cached WHERE id = ${1L}")
                    _ <- client.executeRaw("DEALLOCATE ALL")
                    _ <- client.query(sql"SELECT amount FROM stale_cached WHERE id = ${1L}")
                    // A recovery that fell back to parsing unnamed every time would answer correctly and leave the
                    // count at zero, so the count is what separates repair from bypass.
                    afterRetry <- serverStmtCount(client)
                    _          <- client.query(sql"SELECT amount FROM stale_cached WHERE id = ${1L}")
                    afterHit   <- serverStmtCount(client)
                yield
                    assert(afterRetry == 1L, s"the re-prepared statement must be registered server-side, got $afterRetry")
                    assert(afterHit == 1L, s"a hit on the repaired entry must not prepare another, got $afterHit")
                end for
            }
        }
    }

    "a reset the server refused leaves the statements it did not deallocate reachable" in {
        Scope.run {
            withPg { client =>
                for
                    _    <- client.executeRaw("CREATE TABLE stale_failed_reset (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _    <- client.executeRaw("INSERT INTO stale_failed_reset VALUES (1, 101)")
                    _    <- client.query(sql"SELECT amount FROM stale_failed_reset WHERE id = ${1L}")
                    held <- serverStmtCount(client)
                    // `executeRaw` takes a lease and gives it back, so the session goes to the pool with a block
                    // open. `reset` leases that same session (the pool holds one) and `DISCARD ALL` refuses:
                    // `PreventInTransactionBlock` fires before it deallocates anything.
                    _       <- client.executeRaw("BEGIN")
                    refused <- Abort.run[SqlException](client.reset)
                    _       <- Abort.run[SqlException](client.executeRaw("ROLLBACK"))
                    // The statement the refused reset never touched is still the server's. Forgetting it here
                    // strands it for the life of the connection, since its name went with the cache, and the
                    // re-Parse registers a second one beside it.
                    after     <- client.query(sql"SELECT amount FROM stale_failed_reset WHERE id = ${1L}").flatMap(oneLong)
                    stillHeld <- serverStmtCount(client)
                yield
                    refused match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "25001", s"expected DISCARD ALL to be refused with 25001, got ${e.sqlState}")
                        case other =>
                            fail(s"expected the reset to be refused inside a block, got: $other")
                    end match
                    assert(held == 1L, s"the statement must be registered before the reset, got $held")
                    assert(after == 101L, s"the statement must still answer after the refused reset, got $after")
                    assert(
                        stillHeld == 1L,
                        s"a refused reset deallocates nothing, so the session must still hold exactly its one statement, not $stillHeld"
                    )
                end for
            }
        }
    }

    "a missing SQL-level prepared statement is reported, not mistaken for a stale cache entry" in {
        Scope.run {
            withPg { client =>
                // `FetchPreparedStatement` is not only the Bind-path lookup. It is also what raises for the name
                // in a SQL-level `EXECUTE`, which reaches the server inside the portal this driver just bound and
                // executed successfully. Treating that as a stale cache entry drops a live one, re-parses, counts
                // a recovery that repaired nothing, and meets the identical error on the second attempt.
                val m = client.runtime.pool.metrics
                for
                    _ <- client.serverVersion
                    _ <- client.executeRaw("PREPARE pinned_stmt AS SELECT 1")
                    // Caches the driver's own statement for the text "EXECUTE pinned_stmt".
                    _ <- client.query("EXECUTE pinned_stmt")
                    _ <- client.executeRaw("DEALLOCATE pinned_stmt")
                    _ <- m.preparedStatementsReprepared.get
                    // The driver's statement is intact and binds; the server raises only once it runs the portal
                    // and cannot find `pinned_stmt`.
                    outcome    <- Abort.run[SqlException](client.query("EXECUTE pinned_stmt"))
                    reprepares <- m.preparedStatementsReprepared.get
                yield
                    outcome match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "26000", s"expected 26000 for the missing SQL-level statement, got ${e.sqlState}")
                            assert(
                                e.serverMessage.contains("pinned_stmt"),
                                s"the error must name the statement the caller asked for, got '${e.serverMessage}'"
                            )
                        case other =>
                            fail(s"expected the server to report the missing statement, got: $other")
                    end match
                    assert(
                        reprepares == 0L,
                        s"nothing about this connection's own cache was stale, so no re-prepare should have been counted ($reprepares)"
                    )
                end for
            }
        }
    }

    "a missing SQL-level prepared statement is reported through the streaming API too" in {
        Scope.run {
            withPg { client =>
                val m = client.runtime.pool.metrics
                for
                    _          <- client.serverVersion
                    _          <- client.executeRaw("PREPARE pinned_stream AS SELECT 1")
                    _          <- Scope.run(client.streamQuery("EXECUTE pinned_stream").run)
                    _          <- client.executeRaw("DEALLOCATE pinned_stream")
                    _          <- m.preparedStatementsReprepared.get
                    outcome    <- Abort.run[SqlException](Scope.run(client.streamQuery("EXECUTE pinned_stream").run))
                    reprepares <- m.preparedStatementsReprepared.get
                yield
                    outcome match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "26000", s"expected 26000 for the missing SQL-level statement, got ${e.sqlState}")
                        case other =>
                            fail(s"expected the server to report the missing statement, got: $other")
                    end match
                    assert(reprepares == 0L, s"the stream's own cache entry was never stale, so nothing should be counted ($reprepares)")
                end for
            }
        }
    }

    "a statement invalidated by DDL resolves against the new shape" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_ddl (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_ddl VALUES (1, 101)")
                    // A cached plan whose result rowtype the ALTER below changes. The server answers 0A000 from
                    // RevalidateCachedQuery on the next Bind rather than serving a plan that no longer describes
                    // the row.
                    before <- client.query(sql"SELECT * FROM stale_ddl WHERE id = ${1L}").map(_.head.size)
                    _      <- client.executeRaw("ALTER TABLE stale_ddl ADD COLUMN note text")
                    after  <- client.query(sql"SELECT * FROM stale_ddl WHERE id = ${1L}").map(_.head.size)
                yield
                    assert(before == 2, s"the cached statement must describe two columns, got $before")
                    assert(after == 3, s"the re-prepared statement must describe the added column, got $after")
                end for
            }
        }
    }

    "a stale statement inside a transaction block reports what happened, not what the retry would have hit" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_tx (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_tx VALUES (1, 101)")
                    _ <- client.query(sql"SELECT * FROM stale_tx WHERE id = ${1L}")
                    // Raw BEGIN, so no TransactionContext exists and the adapter's flag reads false inside a real
                    // block. The gate has to read the server's status byte to see the difference.
                    //
                    // DDL rather than a deallocation, because a deallocation is one the driver SEES: it reads the
                    // command tag and drops the cache, so no entry would be stale by the time this binds. An
                    // invalidated plan is the cause that remains invisible until the server refuses the Bind.
                    _       <- client.executeRaw("BEGIN")
                    _       <- client.executeRaw("ALTER TABLE stale_tx ADD COLUMN note text")
                    outcome <- Abort.run[SqlException](client.query(sql"SELECT * FROM stale_tx WHERE id = ${1L}"))
                    _       <- Abort.run[SqlException](client.executeRaw("ROLLBACK"))
                    // The session heals once the block is gone, which is what dropping the entry on a declined
                    // retry is for.
                    healed <- client.query(sql"SELECT * FROM stale_tx WHERE id = ${1L}").map(_.head.size)
                yield
                    outcome match
                        case Result.Failure(e: SqlServerException) =>
                            // 25P02 would mean the retry ran inside the aborted block and the caller read the
                            // retry's refusal in place of the failure that actually happened.
                            assert(e.sqlState == "0A000", s"expected the server's own 0A000, got ${e.sqlState}: ${e.serverMessage}")
                            assert(
                                e.extra.get("routine").contains("RevalidateCachedQuery"),
                                s"expected the RevalidateCachedQuery raise site, got ${e.extra.get("routine")}"
                            )
                        case other =>
                            fail(s"expected a server error inside the block, got: $other")
                    end match
                    assert(healed == 2, s"the rolled-back column is gone, so the re-prepared statement describes two, got $healed")
                end for
            }
        }
    }

    "a re-prepare that fails reports its own failure, not the stale statement's" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_gone (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_gone VALUES (1, 101)")
                    _ <- client.query(sql"SELECT amount FROM stale_gone WHERE id = ${1L}")
                    // The statement is dead AND the table it names is gone, so the retry's Parse fails too. 42P01
                    // is strictly more informative than the 26000 that provoked the retry, and it is what the
                    // caller needs; reporting the stale code would hide the dropped table.
                    _       <- client.executeRaw("DEALLOCATE ALL")
                    _       <- client.executeRaw("DROP TABLE stale_gone")
                    outcome <- Abort.run[SqlException](client.query(sql"SELECT amount FROM stale_gone WHERE id = ${1L}"))
                yield outcome match
                    case Result.Failure(e: SqlServerException) =>
                        assert(e.sqlState == "42P01", s"expected the re-Parse's 42P01, got ${e.sqlState}: ${e.serverMessage}")
                    case other =>
                        fail(s"expected a server error, got: $other")
            }
        }
    }

    "a retry re-encodes its parameters from the values rather than from the first attempt's bytes" in {
        Scope.run {
            withPg { client =>
                // An encoder run twice over its own output double-stringifies jsonb, flips boolean, and renders
                // bytea as the hex of its own hex. Every other leaf in this suite passes with that bug present,
                // which is why these three types are here.
                val payload = """{"k":"v"}"""
                val blob    = Span.from(Array[Byte](0x00, 0x7f, 0xff.toByte, 0x10))
                for
                    _ <- client.executeRaw(
                        "CREATE TABLE stale_codec (id bigint PRIMARY KEY, doc jsonb NOT NULL, flag boolean NOT NULL, raw bytea NOT NULL)"
                    )
                    insert = (id: Long) =>
                        client.execute(sql"INSERT INTO stale_codec (id, doc, flag, raw) VALUES ($id, $payload::jsonb, ${true}, $blob)")
                    _ <- insert(1L)
                    _ <- client.executeRaw("DEALLOCATE ALL")
                    // This INSERT is the one whose Bind is refused and re-bound. 26000 is raised before the portal
                    // exists, so the first attempt inserted nothing and the row count below pins that too.
                    _     <- insert(2L)
                    rows  <- client.query("SELECT id, doc::text, flag, raw FROM stale_codec ORDER BY id")
                    docs  <- Kyo.foreach(rows)(_.decode[String](1))
                    flags <- Kyo.foreach(rows)(_.decode[Boolean](2))
                    raws  <- Kyo.foreach(rows)(_.decode[Span[Byte]](3))
                yield
                    assert(rows.size == 2, s"the refused Bind must not have inserted a row of its own, got ${rows.size}")
                    assert(docs.forall(_ == """{"k": "v"}"""), s"the retried jsonb must round-trip unchanged, got $docs")
                    assert(flags == Chunk(true, true), s"the retried boolean must round-trip unchanged, got $flags")
                    assert(
                        raws.forall(_.toArray.sameElements(blob.toArray)),
                        s"the retried bytea must round-trip unchanged, got ${raws.map(_.toArray.toList)}"
                    )
                end for
            }
        }
    }

    "a stale statement resolves through the streaming API too" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_stream (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_stream VALUES (1, 101), (2, 102), (3, 103)")
                    read = Scope.run(client.streamQuery(sql"SELECT amount FROM stale_stream WHERE id > ${0L}").run)
                        .flatMap(rows => Kyo.foreach(rows)(_.decode[Long](0)))
                    before <- read
                    // The stream sends Bind with a Flush rather than a Sync, so its recovery has to ask for the
                    // barrier the gate reads the transaction status off.
                    _     <- client.executeRaw("DEALLOCATE ALL")
                    after <- read
                yield
                    assert(before == Chunk(101L, 102L, 103L), s"expected all three amounts before the deallocate, got $before")
                    assert(after == Chunk(101L, 102L, 103L), s"the stream must resolve after the deallocate, got $after")
                end for
            }
        }
    }

    "a stale statement inside a transaction block reports what happened through the streaming API too" in {
        Scope.run {
            withPg { client =>
                val read = (c: SqlClient) => Scope.run(c.streamQuery(sql"SELECT * FROM stale_tx_stream WHERE id > ${0L}").run)
                for
                    _ <- client.executeRaw("CREATE TABLE stale_tx_stream (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_tx_stream VALUES (1, 101)")
                    _ <- read(client)
                    // The stream asks for its own barrier to read the status off, so this is the path where the
                    // gate could read 'I' for a session that is really in a failed block. DDL rather than a
                    // deallocation, for the reason the non-streaming sibling gives.
                    _       <- client.executeRaw("BEGIN")
                    _       <- client.executeRaw("ALTER TABLE stale_tx_stream ADD COLUMN note text")
                    outcome <- Abort.run[SqlException](read(client))
                    _       <- Abort.run[SqlException](client.executeRaw("ROLLBACK"))
                    healed  <- read(client).map(rows => rows.map(_.size))
                yield
                    outcome match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "0A000", s"expected the server's own 0A000, got ${e.sqlState}: ${e.serverMessage}")
                        case other =>
                            fail(s"expected a server error inside the block, got: $other")
                    end match
                    assert(healed == Chunk(2), s"the rolled-back column is gone, so the re-prepared statement describes two, got $healed")
                end for
            }
        }
    }

    "a stream re-prepare that fails reports its own failure and leaves the wire usable" in {
        Scope.run {
            withPg { client =>
                for
                    _ <- client.executeRaw("CREATE TABLE stale_stream_gone (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _ <- client.executeRaw("INSERT INTO stale_stream_gone VALUES (1, 101)")
                    _ <- Scope.run(client.streamQuery(sql"SELECT amount FROM stale_stream_gone WHERE id > ${0L}").run)
                    // The retry's re-Parse fails, so the stream aborts with the barrier flag still lowered. The
                    // cleanup has to resynchronise anyway, which is what the follow-up query proves: one barrier
                    // outstanding, not zero and not two.
                    _       <- client.executeRaw("DEALLOCATE ALL")
                    _       <- client.executeRaw("DROP TABLE stale_stream_gone")
                    outcome <- Abort.run[SqlException](
                        Scope.run(client.streamQuery(sql"SELECT amount FROM stale_stream_gone WHERE id > ${0L}").run)
                    )
                    usable <- client.query("SELECT 7").flatMap(oneLong)
                yield
                    outcome match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "42P01", s"expected the re-Parse's 42P01, got ${e.sqlState}: ${e.serverMessage}")
                        case other =>
                            fail(s"expected a server error, got: $other")
                    end match
                    assert(usable == 7L, s"the connection must still answer after the failed re-prepare, got $usable")
                end for
            }
        }
    }

    "a pipeline slot that fails inside its own portal keeps the statement it bound" in {
        Scope.run {
            withPg { client =>
                // A SQL-level EXECUTE naming a deallocated statement raises 26000 from the same routine a stale
                // Bind does, but inside a portal the slot bound successfully. Evicting there queues a `Close 'S'`
                // for a statement that is still live, and the rows never say so: the next call re-parses and
                // answers correctly either way, so the server's own registry is the only witness.
                val batch = (b: SqlClient.PipelineBuilder) => b.query("EXECUTE pinned_pipe")
                for
                    _     <- client.executeRaw("PREPARE pinned_pipe AS SELECT 1")
                    _     <- client.pipeline(batch)
                    _     <- client.executeRaw("DEALLOCATE pinned_pipe")
                    stale <- client.pipeline(batch)
                    // Drains whatever the eviction queued, so the count below reflects the closes that were sent.
                    // Counting only THIS statement, because the drain query registers one of its own and a total
                    // would be satisfied by that alone.
                    _    <- client.query("SELECT 1")
                    held <- client.simpleQuery(
                        "SELECT count(*) FROM pg_prepared_statements WHERE statement = 'EXECUTE pinned_pipe'"
                    ).flatMap(oneLong)
                yield
                    assert(stale.forall(_.isFailure), s"the slot naming a deallocated statement must report: $stale")
                    assert(
                        held == 1L,
                        s"the driver's own statement bound fine and must still be registered, and the session holds $held of it"
                    )
                end for
            }
        }
    }

    "a stale statement in a pipeline is reported, and the next pipeline call succeeds" in {
        Scope.run {
            withPg { client =>
                // The batch goes out as one write, so by the time the client learns a slot was stale the later
                // slots have run. Re-running it could only put it after them, which reorders the batch. The entry
                // is dropped instead, which is what makes the second call below succeed.
                //
                // DDL rather than a deallocation, because a deallocation is one the driver SEES: it reads the
                // command tag and drops the cache, so nothing would be stale by the time the batch binds. An
                // invalidated plan stays invisible until the server refuses the Bind.
                val batch = (b: SqlClient.PipelineBuilder) =>
                    b.query(sql"SELECT * FROM stale_pipe WHERE id = ${1L}")
                        .andThen(b.query(sql"SELECT * FROM stale_pipe WHERE id = ${2L}"))
                for
                    _      <- client.executeRaw("CREATE TABLE stale_pipe (id bigint PRIMARY KEY, amount bigint NOT NULL)")
                    _      <- client.executeRaw("INSERT INTO stale_pipe VALUES (1, 101), (2, 102)")
                    _      <- client.pipeline(batch)
                    _      <- client.executeRaw("ALTER TABLE stale_pipe ADD COLUMN note text")
                    stale  <- client.pipeline(batch)
                    healed <- client.pipeline(batch)
                    widths <- Kyo.foreach(healed) {
                        case Result.Success(outcome) => outcome.rows.head.size
                        case other                   => Abort.panic(new IllegalStateException(s"expected a row, got $other"))
                    }
                yield
                    assert(
                        stale.forall(_.isFailure),
                        s"every slot bound a plan the DDL invalidated, so every slot must report: $stale"
                    )
                    assert(widths == Chunk(3, 3), s"the next pipeline call must succeed on re-prepared statements, got $widths")
                end for
            }
        }
    }

end StalePreparedStatementIntegrationTest
