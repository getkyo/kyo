package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
    The Evaluation thought involves critically judging the information processed.
    - Assesses accuracy and reliability.
    - Evaluates relevance and importance.
    - Determines confidence in conclusions.
    - Relevant techniques: Information Validation, Critical Assessment.
    """
)
case class Evaluation(
    `Assess the accuracy and reliability of the synthesized information`: String,
    `Evaluate the relevance and importance of the information to the query`: String,
    `Determine the confidence level in the conclusions reached`: String
)
