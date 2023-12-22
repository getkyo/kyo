package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Repair thought guides the LLM in addressing and learning from errors.
      - Analyzes causes of past failures.
      - Develops strategies to prevent future errors.
      - Encourages critical evaluation and adaptive learning.
    """
)
case class Repair(
    `Identify causes of past failures`: String,
    `Formulate strategies to prevent future errors`: String,
    `Detail corrective measures for improvement`: String
)
