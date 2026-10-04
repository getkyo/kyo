package kyo.internal.telegram

import kyo.*

/** The long-polling loop behind `Telegram.receive`.
  *
  * `getUpdates` confirms every update whose id is below the `offset` it is called with, so the loop
  * passes one past the last update whose handler returned, and a batch is never confirmed ahead of its
  * handlers. Updates of a batch are handled one at a time, in order.
  *
  * The polls go through an `HttpClient` of their own, `poll`, opened in `receive`'s region: a poll holds its
  * connection for up to `pollTimeout`, and ending the region closes that connection rather than leaving
  * it in the pool of the client whose verbs the handler calls.
  */
private[kyo] object Poller:

    inline val Method = "getUpdates"

    def receive[E, S](telegram: Telegram, poll: HttpClient)(
        handler: Telegram.Update => Unit < (Async & Abort[E] & Env[Telegram] & S)
    )(using Frame): Unit < (Async & Abort[TelegramReceiveFailure | E] & Env[Telegram] & S) =
        val config = telegram.config
        Loop[Maybe[Long], Schedule, Unit, Async & Abort[TelegramReceiveFailure | E] & Env[Telegram] & S](
            Absent,
            config.retrySchedule
        ) { (offset, schedule) =>
            Abort.runWith[TelegramReceiveFailure](this.poll(config, poll, offset)) {
                case Result.Success(updates) =>
                    Kyo.foldLeft(updates)(offset) { (_, update) =>
                        handler(update).andThen(Present(update.id.value + 1))
                    }.map(next => Loop.continue(next, config.retrySchedule))
                case Result.Failure(failure) =>
                    retry(failure, schedule).map(next => Loop.continue(offset, next))
                case Result.Panic(ex) => Abort.panic(ex)
            }
        }
    end receive

    /** One `getUpdates` and the updates it answered, each decoded. */
    private def poll(config: TelegramConfig, client: HttpClient, offset: Maybe[Long])(using
        Frame
    ): Chunk[Telegram.Update] < (Async & Abort[TelegramReceiveFailure]) =
        BotApi.callWith[Chunk[Structure.Value], Chunk[Structure.Value], TelegramReceiveFailure](
            config,
            client,
            Method,
            BotApi.Payload.of(Request.GetUpdates(offset, config.pollTimeout, config.pollLimit, config.allowedUpdates)),
            config.pollTimeout,
            // The loop paces its own polls under `retrySchedule`; `retry` would wait inside one poll.
            retry = Absent
        )(
            Result.succeed(_)
        ).map { values =>
            Kyo.foreach(values) { value =>
                Structure.decode[Telegram.Update](value) match
                    case Result.Success(update)  => update
                    case Result.Failure(failure) =>
                        Abort.fail(TelegramDecodeException.ofDecoded(Method, TelegramDecodeException.Part.Update, failure))
                    case Result.Panic(ex) => Abort.panic(ex)
            }
        }
    end poll

    /** Waits before the next poll when `failure` can pass and the schedule allows another attempt, and
      * answers the schedule for the attempt after it; otherwise ends the loop with `failure`.
      */
    private[kyo] def retry(failure: TelegramReceiveFailure, schedule: Schedule)(using
        Frame
    ): Schedule < (Async & Abort[TelegramReceiveFailure]) =
        def waitThen(sent: Maybe[Duration]): Schedule < (Async & Abort[TelegramReceiveFailure]) =
            Clock.now.map { now =>
                schedule.next(now) match
                    case Absent                 => Abort.fail(failure)
                    case Present((delay, next)) => Async.sleep(sent.getOrElse(delay)).andThen(next)
            }
        decide(failure) match
            case RetryDecision.Stop          => Abort.fail(failure)
            case RetryDecision.WaitSchedule  => waitThen(Absent)
            case RetryDecision.WaitFor(sent) => waitThen(Present(sent))
        end match
    end retry

    /** What `retry` does with a failure: end the loop, wait the schedule's delay, or wait the delay Telegram sent. */
    private[kyo] enum RetryDecision derives CanEqual:
        case Stop
        case WaitSchedule
        case WaitFor(delay: Duration)
    end RetryDecision

    // Every leaf of the sealed TelegramReceiveFailure is named, so a new one does not compile until it is decided.
    private[kyo] def decide(failure: TelegramReceiveFailure): RetryDecision =
        failure match
            case _: TelegramTransportException        => RetryDecision.WaitSchedule
            case e: TelegramUnexpectedStatusException => if e.status.isServerError then RetryDecision.WaitSchedule else RetryDecision.Stop
            case e: TelegramRateLimitException        => e.retryAfter.fold(RetryDecision.WaitSchedule)(RetryDecision.WaitFor(_))
            case _: TelegramDecodeException           => RetryDecision.Stop
            case _: TelegramUnauthorizedException     => RetryDecision.Stop
            case _: TelegramConflictException         => RetryDecision.Stop
            case _: TelegramOtherApiException         => RetryDecision.Stop

end Poller
