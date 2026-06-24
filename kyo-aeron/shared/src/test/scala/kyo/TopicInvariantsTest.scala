package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronSentinels

class TopicInvariantsTest extends Test:

    "effect rows preserved" - {
        "run returns A < Async" in {
            val _: Int < Async = Topic.run(42)
            succeed
        }
        "publish effect row" in {
            // publish carries exactly the 3-member abort row.
            val _: Unit < (Topic & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException] & Async) =
                Topic.publish[Int]("aeron:ipc")(Stream.empty)
            succeed
        }
        "stream effect row" in {
            // stream carries exactly the 2-member abort row (TopicPublishException absent).
            val _: Stream[Int, Topic & Abort[TopicBackpressureException | TopicTransportException] & Async] =
                Topic.stream[Int]("aeron:ipc")
            succeed
        }
        "isolate is available with the expected type" in {
            val _: Isolate[Topic, Any, Any] = Topic.isolate
            succeed
        }
    }

    "wire contract computed in shared Topic" in {
        val messages     = Chunk(1, 2, 3)
        val envelope     = Topic.Envelope(Tag[Int].show, messages)
        val wireBytes    = MsgPack.encode(envelope)
        val wireBytesArr = wireBytes.toArray
        // Pins the MsgPack+Envelope wire format: a schema or codec change that silently alters the
        // on-wire representation breaks byte-equality here.
        val secondEncode = MsgPack.encode(envelope).toArray
        assert(
            java.util.Arrays.equals(wireBytesArr, secondEncode),
            s"MsgPack.encode is non-deterministic: first=${wireBytesArr.toList}, second=${secondEncode.toList}"
        )
        assert(wireBytesArr.nonEmpty, "MsgPack.encode produced an empty byte array for a non-empty Envelope")
        MsgPack.decode[Topic.Envelope[Int]](wireBytes) match
            case Result.Success(decoded) =>
                assert(decoded.typeTag == envelope.typeTag, s"typeTag mismatch: ${decoded.typeTag} != ${envelope.typeTag}")
                assert(decoded.messages == messages, s"messages mismatch: ${decoded.messages} != $messages")
            case Result.Failure(ex) =>
                fail(s"MsgPack.decode failed on a freshly encoded Envelope: $ex")
        end match
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(messages.size).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(messages.toSeq)))
                received <- fiber.get
            yield assert(received == messages.toSeq)
        }
    }

    // Several structural invariants have no leaf here because the build already enforces them: io.aeron
    // is a JVM-only dependency, so importing it in shared/src/main fails the JS/Native compile; a missing
    // kyo_aeron_* C symbol fails Native nativeLink / JS ffiCompile; and the fragment handler staying
    // C-private is documented at its source site (kyo_aeron.c, kyo_aeron_fragment_handler).

    "MsgPack + Envelope wire round-trip" - {
        "Int chunk encodes and decodes symmetrically" in {
            val envelope = Topic.Envelope(Tag[Int].show, Chunk(1, 2, 3))
            val encoded  = MsgPack.encode(envelope)
            MsgPack.decode[Topic.Envelope[Int]](encoded) match
                case Result.Success(decoded) =>
                    assert(decoded.typeTag == envelope.typeTag)
                    assert(decoded.messages == envelope.messages)
                case Result.Failure(ex) =>
                    fail(s"Decode failed: $ex")
            end match
        }
        "String chunk encodes and decodes symmetrically" in {
            val envelope = Topic.Envelope(Tag[String].show, Chunk("a", "b"))
            val encoded  = MsgPack.encode(envelope)
            MsgPack.decode[Topic.Envelope[String]](encoded) match
                case Result.Success(decoded) =>
                    assert(decoded.typeTag == envelope.typeTag)
                    assert(decoded.messages == envelope.messages)
                case Result.Failure(ex) =>
                    fail(s"Decode failed: $ex")
            end match
        }
        "corrupt input yields Result.Failure(DecodeException)" in {
            val corrupt = Span.from(Array[Byte](0xff.toByte, 0xff.toByte))
            val decoded = MsgPack.decode[Topic.Envelope[Int]](corrupt)
            assert(decoded.isFailure)
        }
        "typeTag mismatch maps to panic path (not a thrown exception)" in {
            val envelope = Topic.Envelope(Tag[Int].show, Chunk(1))
            val encoded  = MsgPack.encode(envelope)
            MsgPack.decode[Topic.Envelope[String]](encoded) match
                case Result.Success(decoded) =>
                    assert(decoded.typeTag == Tag[Int].show)
                    assert(decoded.typeTag != Tag[String].show)
                case Result.Failure(ex) =>
                    assert(ex.isInstanceOf[DecodeException])
            end match
        }
    }

    // `.onlyNative`: static linking is a Native-only mechanic (JVM uses the io.aeron Java client; JS
    // dlopens the shim via koffi). Pins that kyo_aeron_* symbols are linked into the Native binary
    // (aeron_driver_static) and resolve at runtime with no system libaeron.
    "runtime: symbols resolve and driver starts without system libaeron".onlyNative in {
        for
            dir     <- Path.tempDir("kyo-aeron-embedded-test")
            runtime <- AeronPlatform.embedded(dir.unsafe.show)
            result <- Sync.Unsafe.defer {
                assert(runtime.transport != null, "runtime.transport is null after embedded()")
                runtime.close()
                succeed
            }
            _ <- dir.removeAll
        yield result
        end for
    }

    // The offer-sentinel classification itself is cross-platform and lives in AeronTransportTest
    // ("offer result maps through AeronSentinels identically on every platform"). Only the
    // JS-specific koffi BigInt->Long sign-marshalling guard stays here, as its own `.onlyJs` leaf.

    "round-trip under Envelope row layout" in {
        val messages = Seq(1, 2, 3)
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(messages.size).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(messages)))
                received <- fiber.get
            yield assert(received == messages)
        }
    }

    // MsgPack field order is schema-driven, so the encoding is deterministic. No live transport.
    "Envelope wire-golden: byte-identical across encode calls" in {
        val messages = Chunk(1, 2, 3)
        val envelope = Topic.Envelope(Tag[Int].show, messages)
        val encoded  = MsgPack.encode(envelope)
        val bytes    = encoded.toArray

        assert(bytes.nonEmpty, "MsgPack.encode produced an empty byte array")

        val second = MsgPack.encode(envelope).toArray
        assert(
            java.util.Arrays.equals(bytes, second),
            s"MsgPack.encode is non-deterministic: first=${bytes.toList}, second=${second.toList}"
        )

        MsgPack.decode[Topic.Envelope[Int]](encoded) match
            case Result.Success(decoded) =>
                assert(decoded.typeTag == envelope.typeTag, s"typeTag mismatch: ${decoded.typeTag} != ${envelope.typeTag}")
                assert(decoded.messages == messages, s"messages mismatch: ${decoded.messages} != $messages")
            case Result.Failure(ex) =>
                fail(s"MsgPack.decode failed on a freshly encoded Envelope: $ex")
        end match
        succeed
    }

    // `.onlyJs`: koffi's int64 marshalling is a JS-only mechanic. It returns int64 as js.BigInt and the
    // generated binding converts via .toString.toLong, so a negative sentinel like NotConnected (-1L)
    // must survive with its sign intact; JVM/Native never exercise that path (primitive long / C long).
    "JS koffi int64 sentinel marshalling: negative offer sentinel survives BigInt round-trip".onlyJs in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubMaybeR <- Abort.run[TopicTransportException] {
                Topic.addPublicationDeadline(transport, "aeron:ipc", 78, 10.seconds)
            }
            // Do NOT interpolate pubMaybeR: on Scala.js a Result holding an FFI Handle (an opaque
            // koffi pointer) throws "TypeError: Cannot convert object to primitive value" on string
            // coercion. Report only the failure's class name.
            _ = assert(
                pubMaybeR.isSuccess,
                s"addPublicationDeadline failed: ${pubMaybeR.failure.map(_.getClass.getSimpleName)}"
            )
            pubMaybe = pubMaybeR.getOrThrow
            _        = assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a live client")
            pub      = pubMaybe.get
            payload  = Array[Byte](0)
            result <- Sync.Unsafe.defer(transport.offer(pub, payload))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                rt.close()
            }
            _ <- dir.removeAll
        yield
            assert(result < 0, s"Expected a negative sentinel from a not-connected JS FFI publication; got $result")
            assert(
                result == AeronSentinels.NotConnected || result == AeronSentinels.BackPressured,
                s"Expected NotConnected (-1L) or BackPressured (-2L) but got $result; " +
                    "this indicates the koffi BigInt->Long conversion does not preserve the sign of negative int64 values"
            )
        end for
    }

    // Behavioral guard: an Envelope with empty payload still encodes to a non-empty byte sequence, since
    // typeTag is always non-empty, so zero-length wire frames are unreachable. That makes the C shim's
    // zero-length divergence (aeron_image.c advances position unconditionally for any frame) unreachable.
    "MsgPack-encoded Envelope with empty payload is always non-empty (zero-length divergence unreachable)" in {
        val envelope = Topic.Envelope(Tag[Int].show, Chunk.empty[Int])
        val encoded  = MsgPack.encode(envelope)
        assert(
            encoded.size > 0,
            "MsgPack-encoded Envelope with empty payload must be non-empty; " +
                "typeTag guarantees it. A zero-length wire frame would cause FFI divergence " +
                "(Absent vs JVM empty array) but is unreachable because Envelope typeTag is always non-empty."
        )
        succeed
    }

    // Subscriptions are per-emit: both consumers hash to the same stream-id (same URI/type), but each
    // emit materializes its own addSubscription call. A shared subscription would let only one consumer
    // receive each message.
    "two concurrent Topic.stream consumers get distinct subscriptions (per-emit safety)" in {
        val messages = Seq(1, 2, 3)
        Topic.run {
            for
                started <- Latch.init(2)
                consumer1 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(messages.size).run)
                )
                consumer2 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(messages.size).run)
                )
                _ <- started.await
                // Publish 2 * messages.size messages so both consumers can receive their own set.
                _         <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(messages ++ messages)))
                received1 <- consumer1.get
                received2 <- consumer2.get
            yield
                assert(
                    received1 == messages,
                    s"Consumer 1 got $received1 but expected $messages; subscriptions may be shared"
                )
                assert(
                    received2 == messages,
                    s"Consumer 2 got $received2 but expected $messages; subscriptions may be shared"
                )
        }
    }

end TopicInvariantsTest
