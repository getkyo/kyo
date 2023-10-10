package kyo.chatgpt

import kyo._
import kyo.chatgpt.embeddings._
import kyo.chatgpt.ais._
import kyo.aspects._

object contexts {

  case class Role(name: String) extends AnyVal

  object Role {
    val system: Role    = Role("system")
    val user: Role      = Role("user")
    val assistant: Role = Role("assistant")
    val function: Role  = Role("function")
  }

  case class Call(function: String, arguments: String)

  case class Message(
      role: Role,
      content: String,
      name: Option[String],
      call: Option[Call]
  )

  case class Model(name: String, maxTokens: Int, maxMessageSize: Int)

  case class Context(
      model: Model,
      messages: List[Message]
  ) {

    def add(
        role: Role,
        msg: String,
        name: Option[String],
        call: Option[Call]
    ): Context > AIs =
      Context(
          model,
          Message(role, msg, name, call) :: messages
      )

    def ++(that: Context): Context =
      Context(model, that.messages ++ messages)
  }

  object Contexts {
    val init = Context(Model("gpt-4", 8192, 8192), Nil)

    def init(entries: (Role, String)*): Context > AIs = {
      def loop(ctx: Context, entries: List[(Role, String)]): Context > AIs =
        entries match {
          case Nil =>
            ctx
          case (role, msg) :: t =>
            ctx.add(role, msg.stripMargin, None, None).map(loop(_, t))
        }
      loop(init, entries.toList)
    }
  }
}
