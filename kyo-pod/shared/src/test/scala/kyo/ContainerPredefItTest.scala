package kyo

import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefItTest extends Test:

    "Postgres" - {
        "psql SELECT 1 returns 1" - runBackends {
            Postgres.initWith(Postgres.Config.default) { pg =>
                pg.psql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackends {
            val cfg = Postgres.Config.default.username("admin").database("mydb")
            Postgres.initWith(cfg) { pg =>
                pg.psql("SELECT current_user").map { result =>
                    assert(result.exitCode.toInt == 0, s"psql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "admin", s"expected 'admin', got '${result.stdout.trim}'")
                }
            }
        }

        "jdbcUrl format" - runBackends {
            Postgres.initWith(Postgres.Config.default) { pg =>
                pg.jdbcUrl.map { url =>
                    val pattern = """^jdbc:postgresql://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(url), s"URL didn't match expected format: $url")
                }
            }
        }

        "create + insert + select round-trip" - runBackends {
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
        "mysql SELECT 1 returns 1" - runBackends {
            MySQL.initWith(MySQL.Config.default) { my =>
                my.mysql("SELECT 1").map { result =>
                    assert(result.exitCode.toInt == 0, s"mysql exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "custom credentials work" - runBackends {
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

        "jdbcUrl format" - runBackends {
            MySQL.initWith(MySQL.Config.default) { my =>
                my.jdbcUrl.map { url =>
                    val pattern = """^jdbc:mysql://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(url), s"URL didn't match expected format: $url")
                }
            }
        }

        "create + insert + select round-trip" - runBackends {
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
        "mongosh ping returns 1" - runBackends {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.mongosh("db.adminCommand('ping').ok").map { result =>
                    assert(result.exitCode.toInt == 0, s"mongosh exited ${result.exitCode}, stderr=${result.stderr}")
                    assert(result.stdout.trim == "1", s"expected '1', got '${result.stdout.trim}'")
                }
            }
        }

        "insert + count round-trip" - runBackends {
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

        "url format" - runBackends {
            MongoDB.initWith(MongoDB.Config.default) { mg =>
                mg.url.map { u =>
                    val pattern = """^mongodb://127\.0\.0\.1:\d+/test$""".r
                    assert(pattern.matches(u), s"URL didn't match: $u")
                }
            }
        }

        "find returns the inserted document" - runBackends {
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
