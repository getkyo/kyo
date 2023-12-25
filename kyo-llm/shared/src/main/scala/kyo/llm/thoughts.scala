package kyo.llm

import zio.schema.{Schema => ZSchema}

package object thoughts {

  sealed trait Thought {
    type T
    def zSchema: ZSchema[T]
  }

  object Thought {
    case class Reason[V](
        zSchema: ZSchema[V]
    ) extends Thought {
      type T = V
    }

    case class Check[V](
        desc: String,
        f: V => Boolean,
        zSchema: ZSchema[V]
    ) extends Thought {
      type T = V
    }
  }

  object Thoughts {
    import Thought._

    def init[T](implicit s: ZSchema[T]): Thought =
      Reason[T](s)

    def initCheck(desc: String): Thought =
      initCheck[Boolean](desc)(identity)

    def initCheck[T](desc: String)(f: T => Boolean)(implicit s: ZSchema[T]): Thought =
      Check[T](desc, f, s)
  }
}
