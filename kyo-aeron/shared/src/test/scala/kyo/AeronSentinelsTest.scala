package kyo

import kyo.internal.AeronSentinels

class AeronSentinelsTest extends Test:

    "sentinel values match Aeron 1.50.2" - {
        "NotConnected == -1L" in {
            assert(AeronSentinels.NotConnected == -1L)
        }
        "BackPressured == -2L" in {
            assert(AeronSentinels.BackPressured == -2L)
        }
        "AdminAction == -3L" in {
            assert(AeronSentinels.AdminAction == -3L)
        }
        "Closed == -4L" in {
            assert(AeronSentinels.Closed == -4L)
        }
        "MaxPositionExceeded == -5L" in {
            assert(AeronSentinels.MaxPositionExceeded == -5L)
        }
        "Error == -6L" in {
            assert(AeronSentinels.Error == -6L)
        }
    }

    // mapOfferResult maps the wired sentinels.
    // Purely synthetic: no live Aeron driver required.
    // The transientSignal is Abort.fail(TopicBackpressureExhaustedException(...)) so -2/-1/-3 route through it.
    // Final wiring: > 0 -> (); -2/-1/-3 -> transientSignal (TopicBackpressureException); -4 -> TopicPublicationClosedException;
    // -5 -> TopicMaxPositionExceededException; -6 -> TopicMessageTooLargeException.
    "mapOfferResult maps the wired sentinels" - {
        val uri      = "aeron:ipc"
        val streamId = 7
        // Representative messageSize/maxLen for the -6 arm assertion.
        val msgSize = 8200
        val maxLen  = 8192
        val transientSignal: Unit < (Async & Abort[TopicBackpressureException]) =
            Abort.fail(TopicBackpressureExhaustedException(uri, streamId))

        "positive result -> success" in {
            val result = Abort.run[TopicException](Topic.mapOfferResult(1L, uri, streamId, transientSignal, msgSize, maxLen))
            result.map { r =>
                assert(r.isSuccess, s"Expected Success for result=1L but got $r")
            }
        }

        "BackPressured (-2) -> TopicBackpressureExhaustedException (transient signal)" in {
            val result = Abort.run[TopicException](Topic.mapOfferResult(
                AeronSentinels.BackPressured,
                uri,
                streamId,
                transientSignal,
                msgSize,
                maxLen
            ))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for BackPressured but got $r")
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureExhaustedException],
                            s"Expected TopicBackpressureExhaustedException but got ${e.getClass.getName}"
                        )
                    case _ => fail("Expected Failure")
                end match
            }
        }

        // NotConnected (-1) must route to the retryable TopicBackpressureException subcategory,
        // matching the not-connected pre-check in publish. -1 routes to transientSignal so that
        // both the pre-check path and the offer-sentinel path are consistent.
        "NotConnected (-1) -> TopicBackpressureException" in {
            val result = Abort.run[TopicException](Topic.mapOfferResult(
                AeronSentinels.NotConnected,
                uri,
                streamId,
                transientSignal,
                msgSize,
                maxLen
            ))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for NotConnected but got $r")
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureException],
                            s"Expected TopicBackpressureException but got ${e.getClass.getName}"
                        )
                    case _ => fail("Expected Failure")
                end match
            }
        }

        // AdminAction (-3) must route to the retryable TopicBackpressureException subcategory.
        // Aeron documents ADMIN_ACTION as "should be retried" (log-rotation / term-count race).
        // -3 routes to transientSignal so high-throughput log-rotation does not permanently
        // fail a publish that would succeed on the next attempt.
        "AdminAction (-3) -> TopicBackpressureException" in {
            val result =
                Abort.run[TopicException](Topic.mapOfferResult(AeronSentinels.AdminAction, uri, streamId, transientSignal, msgSize, maxLen))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for AdminAction but got $r")
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureException],
                            s"Expected TopicBackpressureException but got ${e.getClass.getName}"
                        )
                    case _ => fail("Expected Failure")
                end match
            }
        }

        "Closed (-4) -> TopicPublicationClosedException (terminal)" in {
            val result =
                Abort.run[TopicException](Topic.mapOfferResult(AeronSentinels.Closed, uri, streamId, transientSignal, msgSize, maxLen))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for Closed but got $r")
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicPublicationClosedException],
                            s"Expected TopicPublicationClosedException but got ${e.getClass.getName}"
                        )
                    case _ => fail("Expected Failure")
                end match
            }
        }

        "MaxPositionExceeded (-5) -> TopicMaxPositionExceededException (terminal)" in {
            val result = Abort.run[TopicException](Topic.mapOfferResult(
                AeronSentinels.MaxPositionExceeded,
                uri,
                streamId,
                transientSignal,
                msgSize,
                maxLen
            ))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for MaxPositionExceeded but got $r")
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicMaxPositionExceededException],
                            s"Expected TopicMaxPositionExceededException but got ${e.getClass.getName}"
                        )
                    case _ => fail("Expected Failure")
                end match
            }
        }

        "Error (-6) -> TopicMessageTooLargeException (terminal)" in {
            val result =
                Abort.run[TopicException](Topic.mapOfferResult(AeronSentinels.Error, uri, streamId, transientSignal, msgSize, maxLen))
            result.map { r =>
                assert(r.isFailure, s"Expected Failure for Error but got $r")
                r match
                    case Result.Failure(e: TopicMessageTooLargeException) =>
                        assert(
                            e.messageSize == msgSize,
                            s"Expected messageSize=$msgSize but got ${e.messageSize}"
                        )
                        assert(
                            e.maxMessageLength == maxLen,
                            s"Expected maxMessageLength=$maxLen but got ${e.maxMessageLength}"
                        )
                    case Result.Failure(e) =>
                        fail(s"Expected TopicMessageTooLargeException but got ${e.getClass.getName}")
                    case _ => fail("Expected Failure")
                end match
            }
        }

        // full sentinel table asserts the complete mapping in one pass.
        // -2/-1/-3 -> TopicBackpressureException (retryable); -4 -> TopicPublicationClosedException;
        // -5 -> TopicMaxPositionExceededException; -6 -> TopicMessageTooLargeException; +1 -> success.
        "full sentinel table" in {
            // positive: success
            val checkPos = Abort.run[TopicException](
                Topic.mapOfferResult(1L, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                assert(r.isSuccess, s"1L: expected Success but got $r")
            }
            // -2: TopicBackpressureException
            val checkBp = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.BackPressured, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureException],
                            s"-2: expected TopicBackpressureException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-2: expected Failure but got $r")
                end match
            }
            // -1: TopicBackpressureException (retryable, matches not-connected pre-check)
            val checkNc = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.NotConnected, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureException],
                            s"-1: expected TopicBackpressureException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-1: expected Failure but got $r")
                end match
            }
            // -3: TopicBackpressureException (retryable, log-rotation / term-count race)
            val checkAa = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.AdminAction, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureException],
                            s"-3: expected TopicBackpressureException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-3: expected Failure but got $r")
                end match
            }
            // -4: TopicPublicationClosedException
            val checkCl = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.Closed, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicPublicationClosedException],
                            s"-4: expected TopicPublicationClosedException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-4: expected Failure but got $r")
                end match
            }
            // -5: TopicMaxPositionExceededException
            val checkMp = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.MaxPositionExceeded, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicMaxPositionExceededException],
                            s"-5: expected TopicMaxPositionExceededException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-5: expected Failure but got $r")
                end match
            }
            // -6: TopicMessageTooLargeException
            val checkEr = Abort.run[TopicException](
                Topic.mapOfferResult(AeronSentinels.Error, uri, streamId, transientSignal, msgSize, maxLen)
            ).map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicMessageTooLargeException],
                            s"-6: expected TopicMessageTooLargeException but got ${e.getClass.getName}"
                        )
                    case _ => fail(s"-6: expected Failure but got $r")
                end match
            }
            checkPos.andThen(checkBp).andThen(checkNc).andThen(checkAa).andThen(checkCl).andThen(checkMp).andThen(checkEr)
        }
    }

    // end-to-end not-connected exhaustion.
    // A publish to an IPC URI with no subscriber stays not-connected for the whole schedule
    // and must exhaust to TopicBackpressureExhaustedException, not a terminal TopicPublicationClosedException.
    // With no subscriber the not-connected pre-check in Topic.publish short-circuits before
    // offer is ever called, so this leaf exercises the pre-check exhaustion path, not the
    // mapOfferResult(-1) sentinel arm (the unit leaves cover that mapping directly).
    // Uses a bounded failSchedule so exhaustion is deterministic. Uses a dedicated stream ID
    // (tag for type Int is used by publish internally via tag.hash.abs).
    "not-connected publish exhausts to TopicBackpressureExhaustedException" in {
        val uri = "aeron:ipc"
        // 3 retries at 1 ms: deterministic exhaustion without long wall-clock wait.
        val failSchedule = Schedule.fixed(1.millis).take(3)
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[Int](uri, failSchedule)(Stream.init(Seq(1)))
            }.map { r =>
                r match
                    case Result.Failure(e) =>
                        assert(
                            e.isInstanceOf[TopicBackpressureExhaustedException],
                            s"Expected TopicBackpressureExhaustedException but got ${e.getClass.getName}: $e"
                        )
                    case Result.Success(_) =>
                        fail("Expected failure but publish succeeded (unexpected subscriber?)")
                    case Result.Panic(e) =>
                        fail(s"Unexpected panic: $e")
                end match
            }
        }
    }

end AeronSentinelsTest
