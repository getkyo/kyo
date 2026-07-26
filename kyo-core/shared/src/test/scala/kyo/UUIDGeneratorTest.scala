package kyo

import kyo.internal.UUIDEntropyPlatform

class UUIDGeneratorTest extends kyo.test.Test[Any]:

    private val v4Fixed = parse("00112233-4455-4677-a899-aabbccddeeff")
    private val v7Fixed = parse("00000000-03e8-7000-8000-000000000001")

    private def parse(value: String): UUID =
        UUID.parse(value).getOrThrow

    private def entropy(values: Int*): Array[Byte] =
        values.map(_.toByte).toArray

    private def entropyWithLast(last: Int): Array[Byte] =
        Array.fill[Byte](15)(0) ++ Array(last.toByte)

    private def deterministic(clockMillis: Long*)(entropyBytes: Array[Byte]*): UUIDGenerator =
        UUIDGenerator.test(
            clockMillis = Chunk.from(clockMillis),
            entropy = Chunk.from(entropyBytes.flatten.toArray)
        )

    final private class FixedGenerator(val v4Value: UUID, val v7Value: UUID) extends UUIDGenerator:
        def v4(using Frame): UUID < Sync = Sync.defer(v4Value)
        def v7(using Frame): UUID < Sync = Sync.defer(v7Value)

    final private class RecordingGenerator(v4Value: UUID, v7Value: UUID) extends UUIDGenerator:
        var v4Calls = 0
        var v7Calls = 0

        def v4(using Frame): UUID < Sync =
            Sync.defer {
                v4Calls += 1
                v4Value
            }

        def v7(using Frame): UUID < Sync =
            Sync.defer {
                v7Calls += 1
                v7Value
            }
    end RecordingGenerator

    "capability API" - {
        "declares only Sync on generator operations and preserves the scoped computation effects" in {
            val generatorV4: UUID < Sync = UUIDGenerator.live.v4
            val generatorV7: UUID < Sync = UUIDGenerator.live.v7
            val companionV4: UUID < Sync = UUIDGenerator.v4
            val companionV7: UUID < Sync = UUIDGenerator.v7
            val companionLet: Int < (Abort[String] & Sync) =
                UUIDGenerator.let(UUIDGenerator.live)(Abort.fail("expected"))
            val extensionV4: UUID < Sync = UUID.v4
            val extensionV7: UUID < Sync = UUID.v7
            val extensionLet: Int < (Abort[String] & Sync) =
                UUID.let(UUIDGenerator.live)(Abort.fail("expected"))

            discard(generatorV4)
            discard(generatorV7)
            discard(companionV4)
            discard(companionV7)
            discard(companionLet)
            discard(extensionV4)
            discard(extensionV7)
            discard(extensionLet)
            succeed("all public generation surfaces have their exact declared effect rows")
        }

        "scoped helpers preserve the supplied computation failure" in {
            val generator = new FixedGenerator(v4Fixed, v7Fixed)
            val sentinel  = "sentinel"
            val companion: Int < (Abort[String] & Sync) =
                UUIDGenerator.let(generator)(Abort.fail(sentinel))
            val extension: Int < (Abort[String] & Sync) =
                UUID.let(generator)(Abort.fail(sentinel))

            for
                companionResult <- Abort.run[String](companion)
                extensionResult <- Abort.run[String](extension)
            yield
                companionResult match
                    case Result.Failure(actual) => assert(actual == sentinel)
                    case other                  => fail(s"expected UUIDGenerator.let failure, got $other")
                extensionResult match
                    case Result.Failure(actual) => assert(actual == sentinel)
                    case other                  => fail(s"expected UUID.let failure, got $other")
            end for
        }

        "companion-style operations delegate to the scoped generator" in {
            val generator = new RecordingGenerator(v4Fixed, v7Fixed)
            UUID.let(generator) {
                for
                    generatedV4 <- UUID.v4
                    generatedV7 <- UUID.v7
                yield
                    assert(generatedV4 == v4Fixed)
                    assert(generatedV7 == v7Fixed)
                    assert(generator.v4Calls == 1)
                    assert(generator.v7Calls == 1)
            }
        }

        "v4String delegates to the scoped generator and renders canonical text" in {
            val generator = new RecordingGenerator(v4Fixed, v7Fixed)

            UUID.let(generator)(UUID.v4String).map { generated =>
                assert(generated == "00112233-4455-4677-a899-aabbccddeeff")
                assert(generator.v4Calls == 1)
                assert(generator.v7Calls == 0)
            }
        }

        "UUIDGenerator operations delegate to the scoped generator" in {
            val generator = new RecordingGenerator(v4Fixed, v7Fixed)
            UUIDGenerator.let(generator) {
                for
                    generatedV4 <- UUIDGenerator.v4
                    generatedV7 <- UUIDGenerator.v7
                yield
                    assert(generatedV4 == v4Fixed)
                    assert(generatedV7 == v7Fixed)
                    assert(generator.v4Calls == 1)
                    assert(generator.v7Calls == 1)
            }
        }

        "the UUIDGenerator companion installs the supplied generator" in {
            val generator = new FixedGenerator(v4Fixed, v7Fixed)
            UUIDGenerator.let(generator)(UUID.v4).map: generated =>
                assert(generated == v4Fixed)
        }

        "live operations generate RFC version 4 and version 7 values" in {
            for
                generatedV4 <- UUIDGenerator.live.v4
                generatedV7 <- UUIDGenerator.live.v7
            yield
                assert(generatedV4.version == 4)
                assert(generatedV4.variant == UUID.Variant.RFC)
                assert(generatedV7.version == 7)
                assert(generatedV7.variant == UUID.Variant.RFC)
                generatedV7.unixTimestampMillis match
                    case Present(_) => succeed("live version 7 UUID contains its timestamp")
                    case Absent     => fail("live version 7 UUID did not contain its timestamp")
            end for
        }

        "live version 7 observes the dynamically scoped Kyo Clock" in {
            val controlledMillis = 8000000000000L

            Clock.withTimeControl { control =>
                for
                    _         <- control.set(Instant.Epoch + controlledMillis.millis)
                    generated <- UUID.v7
                yield assert(generated.unixTimestampMillis == Maybe(controlledMillis))
            }
        }
    }

    "scoping" - {
        "nested scopes restore the enclosing generator" in {
            val outer   = new FixedGenerator(v4Fixed, v7Fixed)
            val innerV4 = parse("ffeeddcc-bbaa-4988-b766-554433221100")
            val inner   = new FixedGenerator(innerV4, v7Fixed)

            UUID.let(outer) {
                for
                    before <- UUID.v4
                    nested <- UUID.let(inner)(UUID.v4)
                    after  <- UUID.v4
                yield assert(Chunk(before, nested, after) == Chunk(v4Fixed, innerV4, v4Fixed))
            }
        }

        "concurrent dynamic scopes remain isolated" in {
            val leftV4  = parse("00000000-0000-4000-8000-000000000001")
            val rightV4 = parse("00000000-0000-4000-8000-000000000002")
            val left    = new FixedGenerator(leftV4, v7Fixed)
            val right   = new FixedGenerator(rightV4, v7Fixed)

            for
                entered <- Latch.init(2)
                release <- Latch.init(1)
                generated <- Scope.run {
                    for
                        leftFiber <- Fiber.init(
                            UUID.let(left)(entered.release.andThen(release.await).andThen(UUID.v4))
                        )
                        rightFiber <- Fiber.init(
                            UUID.let(right)(entered.release.andThen(release.await).andThen(UUID.v4))
                        )
                        _             <- entered.await
                        leftFinished  <- leftFiber.done
                        rightFinished <- rightFiber.done
                        _             <- release.release
                        leftResult    <- leftFiber.get
                        rightResult   <- rightFiber.get
                    yield
                        assert(!leftFinished)
                        assert(!rightFinished)
                        Chunk(leftResult, rightResult)
                }
            yield assert(generated == Chunk(leftV4, rightV4))
            end for
        }
    }

    "deterministic generator" - {
        "checks finite entropy blocks without integer overflow" in {
            assert(UUIDGenerator.containsEntropyBlock(size = 16, start = 0))
            assert(!UUIDGenerator.containsEntropyBlock(size = 15, start = 0))
            assert(UUIDGenerator.containsEntropyBlock(size = Int.MaxValue, start = Int.MaxValue - 16))
            assert(!UUIDGenerator.containsEntropyBlock(size = Int.MaxValue, start = Int.MaxValue - 15))
            assert(!UUIDGenerator.containsEntropyBlock(size = Int.MaxValue, start = -1))
        }

        "consumes finite entropy in order and fails after it is exhausted" in {
            val firstEntropy = entropy(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0xa6, 0x77,
                0xe8, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
            )
            val secondEntropy = entropy(
                0xff, 0xee, 0xdd, 0xcc, 0xbb, 0xaa, 0x19, 0x88,
                0x37, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00
            )
            val generator = deterministic()(firstEntropy, secondEntropy)

            for
                first     <- generator.v4
                second    <- generator.v4
                exhausted <- Abort.run[Any](generator.v4)
            yield
                assert(first.show == "00112233-4455-4677-a899-aabbccddeeff")
                assert(second.show == "ffeeddcc-bbaa-4988-b766-554433221100")
                assert(exhausted.isPanic)
            end for
        }
    }

    "version 4" - {
        "matches the published RFC 9562 version 4 example" in {
            val generator = deterministic()(entropy(
                0x91, 0x91, 0x08, 0xf7, 0x52, 0xd1, 0x43, 0x20,
                0x9b, 0xac, 0xf8, 0x47, 0xdb, 0x41, 0x48, 0xa8
            ))

            generator.v4.map: generated =>
                assert(generated.show == "919108f7-52d1-4320-9bac-f847db4148a8")
        }

        "maps secure entropy exactly before stamping version and variant bits" in {
            val generator = deterministic()(entropy(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0xa6, 0x77,
                0xe8, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
            ))

            generator.v4.map: generated =>
                assert(generated.show == "00112233-4455-4677-a899-aabbccddeeff")
                assert(generated.version == 4)
                assert(generated.variant == UUID.Variant.RFC)
        }
    }

    "version 7" - {
        "matches the published RFC 9562 version 7 example" in {
            val generator = deterministic(0x017f22e279b0L)(entropy(
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0c, 0xc3,
                0x18, 0xc4, 0xdc, 0x0c, 0x0c, 0x07, 0x39, 0x8f
            ))

            generator.v7.map: generated =>
                assert(generated.show == "017f22e2-79b0-7cc3-98c4-dc0c0c07398f")
        }

        "panics with the exact failure when the observed timestamp is negative" in {
            val generator = deterministic(-1L)(entropyWithLast(0))

            Abort.run[Any](generator.v7).map: result =>
                result match
                    case Result.Panic(actual: IllegalArgumentException) =>
                        assert(actual.getMessage == "UUID version 7 timestamp must be between 0 and 281474976710655 milliseconds, got -1")
                    case other => fail(s"expected negative UUID version 7 timestamp panic, got $other")
        }

        "panics with the exact failure when the observed timestamp exceeds 48 bits" in {
            val generator = deterministic(0x1000000000000L)(entropyWithLast(0))

            Abort.run[Any](generator.v7).map: result =>
                result match
                    case Result.Panic(actual: IllegalArgumentException) =>
                        assert(
                            actual.getMessage ==
                                "UUID version 7 timestamp must be between 0 and 281474976710655 milliseconds, got 281474976710656"
                        )
                    case other => fail(s"expected oversized UUID version 7 timestamp panic, got $other")
        }

        "maps the observed timestamp and secure payload into the RFC layout" in {
            val generator = deterministic(0x010203040506L)(entropy(
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x26, 0x47,
                0x68, 0x89, 0xaa, 0xcb, 0xec, 0x0d, 0x2e, 0x4f
            ))

            generator.v7.map: generated =>
                assert(generated.show == "01020304-0506-7647-a889-aacbec0d2e4f")
                assert(generated.version == 7)
                assert(generated.variant == UUID.Variant.RFC)
                assert(generated.unixTimestampMillis == Maybe(0x010203040506L))
        }

        "uses a later observed timestamp with a freshly sampled payload" in {
            val generator = deterministic(1000L, 2000L)(entropyWithLast(1), entropyWithLast(2))

            for
                first  <- generator.v7
                second <- generator.v7
            yield
                assert(first.show == "00000000-03e8-7000-8000-000000000001")
                assert(second.show == "00000000-07d0-7000-8000-000000000002")
                assert(first.compare(second) < 0)
            end for
        }

        "increments the 74-bit payload when the timestamp repeats" in {
            val generator = deterministic(1000L, 1000L, 1000L)(entropyWithLast(0x7e))

            for
                first  <- generator.v7
                second <- generator.v7
                third  <- generator.v7
            yield
                assert(first.show == "00000000-03e8-7000-8000-00000000007e")
                assert(second.show == "00000000-03e8-7000-8000-00000000007f")
                assert(third.show == "00000000-03e8-7000-8000-000000000080")
            end for
        }

        "carries a payload increment across the RFC variant bits" in {
            val lower62BitsMax =
                Array.fill[Byte](8)(0) ++ entropy(0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
            val generator = deterministic(1000L, 1000L)(lower62BitsMax)

            for
                beforeCarry <- generator.v7
                afterCarry  <- generator.v7
            yield
                assert(beforeCarry.show == "00000000-03e8-7000-bfff-ffffffffffff")
                assert(afterCarry.show == "00000000-03e8-7001-8000-000000000000")
                assert(afterCarry.variant == UUID.Variant.RFC)
            end for
        }

        "retains the logical millisecond and increments the payload when time moves backward" in {
            val generator = deterministic(1000L, 999L, 998L)(entropyWithLast(0))

            for
                first  <- generator.v7
                second <- generator.v7
                third  <- generator.v7
            yield
                assert(first.show == "00000000-03e8-7000-8000-000000000000")
                assert(second.show == "00000000-03e8-7000-8000-000000000001")
                assert(third.show == "00000000-03e8-7000-8000-000000000002")
                assert(Chunk(first, second, third).map(_.unixTimestampMillis) == Chunk.fill(3)(Maybe(1000L)))
            end for
        }

        "advances the logical millisecond and reseeds after payload exhaustion" in {
            val exhaustedPayload =
                Array.fill[Byte](6)(0) ++
                    entropy(0x0f, 0xff, 0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
            val generator = deterministic(1000L, 1000L)(exhaustedPayload, entropyWithLast(0x2a))

            for
                exhausted <- generator.v7
                advanced  <- generator.v7
            yield
                assert(exhausted.show == "00000000-03e8-7fff-bfff-ffffffffffff")
                assert(advanced.show == "00000000-03e9-7000-8000-00000000002a")
                assert(advanced.unixTimestampMillis == Maybe(1001L))
                assert(exhausted.compare(advanced) < 0)
            end for
        }

        "increments a nonexhausted payload while preserving the maximum timestamp" in {
            val payloadBeforeMaximum =
                Array.fill[Byte](6)(0) ++
                    entropy(0x0f, 0xff, 0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe)
            val generator = deterministic(0xffffffffffffL, 0xffffffffffffL)(payloadBeforeMaximum)

            for
                before <- generator.v7
                atMax  <- generator.v7
            yield
                assert(before.show == "ffffffff-ffff-7fff-bfff-fffffffffffe")
                assert(atMax.show == "ffffffff-ffff-7fff-bfff-ffffffffffff")
                assert(atMax.unixTimestampMillis == Maybe(0xffffffffffffL))
            end for
        }

        "panics instead of wrapping when the maximum timestamp payload is exhausted" in {
            val exhaustedPayload =
                Array.fill[Byte](6)(0) ++
                    entropy(0x0f, 0xff, 0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
            val generator = deterministic(0xffffffffffffL, 0xffffffffffffL)(exhaustedPayload, entropyWithLast(0))

            for
                exhausted <- generator.v7
                result    <- Abort.run[Any](generator.v7)
            yield
                assert(exhausted.show == "ffffffff-ffff-7fff-bfff-ffffffffffff")
                result match
                    case Result.Panic(actual: IllegalStateException) =>
                        assert(actual.getMessage == "UUID version 7 timestamp and payload space exhausted")
                    case other => fail(s"expected UUID version 7 exhaustion panic, got $other")
                end match
            end for
        }

        "uses one atomic sequence for concurrent calls on a single generator" in {
            val count = 64
            val generator: UUIDGenerator.TestControl = UUIDGenerator.testControlled(
                clockMillis = Chunk.fill(count + 1)(1000L),
                entropy = Chunk.from(entropyWithLast(0))
            )

            for
                seed     <- generator.v7
                entered  <- Latch.init(count)
                release  <- Latch.init(1)
                observed <- AtomicRef.init(Chunk.empty[UUID])
                generated <- Scope.run {
                    for
                        fibers <- Kyo.fill(count) {
                            Fiber.init(generator.v7WithStateReadHook { previous =>
                                observed
                                    .getAndUpdate(_.append(previous))
                                    .andThen(entered.release)
                                    .andThen(release.await)
                            })
                        }
                        _                     <- entered.await
                        observedBeforeRelease <- observed.get
                        finishedBeforeRelease <- Kyo.foreach(fibers)(_.done)
                        _                     <- release.release
                        results               <- Kyo.foreach(fibers)(_.get)
                    yield
                        assert(observedBeforeRelease == Chunk.fill(count)(seed))
                        assert(finishedBeforeRelease == Chunk.fill(count)(false))
                        results
                }
                sorted = generated.toSeq.sorted
            yield
                assert(seed.show == "00000000-03e8-7000-8000-000000000000")
                assert(sorted.size == count)
                assert(sorted.distinct.size == count)
                assert(sorted.sliding(2).forall {
                    case Seq(previous, next) => previous.compare(next) < 0
                    case _                   => true
                })
                assert(sorted.head.show == "00000000-03e8-7000-8000-000000000001")
                assert(sorted.last.show == "00000000-03e8-7000-8000-000000000040")
            end for
        }
    }

    "failure handling" - {
        "keeps version 4 entropy failures as Sync panics without a weaker fallback" in {
            val failure = new RuntimeException("secure entropy unavailable")
            val generator = UUIDGenerator.init(
                () => 1000L,
                new UUIDEntropyPlatform:
                    def next16(using Frame): Span[Byte] < Sync = Sync.defer(throw failure)
            )

            Abort.run[Any](generator.v4).map: result =>
                result match
                    case Result.Panic(actual) => assert(actual eq failure)
                    case other                => fail(s"expected secure entropy panic, got $other")
        }

        "keeps version 7 entropy failures as Sync panics without a random timestamp or counter fallback" in {
            val failure = new RuntimeException("secure entropy unavailable")
            val generator = UUIDGenerator.init(
                () => 1000L,
                new UUIDEntropyPlatform:
                    def next16(using Frame): Span[Byte] < Sync = Sync.defer(throw failure)
            )

            Abort.run[Any](generator.v7).map: result =>
                result match
                    case Result.Panic(actual) => assert(actual eq failure)
                    case other                => fail(s"expected secure entropy panic, got $other")
        }
    }

end UUIDGeneratorTest
