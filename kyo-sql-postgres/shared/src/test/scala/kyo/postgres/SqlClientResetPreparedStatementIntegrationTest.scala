package kyo.postgres

import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlTestContainers

/** [[kyo.SqlClient.reset]] and the connection's prepared-statement cache agree about what the server still holds.
  *
  * `reset` runs `DISCARD ALL`, which includes `DEALLOCATE ALL`, so every server-side prepared statement is gone once it returns. A cache
  * still naming them would Bind a name the server no longer has, and the session would answer
  * `26000 prepared statement "s_..." does not exist`. It would keep answering it, because the entry stays cached: one `reset` would poison
  * that SQL on that connection for the rest of its life.
  *
  * Only the extended protocol reaches this, so the leaves run their probe through `query`. The simple protocol prepares nothing and would
  * pass whether or not the cache was invalidated, which is also why the introspection below uses `simpleQuery`: it reads
  * `pg_prepared_statements` without adding itself to it.
  *
  * A one-connection pool, because `reset` deliberately runs on a connection independent of any enclosing transaction. With a larger pool it
  * could scrub a session the probe never touches.
  */
class SqlClientResetPreparedStatementIntegrationTest extends SqlContainerTest:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 5.minutes

    private def withClient[A](table: String)(body: SqlClient => A < (Async & Abort[Any] & Scope))(using
        Frame
    )
        : A < (Async & Abort[Any] & Scope) =
        SqlTestContainers.initScopedPostgres(ContainerPredef.Postgres.Config.default, "postgres-reset-prepared").flatMap { pg =>
            pg.container.mappedPort(pg.config.port).flatMap { port =>
                val url = s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}"
                SqlClient.init(url, SqlConfig.default.maxConnections(1).minConnections(1)).flatMap { client =>
                    DB.run(client) {
                        for
                            _      <- client.executeRaw(s"DROP TABLE IF EXISTS $table")
                            _      <- client.executeRaw(s"CREATE TABLE $table (note TEXT)")
                            _      <- client.executeRaw(s"INSERT INTO $table VALUES ('x')")
                            result <- body(client)
                        yield result
                    }
                }
            }
        }

    "an extended query repeated after reset still resolves".tagged("kyo.OwnContainer") in {
        Scope.run {
            withClient("reset_probe") { client =>
                val sql = "SELECT note FROM reset_probe"
                for
                    // The first call leaves an entry in the cache naming a server-side statement.
                    _ <- client.query(sql)
                    _ <- client.reset
                    // The same SQL again, so a stale cache would Bind a name DISCARD ALL removed.
                    rows <- client.query(sql)
                    note <- rows.head.decode[String](0)
                yield assert(note == "x", s"the repeated query must still read the row, got $note")
                end for
            }
        }
    }

    "reset deallocates the statements the connection prepared".tagged("kyo.OwnContainer") in {
        Scope.run {
            withClient("reset_deallocate_probe") { client =>
                val count = "SELECT count(*) FROM pg_prepared_statements"
                for
                    _      <- client.query("SELECT note FROM reset_deallocate_probe")
                    before <- client.simpleQuery(count).map(_.head.decode[Long](0))
                    _      <- client.reset
                    after  <- client.simpleQuery(count).map(_.head.decode[Long](0))
                yield
                    assert(before > 0L, s"the extended query must leave a prepared statement on the session, found $before")
                    assert(after == 0L, s"DISCARD ALL must leave the session holding none, found $after")
                end for
            }
        }
    }

end SqlClientResetPreparedStatementIntegrationTest
