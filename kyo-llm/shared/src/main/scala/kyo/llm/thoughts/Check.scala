package kyo.llm.thoughts

import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import kyo._
import kyo.stats.Stats
import kyo.stats.Attributes
import kyo.ios.IOs

object Check {

  private val stats   = Thoughts.stats.scope("check")
  private val success = stats.initCounter("success")
  private val failure = stats.initCounter("failure")

  case class CheckFailed(
      thought: Thought,
      field: String,
      analysis: String
  ) extends RuntimeException {
    override def toString =
      p"""
        Thought Invariant Failure
        =========================
        Thought: ${thought.name}
        Field: $field
        Analysis: $analysis
        **Please take all corrective measures to avoid another failure**
      """
  }

  case class Info(
      `The outer field name is an invariant description`: Boolean,
      `Analyze if the invariant has been violated`: String,
      invariantViolated: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, !invariantViolated)
  }

  case class Warn(
      `The outer field name is an invariant description`: Boolean,
      `Analyze if the invariant has been violated`: String,
      invariantViolated: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, !invariantViolated).andThen {
        if (!invariantViolated) {
          ()
        } else {
          ai.systemMessage(
              CheckFailed(
                  parent,
                  field,
                  `Analyze if the invariant has been violated`
              ).toString
          )
        }
      }
  }

  case class Fail(
      `The outer field name is an invariant description`: Boolean,
      `Analyze if the invariant has been violated`: String,
      invariantViolated: Boolean
  ) extends Thought {
    override def eval(parent: Thought, field: String, ai: AI) =
      observe(parent, field, !invariantViolated).andThen {
        if (!invariantViolated) {
          ()
        } else {
          IOs.fail(CheckFailed(
              parent,
              field,
              `Analyze if the invariant has been violated`
          ))
        }
      }
  }

  private def observe(parent: Thought, field: String, result: Boolean) = {
    val c = if (result) success else failure
    c.attributes(
        Attributes
          .add("thought", parent.name)
          .add("field", field)
    ).inc
  }

}
