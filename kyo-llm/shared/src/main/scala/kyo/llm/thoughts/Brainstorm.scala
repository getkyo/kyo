package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    p"""
      The Brainstorm thought fosters innovation and divergent thinking in AI.
      - Combines creative problem solving with design thinking.
      - Encourages lateral thinking and ideation for novel concepts.
    """
)
case class Brainstorm(
    `Reflect on the user's intent`: String,
    `Apply lateral thinking for new perspectives`: String,
    `Elaborate on new perspectives`: String,
    `Consider alternative solutions`: String,
    `Create innovative approaches`: String,
    `Elaborate on innovative solutions`: String
) extends Thought.Opening
