package kyo

class TopicTest extends Test:

    case class Message(value: Int) derives CanEqual, Schema
    case class GenericMessage[A](value: A) derives CanEqual, Schema
    case class ComplexMessage(id: Int, items: List[String]) derives CanEqual, Schema

    val failSchedule = Schedule.fixed(5.millis).take(5)

    val uri = "aeron:ipc"

    "basic publish/subscribe" - {
        "single message type" in {
            val messages = Seq(Message(1), Message(2), Message(3))
            Topic.run {
                for
                    started <- Latch.init(1)
                    fiber <-
                        Fiber.initUnscoped(using Topic.isolate)(
                            started.release.andThen(Topic.stream[Message](uri).take(messages.size).run)
                        )
                    _        <- started.await
                    _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                    received <- fiber.get
                yield assert(received == messages)
            }
        }

        "multiple message types" in {
            val strings = Seq("hello", "world")
            val ints    = Seq(42, 43)
            Topic.run {
                for
                    started       <- Latch.init(2)
                    stringFiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[String](uri).take(strings.size).run))
                    intFiber      <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Int](uri).take(ints.size).run))
                    _             <- started.await
                    _             <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(strings)))
                    _             <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(ints)))
                    stringResults <- stringFiber.get
                    intResults    <- intFiber.get
                yield
                    assert(stringResults == strings)
                    assert(intResults == ints)
            }
        }

        "generic message types" in {
            val strMessages = Seq(GenericMessage("hello"), GenericMessage("world"))
            val intMessages = Seq(GenericMessage(1), GenericMessage(2))

            Topic.run {
                for
                    started <- Latch.init(2)
                    strFiber <-
                        Fiber.initUnscoped(
                            started.release.andThen(Topic.stream[GenericMessage[String]](uri).take(strMessages.size).run)
                        )
                    intFiber <-
                        Fiber.initUnscoped(
                            started.release.andThen(Topic.stream[GenericMessage[Int]](uri).take(intMessages.size).run)
                        )
                    _          <- started.await
                    _          <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(strMessages)))
                    _          <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(intMessages)))
                    strResults <- strFiber.get
                    intResults <- intFiber.get
                yield
                    assert(strResults == strMessages)
                    assert(intResults == intMessages)
            }
        }

        "complex message types" in {
            val messages = Seq(
                ComplexMessage(1, List("a", "b")),
                ComplexMessage(2, List("c", "d"))
            )
            Topic.run {
                for
                    started  <- Latch.init(1)
                    fiber    <- Fiber.initUnscoped(started.release.andThen(Topic.stream[ComplexMessage](uri).take(messages.size).run))
                    _        <- started.await
                    _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                    received <- fiber.get
                yield assert(received == messages)
            }
        }
    }

    "multiple subscribers" - {
        "fan-out to multiple subscribers" in {
            val messages = Seq(Message(1), Message(2), Message(3))

            Topic.run {
                for
                    started <- Latch.init(2)
                    fiber1  <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri).take(messages.size).run))
                    fiber2  <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri).take(messages.size).run))
                    _       <- started.await
                    _       <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                    result1 <- fiber1.get
                    result2 <- fiber2.get
                yield
                    assert(result1 == messages)
                    assert(result2 == messages)
            }
        }

        "subscribers with different consumption rates" in {
            val messages = Seq(Message(1), Message(2))

            Topic.run {
                for
                    started <- Latch.init(2)
                    slowFiber <-
                        Fiber.initUnscoped(started.release.andThen(
                            Topic.stream[Message](uri)
                                .map(r => Async.delay(1.millis)(r))
                                .take(messages.size)
                                .run
                        ))
                    fastFiber <-
                        Fiber.initUnscoped(started.release.andThen(
                            Topic.stream[Message](uri)
                                .take(messages.size)
                                .run
                        ))
                    _    <- started.await
                    _    <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                    slow <- slowFiber.get
                    fast <- fastFiber.get
                yield
                    assert(slow == messages)
                    assert(fast == messages)
            }
        }
    }

    "subtyping" - {
        sealed trait Base derives Schema
        case class Derived1(value: Int)    extends Base derives CanEqual, Schema
        case class Derived2(value: String) extends Base derives CanEqual, Schema

        "base type cannot receive subtypes" in {
            val messages = Seq(Derived1(1), Derived1(2))

            Topic.run {
                for
                    started <- Latch.init(1)
                    fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Base](uri, failSchedule).run))
                    _       <- started.await
                    result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                    result2 <- fiber.getResult
                yield assert(result1.isFailure && result2.isFailure)
            }
        }

        "subtype cannot receive base type" in {
            val messages = Seq[Base](Derived1(1), Derived1(2))

            Topic.run {
                for
                    started <- Latch.init(1)
                    fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived1](uri, failSchedule).run))
                    _       <- started.await
                    result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                    result2 <- fiber.getResult
                yield assert(result1.isFailure && result2.isFailure)
            }
        }

        "different subtypes maintain separation" in {
            val messages1 = Seq(Derived1(1), Derived1(2))
            val messages2 = Seq(Derived2("a"), Derived2("b"))

            Topic.run {
                for
                    started  <- Latch.init(2)
                    fiber1   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived1](uri).take(messages1.size).run))
                    fiber2   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived2](uri).take(messages2.size).run))
                    _        <- started.await
                    _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages1)))
                    _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages2)))
                    results1 <- fiber1.get
                    results2 <- fiber2.get
                yield
                    assert(results1 == messages1)
                    assert(results2 == messages2)
            }
        }
    }

    "maintains message order for large batches" in {
        val count    = 200
        val messages = Seq.tabulate(count)(Message(_))
        Topic.run {
            for
                started  <- Latch.init(1)
                fiber    <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri).take(count).run))
                _        <- started.await
                _        <- Topic.publish[Message](uri)(Stream.init(messages))
                received <- fiber.get
            yield assert(received == messages)
        }
    }

    "handles empty streams" in {
        Topic.run {
            for
                started <- Latch.init(1)
                fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri, failSchedule).take(1).run))
                _       <- started.await
                _       <- Topic.publish[Message](uri, failSchedule)(Stream.empty)
                result  <- fiber.getResult
            yield assert(result.isFailure)
        }
    }

    "partial subscriber failure" in {
        val messageCount = 10
        val messages     = (0 until messageCount).map(Message(_))

        Topic.run {
            for
                started <- Latch.init(2)
                failingFiber <- Fiber.initUnscoped(
                    started.release.andThen(
                        Topic.stream[Message](uri)
                            .map(_ => Abort.fail("Planned failure"): Message < Abort[String])
                            .take(messageCount)
                            .run
                    )
                )
                normalFiber <- Fiber.initUnscoped(
                    started.release.andThen(
                        Topic.stream[Message](uri)
                            .take(messageCount)
                            .run
                    )
                )
                _             <- started.await
                _             <- Topic.publish(uri)(Stream.init(messages))
                failingResult <- failingFiber.getResult
                normalResult  <- normalFiber.get
            yield
                assert(failingResult.isFailure)
                assert(normalResult == messages)
        }
    }

    // ---------------------------------------------------------------------------
    // Oversize publish: Topic.publish with an oversize payload aborts
    // identically on every platform with TopicMessageTooLargeException.
    // ---------------------------------------------------------------------------

    // Publishing a message whose encoded Envelope exceeds
    // maxMessageLength on aeron:ipc?term-length=65536 (maxMessageLength=8192) aborts
    // with TopicMessageTooLargeException on ALL platforms identically.
    //
    // This leaf asserts the uniform TopicMessageTooLargeException outcome on every platform: all three
    // yield Result.Failure(err: TopicMessageTooLargeException) with err.messageSize > err.maxMessageLength
    // and err.maxMessageLength == 8192. Without the uniform -6 mapping the platforms would diverge
    // (JVM: an IllegalArgumentException escaping Sync.Unsafe.defer as a panic; FFI:
    // Result.Failure(TopicPublicationClosedException), the wrong type from -6 mapped to Closed).
    //
    // Anti-flakiness: a subscriber is started so publicationIsConnected returns true, allowing
    // the publish body to reach the encoded-bytes check before calling offer. The subscriber
    // fiber is unscoped so it does not block the publish from proceeding. The failSchedule
    // (5 retries at 5ms) is used; the up-front check is terminal and fires on the
    // first iteration. The payload is a String of 9000 chars; the MsgPack Envelope adds
    // ~50-100 bytes overhead, giving bytes.length well above 8192.
    "publish an oversize message aborts with TopicMessageTooLargeException" in {
        val oversizeUri = "aeron:ipc?term-length=65536"
        val payload     = "x" * 9000
        Topic.run {
            for
                // Start a subscriber so publicationIsConnected can become true.
                subFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    Topic.stream[String](oversizeUri, failSchedule).take(1).run
                )
                result <- Abort.run[TopicException] {
                    Topic.publish[String](oversizeUri, failSchedule)(Stream.init(Seq(payload)))
                }
                _ <- subFiber.interrupt
            yield result match
                case Result.Failure(err: TopicMessageTooLargeException) =>
                    assert(
                        err.messageSize > err.maxMessageLength,
                        s"messageSize=${err.messageSize} should exceed maxMessageLength=${err.maxMessageLength}"
                    )
                    assert(
                        err.maxMessageLength == 8192,
                        s"Expected maxMessageLength=8192 for term-length=65536; got ${err.maxMessageLength}"
                    )
                case Result.Failure(other) =>
                    fail(s"Expected TopicMessageTooLargeException but got ${other.getClass.getSimpleName}: $other")
                case Result.Panic(t) =>
                    fail(s"Expected TopicMessageTooLargeException but got panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
                case Result.Success(_) =>
                    fail("Expected TopicMessageTooLargeException but publish succeeded")
        }
    }

    "isolation" - {
        "publisher without subscribers" in {
            Topic.run {
                for
                    result <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(Seq(Message(1)))))
                yield assert(result.isFailure)
            }
        }

        "subscriber without publisher" in {
            Topic.run {
                for
                    fiber  <- Fiber.initUnscoped(Topic.stream[Message](uri, failSchedule).take(1).run)
                    result <- fiber.getResult
                yield assert(result.isFailure)
            }
        }

        "subscriber starts after publisher" in {
            Topic.run {
                for
                    result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(Seq(Message(1)))))
                    result2 <- Abort.run(Topic.stream[Message](uri, failSchedule).take(1).run)
                yield
                    assert(result1.isFailure)
                    assert(result2.isFailure)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Registration-error leaves (malformed-URI publish/stream, closed-vs-registration, detail-normalization):
    // Surface a registration error as terminal TopicRegistrationFailedException.
    // ---------------------------------------------------------------------------

    // The malformed URI "aeron:bogus" uses a protocol scheme that neither the Aeron
    // C client (aeron_uri_parse) nor the Java client (AeronUri) recognises. The
    // driver rejects it at registration time, producing a RegistrationException (JVM)
    // or aeron_errcode != 0 (FFI). "aeron:ipc?term-length=3" is a valid IPC URI but
    // with a below-minimum term length that some driver versions clamp; "aeron:bogus"
    // is rejected at URI-parse time and is driver-version independent.
    val badUri = "aeron:bogus"

    // (all platforms): publishing to a malformed URI must abort with
    // TopicRegistrationFailedException (terminal), NOT with TopicPublicationClosedException.
    //
    // The Failed arm in addPublicationDeadline must surface TopicRegistrationFailedException: were it to
    // return Maybe.empty (Absent), the publish caller would map it to Abort.fail(TopicPublicationClosedException),
    // the wrong error type (a registration rejection is not a closed-client event). This leaf guards it.
    "malformed-URI publish aborts with TopicRegistrationFailedException" in {
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[Message](badUri, failSchedule)(Stream.init(Seq(Message(1))))
            }.map { result =>
                result match
                    case Result.Failure(reg: TopicRegistrationFailedException) =>
                        assert(reg.aeronUri == badUri, s"aeronUri mismatch: ${reg.aeronUri}")
                        assert(
                            reg.detail.nonEmpty,
                            s"detail must be non-empty (or '(no driver detail)'); got: '${reg.detail}'"
                        )
                    case Result.Failure(other) =>
                        fail(
                            s"Expected TopicRegistrationFailedException but got ${other.getClass.getSimpleName}: $other"
                        )
                    case Result.Panic(t) =>
                        fail(
                            s"Expected TopicRegistrationFailedException but got panic: ${t.getClass.getSimpleName}: ${t.getMessage}"
                        )
                    case Result.Success(_) =>
                        fail("Expected TopicRegistrationFailedException but publish succeeded")
            }
        }
    }

    // (all platforms): streaming from a malformed URI must abort terminally
    // with TopicRegistrationFailedException, NOT with TopicBackpressureExhaustedException after retries.
    //
    // The Failed arm in addSubscriptionDeadline aborts terminally with TopicRegistrationFailedException
    // before the Retry schedule is consulted, so even 1 attempt suffices. The bounded failSchedule
    // (Schedule.fixed(1.millis).take(3)) is a safety net: were a registration failure routed back
    // through the retry path (returning Absent, which the stream caller maps to
    // Abort.fail(TopicBackpressureExhaustedException)), the bounded schedule turns that infinite-retry
    // regression into an observable wrong-type assertion instead of a hang.
    "malformed-URI stream aborts terminally with TopicRegistrationFailedException" in {
        val boundedSchedule = Schedule.fixed(1.millis).take(3)
        Topic.run {
            Abort.run[TopicException] {
                Topic.stream[Message](badUri, boundedSchedule).take(1).run
            }.map { result =>
                result match
                    case Result.Failure(_: TopicRegistrationFailedException) => succeed
                    case Result.Failure(other) =>
                        fail(
                            s"Expected TopicRegistrationFailedException but got ${other.getClass.getSimpleName}: $other"
                        )
                    case Result.Panic(t) =>
                        fail(
                            s"Expected TopicRegistrationFailedException but got panic: ${t.getClass.getSimpleName}: ${t.getMessage}"
                        )
                    case Result.Success(_) =>
                        fail("Expected TopicRegistrationFailedException but stream succeeded")
            }
        }
    }

    // closed-vs-registration (all platforms): a malformed URI produces a terminal
    // TopicRegistrationFailedException (driver rejects the URI), while a valid URI with no subscriber
    // produces a transient not-connected failure the retry schedule exhausts into
    // TopicBackpressureExhaustedException, NOT a registration failure. This leaf asserts BOTH arms so
    // the two error paths stay provably independent within one run.
    "closed-vs-registration (all platforms): registration failure is distinct from closed-client" in {
        Topic.run {
            for
                // malformed URI: terminal registration error (driver rejects the URI)
                regR <- Abort.run[TopicException] {
                    Topic.publish[Message](badUri, failSchedule)(Stream.init(Seq(Message(1))))
                }
                _ = regR match
                    case Result.Failure(_: TopicRegistrationFailedException) => succeed
                    case other => fail(s"Registration path: expected TopicRegistrationFailedException, got: $other")
                // valid IPC URI with no subscriber: not-connected, retried per failSchedule, then
                // exhausted; must be a NON-registration failure.
                ncR <- Abort.run[TopicException] {
                    Topic.publish[Message]("aeron:ipc", failSchedule)(Stream.init(Seq(Message(1))))
                }
            yield ncR match
                case Result.Failure(_: TopicRegistrationFailedException) =>
                    fail(s"valid IPC URI without subscriber must NOT produce TopicRegistrationFailedException; got $ncR")
                case Result.Failure(_) => succeed
                case Result.Success(_) => fail("valid IPC URI without subscriber should not succeed with no subscriber")
        }
    }

    // detail-normalization (all platforms): TopicRegistrationFailedException carries the
    // driver detail in a normalized form. The aeronUri and streamId fields match what
    // was passed to publish. The detail is non-empty (or the normalization sentinel
    // "(no driver detail)" if the driver gave none). The errorCode is non-zero where
    // the driver supplies one (JVM RegistrationException.errorCodeValue() != 0).
    //
    // NOTE: errorCode may be 0 on some FFI paths if the driver does not set it for URI
    // parse failures; the test therefore does NOT assert errorCode != 0 universally.
    // Instead it asserts detail.nonEmpty (the normalization sentinel counts as non-empty).
    "detail-normalization (all platforms): TopicRegistrationFailedException fields are populated" in {
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[Message](badUri, failSchedule)(Stream.init(Seq(Message(1))))
            }.map { result =>
                result match
                    case Result.Failure(reg: TopicRegistrationFailedException) =>
                        assert(reg.aeronUri == badUri, s"aeronUri: expected '$badUri', got '${reg.aeronUri}'")
                        assert(
                            reg.streamId == summon[Tag[Message]].hash.abs,
                            s"streamId: expected ${summon[Tag[Message]].hash.abs}, got ${reg.streamId}"
                        )
                        assert(
                            reg.detail.nonEmpty,
                            s"detail must be non-empty or '(no driver detail)'; got: '${reg.detail}'"
                        )
                    case other =>
                        fail(s"Expected TopicRegistrationFailedException but got: $other")
            }
        }
    }

    // parity loops: cross-platform assertions that the SAME TopicException type/value
    // is produced on JVM, Native, and JS for each failure mode.

    // oversize publish yields TopicMessageTooLargeException on every platform.
    // Mirrors the oversize-publish setup: starts a subscriber so publicationIsConnected becomes true,
    // allowing the up-front oversize check to fire.
    "parity: oversize publish yields TopicMessageTooLargeException identically on every platform" in {
        // term-length=65536 makes maxMessageLength = 8192 (1/8 of the term).
        // A 9000-char String Envelope (with MsgPack overhead) exceeds 8192 and produces TopicMessageTooLargeException.
        val oversizeUri   = "aeron:ipc?term-length=65536"
        val oversizeBytes = "x" * 9000
        Topic.run {
            for
                // Start a subscriber so publicationIsConnected can become true (mirrors the oversize-publish anti-flakiness).
                subFiber <- Fiber.initUnscoped(using Topic.isolate)(
                    Topic.stream[String](oversizeUri, failSchedule).take(1).run
                )
                result <- Abort.run[TopicException] {
                    Topic.publish[String](oversizeUri, failSchedule)(Stream.init(Seq(oversizeBytes)))
                }
                _ <- subFiber.interrupt
            yield result match
                case Result.Failure(_: TopicMessageTooLargeException) => succeed
                case Result.Failure(other) =>
                    fail(s"oversize: expected TopicMessageTooLargeException but got ${other.getClass.getSimpleName}: $other")
                case Result.Panic(t) =>
                    fail(s"oversize: expected TopicMessageTooLargeException but got panic: ${t.getClass.getSimpleName}")
                case Result.Success(_) =>
                    fail("oversize: expected TopicMessageTooLargeException but publish succeeded")
        }
    }

    // malformed URI yields terminal TopicRegistrationFailedException on every platform.
    // Uses Message type to pin the same code path as the malformed-URI stream leaf; a bounded schedule is the
    // safety net against an infinite-retry regression.
    "parity: malformed-URI stream yields terminal TopicRegistrationFailedException identically on every platform" in {
        // Uses the file-level badUri = "aeron:bogus": an unknown protocol scheme the driver rejects.
        val boundedSchedule2 = Schedule.fixed(1.millis).take(3)
        Topic.run {
            Abort.run[TopicException](
                Topic.stream[Message](badUri, boundedSchedule2).take(1).run
            ).map {
                case Result.Failure(_: TopicRegistrationFailedException) => succeed
                case Result.Failure(other) =>
                    fail(
                        s"registration: expected terminal TopicRegistrationFailedException; " +
                            s"got ${other.getClass.getSimpleName}: $other (registration error did not surface as terminal)"
                    )
                case other => fail(s"registration: expected TopicRegistrationFailedException but got: $other")
            }
        }
    }

    // forked-then-closed loop is crash-free on every platform.
    // The UAF guard makes the close-vs-deref safe on
    // Native/JS; JVM is the memory-safe parity control. 50 iterations.
    "parity: forked-then-closed loop is crash-free identically on every platform" in {
        val iterations = 50
        val msgs       = Seq(1, 2, 3)
        Loop.indexed { i =>
            if i >= iterations then Loop.done(succeed)
            else
                Topic.run {
                    for
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.stream[Int](uri).take(msgs.size).run
                        )
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.publish[Int](uri)(Stream.init(msgs))
                        )
                        _ <- Async.sleep(1.millis)
                    yield ()
                }.andThen(Loop.continue)
        }
    }

end TopicTest
