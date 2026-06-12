# kyo-pod

Kyo's Docker and Podman client. Provides typed, streaming access to containers, exec, logs, stats, images, networks, and volumes without wrapping CLI commands or blocking I/O. Two interchangeable backends handle the actual I/O: the HTTP backend speaks the Docker Engine API directly (no CLI binary required), while the Shell backend invokes `docker` or `podman` as a subprocess.

| Backend | Transport |
|---------|-----------|
| HTTP | [Docker Engine API](https://docs.docker.com/engine/api/) over Unix socket |
| Shell | `docker` / `podman` CLI subprocess |

The default entry point is `Container.init(Config)` inside a `Scope.run`. The container's lifecycle is scope-bound: on close, the scope sends SIGTERM, waits, then force-removes the container. Operations fail through `Abort[ContainerException]`, which can be matched by category (for example, `ContainerBackendException` for transport failures) or by a specific leaf type.

<!-- doctest:setup
```scala
import Container.*
import ContainerImage.*
import kyo.*
val alpine: ContainerImage = ContainerImage.Alpine
val img: ContainerImage    = ContainerImage("alpine", "3.19")
```
-->

## Getting Started

Add the dependency to your `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-pod" % "<latest version>"
```

kyo-pod requires Docker or Podman on the host. The HTTP backend is used when a Unix socket is reachable (for example `/var/run/docker.sock`); otherwise kyo-pod falls back to invoking the CLI directly. Both work the same from your code.

## Containers

### Creating a Container

The simplest case is a container running a command against a cached image:

```scala
import kyo.*

val flow =
    Scope.run {
        Container.init(ContainerImage("alpine")).map { c =>
            c.exec("echo", "hello").map { result =>
                assert(result.stdout.trim == "hello")
            }
        }
    }
```

`Container.init` creates and starts a container, returning a handle. The `Scope.run` ensures the container is stopped and removed automatically when the block exits.

> **Note:** `Container.init` force-removes the container when the scope closes. For a container that must outlive the scope, use `initUnscoped`; the caller then owns `stop`/`remove`.

The returned handle has effect type `Container < (Async & Abort[ContainerException] & Scope)`:
- `Async` because container operations suspend
- `Abort[ContainerException]` for typed failures (image not found, daemon unavailable, etc.)
- `Scope` so the runtime can register the cleanup

For richer configuration, pass a `Container.Config`:

```scala
val config = Container.Config(ContainerImage("alpine"))
    .command("sh", "-c", "echo started; sleep infinity")
    .name("my-app")
    .port(8080, 8888) // containerPort 8080 → hostPort 8888
    .env("LOG_LEVEL", "debug")
    .memory(256 * 1024 * 1024L) // 256 MB

Container.init(config)
```

`Container.Config` is a builder: each method returns a new config with that field set. See [Container.Config](#containerconfig) for the full field reference.

### Lifecycle

A running container accepts direct lifecycle commands:

```scala
Container.init(alpine).map { c =>
    for
        _ <- c.pause                          // freeze processes via SIGSTOP
        _ <- c.unpause                        // resume via SIGCONT
        _ <- c.restart                        // stop then start
        _ <- c.rename("new-name")             // rename a running container
        _ <- c.stop                           // SIGTERM, wait up to stopTimeout
        _ <- c.kill(Container.Signal.SIGKILL) // immediate termination
        _ <- c.remove(force = true)           // delete the container
    yield ()
}
```

`stop` waits up to `Config.stopTimeout` (default 3 seconds) for graceful shutdown before force-killing. `remove` requires the container to be stopped unless `force = true` is passed.

> **Caution:** `kill` followed by `waitForExit` on an `autoRemove(true)` container is inherently racy: the daemon can reap the container before the `/wait` subscription lands, returning `Success(0)` and silently losing the real exit code. Prefer `autoRemove(false)` when the exit code matters.

Explicit calls to `stop`/`remove` are optional when the container is created in a Scope, because the scope cleanup runs automatically. Use explicit calls when you want deterministic ordering (for example, to verify the container exited cleanly before moving on).

### Inspection

The handle also exposes read-only inspection:

```scala
Container.init(alpine).map { c =>
    for
        s     <- c.state   // Running / Paused / Stopped / ...
        info  <- c.inspect // full metadata: id, image, config, network, mounts
        stats <- c.stats   // point-in-time CPU/memory/IO snapshot
        top   <- c.top     // list of processes inside
        diffs <- c.changes // filesystem changes since image (added, modified, deleted)
    yield (s, info, stats, top, diffs)
}
```

> **Note:** Mapped ports are reachable only on localhost: `host` is always `"127.0.0.1"`. `mappedPort` fails with `ContainerOperationException` when the port was not declared in `Config.ports` (usually a forgotten `.port(...)`).

For continuous observation, `statsStream` emits a new snapshot at a fixed interval:

```scala doctest:expect=skipped
// Emit one Stats value every 200ms (default interval)
val live: Stream[Container.Stats, Async & Abort[ContainerException]] =
    c.statsStream
```

Lifecycle state transitions follow a predictable machine:

| From | To | Trigger |
|------|----|---------|
| Created | Running | `start` |
| Running | Paused | `pause` |
| Paused | Running | `unpause` |
| Running | Stopped | `stop` / `kill` / container exits |
| Running | Restarting | `restart` (transient) |
| any non-terminal | Removed | `remove` |

### Exec

Running a command inside the container comes in three flavors:

```scala doctest:expect=skipped
// Wait for completion, collect stdout/stderr
val result: Container.ExecResult < (Async & Abort[ContainerException]) =
    c.exec("ls", "-la", "/etc")

// Stream output line-by-line as it arrives
val stream: Stream[Container.LogEntry, Async & Abort[ContainerException]] =
    c.execStream("tail", "-f", "/var/log/app.log")

// Bidirectional: read stdout/stderr and write stdin
val session: Container.AttachSession < (Async & Abort[ContainerException] & Scope) =
    c.execInteractive(Command("cat"))
```

Use `exec` for one-shot commands where you just need the exit code and output. Use `execStream` to consume long-running output (a follow-mode log, a progress indicator) without buffering. Use `execInteractive` for shells, debuggers, or any command that reads stdin.

Each entry in `execStream`'s output is a `LogEntry(source, content)` where `source` is `Source.Stdout` or `Source.Stderr`, so you can separate streams if needed. This works identically on both backends.

### Logs

Container logs accumulate whatever the main process writes to stdout/stderr. Read them four ways:

```scala doctest:expect=skipped
val recent = c.logs                      // stdout + stderr, last 1000 lines (default)
val asText = c.logsText                  // same, flattened to a string
val small  = c.logs(tail = 100)          // last 100 lines only
val all    = c.logs(tail = Int.MaxValue) // explicit opt-in to full history
val follow = c.logStream                 // last 1000 lines, then live tail
```

The buffered APIs (`logs`, `logsText`) cap at `Container.defaultLogTail` (1000 lines) by default to prevent accidental OOM on long-running containers. Pass `tail = Int.MaxValue` to opt into the full history; for unbounded but lazy consumption, prefer `logStream` (it emits entries live until the container exits or the enclosing scope closes).

### Health Checks

A `HealthCheck` is a check function plus a retry schedule. `Container.init` runs the check synchronously after `start` and only returns the handle once the check passes: when you have `c`, the container is already healthy:

```scala
val cfg = Container.Config(ContainerImage("nginx:alpine"))
    .port(80, 8080)
    .healthCheck(HealthCheck.port(80))

Container.init(cfg).map { c =>
    // container is listening on port 80
}
```

`c.isHealthy` re-runs the check on demand. `c.awaitHealthy` re-runs it with the configured retry schedule, which is useful after a container recovers from a transient failure.

Pre-built factories cover common cases:

| factory | checks |
|---------|--------|
| `HealthCheck.exec(cmd)` | Runs `cmd` inside the container; passes if exit code is 0 |
| `HealthCheck.port(port)` | TCP `/dev/tcp` probe inside the container (requires `sh`) |
| `HealthCheck.httpGet(path, port)` | HTTP GET; passes on 2xx |
| `HealthCheck.log(pattern)` | Greps the last 500 log lines for `pattern` |
| `HealthCheck.init(check)` | Bare function: bring your own logic |
| `HealthCheck.init(retrySchedule)(check)` | Same, with a custom retry schedule |
| `HealthCheck.noop` | No check: `init` returns immediately after `start` |

Each factory accepts an optional `retrySchedule`. The default is `Schedule.fixed(500.millis).take(30)`, which probes every 500ms for up to 30 attempts before giving up with a `ContainerHealthCheckException`.

> **Note:** A `HealthCheck` schedule is a retry policy, not a periodic reschedule: once the check passes the container is healthy and the schedule stops. `isHealthy` re-runs the check once on demand.

### File Operations

Copy files in and out of a running container with `copyTo` and `copyFrom`:

```scala
Container.init(alpine).map { c =>
    for
        _ <- c.copyTo(Path("/local/config.yaml"), Path("/etc/app/config.yaml"))
        _ <- c.exec("/bin/run")
        _ <- c.copyFrom(Path("/var/log/app.log"), Path("/tmp/output.log"))
    yield ()
}
```

For reading a file's metadata without transferring it, `c.stat(containerPath)` returns name, size, mode, modification time, and symlink target. For exporting the entire filesystem as a tar stream, `c.exportFs` returns `Stream[Byte, ...]`.

Both copy operations require `tar` on the host's PATH, because tar is used to pack and unpack the archives the Docker API expects.

## Pre-defined containers

kyo-pod ships typed fixtures for popular services. Each nested module under `ContainerPredef` provides a `Container.Config` with sensible defaults, a healthcheck that drives the container's own CLI tool (rather than a port-only probe that would pass during the temporary-listener phase before init scripts run), and accessors for the connection URL and credentials. These are container fixtures only; connect with whichever client library you prefer.

### Postgres

PostgreSQL fixture. Defaults to `postgres:16-alpine` with user/password/database = `"test"`/`"test"`/`"test"`. The container runs `postgres -c fsync=off` for test-speed; healthcheck issues `psql -c "SELECT 1"` so the handle is only returned once init scripts have created `POSTGRES_DB`.

```scala
import kyo.*

ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
    for
        url <- pg.jdbcUrl // jdbc:postgresql://127.0.0.1:<port>/test
        _   <- pg.psql("CREATE TABLE t (id int)")
    yield ()
}
```

### MySQL

MySQL fixture. Defaults to `mysql:8.0` with user/password/database = `"test"`/`"test"`/`"test"` (and `rootPassword = "test"`). Healthcheck issues `mysql -e "SELECT 1"` as the configured user. Note: `mysqladmin ping` succeeds during MySQL's temporary-listener phase before init scripts create the user, so a real query is required to avoid races. Special root-user handling: `username = "root"` omits `MYSQL_USER` (root is implicit), and an empty password is permitted only with the root user (it sets `MYSQL_ALLOW_EMPTY_PASSWORD=yes`); a non-root user with an empty password fails at `init` with `ContainerOperationException`.

```scala
import kyo.*

ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { db =>
    for
        url <- db.jdbcUrl // jdbc:mysql://127.0.0.1:<port>/test
        r   <- db.mysql("SELECT 1")
    yield r.stdout.trim
}
```

### MongoDB

MongoDB fixture. Defaults to `mongo:7`. Healthcheck issues `mongosh --quiet --eval "db.adminCommand('ping').ok"` and asserts the result is `"1"`. Supports simple, single-node mode; for replica sets or sharding, instantiate a `Container` directly with the appropriate command and exec `rs.initiate()` yourself.

```scala
import kyo.*

ContainerPredef.MongoDB.initWith(ContainerPredef.MongoDB.Config.default) { mongo =>
    for
        url <- mongo.url // mongodb://127.0.0.1:<port>/test
        r   <- mongo.mongosh("db.runCommand({ping:1}).ok")
    yield r.stdout.trim
}
```

Each module is based on Testcontainers Java (Apache 2.0); the scaladoc on each class links to the original implementation.

## Configuration

### Container.Config

`Container.Config` holds every creation-time knob: image, command, environment, port publishing, mounts, networking mode, resource limits, security flags, and lifecycle settings. Build it with the builder chain:

```scala
val cfg = Container.Config(ContainerImage("postgres:16"))
    .name("db")
    .env("POSTGRES_PASSWORD", "secret")
    .port(5432, 15432)
    .memory(512 * 1024 * 1024L)
    .cpuLimit(1.5)
    .readOnlyFilesystem(false)
    .restartPolicy(Config.RestartPolicy.OnFailure(3))
    .stopTimeout(10.seconds)
    .healthCheck(HealthCheck.port(5432))
```

For an explicit all-defaults starting point, use `Container.Config.default.copy(image = ...)`. The factory `Container.Config(image)` is equivalent.

> **IMPORTANT**: Not every field is honored by every runtime. Resource limits like `cpuAffinity` and `maxProcesses` require kernel support (cgroups v2, pids controller). Unsupported settings are silently ignored by the runtime rather than raising. Additionally, numeric resource-limit builders (`memory`, `cpuLimit`, and friends) do no range validation: out-of-range values (such as negative bytes or zero CPUs) are forwarded as-is and rejected only at create time by the daemon.

To adjust resource limits on an already-running container without restarting it, use `c.update(memory = Present(512L * 1024 * 1024), cpuLimit = Present(2.0))`. Only the fields passed as `Present` are changed; `Absent` fields are left untouched.

Fields group by concern:

| group | fields |
|-------|--------|
| identity | `name`, `hostname`, `user`, `labels` |
| runtime | `command`, `env`, `interactive`, `allocateTty` |
| networking | `ports`, `networkMode`, `dns`, `extraHosts` |
| storage | `mounts` (Bind / Volume / Tmpfs) |
| resources | `memory`, `memorySwap`, `cpuLimit`, `cpuAffinity`, `maxProcesses` |
| security | `privileged`, `addCapabilities`, `dropCapabilities`, `readOnlyFilesystem` |
| lifecycle | `autoRemove`, `restartPolicy`, `stopSignal`, `stopTimeout`, `healthCheck` |

### Backend Selection

kyo-pod auto-detects the container runtime on first use, probing Podman's socket first, then Docker's. Override per-scope with `withBackendConfig`:

```scala
Container.withBackendConfig(_.UnixSocket(Path("/custom/docker.sock"))) {
    Container.init(alpine).map { c => /* uses the custom socket */ }
}
```

Three backend variants are available:

```scala
BackendConfig.AutoDetect()                             // default: probe and pick
BackendConfig.UnixSocket(Path("/var/run/docker.sock")) // force HTTP-over-socket
BackendConfig.Shell("docker")                          // force CLI subprocess
```

The HTTP backend (UnixSocket) speaks the Docker Engine API directly, so no `docker`/`podman` binary is required on PATH. It still needs `tar` on PATH for `copyTo`/`copyFrom` and `ContainerImage.buildFromPath` (to pack/unpack the archives the API expects).

`BackendConfig.UnixSocket` and `BackendConfig.AutoDetect` accept an optional `apiVersion` (default `"v1.43"`) for targeting specific Docker Engine API revisions. `BackendConfig.Shell` accepts an optional `streamBufferSize` (default 256) that controls the channel capacity used to merge `proc.stdout` and `proc.stderr` into a tagged `LogEntry` stream.

All three variants also accept a `Meter` for concurrency limiting. See [Concurrency Control](#concurrency-control).

`Container.currentBackendDescription` returns a human-readable diagnostic string (for example `"HttpContainerBackend(socket=/var/run/docker.sock, apiVersion=v1.43, runtime=docker)"`).

## Networks

### Scoped Networks

Create a network, connect containers to it, and let the scope clean up:

```scala
Scope.run {
    Container.Network.init(Network.Config.default.copy(name = "backend")).map { netId =>
        Container.initWith(Container.Config(alpine).name("server")) { server =>
            Container.initWith(Container.Config(alpine).name("client")) { client =>
                for
                    _ <- Container.Network.connect(netId, server.id, aliases = Chunk("srv"))
                    _ <- Container.Network.connect(netId, client.id)
                    r <- client.exec("ping", "-c", "1", "srv")
                yield assert(r.isSuccess)
            }
        }
    }
}
```

The network is removed when the scope exits. Containers are removed first (they're registered later in the scope) and the network last, so the daemon sees containers detached before the network is torn down.

### Unscoped and Operations

For networks that outlive the creating scope, use `Network.initUnscoped` and call `Network.remove(id)` manually:

```scala doctest:expect=skipped
Container.Network.initUnscoped(cfg)   // returns Id, no scope cleanup
Container.Network.list                // all networks
Container.Network.list(filters = ...)   // filtered listing
Container.Network.inspect(id)         // full network info
Container.Network.disconnect(net, c)  // detach a container
Container.Network.prune               // remove unused networks
```

Per-container convenience methods are also available directly on the handle:

```scala doctest:expect=skipped
c.connectToNetwork(netId, aliases = Chunk("alias")) // attach container to a network
c.disconnectFromNetwork(netId)                      // detach container from a network
```

These are equivalent to `Container.Network.connect` / `Container.Network.disconnect` but let you call them through the container handle directly.

## Volumes

### Scoped Volumes

Volumes hold persistent data that can be shared across containers or survive a container's lifetime:

```scala
Scope.run {
    Container.Volume.init(Volume.Config.default.copy(name = Present(Volume.Id("mydata")))).map { _ =>
        Container.initWith(Container.Config(alpine).volume(Volume.Id("mydata"), Path("/data"))) { c =>
            c.exec("sh", "-c", "echo hello > /data/file.txt")
        }
    }
}
```

The volume is removed when the scope exits. As with networks, container cleanup runs before volume cleanup.

### Unscoped and Operations

For volumes that outlive the scope, `Volume.initUnscoped` returns the full `Info` (with daemon-assigned name, mountpoint, and driver options). Call `Volume.remove(id)` manually.

```scala doctest:expect=skipped
Container.Volume.initUnscoped(cfg) // returns Info
Container.Volume.list
Container.Volume.inspect(id)
Container.Volume.remove(id, force = true)
Container.Volume.prune
```

## Images

### References

A `ContainerImage` is a structured reference (registry, namespace, name, tag, and digest):

```scala
val img1 = ContainerImage("alpine")         // defaults tag=latest
val img2 = ContainerImage("alpine", "3.19") // name + tag
val img3 = ContainerImage.parse("ghcr.io/owner/repo:v1.0")
    .getOrElse(ContainerImage("fallback"))

// Predefined for common base images
val img4 = ContainerImage.Alpine
val img5 = ContainerImage.Nginx
// For service images (Postgres, Redis, etc.) use ContainerPredef, which provides
// healthchecks, versioned defaults, and connection helpers.
```

Tag and digest are mutually exclusive: `withDigest` clears the tag, and `withTag` clears the digest. `.reference` produces the canonical string form (for example `"docker.io/library/alpine:latest"`).

### Reading

```scala
ContainerImage.list                        // local images
ContainerImage.inspect(img)                // full metadata (layers, labels, platform)
ContainerImage.history(img)                // layer history
ContainerImage.search("alpine", limit = 5) // registry search
```

### Pulling and Building

Pull an image unconditionally, or only when missing locally:

```scala
val ref = ContainerImage("alpine", "3.19")

ContainerImage.pull(ref)   // always contacts the registry
ContainerImage.ensure(ref) // check locally first; pull only if missing

// Stream progress events: populated `id`, `status`, and `progress` fields per layer
ContainerImage.pullWithProgress(ref).run.map { events =>
    events.foreach(e => println(s"${e.id.getOrElse("?")}: ${e.status}"))
}
```

> **Note:** `Container.init` runs `imageEnsure` (pull-if-absent) before `create`, making behavior symmetric across backends: the Shell backend's `create` auto-pulls as a side effect, but the HTTP backend's `POST /containers/create` returns 404 on a missing image. `imageEnsure` bridges this gap.

Build from a Dockerfile directory:

```scala
ContainerImage.buildFromPath(
    Path("/path/to/context"),
    tags = Chunk("myapp:latest"),
    buildArgs = Dict("VERSION" -> "1.0"),
    noCache = false
).run
```

`buildFromPath` uses `tar` to archive the build context, then streams it to the `/build` endpoint. After the stream completes, kyo-pod verifies the tagged image exists on the daemon. This catches silent failures that some builder pipelines emit as plain stream text instead of structured `error` fields, and surfaces them as a `ContainerBuildFailedException`.

To publish a local image to a registry, use `ContainerImage.push(ref)` (pass `auth = Present(auth)` for private registries). To snapshot a running container's filesystem as a new image, use `ContainerImage.commit(container.id, repo = "myapp", tag = "v2")`; it returns the new image ID.

### Authentication

For private registries, pass a `RegistryAuth`:

```scala
val auth = RegistryAuth(
    username = "user",
    password = "password",
    server = "https://registry.example.com"
)

ContainerImage.pull(
    ContainerImage("registry.example.com/myapp:v1"),
    auth = Present(auth)
)
```

To load credentials from the local Docker/Podman config:

```scala
RegistryAuth.fromConfig.map { auth =>
    ContainerImage.pull(img, auth = Present(auth))
}
```

`fromConfig` reads (in order): `~/.docker/config.json`, `$XDG_RUNTIME_DIR/containers/auth.json`, `$DOCKER_CONFIG/config.json`. An empty `RegistryAuth` is returned if none exist.

> **Caution:** `RegistryAuth` holds credentials in memory and passes them to the backend. `toString` redacts the credential strings but not registry names. Scope auth-bearing operations tightly.

## Composition

### Multiple Containers

`initAll` creates a sequence of containers in order, optionally waiting for each to be healthy before starting the next:

```scala
val stack = Chunk(
    Container.Config(ContainerImage("postgres:16")).port(5432).healthCheck(HealthCheck.port(5432)),
    Container.Config(ContainerImage("redis:7")).port(6379).healthCheck(HealthCheck.port(6379)),
    Container.Config(ContainerImage("myapp:latest")).port(8080)
)

Scope.run {
    Container.initAll(stack).map { containers =>
        // postgres is healthy before redis starts, redis healthy before myapp
        assert(containers.length == 3)
    }
}
```

All containers are scope-managed: teardown runs on scope exit, in reverse order of startup.

### Run-Once Jobs

For fire-and-forget batch work, `runOnce` bundles create + waitForExit + logs + teardown into a single call:

```scala
val result: ExecResult < (Async & Abort[ContainerException]) =
    Container.runOnce(
        image = ContainerImage("alpine"),
        command = Command("sh", "-c", "echo processing; exit 0"),
        timeout = 30.seconds
    )
// result.exitCode / result.stdout / result.stderr
```

On timeout, the container is still torn down (via scope) and the result carries `ExitCode.Signaled(15)` with a sentinel message appended to stderr.

### Test Fixtures

`initWith` provides bracket semantics: create a container, run a function with it, then clean up automatically:

```scala
val check = Container.initWith(Container.Config(alpine)) { c =>
    c.exec("echo", "from inside").map(_.stdout.trim == "from inside")
}
```

This is the canonical pattern for integration tests: short-lived container, scoped cleanup, no test-side lifecycle management. The outer `Scope.run` is often already provided by the test harness.

## Error Handling

### Typed Exceptions

All container operations fail with `Abort[ContainerException]`. The hierarchy is sealed into five subcategories by failure mode so callers can recover a whole class of failures in one line, or match a specific leaf for granular handling. There is no root-level catch-all: every failure belongs to a subcategory.

| category | leaves | caller response |
|---|---|---|
| `ContainerBackendException` *(concrete, also used directly)* | `ContainerBackendUnavailableException(backend, reason)`, `ContainerTimeoutException(operation, duration)`, `ContainerNotSupportedException(operation, detail)` | Retry on transport failures, fail fast at boot, bail on missing capabilities. Instantiated directly for HTTP transport errors, meter closures, and panics during daemon requests. |
| `ContainerNotFoundException` *(abstract)* | `ContainerMissingException(id)`, `ContainerImageMissingException(image)`, `ContainerNetworkMissingException(id)`, `ContainerVolumeMissingException(id)` | Often absorbable in idempotent cleanup; useful for fallback pulls. Always a specific resource leaf. |
| `ContainerConflictException` *(concrete, also used directly)* | `ContainerAlreadyExistsException(name)`, `ContainerAlreadyRunningException(id)`, `ContainerAlreadyStoppedException(id)`, `ContainerPortConflictException(port, detail)`, `ContainerVolumeInUseException(id, containers)` | Frequently absorb as success (idempotent start/stop). Instantiated directly for network-endpoint-already-attached-style conflicts. |
| `ContainerOperationException` *(concrete, also used directly)* | `ContainerStartFailedException(id, reason)`, `ContainerExecFailedException(id, cmd, exitCode, stderr)`, `ContainerAuthException(registry, detail)`, `ContainerBuildFailedException(context, detail, cause)`, `ContainerHealthCheckException(id, reason, attempts, lastError)` | Daemon rejected the request; propagate with context. Instantiated directly for unclassified operation rejections. |
| `ContainerDecodeException` *(concrete)* | *(no leaves; used directly)* | Daemon response couldn't be parsed. Programmer-level or daemon version mismatch. |

### Recovery Patterns

```scala doctest:expect=skipped
// Fall back to a different image if the first isn't available
val withFallback =
    Abort.recover[ContainerImageMissingException] { _ =>
        Container.init(ContainerImage("alpine"))
    } {
        Container.init(ContainerImage("custom-image"))
    }

// Absorb every state conflict (already-running, already-stopped, etc.) as success
val idempotentStart =
    Abort.recover[ContainerConflictException](_ => ())(container.start)

// Retry transport failures but fail fast on operation errors
val robustPull =
    Abort.run[ContainerBackendException](pullImage).map {
        case Result.Success(img) => img
        case Result.Failure(_)   => // retryable: log + Retry.backoff
        case Result.Panic(t)     => // not retryable
    }

// Translate an exec failure into a Result for granular handling
c.map { container =>
    Abort.run[ContainerException](container.exec("/bin/false")).map {
        case Result.Success(_)                                           => "ok"
        case Result.Failure(ContainerExecFailedException(_, _, code, _)) => s"exec exit=$code"
        case Result.Failure(_: ContainerMissingException)                => "container gone"
        case Result.Failure(other)                                       => s"other error: $other"
        case Result.Panic(t)                                             => s"panic: $t"
    }
}
```

## Advanced

### Concurrency Control

All three `BackendConfig` variants accept a `Meter` that bounds concurrent daemon operations. Useful for load-testing or when the daemon is the bottleneck:

```scala doctest:expect=skipped
Meter.initSemaphore(8).map { meter =>
    Container.withBackendConfig(_.AutoDetect(meter = meter)) {
        // at most 8 backend calls in flight at once
        Kyo.foreach(configs)(Container.init)
    }
}
```

The meter applies to every operation through the backend: create, start, stop, exec, log streaming, stats, image pull, and so on. Streaming operations hold a permit for the lifetime of their stream.

### Streaming Details

All streaming APIs share the same rules:

- **Scoped**: consume as long as you want; close the enclosing Scope to terminate. Closing stops the daemon request and releases the connection.
- **Source-tagged**: `logStream`, `execStream`, and `attach` all emit `LogEntry` values with the correct `Source.Stdout` / `Source.Stderr` tag. HTTP backend uses Docker's 8-byte framed multiplex; Shell backend reads `proc.stdout` and `proc.stderr` concurrently through an internal channel. The behavior is identical from the caller's perspective.
- **Bounded history**: `logs` and `logsText` accept `tail: Maybe[Int]` to cap history. `logStream` is follow-mode; the first chunk includes recent history and new lines appear as the container writes them.
- **Custom intervals**: `statsStream(interval)` overrides the default 200ms polling cadence.

### Checkpoint and Restore

Containers can be frozen to disk via CRIU and resumed later:

```scala
Container.init(alpine).map { c =>
    c.checkpoint("cp1").map { name =>
        // ... later, possibly after a daemon restart ...
        c.restore(name)
    }
}
```

`checkpoint` returns the checkpoint name so it can be fed directly to `restore`.

> **WARNING**: CRIU must be installed on the host and the container runtime must be configured to use it. Not every kernel supports checkpointing every workload; probe before relying on this in production.

## Cross-Platform

kyo-pod compiles on JVM, JavaScript (Node.js), and Scala Native from a single source tree. All three share the same API and the same tests run against the same Docker/Podman daemons, so behavior is consistent across platforms.

## Demos

Runnable demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). Run any with `sbt 'kyo-podJVM/Test/runMain demo.<Name>'`.

- [**ServiceMesh**](shared/src/test/scala/demo/ServiceMesh.scala): three-tier app (nginx edge, python api, redis cache) on a private network with container-name DNS.
- [**PrometheusExporter**](shared/src/test/scala/demo/PrometheusExporter.scala): polls container `stats` on a schedule and emits Prometheus text-format metric families.
- [**LogAggregator**](shared/src/test/scala/demo/LogAggregator.scala): merges `logStream`s from label-matched containers into one regex-filtered feed.
- [**CodeSandbox**](shared/src/test/scala/demo/CodeSandbox.scala): locked-down runner for submitted code with memory/CPU/PID limits, no network, and a read-only root.
- [**IntegrationTestScaffold**](shared/src/test/scala/demo/IntegrationTestScaffold.scala): spins up Postgres and Redis on a shared network with exec-based health-gated startup.
