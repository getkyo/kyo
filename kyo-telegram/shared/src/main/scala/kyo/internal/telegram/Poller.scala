package kyo.internal.telegram

import kyo.*

/** The long-polling loop behind `Telegram.run`.
  *
  * `getUpdates` confirms every update whose id is below the `offset` it is called with, so the loop
  * passes one past the last update whose handler returned, and a batch is never confirmed ahead of its
  * handlers. Updates of a batch are handled one at a time, in order.
  *
  * The polls go through an `HttpClient` of their own, `poll`, opened in `run`'s region: a poll holds its
  * connection for up to `pollTimeout`, and ending the region closes that connection rather than leaving
  * it in the pool of the client whose verbs the handler calls.
  */
private[kyo] object Poller:

    inline val Method = "getUpdates"

    def run[E, S](telegram: Telegram, poll: HttpClient)(
        handler: TelegramUpdate => Unit < (Async & Abort[E] & Env[Telegram] & S)
    )(using Frame): Unit < (Async & Abort[TelegramRunFailure | E] & Env[Telegram] & S) =
        val config = telegram.config
        Loop[Maybe[Long], Schedule, Unit, Async & Abort[TelegramRunFailure | E] & Env[Telegram] & S](
            Absent,
            config.retrySchedule
        ) { (offset, schedule) =>
            Abort.runWith[TelegramRunFailure](this.poll(config, poll, offset)) {
                case Result.Success(updates) =>
                    Kyo.foldLeft(updates)(offset) { (_, update) =>
                        handler(update).andThen(Present(update.id.value + 1))
                    }.map(next => Loop.continue(next, config.retrySchedule))
                case Result.Failure(failure) =>
                    retry(failure, schedule).map(next => Loop.continue(offset, next))
                case Result.Panic(ex) => Abort.panic(ex)
            }
        }
    end run

    /** One `getUpdates` and the updates it answered, each decoded. */
    private def poll(config: TelegramConfig, client: HttpClient, offset: Maybe[Long])(using
        Frame
    ): Chunk[TelegramUpdate] < (Async & Abort[TelegramRunFailure]) =
        val params: WireCodec.Params =
            offset.fold(Chunk.empty)(o => Chunk("offset" -> WireCodec.long(o))) ++
                Chunk("timeout" -> WireCodec.long(config.pollTimeout.toSeconds), "limit" -> WireCodec.long(config.pollLimit.toLong)) ++
                config.allowedUpdates.fold(Chunk.empty)(types => Chunk("allowed_updates" -> WireCodec.allowedUpdates(types)))
        BotApi.callWith[Chunk[Structure.Value], TelegramRunFailure](
            config,
            client,
            Method,
            BotApi.Payload.Fields(params),
            config.pollTimeout
        )(
            Structure.decode[Chunk[Structure.Value]](_)
        ).map { values =>
            Kyo.foreach(values) { value =>
                WireCodec.decodeUpdate(value) match
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
    private[kyo] def retry(failure: TelegramRunFailure, schedule: Schedule)(using Frame): Schedule < (Async & Abort[TelegramRunFailure]) =
        def waitThen(sent: Maybe[Duration]): Schedule < (Async & Abort[TelegramRunFailure]) =
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

    // Every leaf of the sealed TelegramRunFailure is named, so a new one does not compile until it is decided.
    private[kyo] def decide(failure: TelegramRunFailure): RetryDecision =
        failure match
            case _: TelegramTransportException        => RetryDecision.WaitSchedule
            case e: TelegramUnexpectedStatusException => if e.status.isServerError then RetryDecision.WaitSchedule else RetryDecision.Stop
            case e: TelegramRateLimitException        => e.retryAfter.fold(RetryDecision.WaitSchedule)(RetryDecision.WaitFor(_))
            case _: TelegramDecodeException           => RetryDecision.Stop
            case _: TelegramUnauthorizedException     => RetryDecision.Stop
            case _: TelegramConflictException         => RetryDecision.Stop
            case _: TelegramOtherApiException         => RetryDecision.Stop

end Poller
