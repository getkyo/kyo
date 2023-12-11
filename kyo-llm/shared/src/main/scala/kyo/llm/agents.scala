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
import kyo.llm.util.JsonSchema
import scala.annotation.implicitNotFound

package object agents {

  abstract class Agent {

    type Input
    type Output

    case class Info(
        name: String,
        description: String
    )(implicit
        val input: ValueSchema[Input],
        val output: ValueSchema[Output]
    ) {
      val schema  = JsonSchema(input.get)
      val decoder = JsonCodec.jsonDecoder(input.get)
      val encoder = JsonCodec.jsonEncoder(output.get)
    }

    val info: Info

    private val local = Locals.init(Option.empty[AI])

    def run(input: Input): Output > AIs

    def run(caller: AI, input: Input): Output > AIs =
      local.let(Some(caller)) {
        run(input)
      }

    protected def caller: AI > AIs =
      local.get.map {
        case Some(ai) => ai
        case None     => AIs.init
      }

    private[kyo] def handle(ai: AI, v: String): String > AIs =
      info.decoder.decodeJson(v) match {
        case Left(error) =>
          AIs.fail(
              "Invalid json input. **Correct any mistakes before retrying**. " + error
          )
        case Right(value) =>
          run(ai, value.value).map { v =>
            info.encoder.encodeJson(Value(v)).toString()
          }
      }
  }

  object Agents {
    private val local = Locals.init(Set.empty[Agent])

    def get: Set[Agent] > AIs = local.get

    def enable[T, S](p: Agent*)(v: => T > S): T > (AIs with S) =
      local.get.map { set =>
        local.let(set ++ p.toSeq)(v)
      }

    def disable[T, S](f: T > S): T > (AIs with S) =
      local.let(Set.empty)(f)

    private[kyo] def resultAgent[T](implicit
        t: ValueSchema[T]
    ): (Agent, Option[T] > AIs) > AIs =
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

    private[kyo] def handle(ai: AI, agents: Set[Agent], calls: List[Call]): Unit > AIs =
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
