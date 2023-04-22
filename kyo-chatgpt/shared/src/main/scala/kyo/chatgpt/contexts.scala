package kyo.chatgpt

import kyo.core._
import kyo.concurrent.fibers._
import kyo.chatgpt.ais._
import zio.json.DeriveJsonEncoder
import zio.json.JsonEncoder
import zio.json.JsonDecoder
import zio.json.DeriveJsonDecoder
import kyo.chatgpt.contexts.Context.Model
import zio.json.internal.Write

object contexts extends App {

  opaque type Role = String

  object Role {
    val system: Role    = "system"
    val user: Role      = "user"
    val assistant: Role = "assistant"
  }

  case class Message(role: Role, content: String)

  case class Context(
      model: Context.Model,
      messages: List[Message]
  ) {

    def add(role: Role, msg: String): Context =
      Context(model, Message(role, msg) :: messages)

    def ++(that: Context): Context =
      Context(model, messages ++ that.messages)
  }

  object Context {
    case class Model(name: String, maxTokens: Int)
    val init = Context(Model("gpt-3.5-turbo", 4097), Nil)

  }

  given JsonDecoder[Message] = DeriveJsonDecoder.gen[Message]
  given JsonEncoder[Message] = DeriveJsonEncoder.gen[Message]
  given JsonEncoder[Context.Model] = new JsonEncoder[Context.Model] {
    def unsafeEncode(a: Model, indent: Option[Int], out: Write): Unit =
      out.write("\"" + a.name + "\"")
  }
  given JsonEncoder[Context] = DeriveJsonEncoder.gen[Context]
}
