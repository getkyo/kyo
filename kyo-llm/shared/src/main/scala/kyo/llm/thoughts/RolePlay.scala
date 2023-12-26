package kyo.llm.thoughts

case class RolePlay[Role <: String](
    `The user has defined the following const string as my role`: Role,
    `Strategy to assume the role`: String
) extends Thought.Opening
