package kyo

import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefItTest extends BasePodTest:

    // Real database containers (postgres/mysql/mongo) include heavy image pulls (~600MB for
    // mysql:8.0) plus init scripts that can take 30-60s on a cold cache. The default 60s
    // per-test timeout is too tight for the first podman/shell run of each DB; raising to
    // 3 minutes leaves headroom while still catching genuine hangs.
    override def timeout: Duration = 3.minutes

    "connection URL and container identity" - {

        "Postgres url reaches the live container and names the host itself" - runBackendLong {
            // The accessor has to be verified against a live container, not a format string: it is the mapped
            // host port and the container's own host that make it usable, and both are only known at runtime.
            Postgres.initWith(Postgres.Config.default.username("e1").password("pw").database("e1db")) { pg =>
                for
                    url  <- pg.url
                    jdbc <- pg.jdbcUrl
                    port <- pg.container.mappedPort(5432)
                yield
                    assert(url == s"postgres://e1:pw@${pg.container.host}:$port/e1db", s"unexpected url: $url")
                    assert(url.startsWith("postgres://"), s"kyo-sql resolves the scheme, not jdbc: ($url)")
                    assert(!url.contains("jdbc"), s"the wire-protocol URL must not carry a jdbc prefix ($url)")
                    assert(jdbc == s"jdbc:postgresql://${pg.container.host}:$port/e1db")
                    // The host is read off the handle, so the caller never hard-codes the documented invariant.
                    assert(pg.container.host.nonEmpty)
                    assert(url.contains(pg.container.host))
            }
        }

        "MySQL url reaches the live container and names the host itself" - runBackendLong {
            MySQL.initWith(MySQL.Config.default.username("e1").password("pw").database("e1db")) { my =>
                for
                    url  <- my.url
                    port <- my.container.mappedPort(3306)
                yield
                    assert(url == s"mysql://e1:pw@${my.container.host}:$port/e1db", s"unexpected url: $url")
                    assert(url.startsWith("mysql://"), s"kyo-sql resolves the scheme, not jdbc: ($url)")
                    assert(url.contains(my.container.host))
            }
        }

        "a named, labelled Postgres fixture is findable by its label, not by its image" - runBackendLong {
            // The hazard this closes: an image-filtered teardown on a shared daemon reaches every other
            // postgres:16-alpine, including another process's database. A label filter matches exactly one.
            val label = "kyo-pod-it-" + java.util.UUID.randomUUID().toString.take(8)
            val cfg   = Postgres.Config.default.name(label).label("kyo.pod.it", label)
            Postgres.initWith(cfg) { pg =>
                for
                    byLabel <- Container.list(all = true, filters = Dict("label" -> Chunk(s"kyo.pod.it=$label")))
                yield
                    assert(byLabel.size == 1, s"expected exactly one container for label $label, got ${byLabel.size}")
                    assert(byLabel.head.id == pg.container.id)
                    assert(byLabel.head.names.exists(_.contains(label)), s"expected the chosen name, got ${byLabel.head.names}")
            }
        }

        "a label-scoped teardown removes its own container and leaves an identical sibling running" - runBackendLong {
            // The leaf above asserts a label filter matches one container, but on a daemon holding only that
            // container it would pass just as well if the filter were matching the IMAGE instead. The scenario
            // only discriminates once a SECOND postgres:16-alpine is running, and the half that costs somebody
            // their data if it is wrong is the teardown, not the listing.
            val mine       = "kyo-pod-it-" + java.util.UUID.randomUUID().toString.take(8)
            val sibling    = "kyo-pod-it-" + java.util.UUID.randomUUID().toString.take(8)
            val mineCfg    = Postgres.Config.default.name(mine).label("kyo.pod.it", mine)
            val siblingCfg = Postgres.Config.default.name(sibling).label("kyo.pod.it", sibling)

            def listBy(l: String)(using Frame) =
                Container.list(all = true, filters = Dict("label" -> Chunk(s"kyo.pod.it=$l")))

            // The sibling stays on this leaf's own Scope, so it outlives the teardown under test and is still
            // cleaned up when the leaf ends, however the leaf ends.
            Postgres.initWith(siblingCfg) { other =>
                for
                    // Scope.run closes MY container's scope here, which is what runs the teardown. initWith
                    // propagates Scope to its caller rather than closing it, so without this the container
                    // would still be running when the assertions below look for it.
                    _ <- Scope.run {
                        Postgres.initWith(mineCfg) { pg =>
                            listBy(mine).map { byMine =>
                                assert(byMine.size == 1, s"the label matched ${byMine.size} containers, not just mine")
                                assert(byMine.head.id == pg.container.id)
                                assert(byMine.head.id != other.container.id, "the label matched the identical sibling")
                            }
                        }
                    }
                    afterMine    <- listBy(mine)
                    afterSibling <- listBy(sibling)
                yield
                    assert(
                        afterMine.forall(_.state != Container.State.Running),
                        s"my container survived its own teardown: $afterMine"
                    )
                    assert(
                        afterSibling.size == 1 && afterSibling.head.state == Container.State.Running,
                        s"the identical sibling was taken down with it, which is the data-loss bug: $afterSibling"
                    )
                end for
            }
        }
    }

    "Postgres" - {
        "psql SELECT 1 returns 1" - runBackendLong {
            Postgres.initWith(Postgres.Config.default) { pg =>
                pg.psql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackendLong {
            val cfg = Postgres.Config.default.username("admin").database("mydb")
            Postgres.initWith(cfg) { pg =>
                pg.psql("SELECT current_user").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "admin", s"expected 'admin', got '${result.stdout.trim}'")
                }
            }
        }

        "create + insert + select round-trip" - runBackendLong {
            Postgres.initWith(Postgres.Config.default) { pg =>
                for
                    _    <- pg.psql("CREATE TABLE t (id int, name text)")
                    _    <- pg.psql("INSERT INTO t VALUES (1, 'kyo')")
                    rSel <- pg.psql("SELECT name FROM t WHERE id = 1")
                yield
                    assert(rSel.exitCode.toInt == 0, s"SELECT exited ${rSel.exitCode}, stderr=${rSel.stderr}")
                    assert(rSel.stdout.trim == "kyo", s"expected 'kyo', got '${rSel.stdout.trim}'")
            }
        }
    }

    "MySQL" - {
        "mysql SELECT 1 returns 1" - runBackendLong {
            MySQL.initWith(MySQL.Config.default) { my =>
                my.mysql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"mysql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackendLong {
            val cfg = MySQL.Config.default.username("admin").database("mydb")
            MySQL.initWith(cfg) { my =>
                my.mysql("SELECT current_user()").map { result =>
                    assert(result.exitCode.toInt == 0, s"mysql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(
                        result.stdout.trim.startsWith("admin@"),
                        s"expected current_user starting with 'admin@', got '${result.stdout.trim}'"
                    )
                }
            }
        }

        "create + insert + select round-trip" - runBackendLong {
            MySQL.initWith(MySQL.Config.default) { db =>
                for
                    _    <- db.mysql("CREATE TABLE t (id INT, name VARCHAR(32))")
                    _    <- db.mysql("INSERT INTO t VALUES (1, 'kyo')")
                    rSel <- db.mysql("SELECT name FROM t WHERE id = 1")
                yield
                    assert(rSel.exitCode.toInt == 0, s"SELECT exited ${rSel.exitCode}, stderr=${rSel.stderr}")
                    assert(rSel.stdout.trim == "kyo", s"expected 'kyo', got '${rSel.stdout.trim}'")
            }
        }
    }

    "MongoDB" - {
        "mongosh ping returns 1" - runBackendLong {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.mongosh("db.adminCommand('ping').ok").map { result =>
                    assert(result.exitCode.toInt == 0, s"mongosh exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "insert + count round-trip" - runBackendLong {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.mongosh("db.kyo.insertOne({hello: 'world'}); db.kyo.countDocuments()").map { result =>
                    assert(result.exitCode.toInt == 0, s"mongosh exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(
                        result.stdout.trim.endsWith("1"),
                        s"expected count ending in '1', got: ${result.stdout.trim}"
                    )
                }
            }
        }

        "find returns the inserted document" - runBackendLong {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                for
                    _     <- mg.mongosh("db.kyo.insertOne({name: 'kyo', id: 1})")
                    rFind <- mg.mongosh("db.kyo.findOne({id: 1}).name")
                yield
                    assert(rFind.exitCode.toInt == 0, s"findOne exited ${rFind.exitCode}, stderr=${rFind.stderr}")
                    // mongosh --quiet typically prints strings WITHOUT surrounding quotes in interactive mode;
                    // if the assertion fails due to quoted output like "\"kyo\"", accept either form.
                    val out = rFind.stdout.trim
                    assert(out == "kyo" || out == "\"kyo\"", s"expected 'kyo' (or '\"kyo\"'), got: $out")
            }
        }
    }

end ContainerPredefItTest
