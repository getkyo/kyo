package kyo.chatgpt

import kyo.core._
import kyo.chatgpt.ais._
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import zio.json.DeriveJsonEncoder
import zio.json.JsonEncoder
import zio.json.JsonDecoder
import zio.json.DeriveJsonDecoder
import kyo.chatgpt.contexts.Context.Model
import zio.json.internal.Write

object contexts extends App {

  private val enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

  opaque type Role = String

  object Role {
    val system: Role    = "system"
    val user: Role      = "user"
    val assistant: Role = "assistant"
  }

  case class Message(role: Role, content: String) {
    val tokens = enc.encode(content).size()
  }

  case class Context(
      model: Context.Model,
      messages: List[Message]
  ) {

    def add(role: Role, msg: String): Context =
      Context(model, Message(role, msg) :: messages)

    def ++(that: Context): Context =
      Context(model, messages ++ that.messages)

    private def tokens = messages.map(_.tokens).sum

    private[contexts] def drop(tokens: Int) = {
      def loop(tokens: Int, l: List[Message]): List[Message] = {
        if (tokens <= 0) {
          l
        } else
          l match {
            case Nil    => Nil
            case h :: t => loop(tokens - h.tokens, t)
          }
      }
      copy(messages = loop(tokens, messages))
    }

    private[contexts] def take(tokens: Int) = {
      def loop(tokens: Int, l: List[Message], acc: List[Message]): List[Message] = {
        if (tokens <= 0) {
          acc.reverse
        } else
          l match {
            case Nil    => Nil
            case h :: t => loop(tokens - h.tokens, l, h :: acc)
          }
      }
      copy(messages = loop(tokens, messages, Nil))
    }
  }

  object Context {
    case class Model(name: String, maxTokens: Int)
    val init = Context(Model("gpt-3.5-turbo", 4097), Nil)

    trait Strategy {
      def apply(c: Context): Context > AIs =
        if (c.tokens > c.model.maxTokens * 0.75)
          handle(c, (c.model.maxTokens * 0.5).toInt)
        else
          c
      def handle(c: Context, tokens: Int): Context > AIs
    }
    object Rolling extends Strategy {
      def handle(c: Context, tokens: Int) =
        c.drop(tokens)
    }
    object Summarizing extends Strategy {
      def handle(c: Context, tokens: Int) =
        AIs.ephemeral {
          for {
            ai <- AIs.init(c.take(tokens))
            summary <- ai.ask("Please summarize this entire context so we can use " +
              "it to resume the conversation later. The text should be preferably short " +
              "but without compromising quality.")
          } yield c.copy(messages = Message(Role.assistant, summary) :: c.drop(tokens).messages)
        }
    }
  }

  given JsonEncoder[Message] = DeriveJsonEncoder.gen[Message]
  given JsonEncoder[Context.Model] = new JsonEncoder[Context.Model] {
    def unsafeEncode(a: Model, indent: Option[Int], out: Write): Unit =
      out.write("\"" + a.name + "\"")
  }
  given JsonEncoder[Context] = DeriveJsonEncoder.gen[Context]
  given JsonDecoder[Message] = DeriveJsonDecoder.gen[Message]

}
