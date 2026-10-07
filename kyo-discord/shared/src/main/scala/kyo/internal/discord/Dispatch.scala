package kyo.internal.discord

import kyo.*

/** The receive loop: each event of a session handled on its own fiber, with the client provided.
  *
  * A handler's typed failure ends the loop with it and interrupts the other handlers. A panic is logged at error with the event's
  * type and sequence, never its payload, and the loop goes on: a dispatch is not redelivered, so going on cannot spin. An
  * interaction's handler is bounded by `interactionDeadline` from the frame's arrival and its answer is posted as the callback; past
  * the deadline the handler is interrupted and nothing is posted, since Discord invalidates the token after three seconds.
  *
  * Each handler runs isolated with the state its effects `S` had when the loop started, and what it does to that state is dropped:
  * handlers run concurrently, so there is no order in which their changes could be joined.
  */
private[kyo] object Dispatch:

    def run[E, S](using
        isolate: Isolate[S, Abort[E] & Async, S]
    )(
        discord: Discord,
        session: Session,
        handler: [A] => Discord.Event[A] => A < (Async & Abort[E | Discord.Event.Decline] & Env[Discord] & S)
    )(using Frame): Unit < (Async & Abort[Gateway.Failure | E] & S) =
        isolate.capture { state =>
            // Nothing a turn waits on is raced or interrupted: interrupting a take, or a wait on one, can drop what it was handed. A
            // handler's failure is kept, then wakes the turn it finds; a turn is published before it reads the failure, so one of the
            // two always sees the other.
            AtomicRef.init(Maybe.empty[E]).map { failure =>
                AtomicRef.init(Maybe.empty[Fiber.Promise[Taken[E], Any]]).map { turn =>
                    // Each running handler's interruption, by an id of its own, so the loop's end interrupts those still running.
                    AtomicRef.init(Map.empty[Long, Unit < Sync]).map { running =>
                        AtomicLong.init.map { ids =>
                            def fail(e: E): Unit < Sync =
                                failure.compareAndSet(Absent, Present(e)).andThen(turn.get.map {
                                    case Present(waiting) => waiting.completeDiscard(Result.succeed(Taken.HandlerFailure(e)))
                                    case Absent           => Kyo.unit
                                })
                            def start(delivery: Gateway.Delivery): Unit < Sync =
                                ids.incrementAndGet.map { id =>
                                    Fiber.initUnscoped(isolate.isolate(state, Discord.run(discord)(handle(delivery, handler)))).map {
                                        fiber =>
                                            running.updateAndGet(_.updated(id, fiber.interrupt.unit)).andThen {
                                                fiber.onComplete { result =>
                                                    running.updateAndGet(_ - id).andThen {
                                                        result match
                                                            case Result.Failure(e)            => fail(e)
                                                            case Result.Panic(_: Interrupted) => Kyo.unit
                                                            case Result.Panic(t)              =>
                                                                Log.error(s"Discord: the handler panicked on ${describe(delivery)}.", t)
                                                            case Result.Success(_) => Kyo.unit
                                                    }
                                                }
                                            }
                                    }
                                }
                            // The take in flight stays in the session until its event is handled, so a take left when this loop ends
                            // hands its event to the next receive on a held session instead of dropping it.
                            val take: Fiber[Gateway.Delivery, Abort[Closed]] < Sync =
                                session.pendingTake.get.map {
                                    case Present(pending) => pending
                                    case Absent           =>
                                        Fiber.initUnscoped(session.events.take).map { started =>
                                            session.pendingTake.set(Present(started)).andThen(started)
                                        }
                                }
                            val next: Taken[E] < Async =
                                take.map { pending =>
                                    Fiber.Promise.init[Taken[E], Any].map { waiting =>
                                        turn.set(Present(waiting)).andThen(failure.get).map {
                                            case Present(e) => Taken.HandlerFailure(e)
                                            case Absent     =>
                                                pending.onComplete(_.foldError(
                                                    _.map(delivery =>
                                                        waiting.completeDiscard(Result.succeed(Taken.Event(Result.succeed(delivery))))
                                                    ),
                                                    error => waiting.completeDiscard(Result.succeed(Taken.Event(error)))
                                                )).andThen(waiting.get)
                                        }
                                    }
                                }
                            def step(taken: Taken[E]): Loop.Outcome[Unit, Unit] < (Async & Abort[Gateway.Failure | E]) =
                                taken match
                                    case Taken.HandlerFailure(e) => Abort.fail(e)
                                    case Taken.Event(event)      =>
                                        session.pendingTake.set(Absent).andThen {
                                            event match
                                                case Result.Success(delivery) => start(delivery).andThen(Loop.continue)
                                                // The session ended: cleanly, or with its failure.
                                                case Result.Failure(_) => session.ended.get.andThen(Loop.done(()))
                                                case Result.Panic(t)   => Abort.panic(t)
                                        }
                            val interruptAll = running.get.map(interrupts => Kyo.foreachDiscard(interrupts.values)(identity))
                            Sync.ensure(interruptAll)(Loop.foreach(next.map(step)))
                        }
                    }
                }
            }
        }
    end run

    /** What a turn of the loop waited on: the session's next event, as its take completed, or a handler's typed failure. */
    private enum Taken[+E]:
        case Event(event: Result[Closed, Gateway.Delivery])
        case HandlerFailure(failure: E)

    /** One event: a dispatch's handler, or an interaction's handler raced against its deadline and its answer posted. */
    private def handle[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(
        delivery: Gateway.Delivery,
        handler: [A] => Discord.Event[A] => A < (Async & Abort[E | Discord.Event.Decline] & Env[Discord] & S)
    )(using Frame): Unit < (Async & Abort[E] & Env[Discord] & S) =
        import Discord.Event
        delivery.event match
            case e: Event.InteractionEvent[?] =>
                Env.use[Discord] { discord =>
                    val pending = interaction(e, handler)
                    within(discord, delivery.arrivedAt, pending).map {
                        case Absent          => Kyo.unit
                        case Present(answer) =>
                            pending.ref match
                                case Absent      => Log.warn(s"Discord: interaction (${pending.kind}) carries no token to answer with.")
                                case Present(to) =>
                                    Abort.run[DiscordException](Discord.run(discord)(Rest.callback(to, answer))).map {
                                        case Result.Success(_)                                                    => Kyo.unit
                                        case Result.Failure(e: DiscordOtherApiException) if e.code.value == 10062 =>
                                            late(discord, pending)
                                        case Result.Failure(e) =>
                                            Log.warn(
                                                s"Discord: the answer to interaction ${pending.id} (${pending.kind}) failed: ${e.getClass.getSimpleName}."
                                            )
                                        case Result.Panic(t) => Abort.panic(t)
                                    }
                    }
                }
            case e: (Event.Ready | Event.Resumed.type | Event.MessageCreated | Event.MessageUpdated | Event.MessageDeleted |
                    Event.ReactionAdded | Event.ReactionRemoved | Event.ThreadCreated | Event.ThreadUpdated | Event.ThreadDeleted |
                    Event.ChannelCreated | Event.ChannelUpdated | Event.ChannelDeleted | Event.GuildCreated | Event.GuildDeleted |
                    Event.MemberJoined | Event.MemberLeft | Event.TypingStarted | Event.Unknown) =>
                // A dispatch takes no answer, so declining one is answering it.
                Abort.run[Event.Decline](handler(e)).unit
        end match
    end handle

    /** An interaction's kind, the ref its answer is addressed to, and the handler's answer, not yet run. */
    final case class Pending[-S](kind: String, ref: Maybe[Discord.Interaction.Ref], answer: Discord.InteractionResponse < S):
        def id: String = ref.fold("unknown")(r => WireField.renderSnowflake(r.id.value))

    /** The pending answer to `event`. Without the shared fields an unknown kind has no ref; the handler still sees it. */
    def interaction[E, S](
        event: Discord.Event.InteractionEvent[?],
        handler: [A] => Discord.Event[A] => A < (Async & Abort[E | Discord.Event.Decline] & Env[Discord] & S)
    )(using Frame): Pending[Async & Abort[E | Discord.Event.Decline] & Env[Discord] & S] =
        import Discord.Event
        event match
            case e: Event.Command            => Pending("command", Present(e.interaction.ref), handler(e))
            case e: Event.Component          => Pending("component", Present(e.interaction.ref), handler(e))
            case e: Event.Autocomplete       => Pending("autocomplete", Present(e.interaction.ref), handler(e))
            case e: Event.ModalSubmit        => Pending("modal submit", Present(e.interaction.ref), handler(e))
            case e: Event.UnknownInteraction => Pending(s"type ${e.`type`}", e.interaction.toMaybe.map(_.ref), handler(e))
        end match
    end interaction

    /** Runs the pending answer against what is left of `interactionDeadline` from `arrivedAt`, its timer armed before the answer
      * starts. Past the deadline the answer is interrupted; past the deadline or declined, a warn record names the interaction and its
      * kind and the result is absent.
      */
    def within[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(discord: Discord, arrivedAt: Instant, pending: Pending[Async & Abort[E | Discord.Event.Decline] & Env[Discord] & S])(using
        Frame
    ): Maybe[Discord.InteractionResponse] < (Async & Abort[E] & S) =
        val answering: Outcome < (Async & Abort[E] & S) =
            Abort.run[Discord.Event.Decline](Discord.run(discord)(pending.answer)).map {
                case Result.Success(answer) => Outcome.Answered(answer)
                case Result.Failure(_)      => Outcome.Declined
                case Result.Panic(t)        => Abort.panic(t)
            }
        Clock.now.map { now =>
            Gateway.timer((arrivedAt + discord.config.interactionDeadline).minusOrZero(now)).map { timer =>
                Async.raceFirst(answering, timer.get.andThen(Outcome.Late)).map {
                    case Outcome.Answered(answer) => Present(answer)
                    case Outcome.Declined         =>
                        Log.warn(
                            s"Discord: interaction ${pending.id} (${pending.kind}) was declined, so it goes unanswered."
                        ).andThen(Absent)
                    case Outcome.Late => late(discord, pending).andThen(Absent)
                }
            }
        }
    end within

    private enum Outcome derives CanEqual:
        case Answered(answer: Discord.InteractionResponse)
        case Declined
        case Late
    end Outcome

    /** The record of an interaction Discord no longer takes an answer to: past the deadline, or answered 404 with 10062. */
    private def late(discord: Discord, pending: Pending[Nothing])(using Frame): Unit < Sync =
        Log.warn(
            s"Discord: interaction ${pending.id} (${pending.kind}) was not answered within ${discord.config.interactionDeadline.show}."
        )

    private def describe(delivery: Gateway.Delivery): String =
        s"${delivery.event.getClass.getSimpleName.stripSuffix("$")}${delivery.seq.fold("")(s => s" (sequence $s)")}"

end Dispatch
