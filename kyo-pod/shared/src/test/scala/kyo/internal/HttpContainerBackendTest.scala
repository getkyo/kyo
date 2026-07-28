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

    private def claimLegacyFixture(using Frame): (UUID, Path, Path) < (Sync & Abort[FileFsException]) =
        val uuid = UUID.v5(
            UUID.nil,
            Span.fromUnsafe(uniqueName("copyto-legacy-fixture").getBytes("UTF-8"))
        )
        Path.tempDir("kyo-copyto-claim-").map { claimed =>
            val parent        = claimed.parent.getOrElse(throw new IllegalStateException("temporary directory must have a parent"))
            val exact         = parent / s"kyo-copyto-${uuid.show}"
            val missingSource = parent / s"kyo-copyto-missing-${uuid.show}"
            Abort.run[FileFsException](
                claimed.move(exact, replaceExisting = false, atomicMove = true, createFolders = false)
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

    "copyTo" - {
        "scoped UUID temp directories do not overwrite or delete the exact legacy staging path" in {
            claimLegacyFixture.map { (uuid, foreignPath, missingSource) =>
                val sentinel = foreignPath / "sentinel"
                Sync.ensure(foreignPath.removeAll.unit) {
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
