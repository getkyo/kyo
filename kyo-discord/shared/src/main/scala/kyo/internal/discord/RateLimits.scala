package kyo.internal.discord

import kyo.*

/** The client's REST rate limits (`topics/rate-limits.mdx`): the per-route buckets Discord names in its answers, and the global limit.
  *
  * Discord groups routes into buckets it names in `X-RateLimit-Bucket`, per top-level resource, and says in every answer how many
  * calls the bucket has left and when it resets. `routes` maps a route and resource to the bucket it last answered with; `buckets`
  * holds each bucket's state per resource. A call takes one of the remaining calls before it is sent, so concurrent calls do not overrun
  * a bucket; with none left it waits for the reset, or fails at once when the wait would pass the call's bound. A route Discord never
  * answered goes without waiting: "rate limits should not be hard coded into your app". Entries are dropped once their reset passed.
  *
  * The global limit is a [[kyo.Meter]] of `globalRateLimit` calls a second. "Interaction endpoints are not bound to the bot's Global
  * Rate Limit", so a call on a route holding an interaction token bypasses it.
  */
final private[kyo] class RateLimits private (
    routes: AtomicRef[Map[RateLimits.Key, RateLimits.Route]],
    buckets: AtomicRef[Map[(String, String), RateLimits.State]],
    global: Meter
):
    import RateLimits.*

    /** Waits until a call on `key` may be sent: a call from its bucket, then a slot of the global limit unless `global` is false. A
      * bucket wait longer than `maxWait` fails with the route leaf naming the wait and the bucket, and sends nothing.
      */
    def acquire(key: Key, global: Boolean, maxWait: Duration, method: String)(using
        Frame
    ): Unit < (Async & Abort[DiscordRouteRateLimitException]) =
        takeFromBucket(key, maxWait, method).andThen {
            // A closed limiter is a closed client, whose request then fails at its transport.
            if global then Abort.run[Closed](this.global.run(Kyo.unit)).unit else Kyo.unit
        }

    /** Keeps what an answer on `key` said about its bucket. */
    def record(key: Key, answer: Answer)(using Frame): Unit < Sync =
        Clock.now.map { now =>
            answer.bucket match
                case Absent          => Kyo.unit
                case Present(bucket) =>
                    val reset = now + answer.resetAfter.getOrElse(Duration.Zero)
                    routes.updateAndGet(m => alive(m, now)(_.until).updated(key, Route(bucket, reset))).andThen {
                        answer.remaining match
                            case Absent             => Kyo.unit
                            case Present(remaining) =>
                                buckets.updateAndGet(m =>
                                    alive(m, now)(_.reset).updated((bucket, key.resource), State(remaining, reset))
                                ).unit
                    }
        }

    /** Closes the global limiter. */
    def close(using Frame): Unit < Sync = global.close.unit

    /** The calls waiting for a slot of the global limit. */
    def globalWaiters(using Frame): Int < (Async & Abort[Closed]) = global.pendingWaiters

    private def takeFromBucket(key: Key, maxWait: Duration, method: String)(using
        Frame
    ): Unit < (Async & Abort[DiscordRouteRateLimitException]) =
        Loop.foreach {
            Clock.now.map { now =>
                routes.get.map(m => Maybe.fromOption(m.get(key)).filter(_.until > now)).map {
                    case Absent         => Loop.done(())
                    case Present(route) =>
                        val slot                        = (route.bucket, key.resource)
                        def open(state: State): Boolean = state.reset > now && state.remaining > 0
                        buckets.getAndUpdate(m =>
                            Maybe.fromOption(m.get(slot)) match
                                case Present(state) if open(state) => m.updated(slot, state.copy(remaining = state.remaining - 1))
                                case _                             => m
                        ).map { before =>
                            Maybe.fromOption(before.get(slot)) match
                                case Present(state) if state.reset > now && state.remaining <= 0 =>
                                    val wait = state.reset.minusOrZero(now)
                                    if wait > maxWait then
                                        Abort.fail(DiscordRouteRateLimitException(method, Present(wait), Absent, Present(route.bucket)))
                                    else Async.sleep(wait).andThen(Loop.continue)
                                case _ => Loop.done(())
                        }
                }
            }
        }

end RateLimits

private[kyo] object RateLimits:

    /** A route and the top-level resource its path names: what Discord's buckets are keyed by. */
    final case class Key(method: String, route: String, resource: String)

    /** What an answer's headers said: the bucket, its remaining calls and the time until it resets. */
    final case class Answer(bucket: Maybe[String], remaining: Maybe[Int], resetAfter: Maybe[Duration]) derives CanEqual

    object Answer:
        /** `X-RateLimit-Bucket`, `X-RateLimit-Remaining` and `X-RateLimit-Reset-After` ("Total time (in seconds) ... Can have
          * decimals"); a header that does not parse is absent.
          */
        def of(headers: HttpHeaders): Answer =
            val remaining = headers.get("X-RateLimit-Remaining").map(_.trim).filter(t => t.nonEmpty && t.length <= 9 && t.forall(_.isDigit))
            val resetAfter = headers.get("X-RateLimit-Reset-After").flatMap(t => Maybe.fromOption(t.trim.toDoubleOption))
                .flatMap(Rest.retryAfterSeconds)
            Answer(headers.get("X-RateLimit-Bucket").filter(_.nonEmpty), remaining.map(_.toInt), resetAfter)
        end of
    end Answer

    final case class Route(bucket: String, until: Instant)
    final case class State(remaining: Int, reset: Instant)

    /** The limits of a client, with a global limit of `globalRate` calls a second, closed with the enclosing `Scope`. */
    def init(globalRate: Int)(using Frame): RateLimits < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(globalRate))(_.close)

    /** The limits of a client whose owner closes them. */
    def initUnscoped(globalRate: Int)(using Frame): RateLimits < Sync =
        for
            routes  <- AtomicRef.init(Map.empty[Key, Route])
            buckets <- AtomicRef.init(Map.empty[(String, String), State])
            global  <- Meter.initRateLimiterUnscoped(globalRate, 1.second, reentrant = false)
        yield new RateLimits(routes, buckets, global)

    /** The top-level resource a path starts with: a channel, a guild, or a webhook or interaction with its token, as Discord keys its
      * buckets ("non-inclusive of top-level resources in the path"); none for any other route.
      */
    def resourceOf(path: String): String =
        path.split('/').toList.filter(_.nonEmpty) match
            case "channels" :: id :: _              => s"channels/$id"
            case "guilds" :: id :: _                => s"guilds/$id"
            case "webhooks" :: id :: token :: _     => s"webhooks/$id/$token"
            case "webhooks" :: id :: Nil            => s"webhooks/$id"
            case "interactions" :: id :: token :: _ => s"interactions/$id/$token"
            case _                                  => ""

    private def alive[K, V](m: Map[K, V], now: Instant)(until: V => Instant): Map[K, V] = m.filter((_, v) => until(v) > now)

end RateLimits
