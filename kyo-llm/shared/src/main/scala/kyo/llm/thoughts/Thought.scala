package kyo.llm.thoughts

import kyo._
import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import scala.reflect.ClassTag
import kyo.concurrent.fibers.Fibers
import kyo.stats.Stats

abstract class Thought {
  self: Product =>

  final def handle(path: List[String], ai: AI): Unit < AIs = {
    val npath = productPrefix :: path
    val thoughts =
      eval(npath, ai).andThen {
        (0 until productArity).flatMap { idx =>
          productElement(idx) match {
            case v: Thought =>
              Some((productElementName(idx), v))
            case _ =>
              None
          }
        }
      }
    AIs.parallelTraverse(thoughts)((f, t) => t.eval(f :: npath, ai)).unit
  }

  def eval(path: List[String], ai: AI): Unit < AIs = ()
}

object Thought {

  val stats = Stats.initScope("thoughts")

  abstract class Closing extends Thought {
    self: Product =>
  }

  sealed trait Info {
    type Thought
    def name: String
    def opening: Boolean
    def zSchema: ZSchema[Thought]
  }

  def info[T <: Thought](implicit j: Json[T], t: ClassTag[T]): Info =
    new Info {
      type Thought = T
      val name    = t.runtimeClass.getSimpleName()
      val opening = !classOf[Closing].isAssignableFrom(t.runtimeClass)
      val zSchema = j.zSchema
    }
}
