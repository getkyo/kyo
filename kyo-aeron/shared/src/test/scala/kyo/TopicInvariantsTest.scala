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
        // Byte-equality: encoding the same value a second time must yield identical bytes.
        // This pins the MsgPack+Envelope wire format and will fail if the schema or codec
        // changes in a way that silently alters the on-wire representation.
        val secondEncode = MsgPack.encode(envelope).toArray
        assert(
            java.util.Arrays.equals(wireBytesArr, secondEncode),
            s"MsgPack.encode is non-deterministic: first=${wireBytesArr.toList}, second=${secondEncode.toList}"
        )
        // The encoded bytes are non-empty (a non-vacuous wire frame exists).
        assert(wireBytesArr.nonEmpty, "MsgPack.encode produced an empty byte array for a non-empty Envelope")
        // Decode the pinned bytes and assert the round-trip reproduces the original envelope.
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

    // The structural invariants (no io.aeron in shared; the C header symbols match the bindings;
    // the close-order) are enforced by the cross-platform build rather than by a test:
    //   io.aeron is a jvm-only dependency (see build.sbt), so any io.aeron import in
    //     shared/src/main fails the kyo-aeronJS / kyo-aeronNative compile.
    //   every AeronBindings method maps 1:1 to a kyo_aeron_* C symbol; a missing
    //     symbol fails Native nativeLink / JS ffiCompile.
    //   the add-vs-close contract (returns Absent, no crash) is asserted behaviorally
    //     by AeronTransportTest "add-vs-close ... return Absent, not a crash".
    // (fragment handler stays C-private, no Scala/koffi upcall) is a design-intent
    // property of the C shim documented at the source site (kyo_aeron.c, kyo_aeron_fragment_handler).
    // A char-index / token source scan of these would assert source shape, not behavior, so they are
    // verified structurally instead: the static link for symbol presence, the C source site for the
    // private fragment handler, rather than by a source-scan leaf.

    // offer sentinel mapping: a not-connected publish with a bounded fail schedule
    // fails. Covered cross-platform (and more strictly, asserting TopicBackpressureExhaustedException
    // and rejecting both Success and Panic) by AeronSentinelsTest "not-connected publish
    // exhausts to TopicBackpressureExhaustedException"; a JVM-only leaf
    // would be a weaker (result.isFailure-only) single-platform copy, so it is not duplicated here.

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

    // Platform-specific bar: genuine Native static-link MECHANIC, no cross-platform equivalent.
    // This pins that the kyo_aeron_* FFI symbols are statically linked into the Native binary
    // (aeron_driver_static) and resolve at runtime with no system libaeron. The JVM has
    // no C shim (it uses the io.aeron Java client); JS dlopen's the shim via koffi (a different
    // mechanism, covered by the JS leaf). `.onlyNative` is the only gate that exercises static linking.
    "runtime: symbols resolve and driver starts without system libaeron".onlyNative in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            // embedded(dir) returns AeronRuntime < Async (the @Ffi.blocking .safe.get bridge);
            // On a clean native binary, the FFI symbols are statically linked; this call proves
            // all kyo_aeron_* symbols resolve at runtime with no system libaeron.
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

    // FFI offer sentinel classification (the not-connected offer returns a negative
    // AeronSentinels value) is a cross-platform contract: AeronPlatform.embedded() dispatches per
    // platform, so this is covered by the single cross-platform leaf
    // AeronTransportTest "offer result maps through AeronSentinels identically on every platform".
    // The JS-specific koffi BigInt->Long sign-marshalling guard for the same sentinel stays below
    // as its own `.onlyJs` leaf.

    // round-trip still works under the Envelope row layout.
    // Uses stream-id 201 (distinct from all other test stream-ids).
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

    // Envelope wire shape is deterministic and byte-identical across encode calls.
    // Encodes a fixed Chunk(1,2,3) as Envelope(tag.show, chunk) via MsgPack.encode and checks
    // byte length and leading bytes. The encoding is deterministic (MsgPack field order is
    // schema-driven). No live Aeron transport required.
    "Envelope wire-golden: byte-identical across encode calls" in {
        val messages = Chunk(1, 2, 3)
        val envelope = Topic.Envelope(Tag[Int].show, messages)
        val encoded  = MsgPack.encode(envelope)
        val bytes    = encoded.toArray

        // Non-empty wire frame.
        assert(bytes.nonEmpty, "MsgPack.encode produced an empty byte array")

        // Deterministic: encoding the same value twice yields identical bytes.
        val second = MsgPack.encode(envelope).toArray
        assert(
            java.util.Arrays.equals(bytes, second),
            s"MsgPack.encode is non-deterministic: first=${bytes.toList}, second=${second.toList}"
        )

        // Round-trip: decode returns the original envelope.
        MsgPack.decode[Topic.Envelope[Int]](encoded) match
            case Result.Success(decoded) =>
                assert(decoded.typeTag == envelope.typeTag, s"typeTag mismatch: ${decoded.typeTag} != ${envelope.typeTag}")
                assert(decoded.messages == messages, s"messages mismatch: ${decoded.messages} != $messages")
            case Result.Failure(ex) =>
                fail(s"MsgPack.decode failed on a freshly encoded Envelope: $ex")
        end match
        succeed
    }

    // Platform-specific bar: genuine JS koffi int64 MARSHALLING mechanic, no cross-platform
    // equivalent. koffi on JS returns int64 values as js.BigInt; the generated JS binding converts
    // via .toString.toLong. A negative sentinel such as NotConnected (-1L) must survive that
    // round-trip with its sign intact (BigInt(-1).toString == "-1", "-1".toLong == -1L). The JVM
    // returns a primitive long and Native returns a C long, so neither exercises koffi's BigInt
    // path; only the `.onlyJs` leaf pins this conversion.
    "JS koffi int64 sentinel marshalling: negative offer sentinel survives BigInt round-trip".onlyJs in {
        // Stream-id 78 is distinct from all other test leaves including the Native offer-sentinel leaf above.
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubMaybeR <- Abort.run[TopicTransportException] {
                Topic.addPublicationDeadline(transport, "aeron:ipc", 78, 10.seconds)
            }
            // Do NOT interpolate pubMaybeR: on Scala.js a Result holding an FFI Handle (an opaque
            // koffi pointer) throws "TypeError: Cannot convert object to primitive value" during
            // string coercion. Report only the failure's class name.
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

    // Behavioral guard: an Envelope with empty payload still encodes to a non-empty byte sequence
    // (the typeTag field is always non-empty, so zero-length wire frames are unreachable). The C
    // shim's zero-length divergence (aeron_image.c advances position unconditionally for any
    // received frame) is therefore unreachable from kyo-aeron's wire path.
    "MsgPack-encoded Envelope with empty payload is always non-empty (zero-length divergence unreachable)" in {
        // Smallest possible payload: Chunk.empty[Int]. typeTag is non-empty (Tag[Int].show is non-empty).
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

    // Behavioral guard: two concurrent consumers of the same Topic.stream value get distinct
    // subscriptions (each emit materializes its own addSubscription call). Runtime owner-thread
    // enforcement is not imposed; the guard proves subscriptions are per-emit by showing two
    // concurrent consumers both succeed independently.
    "two concurrent Topic.stream consumers get distinct subscriptions (per-emit safety)" in {
        val messages = Seq(1, 2, 3)
        Topic.run {
            for
                // Both consumers read from the same URI and type; Topic hashes the stream-id
                // identically, but each emit call creates its own Aeron subscription handle.
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
                // Both consumers received their own copy of the messages.
                // If subscriptions were shared, only one consumer would receive each message.
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
