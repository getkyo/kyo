package kyo.chatgpt

import kyo._
import kyo.aspects._
import kyo.chatgpt.ais._
import kyo.chatgpt.embeddings._

object contexts {

  case class Role(name: String) extends AnyVal

  object Role {
    val system: Role    = Role("system")
    val user: Role      = Role("user")
    val assistant: Role = Role("assistant")
    val tool: Role      = Role("tool")
  }

  case class CallId(id: String)

  case class Call(id: CallId, function: String, arguments: String)

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
        callId: CallId,
        content: String,
        role: Role = Role.tool
    ) extends Message

    def apply(role: Role, content: String): Message =
      role match {
        case Role.system    => SystemMessage(content)
        case Role.user      => UserMessage(content)
        case Role.assistant => AssistantMessage(content)
        case _              => throw new IllegalArgumentException("invalid role: " + role)
      }
  }

  case class Context(
      seed: Option[String],
      messages: List[Message]
  ) {

    def systemMessage(content: String): Context =
      add(Message.SystemMessage(content))

    def userMessage(content: String, imageUrls: List[String] = Nil): Context =
      add(Message.UserMessage(content, imageUrls))

    def assistantMessage(content: String, calls: List[Call] = Nil): Context =
      add(Message.AssistantMessage(content, calls))

    def toolMessage(callId: CallId, content: String): Context =
      add(Message.ToolMessage(callId, content))

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

    def dump(ctx: Context): String = {
      val seedStr = ctx.seed.map(s => s".seed(p\"\"\"$s\"\"\")").getOrElse("")
      val messagesStr = ctx.messages.reverse.map {
        case Message.SystemMessage(content, _) =>
          s"\n  .systemMessage(p\"\"\"$content\"\"\")"
        case Message.UserMessage(content, imageUrls, _) =>
          val imageUrlsStr = imageUrls.map(url => s"\"$url\"").mkString(", ")
          s"\n  .userMessage(p\"\"\"$content\"\"\", List($imageUrlsStr))"
        case Message.AssistantMessage(content, calls, _) =>
          val callsStr = calls.map(call =>
            s"Call(CallId(\"${call.id.id}\"), \"${call.function}\", p\"\"\"${call.arguments}\"\"\")"
          ).mkString(", ")
          s"\n  .assistantMessage(p\"\"\"$content\"\"\", List($callsStr))"
        case Message.ToolMessage(callId, content, _) =>
          s"\n  .toolMessage(CallId(\"${callId.id}\"), p\"\"\"$content\"\"\")"
      }.mkString
      s"Contexts.init$seedStr$messagesStr"
    }

    def parse(ctxStr: String): Context = {
      val seedPattern             = """\.seed\(p\"\"\"(.*?)\"\"\"\)""".r
      val userMessagePattern      = """\.userMessage\(p\"\"\"(.*?)\"\"\", List\((.*?)\)\)""".r
      val systemMessagePattern    = """\.systemMessage\(p\"\"\"(.*?)\"\"\"\)""".r
      val assistantMessagePattern = """\.assistantMessage\(p\"\"\"(.*?)\"\"\", List\((.*?)\)\)""".r
      val toolMessagePattern      = """\.toolMessage\(CallId\(\"(.*?)\"\), p\"\"\"(.*?)\"\"\"\)""".r

      val lines = ctxStr.split("\n").map(_.trim).filter(_.nonEmpty)
      lines.foldLeft(Context(None, Nil)) { (context, line) =>
        line match {
          case seedPattern(seed) =>
            context.copy(seed = Some(seed))
          case userMessagePattern(content, imageUrlsStr) =>
            val imageUrls =
              imageUrlsStr.split(", ").map(_.stripPrefix("\"").stripSuffix("\"")).toList
            context.userMessage(content, imageUrls)
          case systemMessagePattern(content) =>
            context.systemMessage(content)
          case assistantMessagePattern(content, callsStr) =>
            val calls = callsStr.split(", ").map { callStr =>
              val parts     = callStr.split(", ")
              val callId    = parts(0).stripPrefix("Call(CallId(\"").stripSuffix("\")")
              val function  = parts(1).stripPrefix("\"").stripSuffix("\"")
              val arguments = parts(2).stripPrefix("p\"\"\"").stripSuffix("\"\"\")")
              Call(CallId(callId), function, arguments)
            }.toList
            context.assistantMessage(content, calls)
          case toolMessagePattern(callId, content) =>
            context.toolMessage(CallId(callId), content)
          case _ =>
            throw new IllegalStateException("Can't parse context string: " + ctxStr)
        }
      }
    }

  }
}
