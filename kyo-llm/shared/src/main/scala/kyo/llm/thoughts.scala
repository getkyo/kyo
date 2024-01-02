package kyo.llm

import kyo._
import kyo.concurrent.fibers.Fibers
import kyo.llm.ais._
import kyo.llm.json.Schema
import kyo.llm.json._
import kyo.llm.thoughts.Thoughts.Collect
import kyo.llm.thoughts._
import kyo.seqs.Seqs
import kyo.stats.Stats
import zio.Chunk
import zio.schema.FieldSet
import zio.schema.Schema.Field
import zio.schema.TypeId
import zio.schema.validation.Validation
import zio.schema.{Schema => ZSchema}

import scala.collection.immutable.ListMap
import scala.reflect.ClassTag
import kyo.consoles.Consoles

package object thoughts {

  abstract class Thought {
    self: Product =>

    def name = productPrefix

    final def eval(ai: AI): Unit < AIs =
      eval(Thoughts.Empty, "", ai)

    def eval(parent: Thought, field: String, ai: AI): Unit < AIs = {
      val thoughts =
        (0 until productArity).flatMap { idx =>
          val name = productElementName(idx)
          productElement(idx) match {
            case Collect(l) =>
              l.map((name, _))
            case v: Thought =>
              (name, v) :: Nil
            case _ =>
              None
          }
        }
      Seqs.traverse(thoughts)((f, t) => t.eval(self, f, ai)).unit
    }
  }

  object Thoughts {

    sealed trait Position
    object Position {
      case object Opening extends Position
      case object Closing extends Position
    }

    case object Empty                    extends Thought
    case class Collect(l: List[Thought]) extends Thought

    private[kyo] val stats = Stats.initScope("thoughts")

    case class Info(
        name: String,
        pos: Position,
        json: Json[_]
    )

    def opening[T <: Thought](implicit j: Json[T], t: ClassTag[T]): Info =
      Info(t.runtimeClass.getSimpleName(), Position.Opening, j)

    def closing[T <: Thought](implicit j: Json[T], t: ClassTag[T]): Info =
      Info(t.runtimeClass.getSimpleName(), Position.Closing, j)

    def result[T](thoughts: List[Info], j: Json[T], full: Boolean): Json[Result[T]] = {
      def schema[T](name: String, l: List[Thoughts.Info]): ZSchema[T] = {
        val fields = l.map { t =>
          import zio.schema.Schema._
          Field.apply[ListMap[String, Any], Any](
              t.name,
              t.json.zSchema.asInstanceOf[ZSchema[Any]],
              Chunk.empty,
              Validation.succeed,
              identity,
              (_, _) => ListMap.empty
          )
        }
        val r = ZSchema.record(TypeId.fromTypeName(name), FieldSet(fields: _*))
        r.transform(
            listMap => Collect(listMap.values.toList.asInstanceOf[List[Thought]]),
            _ => ListMap.empty
        ).asInstanceOf[ZSchema[T]]
      }
      val (opening, closing) = thoughts.partition(_.pos == Position.Opening)
      type Opening
      type Closing
      implicit val o: ZSchema[Opening] = schema("OpeningThoughts", opening)
      implicit val c: ZSchema[Closing] = schema("ClosingThoughts", closing)
      implicit val t: ZSchema[T]       = j.zSchema
      (if (full) {
         Json[Result.Full[Opening, T, Closing]]
       } else {
         Json[Result.Short[Opening, T]]
       }).asInstanceOf[Json[Result[T]]]
    }

    sealed trait Result[T] extends Thought {
      self: Product =>
      val agentInput: T
      val shortActionNarrationToBeShownToTheUser: String
    }

    object Result {

      case class Full[Opening, T, Closing](
          strictlyFollowTheJsonSchema: Boolean,
          `Always use the correct json type from the schema`: Boolean,
          `This is a required thought field for inner-dialog`: Boolean,
          `Strictly follow the required fields including thoughts`: Boolean,
          openingThoughts: Opening,
          `Opening thoughts summary`: String,
          `Only agentInput is visible to the user`: Boolean,
          agentInput: T,
          `agentInput fully satisfies the user's resquest`: Boolean,
          shortActionNarrationToBeShownToTheUser: String,
          closingThoughts: Closing,
          allFieldsAdhereToTheJsonSchema: Boolean
      ) extends Result[T]

      case class Short[Thoughts, T](
          `I won't skip any of the required fields`: Boolean,
          openingThoughts: Thoughts,
          `Opening thoughts summary`: String,
          agentInput: T,
          `agentInput fully satisfies the user's resquest`: Boolean,
          shortActionNarrationToBeShownToTheUser: String,
          allFieldsAdhereToTheJsonSchema: Boolean
      ) extends Result[T]
    }
  }
}
