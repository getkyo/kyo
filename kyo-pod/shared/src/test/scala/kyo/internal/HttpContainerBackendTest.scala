package kyo.internal

import kyo.*

class HttpContainerBackendTest extends BasePodTest:

    "runtime identity and CLI equivalent" - {

        "the CLI equivalent names the env var the runtime actually reads" in {
            // podman reads CONTAINER_HOST and ignores DOCKER_HOST, which is the whole reason a user staring at
            // an empty `podman ps` cannot reconcile it with the containers kyo-pod is managing.
            assert(
                HttpContainerBackend.cliEquivalent("/var/run/docker.sock", "podman") ==
                    "CONTAINER_HOST=unix:///var/run/docker.sock podman ps"
            )
            assert(
                HttpContainerBackend.cliEquivalent("/var/run/docker.sock", "docker") ==
                    "DOCKER_HOST=unix:///var/run/docker.sock docker ps"
            )
        }

        "the description carries the runtime and the command that reaches the same daemon" in {
            val backend = new HttpContainerBackend("/var/run/docker.sock", "v1.43", Meter.Noop, Present("podman"))
            val text    = backend.describe
            assert(text.contains("socket=/var/run/docker.sock"))
            assert(text.contains("runtime=podman"), s"expected the probed runtime, got: $text")
            assert(text.contains("CONTAINER_HOST=unix:///var/run/docker.sock podman ps"), s"missing the CLI equivalent: $text")
            assert(!text.contains("DOCKER_HOST"), s"podman does not read DOCKER_HOST: $text")
        }

        "the live backend reports the runtime the daemon itself reports" - runRuntimes { runtime =>
            // The container-backed half: the description has to be right against a real daemon, which is where
            // the socket-path heuristic went wrong. An installed CLI does not mean a reachable daemon, so a
            // socket that does not answer is skipped rather than asserted against.
            import AllowUnsafe.embrace.danger
            ContainerRuntime.findSocket(runtime) match
                case Some(path) =>
                    Abort.run[ContainerException] {
                        Container.withBackendConfig(_.UnixSocket(Path(path))) {
                            Container.currentBackendDescription
                        }
                    }.map {
                        case Result.Success(text) =>
                            assert(text.contains(s"runtime=$runtime"), s"expected runtime=$runtime in: $text")
                            val expectedEnv = if runtime == "podman" then "CONTAINER_HOST=" else "DOCKER_HOST="
                            assert(text.contains(expectedEnv), s"expected $expectedEnv in: $text")
                            assert(text.contains(path), s"expected the socket path in: $text")
                        case _ =>
                            succeed(s"the $runtime socket at $path does not answer on this host")
                    }
                case None => succeed(s"no $runtime socket on this host")
            end match
        }

        "the daemon's answer wins over a socket path that names a different runtime" in {
            // The reported host exactly: /var/run/docker.sock is a symlink to the podman machine socket, so
            // the path says docker while the daemon is podman. The oracle is an independent read of the
            // daemon's own /version, so this asserts the wiring rather than restating the classification.
            import AllowUnsafe.embrace.danger
            val dockerNamed = "/var/run/docker.sock"
            val guess       = new HttpContainerBackend(dockerNamed, "v1.43", Meter.Noop)
            assert(guess.runtimeName == "docker", "the path alone can only say docker")
            Abort.run[Throwable](HttpClient.getText(guess.url("/version"))).map {
                case Result.Success(body) =>
                    val fromDaemon = if body.toLowerCase.contains("podman") then "podman" else "docker"
                    Abort.run[ContainerException] {
                        Container.withBackendConfig(_.UnixSocket(Path(dockerNamed))) {
                            Container.currentBackendDescription
                        }
                    }.map {
                        case Result.Success(text) =>
                            assert(text.contains(s"runtime=$fromDaemon"), s"expected runtime=$fromDaemon in: $text")
                        case _ => succeed(s"$dockerNamed pinged but did not serve a backend")
                    }
                case _ => succeed(s"no daemon answers at $dockerNamed on this host")
            }
        }

        "the printed CLI equivalent actually reaches the daemon kyo-pod is managing" - runRuntimes { runtime =>
            // The point of printing the command is that a user can run it and see the containers their code
            // started. Asserting the string alone would not establish that, so this runs it: the ids the
            // command lists must contain every id Container.list(all = true) reports through the same socket.
            import AllowUnsafe.embrace.danger
            ContainerRuntime.findSocket(runtime) match
                case Some(path) =>
                    val envVar = if runtime == "podman" then "CONTAINER_HOST" else "DOCKER_HOST"
                    Abort.run[Any] {
                        Container.withBackendConfig(_.UnixSocket(Path(path))) {
                            for
                                seen <- Container.list(all = true)
                                out <- Command(runtime, "ps", "--all", "--format", "{{.ID}}")
                                    .envAppend(Map(envVar -> s"unix://$path"))
                                    .text
                            yield
                                val listed = out.linesIterator.map(_.trim).filter(_.nonEmpty).toSet
                                // Container.Id renders the full id; the CLI prints the short form.
                                val missing = seen.map(_.id.value).filterNot(id => listed.exists(short => id.startsWith(short)))
                                assert(
                                    missing.isEmpty,
                                    s"$envVar=unix://$path $runtime ps did not list ${missing.mkString(", ")}; it listed $listed"
                                )
                            end for
                        }
                    }.map {
                        case Result.Success(_) => ()
                        case _                 => succeed(s"the $runtime CLI or its socket is unavailable on this host")
                    }
                case None => succeed(s"no $runtime socket on this host")
            end match
        }

        "a probed runtime overrides the socket-path guess" in {
            // The reported host: /var/run/docker.sock is a symlink to the podman machine socket, so the path
            // says docker while the daemon is podman. The path is the fallback, never the answer when the
            // daemon has given one.
            val guessed = new HttpContainerBackend("/var/run/docker.sock", "v1.43", Meter.Noop)
            assert(guessed.runtimeName == "docker")
            val probed = new HttpContainerBackend("/var/run/docker.sock", "v1.43", Meter.Noop, Present("podman"))
            assert(probed.runtimeName == "podman")
            // A podman-named path still resolves to podman with nothing probed.
            val byPath = new HttpContainerBackend("/run/user/501/podman/podman.sock", "v1.43", Meter.Noop)
            assert(byPath.runtimeName == "podman")
        }
    }

    final private class FixedUUIDGenerator(value: UUID) extends UUIDGenerator:
        var calls = 0

        def v4(using Frame): UUID < Sync =
            Sync.defer {
                calls += 1
                value
            }

        def v7(using Frame): UUID < Sync =
            Sync.defer(value)
    end FixedUUIDGenerator

    private def claimLegacyFixture(using Frame): (UUID, Path, Path) < (Sync & Scope & Abort[FileReadException | FileStructureException]) =
        val uuid = UUID.v5(
            UUID.nil,
            Span.fromUnsafe(uniqueName("copyto-legacy-fixture").getBytes("UTF-8"))
        )
        Path.tempDir("kyo-copyto-claim-").map { claimed =>
            val parent        = claimed.parent.getOrElse(throw new IllegalStateException("temporary directory must have a parent"))
            val exact         = parent / s"kyo-copyto-${uuid.show}"
            val missingSource = parent / s"kyo-copyto-missing-${uuid.show}"
            Abort.run[FileStructureException](
                claimed.move(
                    exact,
                    Path.MoveOptions(
                        replace = Path.Replace.Never,
                        atomicity = Path.Atomicity.Required,
                        createFolders = false
                    )
                )
            ).map {
                case Result.Success(_) =>
                    missingSource.exists.map { sourceExists =>
                        if sourceExists then exact.removeAll.andThen(claimLegacyFixture)
                        else (uuid, exact, missingSource)
                    }
                case Result.Failure(_: FileAlreadyExistsException) =>
                    claimed.removeAll.andThen(claimLegacyFixture)
                case Result.Failure(error) =>
                    claimed.removeAll.andThen(Abort.fail(error))
                case Result.Panic(error) =>
                    claimed.removeAll.andThen(throw error)
            }
        }
    end claimLegacyFixture

    "create payload" - {
        // Regression guard for the podman 5.x compat API: docker and podman 4.x treat
        // PidsLimit 0 as "no limit configured", but podman 5.8.4 applies it as a literal
        // pids.max=0, so every container created through the HTTP backend died at start
        // (exit 2, sh unable to fork). The limit must be absent from the JSON unless the
        // caller configured one.
        def hostConfigJson(config: Container.Config): String < Sync =
            Sync.defer {
                val backend = new HttpContainerBackend("/unused.sock")
                Json.encode(backend.buildHostConfig(
                    config,
                    binds = Chunk.empty,
                    portBindings = Map.empty,
                    networkModeStr = "bridge",
                    tmpfs = Map.empty,
                    restartPol = backend.RestartPolicyEntry("no", 0)
                ))
            }

        "omits PidsLimit when maxProcesses is unset" in {
            hostConfigJson(Container.Config(ContainerImage("alpine"))).map { json =>
                assert(!json.contains("PidsLimit"), s"PidsLimit must be absent when unconfigured: $json")
            }
        }

        "carries PidsLimit when maxProcesses is set" in {
            hostConfigJson(Container.Config(ContainerImage("alpine")).maxProcesses(64)).map { json =>
                assert(json.contains("\"PidsLimit\":64"), s"configured limit must be encoded: $json")
            }
        }
    }

    "update payload" - {
        "omits PidsLimit when maxProcesses is unset" in {
            val backend = new HttpContainerBackend("/unused.sock")
            val json    = Json.encode(backend.UpdateRequest(Memory = 1024L))
            assert(!json.contains("PidsLimit"), s"PidsLimit must be absent when unconfigured: $json")
        }
    }

    "copyTo" - {
        "scoped UUID temp directories do not overwrite or delete the exact legacy staging path" in {
            claimLegacyFixture.map { (uuid, foreignPath, missingSource) =>
                val sentinel = foreignPath / "sentinel"
                Sync.ensure(Abort.run[FileStructureException](foreignPath.removeAll).unit) {
                    sentinel.write("foreign fixture").andThen {
                        val generator = new FixedUUIDGenerator(uuid)
                        val backend   = new HttpContainerBackend("/unused.sock")
                        val operation = backend.copyTo(
                            Container.Id("container"),
                            missingSource,
                            Path("destination")
                        )

                        assert(generator.calls == 0)

                        UUID.let(generator) {
                            Abort.run[ContainerException](operation)
                        }.map { result =>
                            assert(generator.calls == 1)
                            result match
                                case Result.Failure(error) =>
                                    assert(error.getMessage.contains("failed to copy source"))
                                case other =>
                                    fail(s"expected the missing source to fail after creating an isolated temp directory, got $other")
                            end match
                            sentinel.read.map(content => assert(content == "foreign fixture"))
                        }
                    }
                }
            }
        }
    }
end HttpContainerBackendTest
