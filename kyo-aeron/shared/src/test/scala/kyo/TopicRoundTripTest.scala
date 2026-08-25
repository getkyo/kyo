package kyo

// Mirrors Aeron's FragmentedMessageTest, PubAndSubTest, MemoryOrderingTest, PongTest. The UDP round-trip
// leaf uses a fixed high port: the suite runs serially (parallelExecution := false), so a fixed port is
// collision-safe and Topic cannot read back an Aeron-assigned ephemeral one. Nothing here may reach for
// java.net (DatagramSocket and friends break the JS and Native link).

class TopicRoundTripTest extends Test:

    // Mirrors Aeron's FragmentedMessageTest.shouldReceivePublishedMessage and
    // PubAndSubTest.shouldFragmentExactMessageLengthsCorrectly.

    case class BigMsg(payload: String) derives CanEqual, Schema

    "a single multi-fragment message round-trips" in {
        // ~4000 chars: well above the 1408-byte IPC MTU (multi-fragment reassembly) and well below
        // maxMessageLength (~8 MiB on a 64 MiB term).
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

    // Mirrors Aeron's MemoryOrderingTest.shouldReceiveMessagesInOrderWithFirstLongWordIntact. Publish-
    // before-connect silently drops offers, and started.await only proves the subscriber fiber started, not
    // that its image is receiving, so a leading probe phase emits sentinel Ordered(-1) values until the
    // subscriber's first received element signals readiness. FIFO and no-loss hold only within one
    // publication, so the probes and the real sequence share one Topic.publish call rather than risking
    // reorder across several.

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
                    // chunkSize 1 gives one offer per probe step.
                    val probes =
                        Stream.unfold(0, chunkSize = 1) { step =>
                            receiving.pending.map { stillWaiting =>
                                if stillWaiting == 0 || step >= maxProbes then Absent: Maybe[(Ordered, Int)]
                                else Async.sleep(probeBackoff).andThen(Present((probe, step + 1)): Maybe[(Ordered, Int)])
                            }
                        }
                    val real = Stream.init(Seq.tabulate(n)(i => Ordered(i.toLong)))
                    Topic.publish[Ordered](uri)(probes.concat(real))
                }
                received <- fiber.get
            yield
                assert(
                    received.size == n,
                    s"message loss: expected $n messages, received ${received.size} (the single-publication readiness handshake should prevent drops)"
                )
                assert(
                    received.zipWithIndex.forall { case (msg, idx) => msg.seq == idx.toLong },
                    s"ordering violated: head=${received.take(10).map(_.seq)} tail=${received.takeRight(10).map(_.seq)}"
                )
        }
    }

    // Mirrors Aeron's PubAndSubTest.shouldContinueAfterBufferRollover: count and order must survive the
    // rollover, whose retryable ADMIN_ACTION path is retried transparently. term-length=65536
    // (maxMessageLength 8192) with rechunk(1) puts 300 ~260-byte Envelopes over a 64 KB term; the 184-byte
    // padding batch forces a short pad frame by giving an Envelope length not a multiple of 32.

    case class RollMsg(idx: Int, pad: String) derives CanEqual, Schema

    "publishing continues across a term-buffer rollover" in {
        val rollUri = "aeron:ipc?term-length=65536"
        val msgs    = Seq.tabulate(300)(i => RollMsg(i, "a" * 200))
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
                _ <- Fiber.initUnscoped(
                    Topic.publish[RollMsg](rollUri)(Stream.init(allMsgs).rechunk(1))
                )
                received <- fiber.get
            yield
                assert(received.size == total, s"expected $total messages across rollover, got ${received.size}")
                assert(received == allMsgs, s"message mismatch across term rollover")
        }
    }

    // Mirrors Aeron's PongTest.playPingPong. Latch(2) registers both the echo subscriber (stream[Ping])
    // and the initiator subscriber (stream[Pong]) before the first Ping, making the flow deterministic.

    case class Ping(value: Int) derives CanEqual, Schema
    case class Pong(value: Int) derives CanEqual, Schema

    "ping-pong echoes across two streams" in {
        val pingValue = 42
        Topic.run {
            for
                started <- Latch.init(2)
                echoFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(
                        for
                            pings <- Topic.stream[Ping]("aeron:ipc").take(1).run
                            _     <- Topic.publish[Pong]("aeron:ipc")(Stream.init(pings.map(p => Pong(p.value))))
                        yield ()
                    )
                )
                pongFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Pong]("aeron:ipc").take(1).run)
                )
                _     <- started.await
                _     <- Fiber.initUnscoped(Topic.publish[Ping]("aeron:ipc")(Stream.init(Seq(Ping(pingValue)))))
                pongs <- pongFiber.get
                _     <- echoFiber.get
            yield
                assert(pongs.size == 1, s"expected 1 Pong, got ${pongs.size}")
                assert(pongs.head == Pong(pingValue), s"expected Pong($pingValue), got ${pongs.head}")
        }
    }

    // The first UDP round-trip on Native/JS: a C-driver/UDP divergence would surface here.

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
