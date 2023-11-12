package kyo.chatgpt

import kyo._
import kyo.aspects._
import kyo.chatgpt.embeddings._

object contexts {

  case class Role(name: String) extends AnyVal

  object Role {
    val system: Role    = Role("system")
    val user: Role      = Role("user")
    val assistant: Role = Role("assistant")
    val tool: Role      = Role("tool")
  }

  case class Call(id: String, function: String, arguments: String)

  sealed trait Message {
    def role: Role
    def content: String
  }

  object Message {
    case class SystemMessage(
        content: String,
        role: Role = Role.system
    ) extends Message

    case class UserMessage(
        content: String,
        imageUrls: List[String] = Nil,
        role: Role = Role.user
    ) extends Message

    case class AssistantMessage(
        content: String,
        calls: List[Call] = Nil,
        role: Role = Role.assistant
    ) extends Message

    case class ToolMessage(
        content: String,
        toolCallId: String = "unknown",
        role: Role = Role.tool
    ) extends Message

    def apply(role: Role, content: String): Message =
      role match {
        case Role.system    => SystemMessage(content)
        case Role.user      => UserMessage(content)
        case Role.assistant => AssistantMessage(content)
        case Role.tool      => ToolMessage(content)
        case _              => throw new IllegalArgumentException("invalid role: " + role)
      }
  }

  case class Context(
      seed: Option[String],
      messages: List[Message]
  ) {

    def isEmpty: Boolean =
      seed.isEmpty && messages.isEmpty

    def seed(seed: String) =
      Context(Some(seed), messages)

    def add(msg: Message): Context =
      Context(
          seed,
          msg :: messages
      )

    def ++(that: Context): Context =
      Context(seed, that.messages ++ messages)
  }

  object Contexts {
    val init = Context(None, Nil)

    def init(entries: (Role, String)*): Context = {
      def loop(ctx: Context, entries: List[(Role, String)]): Context =
        entries match {
          case Nil =>
            ctx
          case (role, msg) :: t =>
            loop(ctx.add(Message(role, msg)), t)
        }
      loop(init, entries.toList)
    }
  }
}
