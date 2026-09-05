package kyo.internal

import kyo.*

class HttpContainerBackendTest extends BasePodTest:

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
                    HttpStatusException(HttpStatus(status), "POST", "http+unix://unused/images/create", body),
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
    }

end HttpContainerBackendTest
