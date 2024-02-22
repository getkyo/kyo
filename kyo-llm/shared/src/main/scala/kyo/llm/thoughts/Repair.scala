package kyo.llm.thoughts

import kyo.llm.*

case class Repair(
    `Check for failures from tool and system messages`: Boolean,
    `List failure messages`: List[String],
    `Identify causes of the failures`: String,
    `Detail corrective measures for improvement`: String
) extends Thought
