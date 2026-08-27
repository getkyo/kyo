package kyo

import kyo.*
import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefTest extends BasePodTest:

    "container identity and connection URL" - {

        "Postgres.Config carries a name and labels through to the container config" in {
            // An unscoped fixture outlives the process that started it, so it is exactly the container that
            // needs a durable handle. Without one the only identity is the daemon-assigned name
            // (`relaxed_ellis`), and a teardown filtered by image reaches every other postgres:16-alpine on the
            // same daemon, including another process's database.
            val cfg   = Postgres.Config.default.name("e1-db").label("owner", "e1")
            val built = Postgres.buildContainerConfig(cfg)
            assert(built.name == Present("e1-db"))
            assert(built.labels.get("owner") == Present("e1"))
        }

        "Postgres.Config layers onto the general container surface without losing the fixture's own settings" in {
            val cfg = Postgres.Config.default
                .name("e1-db")
                .withContainer(_.memory(512L * 1024 * 1024).hostname("pg-host"))
            val built = Postgres.buildContainerConfig(cfg)
            // the caller's fields survive
            assert(built.name == Present("e1-db"))
            assert(built.memory == Present(512L * 1024 * 1024))
            assert(built.hostname == Present("pg-host"))
            // the fixture's own fields are applied on top
            assert(built.image == Postgres.defaultImage)
            assert(built.env.get("POSTGRES_USER") == Present("test"))
            assert(built.env.get("POSTGRES_DB") == Present("test"))
            assert(built.ports.exists(_.containerPort == 5432))
            assert(built.command.isDefined)
        }

        "MySQL.Config carries a name and labels, and keeps its own env and server args" in {
            val cfg   = MySQL.Config.default.name("e1-mysql").label("owner", "e1")
            val built = MySQL.buildContainerConfig(cfg)
            assert(built.name == Present("e1-mysql"))
            assert(built.labels.get("owner") == Present("e1"))
            assert(built.image == MySQL.defaultImage)
            assert(built.env.get("MYSQL_DATABASE") == Present("test"))
            assert(built.ports.exists(_.containerPort == 3306))
        }

        "MongoDB.Config carries a name and labels" in {
            val built = MongoDB.buildContainerConfig(MongoDB.Config.default.name("e1-mongo").label("owner", "e1"))
            assert(built.name == Present("e1-mongo"))
            assert(built.labels.get("owner") == Present("e1"))
            assert(built.image == MongoDB.defaultImage)
        }

        "an explicit image on the fixture wins over the one in the layered container config" in {
            val cfg = Postgres.Config.default
                .image(ContainerImage("postgres:15"))
                .withContainer(_.name("pinned"))
            val built = Postgres.buildContainerConfig(cfg)
            assert(built.image == ContainerImage("postgres:15"))
            assert(built.name == Present("pinned"))
        }

        "Postgres formats a wire-protocol URL, not only a JDBC one" in {
            // kyo-sql has no JDBC underneath and parses `<scheme>://[user[:password]@]host:port/db`, so the
            // jdbc: form fails on the scheme alone. This is the seam where a new user pastes the wrong one.
            assert(Postgres.formatUrl("127.0.0.1", 38835, "e1", "pw", "e1") == "postgres://e1:pw@127.0.0.1:38835/e1")
            assert(Postgres.formatJdbcUrl("127.0.0.1", 38835, "e1") == "jdbc:postgresql://127.0.0.1:38835/e1")
        }

        "MySQL formats a wire-protocol URL, not only a JDBC one" in {
            assert(MySQL.formatUrl("127.0.0.1", 38761, "e1", "pw", "e1") == "mysql://e1:pw@127.0.0.1:38761/e1")
            assert(MySQL.formatJdbcUrl("127.0.0.1", 38761, "e1") == "jdbc:mysql://127.0.0.1:38761/e1")
        }
    }

    "Postgres" - {
        "Config" - {
            "default has expected fields" in {
                val c = Postgres.Config.default
                assert(c.image == ContainerImage("postgres:16-alpine"))
                assert(c.username == "test")
                assert(c.password == "test")
                assert(c.database == "test")
                assert(c.port == 5432)
            }
            "username() builder is immutable" in {
                val base    = Postgres.Config.default
                val updated = base.username("admin")
                assert(base.username == "test")
                assert(updated.username == "admin")
                assert(updated.password == base.password)
                assert(updated.database == base.database)
                assert(updated.port == base.port)
                assert(updated.image == base.image)
            }
            "image() builder updates only image" in {
                val updated = Postgres.Config.default.image(ContainerImage("postgres:15"))
                assert(updated.image == ContainerImage("postgres:15"))
                assert(updated.username == "test")
            }
            "Postgres.Config.default == Postgres.Config()" in {
                assert(Postgres.Config.default == Postgres.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = Postgres.Config.default
                val updated = base.readinessBudget(30.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 30.seconds)
                assert(updated.port == base.port)
            }
        }
    }

    "MySQL" - {
        "Config" - {
            "default has expected fields" in {
                val c = MySQL.Config.default
                assert(c.image == ContainerImage("mysql:8.0"))
                assert(c.username == "test")
                assert(c.password == "test")
                assert(c.database == "test")
                assert(c.rootPassword == "test")
                assert(c.port == 3306)
            }
            "username() builder is immutable" in {
                val base    = MySQL.Config.default
                val updated = base.username("admin")
                assert(base.username == "test")
                assert(updated.username == "admin")
                assert(updated.password == base.password)
                assert(updated.database == base.database)
            }
            "image() builder updates only image" in {
                val updated = MySQL.Config.default.image(ContainerImage("mysql:8.4"))
                assert(updated.image == ContainerImage("mysql:8.4"))
                assert(updated.username == "test")
            }
            "rootPassword() builder updates rootPassword" in {
                val updated = MySQL.Config.default.rootPassword("supersecret")
                assert(updated.rootPassword == "supersecret")
                assert(updated.password == "test")
            }
            "MySQL.Config.default == MySQL.Config()" in {
                assert(MySQL.Config.default == MySQL.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = MySQL.Config.default
                val updated = base.readinessBudget(45.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 45.seconds)
                assert(updated.serverArgs == base.serverArgs)
            }
        }
        "buildContainerConfig" - {
            // The command line is `mysqld` followed by the memory caps followed by the caller's own args, and
            // the ORDER is load-bearing twice over. `mysqld` is the executable, so anything that replaces the
            // whole command drops it. The caller's args come last because MySQL takes the last spelling of a
            // repeated flag, which is what lets a fixture raise a cap the defaults lower.
            //
            // This pins the contract because the failure mode is silent: five fixtures were calling
            // `.command(...)` on the RESULT of this method, which replaced the line and dropped all three
            // caps, and the official image's entrypoint hides it by prepending `mysqld` to any argv whose
            // first element starts with a dash. The containers booted and simply took the memory back,
            // roughly 350MB each from performance_schema alone.
            "command line is mysqld, then the default caps, then the caller's serverArgs, in that order" in {
                val cfg  = MySQL.buildContainerConfig(MySQL.Config.default.appendServerArgs("--sql-mode=ANSI"))
                val args = cfg.command.map(_.args)
                assert(
                    args == Present(Chunk("mysqld") ++ MySQL.defaultServerArgs ++ Chunk("--sql-mode=ANSI")),
                    s"command line lost its executable, its caps or its ordering; got $args"
                )
                assert(
                    args.map(_.head) == Present("mysqld"),
                    "the first element is the executable, so anything replacing the command line drops it"
                )
                assert(
                    MySQL.defaultServerArgs.forall(a => args.exists(_.contains(a))),
                    s"every default memory cap must survive into the command line; got $args"
                )
            }
            "default config (non-root user, non-empty password) sets MYSQL_USER/PASSWORD/ROOT_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default)
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_USER") == Present("test"))
                assert(env.get("MYSQL_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ROOT_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Absent)
            }
            "root user with empty password sets MYSQL_ALLOW_EMPTY_PASSWORD and omits MYSQL_ROOT_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default.username("root").password(""))
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Present("yes"))
                assert(env.get("MYSQL_USER") == Absent)
                assert(env.get("MYSQL_PASSWORD") == Absent)
                assert(
                    env.get("MYSQL_ROOT_PASSWORD") == Absent,
                    "MYSQL_ROOT_PASSWORD must not be set alongside MYSQL_ALLOW_EMPTY_PASSWORD=yes (image rejects the combination)"
                )
            }
            "root user with non-empty password sets MYSQL_ROOT_PASSWORD and omits MYSQL_USER/MYSQL_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default.username("root").password("secret"))
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_ROOT_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Absent)
                assert(env.get("MYSQL_USER") == Absent)
                assert(env.get("MYSQL_PASSWORD") == Absent)
            }
        }
    }

    "MongoDB" - {
        "Config" - {
            "default has expected fields" in {
                val c = MongoDB.Config.default
                assert(c.image == ContainerImage("mongo:7"))
                assert(c.database == "test")
                assert(c.port == 27017)
            }
            "image() builder is immutable" in {
                val base    = MongoDB.Config.default
                val updated = base.image(ContainerImage("mongo:6"))
                assert(base.image == ContainerImage("mongo:7"))
                assert(updated.image == ContainerImage("mongo:6"))
                assert(updated.database == base.database)
                assert(updated.port == base.port)
            }
            "database() builder updates only database" in {
                val updated = MongoDB.Config.default.database("mydb")
                assert(updated.database == "mydb")
                assert(updated.image == MongoDB.Config.default.image)
            }
            "MongoDB.Config.default == MongoDB.Config()" in {
                assert(MongoDB.Config.default == MongoDB.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = MongoDB.Config.default
                val updated = base.readinessBudget(90.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 90.seconds)
                assert(updated.port == base.port)
            }
        }
    }

    "readinessScript" - {
        "embeds the configured budget as the loop deadline" in {
            // The generated shell loop computes its end as `date +%s` plus the budget in seconds, so a fixture's
            // readinessBudget must appear verbatim in the script. This pins the flow-through that the
            // Container.HealthCheck closure otherwise hides (it exposes only check/schedule, not the script).
            val script = ContainerPredef.readinessScript(Chunk("psql"), 30.seconds)
            assert(script.contains("+30)"), s"budget seconds must reach the loop deadline; got: $script")
        }
        "carries a Config's readinessBudget (default 120s)" in {
            val budget = Postgres.Config.default.readinessBudget
            assert(ContainerPredef.readinessScript(Chunk("psql"), budget).contains("+120)"))
        }
    }

    // =========================================================================
    // URL formatters — pure string formatting, no runtime needed
    // =========================================================================

    "Postgres.formatJdbcUrl" - {
        "default shape" in {
            assert(Postgres.formatJdbcUrl("127.0.0.1", 5432, "test") == "jdbc:postgresql://127.0.0.1:5432/test")
        }
        "preserves custom database name" in {
            assert(Postgres.formatJdbcUrl("127.0.0.1", 49154, "mydb") == "jdbc:postgresql://127.0.0.1:49154/mydb")
        }
        "ephemeral host port (e.g. 0-allocated mapping) matches expected pattern" in {
            val url = Postgres.formatJdbcUrl("127.0.0.1", 65432, "test")
            val pat = """^jdbc:postgresql://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

    "MySQL.formatJdbcUrl" - {
        "default shape" in {
            assert(MySQL.formatJdbcUrl("127.0.0.1", 3306, "test") == "jdbc:mysql://127.0.0.1:3306/test")
        }
        "preserves custom database name" in {
            assert(MySQL.formatJdbcUrl("127.0.0.1", 49155, "mydb") == "jdbc:mysql://127.0.0.1:49155/mydb")
        }
        "ephemeral host port matches expected pattern" in {
            val url = MySQL.formatJdbcUrl("127.0.0.1", 33060, "test")
            val pat = """^jdbc:mysql://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

    "MongoDB.formatConnectionString" - {
        "default shape (no database segment)" in {
            assert(MongoDB.formatConnectionString("127.0.0.1", 27017) == "mongodb://127.0.0.1:27017")
        }
    }

    "MongoDB.formatUrl" - {
        "default shape with database" in {
            assert(MongoDB.formatUrl("127.0.0.1", 27017, "test") == "mongodb://127.0.0.1:27017/test")
        }
        "preserves custom database name" in {
            assert(MongoDB.formatUrl("127.0.0.1", 49156, "mydb") == "mongodb://127.0.0.1:49156/mydb")
        }
        "ephemeral host port matches expected pattern" in {
            val url = MongoDB.formatUrl("127.0.0.1", 27018, "test")
            val pat = """^mongodb://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

end ContainerPredefTest
