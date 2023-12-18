package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Critical thought emphasizes skepticism and analysis.
      - Engages in critical evaluation of information and assumptions.
      - Relevant techniques: Logical Analysis, Bias Identification.
    """
)
case class Critical(
    `Critically evaluate information and assumptions`: String,
    `Identify and challenge potential biases and fallacies`: String,
    `Reflect on reasoning process to identify areas for improvement`: String
)
