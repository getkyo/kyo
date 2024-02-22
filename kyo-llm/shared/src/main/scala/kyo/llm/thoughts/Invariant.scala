package kyo.llm.thoughts

import kyo.*
import kyo.Stats
import kyo.llm.*
import kyo.llm.contexts.*
import kyo.stats.Attributes
import zio.schema.Schema as ZSchema

object Invariant:

    private val stats   = Thoughts.stats.scope("invariant")
    private val success = stats.initCounter("success")
    private val failure = stats.initCounter("failure")

    case class InvariantViolated(
        state: Context,
        thought: Thought,
        field: String,
        analysis: String
    ) extends RuntimeException(
            "\n*********\n" + List(
                "Thought: " + thought.name,
                "Field: " + field,
                "Analysis: " + analysis
            ).mkString("\n") +
                "\n*********"
        )

    case class Info(
        `The outer field name is an invariant`: Boolean,
        invariantViolated: Boolean
    ) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            observe(parent, field, !invariantViolated)
    end Info

    case class Warn(
        `The outer field name is an invariant description`: Boolean,
        `Analyze if the invariant has been violated`: String,
        invariantViolated: Boolean
    ) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            observe(parent, field, !invariantViolated).andThen {
                if !invariantViolated then
                    ()
                else
                    ai.systemMessage(
                        failure(
                            parent,
                            field,
                            `Analyze if the invariant has been violated`
                        )
                    )
            }
    end Warn

    case class Fail(
        `The outer field name is an invariant description`: Boolean,
        `Analyze if the invariant has been violated`: String,
        invariantViolated: Boolean
    ) extends Thought:
        override def eval(parent: Thought, field: String, ai: AI) =
            observe(parent, field, !invariantViolated).andThen {
                if !invariantViolated then
                    ()
                else
                    ai.save.map { ctx =>
                        IOs.fail(InvariantViolated(
                            ctx,
                            parent,
                            field,
                            `Analyze if the invariant has been violated`
                        ))
                    }
            }
    end Fail

    private def failure(thought: Thought, field: String, analysis: String) =
        p"""
      Thought Invariant Failure
      =========================
      Thought: ${thought.name}
      Field: $field
      Analysis: $analysis
      **Please take all corrective measures to avoid another failure**
    """

    private def observe(parent: Thought, field: String, result: Boolean) =
        val c = if result then success else failure
        c.attributes(
            Attributes
                .add("thought", parent.name)
                .add("field", field)
        ).inc
    end observe
end Invariant
