package kyo

import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefTest extends Test:

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
        }
        "buildContainerConfig" - {
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
