package kyo

import kyo.Actor.Subject

/** A push-based publish/subscribe handle.
  *
  * Subscribers are [[Actor.Subject]] sinks; `publish` delivers each value into every subscriber, awaiting delivery, and prunes any
  * subscriber whose send fails with `Closed`. Unlike [[Hub]] (a pull primitive whose listeners buffer and drain at their own rate), a PubSub
  * pushes directly into subscriber sinks, so a subscriber's own mailbox is the buffer.
  *
  * @tparam A
  *   The type of values published through this PubSub
  */
sealed abstract class PubSub[A]:

    /** Publishes a value to all current subscribers, awaiting delivery. Subscribers that respond with `Closed` are removed after delivery
      * completes. A publish that races a concurrent `close` may deliver to no subscribers and complete normally; only a publish issued after
      * `close` completes is guaranteed to fail with `Closed`.
      */
    def publish(value: A)(using Frame): Unit < (Async & Abort[Closed])

    /** Registers a subscriber. The subscription is removed when the enclosing scope closes. */
    def subscribe(subscriber: Subject[A])(using Frame): Unit < (Async & Abort[Closed] & Scope)

    /** The number of registered subscribers.
      *
      * Subscribers whose sink has closed are pruned lazily on the next `publish`, so a recently-closed sink may still be counted until then.
      */
    def subscriberCount(using Frame): Int < (Async & Abort[Closed])

    /** Closes the PubSub. Subsequent `publish` and `subscribe` operations fail with `Closed`. Subscribers are not notified; their channels or
      * mailboxes stay open and simply receive no further values.
      */
    def close(using Frame): Unit < Sync

end PubSub

object PubSub:

    // Sends `value` to each subscriber and returns the set of subscribers that failed with Closed (to be pruned).
    // `concurrency` bounds how many subscriber sends run in parallel per publish.
    private def fanOut[A](subscribers: Set[Subject[A]], value: A, concurrency: Int)(using Frame): Set[Subject[A]] < Async =
        Async.foreach(subscribers.toSeq, concurrency) { subscriber =>
            Abort.run[Closed](subscriber.send(value)).map {
                case Result.Success(_) => Maybe.empty[Subject[A]]
                case _                 => Maybe(subscriber)
            }
        }.map(results => results.collect { case Present(s) => s }.toSet)

    /** Creates a plain PubSub with direct concurrent fan-out, bounded to [[Async.defaultConcurrency]] subscriber sends per publish.
      *
      * Per-subscriber delivery is FIFO from each publisher's perspective. Under concurrent publishers there is no total order across
      * subscribers (two subscribers may observe two concurrent publishes in different orders). Use `linearized` when all subscribers must
      * agree on order.
      *
      * @tparam A
      *   The type of values published
      */
    def init[A](using frame: Frame): PubSub[A] < Sync =
        init[A](Async.defaultConcurrency)

    /** Creates a plain PubSub with direct concurrent fan-out, bounding how many subscribers a single publish delivers to in parallel.
      *
      * Per-subscriber delivery is FIFO from each publisher's perspective. Under concurrent publishers there is no total order across
      * subscribers (two subscribers may observe two concurrent publishes in different orders). Use `linearized` when all subscribers must
      * agree on order.
      *
      * @param concurrency
      *   The maximum number of subscriber sends a single `publish` runs in parallel (must be >= 1). A value of `1` delivers to subscribers
      *   sequentially.
      * @tparam A
      *   The type of values published
      */
    def init[A](concurrency: Int)(using frame: Frame): PubSub[A] < Sync =
        require(concurrency >= 1, s"concurrency must be >= 1, got $concurrency")
        for
            state  <- AtomicRef.init(Set.empty[Subject[A]])
            closed <- AtomicBoolean.init(false)
        yield new PubSub[A]:
            def publish(value: A)(using Frame): Unit < (Async & Abort[Closed]) =
                closed.get.map {
                    case true => Abort.fail(Closed("PubSub", frame))
                    case false =>
                        state.get.map { subscribers =>
                            fanOut(subscribers, value, concurrency).map { dead =>
                                if dead.isEmpty then ()
                                else state.updateAndGet(_ -- dead).unit
                            }
                        }
                }
            def subscribe(subscriber: Subject[A])(using Frame): Unit < (Async & Abort[Closed] & Scope) =
                closed.get.map {
                    case true => Abort.fail(Closed("PubSub", frame))
                    case false =>
                        state.updateAndGet(_ + subscriber).andThen {
                            Scope.ensure(state.updateAndGet(_ - subscriber).unit)
                        }
                }
            def subscriberCount(using Frame): Int < (Async & Abort[Closed]) = state.get.map(_.size)
            def close(using Frame): Unit < Sync                             = closed.set(true).andThen(state.set(Set.empty))
        end for
    end init

    /** Commands processed by a [[linearized]] PubSub's owning actor.
      *
      * Each command carries its own `replyTo` sink so the actor can signal completion (or return a value) through the strand-safe
      * [[Actor.ask]], which races the reply against actor termination.
      *
      * `private[kyo]` rather than object-`private`: the lifted `Tag[Command[A]]` using-params on `linearized` expose this type in that
      * public method's signature, so it must be at least package-visible.
      */
    private[kyo] enum Command[A]:
        case Publish(value: A, replyTo: Subject[Unit])
        case Subscribe(subscriber: Subject[A], replyTo: Subject[Unit])
        case Unsubscribe(subscriber: Subject[A], replyTo: Subject[Unit])
        case Count(replyTo: Subject[Int])
    end Command

    /** Creates a linearized PubSub backed by an actor that owns the subscriber set, bounding fan-out to [[Async.defaultConcurrency]]
      * subscriber sends per publish.
      *
      * All publishes and membership changes serialize through one mailbox, so every subscriber observes events in the same total order, and
      * subscribe/unsubscribe are ordered relative to publishes. Costs one actor hop per operation. Use [[init]] when cross-subscriber order
      * does not matter and you want the cheaper direct fan-out.
      *
      * Every operation goes through the strand-safe [[Actor.ask]], so closing the PubSub while an operation is in flight completes the
      * waiting caller (with `Closed`) rather than stranding it.
      *
      * @tparam A
      *   The type of values published
      */
    def linearized[A: Tag](using
        frame: Frame,
        // These composed tags are summoned at the concrete call site (where A is known) rather than
        // synthesized inside this method: the kyo package forbids deriving a dynamic tag whose leaf is the
        // abstract A, so they are lifted to using parameters here.
        stateTag: Tag[Var[Set[Subject[A]]]],
        commandTag: Tag[Command[A]],
        pollTag: Tag[Poll[Command[A]]],
        emitTag: Tag[Emit[Command[A]]],
        subjectTag: Tag[Subject[Command[A]]]
    ): PubSub[A] < (Scope & Async) =
        linearized[A](Async.defaultConcurrency)

    /** Creates a linearized PubSub backed by an actor that owns the subscriber set, bounding how many subscribers a single publish delivers
      * to in parallel.
      *
      * All publishes and membership changes serialize through one mailbox, so every subscriber observes events in the same total order, and
      * subscribe/unsubscribe are ordered relative to publishes. The bound only limits parallelism within a single publish's fan-out; ordering
      * across publishes is unaffected. Costs one actor hop per operation. Use [[init]] when cross-subscriber order does not matter and you want
      * the cheaper direct fan-out.
      *
      * Every operation goes through the strand-safe [[Actor.ask]], so closing the PubSub while an operation is in flight completes the
      * waiting caller (with `Closed`) rather than stranding it.
      *
      * @param concurrency
      *   The maximum number of subscriber sends a single `publish` runs in parallel (must be >= 1). A value of `1` delivers to subscribers
      *   sequentially.
      * @tparam A
      *   The type of values published
      */
    def linearized[A: Tag](concurrency: Int)(using
        frame: Frame,
        // These composed tags are summoned at the concrete call site (where A is known) rather than
        // synthesized inside this method: the kyo package forbids deriving a dynamic tag whose leaf is the
        // abstract A, so they are lifted to using parameters here.
        stateTag: Tag[Var[Set[Subject[A]]]],
        commandTag: Tag[Command[A]],
        pollTag: Tag[Poll[Command[A]]],
        emitTag: Tag[Emit[Command[A]]],
        subjectTag: Tag[Subject[Command[A]]]
    ): PubSub[A] < (Scope & Async) =
        require(concurrency >= 1, s"concurrency must be >= 1, got $concurrency")
        // A private actor owns the subscriber set in Var[Set[Subject[A]]] and serializes every command
        // through its single mailbox, which is what gives all subscribers a total order on publishes.
        // Each facade method issues the strand-safe Actor.ask, so a caller in flight when the PubSub closes
        // is completed (the reply races actor termination) instead of being stranded.
        Actor.run {
            Var.run(Set.empty[Subject[A]]) {
                Actor.receiveLoop[Command[A]] {
                    case Command.Publish(value, replyTo) =>
                        Var.use[Set[Subject[A]]] { subscribers =>
                            fanOut(subscribers, value, concurrency).map { dead =>
                                (if dead.isEmpty then Kyo.unit else Var.update[Set[Subject[A]]](_ -- dead).unit)
                                    .andThen(replyTo.send(()))
                                    .andThen(Loop.continue)
                            }
                        }
                    case Command.Subscribe(subscriber, replyTo) =>
                        Var.update[Set[Subject[A]]](_ + subscriber)
                            .andThen(replyTo.send(()))
                            .andThen(Loop.continue)
                    case Command.Unsubscribe(subscriber, replyTo) =>
                        Var.update[Set[Subject[A]]](_ - subscriber)
                            .andThen(replyTo.send(()))
                            .andThen(Loop.continue)
                    case Command.Count(replyTo) =>
                        Var.use[Set[Subject[A]]](subs => replyTo.send(subs.size))
                            .andThen(Loop.continue)
                }
            }
        }.map { actor =>
            new PubSub[A]:
                def publish(value: A)(using Frame): Unit < (Async & Abort[Closed]) =
                    actor.ask(Command.Publish(value, _))

                def subscribe(subscriber: Subject[A])(using Frame): Unit < (Async & Abort[Closed] & Scope) =
                    actor.ask(Command.Subscribe(subscriber, _)).andThen {
                        Scope.ensure(Abort.run[Closed](actor.ask(Command.Unsubscribe(subscriber, _))).unit)
                    }

                def subscriberCount(using Frame): Int < (Async & Abort[Closed]) =
                    actor.ask(Command.Count(_))

                def close(using Frame): Unit < Sync =
                    actor.close.unit
        }
    end linearized

end PubSub
