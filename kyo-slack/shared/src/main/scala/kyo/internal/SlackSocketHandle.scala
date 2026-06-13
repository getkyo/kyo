package kyo.internal

import kyo.*

/** Internal carrier behind the opaque `kyo.SlackConnection`. Holds the live engine
  * and config for the manual receive/close path. The controller slot is populated by
  * the reconnect layer.
  */
final private[kyo] class SlackSocketHandle private[kyo] (
    private[kyo] val engine: SlackSocketEngine,
    private[kyo] val config: SlackConfig,
    private[kyo] val controller: AtomicRef[Maybe[kyo.internal.SlackReconnect.Controller]]
)

private[kyo] object SlackSocketHandle:
    private[kyo] def fromEngine(engine: SlackSocketEngine, config: SlackConfig)(using Frame): SlackSocketHandle < Sync =
        AtomicRef.init[Maybe[kyo.internal.SlackReconnect.Controller]](Absent).map(ref => new SlackSocketHandle(engine, config, ref))
