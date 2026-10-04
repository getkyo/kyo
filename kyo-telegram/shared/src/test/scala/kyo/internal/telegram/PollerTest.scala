package kyo.internal.telegram

import kyo.*

class PollerTest extends kyo.test.Test[Any]:

    private val transport =
        TelegramTransportException("getUpdates", TelegramTransportException.Kind.ConnectionClosed, "api.telegram.org", 443, Absent)()

    /** Runs `retry` under virtual time: whether it had returned once `before` passed, and its result once `rest` more passed. */
    private def observe(failure: TelegramReceiveFailure, schedule: Schedule, before: Duration, rest: Duration)(using
        Frame
    ): (Boolean, Result[TelegramReceiveFailure, Schedule]) < Async =
        Clock.withTimeControl { control =>
            for
                fiber  <- Fiber.initUnscoped(Abort.run[TelegramReceiveFailure](Poller.retry(failure, schedule)))
                _      <- control.awaitPendingSleepers(1)
                _      <- control.advance(before)
                early  <- fiber.done
                _      <- control.advance(rest)
                result <- fiber.get
            yield (early, result)
        }

    "a transport failure waits the schedule's delay and answers the schedule for the next attempt" in {
        observe(transport, Schedule.fixed(2.seconds), 1999.millis, 1.milli).map { (early, result) =>
            assert(!early)
            assert(result == Result.succeed(Schedule.fixed(2.seconds)))
        }
    }

    "a server error waits the schedule's delay" in {
        observe(TelegramUnexpectedStatusException("getUpdates", HttpStatus(503)), Schedule.fixed(3.seconds), 2999.millis, 1.milli).map {
            (early, result) =>
                assert(!early)
                assert(result == Result.succeed(Schedule.fixed(3.seconds)))
        }
    }

    "a rate limit waits the delay Telegram sent instead of the schedule's" in {
        observe(TelegramRateLimitException("getUpdates", Present(9.seconds)), Schedule.fixed(2.seconds), 8999.millis, 1.milli).map {
            (early, result) =>
                assert(!early)
                assert(result == Result.succeed(Schedule.fixed(2.seconds)))
        }
    }

    "a rate limit without a delay waits the schedule's" in {
        observe(TelegramRateLimitException("getUpdates", Absent), Schedule.fixed(2.seconds), 1999.millis, 1.milli).map { (early, result) =>
            assert(!early)
            assert(result == Result.succeed(Schedule.fixed(2.seconds)))
        }
    }

    "a failure retrying cannot change ends at once" in {
        val failures: Chunk[TelegramReceiveFailure] = Chunk(
            TelegramUnauthorizedException("getUpdates", "Unauthorized"),
            TelegramConflictException("getUpdates", "Conflict"),
            TelegramUnexpectedStatusException("getUpdates", HttpStatus(404)),
            TelegramOtherApiException("getUpdates", 400, "Bad Request"),
            TelegramDecodeException(
                "getUpdates",
                TelegramDecodeException.Part.Update,
                TelegramDecodeException.Failure.MissingField,
                Chunk("update_id"),
                Absent
            )
        )
        Kyo.foreach(failures)(f => Abort.run[TelegramReceiveFailure](Poller.retry(f, Schedule.fixed(1.second)))).map { results =>
            assert(results == failures.map(Result.fail(_)))
        }
    }

    "a retryable failure ends the loop when the schedule allows no more attempts" in {
        Abort.run[TelegramReceiveFailure](Poller.retry(transport, Schedule.done)).map(result => assert(result == Result.fail(transport)))
    }

    "each leaf of TelegramReceiveFailure has its decision" in {
        import Poller.RetryDecision
        val decided: Chunk[(TelegramReceiveFailure, RetryDecision)] = Chunk(
            transport                                                              -> RetryDecision.WaitSchedule,
            TelegramUnexpectedStatusException("getUpdates", HttpStatus.BadGateway) -> RetryDecision.WaitSchedule,
            TelegramUnexpectedStatusException("getUpdates", HttpStatus(404))       -> RetryDecision.Stop,
            TelegramRateLimitException("getUpdates", Present(7.seconds))           -> RetryDecision.WaitFor(7.seconds),
            TelegramRateLimitException("getUpdates", Absent)                       -> RetryDecision.WaitSchedule,
            TelegramUnauthorizedException("getUpdates", "Unauthorized")            -> RetryDecision.Stop,
            TelegramConflictException("getUpdates", "Conflict")                    -> RetryDecision.Stop,
            TelegramOtherApiException("getUpdates", 400, "Bad Request")            -> RetryDecision.Stop,
            TelegramDecodeException(
                "getUpdates",
                TelegramDecodeException.Part.Update,
                TelegramDecodeException.Failure.MissingField,
                Chunk.empty,
                Absent
            ) -> RetryDecision.Stop
        )
        assert(decided.map((f, _) => Poller.decide(f)) == decided.map(_._2))
    }

end PollerTest
