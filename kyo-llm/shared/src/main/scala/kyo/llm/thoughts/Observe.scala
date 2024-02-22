package kyo.llm.thoughts

import kyo.llm.*
import zio.schema.{Schema as ZSchema}
import kyo.*

import kyo.stats.*

object Observe:

    private val stats = Thoughts.stats.scope("observe")
    private val count = stats.initCounter("count")
    private val sum   = stats.initCounter("sum")
    private val hist  = stats.initHistogram("distribution")

    private def add(counter: Counter, parent: Thought, field: String, v: Long) =
        counter.attributes(
            Attributes
                .add("thought", parent.name)
                .add("field", field)
        ).add(v)

    case class Count[T](v: T) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            add(count, parent, field, 1)

    object Count:
        given schema[T](using s: ZSchema[T]): ZSchema[Count[T]] =
            s.transform(Count(_), _.v)

    case class Sum(v: Int) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            add(sum, parent, field, v)

    object Sum:
        given schema: ZSchema[Sum] =
            ZSchema.primitive[Int].transform(Sum(_), _.v)

    case class Distribution(v: Double) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            hist.attributes(
                Attributes
                    .add("thought", parent.name)
                    .add("field", field)
            ).observe(v)
    end Distribution

    object Distribution:
        given schema: ZSchema[Distribution] =
            ZSchema.primitive[Double].transform(Distribution(_), _.v)

    object Log:

        private def log(f: String => Unit < IOs, parent: Thought, field: String, s: String) =
            if s.nonEmpty then
                f(s"(thought=${parent.name}, field=$field): $s")
            else
                (
            )

        case class Debug(s: String) extends Thought:
            override def eval(parent: Thought, field: String, ai: AI) =
                log(Logs.debug(_), parent, field, s)

        object Debug:
            given schema: ZSchema[Debug] =
                ZSchema.primitive[String].transform(Debug(_), _.s)

        case class Info(s: String) extends Thought:
            override def eval(parent: Thought, field: String, ai: AI) =
                log(Logs.info(_), parent, field, s)

        object Info:
            given schema: ZSchema[Info] =
                ZSchema.primitive[String].transform(Info(_), _.s)

        case class Warn(s: String) extends Thought:
            override def eval(parent: Thought, field: String, ai: AI) =
                log(Logs.warn(_), parent, field, s)

        object Warn:
            given schema: ZSchema[Warn] =
                ZSchema.primitive[String].transform(Warn(_), _.s)

        case class Error(s: String) extends Thought:
            override def eval(parent: Thought, field: String, ai: AI) =
                log(Logs.error(_), parent, field, s)

        object Error:
            given schema: ZSchema[Error] =
                ZSchema.primitive[String].transform(Error(_), _.s)
    end Log
end Observe
