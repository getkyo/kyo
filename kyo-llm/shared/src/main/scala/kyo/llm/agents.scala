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
import kyo.llm.thoughts.Thought
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

    def thoughts: List[Thought.Info] = Nil

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

    private[kyo] def request: Schema = {
      def schema[T](name: String, l: List[Thought.Info]): ZSchema[T] = {
        val fields = l.map { t =>
          import zio.schema.Schema._
          Field[ListMap[String, Any], Any](
            t.name, 
            t.zSchema.asInstanceOf[ZSchema[Any]], 
            Chunk.empty, 
            Validation.succeed, 
            identity, 
            (_, _) => ListMap.empty)
        }
        ZSchema.record(TypeId.fromTypeName(name), FieldSet(fields:_*)).asInstanceOf[ZSchema[T]]
      }
      val (opening, closing) = thoughts.partition(_.opening)
      implicit val o: ZSchema[opening.type] = schema("OpeningThoughts", opening)
      implicit val c: ZSchema[closing.type] = schema("ClosingThoughts", closing)
      implicit val i: ZSchema[In] = info.input.zSchema
      Json.schema[Agents.Request[opening.type, In, closing.type]]
    }

    private[kyo] def handle(ai: AI, v: String): String < AIs =
      implicit def s: ZSchema[In] = info.input.zSchema
      Json.decode[Agents.RequestPayload[In]](v).map { res =>
        Listeners.observe(res.actionNarrationToBeShownToTheUser) {
          run(ai, res.agentInput).map(info.output.encode)
        }
      }
  }

  object Agents {
    private val local = Locals.init(Set.empty[Agent])

    @desc(
        p"""
          1. This function call is a mechanism that mixes an inner-dialog mechanism
             to enhance the quality of the generated data.
          2. If you encounter field names with text instead of regular identifiers,
             they're meant as thoughts in an inner-dialog mechanism. Leverage them 
             to enhance your generation.
          3. Thought fields with text identifiers aren't free text, strictly follow
             the provided json schema.
        """
    )
    case class Request[Opening, T, Closing](
        @desc("A short text to provide a status update to the user.")
        actionNarrationToBeShownToTheUser: String,
        openingThoughts: Request.OpeningThoughts[Opening],
        `agentInput must be complete, it's only thing the user will see`: Boolean,
        @desc("String fields are text, not json. Change the tone as if you're addressing the user. Analyze and leverage the thoughts so far.")
        agentInput: T,
        closingThoughts: Request.ClosingThoughts[Closing]
    )

    object Request {
      case class OpeningThoughts[T](
          strictlyFollowTheJsonSchema: Boolean,
          `Even when the the field name is a text like here`: Boolean,
          @desc("Make sure to generate all required fields by the json schema.")
          `I understand text field names function as an inner-dialog reasoning mechanism`: Boolean,
          additionalOpeningThoughts: T,
          `Elaborate on how I can use the opening thoughts`: String,
          `Do not output json in string fields`: Boolean,
          `Now I'll proceed to generate a complete function call input`: Boolean,
          `Opening thoughts finalized`: Boolean,
          `I'll change the tone as if I'm addressing the user`: Boolean
      )
      case class ClosingThoughts[T](
          `The opening thoughts were considered`: Boolean,
          `The input is complete and follows the json schema`: Boolean,
          additionalClosingThoughts: T,
          @desc("There's a common issue where you output many new line characters after the json.")
          `I will not generate a sequence of several new line charaters`: Boolean
      )
    }

    case class RequestPayload[T](
        actionNarrationToBeShownToTheUser: String,
        agentInput: T
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

    private[kyo] def resultAgent[T](_thoughts: List[Thought.Info])(
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

            override def thoughts: List[Thought.Info] = 
              _thoughts

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
