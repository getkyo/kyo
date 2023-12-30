package kyo.llm.thoughts

import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import kyo._
import kyo.stats.Stats
import kyo.stats.Attributes
import kyo.ios.IOs

object Check {

  private val stats   = Thought.stats.scope("check")
  private val success = stats.initCounter("success")
  private val failure = stats.initCounter("failure")

  case class CheckFailed(ai: AI, thought: Thought, invariant: String, analysis: String)
      extends RuntimeException

  private def observe(parent: Thought, field: String, result: Boolean) = {
    val c = if (result) success else failure
    c.attributes(
        Attributes
          .add("thought", parent.name)
          .add("field", field)
    ).inc
  }

  private def warn(
      ai: AI,
      parent: Thought,
      field: String,
      invariant: String,
      analysis: String = "Plase reason about the failure and fix any mistakes.",
      repair: Option[Repair] = None
  ): Unit < AIs =
    ai.systemMessage(
        p"""
          Thought Invariant Failure
          =========================
          Thought: ${parent.name}
          Field: $field
          Description: $invariant
          Analysis: $analysis
          ${repair.map(pprint(_).plainText).map("Repair: " + _).getOrElse("")}
        """
    )

  case class Info(result: Boolean) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, result)
  }

  object Info {
    implicit val schema: ZSchema[Info] =
      ZSchema.primitive[Boolean].transform(Info(_), _.result)
  }

  case class Warn[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, `Invariant holds`).andThen {
        warn(ai, parent, field, `Invariant check description`)
      }
  }

  case class Fail[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant check analysis`: String,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, `Invariant holds`).andThen {
        warn(
            ai,
            parent,
            field,
            `Invariant check description`,
            `Invariant check analysis`
        ).andThen {
          IOs.fail(CheckFailed(
              ai,
              parent,
              `Invariant check description`,
              `Invariant check analysis`
          ))
        }
      }
  }

  case class SelfRepair[Invarant <: String](
      `Invariant check description`: Invarant,
      `Invariant check analysis`: String,
      `Invariant holds`: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, `Invariant holds`).andThen {
        AIs.ephemeral {
          warn(
              ai,
              parent,
              field,
              `Invariant check description`,
              `Invariant check analysis`
          ).andThen {
            ai.gen[Repair]("Provide a repair for the last failed thought invariant.")
          }
        }.map { repair =>
          warn(
              ai,
              parent,
              field,
              `Invariant check description`,
              `Invariant check analysis`,
              Some(repair)
          )
        }
      }
  }
}
