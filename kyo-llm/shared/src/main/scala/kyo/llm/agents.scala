package kyo.llm

import kyo._
import kyo.ios._
import kyo.seqs._
import kyo.tries._
import kyo.locals._
import kyo.llm.ais._
import scala.util._
import kyo.llm.contexts._
import kyo.concurrent.atomics._
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import scala.annotation.implicitNotFound
import kyo.llm.listeners.Listeners

package object agents {

  abstract class Agent {

    type Input
    type Output

    case class Info(
        name: String,
        description: String
    )(implicit
        val input: Json[Agents.Request[Input]],
        val output: Json[Output]
    )

    val info: Info

    private val local = Locals.init(Option.empty[AI])

    def run(input: Input): Output < AIs

    def run(caller: AI, input: Input): Output < AIs =
      local.let(Some(caller)) {
        run(input)
      }

    protected def caller: AI < AIs =
      local.get.map {
        case Some(ai) => ai
        case None     => AIs.init
      }

    private[kyo] def handle(ai: AI, v: String): String < AIs =
      info.input.decode(v).map { req =>
        Listeners.observe(req.actionNarrationToBeShownToTheUser) {
          run(ai, req.inputOfTheFunctionCall).map(info.output.encode)
        }
      }

  }

  object Agents {
    private val local = Locals.init(Set.empty[Agent])

    case class Request[T](
        @desc("A short text to provide a status update to the user. " +
          "Note that this field is new and might not be in your previous executions.")
        actionNarrationToBeShownToTheUser: String,
        inputOfTheFunctionCall: T
    )

    def get: Set[Agent] < AIs = local.get

    def enable[T, S](p: Seq[Agent])(v: => T < S): T < (AIs with S) =
      local.get.map { set =>
        local.let(set ++ p)(v)
      }

    def enable[T, S](first: Agent, rest: Agent*)(v: => T < S): T < (AIs with S) =
      enable(first +: rest)(v)

    def disable[T, S](f: T < S): T < (AIs with S) =
      local.let(Set.empty)(f)

    private[kyo] def resultAgent[T](
        implicit t: Json[Request[T]]
    ): (Agent, Option[T] < AIs) < AIs =
      Atomics.initRef(Option.empty[T]).map { ref =>
        val agent =
          new Agent {
            type Input  = T
            type Output = String

            val info = Info(
                "resultAgent",
                "Call this agent with the result."
            )

            def run(input: T) =
              ref.set(Some(input)).andThen("Result processed.")
          }
        (agent, ref.get)
      }

    private[kyo] def handle(ai: AI, agents: Set[Agent], calls: List[Call]): Unit < AIs =
      Seqs.traverseUnit(calls) { call =>
        agents.find(_.info.name == call.function) match {
          case None =>
            ai.agentMessage(call.id, "Agent not found: " + call)
          case Some(agent) =>
            AIs.ephemeral {
              Agents.disable {
                Tries.run[String, AIs] {
                  ai.agentMessage(
                      call.id,
                      p"""
                        Entering the agent execution flow. Further interactions 
                        are automated and indirectly initiated by a human.
                      """
                  ).andThen {
                    agent.handle(ai, call.arguments)
                  }
                }
              }
            }.map {
              case Success(result) =>
                ai.agentMessage(call.id, result)
              case Failure(ex) =>
                ai.agentMessage(call.id, "Failure:" + ex)
            }
        }
      }
  }
}
