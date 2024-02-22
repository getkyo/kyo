package kyo.llm.thoughts

import kyo.llm.*

case class Role[Desc <: String](
    `The user has defined the following const string as my role`: Desc,
    `Strategy to assume the role`: String
) extends Thought
