package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Ethical thought incorporates principles of ethics and morality.
      - Analyzes ethical dimensions and moral considerations of scenarios.
      - Relevant techniques: Ethical Analysis, Moral Deliberation.
    """
)
case class Ethical(
    `Analyze ethical implications of different scenarios`: String,
    `Deliberate on moral values and principles in context`: String,
    `Consider ethical consequences in decision-making processes`: String
)
