package kyo

import Actor.Subject
import Agent.internal.*
import kyo.ai.Config

/** A stateful, actor-backed LLM entity that processes typed messages.
  *
  * `Agent` is an opaque alias over `kyo.Actor`: a persistent entity with a lifecycle that receives typed
  * inputs and replies with typed outputs, combining the LLM generation surface with the actor concurrency
  * model. The primary interaction is `ask`, which sends an input and awaits the reply under
  * `Async & Abort[Closed | Error]` (a closed mailbox surfaces as `Abort[Closed]`, the agent's error as
  * `Abort[Error]`, never a throw).
  *
  * `run` mints one stable `AI` instance for the agent and hands it to the behavior as its `self`: the
  * behavior calls `self.gen` explicitly, and because the actor's parked continuation keeps the `LLM.State`
  * alive, that instance's conversation persists across asks. The agent's enablements (tools, prompts,
  * thoughts, modes) are enabled around the behavior in argument order (via `AI.enable`), then `LLM.run`.
  * `runBehavior` is the lower-level escape hatch for custom actor behaviors.
  *
  * @tparam Error
  *   the error type the agent can produce
  * @tparam In
  *   the input type the agent accepts
  * @tparam Out
  *   the output type the agent returns
  */
opaque type Agent[+Error, In, Out] = Actor[Error, Agent.internal.Message[In, Out], Any]

object Agent:

    opaque type Ref[In, Out] = Subject[Message[In, Out]]

    extension [In, Out](self: Ref[In, Out])
        def ask(in: In)(using Frame): Out < (Async & Abort[Closed]) =
            self.ask(Message(in, _))

    opaque type Context[In, Out] = Actor.Context[Message[In, Out]]

    extension [Error, In, Out](self: Agent[Error, In, Out])
        /** Sends an input to the agent and awaits the reply. */
        def ask(in: In)(using Frame): Out < (Async & Abort[Closed | Error]) =
            self.ask(Message[In, Out](in, _))

        /** Closes the agent's mailbox, preventing it from receiving any new messages. */
        def close(using Frame): Maybe[Seq[In]] < Sync =
            (self: Actor[Error, Message[In, Out], Any]).close.map(_.map(_.map(_.input)))
    end extension

    /** Creates an agent with an explicit config and any mix of enablements (tools, prompts, thoughts, modes).
      *
      * Mints the agent's stable `AI` instance and passes it to the behavior as `self`, then enables the given
      * enablements over a receive-all behavior (via `AI.enable`, in argument order) and runs `LLM`. The
      * `config` is required here (e.g. from a test server); for the env-default `Config`, use the no-config
      * overload. The behavior reaches the agent's conversation through `self.gen`; nothing is ambient.
      */
    def run[In: Tag](using
        Frame
    )[Error, Out: Tag, S](
        using Isolate[S, Sync, Any]
    )(
        config: Config,
        enablements: AI.Enablement[LLM & S]*
    )(
        f: (AI, In) => Out < (Abort[Error | AIGenException] & Context[In, Out] & LLM & Async & S)
    )(
        using
        tPoll: Tag[Poll[Message[In, Out]]],
        tEmit: Tag[Emit[Message[In, Out]]],
        tSubject: Tag[Subject[Message[In, Out]]],
        tMsg: Tag[Message[In, Out]]
    ): Agent[Error, In, Out] < (Scope & Async & S) =
        given Tag[Poll[Message[In, Out]]]    = tPoll
        given Tag[Emit[Message[In, Out]]]    = tEmit
        given Tag[Subject[Message[In, Out]]] = tSubject
        given Tag[Message[In, Out]]          = tMsg
        runBehavior[In](config, enablements*)(ai =>
            Actor.receiveAll[Message[In, Out]](process(_, (in: In) => f(ai, in)))
        )
    end run

    /** Creates an agent with the env-default config and no enablements (ergonomic trailing-lambda form).
      *
      * Allows `Agent.run[T] { (self, input) => ... }` without supplying a config or
      * enablements. To pass them, use the overload with a leading param list:
      * `Agent.run[T](cfg, prompt, tool, thought) { (self, input) => ... }`.
      */
    def run[In: Tag](using
        Frame
    )[Error, Out: Tag, S](
        using Isolate[S, Sync, Any]
    )(
        f: (AI, In) => Out < (Abort[Error | AIGenException] & Context[In, Out] & LLM & Async & S)
    )(
        using
        tPoll: Tag[Poll[Message[In, Out]]],
        tEmit: Tag[Emit[Message[In, Out]]],
        tSubject: Tag[Subject[Message[In, Out]]],
        tMsg: Tag[Message[In, Out]]
    ): Agent[Error, In, Out] < (Scope & Async & S) =
        given Tag[Poll[Message[In, Out]]]    = tPoll
        given Tag[Emit[Message[In, Out]]]    = tEmit
        given Tag[Subject[Message[In, Out]]] = tSubject
        given Tag[Message[In, Out]]          = tMsg
        runBehavior[In](ai =>
            Actor.receiveAll[Message[In, Out]](process(_, (in: In) => f(ai, in)))
        )
    end run

    /** Lower-level: runs a custom actor behavior with LLM, enabling any mix of enablements.
      *
      * Mints the agent's stable `AI` instance and passes it to the behavior builder, then enables the given
      * enablements (via `AI.enable`, in argument order) and calls `LLM.run` around the behavior. The `config`
      * is required here; for the env-default `Config`, use the no-config overload.
      */
    def runBehavior[In: Tag](using
        Frame
    )[Error, Out: Tag, S](
        using Isolate[S, Sync, Any]
    )(
        config: Config,
        enablements: AI.Enablement[LLM & S]*
    )(
        behavior: AI => Any < (Abort[Error | AIGenException] & Context[In, Out] & LLM & Async & S)
    )(
        using
        tPoll: Tag[Poll[Message[In, Out]]],
        tEmit: Tag[Emit[Message[In, Out]]],
        tSubject: Tag[Subject[Message[In, Out]]],
        tMsg: Tag[Message[In, Out]]
    ): Agent[Error, In, Out] < (Scope & Async & S) =
        given Tag[Poll[Message[In, Out]]]    = tPoll
        given Tag[Emit[Message[In, Out]]]    = tEmit
        given Tag[Subject[Message[In, Out]]] = tSubject
        given Tag[Message[In, Out]]          = tMsg
        runImpl[In, Error, Out, S](Present(config)) { ai =>
            behavior(ai).handle(AI.enable(enablements*))
        }
    end runBehavior

    /** Lower-level: runs a custom actor behavior with LLM enabled (ergonomic trailing-lambda form).
      *
      * Allows `Agent.runBehavior[T](behavior)` without supplying a config or enablements.
      * To pass them, use the overload with a leading param list:
      * `Agent.runBehavior[T](cfg, prompt, tool, thought)(behavior)`.
      */
    def runBehavior[In: Tag](using
        Frame
    )[Error, Out: Tag, S](
        using Isolate[S, Sync, Any]
    )(
        behavior: AI => Any < (Abort[Error | AIGenException] & Context[In, Out] & LLM & Async & S)
    )(
        using
        tPoll: Tag[Poll[Message[In, Out]]],
        tEmit: Tag[Emit[Message[In, Out]]],
        tSubject: Tag[Subject[Message[In, Out]]],
        tMsg: Tag[Message[In, Out]]
    ): Agent[Error, In, Out] < (Scope & Async & S) =
        given Tag[Poll[Message[In, Out]]]    = tPoll
        given Tag[Emit[Message[In, Out]]]    = tEmit
        given Tag[Subject[Message[In, Out]]] = tSubject
        given Tag[Message[In, Out]]          = tMsg
        runImpl[In, Error, Out, S](Absent)(behavior)
    end runBehavior

    def receiveAll[In](using
        Frame
    )[Out, S](f: In => Out < S)(
        using Tag[Poll[Message[In, Out]]]
    ): Unit < (Context[In, Out] & S) =
        Actor.receiveAll[Message[In, Out]](process(_, f))

    def receiveMax[In](max: Int)[Out, S](f: In => Out < S)(using Frame, Tag[Poll[Message[In, Out]]]): Unit < (Context[In, Out] & S) =
        Actor.receiveMax(max)(process(_, f))

    def receiveLoop[In](using
        Frame
    )[Out, S](f: In => Loop.Outcome[Out, Unit] < S)(
        using Tag[Poll[Message[In, Out]]]
    ): Unit < (Context[In, Out] & S) =
        Actor.receiveLoop { msg =>
            f(msg.input).map {
                case continue: Loop.Continue[Out] @unchecked =>
                    msg.replyTo.send(continue._1).andThen(Loop.continue)
                case _ =>
                    Loop.done
            }
        }

    def receiveLoop[In](using
        Frame
    )[State, Out, S](state: State)(
        f: (State, In) => Loop.Outcome2[State, Out, Unit] < S
    )(
        using Tag[Poll[Message[In, Out]]]
    ): State < (Context[In, Out] & S) =
        Actor.receiveLoop(state) { (msg, state) =>
            f(state, msg.input).map {
                case continue: Loop.Continue2[State, Out] @unchecked =>
                    msg.replyTo.send(continue._2).andThen(Loop.continue(continue._1))
                case _ =>
                    Loop.done(state)
            }
        }

    def self[In, Out](using Frame, Tag[Subject[Message[In, Out]]]): Ref[In, Out] < Context[In, Out] =
        Actor.self

    def selfWith[In, Out](using
        Frame
    )[B, S](f: Ref[In, Out] => B < S)(
        using Tag[Subject[Message[In, Out]]]
    ): B < (Context[In, Out] & S) =
        Actor.selfWith(f)

    private def runImpl[In: Tag, Error, Out: Tag, S](using
        Isolate[S, Sync, Any]
    )(
        config: Maybe[Config]
    )(
        behavior: AI => Any < (Abort[Error | AIGenException] & Context[In, Out] & LLM & Async & S)
    )(
        using
        Tag[Poll[Message[In, Out]]],
        Tag[Emit[Message[In, Out]]],
        Tag[Subject[Message[In, Out]]],
        Tag[Message[In, Out]],
        Frame
    ): Agent[Error, In, Out] < (Scope & Async & S) =
        // Mint ONE stable instance and pass it to the behavior, which calls self.gen explicitly; the parked
        // actor continuation's LLM.State persists that instance's conversation across asks.
        // LLM.run discharges LLM; Abort[AIGenException] is re-thrown as a panic (AIGenException <: Throwable);
        // Async is consumed by Actor.run.
        val llmRun =
            config match
                case Present(c) => LLM.run(c)(AI.initWith(behavior))
                case Absent     => LLM.run(AI.initWith(behavior))
        Actor.run(Abort.run[AIGenException](llmRun).map(_.getOrThrow))
    end runImpl

    private[kyo] object internal:

        case class Message[In, Out](input: In, replyTo: Subject[Out])

        def process[In, Out, S](msg: Message[In, Out], f: In => Out < S)(using Frame): Unit < (S & Async & Abort[Closed]) =
            f(msg.input).map(msg.replyTo.send)
    end internal
end Agent
