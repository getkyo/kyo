package kyo

/** A live, manually-managed Socket Mode connection handle returned by
  * `Slack.connectUnscoped`. Opaque so a caller cannot fabricate one; consumed via
  * the `receive` / `close` extension methods. The representation wraps the internal
  * engine handle, which carries the live socket engine, the config, and the
  * reconnect-controller slot `receive` populates and `close` reads.
  */
opaque type SlackConnection = kyo.internal.SlackSocketHandle

private[kyo] object SlackConnection:
    private[kyo] def fromHandle(h: kyo.internal.SlackSocketHandle): SlackConnection        = h
    extension (c: SlackConnection) private[kyo] def handle: kyo.internal.SlackSocketHandle = c

extension (connection: SlackConnection)
    /** Run the receive loop on this manual connection: ack on handler return exactly
      * once per envelope; a routine disconnect rotates transparently per the config's
      * reconnect policy; ends on `link_disabled` with `SlackTerminalException`. The
      * bot-token ambient is bound around the loop body, so a
      * `Slack.chatPostMessage`/etc. call from inside the handler resolves the token on
      * this manual path exactly as under the scoped `connect`. The connection's
      * already-opened engine is the controller's first active engine (no duplicate
      * open); the controller is recorded on the handle so `close` tears down the
      * currently-active engine.
      */
    def receive[S](using
        Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        val h = SlackConnection.handle(connection)
        kyo.internal.SlackReconnect.controllerFrom(h.engine, () => Slack.openEngine(h.config), h.config).map { controller =>
            h.controller.set(Present(controller)).andThen {
                kyo.internal.SlackWebApi.local.let(Present(h.config.bot)) {
                    controller.start(handler)
                }
            }
        }
    end receive

    /** Observable teardown of socket and background fibers. Idempotent and total
      * (never aborts), mirroring `HttpWebSocket.close`. Closes the currently-active
      * engine (via the controller while `receive` is running, else the initial engine).
      */
    def close(using Frame): Unit < Async =
        val h = SlackConnection.handle(connection)
        h.controller.use {
            case Present(controller) => controller.closeActive
            case Absent              => h.engine.closeNow
        }
    end close
end extension
