package kyo

// Mirrors Aeron's FragmentedMessageTest, PubAndSubTest, MemoryOrderingTest, PongTest.
//
// Every leaf is cross-platform (JVM + JS + Native). The UDP round-trip leaf exercises the
// aeron:udp CONTRACT, which is transport-agnostic; it uses a FIXED high port (the suite runs
// SERIALLY, Test / parallelExecution := false, so a fixed high port is collision-safe within a run,
// and Topic cannot read back an Aeron-assigned ephemeral port). No java.net / DatagramSocket is
// used (those break the JS/Native LINK).

class TopicRoundTripTest extends Test:

    // ---------------------------------------------------------------------------
    // singleMultiFragmentMessageRoundTrip
    //
    // Mirrors Aeron's FragmentedMessageTest.shouldReceivePublishedMessage and
    // PubAndSubTest.shouldFragmentExactMessageLengthsCorrectly.
    //
    // Publishes a single message whose encoded Envelope spans multiple MTU-sized
    // Aeron fragments (IPC MTU ~1408 bytes). The subscriber must receive it
    // byte-identical after reassembly. Also publishes a second message sized to an
    // exact fragment boundary to confirm both boundary cases round-trip.
    // ---------------------------------------------------------------------------

    case class BigMsg(payload: String) derives CanEqual, Schema

    "a single multi-fragment message round-trips" in {
        // ~4000 chars -> encoded Envelope is well above the 1408-byte IPC MTU,
        // triggering multi-fragment reassembly, and well below default maxMessageLength
        // (~8 MiB on a 64 MiB term).
        val aboveFragBoundary = BigMsg("x" * 4000)
        // ~2816 chars = 2 * 1408: sized to an exact 2-fragment boundary.
        val exactBoundary = BigMsg("y" * 2816)
        val messages      = Seq(aboveFragBoundary, exactBoundary)
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[BigMsg]("aeron:ipc").take(messages.size).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[BigMsg]("aeron:ipc")(Stream.init(messages)))
                received <- fiber.get
            yield assert(received == messages)
        }
    }

    // ---------------------------------------------------------------------------
    // sustainedHighVolumeOrdering
    //
    // Mirrors Aeron's MemoryOrderingTest.shouldReceiveMessagesInOrderWithFirstLongWordIntact.
    //
    // One publisher sends N sequentially-numbered messages in multiple chunks; one
    // subscriber receives all N via stream.take(N); the test asserts no-loss (received
    // size == N) AND strict-order (msg.seq == idx) as SEPARATE assertions.
    //
    // Connection-readiness handshake (deterministic; no settle-sleep):
    //   started.await proves only that the subscriber FIBER started, not that the Aeron
    //   IPC subscription image is receiving. Aeron publish-before-connect
    //   semantics silently DROP offers made before the image connects, so beginning the
    //   bulk publish on started.await alone races the image registration and loses the
    //   leading messages (the subscriber then receives fewer than N).
    //
    //   The design is condition-driven AND single-publication. Both properties the test
    //   asserts are per-Aeron-publication guarantees: Aeron delivers FIFO and without loss
    //   only WITHIN one publication (session). Spreading the sequence over many Topic.publish
    //   calls opens a new publication/session per
    //   call, and the subscriber can interleave their images, reordering messages across batch
    //   boundaries even when nothing is lost (the subscriber then observes messages out of batch order). So the probes AND the real 0..n-1 sequence are published through ONE
    //   Topic.publish call, i.e. one publication, so order and no-loss hold by Aeron's own
    //   per-session contract.
    //
    //   Within that single publication, a leading probe phase emits sentinel Ordered(-1)
    //   values until the subscriber acknowledges its image is receiving: the subscriber
    //   releases the `receiving` latch via .tap on its FIRST received element (a probe,
    //   proving THIS publication's session image is connected) and drops the sentinels with
    //   filterPure(_.seq >= 0) before take(n). The probe Stream.unfold emits one sentinel per
    //   step, backing off with Async.sleep ONLY between steps (the bounded H4 retry pattern:
    //   it polls the ack CONDITION `receiving.pending == 0` and completes the INSTANT it is
    //   satisfied, never a fixed settle-sleep), then the real sequence flows on the same
    //   already-connected publication. Topic.publish retries Aeron back-pressure (-2)
    //   internally per defaultRetrySchedule, so no separate backpressure budget is needed.
    // ---------------------------------------------------------------------------

    case class Ordered(seq: Long) derives CanEqual, Schema

    "sustained high-volume publishing preserves message order" in {
        val n            = 1000
        val uri          = "aeron:ipc?term-length=4194304"
        val probe        = Ordered(-1L)
        val probeBackoff = 5.millis
        val maxProbes    = 2000 // bounded: ~10 s ceiling at 5 ms; the probe stream stops if the image never connects
        Topic.run {
            for
                started   <- Latch.init(1)
                receiving <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(
                        Topic.stream[Ordered](uri)
                            .tap(_ => receiving.release)
                            .filterPure(_.seq >= 0)
                            .take(n)
                            .run
                    )
                )
                _ <- started.await
                _ <- Fiber.initUnscoped {
                    // Probe phase: one sentinel per unfold step (chunkSize 1 => one offer each),
                    // stopping the instant the subscriber acks (receiving.pending == 0) or the cap
                    // is hit. The backoff sits between probe steps only (it polls the ack condition).
                    val probes =
                        Stream.unfold(0, chunkSize = 1) { step =>
                            receiving.pending.map { stillWaiting =>
                                if stillWaiting == 0 || step >= maxProbes then Absent: Maybe[(Ordered, Int)]
                                else Async.sleep(probeBackoff).andThen(Present((probe, step + 1)): Maybe[(Ordered, Int)])
                            }
                        }
                    val real = Stream.init(Seq.tabulate(n)(i => Ordered(i.toLong)))
                    // ONE Topic.publish => ONE Aeron publication => per-session FIFO + no-loss for
                    // both the probes and the concatenated real sequence.
                    Topic.publish[Ordered](uri)(probes.concat(real))
                }
                received <- fiber.get
            yield
                // No-loss: the single-publication handshake guarantees every real message is delivered.
                assert(
                    received.size == n,
                    s"message loss: expected $n messages, received ${received.size} (the single-publication readiness handshake should prevent drops)"
                )
                // Ordering: the received sequence is strictly 0 until n with no gaps or duplicates.
                assert(
                    received.zipWithIndex.forall { case (msg, idx) => msg.seq == idx.toLong },
                    s"ordering violated: head=${received.take(10).map(_.seq)} tail=${received.takeRight(10).map(_.seq)}"
                )
        }
    }

    // ---------------------------------------------------------------------------
    // termBufferRolloverContinuity
    //
    // Mirrors Aeron's PubAndSubTest.shouldContinueAfterBufferRollover,
    // shouldContinueAfterBufferRolloverBatched, shouldContinueAfterBufferRolloverWithPadding,
    // shouldContinueAfterRolloverWithMinimalPaddingHeader.
    //
    // Uses aeron:ipc?term-length=65536 (64 KB term; maxMessageLength = 8192).
    // Each message is published in its own Envelope via rechunk(1) so the encoded size
    // stays well under maxMessageLength. With a ~200-byte payload each Envelope is
    // roughly 260 bytes on the wire (32-byte Aeron frame header + MsgPack overhead +
    // payload). A 64 KB term holds ~250 such frames; 300 messages crosses the boundary.
    // Asserts count and order are preserved across the rollover (the ADMIN_ACTION
    // retryable path fires during term rotation and is transparently retried).
    //
    // Sub-variant (padding-edge): a second batch uses a payload sized so the Envelope
    // length is close to a 32-byte frame-alignment boundary, exercising the pad-frame
    // path at term end (Aeron pads to the next 32-byte boundary before rolling over).
    // A 184-byte payload produces an Envelope that is not a multiple of 32 bytes,
    // leaving a short pad header at the term boundary.
    // ---------------------------------------------------------------------------

    case class RollMsg(idx: Int, pad: String) derives CanEqual, Schema

    "publishing continues across a term-buffer rollover" in {
        val rollUri = "aeron:ipc?term-length=65536"
        // Primary batch: 200-byte payload; each single-message Envelope is ~260 bytes.
        // 300 messages guarantees crossing at least one 64 KB term boundary.
        val msgs = Seq.tabulate(300)(i => RollMsg(i, "a" * 200))
        // Sub-variant (padding-edge): 184-byte payload. Envelope length is not a
        // multiple of 32, so Aeron writes a pad frame at the term end before rolling.
        val msgsPad = Seq.tabulate(50)(i => RollMsg(i + 300, "b" * 184))
        val allMsgs = msgs ++ msgsPad
        val total   = allMsgs.size
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[RollMsg](rollUri).take(total).run)
                )
                _ <- started.await
                // rechunk(1): each message published in its own Envelope so the encoded
                // size stays under maxMessageLength=8192 for term-length=65536.
                _ <- Fiber.initUnscoped(
                    Topic.publish[RollMsg](rollUri)(Stream.init(allMsgs).rechunk(1))
                )
                received <- fiber.get
            yield
                assert(received.size == total, s"expected $total messages across rollover, got ${received.size}")
                assert(received == allMsgs, s"message mismatch across term rollover")
        }
    }

    // ---------------------------------------------------------------------------
    // pingPongEchoAcrossTwoStreams
    //
    // Mirrors Aeron's PongTest.playPingPong.
    //
    // Two distinct message types (distinct stream-ids via Tag.hash). An echo fiber
    // receives one Ping and publishes one Pong with the same value. The initiator
    // fiber publishes the Ping and then receives the Pong. Latch(2) ensures both
    // the echo subscriber (stream[Ping]) and the initiator subscriber (stream[Pong])
    // are registered before the first Ping is published, making the flow deterministic.
    // ---------------------------------------------------------------------------

    case class Ping(value: Int) derives CanEqual, Schema
    case class Pong(value: Int) derives CanEqual, Schema

    "ping-pong echoes across two streams" in {
        val pingValue = 42
        Topic.run {
            for
                started <- Latch.init(2)
                // Echo fiber: receive one Ping, publish one Pong.
                echoFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(
                        for
                            pings <- Topic.stream[Ping]("aeron:ipc").take(1).run
                            _     <- Topic.publish[Pong]("aeron:ipc")(Stream.init(pings.map(p => Pong(p.value))))
                        yield ()
                    )
                )
                // Initiator fiber: subscribe to Pong before publishing Ping.
                pongFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Pong]("aeron:ipc").take(1).run)
                )
                // Both subscribers registered; now publish the Ping.
                _     <- started.await
                _     <- Fiber.initUnscoped(Topic.publish[Ping]("aeron:ipc")(Stream.init(Seq(Ping(pingValue)))))
                pongs <- pongFiber.get
                _     <- echoFiber.get
            yield
                assert(pongs.size == 1, s"expected 1 Pong, got ${pongs.size}")
                assert(pongs.head == Pong(pingValue), s"expected Pong($pingValue), got ${pongs.head}")
        }
    }

    // ---------------------------------------------------------------------------
    // UDP single-message round-trip.
    //
    // The aeron:udp contract is transport-agnostic, so this leaf is cross-platform: a publisher and
    // a subscriber on the SAME UDP endpoint round-trip a small batch byte-identical. It uses a FIXED
    // high port (the suite runs serially, so a fixed port is collision-safe; Topic cannot read back
    // an Aeron-assigned ephemeral port) and no java.net API (which would break the JS/Native link).
    // First UDP round-trip on Native/JS: a C-driver/UDP divergence would surface here.
    // ---------------------------------------------------------------------------

    case class UdpMsg(value: Int) derives CanEqual, Schema

    "a single message round-trips over UDP" in {
        val uri      = "aeron:udp?endpoint=127.0.0.1:24519"
        val messages = Seq(UdpMsg(1), UdpMsg(2), UdpMsg(3))
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[UdpMsg](uri).take(messages.size).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[UdpMsg](uri)(Stream.init(messages)))
                received <- fiber.get
            yield assert(received == messages, s"udp round-trip mismatch: expected $messages, got $received")
        }
    }

end TopicRoundTripTest
