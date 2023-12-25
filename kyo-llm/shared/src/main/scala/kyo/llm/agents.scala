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
import thoughts.Repair

package object agents {

  abstract class Agent {

    type In
    type Out

    case class Info(
        name: String,
        description: String
    )(implicit
        val input: Json[Agents.Request[In]],
        val output: Json[Out]
    )

    val info: Info

    private val local = Locals.init(Option.empty[AI])

    def run(input: In): Out < AIs

    def run(caller: AI, input: In): Out < AIs =
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

    @desc(
        p"""
        1. This function call is a mechanism that mixes an inner-dialog mechanism
           to enhance the quality of the generated json.
        2. If you encounter field names with text instead of regular identifiers,
           they're meant as thoughts in an inner-dialog mechanism. Leverage them 
           to enhance your generation.
        3. Thought fields with text identifiers aren't free text, strictly follow
           the provided json schema.
      """
    )
    case class Request[T](
        @desc("A short text to provide a status update to the user.")
        actionNarrationToBeShownToTheUser: String,
        strictlyFollowTheJsonSchema: Boolean,
        `Even when the the field name is a text like here`: Boolean,
        @desc("Make sure to generate all required fields by the json schema.")
        `I understand text field names function as an inner-dialog reasoning mechanism`: Boolean,
        `Now I'll proceed to generate a complete function call input`: Boolean,
        inputOfTheFunctionCall: T,
        `The input is complete and follows the json schema`: Boolean,
        @desc("There's a common issue where you output many new line characters after the json.")
        `I will not generate a sequence of several new line charaters`: Boolean
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
            type In  = T
            type Out = String

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
      Seqs.traverse(calls) { call =>
        agents.find(_.info.name == call.function) match {
          case None =>
            ai.agentMessage(call.id, "Agent doesn't exist anymore: " + Json.encode(call))
              .andThen(false)
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
                ai.agentMessage(call.id, result).andThen(true)
              case Failure(ex) =>
                ai.agentMessage(call.id, "Agent failure:" + ex).andThen(false)
            }
        }
      }.map { l =>
        if (!l.forall(identity)) {
          ai.gen[Repair]("One or more agents failed.").unit
        } else {
          ()
        }
      }
  }
}
