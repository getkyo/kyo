package kyo.llm.thoughts.old.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Creative thought fosters innovation and divergent thinking in AI.
      - Combines creative problem solving with design thinking.
      - Encourages lateral thinking and ideation for novel concepts.
      - Includes 'Elaborate' fields for in-depth explanations, enriching creativity.
    """
)
case class Creative(
    `Reflect on the user's intent`: String,
    `Apply lateral thinking for new perspectives`: String,
    `Elaborate on new perspectives`: String,
    `Consider alternative solutions`: String,
    `Create innovative approaches`: String,
    `Elaborate on innovative solutions`: String
)
