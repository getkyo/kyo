package kyo

import kyo.Actor.Subject

/** A push-based publish/subscribe handle.
  *
  * Subscribers are [[Actor.Subject]] sinks; `publish` delivers each value into every subscriber, awaiting delivery, and prunes any
  * subscriber whose send fails with `Closed`. Unlike [[Hub]] (a pull primitive whose listeners buffer and drain at their own rate), a Topic
  * pushes directly into subscriber sinks, so a subscriber's own mailbox is the buffer.
  *
  * @tparam A
  *   The type of values published through this Topic
  */
sealed abstract class Topic[A]:

    /** Publishes a value to all current subscribers, awaiting delivery. Subscribers that respond with `Closed` are removed after delivery
      * completes. A publish that races a concurrent `close` may deliver to no subscribers and complete normally; only a publish issued after
      * `close` completes is guaranteed to fail with `Closed`.
      */
    def publish(value: A)(using Frame): Unit < (Async & Abort[Closed])

    /** Registers a subscriber. The subscription is removed when the enclosing scope closes. */
    def subscribe(subscriber: Subject[A])(using Frame): Unit < (Sync & Abort[Closed] & Scope)

    /** The number of current subscribers. */
    def subscriberCount(using Frame): Int < Sync

    /** Closes the Topic. Subsequent `publish` and `subscribe` operations fail with `Closed`. Subscribers are not notified; their channels or
      * mailboxes stay open and simply receive no further values.
      */
    def close(using Frame): Unit < Sync

end Topic

object Topic:

    // Sends `value` to each subscriber concurrently and returns the set of subscribers that failed with Closed (to be pruned).
    private def fanOut[A](subscribers: Set[Subject[A]], value: A)(using Frame): Set[Subject[A]] < Async =
        Async.foreach(subscribers.toSeq) { subscriber =>
            Abort.run[Closed](subscriber.send(value)).map {
                case Result.Success(_) => Maybe.empty[Subject[A]]
                case _                 => Maybe(subscriber)
            }
        }.map(results => results.collect { case Present(s) => s }.toSet)

    /** Creates a plain Topic with direct concurrent fan-out.
      *
      * Per-subscriber delivery is FIFO from each publisher's perspective. Under concurrent publishers there is no total order across
      * subscribers (two subscribers may observe two concurrent publishes in different orders). Use `linearized` when all subscribers must
      * agree on order.
      *
      * @tparam A
      *   The type of values published
      */
    def init[A](using frame: Frame): Topic[A] < Sync =
        for
            state  <- AtomicRef.init(Set.empty[Subject[A]])
            closed <- AtomicBoolean.init(false)
        yield new Topic[A]:
            def publish(value: A)(using Frame): Unit < (Async & Abort[Closed]) =
                closed.get.map {
                    case true => Abort.fail(Closed("Topic", frame))
                    case false =>
                        state.get.map { subscribers =>
                            fanOut(subscribers, value).map { dead =>
                                if dead.isEmpty then ()
                                else state.updateAndGet(_ -- dead).unit
                            }
                        }
                }
            def subscribe(subscriber: Subject[A])(using Frame): Unit < (Sync & Abort[Closed] & Scope) =
                closed.get.map {
                    case true => Abort.fail(Closed("Topic", frame))
                    case false =>
                        state.updateAndGet(_ + subscriber).andThen {
                            Scope.ensure(state.updateAndGet(_ - subscriber).unit)
                        }
                }
            def subscriberCount(using Frame): Int < Sync = state.get.map(_.size)
            def close(using Frame): Unit < Sync          = closed.set(true).andThen(state.set(Set.empty))

end Topic
