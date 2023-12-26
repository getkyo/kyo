package kyo.llm.thoughts

import kyo._
import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import scala.reflect.ClassTag

sealed abstract class Thought {
  self: Product =>

  class Check(
      name: String,
      description: String,
      f: () => Boolean < AIs
  )

  object Check {
    def apply(name: String, description: String = "")(f: => Boolean < AIs): Check =
      new Check(name, description, () => f)
  }

  def checks: List[Check] = Nil
}

object Thought {

  abstract class Opening extends Thought {
    self: Product =>
  }
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
      val opening = classOf[Opening].isAssignableFrom(t.runtimeClass)
      val zSchema = j.zSchema
    }
}
