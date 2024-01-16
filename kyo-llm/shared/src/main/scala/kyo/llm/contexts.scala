package kyo.llm

import kyo._
import zio.schema.{Schema => ZSchema}

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
    reminder: Option[String],
    messages: List[Message],
    thoughts: List[Thoughts.Info]
) {

  def seed(seed: String): Context =
    copy(seed = Some(seed))

  def reminder(reminder: String): Context =
    copy(reminder = Some(reminder))

  def thought(info: Thoughts.Info): Context =
    copy(thoughts = thoughts :+ info)

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

  def add(msg: Message): Context =
    copy(messages = msg :: messages)

  def ++(that: Context): Context =
    copy(messages = that.messages ++ messages, thoughts = that.thoughts ++ thoughts)

  def dump: String = {
    def stringify(s: String): String = {
      if (s.contains('\n') || s.contains('"') || s.contains('\\')) s"p\"\"\"$s\"\"\""
      else s""""$s""""
    }
    val seedStr     = seed.map(s => s"\n  .seed(${stringify(s)})").getOrElse("")
    val reminderStr = reminder.map(s => s"\n  .reminder(${stringify(s)})").getOrElse("")
    val messagesStr = messages.reverse.map {
      case Message.SystemMessage(content, _) =>
        s"\n  .systemMessage(${stringify(content)})"
      case Message.UserMessage(content, imageUrls, _) =>
        val imageUrlsStr = imageUrls.map(url => s"\"$url\"").mkString(", ")
        s"\n  .userMessage(${stringify(content)}${if (imageUrls.isEmpty) ""
          else s", List($imageUrlsStr)"})"
      case Message.AssistantMessage(content, calls, _) =>
        val callsStr = calls.map(call =>
          s"Call(CallId(\"${call.id.id}\"), \"${call.function}\", ${stringify(call.arguments)})"
        ).mkString(", ")
        s"\n  .assistantMessage(${stringify(content)}${if (calls.isEmpty) ""
          else s", List($callsStr)"})"
      case Message.ToolMessage(callId, content, _) =>
        s"\n  .toolMessage(CallId(\"${callId.id}\"), ${stringify(content)})"
    }.mkString
    s"Context.empty$seedStr$reminderStr$messagesStr"
  }
}

object Context {
  val empty = Context(None, None, Nil, Nil)
}
