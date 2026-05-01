package kyo

import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefItTest extends Test:

    // Real database containers (postgres/mysql/mongo) include heavy image pulls (~600MB for
    // mysql:8.0) plus init scripts that can take 30-60s on a cold cache. The default 60s
    // per-test timeout is too tight for the first podman/shell run of each DB; raising to
    // 3 minutes leaves headroom while still catching genuine hangs.
    override def timeout: Duration = 3.minutes

    "Postgres" - {
        "psql SELECT 1 returns 1" - runBackendsLong {
            Postgres.initWith(Postgres.Config.default) { pg =>
                pg.psql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackendsLong {
            val cfg = Postgres.Config.default.username("admin").database("mydb")
            Postgres.initWith(cfg) { pg =>
                pg.psql("SELECT current_user").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "admin", s"expected 'admin', got '${result.stdout.trim}'")
                }
            }
        }

        "jdbcUrl format" - runBackendsLong {
            Postgres.initWith(Postgres.Config.default) { pg =>
                pg.jdbcUrl.map { url =>
                    val pattern = """^jdbc:postgresql://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(url), s"URL didn't match expected format: $url")
                }
            }
        }

        "create + insert + select round-trip" - runBackendsLong {
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
        "mysql SELECT 1 returns 1" - runBackendsLong {
            MySQL.initWith(MySQL.Config.default) { my =>
                my.mysql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"mysql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackendsLong {
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

        "jdbcUrl format" - runBackendsLong {
            MySQL.initWith(MySQL.Config.default) { my =>
                my.jdbcUrl.map { url =>
                    val pattern = """^jdbc:mysql://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(url), s"URL didn't match expected format: $url")
                }
            }
        }

        "create + insert + select round-trip" - runBackendsLong {
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
        "mongosh ping returns 1" - runBackendsLong {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.mongosh("db.adminCommand('ping').ok").map { result =>
                    assert(result.exitCode.toInt == 0, s"mongosh exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "insert + count round-trip" - runBackendsLong {
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

        "url format" - runBackendsLong {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.url.map { u =>
                    val pattern = """^mongodb://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(u), s"URL didn't match: $u")
                }
            }
        }

        "find returns the inserted document" - runBackendsLong {
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
