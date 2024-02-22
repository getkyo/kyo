package kyo.llm.thoughts

import kyo.llm.*

case class Purpose[T <: String](
    `My single purpose is`: T,
    `Don't approach any other subject`: Boolean,
    `Any answer must be related to purpose`: Boolean,
    `Do not stop until purpose is fulfilled`: Boolean,
    `Strategy to act only according to purpose`: String
) extends Thought
