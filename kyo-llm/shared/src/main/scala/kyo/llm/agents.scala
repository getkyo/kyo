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
import zio.schema.{Schema => ZSchema}
import zio.schema.codec.JsonCodec
import scala.annotation.implicitNotFound
import kyo.llm.listeners.Listeners
import thoughts.Repair
import kyo.llm.thoughts._
import kyo.llm.json.Schema
import zio.schema.TypeId
import zio.schema.FieldSet
import zio.schema.Schema.Field
import zio.Chunk
import zio.schema.validation.Validation
import scala.collection.immutable.ListMap

package object agents {

  abstract class Agent {

    type In
    type Out

    case class Info(
        name: String,
        description: String
    )(implicit
        val input: Json[In],
        val output: Json[Out]
    )

    def info: Info

    def thoughts: List[Thoughts.Info] = Nil

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

    protected[kyo] def isResult: Boolean = false

    private[kyo] def json: Json[Thoughts.Result[In]] =
      Thoughts.result(thoughts, info.input, isResult)

    private[kyo] def handle(ai: AI, call: Call): Boolean < AIs =
      Agents.disable {
        implicit def s: ZSchema[In] = info.input.zSchema
        Tries.run(json.decode(call.arguments)).map {
          case Failure(ex) =>
            ai.agentMessage(call.id, "Invalid agent input: " + ex).andThen(false)
          case Success(res) =>
            ai.agentMessage(call.id, "Agent processing.").andThen {
              res.handle(ai).andThen {
                Listeners.observe(res.shortActionNarrationToBeShownToTheUser) {
                  AIs.ephemeral {
                    Tries.run(run(ai, res.agentInput).map(info.output.encode)).map {
                      case Failure(ex) =>
                        ai.agentMessage(call.id, "Agent failure: " + ex).andThen(false)
                      case Success(value) =>
                        ai.agentMessage(call.id, value).andThen(true)
                    }
                  }
                }
              }
            }
        }
      }
  }

  object Agents {
    private val local = Locals.init(List.empty[Agent])

    def get: List[Agent] < AIs = local.get

    def enable[T, S](p: Seq[Agent])(v: => T < S): T < (AIs with S) =
      local.get.map { set =>
        local.let(set ++ p)(v)
      }

    def enable[T, S](first: Agent, rest: Agent*)(v: => T < S): T < (AIs with S) =
      enable(first +: rest)(v)

    def disable[T, S](f: T < S): T < (AIs with S) =
      local.let(List.empty)(f)

    private[kyo] def resultAgent[T](_thoughts: List[Thoughts.Info])(
        implicit t: Json[T]
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

            override def isResult = true

            override val thoughts: List[Thoughts.Info] =
              _thoughts

            def run(input: T) =
              ref.set(Some(input)).andThen("Result processed.")
          }
        (agent, ref.get)
      }

    private[kyo] def handle(ai: AI, agents: List[Agent], calls: List[Call]): Unit < AIs =
      Seqs.traverse(calls) { call =>
        agents.find(_.info.name == call.function) match {
          case None =>
            ai.agentMessage(call.id, "Agent doesn't exist anymore: " + Json.encode(call))
              .andThen(false)
          case Some(agent) =>
            agent.handle(ai, call)
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
