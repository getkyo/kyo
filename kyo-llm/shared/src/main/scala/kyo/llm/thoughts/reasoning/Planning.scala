package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Planning thought outlines strategic steps based on analysis.
      - Develops a plan of action based on analysis.
      - Outlines specific measures to address the task.
      - Prepares for potential challenges in implementation.
      - Relevant techniques: Strategic Planning, Action Formulation.
    """
)
case class ActionPlanning(
    `Develop a strategic plan of action based on analysis`: String,
    `Outline specific steps or measures to address the query or problem`: String,
    `Prepare for potential challenges or obstacles in implementing the plan`: String
)
