package kyo

/** Socket Mode connection configuration: the app-level and bot tokens, the
  * WebSocket keepalive interval, the per-envelope ack deadline, and the reconnect
  * policy. One value passed into `Slack.connect` / `connectUnscoped`.
  *
  * `keepAliveInterval` maps to `HttpWebSocket.Config.autoPingInterval`: Socket Mode
  * defines no application-level keepalive distinct from WS ping/pong, so
  * this is the complete keepalive mechanism.
  *
  * `ackDeadline` is the per-envelope window within which the framework acks each
  * ackable envelope (Slack re-delivers an envelope it does not see acked within
  * roughly 3 seconds). The handler must return its `SlackAck` within this window: if
  * it does, the framework emits that ack; if it does not, the framework emits the bare
  * ack (`SlackAck.Ack`) when the deadline fires and race-cancels the still-running
  * handler, so no late payload ack is sent (the bare ack already went out). Exactly one
  * ack is emitted per ackable envelope. Long work therefore belongs in a forked fiber
  * or a `response_url` POST, not inline in the handler.
  */
case class SlackConfig(
    appLevel: SlackToken.AppLevel,
    bot: SlackToken.Bot,
    keepAliveInterval: Maybe[Duration] = Present(30.seconds),
    ackDeadline: Duration = 3.seconds,
    reconnect: SlackConfig.Reconnect = SlackConfig.Reconnect.Overlap
) derives CanEqual

object SlackConfig:

    /** Reconnect policy on a routine `disconnect`.
      *   - `Overlap`: open the fresh connection and confirm it is live BEFORE
      *     stopping the old one, so no inbound envelope is lost across the rollover.
      *   - `Immediate`: close the old connection, then open the fresh one (a brief
      *     gap is accepted).
      *   - `Off`: do not reconnect; the loop ends cleanly on a routine disconnect.
      *
      * `link_disabled` is terminal under every policy.
      */
    enum Reconnect derives CanEqual:
        case Overlap
        case Immediate
        case Off
    end Reconnect

end SlackConfig
