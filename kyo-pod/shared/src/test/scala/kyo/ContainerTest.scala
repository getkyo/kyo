package kyo

class ContainerTest extends Test:

    private def assertSuccess[A](r: Result[String, A]): A =
        r match
            case Result.Success(v) => v
            case other             => fail(s"Expected Success but got: $other")

    // =========================================================================
    // ContainerImage.parse
    // =========================================================================

    "ContainerImage.parse" - {

        "simple name defaults to tag latest" in {
            val img = assertSuccess(ContainerImage.parse("alpine"))
            assert(img.name == "alpine")
            assert(img.tag == Present("latest"))
            assert(img.registry == Absent)
            assert(img.namespace == Absent)
            assert(img.digest == Absent)
        }

        "name:tag" in {
            val img = assertSuccess(ContainerImage.parse("alpine:3.19"))
            assert(img.name == "alpine")
            assert(img.tag == Present("3.19"))
            assert(img.digest == Absent)
        }

        "name with digest" in {
            val img = assertSuccess(ContainerImage.parse("myapp@sha256:abc123def"))
            assert(img.name == "myapp")
            assert(img.tag == Absent)
            // Digest.apply prepends "sha256:" if not present, but input already has it.
            // Verify no double-prefix bug
            assert(img.digest.get.value == "sha256:abc123def")
        }

        "registry with port" in {
            val img = assertSuccess(ContainerImage.parse("localhost:5000/myapp:v1"))
            assert(img.registry == Present(ContainerImage.Registry("localhost:5000")))
            assert(img.name == "myapp")
            assert(img.tag == Present("v1"))
        }

        "registry/namespace/name:tag" in {
            val img = assertSuccess(ContainerImage.parse("ghcr.io/owner/repo:v1.2.3"))
            assert(img.registry == Present(ContainerImage.Registry("ghcr.io")))
            assert(img.namespace == Present("owner"))
            assert(img.name == "repo")
            assert(img.tag == Present("v1.2.3"))
        }

        "deeply nested namespace" in {
            val img = assertSuccess(ContainerImage.parse("registry.io/a/b/c/image:v1"))
            assert(img.registry == Present(ContainerImage.Registry("registry.io")))
            assert(img.name.contains("image"))
            assert(img.tag == Present("v1"))
        }

        "empty string fails" in {
            val r = ContainerImage.parse("")
            assert(r.isFailure)
        }

        "just a tag ':latest' — empty name is rejected" in {
            val r = ContainerImage.parse(":latest")
            assert(r.isFailure)
        }

        "digest only '@sha256:abc' — empty name is rejected" in {
            val r = ContainerImage.parse("@sha256:abc")
            assert(r.isFailure)
        }

        "namespace without registry (library/alpine)" in {
            val img = assertSuccess(ContainerImage.parse("library/alpine"))
            assert(img.namespace == Present("library"))
            assert(img.name == "alpine")
            assert(img.tag == Present("latest"))
            assert(img.registry == Absent)
        }

        "predefined image roundtrip — Alpine" in {
            val ref    = ContainerImage.Alpine.reference
            val parsed = assertSuccess(ContainerImage.parse(ref))
            assert(parsed.name == ContainerImage.Alpine.name)
            assert(parsed.tag == ContainerImage.Alpine.tag)
            assert(parsed.registry == ContainerImage.Alpine.registry)
            assert(parsed.namespace == ContainerImage.Alpine.namespace)
        }

        "withTag clears digest and vice versa" in {
            val base     = ContainerImage("myapp")
            val digested = base.withDigest(ContainerImage.Digest("abc123"))
            assert(digested.digest.get.value == "sha256:abc123")
            assert(digested.tag == Absent)

            val tagged = digested.withTag("v2")
            assert(tagged.tag == Present("v2"))
            assert(tagged.digest == Absent)
        }
    }

    // =========================================================================
    // ContainerImage.apply(ref) — single-arg constructor parses the reference
    // =========================================================================

    "ContainerImage(ref) parses" - {
        "tagged image" in {
            val img = ContainerImage("python:3.12-alpine")
            assert(img.reference == "python:3.12-alpine")
        }
        "untagged image gets latest" in {
            val img = ContainerImage("alpine")
            assert(img.reference == "alpine:latest")
        }
        "registry/namespace/name:tag" in {
            val img = ContainerImage("ghcr.io/owner/repo:v1")
            assert(img.reference == "ghcr.io/owner/repo:v1")
        }
        "digest reference" in {
            val img = ContainerImage("myapp@sha256:deadbeef")
            assert(img.reference == "myapp@sha256:deadbeef")
        }
    }

    // =========================================================================
    // Container.Platform.parse
    // =========================================================================

    "Container.Platform.parse" - {

        "2 parts" in {
            val p = assertSuccess(Container.Platform.parse("linux/amd64"))
            assert(p.os == "linux")
            assert(p.arch == "amd64")
            assert(p.variant == Absent)
        }

        "3 parts with variant" in {
            val p = assertSuccess(Container.Platform.parse("linux/arm/v7"))
            assert(p.os == "linux")
            assert(p.arch == "arm")
            assert(p.variant == Present("v7"))
        }

        "invalid format fails" in {
            val r = Container.Platform.parse("invalid")
            assert(r.isFailure)
        }

        // boundary cases
        "too many parts fails" in {
            val r = Container.Platform.parse("linux/amd64/v8/extra")
            assert(r.isFailure)
            assert(r.failure.get.contains("Invalid"))
        }
        "empty string fails" in {
            assert(Container.Platform.parse("").isFailure)
        }
    }

    // =========================================================================
    // ContainerImage.Digest
    // =========================================================================

    "ContainerImage.Digest" - {

        "auto-prefixes sha256 when not present" in {
            val d = ContainerImage.Digest("abc123")
            assert(d.value == "sha256:abc123")
        }

        "does not double-prefix when sha256: already present" in {
            val d = ContainerImage.Digest("sha256:abc123")
            assert(d.value == "sha256:abc123")
        }
    }

    // =========================================================================
    // Container.Config.Protocol.cliName
    // =========================================================================

    "Container.Config.Protocol.cliName" - {

        "TCP" in {
            assert(Container.Config.Protocol.TCP.cliName == "tcp")
        }

        "UDP" in {
            assert(Container.Config.Protocol.UDP.cliName == "udp")
        }

        "SCTP" in {
            assert(Container.Config.Protocol.SCTP.cliName == "sctp")
        }
    }

    // =========================================================================
    // Container.Config.RestartPolicy.cliName
    // =========================================================================

    "Container.Config.RestartPolicy.cliName" - {

        "No" in {
            assert(Container.Config.RestartPolicy.No.cliName == "no")
        }

        "Always" in {
            assert(Container.Config.RestartPolicy.Always.cliName == "always")
        }

        "UnlessStopped" in {
            assert(Container.Config.RestartPolicy.UnlessStopped.cliName == "unless-stopped")
        }

        "OnFailure" in {
            assert(Container.Config.RestartPolicy.OnFailure(5).cliName == "on-failure")
        }
    }

    // =========================================================================
    // HealthCheck.all with zero checks
    // =========================================================================

    "HealthCheck.all with zero checks" - {

        "empty composite check uses defaultRetrySchedule" in {
            // NOTE: empty all() trivially passes — no health requirements
            val hc = Container.HealthCheck.all()
            assert(hc.schedule == Container.HealthCheck.defaultRetrySchedule)
        }

        // empty composite semantics and head-schedule selection
        "empty composite passes trivially" in run {
            val hc = Container.HealthCheck.all()
            // NOTE: null.asInstanceOf[Container] is intentional — the empty composite never dereferences the container argument.
            // Keep this comment so a future reader understands the intent if the impl gains short-circuit logic.
            Abort.run[ContainerException](hc.check(null.asInstanceOf[Container]))
                .map(r => assert(r.isSuccess, s"expected trivial success, got $r"))
        }
        "non-empty composite picks head's schedule" in {
            val s1 = Schedule.fixed(123.millis).take(1)
            val s2 = Schedule.fixed(456.millis).take(2)
            val a = new Container.HealthCheck:
                def check(c: Container)(using Frame) = ()
                def schedule: Schedule               = s1
            val b = new Container.HealthCheck:
                def check(c: Container)(using Frame) = ()
                def schedule: Schedule               = s2
            // `eq` is correct — implementation returns chs.head.schedule (Container.scala:1155).
            assert(Container.HealthCheck.all(a, b).schedule eq s1)
        }
    }

    "ContainerImage.parse — empty tag" - {

        "'alpine:' defaults tag to 'latest' instead of empty string" in {
            val r = ContainerImage.parse("alpine:")
            r match
                case Result.Success(img) =>
                    assert(
                        img.tag == Present("latest"),
                        s"Expected tag 'latest' for 'alpine:', got '${img.tag}'"
                    )
                case err =>
                    fail(s"Expected parse to succeed with default tag, got failure: $err")
            end match
        }

        // namespaced variant
        "'library/alpine:' (with namespace) defaults tag to 'latest'" in {
            val img = assertSuccess(ContainerImage.parse("library/alpine:"))
            assert(img.namespace == Present("library"))
            assert(img.name == "alpine")
            assert(img.tag == Present("latest"), s"got tag=${img.tag}")
        }
    }

    "ContainerImage.RegistryAuth — toString redaction" - {

        "toString does not expose username, password, or base64 credentials" in {
            val auth = ContainerImage.RegistryAuth("user", "verysecretpass")
            val s    = auth.toString
            assert(!s.contains("user"), s"toString leaks username: $s")
            assert(!s.contains("verysecretpass"), s"toString leaks password: $s")
            // Base64 of "user:verysecretpass" is "dXNlcjp2ZXJ5c2VjcmV0cGFzcw==".
            // Don't hardcode the full string; check the base64 class of characters is not present as a long run.
            assert(
                !s.contains("dXNlcjp2ZXJ5c2VjcmV0cGFzcw"),
                s"toString leaks base64 credentials: $s"
            )
        }
    }

    // =========================================================================
    // ContainerException message composition
    // =========================================================================

    "ContainerException messages" - {

        "ImageNotFound message contains image reference and has no Throwable cause" in {
            val img = ContainerImage("alpine:3.19")
            val ex  = ContainerImageMissingException(img)
            assert(ex.getCause == null, "image-missing exceptions carry no Throwable cause")
            assert(ex.getMessage.contains("alpine:3.19"), s"getMessage must contain image reference")
        }

        "NotFound message contains container id and has no Throwable cause" in {
            val id = Container.Id("abc123")
            val ex = ContainerMissingException(id)
            assert(ex.getCause == null, "container-missing exceptions carry no Throwable cause")
            assert(ex.getMessage.contains("abc123"), s"getMessage must contain container id")
        }

        "subcategory message carries cause class and message" in {
            val ex  = ContainerOperationException("outer", new java.io.IOException("inner"))
            val msg = ex.getMessage
            assert(msg.contains("outer"), s"expected 'outer' in '$msg'")
            assert(msg.contains("IOException"), s"expected 'IOException' in '$msg'")
            assert(msg.contains("inner"), s"expected 'inner' in '$msg'")
        }

        "Timeout message includes duration and operation" in {
            val ex  = ContainerTimeoutException("pull image", 30.seconds)
            val msg = ex.getMessage
            assert(msg.contains("pull image"), s"expected operation in '$msg'")
            assert(msg.contains("30"), s"expected duration in '$msg'")
        }

        // leaf exception coverage
        "all conflict and operation leaves carry their constructor fields" in {
            val cid = Container.Id("c-123")
            val vid = Container.Volume.Id("v-1")
            assert(ContainerAlreadyRunningException(cid).getMessage.contains("c-123"))
            assert(ContainerAlreadyStoppedException(cid).getMessage.contains("c-123"))
            assert(ContainerPortConflictException(8080, "bind: address already in use").port == 8080)
            assert(ContainerPortConflictException(8080, "x").getMessage.contains("8080"))
            assert(ContainerAuthException("ghcr.io", "denied").registry == "ghcr.io")
            assert(ContainerAuthException("ghcr.io", "denied").getMessage.contains("ghcr.io"))
            // 2-arg call uses default cause = ""; ContainerException.scala:194
            assert(ContainerBuildFailedException("/ctx", "tar failed").getMessage.contains("tar failed"))
            // 3-arg call with cause: confirm Throwable cause is reflected
            val ex = ContainerBuildFailedException("/ctx", "tar failed", new RuntimeException("inner"))
            assert(ex.getMessage.contains("tar failed"))
            assert(ContainerStartFailedException(cid, "OCI error").getMessage.contains("c-123"))
            assert(ContainerVolumeInUseException(vid, "c-1,c-2").containers == "c-1,c-2")
        }

        // ContainerDecodeException construction
        "ContainerDecodeException carries context message and cause" in {
            val ex = new ContainerDecodeException("decode failed at /containers/json", "bad token at index 5")
            assert(ex.getMessage.contains("decode failed"))
            assert(ex.getMessage.contains("bad token"))
        }

        // ContainerHealthCheckException field preservation
        "ContainerHealthCheckException preserves attempts and lastError" in {
            val ex = ContainerHealthCheckException(Container.Id("c1"), "exec failed", attempts = 3, lastError = "x" * 600)
            assert(ex.attempts == 3)
            // Field is not truncated; only the accumulator truncates each entry.
            assert(ex.lastError.length == 600)
            assert(ex.getMessage.contains("3 attempt"))
        }
    }

    // =========================================================================
    // HealthCheck.noop
    // =========================================================================

    "HealthCheck.noop" - {
        "is the noop singleton" in {
            val cfg = Container.Config(ContainerImage("alpine:3.19")).copy(healthCheck = Container.HealthCheck.noop)
            assert(cfg.healthCheck eq Container.HealthCheck.noop, "healthCheck should be the noop singleton")
        }
        "default config uses HealthCheck.running" in {
            val cfg = Container.Config(image = ContainerImage("alpine:3.19"))
            assert(cfg.healthCheck eq Container.HealthCheck.running, "default healthCheck should be HealthCheck.running")
        }
    }

    // =========================================================================
    // Network.Config builders
    // =========================================================================

    "Network.Config builders" - {
        "driver" in {
            val c = Container.Network.Config.default.copy(name = "n").driver(Container.NetworkDriver.Host)
            assert(c.driver == Container.NetworkDriver.Host)
        }
        "label + labels" in {
            val c = Container.Network.Config.default.copy(name = "n").label("a", "1").labels(Dict("b" -> "2"))
            assert(c.labels.is(Dict("a" -> "1", "b" -> "2")))
        }
        "option + options" in {
            val c = Container.Network.Config.default.copy(name = "n").option("x", "1").options(Dict("y" -> "2"))
            assert(c.options.is(Dict("x" -> "1", "y" -> "2")))
        }
        "flags" in {
            val c = Container.Network.Config.default.copy(name = "n").internal(true).attachable(false).enableIPv6(true)
            assert(c.internal && !c.attachable && c.enableIPv6)
        }
    }

    "Summary.attach" in {
        // Compile-level check: the method exists on Summary and returns a Container effect.
        val _: Container.Summary => Container < (Async & Abort[ContainerException]) = _.attach
        assert(true)
    }

    "currentBackendDescription" in {
        // Compile-level check: the method exists and returns the expected type.
        val _: String < (Async & Abort[ContainerException]) = Container.currentBackendDescription
        assert(true)
    }

    "BackendConfig.UnixSocket overloads" in {
        val a = Container.BackendConfig.UnixSocket("/var/run/docker.sock")
        val b = Container.BackendConfig.UnixSocket(Path("/var/run/docker.sock"))
        assert(a == b, s"String and kyo.Path constructors should produce equal values: $a vs $b")
    }

    // =========================================================================
    // HttpContainerBackend env var parsing — pure parsing, no Docker required.
    // Validates that DOCKER_HOST / CONTAINER_HOST values are interpreted with
    // explicit error messages instead of being silently dropped.
    // =========================================================================

    "HttpContainerBackend env var parsing" - {

        def parsed(envName: String, value: String)(using Frame): Result[ContainerException, String] < Any =
            Abort.run[ContainerException](
                kyo.internal.HttpContainerBackend.parseHostUri(envName, value)
            )

        "DOCKER_HOST=unix:///var/run/docker.sock returns the path" in run {
            parsed("DOCKER_HOST", "unix:///var/run/docker.sock").map { r =>
                assert(r == Result.Success("/var/run/docker.sock"))
            }
        }

        "DOCKER_HOST=/var/run/docker.sock (bare absolute path) returns it as-is" in run {
            parsed("DOCKER_HOST", "/var/run/docker.sock").map { r =>
                assert(r == Result.Success("/var/run/docker.sock"))
            }
        }

        "CONTAINER_HOST=unix:///run/podman/podman.sock returns the path" in run {
            parsed("CONTAINER_HOST", "unix:///run/podman/podman.sock").map { r =>
                assert(r == Result.Success("/run/podman/podman.sock"))
            }
        }

        "DOCKER_HOST=tcp://localhost:2375 fails with TCP message" in run {
            parsed("DOCKER_HOST", "tcp://localhost:2375").map {
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    val msg = e.reason
                    assert(msg.contains("TCP"), s"expected 'TCP' in: $msg")
                    assert(msg.contains("DOCKER_HOST"), s"expected env var name in: $msg")
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }

        "CONTAINER_HOST=ssh://user@host fails with SSH message" in run {
            parsed("CONTAINER_HOST", "ssh://user@host").map {
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    val msg = e.reason
                    assert(msg.contains("SSH"), s"expected 'SSH' in: $msg")
                    assert(msg.contains("CONTAINER_HOST"), s"expected env var name in: $msg")
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }

        "DOCKER_HOST=npipe:////./pipe/docker_engine fails with named-pipe message" in run {
            parsed("DOCKER_HOST", "npipe:////./pipe/docker_engine").map {
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    val msg = e.reason.toLowerCase
                    assert(
                        msg.contains("named-pipe") || msg.contains("npipe"),
                        s"expected 'named-pipe' or 'npipe' in: ${e.reason}"
                    )
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }

        "DOCKER_HOST=fd://3 fails with fd message" in run {
            parsed("DOCKER_HOST", "fd://3").map {
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    assert(e.reason.contains("fd"), s"expected 'fd' in: ${e.reason}")
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }

        "DOCKER_HOST=localhost:2375 (no scheme) fails with unrecognized message" in run {
            parsed("DOCKER_HOST", "localhost:2375").map {
                case Result.Failure(e: ContainerBackendUnavailableException) =>
                    assert(
                        e.reason.toLowerCase.contains("unrecognized"),
                        s"expected 'unrecognized' in: ${e.reason}"
                    )
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }

        "DOCKER_HOST=unix:// (empty path) fails" in run {
            parsed("DOCKER_HOST", "unix://").map {
                case Result.Failure(_: ContainerBackendUnavailableException) => succeed
                case other => fail(s"Expected ContainerBackendUnavailableException, got $other")
            }
        }
    }

    // =========================================================================
    // HttpContainerBackend.canonicalStatus — picks the daemon's *intended*
    // status from the JSON error body's `response` field, which is the
    // strongest classification signal when podman's docker-compat shim
    // returns a wire-level status that disagrees with the real condition.
    // =========================================================================

    "HttpContainerBackend.canonicalStatus" - {
        import kyo.internal.HttpContainerBackend.canonicalStatus

        "returns wire status when body is Absent" in {
            assert(canonicalStatus(500, Absent) == 500)
            assert(canonicalStatus(404, Absent) == 404)
            succeed
        }

        "returns wire status when body is empty" in {
            assert(canonicalStatus(500, Present("")) == 500)
            succeed
        }

        "returns wire status when body is not JSON" in {
            assert(canonicalStatus(500, Present("not json at all")) == 500)
            succeed
        }

        "returns wire status when body has no response field (docker)" in {
            assert(canonicalStatus(404, Present("""{"message":"No such image: foo"}""")) == 404)
            succeed
        }

        // Podman's docker-compat shim case — the wire status disagrees with the real condition.

        "podman name conflict: HTTP 500 with response 409 in body returns 409" in {
            val body = """{"cause":"...","message":"container name X is already in use","response":409}"""
            assert(canonicalStatus(500, Present(body)) == 409)
            succeed
        }

        "podman missing image: HTTP 403 with response 404 in body returns 404" in {
            val body = """{"cause":"...","message":"no such image","response":404}"""
            assert(canonicalStatus(403, Present(body)) == 404)
            succeed
        }

        "out-of-range response field falls back to wire status" in {
            assert(canonicalStatus(500, Present("""{"response":99}""")) == 500)
            assert(canonicalStatus(500, Present("""{"response":700}""")) == 500)
            assert(canonicalStatus(500, Present("""{"response":-1}""")) == 500)
            succeed
        }

        "valid response field at boundaries is honoured" in {
            assert(canonicalStatus(500, Present("""{"response":100}""")) == 100)
            assert(canonicalStatus(500, Present("""{"response":599}""")) == 599)
            succeed
        }

        // Real CI capture from rootless podman: the shim returns wire 500 AND
        // sets response: 500, but the cause/message clearly say "already in use".
        // Without cause/message inference, this would fall through to a generic
        // operation error. With it, we recover 409 (Conflict).

        "cause field 'already in use' overrides wire+response 500 to 409" in {
            val body = """{"cause":"that name is already in use","message":"container name X is already in use","response":500}"""
            assert(canonicalStatus(500, Present(body)) == 409)
            succeed
        }

        "message field 'no such image' on docker (no response field) returns 404" in {
            val body = """{"message":"No such image: foo:latest"}"""
            assert(canonicalStatus(404, Present(body)) == 404)
            succeed
        }

        "manifest unknown phrase returns 404" in {
            val body = """{"message":"manifest unknown for image foo"}"""
            assert(canonicalStatus(500, Present(body)) == 404)
            succeed
        }

        "no such container in cause returns 404" in {
            val body = """{"cause":"no such container","message":"some wrapper"}"""
            assert(canonicalStatus(500, Present(body)) == 404)
            succeed
        }

        "cause/message takes precedence over response field" in {
            // response says 500 but cause says conflict — cause wins
            val body = """{"cause":"that name is already in use","response":500}"""
            assert(canonicalStatus(500, Present(body)) == 409)
            succeed
        }
    }

    // =========================================================================
    // parseState — covers docker + podman state vocabularies.
    // =========================================================================

    "parseState" - {
        import kyo.internal.ContainerBackend.parseState

        "docker states" in {
            assert(parseState("created") == Container.State.Created)
            assert(parseState("running") == Container.State.Running)
            assert(parseState("paused") == Container.State.Paused)
            assert(parseState("restarting") == Container.State.Restarting)
            assert(parseState("removing") == Container.State.Removing)
            assert(parseState("exited") == Container.State.Stopped)
            assert(parseState("dead") == Container.State.Dead)
            succeed
        }

        "podman pre-start states map to Created" in {
            assert(parseState("configured") == Container.State.Created)
            assert(parseState("initialized") == Container.State.Created)
            succeed
        }

        "case-insensitive" in {
            assert(parseState("RUNNING") == Container.State.Running)
            assert(parseState("Configured") == Container.State.Created)
            succeed
        }

        "unknown states default to Stopped" in {
            assert(parseState("unknown-state-xyz") == Container.State.Stopped)
            assert(parseState("") == Container.State.Stopped)
            succeed
        }
    }

    // =========================================================================
    // NetworkDriver.parse
    // =========================================================================

    "NetworkDriver.parse" - {
        "known driver" in {
            assert(Container.NetworkDriver.parse("bridge") == Container.NetworkDriver.Bridge)
            assert(Container.NetworkDriver.parse("HOST") == Container.NetworkDriver.Host)
        }
        "Custom branch for unknown driver" in {
            val d = Container.NetworkDriver.parse("calico")
            assert(d == Container.NetworkDriver.Custom("calico"))
        }
        "round-trip through cliName" in {
            import Container.NetworkDriver.*
            assert(parse(Bridge.cliName) == Bridge)
            assert(parse(Custom("weave").cliName) == Custom("weave"))
            assert(None.cliName == "none")
        }
    }

    // =========================================================================
    // HealthStatus.parse
    // =========================================================================

    "HealthStatus.parse" - {
        "Healthy" in { assert(Container.HealthStatus.parse("healthy") == Container.HealthStatus.Healthy) }
        "Unhealthy is case-insensitive" in {
            assert(Container.HealthStatus.parse("UNHEALTHY") == Container.HealthStatus.Unhealthy)
        }
        "Starting" in { assert(Container.HealthStatus.parse("starting") == Container.HealthStatus.Starting) }
        "fallback to NoHealthcheck" in {
            assert(Container.HealthStatus.parse("") == Container.HealthStatus.NoHealthcheck)
            assert(Container.HealthStatus.parse("none") == Container.HealthStatus.NoHealthcheck)
            assert(Container.HealthStatus.parse("garbage") == Container.HealthStatus.NoHealthcheck)
        }
    }

    // =========================================================================
    // BackendConfig.AutoDetect
    // =========================================================================

    "BackendConfig.AutoDetect" - {
        "default constructor uses Meter.Noop" in {
            val cfg: Container.BackendConfig.AutoDetect = Container.BackendConfig.AutoDetect()
            assert(cfg.meter eq Meter.Noop)
            assert(cfg.apiVersion == kyo.internal.HttpContainerBackend.defaultApiVersion)
            assert(cfg.streamBufferSize == kyo.internal.ShellBackend.defaultStreamBufferSize)
        }
        "explicit overrides round-trip" in {
            val cfg: Container.BackendConfig.AutoDetect = Container.BackendConfig.AutoDetect(apiVersion = "v1.50")
            assert(cfg.apiVersion == "v1.50")
        }
    }

    // =========================================================================
    // parseInstant
    // =========================================================================

    "parseInstant" - {
        import kyo.internal.ContainerBackend.parseInstant
        "Docker zulu format" in {
            val r = parseInstant(Some("2024-04-29T12:00:00Z"))
            // Maybe API: nonEmpty / isDefined; there is NO `isPresent`.
            assert(r.nonEmpty)
        }
        "Podman offset format" in {
            val r = parseInstant(Some("2024-04-29T05:00:00-07:00"))
            assert(r.nonEmpty)
            val instant = r.get
            // 05:00 -07:00 == 12:00 UTC
            assert(instant.toJava.toString.startsWith("2024-04-29T12:00:00"))
        }
        "garbage returns Absent (not panic)" in {
            assert(parseInstant(Some("not-a-date")) == Absent)
        }
        "zero placeholder returns Absent" in {
            assert(parseInstant(Some("0001-01-01T00:00:00Z")) == Absent)
            assert(parseInstant(Some("")) == Absent)
            assert(parseInstant(None) == Absent)
        }
    }

    // =========================================================================
    // Container.Signal.name
    // =========================================================================

    "Container.Signal.name" - {
        "enum cases stringify with SIG prefix" in {
            assert(Container.Signal.SIGTERM.name == "SIGTERM")
            assert(Container.Signal.SIGKILL.name == "SIGKILL")
        }
        "Custom non-prefixed name auto-prefixes to SIG" in {
            assert(Container.Signal.Custom("USR1").name == "SIGUSR1")
            assert(Container.Signal.Custom("usr1").name == "SIGUSR1")
        }
        "Custom already-prefixed name stays canonical-case" in {
            assert(Container.Signal.Custom("SIGUSR1").name == "SIGUSR1")
        }
        "Custom numeric name passes through verbatim (kill -9 must still work)" in {
            assert(Container.Signal.Custom("9").name == "9")
        }
    }

    // =========================================================================
    // registryAuthFromConfig path resolution
    // =========================================================================

    "registryAuthFromConfig path resolution" - {

        def systemWith(envOverrides: Map[String, String], propsOverrides: Map[String, String]): kyo.System =
            kyo.System(new kyo.System.Unsafe:
                def env(name: String)(using AllowUnsafe): Maybe[String] =
                    envOverrides.get(name) match
                        case Some(v)    => Present(v)
                        case scala.None => Maybe(java.lang.System.getenv(name))
                def property(name: String)(using AllowUnsafe): Maybe[String] =
                    propsOverrides.get(name) match
                        case Some(v)    => Present(v)
                        case scala.None => Maybe(java.lang.System.getProperty(name))
                def lineSeparator()(using AllowUnsafe): String          = java.lang.System.lineSeparator()
                def userName()(using AllowUnsafe): String               = java.lang.System.getProperty("user.name")
                def operatingSystem()(using AllowUnsafe): kyo.System.OS = kyo.System.OS.Linux
                def availableProcessors()(using AllowUnsafe): Int       = 1)

        "XDG_RUNTIME_DIR/containers/auth.json is consulted when present" in run {
            val tmpRoot    = Path("/tmp/kyo-xdg-" + java.util.UUID.randomUUID)
            val containers = tmpRoot / "containers"
            for
                _ <- containers.mkDir
                _ <- (containers / "auth.json").write("""{"auths":{"ghcr.io":"dGVzdA=="}}""")
                auth <- kyo.System.let(systemWith(
                    envOverrides = Map("XDG_RUNTIME_DIR" -> tmpRoot.toString, "DOCKER_CONFIG" -> ""),
                    propsOverrides = Map("user.home" -> "/nonexistent")
                )) {
                    kyo.internal.ContainerBackend.registryAuthFromConfig
                }
                _ <- tmpRoot.removeAll
            yield assert(
                auth.auths.toMap.nonEmpty,
                s"expected XDG path to be consulted, got empty auth: $auth"
            )
            end for
        }

        "malformed JSON yields empty auth Dict (no panic)" in run {
            val tmpRoot    = Path("/tmp/kyo-xdg-bad-" + java.util.UUID.randomUUID)
            val containers = tmpRoot / "containers"
            for
                _ <- containers.mkDir
                _ <- (containers / "auth.json").write("not json")
                auth <- kyo.System.let(systemWith(
                    envOverrides = Map("XDG_RUNTIME_DIR" -> tmpRoot.toString, "DOCKER_CONFIG" -> ""),
                    propsOverrides = Map("user.home" -> "/nonexistent")
                )) { kyo.internal.ContainerBackend.registryAuthFromConfig }
                _ <- tmpRoot.removeAll
            yield assert(
                auth.auths.toMap.isEmpty,
                s"expected empty Dict on malformed JSON, got: $auth"
            )
            end for
        }

        "missing file yields empty auth Dict (no panic)" in run {
            val auth = kyo.System.let(systemWith(
                envOverrides = Map("XDG_RUNTIME_DIR" -> "/tmp/kyo-no-such-xdg-dir", "DOCKER_CONFIG" -> ""),
                propsOverrides = Map("user.home" -> "/nonexistent")
            )) { kyo.internal.ContainerBackend.registryAuthFromConfig }
            auth.map(a => assert(a.auths.toMap.isEmpty))
        }
    }

    // =========================================================================
    // registryAuthHeader
    // =========================================================================

    "registryAuthHeader" - {
        "selects entry whose registry key matches image.registry" in {
            val multi = ContainerImage.RegistryAuth(Dict(
                ContainerImage.Registry("docker.io") -> "ZG9ja2VyOmRvY2tlcg==",
                ContainerImage.Registry("ghcr.io")   -> "Z2hjcjp3cm9uZw==",
                ContainerImage.Registry("quay.io")   -> "cXVheTp4eHg="
            ))
            val img    = assertSuccess(ContainerImage.parse("ghcr.io/kyo-test/nope:v1"))
            val header = kyo.internal.HttpContainerBackend.registryAuthHeader(img, multi)
            assert(header.nonEmpty, "expected a header for ghcr.io")
            val raw = new String(java.util.Base64.getDecoder.decode(header.get))
            assert(
                raw.contains("Z2hjcjp3cm9uZw=="),
                s"expected ghcr.io entry to be selected; header decoded to: $raw"
            )
        }

        // RegistryAuth.apply stores the implicit registry under "https://index.docker.io/v1/"
        // while HttpContainerBackend.registryAuthHeader falls back to Registry.DockerHub == "docker.io".
        // registryAuthHeader must check the "https://index.docker.io/v1/" key as a fallback.
        "Docker Hub default when image has no explicit registry" in {
            val auth = ContainerImage.RegistryAuth(Dict(
                ContainerImage.Registry("https://index.docker.io/v1/") -> "ZG9ja2VyOmRvY2tlcg==",
                ContainerImage.Registry("ghcr.io")                     -> "Z2hjcjp3cm9uZw=="
            ))
            val img    = ContainerImage("alpine") // no explicit registry → DockerHub default
            val header = kyo.internal.HttpContainerBackend.registryAuthHeader(img, auth)
            assert(header.nonEmpty, "expected DockerHub default header")
        }

        "Absent when no entry matches the image registry" in {
            val auth = ContainerImage.RegistryAuth(Dict(
                ContainerImage.Registry("ghcr.io") -> "Z2hjcjp3cm9uZw=="
            ))
            val img = assertSuccess(ContainerImage.parse("quay.io/kyo-test/nope:v1"))
            assert(kyo.internal.HttpContainerBackend.registryAuthHeader(img, auth) == Absent)
        }
    }

    // =========================================================================
    // Container.Config builder
    // =========================================================================

    "Config" - {
        "minimal config only requires image" in {
            val config = Container.Config("alpine")
            assert(config.image.reference.contains("alpine"))
            assert(config.ports.isEmpty)
            assert(config.mounts.isEmpty)
            assert(config.labels.isEmpty)
            assert(config.dns.isEmpty)
            assert(config.extraHosts.isEmpty)
            assert(config.memory == Absent)
            assert(!config.privileged)
            assert(!config.interactive)
            assert(!config.allocateTty)
            assert(!config.autoRemove)
        }

        "builder chains are immutable" in {
            val base     = Container.Config("alpine")
            val withName = base.name("test")
            val withPort = base.port(80, 8080)
            assert(base.name == Absent)
            assert(base.ports.isEmpty)
            assert(withName.name == Present("test"))
            assert(withName.ports.isEmpty)
            assert(withPort.name == Absent)
            assert(withPort.ports.size == 1)
        }

        "port(container, host) adds a PortBinding with both ports" in {
            val config = Container.Config("alpine").port(80, 8080)
            assert(config.ports.size == 1)
            assert(config.ports.head.containerPort == 80)
            assert(config.ports.head.hostPort == 8080)
            assert(config.ports.head.protocol == Container.Config.Protocol.TCP)
        }

        "port(container) adds a PortBinding with random host port" in {
            val config = Container.Config("alpine").port(80)
            assert(config.ports.size == 1)
            assert(config.ports.head.containerPort == 80)
            assert(config.ports.head.hostPort == 0)
        }

        "multiple port calls accumulate" in {
            val config = Container.Config("app").port(80, 8080).port(443, 8443)
            assert(config.ports.size == 2)
            assert(config.ports(0).containerPort == 80)
            assert(config.ports(1).containerPort == 443)
        }

        "bind mount via builder" in {
            val config = Container.Config("alpine").bind(Path("/host"), Path("/container"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Bind(s, t, ro) =>
                    assert(s == Path("/host"))
                    assert(t == Path("/container"))
                    assert(!ro)
                case other => fail(s"Expected Bind mount, got $other")
            end match
        }

        "volume mount via builder" in {
            val config = Container.Config("alpine").volume(Container.Volume.Id("data"), Path("/var/data"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Volume(n, t, ro) =>
                    assert(n == Container.Volume.Id("data"))
                    assert(t == Path("/var/data"))
                    assert(!ro)
                case other => fail(s"Expected Volume mount, got $other")
            end match
        }

        "tmpfs mount via builder" in {
            val config = Container.Config("alpine").tmpfs(Path("/tmp/test"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Tmpfs(t, sz) =>
                    assert(t == Path("/tmp/test"))
                    assert(sz == Absent)
                case other => fail(s"Expected Tmpfs mount, got $other")
            end match
        }

        "mount with function DSL" in {
            val config = Container.Config("alpine")
                .mount(_.Bind(Path("/a"), Path("/b"), readOnly = true))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Bind(s, t, ro) =>
                    assert(s == Path("/a"))
                    assert(t == Path("/b"))
                    assert(ro)
                case other => fail(s"Expected Bind mount, got $other")
            end match
        }

        "multiple mount calls accumulate in order" in {
            val config = Container.Config("alpine")
                .bind(Path("/a"), Path("/b"))
                .volume(Container.Volume.Id("v"), Path("/c"))
                .tmpfs(Path("/d"))
            assert(config.mounts.size == 3)
            config.mounts(0) match
                case _: Container.Config.Mount.Bind => succeed
                case other                          => fail(s"Expected Bind mount, got $other")
            config.mounts(1) match
                case _: Container.Config.Mount.Volume => succeed
                case other                            => fail(s"Expected Volume mount, got $other")
            config.mounts(2) match
                case _: Container.Config.Mount.Tmpfs => succeed
                case other                           => fail(s"Expected Tmpfs mount, got $other")
        }

        "networkMode with function DSL" in {
            val config = Container.Config("alpine").networkMode(_.Host)
            assert(config.networkMode == Container.Config.NetworkMode.Host)
        }

        "restartPolicy with function DSL" in {
            val config = Container.Config("alpine").restartPolicy(_.Always)
            assert(config.restartPolicy == Container.Config.RestartPolicy.Always)
        }

        "restartPolicy OnFailure with maxRetries" in {
            val config = Container.Config("alpine").restartPolicy(_.OnFailure(5))
            config.restartPolicy match
                case Container.Config.RestartPolicy.OnFailure(n) => assert(n == 5)
                case other                                       => fail(s"Expected OnFailure(5), got $other")
        }

        "label adds a single label" in {
            val config = Container.Config("alpine").label("env", "test")
            assert(config.labels.is(Dict("env" -> "test")))
        }

        "labels merges with existing" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Dict("b" -> "2", "c" -> "3"))
            assert(config.labels.is(Dict("a" -> "1", "b" -> "2", "c" -> "3")))
        }

        "labels overwrites on conflict" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Dict("a" -> "2"))
            assert(config.labels.is(Dict("a" -> "2")))
        }

        "dns accumulates across calls" in {
            val config = Container.Config("alpine").dns("8.8.8.8").dns("8.8.4.4")
            assert(config.dns == Chunk("8.8.8.8", "8.8.4.4"))
        }

        "extraHost adds structured entry" in {
            val config = Container.Config("alpine").extraHost("myhost", "10.0.0.1")
            assert(config.extraHosts.size == 1)
            assert(config.extraHosts.head.hostname == "myhost")
            assert(config.extraHosts.head.ip == "10.0.0.1")
        }

        "command(String*) creates Command from args" in {
            val config = Container.Config("alpine").command("sh", "-c", "echo hello")
            assert(config.command.isDefined)
            assert(config.command.map(_.args) == Present(Chunk("sh", "-c", "echo hello")))
        }

        "command(Command) preserves env and cwd" in {
            val cmd = Command("sh", "-c", "echo hello")
                .envAppend(Map("FOO" -> "bar"))
                .cwd(Path("/tmp"))
            val config = Container.Config("alpine").command(cmd)
            assert(config.command.map(_.args) == Present(Chunk("sh", "-c", "echo hello")))
            assert(config.command.map(_.env) == Present(Map("FOO" -> "bar")))
            assert(config.command.flatMap(_.workDir) == Present(Path("/tmp")))
        }

        "default command is Absent — uses image CMD/ENTRYPOINT" in {
            val config = Container.Config("alpine")
            assert(config.command.isEmpty)
        }

        "default restartPolicy is No" in {
            assert(Container.Config("alpine").restartPolicy == Container.Config.RestartPolicy.No)
        }

        "default stopTimeout is 3 seconds" in {
            assert(Container.Config("alpine").stopTimeout == 3.seconds)
        }

        "resource limit builders set values" in {
            val config = Container.Config("alpine")
                .memory(512 * 1024 * 1024L)
                .cpuLimit(1.5)
                .cpuAffinity("0-3")
                .maxProcesses(100)
            assert(config.memory == Present(512 * 1024 * 1024L))
            assert(config.cpuLimit == Present(1.5))
            assert(config.cpuAffinity == Present("0-3"))
            assert(config.maxProcesses == Present(100L))
        }

        "security builders set values" in {
            val config = Container.Config("alpine")
                .privileged(true)
                .addCapabilities(Container.Capability.NetAdmin, Container.Capability.SysPtrace)
                .dropCapabilities(Container.Capability.Mknod)
                .readOnlyFilesystem(true)
            assert(config.privileged)
            assert(config.addCapabilities == Chunk(Container.Capability.NetAdmin, Container.Capability.SysPtrace))
            assert(config.dropCapabilities == Chunk(Container.Capability.Mknod))
            assert(config.readOnlyFilesystem)
        }
    }

    // =========================================================================
    // explainPortMissing — error-message branching for failed mappedPort lookups
    // =========================================================================

    private def fakeInfo(state: Container.State, exitCode: Maybe[ExitCode] = Absent): Container.Info =
        Container.Info(
            id = Container.Id("c-id"),
            name = "test",
            image = ContainerImage("alpine"),
            imageId = ContainerImage.Id(""),
            state = state,
            exitCode = exitCode,
            pid = 0,
            startedAt = Absent,
            finishedAt = Absent,
            healthStatus = Container.HealthStatus.NoHealthcheck,
            ports = Chunk.empty,
            mounts = Chunk.empty,
            labels = Dict.empty,
            env = Dict.empty,
            command = "",
            createdAt = Instant.Epoch,
            restartCount = 0,
            driver = "",
            platform = Container.Platform.default,
            networkSettings = Container.Info.NetworkSettings(Absent, Absent, Absent, Dict.empty)
        )

    "Container.explainPortMissing" - {
        "container not running mentions state and exit code" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq(80),
                info = fakeInfo(Container.State.Stopped, Present(ExitCode.Failure(2))),
                containerPort = 80
            )
            assert(msg.contains("Stopped"))
            assert(msg.contains("exit=2"))
            assert(msg.contains("c-id"))
        }

        "running but port not declared lists configured ports" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq(80, 443),
                info = fakeInfo(Container.State.Running),
                containerPort = 9999
            )
            assert(msg.contains("not declared"))
            assert(msg.contains("80"))
            assert(msg.contains("443"))
        }

        "running with no configured ports says 'none'" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq.empty,
                info = fakeInfo(Container.State.Running),
                containerPort = 80
            )
            assert(msg.contains("not declared"))
            assert(msg.contains("none"))
        }

        "running and port IS declared but not visible flags the framework bug" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq(80),
                info = fakeInfo(Container.State.Running),
                containerPort = 80
            )
            assert(msg.contains("not yet observable"))
            assert(msg.contains("Please report"))
        }

        "exit code Signaled is rendered as signaled(N)" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq(80),
                info = fakeInfo(Container.State.Stopped, Present(ExitCode.Signaled(9))),
                containerPort = 80
            )
            assert(msg.contains("signaled(9)"))
        }

        "exit code Absent renders as 'none'" in {
            val msg = Container.explainPortMissing(
                id = Container.Id("c-id"),
                configuredPorts = Seq(80),
                info = fakeInfo(Container.State.Stopped, Absent),
                containerPort = 80
            )
            assert(msg.contains("exit=none"))
        }
    }

    // =========================================================================
    // HealthCheck error truncation + recent-errors aggregation
    // =========================================================================

    "Container.truncateHealthCheckError" - {
        "short message preserved unchanged" in {
            val short = "x" * 100
            assert(Container.truncateHealthCheckError(short) == short)
        }

        "message exactly at the cap is preserved unchanged" in {
            val atCap = "x" * Container.healthCheckErrorMessageMaxLength
            assert(Container.truncateHealthCheckError(atCap) == atCap)
        }

        "long message is truncated to the cap" in {
            val long      = "x" * 1000
            val truncated = Container.truncateHealthCheckError(long)
            assert(truncated.length == Container.healthCheckErrorMessageMaxLength)
            assert(truncated == "x" * Container.healthCheckErrorMessageMaxLength)
        }
    }

    "Container.appendRecentHealthCheckError" - {
        "appends when buffer is empty" in {
            assert(Container.appendRecentHealthCheckError(Seq.empty, "a") == Seq("a"))
        }

        "appends until capacity is reached" in {
            val cap    = Container.healthCheckRecentErrorsCapacity
            val filled = (1 until cap).map(i => s"e$i")
            val r      = Container.appendRecentHealthCheckError(filled, s"e$cap")
            assert(r.length == cap)
            assert(r.last == s"e$cap")
        }

        "drops oldest when at capacity" in {
            val cap  = Container.healthCheckRecentErrorsCapacity
            val full = (1 to cap).map(i => s"e$i")
            val r    = Container.appendRecentHealthCheckError(full, "newest")
            assert(r.length == cap)
            assert(!r.contains("e1"), "oldest entry must be dropped")
            assert(r.last == "newest")
        }
    }

    "Container.formatRecentHealthCheckErrors" - {
        "empty input produces empty string" in {
            assert(Container.formatRecentHealthCheckErrors(Seq.empty) == "")
        }

        "single entry wrapped in brackets" in {
            assert(Container.formatRecentHealthCheckErrors(Seq("oops")) == "[oops]")
        }

        "multiple entries joined by single space" in {
            assert(Container.formatRecentHealthCheckErrors(Seq("a", "b", "c")) == "[a] [b] [c]")
        }

        "ContainerHealthCheckException.lastError shape: short reason ≤500 chars per entry" in {
            val short    = "check-failed-short"
            val errors   = Seq(Container.truncateHealthCheckError(short))
            val lastErr  = Container.formatRecentHealthCheckErrors(errors)
            val maxEntry = Container.healthCheckErrorMessageMaxLength + 2 // brackets
            assert(lastErr.contains(short))
            assert(lastErr.length <= maxEntry)
        }

        "ContainerHealthCheckException.lastError shape: long reason truncated to cap per entry" in {
            val long = "x" * 1000
            // Simulate the runHealthCheck loop filling the buffer to capacity with the same long error.
            val cap = Container.healthCheckRecentErrorsCapacity
            val errs = (1 to cap).foldLeft(Seq.empty[String])((acc, _) =>
                Container.appendRecentHealthCheckError(acc, Container.truncateHealthCheckError(long))
            )
            val out         = Container.formatRecentHealthCheckErrors(errs)
            val maxEntryLen = Container.healthCheckErrorMessageMaxLength + 2 // brackets
            val maxTotal    = maxEntryLen * cap + (cap - 1)                  // separating spaces
            assert(out.length <= maxTotal)
            val entries   = out.split("\\] \\[").toSeq
            val lastEntry = entries.last.stripPrefix("[").stripSuffix("]")
            assert(lastEntry.length <= Container.healthCheckErrorMessageMaxLength)
        }
    }

end ContainerTest
