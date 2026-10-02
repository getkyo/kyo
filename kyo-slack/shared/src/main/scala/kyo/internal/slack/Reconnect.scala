package kyo.internal.slack

import kyo.*

/** The reconnect policy layer above `SocketEngine`, driven by
  * `SlackConfig.Reconnect`. The single owner of engine lifecycle: it is the only
  * code that constructs, swaps, and closes engines (the receive loop reads the
  * active engine ref but never closes one). The hard case is `Overlap`: bring the
  * fresh engine's transport up live (its readiness gate completes on WS-connect-body
  * entry) BEFORE stopping the old one, so both sockets are live across the switch and
  * no inbound envelope is lost. A bounded rolling seen-`envelope_id` window carries
  * the old engine's ids into the new engine's window, so an id Slack re-pushes onto
  * both connections during the overlap is acked but not re-delivered; the window
  * stays bounded to two engine generations, never a lifetime accumulator.
  *
  * `link_disabled` is terminal under every policy; the engine raises
  * `SlackLinkDisabledException` and the controller propagates it.
  */
private[kyo] object Reconnect:

    /** A bounded rolling seen-`envelope_id` window: the ids the PRIOR engine acknowledged
      * plus the ids the CURRENT engine has acknowledged. A re-delivered id is suppressed
      * when it is in EITHER set, so an id the old engine acknowledged and Slack re-pushes
      * onto the new engine during the overlap is acked but not re-delivered. An id whose
      * delivery produced no ack is in neither set, so its next push is delivered. `advance`
      * rolls the window forward at a rotation: the current ids become the prior window
      * and the current set is emptied, so the set is bounded to two engine generations,
      * never a lifetime accumulator. After two rotations an id falls out of the window
      * and is delivered again. Each set is an `AtomicRef`, so the two engines that
      * overlap at a rotation read and grow them atomically.
      */
    final private[kyo] class OverlapDedup(
        private val prior: AtomicRef[Set[SlackId.EnvelopeId]],
        private val current: AtomicRef[Set[SlackId.EnvelopeId]]
    ):
        /** True if this id is in the prior window or the current set. */
        private[kyo] def seenBefore(id: SlackId.EnvelopeId)(using Frame): Boolean < Sync =
            prior.get.map(p => current.get.map(c => p.contains(id) || c.contains(id)))

        /** Record this id as acknowledged by the current engine. */
        private[kyo] def remember(id: SlackId.EnvelopeId)(using Frame): Unit < Sync =
            current.updateAndGet(_ + id).unit

        /** Roll the window forward at a rotation: the current ids become the prior
          * window (so a re-push during the overlap is still suppressed), and the
          * current set is emptied for the new engine.
          */
        private[kyo] def advance(using Frame): Unit < Sync =
            current.getAndSet(Set.empty).map(now => prior.set(now))

        /** Drop both windows (Immediate rotation: no overlap, no carry-over). */
        private[kyo] def clear(using Frame): Unit < Sync =
            prior.set(Set.empty).andThen(current.set(Set.empty))
    end OverlapDedup

    private[kyo] def newDedup(using Frame): OverlapDedup < Sync =
        AtomicRef.init[Set[SlackId.EnvelopeId]](Set.empty).map { prior =>
            AtomicRef.init[Set[SlackId.EnvelopeId]](Set.empty).map(current => new OverlapDedup(prior, current))
        }

    /** A live reconnect controller: holds the active-engine ref so teardown closes
      * whatever engine is current (not just the first), the single owner of engine
      * lifecycle. `Slack.run` `Scope.acquireRelease`s a `Controller` so the scope
      * finalizer calls `closeActive` on the engine active at exit, even after a
      * rotation swapped it.
      */
    final private[kyo] class Controller private[kyo] (
        private[kyo] val active: AtomicRef[SocketEngine],
        private[kyo] val open: () => SocketEngine < (Async & Abort[SlackException.Connect]),
        private[kyo] val config: SlackConfig
    ):
        /** Run the receive loop under the reconnect policy until a clean stop or a
          * terminal `link_disabled`. On a routine disconnect, rotate per policy.
          */
        private[kyo] def start[E, S](
            using Isolate[S, Abort[E] & Async, S]
        )(
            handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E])
        )(using Frame): Unit < (S & Async & Abort[SlackException.Connect | SlackLinkDisabledException | E]) =
            newDedup.map(dedup => loop(active, dedup, open, config, handler))

        /** Close the CURRENTLY active engine (the scope finalizer / `Slack.close`
          * teardown). Idempotent; total.
          */
        private[kyo] def closeActive(using Frame): Unit < Async =
            active.get.map(_.closeNow)
    end Controller

    /** Open the first engine via `open` and seed a `Controller` with it as the
      * initial active engine, so the FIRST engine IS the controller's active engine
      * (never a leaked second one).
      */
    private[kyo] def open(
        open: () => SocketEngine < (Async & Abort[SlackException.Connect]),
        config: SlackConfig
    )(using Frame): Controller < (Async & Abort[SlackException.Connect]) =
        open().map(first => AtomicRef.init[SocketEngine](first).map(active => new Controller(active, open, config)))

    /** Seed a `Controller` from an ALREADY-OPENED engine: the controller a client from
      * `Slack.init` holds for its whole life, so every `receive` starts on the engine active
      * at that moment and `close` tears down that same engine, whatever rotations came
      * before. `open` supplies fresh engines for rotations only.
      */
    private[kyo] def controllerFrom(
        first: SocketEngine,
        open: () => SocketEngine < (Async & Abort[SlackException.Connect]),
        config: SlackConfig
    )(using Frame): Controller < Sync =
        AtomicRef.init[SocketEngine](first).map(active => new Controller(active, open, config))

    private def loop[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        active: AtomicRef[SocketEngine],
        dedup: OverlapDedup,
        open: () => SocketEngine < (Async & Abort[SlackException.Connect]),
        config: SlackConfig,
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E])
    )(using Frame): Unit < (S & Async & Abort[SlackException.Connect | SlackLinkDisabledException | E]) =
        Loop.foreach {
            active.get.map { engine =>
                engine.receiveLoopWithReconnect(
                    handler,
                    dedup,
                    onRoutineDisconnect = _ =>
                        config.reconnect match
                            case SlackConfig.Reconnect.Off       => Reaction.Stop
                            case SlackConfig.Reconnect.Immediate => Reaction.Reconnect(overlap = false)
                            case SlackConfig.Reconnect.Overlap   => Reaction.Reconnect(overlap = true)
                ).map {
                    // Stopping drains and flushes like a rotation: the teardown after the loop is closeNow, which drops
                    // the buffered residue and any ack still in outbound.
                    case Reaction.Stop =>
                        engine.drainBufferedInbound(handler, dedup).andThen(engine.closeTransport).andThen(Loop.done[Unit])
                    case Reaction.Reconnect(overlap) => rotate(active, dedup, open, engine, overlap, handler).andThen(Loop.continue[Unit])
                }
            }
        }

    /** The Overlap choreography (overlap = true): roll the dedup window forward (the
      * ids the old engine delivered become the prior window so a re-push onto the new
      * engine during the overlap is suppressed; the set stays bounded to two engine
      * generations), then open and AWAIT the new engine's transport readiness BEFORE
      * stopping the old one, so both sockets are live across the switch (no instant
      * where neither reads, no loss). An id re-pushed AFTER the window has rolled past
      * it (two rotations later) IS delivered again.
      *
      * The teardown order is the design contract: engineNew ready (open awaits the
      * readiness gate) -> switch the active ref -> drain engineOld's already-buffered
      * inbound residue through the SAME deliver+ack path (so a frame Slack already
      * delivered to engineOld but the loop had not yet read when active switched is
      * delivered exactly once, not dropped; dedup suppresses any id already delivered)
      * -> close engineOld's socket and relay. The drain is a SEPARATE no-loss mechanism
      * from the dedup: the dedup only prevents a re-delivered id from being delivered
      * twice, it cannot replay a frame that was silently dropped.
      *
      * Immediate (overlap = false) accepts a gap for NEW frames (the window where neither
      * socket is connected), but frames engineOld ALREADY received are not new: dropping
      * them would lose already-received envelopes. So Immediate also drains engineOld's
      * residue (deliver+ack) BEFORE closing it, then opens the new engine and clears the
      * dedup (no overlap window survives).
      */
    private def rotate[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        active: AtomicRef[SocketEngine],
        dedup: OverlapDedup,
        open: () => SocketEngine < (Async & Abort[SlackException.Connect]),
        engineOld: SocketEngine,
        overlap: Boolean,
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E])
    )(using Frame): Unit < (S & Async & Abort[SlackException.Connect | SlackLinkDisabledException | E]) =
        if overlap then
            dedup.advance.andThen {
                open().map { engineNew =>
                    active.set(engineNew).andThen {
                        // Once engineNew is active, the controller's teardown no longer reaches engineOld, so this rotation
                        // closes it on every ending: a handler failure or an interrupt during the drain included.
                        AtomicBoolean.init(false).map { closed =>
                            Scope.run {
                                Scope.ensure(closed.get.map(done => if done then Kyo.unit else engineOld.closeNow)).andThen {
                                    engineOld.drainBufferedInbound(handler, dedup)
                                        .andThen(engineOld.closeTransport)
                                        .andThen(closed.set(true))
                                }
                            }
                        }
                    }
                }
            }
        else
            engineOld.drainBufferedInbound(handler, dedup).andThen {
                engineOld.closeTransport.andThen {
                    dedup.clear.andThen {
                        open().map(engineNew => active.set(engineNew))
                    }
                }
            }

    /** The controller's reaction to a routine disconnect: stop the loop or rotate
      * (overlapping or not, per policy).
      */
    private[kyo] enum Reaction derives CanEqual:
        case Stop
        case Reconnect(overlap: Boolean)
    end Reaction

end Reconnect
