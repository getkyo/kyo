package kyo.postgres

import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlTestContainers

/** What [[PostgresConfig.searchPath]] does against a live server.
  *
  * It governs the statement text the renderer never sees: `sql"..."` fragments, `executeRaw` and migrations go out with whatever names the
  * caller wrote, so a program mixing those with the DSL still needs a session default. A statement that names its own schema carries it in
  * the SQL and is unaffected.
  *
  * What has to hold is that it reaches EVERY connection, first statement included. A `SET` issued through the pool reaches one connection
  * and leaves the rest resolving elsewhere.
  *
  * Two schemas hold the same table name here, deliberately. A connection resolving against the wrong one then answers a ROW rather than an
  * error, so a leaf using a name present in only one schema would report a missing table and never reach the case that matters.
  */
class PostgresConfigSearchPathIntegrationTest extends SqlContainerTest:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 5.minutes

    private def withServer[A](f: String => A < (Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (Async & Abort[SqlException | ContainerException] & Scope) =
        SqlTestContainers.initScopedPostgres(ContainerPredef.Postgres.Config.default, "postgres-search-path").flatMap { pg =>
            pg.container.mappedPort(pg.config.port).flatMap { port =>
                val url = s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}"
                SqlClient.init(url, SqlConfig.default.maxConnections(1)).flatMap { setup =>
                    DB.run(setup) {
                        // The same table name in two schemas, holding different rows.
                        setup.executeRaw("CREATE TABLE probe (note TEXT)")
                            .andThen(setup.executeRaw("INSERT INTO probe VALUES ('public')"))
                            .andThen(setup.executeRaw("CREATE SCHEMA app"))
                            .andThen(setup.executeRaw("CREATE TABLE app.probe (note TEXT)"))
                            .andThen(setup.executeRaw("INSERT INTO app.probe VALUES ('app')"))
                    }.andThen(f(url))
                }
            }
        }

    private def noteOn(client: SqlClient)(using Frame): String < (Async & Abort[SqlException]) =
        client.query("SELECT note FROM probe").map(_.head.decode[String](0))

    "an unqualified read resolves against the configured schema on every pooled connection".tagged("kyo.OwnContainer") in {
        Scope.run {
            withServer { url =>
                val config = SqlConfig.default
                    .maxConnections(10)
                    .minConnections(10)
                    .extension(PostgresConfig(searchPath = Chunk("app")))
                SqlClient.init(url, config).map { client =>
                    DB.run(client) {
                        // Ten concurrent reads on a pre-warmed pool, every one asserted. A leaf reading once would
                        // pass on whichever connection happened to be right, which is what makes a per-session
                        // search path look correct until it is read from several connections at once.
                        Async.fill(10, 10)(noteOn(client)).map { notes =>
                            assert(notes.forall(_ == "app"), s"every connection must resolve against app, got ${notes.mkString(",")}")
                        }
                    }
                }
            }
        }
    }

    "naming no search path leaves the server's own default standing".tagged("kyo.OwnContainer") in {
        Scope.run {
            withServer { url =>
                SqlClient.init(url, SqlConfig.default.maxConnections(2)).map { client =>
                    DB.run(client)(noteOn(client).map(note => assert(note == "public", s"expected the server's default, got $note")))
                }
            }
        }
    }

    "the configured search path survives a session reset".tagged("kyo.OwnContainer") in {
        Scope.run {
            withServer { url =>
                val config = SqlConfig.default.maxConnections(1).extension(PostgresConfig(searchPath = Chunk("app")))
                SqlClient.init(url, config).map { client =>
                    DB.run(client) {
                        // `DISCARD ALL` restores a parameter to its STARTUP value, so sending the search path in
                        // the startup packet is what makes a scrubbed session come back to it. A post-connect SET
                        // would be wiped here, leaving the connection resolving somewhere else for the rest of its life.
                        //
                        // Both reads go through the extended protocol, the path ordinary DSL statements take.
                        noteOn(client).map(before => assert(before == "app", s"expected app before the reset, got $before"))
                            .andThen(client.reset)
                            .andThen(noteOn(client).map(after => assert(after == "app", s"expected app after the reset, got $after")))
                    }
                }
            }
        }
    }

    "a schema named in the statement wins over the configured search path".tagged("kyo.OwnContainer") in {
        Scope.run {
            withServer { url =>
                val config = SqlConfig.default.maxConnections(2).extension(PostgresConfig(searchPath = Chunk("app")))
                SqlClient.init(url, config).map { client =>
                    DB.run(client) {
                        client.query("""SELECT note FROM "public"."probe"""").map(_.head.decode[String](0)).map { note =>
                            assert(note == "public", s"a qualified read must reach the schema it named, got $note")
                        }
                    }
                }
            }
        }
    }

    "two search paths on one client do not share pooled connections".tagged("kyo.OwnContainer") in {
        Scope.run {
            withServer { url =>
                val config = SqlConfig.default.maxConnections(4).extension(PostgresConfig(searchPath = Chunk("app")))
                SqlClient.init(url, config).map { client =>
                    DB.run(client) {
                        // Through the DSL run surface, deliberately. `DB.withConfig` re-scopes the settings the DSL
                        // threads into its lease; a direct `client.query` reads the CLIENT's own config and would not
                        // see the adjustment at all, so it would assert nothing about this.
                        //
                        // Both scopes lease from the SAME pool, so a search path outside the pool's identity would be
                        // served a connection opened under the other one. This is the leaf that pins
                        // SqlConnectionPool.Endpoint keying on the config's extensions.
                        Sql.from[Probe]("p", "probe").run.map(rows =>
                            assert(rows.head.note == "app", s"expected app, got ${rows.head.note}")
                        )
                            .andThen {
                                DB.withConfig(_.extension(PostgresConfig(searchPath = Chunk("public")))) {
                                    Async.fill(4, 4)(Sql.from[Probe]("p", "probe").run.map(_.head.note)).map { notes =>
                                        assert(
                                            notes.forall(_ == "public"),
                                            s"a re-scoped search path must not be served the other's connections, got ${notes.mkString(",")}"
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

end PostgresConfigSearchPathIntegrationTest

/** The row the DSL leaf reads, whose table name is `probe` in both schemas. */
case class Probe(note: String) derives SqlSchema
