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
    }

    // =========================================================================
    // HealthCheck.noop
    // =========================================================================

    "HealthCheck.noop" - {
        "is the noop singleton" in {
            val cfg = Container.Config(ContainerImage("alpine:3.19")).copy(healthCheck = Container.HealthCheck.noop)
            assert(cfg.healthCheck eq Container.HealthCheck.noop, "healthCheck should be the noop singleton")
        }
        "default config uses HealthCheck.noop" in {
            val cfg = Container.Config(image = ContainerImage("alpine:3.19"))
            assert(cfg.healthCheck eq Container.HealthCheck.noop, "default healthCheck should be HealthCheck.noop")
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

    "host accessor" - {
        "is the literal '127.0.0.1' on every Container instance" in {
            // The `host` field is a constant on the Container class — no backend access needed.
            // Construct a Container with null backend/healthState since reading `host` never dereferences them.
            val c = new Container(
                Container.Id("unit-test-host"),
                Container.Config(ContainerImage("alpine:3.19")),
                backend = null,
                healthState = null
            )
            assert(c.host == "127.0.0.1")
        }
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

end ContainerTest
