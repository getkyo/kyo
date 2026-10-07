package kyo.internal.discord

import kyo.*

/** A Gateway session: the policy over its connections, and the queue of the events they read.
  *
  * The session fiber identifies, reads until a connection ends, and reconnects as the outcome and `DiscordConfig.reconnect` say:
  * resuming where it can, identifying afresh where Discord requires it. Before every Identify, `GET /gateway/bot` supplies the URL and
  * the daily budget, and identifies on one client are spaced by 5 seconds over `max_concurrency` ("Session Start Limit Object").
  *
  * Each wait before a reconnection has its timer armed, then is logged at info with its length, then awaited: the record is
  * operational, and it is also the point a test can advance the clock from.
  *
  * `events` is drained by `receive`. A dispatch is queued with an unwaited put, so the reader never blocks on it: a bounded channel
  * keeps its pending puts in order, which holds the dispatches read before anyone receives, `READY` first.
  */
final private[kyo] class Session private (
    val events: Channel[Gateway.Delivery],
    // The take of `events` a receive has in flight. It outlives the receive that started it, so the event it is handed goes to the
    // next receive rather than being dropped with an interrupted take.
    val pendingTake: AtomicRef[Maybe[Fiber[Gateway.Delivery, Abort[Closed]]]],
    val ended: Fiber.Promise[Unit, Abort[Gateway.Failure]],
    shared: Gateway.Shared,
    fiber: Fiber[Any, Sync]
):
    /** Closes the session's socket with 1000, which ends the session at Discord, and stops its fiber. Idempotent.
      *
      * With a socket open, the session ends on its own once its reader sees the close: interrupting it instead would tear the socket
      * down before the close frame went out, which Discord reads as an abnormal drop and keeps the session. Between connections there
      * is no socket, and the fiber is interrupted.
      */
    def close(using Frame): Unit < Async =
        shared.stopping.set(true)
            .andThen(shared.current.get.map {
                case Present(ws) => ws.close(1000, "").andThen(fiber.getResult).unit
                case Absent      => fiber.interrupt.andThen(fiber.getResult).unit
            })
            .andThen(events.close.unit)
end Session

private[kyo] object Session:

    /** The backoff between consecutive reconnections: the first after a healthy connection goes at once, each further one waits the
      * policy's next step, and `reset` starts over once a connection reaches Ready or Resumed.
      */
    final case class Backoff(policy: Schedule, current: Maybe[Schedule]):
        def next(now: Instant): Maybe[(Duration, Backoff)] =
            current match
                case Absent     => Present((Duration.Zero, copy(current = Present(policy))))
                case Present(s) => s.next(now).map((delay, rest) => (delay, copy(current = Present(rest))))
        def reset: Backoff = copy(current = Absent)
    end Backoff

    object Backoff:
        def start(policy: Schedule): Backoff = Backoff(policy, Absent)

    /** The wait before the next Identify, so identifies on one client are `5 seconds / maxConcurrency` apart. */
    def identifyWait(last: Maybe[Instant], now: Instant, maxConcurrency: Int): Duration =
        last.fold(Duration.Zero)(at => (at + (5000L / math.max(1, maxConcurrency)).millis).minusOrZero(now))

    /** A Gateway URL Discord supplied, admitted only as `wss` with a host and no unix socket (1.7 of the design note), with the version
      * and encoding the module speaks set in place of any query it had.
      */
    def gatewayUrl(text: String)(using Frame): Maybe[HttpUrl] =
        HttpUrl.parse(text).toMaybe.filter { url =>
            url.scheme.exists(_.equalsIgnoreCase("wss")) && url.host.nonEmpty && url.unixSocket.isEmpty
        }.map(url => url.copy(scheme = Present("wss"), path = if url.path.isEmpty then "/" else url.path, rawQuery = Present(Query)))

    private inline val Query = "v=10&encoding=json"

    /** Starts a session for `discord` and returns it once its first connection reached Ready, or fails as that connection did. */
    def open(discord: Discord)(using Frame): Session < (Async & Abort[Gateway.Failure]) =
        for
            events <- Channel.initUnscoped[Gateway.Delivery](64)
            ended  <- Fiber.Promise.init[Unit, Abort[Gateway.Failure]]
            ready  <- Fiber.Promise.init[Unit, Abort[Gateway.Failure]]
            shared <- AtomicRef.init(Maybe.empty[Long]).map { seq =>
                AtomicRef.init(Maybe.empty[Frames.ReadyInfo]).map { resumable =>
                    AtomicRef.init(Maybe.empty[HttpWebSocket]).map { current =>
                        AtomicBoolean.init(false).map(stopping => Gateway.Shared(seq, resumable, current, stopping))
                    }
                }
            }
            lastIdentify <- AtomicRef.init(Maybe.empty[Instant])
            gatewayInfo  <- AtomicRef.init(Maybe.empty[Discord.GatewayInfo])
            // Unsafe: an unwaited put keeps the reader from blocking on the queue; the channel holds pending puts in order.
            deliver = (delivery: Gateway.Delivery) => Sync.Unsafe.defer(discard(events.unsafe.putFiber(delivery)))
            driver  = Driver(discord, shared, deliver, ready.completeUnit.unit, lastIdentify, gatewayInfo)
            fiber <- Fiber.initUnscoped(Env.run(discord)(Abort.run[Gateway.Failure](driver.run)).map { result =>
                val outcome: Result[Gateway.Failure, Unit < Abort[Gateway.Failure]] = result match
                    case Result.Success(_) => Result.succeed(())
                    case Result.Failure(e) => Result.fail(e)
                    case Result.Panic(t)   => Result.panic(t)
                ready.completeDiscard(outcome).andThen(ended.completeDiscard(outcome)).andThen(events.close.unit)
            })
            pendingTake <- AtomicRef.init(Maybe.empty[Fiber[Gateway.Delivery, Abort[Closed]]])
            session = new Session(events, pendingTake, ended, shared, fiber)
            opened <- Abort.run[Gateway.Failure](ready.get)
            _      <- if opened.isSuccess then Kyo.unit else session.close
        yield Abort.get(opened).andThen(session)
        end for
    end open

    /** Where the next connection goes, and how it starts. */
    private enum Mode derives CanEqual:
        case Identify, Resume

    /** What follows a connection. */
    private enum Next derives CanEqual:
        case Reconnect(mode: Mode, backoff: Backoff, timer: Fiber[Unit, Any])
        case End
        case Fail(failure: Gateway.Failure)
    end Next

    final private class Driver(
        discord: Discord,
        shared: Gateway.Shared,
        deliver: Gateway.Delivery => Unit < Sync,
        firstReady: Unit < Sync,
        lastIdentify: AtomicRef[Maybe[Instant]],
        gatewayInfo: AtomicRef[Maybe[Discord.GatewayInfo]]
    ):
        private val config = discord.config

        def run(using Frame): Unit < (Async & Abort[Gateway.Failure] & Env[Discord]) =
            val start = config.reconnect match
                case DiscordConfig.Reconnect.Resume(backoff) => Backoff.start(backoff)
                case DiscordConfig.Reconnect.Off             => Backoff.start(Schedule.done)
            Loop(Mode.Identify: Mode, start, false) { (mode, backoff, everReady) =>
                AtomicBoolean.init(false).map { healthy =>
                    val connected = (mode match
                        case Mode.Identify => identify
                        case Mode.Resume   => resume
                    ).map { (url, start) =>
                        Gateway.run(discord, url, start, shared, deliver, healthy.set(true).andThen(firstReady)) { (outcome, close) =>
                            healthy.get.map(h => next(outcome, close, if h then backoff.reset else backoff, everReady || h))
                        }
                    }
                    Abort.run[Gateway.Failure](connected).map {
                        case Result.Success(next) => healthy.get.map(h => (next, everReady || h))
                        // A connection that could not be opened is retried as a close would be, and surfaces when the backoff is done.
                        case Result.Failure(failure: DiscordTransportException) =>
                            unreachable(failure, backoff, everReady).map((_, everReady))
                        case Result.Failure(failure) => (Next.Fail(failure), everReady)
                        case Result.Panic(e)         => Abort.panic(e)
                    }.map { (next, ready) =>
                        next match
                            case Next.Reconnect(mode, backoff, timer) =>
                                // A close that arrived while the connection ended: stop rather than open the next one.
                                timer.get.andThen(shared.stopping.get).map(stopping =>
                                    if stopping then Loop.done(()) else Loop.continue(mode, backoff, ready)
                                )
                            case Next.End           => Loop.done(())
                            case Next.Fail(failure) => Abort.fail(failure)
                    }
                }
            }
        end run

        /** `GET /gateway/bot` for the URL and the budget: a spent budget fails without connecting. */
        private def identify(using Frame): (HttpUrl, Gateway.Start) < (Async & Abort[Gateway.Failure] & Env[Discord]) =
            Rest.call[Discord.GatewayInfo, Gateway.Failure](
                Rest.Call(HttpMethod.GET, "/gateway/bot", Rest.path("gateway", "bot"), retried = false)
            ).map { info =>
                val limit = info.sessionStartLimit
                if limit.remaining <= 0 then Abort.fail(DiscordSessionStartLimitException(limit.total, limit.resetAfter))
                else
                    gatewayUrl(info.url) match
                        case Absent       => Abort.fail(DiscordRefusedUrlException(Gateway.ConnectMethod))
                        case Present(url) =>
                            gatewayInfo.set(Present(info))
                                .andThen(Clock.now.map(now => lastIdentify.set(Present(now))))
                                .andThen((url, Gateway.Start.Identify))
                end if
            }

        /** The resume URL Ready named, falling back to the Get Gateway URL when that one is refused (1.7). */
        private def resume(using Frame): (HttpUrl, Gateway.Start) < (Async & Abort[Gateway.Failure]) =
            shared.resumable.get.map { resumable =>
                gatewayInfo.get.map { info =>
                    resumable match
                        case Absent         => Abort.fail(DiscordRefusedUrlException(Gateway.ConnectMethod))
                        case Present(ready) =>
                            val url: Maybe[HttpUrl] = gatewayUrl(ready.resumeGatewayUrl).orElse(info.flatMap(i => gatewayUrl(i.url)))
                            url match
                                case Absent       => Abort.fail(DiscordRefusedUrlException(Gateway.ConnectMethod))
                                case Present(url) =>
                                    shared.seq.get.map(seq => (url, Gateway.Start.Resume(ready.sessionId, seq)))
                            end match
                }
            }

        /** What follows a connection's outcome, its timer armed before this returns. */
        private def next(outcome: Gateway.Outcome, close: Maybe[(Int, String)], backoff: Backoff, everReady: Boolean)(using
            Frame
        ): Next < (Async & Abort[Gateway.Failure]) =
            outcome match
                case Gateway.Outcome.Stopped                                                            => Next.End
                case Gateway.Outcome.Fail(failure)                                                      => Next.Fail(failure)
                case Gateway.Outcome.Resume | Gateway.Outcome.Identify | Gateway.Outcome.InvalidSession =>
                    val closed = DiscordGatewayClosedException(
                        close.fold(AbnormalClosure)(_._1),
                        Rest.redact(Chunk(config.token.value), close.fold("")(_._2))
                    )
                    config.reconnect match
                        // `Off` ends run and receive cleanly on such a close, and fails an init that has not reached Ready.
                        case DiscordConfig.Reconnect.Off       => if everReady then Next.End else Next.Fail(closed)
                        case DiscordConfig.Reconnect.Resume(_) =>
                            shared.resumable.get.map { resumable =>
                                val mode =
                                    if outcome == Gateway.Outcome.Resume && resumable.nonEmpty then Mode.Resume else Mode.Identify
                                reconnect(mode, outcome == Gateway.Outcome.InvalidSession, backoff, s"after close ${closed.code}", closed)
                            }
                    end match
        end next

        private def unreachable(failure: DiscordTransportException, backoff: Backoff, everReady: Boolean)(using
            Frame
        ): Next < (Async & Abort[Gateway.Failure]) =
            config.reconnect match
                case DiscordConfig.Reconnect.Off       => Next.Fail(failure)
                case DiscordConfig.Reconnect.Resume(_) =>
                    shared.resumable.get.map { resumable =>
                        reconnect(
                            if resumable.nonEmpty then Mode.Resume else Mode.Identify,
                            false,
                            backoff,
                            s"after ${failure.kind.show}",
                            failure
                        )
                    }

        /** Arms the wait before the next connection and logs it: the backoff's step, the 1 to 5 seconds Discord asks for after an
          * invalid session ("wait a random amount of time between 1 and 5 seconds", `events/gateway.mdx`, "Invalid Session"), and the
          * identify spacing, whichever is longest. A done backoff surfaces `last`.
          */
        private def reconnect(mode: Mode, invalidSession: Boolean, backoff: Backoff, why: String, last: Gateway.Failure)(using
            Frame
        ): Next < (Async & Abort[Gateway.Failure]) =
            Clock.now.map { now =>
                backoff.next(now) match
                    case Absent                    => Next.Fail(last)
                    case Present((delay, backoff)) =>
                        for
                            discordAsks <- Random.nextInt(4001).map(n => if invalidSession then (1000 + n).millis else Duration.Zero)
                            identified  <- lastIdentify.get
                            info        <- gatewayInfo.get
                            spacing =
                                if mode == Mode.Identify then
                                    identifyWait(identified, now, info.fold(1)(_.sessionStartLimit.maxConcurrency))
                                else Duration.Zero
                            wait = Seq(delay, discordAsks, spacing).maxBy(_.toNanos)
                            timer <- Gateway.timer(wait)
                            _     <- Log.info(s"Discord Gateway: reconnecting in ${wait.toMillis} milliseconds, $why.")
                        yield Next.Reconnect(mode, backoff, timer)
            }
        end reconnect
    end Driver

    /** RFC 6455's code for a connection that closed without a close frame, used when one is reported with no code. */
    private inline val AbnormalClosure = 1006

end Session
