package kyo.internal

import kyo.*
import kyo.crypto.*

class HttpContainerBackendTest extends BasePodTest:

    "daemon deadline" - {
        val floor = HttpContainerBackend.defaultDaemonTimeout

        "raises the client's default to the configured floor" in {
            val deadline = HttpContainerBackend.daemonDeadline(floor, 5.seconds, Duration.Zero)
            assert(deadline == floor, s"expected the floor $floor, got $deadline")
        }

        "keeps a caller's longer timeout" in {
            val longer   = floor + 1.minute
            val deadline = HttpContainerBackend.daemonDeadline(floor, longer, Duration.Zero)
            assert(deadline == longer, s"expected the caller's $longer, got $deadline")
        }

        "adds a stop's grace window on top of the floor" in {
            val deadline = HttpContainerBackend.daemonDeadline(floor, 5.seconds, 10.seconds)
            assert(deadline == floor + 10.seconds, s"expected ${floor + 10.seconds}, got $deadline")
        }

        "a backend config carries its own floor" in {
            val deadline = HttpContainerBackend.daemonDeadline(2.minutes, 5.seconds, Duration.Zero)
            assert(deadline == 2.minutes, s"expected the configured 2 minutes, got $deadline")
        }
    }

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

        // The CLI pointed at the socket kyo-pod uses, as the printed equivalent tells a user to run it.
        def cli(runtime: String, path: String, args: String*): Command =
            Command((runtime +: args)*).envAppend(Map((if runtime == "podman" then "CONTAINER_HOST" else "DOCKER_HOST") -> s"unix://$path"))

        def psIds(runtime: String, path: String): Command = cli(runtime, path, "ps", "--all", "--format", "{{.ID}}")

        /** The ids `Container.list(all = true)` reports both before and after `ps --all` through the same socket that the CLI did not
          * list, and what it listed. `between` runs after the first API listing and before the CLI.
          *
          * The daemon is shared with every other process on the host, so a container can be removed while the CLI runs. Only an id the
          * API reports on both sides of the CLI call existed throughout it, so only those are evidence that the CLI sees a different daemon.
          */
        def unlistedByCli(
            runtime: String,
            path: String,
            between: Unit < (Async & Abort[Any]),
            listing: Command
        )(using Frame): (Set[String], Set[String]) < (Async & Abort[Any]) =
            Container.withBackendConfig(_.UnixSocket(Path(path))) {
                for
                    before <- Container.list(all = true)
                    _      <- between
                    out    <- listing.text
                    after  <- Container.list(all = true)
                yield
                    val listed     = out.linesIterator.map(_.trim).filter(_.nonEmpty).toSet
                    val throughout = before.map(_.id.value).toSet.intersect(after.map(_.id.value).toSet)
                    // Container.Id renders the full id; the CLI prints the short form.
                    (throughout.filterNot(id => listed.exists(short => id.startsWith(short))), listed)
                end for
            }

        def withRuntimeSocket(runtime: String)(f: String => Unit < (Async & Abort[Any]))(using
            Frame,
            kyo.test.AssertScope
        )
            : Unit < (Async & Abort[Any]) =
            import AllowUnsafe.embrace.danger
            ContainerRuntime.findSocket(runtime) match
                case Some(path) =>
                    Abort.run[Any](f(path)).map {
                        case Result.Success(_) => ()
                        case _                 => succeed(s"the $runtime CLI or its socket is unavailable on this host")
                    }
                case None => succeed(s"no $runtime socket on this host")
            end match
        end withRuntimeSocket

        "the printed CLI equivalent actually reaches the daemon kyo-pod is managing" - runRuntimes { runtime =>
            // The point of printing the command is that a user can run it and see the containers their code
            // started. Asserting the string alone would not establish that, so this runs it: the ids the
            // command lists must contain every id Container.list(all = true) reports through the same socket.
            withRuntimeSocket(runtime) { path =>
                unlistedByCli(runtime, path, Kyo.unit, psIds(runtime, path)).map { (missing, listed) =>
                    assert(missing.isEmpty, s"$runtime ps through $path did not list ${missing.mkString(", ")}; it listed $listed")
                }
            }
        }

        // The daemon is shared: another process can remove a container between the API listing and the CLI's, and that container
        // is no evidence that the CLI reaches a different daemon.
        "a container another process removes during the check is not reported as unlisted" - runRuntimes { runtime =>
            withRuntimeSocket(runtime) { path =>
                for
                    id     <- cli(runtime, path, "create", "docker.io/library/alpine:3", "true").text.map(_.trim)
                    result <- unlistedByCli(runtime, path, cli(runtime, path, "rm", "-f", id).waitFor.unit, psIds(runtime, path))
                yield
                    val (missing, listed) = result
                    assert(missing.isEmpty, s"$runtime ps through $path did not list ${missing.mkString(", ")}; it listed $listed")
                end for
            }
        }

        "a container present throughout that the CLI does not list is still reported" - runRuntimes { runtime =>
            withRuntimeSocket(runtime) { path =>
                cli(runtime, path, "create", "docker.io/library/alpine:3", "true").text.map(_.trim).map { id =>
                    Scope.run {
                        Scope.ensure(cli(runtime, path, "rm", "-f", id).waitFor.unit).andThen {
                            // A listing that reaches no daemon prints nothing.
                            unlistedByCli(runtime, path, Kyo.unit, Command("true")).map { (missing, _) =>
                                assert(missing.exists(_.startsWith(id.take(12))), s"expected $id among the unlisted, got $missing")
                            }
                        }
                    }
                }
            }
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

    private def claimLegacyFixture(using Frame): (UUID, Path, Path) < (Sync & Scope & Abort[FileSystemException]) =
        val uuid = UUID.v5(
            UUID.nil,
            Span.fromUnsafe(uniqueName("copyto-legacy-fixture").getBytes("UTF-8"))
        )
        Path.run(Path.tempDir("kyo-copyto-claim-").map { claimed =>
            val parent        = claimed.parent.getOrElse(throw new IllegalStateException("temporary directory must have a parent"))
            val exact         = parent / s"kyo-copyto-${uuid.show}"
            val missingSource = parent / s"kyo-copyto-missing-${uuid.show}"
            Abort.run[FileSystemException](
                Path.run(claimed.move(
                    exact,
                    Path.MoveOptions(
                        replace = Path.Replace.Never,
                        atomicity = Path.Atomicity.Required,
                        createFolders = false
                    )
                ))
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
        })
    end claimLegacyFixture

    "endpoint URLs" - {

        "a socket path with a space and a '+' reaches the daemon unchanged, as do query values" in {
            val backend = new HttpContainerBackend("/tmp/a b+c/docker.sock", "v1.43", Meter.Noop)
            val url     = HttpUrl.parse(backend.url("/containers/x/archive", "path" -> "/a b+c")).getOrThrow
            assert(url.unixSocket == Present("/tmp/a b+c/docker.sock"))
            assert(url.path == "/v1.43/containers/x/archive")
            assert(url.query("path") == Present("/a b+c"))
        }
    }

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
            Path.run(claimLegacyFixture.map { (uuid, foreignPath, missingSource) =>
                val sentinel = foreignPath / "sentinel"
                Sync.ensure(Abort.run[FileSystemException](Path.run(foreignPath.removeAll)).unit) {
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
            })
        }
    }

    "stat" - {

        /** `stat` of container `c1` against a fake daemon on a unix socket that answers with `header` as the path stat. The backend only
          * speaks `http+unix`, so a host that cannot bind a Unix socket cancels the leaf.
          */
        def statWith(header: String)(using
            Frame
        ): Result[ContainerException, Container.FileStat] < (Async & Scope & Abort[FileSystemException | HttpBindException]) =
            Sync.defer {
                if !TestUnixSockets.supported then throw kyo.test.TestCancelled("this host cannot bind a Unix socket for the fake daemon")
            }.andThen(Path.run(Path.tempDir("kyo-pod-stat-").map { dir =>
                val socket = (dir / "d.sock").toString
                val route  = HttpRoute.headRaw("v1.43" / "containers" / "c1" / "archive")
                    .response(_.header[String]("X-Docker-Container-Path-Stat"))
                val daemon = route.handler(_ => HttpResponse.ok.addField("X-Docker-Container-Path-Stat", header))
                HttpServer.init(HttpServerConfig.default.unixSocket(socket))(daemon).andThen {
                    Abort.run[ContainerException](new HttpContainerBackend(socket).stat(Container.Id("c1"), Path("/tmp/x")))
                }
            }))

        "a path-stat header that is not base64 fails as a decode error" in {
            statWith("not*base64").map {
                case Result.Failure(error: ContainerDecodeException) =>
                    assert(error.getMessage.contains("c1"), s"expected the container id in: ${error.getMessage}")
                case other =>
                    fail(s"expected a ContainerDecodeException, got $other")
            }
        }

        // Podman encodes the header with the URL-safe alphabet: the stat of `/tmp/~~~` carries `-` where the standard alphabet has `+`.
        "a path-stat header in the URL-safe alphabet, as podman sends it, decodes" in {
            val podmanHeader =
                "eyJuYW1lIjoifn5-Iiwic2l6ZSI6MCwibW9kZSI6NDIwLCJtdGltZSI6IjIwMjYtMTAtMDNUMjI6NDg6NDAuMzM2ODQ4MDQyLTA3OjAwIiwiaXNEaXIiOmZhbHNlLCJsaW5rVGFyZ2V0IjoiL3RtcC9-fn4ifQ=="
            statWith(podmanHeader).map {
                case Result.Success(stat) =>
                    assert(stat.name == "~~~")
                    assert(stat.linkTarget == Present("/tmp/~~~"))
                case other =>
                    fail(s"expected the stat of /tmp/~~~, got $other")
            }
        }
    }

    /** A failing registry must not be reported as a missing image.
      *
      * The pull path deliberately collapses every no-credentials failure into
      * `ContainerImageMissingException`, because a registry answers the same way for "does not exist" and
      * "needs credentials" and a caller with no credentials cannot act on the difference. A server error
      * asserts neither, and missing is the one classification callers treat as permanent: `Container.init`
      * scopes its retry to it while treating the up-front ensure as fail-fast, so a transient upstream
      * fault landed in the bucket nothing retries. Docker Hub answered 500 to a manifest HEAD for an image
      * that exists and the pull reported the image as gone.
      */
    "pull error classification" - {
        val pullImage = ContainerImage("redis", "7-alpine")

        def classify(
            status: Int,
            body: String,
            auth: Maybe[ContainerImage.RegistryAuth] = Absent
        )(using Frame): Result[ContainerException, Unit] < Sync =
            val backend = new HttpContainerBackend("/unused.sock")
            Abort.run[ContainerException](
                backend.normalizePullError(
                    HttpStatusException(HttpStatus.init(status).getOrThrow, "POST", "http+unix://unused/images/create", body),
                    pullImage,
                    auth
                )
            )
        end classify

        // A real daemon response body, quoting the registry's own status.
        val hubFailure =
            """Error response from daemon: Head "https://registry-1.docker.io/v2/library/redis/manifests/7-alpine": """ +
                "received unexpected HTTP status: 500 Internal Server Error"

        "the captured Docker Hub 500 is not a missing image" in {
            classify(404, hubFailure).map { result =>
                assert(
                    !result.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"a registry fault must not be classified as a missing image, got $result"
                )
                assert(result.failure.exists(_.isInstanceOf[ContainerOperationException]), s"expected an operation error, got $result")
            }
        }

        // Not being a missing image is only half of it. Container.init retries this class and nothing else on
        // the up-front ensure, so the fault has to arrive as the type that retry names: an unclassified
        // operation failure reads to every caller as terminal and is what CI saw when a 502 reached the pull.
        "a registry fault is typed so the caller can retry it" in {
            classify(404, hubFailure).map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerRegistryUnavailableException]),
                    s"expected a registry-unavailable failure, got $result"
                )
            }
        }

        // The exact shape the podman daemon returned on main run 35491732864: its own 500 wrapping the
        // gateway status the registry gave it, for an image that does not exist. The image being absent is
        // not something this response establishes, because the registry never got far enough to say so.
        "the captured podman 500 quoting a 502 is a registry fault" in {
            classify(500, """{"message":"received unexpected HTTP status: 502 Bad Gateway"}""").map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerRegistryUnavailableException]),
                    s"expected a registry-unavailable failure, got $result"
                )
            }
        }

        "a 5xx from the daemon itself is not a missing image" in {
            classify(500, """{"message":"internal error"}""").map { result =>
                assert(
                    !result.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"a 5xx must not be classified as a missing image, got $result"
                )
            }
        }

        // The conflation the branch exists for must survive: with no credentials, a denial and a 404 both
        // still read as missing, which is what callers can actually act on.
        "a denial with no credentials is still a missing image" in {
            classify(403, """{"message":"denied: requested access to the resource is denied"}""").map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"expected the no-credentials conflation to hold, got $result"
                )
            }
        }

        // An absence claim in the body outranks transport wording next to it: the daemon answered about the
        // image, so the classification follows that answer rather than the 5xx it also mentions.
        "an absence claim in the body wins over quoted server-error wording" in {
            classify(404, """{"message":"manifest unknown: received unexpected HTTP status: 500 Internal Server Error"}""").map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"an explicit absence claim must still read as missing, got $result"
                )
            }
        }

        // Whatever status the daemon chose, a body reporting that its connection to the registry failed says nothing about the image,
        // and must read as the registry fault the shell backend reports for the same failure.
        "a failed connection to the registry quoted under a 4xx is a registry fault" in {
            classify(
                404,
                """{"message":"Get \"https://auth.docker.io/token\": read tcp 172.17.0.2:41234->3.94.224.37:443: read: connection reset by peer"}"""
            ).map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerRegistryUnavailableException]),
                    s"a failed registry connection must not be classified as a missing image, got $result"
                )
            }
        }

        "a 404 with no credentials is still a missing image" in {
            classify(404, """{"message":"manifest unknown"}""").map { result =>
                assert(
                    result.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"expected a missing image, got $result"
                )
            }
        }

        // With credentials supplied the daemon's own signal is authoritative, and a denial body means the
        // supplied credentials were rejected whatever status carries it.
        "a denial with credentials supplied is an auth failure" in {
            classify(500, """{"message":"unauthorized: authentication required"}""", Present(ContainerImage.RegistryAuth(Dict.empty))).map {
                result =>
                    assert(result.failure.exists(_.isInstanceOf[ContainerAuthException]), s"expected an auth failure, got $result")
            }
        }

        // Docker's classic image store keeps one copy per digest reference, so a platform's pull of an index already cached for
        // another platform is refused mid-stream. Read as an unclassified failure, nothing tells the caller the store is the cause.
        "a refused overwrite of another platform's copy is a platform conflict" in {
            val digest  = "sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092"
            val image   = ContainerImage(s"docker.io/library/busybox@$digest")
            val arm64   = Container.Platform("linux", "arm64")
            val line    = s"""{"errorDetail":{"message":"cannot overwrite digest $digest"},"error":"cannot overwrite digest $digest"}"""
            val backend = new HttpContainerBackend("/unused.sock")
            Abort.run[ContainerException](Emit.run(backend.processPullLine(line, image, Present(arm64)))).map { result =>
                result.failure match
                    case Present(e: ContainerImagePlatformConflictException) =>
                        assert(e.image == image)
                        assert(e.platform == Present(arm64))
                        assert(e.detail.contains(digest))
                    case other => fail(s"expected a platform conflict, got $result")
            }
        }
    }

end HttpContainerBackendTest
