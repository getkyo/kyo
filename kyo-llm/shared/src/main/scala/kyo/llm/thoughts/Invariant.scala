package kyo.llm.thoughts

import kyo.llm.ais._
import zio.schema.{Schema => ZSchema}
import kyo._
import kyo.stats.Stats
import kyo.stats.Attributes

import kyo.llm.contexts.Context

object Invariant {

  private val stats   = Thoughts.stats.scope("invariant")
  private val success = stats.initCounter("success")
  private val failure = stats.initCounter("failure")

  case class InvariantViolated(
      state: Context,
      thought: Thought,
      field: String,
      analysis: String
  ) extends RuntimeException

  case class Info(
      `The outer field name is an invariant`: Boolean,
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
              failure(
                  parent,
                  field,
                  `Analyze if the invariant has been violated`
              )
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
          ai.save.map { ctx =>
            IOs.fail(InvariantViolated(
                ctx,
                parent,
                field,
                `Analyze if the invariant has been violated`
            ))
          }
        }
      }
  }

  private def failure(thought: Thought, field: String, analysis: String) = {
    p"""
      Thought Invariant Failure
      =========================
      Thought: ${thought.name}
      Field: $field
      Analysis: $analysis
      **Please take all corrective measures to avoid another failure**
    """
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
