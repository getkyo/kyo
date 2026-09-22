package kyo

import kyo.Sql.*
import kyo.internal.SqliteTempDatabase

/** [[SqliteAttach]] makes a schema-qualified name resolve on every connection in the pool.
  *
  * The property is about the POOL, so every leaf here opens more than one connection. `ATTACH` run as an ordinary statement reaches the one
  * connection that served it, leaving the next statement on a different connection to answer "no such table" for a name that worked a
  * moment earlier.
  *
  * File-backed databases, not `:memory:`: SQLite gives every CONNECTION its own private in-memory database, so a pool of them would share
  * no tables and these leaves would be measuring the wrong thing. That is also what confines them to the platforms with a filesystem.
  */
class SqliteAttachTest extends Test:

    case class Invoice(note: String) derives SqlSchema

    /** A throwaway database file, deleted with its WAL sidecars when the scope exits.
      *
      * Cancels where there is no filesystem to hold one, which is the same reachability the SQLite conformance descriptor reports, rather
      * than failing as though the attachment were broken.
      */
    private def tempDatabase(using Frame): String < (Sync & Scope) =
        assume(SqliteTempDatabase.available, "this platform has no filesystem, so an attached database has nowhere to live")
        Scope.acquireRelease(Sync.defer(SqliteTempDatabase.create()))(path => Sync.defer(SqliteTempDatabase.delete(path)))
    end tempDatabase

    "a qualified read resolves on every pooled connection" in {
        Scope.run {
            for
                main <- tempDatabase
                aux  <- tempDatabase
                config = SqlConfig.default.maxConnections(4).minConnections(4)
                    .extension(SqliteAttach(Map("aux" -> aux)))
                result <- SqlClient.init(s"sqlite://$main", config).map { client =>
                    DB.run(client) {
                        for
                            _ <- client.executeRaw("""CREATE TABLE "aux"."invoice" (note TEXT)""")
                            _ <- client.executeRaw("""INSERT INTO "aux"."invoice" VALUES ('attached')""")
                            // Four concurrent reads on a pre-warmed pool of four. Every one asserted: reading
                            // once could pass on whichever connection happened to carry the attachment.
                            notes <- Async.fill(4, 4)(Sql.from[Invoice](alias = "i", schemaName = "aux", tableName = "invoice").run)
                        yield assert(
                            notes.forall(_.map(_.note) == Chunk("attached")),
                            s"every connection must resolve aux.invoice, got ${notes.map(_.map(_.note)).mkString(",")}"
                        )
                        end for
                    }
                }
            yield result
        }
    }

    "the main database is reachable by name without any attachment" in {
        // `main` always exists, so qualification needs no configuration to reach the database the URL opened.
        Scope.run {
            tempDatabase.map { main =>
                SqlClient.init(s"sqlite://$main", SqlConfig.default.maxConnections(2)).map { client =>
                    DB.run(client) {
                        for
                            _    <- client.executeRaw("CREATE TABLE invoice (note TEXT)")
                            _    <- client.executeRaw("INSERT INTO invoice VALUES ('home')")
                            rows <- Sql.from[Invoice](alias = "i", schemaName = "main", tableName = "invoice").run
                        yield assert(rows.map(_.note) == Chunk("home"))
                        end for
                    }
                }
            }
        }
    }

    "the same table name in two schemas is two tables" in {
        Scope.run {
            for
                main <- tempDatabase
                aux  <- tempDatabase
                config = SqlConfig.default.maxConnections(2).extension(SqliteAttach(Map("aux" -> aux)))
                result <- SqlClient.init(s"sqlite://$main", config).map { client =>
                    DB.run(client) {
                        for
                            _     <- client.executeRaw("CREATE TABLE invoice (note TEXT)")
                            _     <- client.executeRaw("INSERT INTO invoice VALUES ('home')")
                            _     <- client.executeRaw("""CREATE TABLE "aux"."invoice" (note TEXT)""")
                            _     <- client.executeRaw("""INSERT INTO "aux"."invoice" VALUES ('attached')""")
                            here  <- Sql.from[Invoice](alias = "i", schemaName = "main", tableName = "invoice").run
                            there <- Sql.from[Invoice](alias = "i", schemaName = "aux", tableName = "invoice").run
                        yield
                            assert(here.map(_.note) == Chunk("home"), s"main holds home, got ${here.map(_.note)}")
                            assert(there.map(_.note) == Chunk("attached"), s"aux holds attached, got ${there.map(_.note)}")
                        end for
                    }
                }
            yield result
        }
    }

    "a database that cannot be attached fails the connection rather than opening it half configured" in {
        // Leaving the connection in the pool with a schema missing is the worse outcome: the pool would hold
        // connections disagreeing about which schemas exist, and which one a statement got would decide whether
        // it worked. The path below names a directory that does not exist, which SQLite cannot open.
        //
        // `minConnections = 1` is what makes this observable at `init`: with no warm-up the pool opens nothing
        // until a statement asks for a connection, so the refusal would arrive at the first query instead.
        Scope.run {
            tempDatabase.map { main =>
                val config = SqlConfig.default
                    .maxConnections(1)
                    .minConnections(1)
                    .extension(SqliteAttach(Map("aux" -> s"$main-absent/aux.db")))
                Abort.run[SqlException](SqlClient.init(s"sqlite://$main", config)).map { result =>
                    assert(result.isFailure, s"opening with an unattachable database must fail, got $result")
                }
            }
        }
    }

end SqliteAttachTest
