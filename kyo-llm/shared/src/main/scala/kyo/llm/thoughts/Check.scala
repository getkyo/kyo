package kyo.llm.thoughts

import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import kyo._
import kyo.stats.Stats
import kyo.stats.Attributes
import kyo.ios.IOs

object Check {

  private val stats   = Thought.stats.scope("checks")
  private val success = stats.initCounter("success")
  private val failure = stats.initCounter("failure")

  case class CheckFailed(path: List[String], invariant: String, analysis: String)
      extends RuntimeException

  private def observe(path: List[String], result: Boolean) = {
    val c = if (result) success else failure
    c.attributes(Attributes.of("thought", path.last)).inc
  }

  private def warn(ai: AI, path: List[String], invariant: String): Unit < AIs =
    ai.systemMessage(
        p"""
          Thought Invariant Failure
          =========================
          Description: $invariant
          Path: ${path.map(v => s"`$v`").mkString(".")}
          Plase analyze and fix any mistakes.
        """
    )

  case class Info(result: Boolean) extends Thought {
    override def eval(path: List[String], ai: AI) =
      observe(path, result)
  }

  object Info {
    implicit val schema: ZSchema[Info] =
      ZSchema.primitive[Boolean].transform(Info(_), _.result)
  }

  case class Warn[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(path: List[String], ai: AI) =
      observe(path, `Invariant holds`).andThen {
        warn(ai, path, `Invariant check description`)
      }
  }

  case class Fail[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant check analysis`: String,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(path: List[String], ai: AI) =
      observe(path, `Invariant holds`).andThen {
        warn(ai, path, `Invariant check description`).andThen {
          IOs.fail(CheckFailed(path, `Invariant check description`, `Invariant check analysis`))
        }
      }
  }

  case class SelfRepair[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant check analysis`: String,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(path: List[String], ai: AI) =
      observe(path, `Invariant holds`).andThen {
        AIs.ephemeral {
          warn(ai, path, `Invariant check description`).andThen {
            ai.gen[Repair]("Provide a repair for the failed thought invariant.")
          }
        }.map { repair =>
          ai.systemMessage(
              p"""
              Thought Invariant Repair
              ========================
              Description: ${`Invariant check description`}
              Path: ${path.map(v => s"`$v`").mkString(".")}
              Inferred Repair: ${pprint(repair)}
            """
          )
        }
      }
  }
}
