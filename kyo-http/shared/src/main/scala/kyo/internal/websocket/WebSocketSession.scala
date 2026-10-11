package kyo.internal.websocket

import kyo.*
import kyo.internal.transport.*

/** The session loop of an upgraded WebSocket connection, shared by the client and the server: a reader fiber decodes messages from the
  * wire into the handle's inbound channel, a writer fiber encodes the handle's outbound channel onto the wire and ends with the Close
  * frame, a keep-alive fiber sends a Ping every `autoPingInterval`, and the handler runs on the calling fiber.
  *
  * The writer is the only fiber that writes data frames, and the Close frame is the last one it writes. The reader's auto-Pong and the
  * keep-alive Ping are written by their own fibers, each as one transport write, so they never split a data frame; they share a lock with
  * the Close frame, and once it is written they are dropped, so nothing follows the Close frame (RFC 6455 section 5.5.1).
  *
  * The session closes, keeping what was queued, as soon as its close is decided, whatever the handler is doing: on `ws.close`, on a
  * Close frame from the peer, which the writer then echoes (RFC 6455 section 5.5.1), on a frame that fails the connection (section
  * 7.1.7), whose Close code becomes the close reason unless one is already set, and on the end of the transport. A `put` after that
  * fails `Closed`.
  *
  * After its own Close an endpoint waits for the peer's, which ends the reader, before the connection is closed, since the connection
  * closes once both Close frames have been exchanged (RFC 6455 section 7.1.2). The wait and the writer's drain together are bounded by
  * `closeTimeout`.
  */
private[kyo] object WebSocketSession:

    /** Runs `f` over a session on `transport`, reading frames from `wire`. `mask` is the role: `true` for a client, whose frames are
      * masked (RFC 6455 section 5.3).
      */
    def run[A, S](
        transport: TransportStream,
        wire: Stream[Span[Byte], Async],
        config: HttpWebSocket.Config,
        mask: Boolean
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async) =
        Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { inbound =>
            Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { outbound =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeReasonRef =>
                    Fiber.Promise.init[Unit, Any].map { peerClosedPromise =>
                        Meter.initMutexUnscoped.map { controlLock =>
                            AtomicBoolean.initWith(false) { closeSent =>
                                // Control frames and the Close frame go through the lock, so none is written after the Close frame.
                                val control = new TransportStream:
                                    def read(using Frame): Stream[Span[Byte], Async]       = transport.read
                                    def write(data: Span[Byte])(using Frame): Unit < Async =
                                        Abort.run[Closed](controlLock.run {
                                            closeSent.get.map(sent => if sent then Kyo.unit else transport.write(data))
                                        }).unit
                                val writeClose: (Int, String) => Unit < Async = (code, reason) =>
                                    Abort.run[Any](controlLock.run {
                                        closeSent.getAndSet(true).map { sent =>
                                            if sent then Kyo.unit else WebSocketCodec.writeClose(transport, code, reason, mask)
                                        }
                                    }).unit
                                // Each route to a close records its reason and closes outbound keeping its queued frames, so the writer
                                // writes every frame put before the close and then the Close frame.
                                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                                    closeReasonRef.set(Present((code, reason))).andThen(WebSocketCodec.closeKeepingQueued(outbound))
                                val peerCloseFn: ((Int, String)) => Unit < Async = (code, reason) => closeFn(code, reason)
                                val failFn: ((Int, String)) => Unit < Async      = failure =>
                                    closeReasonRef.updateAndGet(current => if current.isEmpty then Present(failure) else current)
                                        .andThen(WebSocketCodec.closeKeepingQueued(outbound))
                                val ws = new HttpWebSocket(inbound, outbound, closeReasonRef, peerClosedPromise, closeFn)

                                Fiber.initUnscoped {
                                    Loop(wire) { stream =>
                                        WebSocketCodec.readFrameWith(
                                            stream,
                                            control,
                                            readBufferCapacity(config.maxFrameSize),
                                            peerCloseFn,
                                            mask,
                                            failFn
                                        ) { (frame, remaining) =>
                                            // Once the handler has returned, inbound is closed and the messages that still arrive before
                                            // the peer's Close are dropped.
                                            Abort.run[Closed](inbound.put(frame)).andThen(Loop.continue(remaining))
                                        }
                                    }
                                }.map { readFiber =>
                                    Fiber.initUnscoped {
                                        // On the reader's end (a Close frame, a failure, EOF), close inbound keeping the frames that arrived
                                        // before it, close outbound so the writer ends (with no Close frame after an EOF, since no close
                                        // reason was recorded for a peer that is gone), and complete peerClosedPromise so ws.onPeerClose
                                        // fires, after which a put fails.
                                        readFiber.getResult.map { result =>
                                            val log = result match
                                                case Result.Panic(t) => Log.warn("HttpWebSocket reader panicked", t)
                                                case _               => Kyo.unit
                                            log.andThen(WebSocketCodec.closeKeepingQueued(inbound))
                                                .andThen(WebSocketCodec.closeKeepingQueued(outbound))
                                                .andThen(peerClosedPromise.completeUnit.unit)
                                        }
                                    }.map { monitorFiber =>
                                        Fiber.initUnscoped(keepAlive(control, config, mask)).map { pingFiber =>
                                            Fiber.initUnscoped {
                                                // Exits once outbound is closed or a wire write fails.
                                                Abort.run[Throwable] {
                                                    Loop.foreach {
                                                        outbound.take.map { frame =>
                                                            WebSocketCodec.writeFrame(transport, frame, mask).andThen(Loop.continue)
                                                        }
                                                    }
                                                }.map { result =>
                                                    val log = result match
                                                        case Result.Failure(_: Closed) => Kyo.unit
                                                        case Result.Failure(e)         => Log.warn(s"HttpWebSocket writer failed: $e")
                                                        case Result.Panic(t)           => Log.warn("HttpWebSocket writer panicked", t)
                                                        case Result.Success(_)         => Kyo.unit
                                                    log.andThen(pingFiber.interrupt.unit).andThen(closeReasonRef.get).map {
                                                        case Present((code, reason)) => writeClose(code, reason)
                                                        case Absent                  => Kyo.unit
                                                    }.andThen(outbound.close).unit
                                                }
                                            }.map { writeFiber =>
                                                Sync.ensure(
                                                    readFiber.interrupt.unit
                                                        .andThen(writeFiber.interrupt.unit)
                                                        .andThen(pingFiber.interrupt.unit)
                                                        .andThen(monitorFiber.interrupt.unit)
                                                        .andThen(inbound.closeDiscard)
                                                        .andThen(outbound.closeDiscard)
                                                ) {
                                                    f(ws).map { result =>
                                                        // A handler that returns without a close decided closes with 1000 (unless the
                                                        // reader already ended). The session then waits, within closeTimeout, for the
                                                        // writer to write the queued frames and the Close frame, which the finalizer
                                                        // would otherwise interrupt, and for the peer's Close.
                                                        closeOnReturn(closeReasonRef, readFiber.done)
                                                            .andThen(inbound.closeDiscard)
                                                            .andThen(WebSocketCodec.closeKeepingQueued(outbound))
                                                            .andThen(Abort.run[Timeout](Async.timeout(config.closeTimeout) {
                                                                writeFiber.get.andThen(readFiber.getResult.unit)
                                                            }).unit)
                                                            .andThen(result)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end run

    /** Decides the close reason once the handler has returned: 1000 when no close was decided and the reader, checked by `readerDone`,
      * still runs. The reader may record a peer's Close at any point of this, so the 1000 is written only into an empty reason, in one
      * atomic step.
      */
    private[internal] def closeOnReturn(closeReasonRef: AtomicRef[Maybe[(Int, String)]], readerDone: Boolean < Sync)(using
        Frame
    ): Unit < Sync =
        readerDone.map { isDone =>
            if isDone then Kyo.unit
            else closeReasonRef.updateAndGet(current => if current.isEmpty then Present((1000, "")) else current).unit
        }

    /** Sends an empty Ping every `autoPingInterval` until interrupted. An interval that is not positive and finite sends none. */
    private def keepAlive(transport: TransportStream, config: HttpWebSocket.Config, mask: Boolean)(using Frame): Unit < Async =
        config.autoPingInterval match
            case Present(interval) if interval.isFinite && interval > Duration.Zero =>
                Loop.foreach {
                    Async.sleep(interval).andThen(WebSocketCodec.writePing(transport, mask)).andThen(Loop.continue)
                }
            case _ => Kyo.unit

end WebSocketSession
