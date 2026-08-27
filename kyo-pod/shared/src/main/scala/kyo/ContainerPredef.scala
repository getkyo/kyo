package kyo

import kyo.*

/** Pre-defined container fixtures for popular services. Each nested module (`Postgres`, `MySQL`, `MongoDB`) provides a typed `Config` with
  * sensible defaults, a healthcheck that drives the container's own CLI tool, and accessors for the connection URL and credentials. These
  * are container fixtures only — connect with whichever client library you prefer.
  *
  * Usage:
  *
  * ```scala
  * ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
  *   pg.jdbcUrl.map(url => /* … */)
  * }
  * ```
  *
  * @see
  *   [[ContainerPredef.Postgres]] PostgreSQL fixture
  * @see
  *   [[ContainerPredef.MySQL]] MySQL fixture
  * @see
  *   [[ContainerPredef.MongoDB]] MongoDB fixture
  */
object ContainerPredef:

    /** The in-container readiness shell loop for `probe`, bounded by `budget`: probe until it exits 0 or the
      * budget elapses. Split out from [[readinessLoop]] so a fixture's configured budget can be asserted to
      * reach the generated script in a unit test, without standing up a container.
      */
    private[kyo] def readinessScript(probe: Chunk[String], budget: Duration): String =
        val quoted = probe.map(a => "'" + a.replace("'", "'\\''") + "'").mkString(" ")
        s"""end=$$(($$(date +%s)+${budget.toSeconds})); while [ "$$(date +%s)" -lt "$$end" ]; do $quoted >/dev/null 2>&1 && exit 0; sleep 2; done; exit 1"""
    end readinessScript

    /** A readiness [[Container.HealthCheck]] that runs `probe` in one bounded poll loop *inside* the container, not a
      * fresh host `exec` per retry. Each host `exec` leaves a `conmon` lingering ~300s on rootless podman, so a
      * sub-second host-side poll across a fixture suite accumulates orphaned `conmon`; the in-container loop collapses
      * each fixture's wait to a single `exec`. `budget` bounds the loop so a service that never comes up fails rather
      * than hang to the leaf timeout. Ready is exit code 0, so `probe` must exit non-zero until the service answers.
      */
    private[kyo] def readinessLoop(probe: Chunk[String], budget: Duration = 120.seconds): Container.HealthCheck =
        val cmd = Command("sh", "-c", readinessScript(probe, budget))
        new Container.HealthCheck:
            def check(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
                // The probe blocks inside the container until the service answers, so the exec must outlast the
                // caller's ambient HttpClient timeout (often the 5s default; predef fixtures are reused beyond kyo-pod).
                // Give it its own budget-covering timeout; the shell backend ignores the HttpClient config.
                HttpClient.withConfig(_.timeout(budget + 15.seconds)) {
                    Abort.run[ContainerException](container.exec(cmd)).map {
                        case Result.Success(r) if r.isSuccess => Kyo.unit
                        case Result.Success(r) =>
                            healthFailure(container, s"readiness probe did not pass within ${budget.toSeconds}s", r.stderr.trim)
                        case Result.Failure(cause) =>
                            // The readiness exec failed: usually the service's own container exited mid-boot (OOM or
                            // failed init), which the daemon reports as a not-running container. Capture why.
                            healthFailure(container, s"readiness exec failed: ${cause.getMessage}", "")
                        case Result.Panic(t) => Abort.panic(t)
                    }
                }
            end check
            def schedule: Schedule = Schedule.done
        end new
    end readinessLoop

    /** Best-effort container post-mortem: terminal state (status + exit code) and the tail of its logs. A flaky DB
      * fixture that dies or never becomes ready otherwise surfaces only as an opaque "already stopped" or "readiness
      * did not pass"; its own stderr names the cause (OOM-exit, failed init, bad config). Both reads are guarded so an
      * already-reaped container yields a placeholder rather than masking the original failure with a secondary error.
      */
    private[kyo] def postMortem(container: Container)(using Frame): String < Async =
        Abort.run[ContainerException](container.inspect).map { infoR =>
            Abort.run[ContainerException](container.logsText(tail = 40)).map { logsR =>
                val st = infoR match
                    case Result.Success(i) => s"container state=${i.state} exitCode=${i.exitCode}"
                    case _                 => "container state=unavailable"
                val lg = logsR match
                    case Result.Success(t) if t.trim.nonEmpty => s"; log tail: ${t.trim.linesIterator.toList.takeRight(20).mkString(" ⏎ ")}"
                    case _                                    => "; logs unavailable"
                s"$st$lg"
            }
        }

    /** Raise a [[ContainerHealthCheckException]] enriched with the container's [[postMortem]]. Used by the predef
      * fixtures so a flaky DB failure on a constrained runner is self-diagnosing rather than an opaque abort.
      */
    private[kyo] def healthFailure(container: Container, reason: String, probeError: String)(using
        Frame
    ): Nothing < (Async & Abort[ContainerException]) =
        postMortem(container).map { diag =>
            val detail = Chunk(probeError, diag).filter(_.nonEmpty).mkString(" | ")
            Abort.fail[ContainerException](ContainerHealthCheckException(container.id, reason, attempts = 1, lastError = detail))
        }

    /** Run a fixture container operation; on an abort (e.g. the container died and the daemon reports it not running)
      * re-raise it enriched with the container's [[postMortem]]. Successful results (including a query that ran but
      * returned a non-zero exit) pass through unchanged for the caller to assert on.
      */
    private[kyo] def withPostMortem[A](container: Container, op: String)(body: => A < (Async & Abort[ContainerException]))(using
        Frame
    ): A < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](body).map {
            case Result.Success(a)     => a
            case Result.Failure(cause) => healthFailure(container, s"$op failed: ${cause.getMessage}", "")
            case Result.Panic(t)       => Abort.panic(t)
        }

    // =============================================================================================
    // Postgres
    // =============================================================================================

    /** PostgreSQL container fixture for tests. Based on Testcontainers Java's
      * [PostgreSQLContainer](https://github.com/testcontainers/testcontainers-java/blob/main/modules/postgresql/src/main/java/org/testcontainers/containers/PostgreSQLContainer.java)
      * (Apache 2.0).
      *
      * Defaults to `postgres:16-alpine` with user/password/database = `"test"` / `"test"` / `"test"`. The container runs
      * `postgres -c fsync=off` for test-speed. Healthcheck issues `psql -c "SELECT 1"` so the handle is only returned once init scripts
      * have created `POSTGRES_DB` — this avoids the `pg_isready` race where the readiness probe passes during the temporary-listener phase
      * before init scripts run.
      *
      * @see
      *   [Docker postgres image](https://hub.docker.com/_/postgres)
      */
    final class Postgres private[kyo] (
        /** The underlying container handle for direct lifecycle and exec operations. */
        val container: Container,
        /** The configuration this fixture was started with. */
        val config: Postgres.Config
    ):

        /** JDBC connection URL: `jdbc:postgresql://host:port/database`. Looks up the dynamically-mapped host port for `config.port`. */
        def jdbcUrl(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp => Postgres.formatJdbcUrl(container.host, hp, config.database))

        /** Connection URL in the `postgres://user:password@host:port/database` form.
          *
          * This is the URL a driver that speaks the Postgres wire protocol directly takes, kyo-sql among them, and
          * it is the one to reach for first: `jdbcUrl` names a technology kyo-sql deliberately does not use, so
          * pasting it into a driver that wants this scheme fails on the scheme alone. The host comes from the
          * container handle rather than the caller, and the port is the dynamically-mapped one, so nothing about
          * the string has to be reassembled by hand.
          *
          * The credentials are written literally, matching how a wire-protocol URL is parsed: the last `@` ends
          * the userinfo and the first `:` inside it splits user from password. A username containing `:` or `@`
          * therefore cannot be expressed in this form.
          */
        def url(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp =>
                Postgres.formatUrl(container.host, hp, config.username, config.password, config.database)
            )

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
            withPostMortem(container, "psql")(container.exec(
                "psql",
                "-U",
                config.username,
                "-d",
                config.database,
                "-tAc",
                sql
            ))
    end Postgres

    object Postgres:

        /** Default image: `postgres:16-alpine`. */
        val defaultImage: ContainerImage = ContainerImage("postgres:16-alpine")

        /** Default container port — `5432`. */
        val defaultPort: Int = 5432

        /** Format a Postgres JDBC URL: `jdbc:postgresql://host:port/database`. */
        private[kyo] def formatJdbcUrl(host: String, port: Int, database: String): String =
            s"jdbc:postgresql://$host:$port/$database"

        /** Format a Postgres wire-protocol URL: `postgres://user:password@host:port/database`. */
        private[kyo] def formatUrl(host: String, port: Int, user: String, password: String, database: String): String =
            s"postgres://$user:$password@$host:$port/$database"

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
          * @param container
          *   the general [[Container.Config]] this fixture layers its own settings onto, so choosing the fixture
          *   does not cost the general surface. The fixture owns what makes it a Postgres container (image, the
          *   `POSTGRES_*` env, the published port, the server command, the healthcheck) and applies those last;
          *   everything else the caller sets here (a name, labels, mounts, networks, resource limits) is kept.
          *   Setting a name or labels is what gives an `initUnscoped` container an identity to find again: a
          *   daemon-assigned name like `relaxed_ellis` says nothing, and filtering a teardown by image can reach
          *   another process's database running the same one.
          */
        final case class Config(
            image: ContainerImage = defaultImage,
            username: String = "test",
            password: String = "test",
            database: String = "test",
            port: Int = defaultPort,
            container: Container.Config = Container.Config(defaultImage),
            readinessBudget: Duration = 120.seconds
        ) derives CanEqual:
            def image(i: ContainerImage): Config = copy(image = i)
            def username(u: String): Config      = copy(username = u)
            def password(p: String): Config      = copy(password = p)
            def database(d: String): Config      = copy(database = d)

            /** Names the container, so it can be found again after the process that started it is gone. */
            def name(n: String): Config = withContainer(_.name(n))

            /** Adds one label. Labels are the precise teardown filter: an image filter matches every other
              * container running the same image, including another process's.
              */
            def label(key: String, value: String): Config = withContainer(_.label(key, value))

            /** Applies `f` to the general container config this fixture layers onto. */
            def withContainer(f: Container.Config => Container.Config): Config =
                copy(container = f(container))
            def readinessBudget(d: Duration): Config = copy(readinessBudget = d)
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

        /** Build the [[Container.Config]] for a Postgres fixture from this config.
          *
          * The caller's own container config is the BASE and the fixture's settings are applied on top, so a
          * field the fixture does not own survives untouched however `Container.Config` grows. `image` is taken
          * from the fixture's own field, which is the one the builders write.
          */
        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            c.container.copy(image = c.image)
                .env("POSTGRES_USER", c.username)
                .env("POSTGRES_PASSWORD", c.password)
                .env("POSTGRES_DB", c.database)
                .port(c.port, 0)
                .command("postgres", "-c", "fsync=off")
                .requireService(true)
                .healthCheck(readinessLoop(Chunk("psql", "-U", c.username, "-d", c.database, "-c", "SELECT 1"), c.readinessBudget))
    end Postgres

    // =============================================================================================
    // MySQL
    // =============================================================================================

    /** MySQL container fixture for tests. Based on Testcontainers Java's
      * [MySQLContainer](https://github.com/testcontainers/testcontainers-java/blob/main/modules/mysql/src/main/java/org/testcontainers/containers/MySQLContainer.java)
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
      *   [Docker mysql image](https://hub.docker.com/_/mysql)
      */
    final class MySQL private[kyo] (
        /** The underlying container handle for direct lifecycle and exec operations. */
        val container: Container,
        /** The configuration this fixture was started with. */
        val config: MySQL.Config
    ):

        /** JDBC connection URL: `jdbc:mysql://host:port/database`. Looks up the dynamically-mapped host port for `config.port`. */
        def jdbcUrl(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp => MySQL.formatJdbcUrl(container.host, hp, config.database))

        /** Connection URL in the `mysql://user:password@host:port/database` form.
          *
          * The URL a driver that speaks the MySQL wire protocol directly takes, kyo-sql among them. See
          * [[ContainerPredef.Postgres.url]] for why this sits next to `jdbcUrl` and how the credentials are
          * written.
          */
        def url(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp =>
                MySQL.formatUrl(container.host, hp, config.username, config.password, config.database)
            )

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
            val args = Chunk("mysql", "-h", "127.0.0.1", "-u", config.username) ++
                (if config.password.nonEmpty then Chunk(s"-p${config.password}") else Chunk.empty) ++
                Chunk(config.database, "-N", "-e", sql)
            withPostMortem(container, "mysql")(container.exec(Command(args*)))
        end mysql
    end MySQL

    object MySQL:

        /** Default image: `mysql:8.0`. */
        val defaultImage: ContainerImage = ContainerImage("mysql:8.0")

        /** Default container port — `3306`. */
        val defaultPort: Int = 3306

        /** Format a MySQL JDBC URL: `jdbc:mysql://host:port/database`. */
        private[kyo] def formatJdbcUrl(host: String, port: Int, database: String): String =
            s"jdbc:mysql://$host:$port/$database"

        /** Format a MySQL wire-protocol URL: `mysql://user:password@host:port/database`. */
        private[kyo] def formatUrl(host: String, port: Int, user: String, password: String, database: String): String =
            s"mysql://$user:$password@$host:$port/$database"

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
        final case class Config(
            image: ContainerImage = defaultImage,
            username: String = "test",
            password: String = "test",
            database: String = "test",
            rootPassword: String = "test",
            port: Int = defaultPort,
            /** Extra `mysqld` command-line args appended after [[defaultServerArgs]]. Use for per-fixture overrides such as
              * `--default-authentication-plugin=mysql_native_password` or `--innodb-buffer-pool-size=<larger>`; anything set here follows
              * the baseline args, so later flags win by MySQL's last-write semantics.
              */
            serverArgs: Chunk[String] = Chunk.empty,
            /** The general [[Container.Config]] this fixture layers its own settings onto. See
              * [[ContainerPredef.Postgres.Config.container]]: the fixture owns image, the `MYSQL_*` env, the
              * published port, the server args and the healthcheck, and everything else the caller sets here (a
              * name, labels, mounts, networks, resource limits) is kept.
              */
            container: Container.Config = Container.Config(defaultImage),
            readinessBudget: Duration = 120.seconds
        ) derives CanEqual:
            def image(i: ContainerImage): Config        = copy(image = i)
            def username(u: String): Config             = copy(username = u)
            def password(p: String): Config             = copy(password = p)
            def database(d: String): Config             = copy(database = d)
            def rootPassword(p: String): Config         = copy(rootPassword = p)
            def serverArgs(args: Chunk[String]): Config = copy(serverArgs = args)
            def appendServerArgs(args: String*): Config = copy(serverArgs = serverArgs ++ Chunk.from(args))

            /** Names the container, so it can be found again after the process that started it is gone. */
            def name(n: String): Config = withContainer(_.name(n))

            /** Adds one label, the precise teardown filter an image filter cannot be. */
            def label(key: String, value: String): Config = withContainer(_.label(key, value))

            /** Applies `f` to the general container config this fixture layers onto. */
            def withContainer(f: Container.Config => Container.Config): Config =
                copy(container = f(container))
            def readinessBudget(d: Duration): Config = copy(readinessBudget = d)
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

        private def applyEnv(base: Container.Config, c: Config): Container.Config =
            if c.username == rootUser then
                if c.password.isEmpty then base.env("MYSQL_ALLOW_EMPTY_PASSWORD", "yes")
                else base.env("MYSQL_ROOT_PASSWORD", c.rootPassword)
            else
                base
                    .env("MYSQL_USER", c.username)
                    .env("MYSQL_PASSWORD", c.password)
                    .env("MYSQL_ROOT_PASSWORD", c.rootPassword)
            end if
        end applyEnv

        /** Baseline mysqld args composed into every MySQL fixture: keep the container test-lean so many can coexist on a single Docker
          * daemon without OOMing.
          *
          *   - `--innodb-buffer-pool-size=64M`: default is 128M and grows with server-tuning; 64M is enough for functional tests and
          *     fits with a fast shutdown flush.
          *   - `--performance-schema=OFF`: the performance_schema engine reserves ~350MB of shared memory at boot.
          *   - `--innodb-log-file-size=32M`: default is 48MB per log file × 2 files; 32M × 2 keeps the redo log lean.
          *
          * Users who need a production-shaped MySQL can compose their own args via [[serverArgs]] on top of these.
          */
        val defaultServerArgs: Chunk[String] = Chunk(
            "--innodb-buffer-pool-size=64M",
            "--performance-schema=OFF",
            "--innodb-log-file-size=32M"
        )

        /** Teardown budget for the post-SIGKILL `waitForExit` (see [[buildContainerConfig]]'s `stopSignal`). A cold SIGKILL of the idle,
          * force-removed `mysqld` exits at once; the budget stays generous only to absorb reap latency under CI contention.
          */
        val defaultStopTimeout: Duration = 30.seconds

        /** MySQL's docker-entrypoint init (temporary listener, then DB/user creation, then real listener) takes 10 to 20 s cold, and can stretch to
          * a minute or more when several MySQL containers race for the same Docker VM's CPU / disk. The [[Container.Config]] default of
          * 60 s is fine for a single container in isolation but too tight for aggregate test runs; MySQL fixtures get their own longer
          * budget so the port-binding wait doesn't spuriously trip on a healthy but slow start.
          */
        val defaultPortMappingTimeout: Duration = 3.minutes

        /** Build the [[Container.Config]] for a MySQL fixture from this config. */
        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            val healthCmdBase = Chunk("mysql", "-h", "127.0.0.1", "-u", c.username) ++
                (if c.password.nonEmpty then Chunk(s"-p${c.password}") else Chunk.empty) ++
                Chunk(c.database, "-N", "-e", "SELECT 1")
            val commandLine: Chunk[String] = Chunk("mysqld") ++ defaultServerArgs ++ c.serverArgs
            // The caller's own container config is the BASE and the fixture's settings are applied on top, so
            // a field the fixture does not own (a name, labels, mounts, networks, limits) survives untouched
            // however Container.Config grows. `image` comes from the fixture's own field, the one the builders
            // write.
            val base = c.container.copy(image = c.image)
                .env("MYSQL_DATABASE", c.database)
                .port(c.port, 0)
                .command(commandLine*)
                // Run under an init process (catatonit as PID 1): mysqld as PID 1 does not reap its subprocesses' zombie children, so under a
                // loaded CI runner the container wedges in `stopping` and the daemon reports "given PID did not die within timeout", leaking
                // daemon-side. The init reaps the zombies and forwards signals to mysqld, so teardown completes.
                .initProcess(true)
                // Teardown SIGKILLs rather than graceful-stops: the fixture is discarded with its anonymous volume, so mysqld's InnoDB shutdown
                // flush buys nothing and only adds a slow I/O-heavy shutdown on a loaded runner. The cold SIGKILL exits at once.
                .stopSignal(Container.Signal.SIGKILL)
                .stopTimeout(defaultStopTimeout)
                .portMappingTimeout(defaultPortMappingTimeout)
                .requireService(true)
                .healthCheck(readinessLoop(healthCmdBase, c.readinessBudget))
            applyEnv(base, c)
        end buildContainerConfig
    end MySQL

    // =============================================================================================
    // MongoDB
    // =============================================================================================

    /** MongoDB container fixture for tests. Based on Testcontainers Java's
      * [MongoDBContainer](https://github.com/testcontainers/testcontainers-java/blob/main/modules/mongodb/src/main/java/org/testcontainers/mongodb/MongoDBContainer.java)
      * (Apache 2.0).
      *
      * Defaults to `mongo:7`. Healthcheck issues `mongosh --quiet --eval "db.adminCommand('ping').ok"` and asserts the result is `"1"`.
      *
      * Supports simple, single-node mode; for replica sets or sharding, instantiate a [[Container]] directly with the appropriate command
      * and exec `rs.initiate()` yourself.
      *
      * @see
      *   [Docker mongo image](https://hub.docker.com/_/mongo)
      */
    final class MongoDB private[kyo] (
        /** The underlying container handle for direct lifecycle and exec operations. */
        val container: Container,
        /** The configuration this fixture was started with. */
        val config: MongoDB.Config
    ):

        /** The base connection URL without a database path: `mongodb://host:port`. Looks up the dynamically-mapped host port for
          * `config.port`.
          */
        def connectionString(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp => MongoDB.formatConnectionString(container.host, hp))

        /** Connection URL for the configured database: `mongodb://host:port/<database>`. */
        def url(using Frame): String < (Async & Abort[ContainerException]) =
            container.mappedPort(config.port).map(hp => MongoDB.formatUrl(container.host, hp, config.database))

        /** The database name configured for the MongoDB instance. */
        def database: String = config.database

        /** Run `mongosh --quiet --eval "<eval>"` against this container, returning the raw exec result. */
        def mongosh(eval: String)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
            withPostMortem(container, "mongosh")(container.exec("mongosh", "--quiet", "--eval", eval))
    end MongoDB

    object MongoDB:

        /** Default image: `mongo:7`. */
        val defaultImage: ContainerImage = ContainerImage("mongo:7")

        /** Default container port — `27017`. */
        val defaultPort: Int = 27017

        /** Format a MongoDB connection string (no database): `mongodb://host:port`. */
        private[kyo] def formatConnectionString(host: String, port: Int): String =
            s"mongodb://$host:$port"

        /** Format a MongoDB URL with database: `mongodb://host:port/database`. */
        private[kyo] def formatUrl(host: String, port: Int, database: String): String =
            s"mongodb://$host:$port/$database"

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
        final case class Config(
            image: ContainerImage = defaultImage,
            database: String = "test",
            port: Int = defaultPort,
            /** The general [[Container.Config]] this fixture layers its own settings onto. See
              * [[ContainerPredef.Postgres.Config.container]].
              */
            container: Container.Config = Container.Config(defaultImage),
            readinessBudget: Duration = 120.seconds
        ) derives CanEqual:
            def image(i: ContainerImage): Config = copy(image = i)
            def database(d: String): Config      = copy(database = d)

            /** Names the container, so it can be found again after the process that started it is gone. */
            def name(n: String): Config = withContainer(_.name(n))

            /** Adds one label, the precise teardown filter an image filter cannot be. */
            def label(key: String, value: String): Config = withContainer(_.label(key, value))

            /** Applies `f` to the general container config this fixture layers onto. */
            def withContainer(f: Container.Config => Container.Config): Config =
                copy(container = f(container))
            def readinessBudget(d: Duration): Config = copy(readinessBudget = d)
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

        /** Build the [[Container.Config]] for a MongoDB fixture from this config. The caller's own container
          * config is the base and the fixture's settings are applied on top; see
          * [[ContainerPredef.Postgres.buildContainerConfig]].
          */
        private[kyo] def buildContainerConfig(c: Config): Container.Config =
            c.container.copy(image = c.image)
                .port(c.port, 0)
                .requireService(true)
                .healthCheck(readinessLoop(Chunk("mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"), c.readinessBudget))
    end MongoDB

end ContainerPredef
