package kyo.llm.thoughts

import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import kyo._
import kyo.ios._
import kyo.stats._

object Observe {

  private val stats = Thought.stats.scope("observe")
  private val count = stats.initCounter("count")
  private val sum   = stats.initCounter("sum")
  private val hist  = stats.initHistogram("distribution")

  private def add(counter: Counter, parent: Thought, field: String, v: Long) =
    counter.attributes(
        Attributes
          .add("thought", parent.name)
          .add("field", field)
    ).add(v)

  case class Count[T](v: T) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      add(count, parent, field, 1)
  }

  object Count {
    implicit def schema[T](implicit s: ZSchema[T]): ZSchema[Count[T]] =
      s.transform(Count(_), _.v)
  }

  case class Sum(v: Int) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      add(sum, parent, field, v)
  }

  object Sum {
    implicit val schema: ZSchema[Sum] =
      ZSchema.primitive[Int].transform(Sum(_), _.v)
  }

  case class Distribution(v: Double) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      hist.attributes(
          Attributes
            .add("thought", parent.name)
            .add("field", field)
      ).observe(v)
  }

  object Distribution {
    implicit val schema: ZSchema[Distribution] =
      ZSchema.primitive[Double].transform(Distribution(_), _.v)
  }
}
