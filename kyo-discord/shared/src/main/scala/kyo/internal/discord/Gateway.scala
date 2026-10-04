package kyo.internal.discord

import kyo.*

/** One connection to Discord's Gateway (`events/gateway.mdx`): Hello, the heartbeat, Identify or Resume, and the frames until the
  * connection ends with an [[Gateway.Outcome]].
  *
  * The reader never waits on the handler: control frames are handled in place and dispatches go to the session's queue, so a slow
  * consumer cannot hold back the heartbeat acknowledgements behind them and make a live connection look dead.
  *
  * Every timer is armed before the frame that makes it observable is sent: the first heartbeat's before Identify, each next one's
  * before the heartbeat. A test that saw the frame can then advance the clock past the timer.
  */
private[kyo] object Gateway:

    /** What every Gateway entry point can fail with: the leaves its connection, its frames and `GET /gateway/bot` produce. */
    type Failure = DiscordInitFailure & DiscordReceiveFailure

    /** How a connection ended. */
    enum Outcome derives CanEqual:
        /** Reconnect and resume the session. */
        case Resume

        /** Reconnect with a fresh Identify. */
        case Identify

        /** Discord refused the session (op 9 with `d` false): identify afresh after the 1 to 5 seconds Discord asks for. */
        case InvalidSession

        /** The session ends with `failure`. */
        case Fail(failure: Failure)

        /** The client closed it. */
        case Stopped
    end Outcome

    /** How a connection starts. */
    enum Start derives CanEqual:
        case Identify
        case Resume(sessionId: String, seq: Maybe[Long])

    /** A dispatch as it arrived: the event, its sequence, and when it was read, which an interaction's deadline counts from. */
    final case class Delivery(event: Discord.Event[?], seq: Maybe[Long], arrivedAt: Instant)

    /** The state a session keeps across its connections. */
    final class Shared(
        val seq: AtomicRef[Maybe[Long]],
        val resumable: AtomicRef[Maybe[Frames.ReadyInfo]],
        val current: AtomicRef[Maybe[HttpWebSocket]],
        val stopping: AtomicBoolean
    )

    /** Opens a connection to `url` and runs it until it ends. `ended` decides what follows from the outcome and the close the
      * connection saw, while the socket is still open: whatever it arms happens before the socket closes. `healthy` runs when the
      * connection reached Ready or Resumed. A connection that could not be opened fails with the transport leaf.
      */
    def run[A](
        discord: Discord,
        url: HttpUrl,
        start: Start,
        shared: Shared,
        deliver: Delivery => Unit < Sync,
        healthy: Unit < Sync
    )(ended: (Outcome, Maybe[(Int, String)]) => A < (Async & Abort[Failure]))(using
        Frame
    ): A < (Async & Abort[Failure]) =
        val config = discord.config
        val socket = HttpWebSocket.Config(maxFrameSize = MaxFrameSize)
        connectFailure(url) {
            HttpClient.let(discord.http)(HttpClient.withConfig(Rest.requestConfig(config, config.requestTimeout)) {
                HttpClient.webSocket(url, HttpHeaders.empty.add("User-Agent", Rest.UserAgent), socket) { ws =>
                    Abort.run[Failure](connection(discord, ws, start, shared, deliver, healthy)(ended)).map(Abort.get(_))
                }
            })
        }
    end run

    // Discord sends no frame near this size: a `GUILD_CREATE` for a guild at the member limit is the largest, a few MiB.
    private inline val MaxFrameSize = 16 * 1024 * 1024

    private def connection[A](
        discord: Discord,
        ws: HttpWebSocket,
        start: Start,
        shared: Shared,
        deliver: Delivery => Unit < Sync,
        healthy: Unit < Sync
    )(ended: (Outcome, Maybe[(Int, String)]) => A < (Async & Abort[Failure]))(using Frame): A < (Async & Abort[Failure]) =
        val config  = discord.config
        val secrets = Chunk(config.token.value)
        Scope.run {
            for
                _ <- shared.current.set(Present(ws))
                // "Apps can send 120 gateway events per connection every 60 seconds" (`events/gateway.mdx`, "Rate Limiting").
                limiter <- Meter.initRateLimiter(120, 60.seconds, reentrant = false)
                send = (text: String) => Abort.run[Closed](limiter.run(ws.put(HttpWebSocket.Payload.Text(text)))).unit
                beat = shared.seq.get.map(seq => send(s"""{"op":1,"d":${seq.fold("null")(_.toString)}}"""))
                acked   <- AtomicBoolean.init(true)
                zombie  <- AtomicBoolean.init(false)
                closes  <- AtomicRef.init(Maybe.empty[(Int, String)])
                outcome <- helloInterval(ws).map {
                    case Absent            => closedOutcome(ws, zombie, shared, config, secrets)
                    case Present(interval) =>
                        for
                            jitter     <- Random.nextDouble
                            first      <- timer((interval.toMillis * jitter).toLong.millis)
                            heartbeats <- Fiber.initUnscoped(first.get.andThen(Loop.foreach {
                                acked.getAndSet(false).map { wasAcked =>
                                    // "If a client does not receive a heartbeat ACK between its attempts at sending heartbeats", the
                                    // connection is zombied; 4900 keeps the session resumable, where 1000 and 1001 would end it.
                                    if !wasAcked then zombie.set(true).andThen(ws.close(4900, "")).andThen(Loop.done(()))
                                    else timer(interval).map(next => beat.andThen(next.get)).andThen(Loop.continue)
                                }
                            }))
                            _       <- Scope.ensure(heartbeats.interrupt.unit)
                            _       <- send(startFrame(config, start))
                            outcome <- read(ws, shared, deliver, healthy, beat, acked, zombie, config, secrets)
                        yield outcome
                }
                close <- ws.closeReason
                next  <- ended(outcome, close)
                _     <- outcome match
                    case Outcome.Resume | Outcome.Identify | Outcome.InvalidSession => ws.close(4900, "")
                    case Outcome.Fail(_) | Outcome.Stopped                          => ws.close(1000, "")
                _ <- shared.current.set(Absent)
            yield next
        }
    end connection

    /** Hello's heartbeat interval, or `Absent` when the connection closed before it. */
    private def helloInterval(ws: HttpWebSocket)(using Frame): Maybe[Duration] < (Async & Abort[Failure]) =
        Loop.foreach {
            Abort.run[Closed](ws.take()).map {
                case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                    envelope(text).map { frame =>
                        if frame.op != 10 then Loop.continue
                        else
                            Structure.decode[Frames.Hello](frame.d.getOrElse(Structure.Value.Null)) match
                                case Result.Success(hello) => Loop.done(Present(hello.heartbeatInterval.millis))
                                case Result.Failure(e)     => Abort.fail(frameFailure(e))
                                case Result.Panic(e)       => Abort.panic(e)
                    }
                case Result.Success(HttpWebSocket.Payload.Binary(_)) => Loop.continue
                case Result.Failure(_)                               => Loop.done(Absent)
                case Result.Panic(e)                                 => Abort.panic(e)
            }
        }

    private def read(
        ws: HttpWebSocket,
        shared: Shared,
        deliver: Delivery => Unit < Sync,
        healthy: Unit < Sync,
        beat: Unit < Async,
        acked: AtomicBoolean,
        zombie: AtomicBoolean,
        config: DiscordConfig,
        secrets: Chunk[String]
    )(using Frame): Outcome < (Async & Abort[Failure]) =
        Loop.foreach {
            Abort.run[Closed](ws.take()).map {
                case Result.Failure(_)                               => closedOutcome(ws, zombie, shared, config, secrets).map(Loop.done(_))
                case Result.Panic(e)                                 => Abort.panic(e)
                case Result.Success(HttpWebSocket.Payload.Binary(_)) => Loop.continue
                case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                    envelope(text).map { frame =>
                        frame.op match
                            case 0 =>
                                frame.s.fold(Kyo.unit)(s => shared.seq.set(Present(s)))
                                    .andThen(dispatch(text, frame, shared, deliver, healthy))
                                    .andThen(Loop.continue)
                            // Discord asks for a heartbeat at once; it is not one of the periodic ones that must be acknowledged.
                            case 1 => beat.andThen(Loop.continue)
                            case 7 => Loop.done(Outcome.Resume)
                            case 9 =>
                                Loop.done(if frame.d.contains(Structure.Value.Bool(true)) then Outcome.Resume else Outcome.InvalidSession)
                            case 11 => acked.set(true).andThen(Loop.continue)
                            case _  => Loop.continue
                    }
            }
        }

    /** A dispatch: the event goes to the session's queue; `READY` also keeps the session's id and resume URL, and it and `RESUMED`
      * mark the connection healthy. A dispatch whose payload the model cannot read is skipped and logged by name and sequence, never by
      * its content.
      */
    private def dispatch(
        text: String,
        frame: Frames.Envelope,
        shared: Shared,
        deliver: Delivery => Unit < Sync,
        healthy: Unit < Sync
    )(using Frame): Unit < (Async & Abort[Failure]) =
        val name                                  = frame.t.getOrElse("")
        val kept: Unit < (Async & Abort[Failure]) =
            name match
                case "READY" =>
                    Structure.decode[Frames.ReadyInfo](frame.d.getOrElse(Structure.Value.Null)) match
                        case Result.Success(info) => shared.resumable.set(Present(info)).andThen(healthy)
                        case Result.Failure(e)    => Abort.fail(frameFailure(e))
                        case Result.Panic(e)      => Abort.panic(e)
                case "RESUMED" => healthy
                case _         => Kyo.unit
        kept.andThen {
            Json.decode[Discord.Event[?]](text) match
                case Result.Success(event) => Clock.now.map(now => deliver(Delivery(event, frame.s, now)))
                case Result.Failure(_)     =>
                    Log.warn(s"Discord Gateway: skipped the $name dispatch${frame.s.fold("")(s => s" $s")}: its payload did not decode.")
                case Result.Panic(e) => Abort.panic(e)
        }
    end dispatch

    /** How a connection that closed ended: a zombied one resumes, a stopped one stops, and any other by its close code. */
    private def closedOutcome(
        ws: HttpWebSocket,
        zombie: AtomicBoolean,
        shared: Shared,
        config: DiscordConfig,
        secrets: Chunk[String]
    )(using Frame): Outcome < Sync =
        shared.stopping.get.map { stopping =>
            if stopping then Outcome.Stopped
            else
                zombie.get.map { zombied =>
                    if zombied then Outcome.Resume
                    else ws.closeReason.map(close => classify(close.map(_._1), close.fold("")(_._2), config.intents, config.shard, secrets))
                }
        }

    /** The outcome of a close with `code` (`topics/opcodes-and-status-codes.mdx`, "Gateway Close Event Codes"): the codes Discord marks
      * as not reconnectable are their leaves; 4007 (invalid sequence) and 4009 (session timed out) need a new session; every other code,
      * and a close with none, resumes.
      */
    def classify(code: Maybe[Int], reason: String, intents: Discord.Intents, shard: Maybe[Discord.Shard], secrets: Chunk[String])(using
        Frame
    ): Outcome =
        code match
            case Present(4004) => Outcome.Fail(DiscordAuthenticationFailedException())
            case Present(4010) =>
                Outcome.Fail(shard.fold(DiscordGatewayClosedException(4010, Rest.redact(secrets, reason)))(DiscordInvalidShardException(_)))
            case Present(4011)        => Outcome.Fail(DiscordShardingRequiredException())
            case Present(4012)        => Outcome.Fail(DiscordGatewayClosedException(4012, Rest.redact(secrets, reason)))
            case Present(4013)        => Outcome.Fail(DiscordInvalidIntentsException(intents))
            case Present(4014)        => Outcome.Fail(DiscordDisallowedIntentsException(intents))
            case Present(4007 | 4009) => Outcome.Identify
            case _                    => Outcome.Resume
    end classify

    private def startFrame(config: DiscordConfig, start: Start)(using Frame): String =
        start match
            case Start.Identify =>
                Json.encode(Frames.Outbound(
                    2,
                    Frames.Identify(
                        config.token,
                        config.intents,
                        Frames.Properties("kyo", "kyo", "kyo"),
                        config.shard.map(s => Chunk(s.id, s.count))
                    )
                ))
            case Start.Resume(sessionId, seq) => Json.encode(Frames.Outbound(6, Frames.Resume(config.token, sessionId, seq)))

    private def envelope(text: String)(using Frame): Frames.Envelope < Abort[Failure] =
        Json.decode[Frames.Envelope](text) match
            case Result.Success(frame) => frame
            case Result.Failure(e)     => Abort.fail(frameFailure(e))
            case Result.Panic(e)       => Abort.panic(e)

    private def frameFailure(e: DecodeException)(using Frame): DiscordDecodeException =
        DiscordDecodeException(GatewayMethod, DiscordDecodeException.Part.Frame, e)

    /** A timer of `duration`, armed now. A wait of zero is done at once: kyo's `Clock.sleep` arms even a zero sleep, which under a
      * controlled clock fires only when the clock advances.
      */
    def timer(duration: Duration)(using Frame): Fiber[Unit, Any] < Sync =
        if duration <= Duration.Zero then Fiber.unit else Clock.sleep(duration)

    /** How a failure names the Gateway's frames. */
    inline val GatewayMethod = "gateway"

    /** How a failure names opening the Gateway's socket. */
    inline val ConnectMethod = "gateway-connect"

    /** Runs the connect, describing a kyo-http failure as the transport leaf of `gateway-connect` without keeping it. */
    private def connectFailure[A](url: HttpUrl)(v: A < (Async & Abort[Failure | HttpException]))(using
        Frame
    ): A < (Async & Abort[Failure]) =
        Abort.runWith[HttpException](v) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                val described: Maybe[(DiscordTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException])] =
                    e match
                        case _: HttpWebSocketHandshakeException =>
                            Present((DiscordTransportException.Kind.WebSocketHandshake, Absent, Absent))
                        case other => Rest.describe(other)
                described match
                    case Present((kind, timeout, cause)) =>
                        Abort.fail(DiscordTransportException(ConnectMethod, kind, url.host, url.port, timeout)(cause))
                    // The class name only: nothing of a Gateway URL is copied.
                    case Absent => bug(s"${e.getClass.getSimpleName} reached a Discord Gateway connection")
                end match
            case Result.Panic(e) => Abort.panic(e)
        }

end Gateway
