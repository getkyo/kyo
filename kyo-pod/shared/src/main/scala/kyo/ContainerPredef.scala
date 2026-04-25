package kyo

import kyo.*

/** Pre-defined container fixtures for popular services. Each nested module (`Postgres`, `MySQL`, `MongoDB`) provides a typed `Config` with
  * sensible defaults, a healthcheck that drives the container's own CLI tool, and accessors for the connection URL and credentials. These
  * are container fixtures only — connect with whichever client library you prefer.
  *
  * Usage:
  * {{{
  * ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
  *   pg.jdbcUrl.map(url => /* … */)
  * }
  * }}}
  *
  * @see
  *   [[ContainerPredef.Postgres]] PostgreSQL fixture
  * @see
  *   [[ContainerPredef.MySQL]] MySQL fixture
  * @see
  *   [[ContainerPredef.MongoDB]] MongoDB fixture
  */
object ContainerPredef:

    // =============================================================================================
    // Postgres
    // =============================================================================================

    /** PostgreSQL container fixture for tests. Based on Testcontainers Java's
      * [[https://github.com/testcontainers/testcontainers-java/blob/main/modules/postgresql/src/main/java/org/testcontainers/containers/PostgreSQLContainer.java PostgreSQLContainer]]
      * (Apache 2.0).
      *
      * Defaults to `postgres:16-alpine` with user/password/database = `"test"` / `"test"` / `"test"`. The container runs
      * `postgres -c fsync=off` for test-speed. Healthcheck issues `psql -c "SELECT 1"` so the handle is only returned once init scripts
      * have created `POSTGRES_DB` — this avoids the `pg_isready` race where the readiness probe passes during the temporary-listener phase
      * before init scripts run.
      *
      * @see
      *   [[https://hub.docker.com/_/postgres Docker postgres image]]
      */
    final class Postgres private[kyo] (val container: Container, val config: Postgres.Config):

        /** JDBC connection URL: `jdbc:postgresql://host:port/database`. Looks up the dynamically-mapped host port for `config.port`. */
        def jdbcUrl(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map { hp =>
                s"jdbc:postgresql://${container.host}:$hp/${config.database}"
            }

        /** Configured database username. */
        def username: String = config.username

        /** Configured database password. */
        def password: String = config.password

        /** Configured default database name. */
        def database: String = config.database

        /** Run `psql` against this container as the configured user, returning the raw exec result. Uses `-tAc` so output is unaligned and
          * tuples-only — convenient for parsing single scalar results.
          */
        def psql(sql: String)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
            container.exec(
                "psql",
                "-U",
                config.username,
                "-d",
                config.database,
                "-tAc",
                sql
            )
    end Postgres

    object Postgres:

        /** Default image: `postgres:16-alpine`. */
        val defaultImage: ContainerImage = ContainerImage("postgres:16-alpine")

        /** Default container port — `5432`. */
        val defaultPort: Int = 5432

        /** Configuration for a [[Postgres]] container. Builder methods produce updated copies; the original instance is unchanged.
          *
          * @param image
          *   the Postgres container image (default `postgres:16-alpine`)
          * @param username
          *   value for the `POSTGRES_USER` env var (default `"test"`)
          * @param password
          *   value for the `POSTGRES_PASSWORD` env var (default `"test"`)
          * @param database
          *   value for the `POSTGRES_DB` env var (default `"test"`)
          * @param port
          *   the in-container port Postgres listens on (default `5432`); the host port is allocated dynamically
          */
        case class Config(
            image: ContainerImage = defaultImage,
            username: String = "test",
            password: String = "test",
            database: String = "test",
            port: Int = defaultPort
        ) derives CanEqual:
            /** Returns a copy with `image` replaced. */
            def image(i: ContainerImage): Config = copy(image = i)

            /** Returns a copy with `username` replaced. */
            def username(u: String): Config = copy(username = u)

            /** Returns a copy with `password` replaced. */
            def password(p: String): Config = copy(password = p)

            /** Returns a copy with `database` replaced. */
            def database(d: String): Config = copy(database = d)
        end Config

        object Config:
            /** Default configuration — equivalent to `Config()`. */
            val default: Config = Config()
        end Config

        /** Start a Postgres container scoped to the surrounding `Scope` — it is stopped and removed when the scope closes. */
        def init(config: Config = Config.default)(using Frame): Postgres < (Async & Abort[ContainerException] & Scope) =
            Container.init(buildContainerConfig(config)).map(c => new Postgres(c, config))

        /** Start a Postgres container without scope-managed cleanup. The caller is responsible for stopping it. */
        def initUnscoped(config: Config = Config.default)(using Frame): Postgres < (Async & Abort[ContainerException]) =
            Container.initUnscoped(buildContainerConfig(config)).map(c => new Postgres(c, config))

        /** Start a Postgres container and pass it to `f`, releasing the container when the surrounding scope closes. */
        def initWith[A, S](config: Config = Config.default)(f: Postgres => A < S)(using
            Frame
        ): A < (S & Async & Abort[ContainerException] & Scope) =
            init(config).map(f)

        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            Container.Config(c.image)
                .env("POSTGRES_USER", c.username)
                .env("POSTGRES_PASSWORD", c.password)
                .env("POSTGRES_DB", c.database)
                .port(c.port, 0)
                .command("postgres", "-c", "fsync=off")
                .healthCheck(Container.HealthCheck.exec(
                    Command("psql", "-U", c.username, "-d", c.database, "-c", "SELECT 1"),
                    Absent
                ))
    end Postgres

    // =============================================================================================
    // MySQL
    // =============================================================================================

    /** MySQL container fixture for tests. Based on Testcontainers Java's
      * [[https://github.com/testcontainers/testcontainers-java/blob/main/modules/mysql/src/main/java/org/testcontainers/containers/MySQLContainer.java MySQLContainer]]
      * (Apache 2.0).
      *
      * Defaults to `mysql:8.0` with user/password/database = `"test"` / `"test"` / `"test"` (and `rootPassword = "test"`). Healthcheck
      * issues `mysql -e "SELECT 1"` as the configured user — `mysqladmin ping` succeeds during MySQL's temporary-listener phase before init
      * scripts create the user, so a real query is required to avoid races.
      *
      * Root-user handling: `username = "root"` omits `MYSQL_USER` (root is implicit). An empty password is permitted only with the root
      * user (it sets `MYSQL_ALLOW_EMPTY_PASSWORD=yes`); a non-root user with an empty password fails at `init` with
      * [[ContainerOperationException]].
      *
      * @see
      *   [[https://hub.docker.com/_/mysql Docker mysql image]]
      */
    final class MySQL private[kyo] (val container: Container, val config: MySQL.Config):

        /** JDBC connection URL: `jdbc:mysql://host:port/database`. Looks up the dynamically-mapped host port for `config.port`. */
        def jdbcUrl(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map { hp =>
                s"jdbc:mysql://${container.host}:$hp/${config.database}"
            }

        /** Configured database username. */
        def username: String = config.username

        /** Configured database password. */
        def password: String = config.password

        /** Configured default database name. */
        def database: String = config.database

        /** Run `mysql` against this container as the configured user, returning the raw exec result. Uses `-N -e` so output is unaligned
          * and column-header-free — convenient for parsing single scalar results.
          */
        def mysql(sql: String)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
            val args = Chunk("mysql", "-u", config.username) ++
                (if config.password.nonEmpty then Chunk(s"-p${config.password}") else Chunk.empty) ++
                Chunk(config.database, "-N", "-e", sql)
            container.exec(Command(args*))
        end mysql
    end MySQL

    object MySQL:

        /** Default image: `mysql:8.0`. */
        val defaultImage: ContainerImage = ContainerImage("mysql:8.0")

        /** Default container port — `3306`. */
        val defaultPort: Int = 3306

        /** Configuration for a [[MySQL]] container. Builder methods produce updated copies; the original instance is unchanged.
          *
          * @param image
          *   the MySQL container image (default `mysql:8.0`)
          * @param username
          *   value for the `MYSQL_USER` env var (default `"test"`); when set to `"root"` the env var is omitted (root is implicit)
          * @param password
          *   value for the `MYSQL_PASSWORD` env var (default `"test"`); empty is permitted only when `username == "root"`, in which case
          *   `MYSQL_ALLOW_EMPTY_PASSWORD=yes` is set instead
          * @param database
          *   value for the `MYSQL_DATABASE` env var (default `"test"`)
          * @param rootPassword
          *   value for the `MYSQL_ROOT_PASSWORD` env var (default `"test"`)
          * @param port
          *   the in-container port MySQL listens on (default `3306`); the host port is allocated dynamically
          */
        case class Config(
            image: ContainerImage = defaultImage,
            username: String = "test",
            password: String = "test",
            database: String = "test",
            rootPassword: String = "test",
            port: Int = defaultPort
        ) derives CanEqual:
            /** Returns a copy with `image` replaced. */
            def image(i: ContainerImage): Config = copy(image = i)

            /** Returns a copy with `username` replaced. */
            def username(u: String): Config = copy(username = u)

            /** Returns a copy with `password` replaced. */
            def password(p: String): Config = copy(password = p)

            /** Returns a copy with `database` replaced. */
            def database(d: String): Config = copy(database = d)

            /** Returns a copy with `rootPassword` replaced. */
            def rootPassword(p: String): Config = copy(rootPassword = p)
        end Config

        object Config:
            /** Default configuration — equivalent to `Config()`. */
            val default: Config = Config()
        end Config

        /** Start a MySQL container scoped to the surrounding `Scope` — it is stopped and removed when the scope closes. Fails with
          * [[ContainerOperationException]] if `password` is empty and `username` is not `"root"`.
          */
        def init(config: Config = Config.default)(using Frame): MySQL < (Async & Abort[ContainerException] & Scope) =
            validateConfig(config).andThen {
                Container.init(buildContainerConfig(config)).map(c => new MySQL(c, config))
            }

        /** Start a MySQL container without scope-managed cleanup. The caller is responsible for stopping it. Fails with
          * [[ContainerOperationException]] if `password` is empty and `username` is not `"root"`.
          */
        def initUnscoped(config: Config = Config.default)(using Frame): MySQL < (Async & Abort[ContainerException]) =
            validateConfig(config).andThen {
                Container.initUnscoped(buildContainerConfig(config)).map(c => new MySQL(c, config))
            }

        /** Start a MySQL container and pass it to `f`, releasing the container when the surrounding scope closes. */
        def initWith[A, S](config: Config = Config.default)(f: MySQL => A < S)(using
            Frame
        ): A < (S & Async & Abort[ContainerException] & Scope) =
            init(config).map(f)

        private val rootUser = "root"

        private def validateConfig(c: Config)(using Frame): Unit < Abort[ContainerException] =
            if c.password.isEmpty && c.username != rootUser then
                Abort.fail(ContainerOperationException(
                    s"MySQL config invalid: empty password is only permitted with the root user (username=${c.username})"
                ))
            else ()

        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            val healthCmdBase = Chunk("mysql", "-h", "127.0.0.1", "-u", c.username) ++
                (if c.password.nonEmpty then Chunk(s"-p${c.password}") else Chunk.empty) ++
                Chunk(c.database, "-N", "-e", "SELECT 1")
            val base = Container.Config(c.image)
                .env("MYSQL_DATABASE", c.database)
                .port(c.port, 0)
                .healthCheck(Container.HealthCheck.exec(
                    Command(healthCmdBase*),
                    Absent,
                    Schedule.fixed(1.second).take(60)
                ))

            if c.username == rootUser then
                if c.password.isEmpty then base.env("MYSQL_ALLOW_EMPTY_PASSWORD", "yes")
                else base.env("MYSQL_ROOT_PASSWORD", c.rootPassword)
            else
                base
                    .env("MYSQL_USER", c.username)
                    .env("MYSQL_PASSWORD", c.password)
                    .env("MYSQL_ROOT_PASSWORD", c.rootPassword)
            end if
        end buildContainerConfig
    end MySQL

    // =============================================================================================
    // MongoDB
    // =============================================================================================

    /** MongoDB container fixture for tests. Based on Testcontainers Java's
      * [[https://github.com/testcontainers/testcontainers-java/blob/main/modules/mongodb/src/main/java/org/testcontainers/mongodb/MongoDBContainer.java MongoDBContainer]]
      * (Apache 2.0).
      *
      * Defaults to `mongo:7`. Healthcheck issues `mongosh --quiet --eval "db.adminCommand('ping').ok"` and asserts the result is `"1"`.
      *
      * Supports simple, single-node mode; for replica sets or sharding, instantiate a [[Container]] directly with the appropriate command
      * and exec `rs.initiate()` yourself.
      *
      * @see
      *   [[https://hub.docker.com/_/mongo Docker mongo image]]
      */
    final class MongoDB private[kyo] (val container: Container, val config: MongoDB.Config):

        /** The base connection URL without a database path: `mongodb://host:port`. Looks up the dynamically-mapped host port for
          * `config.port`.
          */
        def connectionString(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map { hp =>
                s"mongodb://${container.host}:$hp"
            }

        /** Connection URL for the configured database: `mongodb://host:port/<database>`. */
        def url(using Frame): String < (Async & Abort[ContainerException]) =
            connectionString.map(cs => s"$cs/${config.database}")

        /** The database name configured for the MongoDB instance. */
        def database: String = config.database

        /** Run `mongosh --quiet --eval "<eval>"` against this container, returning the raw exec result. */
        def mongosh(eval: String)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
            container.exec("mongosh", "--quiet", "--eval", eval)
    end MongoDB

    object MongoDB:

        /** Default image: `mongo:7`. */
        val defaultImage: ContainerImage = ContainerImage("mongo:7")

        /** Default container port — `27017`. */
        val defaultPort: Int = 27017

        /** Configuration for a [[MongoDB]] container. Builder methods produce updated copies; the original instance is unchanged.
          *
          * Note that unlike Postgres/MySQL, MongoDB does not pre-create databases at container startup — `database` is used only when
          * formatting the connection URL.
          *
          * @param image
          *   the MongoDB container image (default `mongo:7`)
          * @param database
          *   the database name embedded in [[MongoDB.url]] (default `"test"`)
          * @param port
          *   the in-container port MongoDB listens on (default `27017`); the host port is allocated dynamically
          */
        case class Config(
            image: ContainerImage = defaultImage,
            database: String = "test",
            port: Int = defaultPort
        ) derives CanEqual:
            /** Returns a copy with `image` replaced. */
            def image(i: ContainerImage): Config = copy(image = i)

            /** Returns a copy with `database` replaced. */
            def database(d: String): Config = copy(database = d)
        end Config

        object Config:
            /** Default configuration — equivalent to `Config()`. */
            val default: Config = Config()
        end Config

        /** Start a MongoDB container scoped to the surrounding `Scope` — it is stopped and removed when the scope closes. */
        def init(config: Config = Config.default)(using Frame): MongoDB < (Async & Abort[ContainerException] & Scope) =
            Container.init(buildContainerConfig(config)).map(c => new MongoDB(c, config))

        /** Start a MongoDB container without scope-managed cleanup. The caller is responsible for stopping it. */
        def initUnscoped(config: Config = Config.default)(using Frame): MongoDB < (Async & Abort[ContainerException]) =
            Container.initUnscoped(buildContainerConfig(config)).map(c => new MongoDB(c, config))

        /** Start a MongoDB container and pass it to `f`, releasing the container when the surrounding scope closes. */
        def initWith[A, S](config: Config = Config.default)(f: MongoDB => A < S)(using
            Frame
        ): A < (S & Async & Abort[ContainerException] & Scope) =
            init(config).map(f)

        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            Container.Config(c.image)
                .port(c.port, 0)
                .healthCheck(Container.HealthCheck.exec(
                    Command("mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"),
                    Present("1"),
                    Schedule.fixed(500.millis).take(60)
                ))
    end MongoDB

end ContainerPredef
