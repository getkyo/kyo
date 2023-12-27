package kyo.llm.thoughts

import kyo.llm.ais._

case class Repair(
    `Check for failures from function calls`: String,
    `Identify causes of the failures`: String,
    `Detail corrective measures for improvement`: String
) extends Thought.Opening
