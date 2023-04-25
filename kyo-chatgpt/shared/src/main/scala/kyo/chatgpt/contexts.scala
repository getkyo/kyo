package kyo.chatgpt

import kyo.core._
import kyo.chatgpt.embeddings._
import kyo.chatgpt.ais._
import kyo.aspects._

object contexts {

  opaque type Role = String

  extension (r: Role) {
    def name: String = r
  }

  object Role {
    val system: Role    = "system"
    val user: Role      = "user"
    val assistant: Role = "assistant"
  }

  case class Message(
      role: Role,
      content: String,
      embedding: Embedding
  )

  case class Model(name: String, maxTokens: Int, maxMessageSize: Int)

  case class Context(
      model: Model,
      messages: List[Message],
      tokens: Int
  ) {

    def add(role: Role, msg: String): Context > AIs =
      Embeddings(msg).map { embedding =>
        if (embedding.tokens >= model.maxTokens / 2) {
          AIs.fail(s"Message too long: ${embedding.tokens} > ${model.maxTokens / 2}")
        } else {
          Context(
              model,
              Message(role, msg, embedding) :: messages,
              tokens + embedding.tokens
          )
        }
      }

    def ++(that: Context): Context =
      Context(model, that.messages ++ messages, tokens + that.tokens)

    def partition(tokens: Int): (Context, Context) =
      def loop(messages: List[Message], count: Int, acc: List[Message]): (Context, Context) =
        messages match {
          case Nil =>
            (Context(model, acc.reverse, count), Context(model, Nil, 0))
          case head :: tail =>
            if (count + head.embedding.tokens > tokens) {
              (Context(model, acc.reverse, count), Context(model, messages, this.tokens - count))
            } else {
              loop(tail, count + head.embedding.tokens, head :: acc)
            }
        }
      loop(messages, 0, Nil)

    def compact: Context > AIs =
      if (tokens >= model.maxTokens) {
        AIs.iso(Contexts.Strategy.aspect(this)(identity))
      } else {
        this
      }
  }

  object Contexts {
    val init = Context(Model("gpt-3.5-turbo", 4097, 4097), Nil, 0)

    def rolling[T, S](v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(Strategy.aspect.let(Strategy.rolling)(v))

    def summarizing[T, S](low: Double, high: Double)(v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(Strategy.aspect.let(Strategy.summarizing(low, high))(v))

    def summarizing[T, S](v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(Strategy.aspect.let(Strategy.summarizing())(v))

    private[contexts] object Strategy {

      def summarizing(low: Double = 0.25, high: Double = 0.75) =
        new Cut[Context, Context, AIs] {
          def apply[S2, S3](v: Context > S2)(f: Context => Context > (S3 | Aspects)) =
            v.map { ctx =>
              val lt              = (ctx.model.maxTokens * low).toInt
              val ht              = (ctx.model.maxTokens * high).toInt
              val (keep, compact) = ctx.partition(ht - lt)
              aspect.sandbox {
                for {
                  ai <- AIs.init(compact)
                  summary <-
                    ai.ask(
                        "Please summarize this conversation from your perspective so we I can copy and paste your response in our next " +
                          "interaction. Please focus on key new informaiton you've acquired and provide a summary that will " +
                          "allow us to resume this conversation later without losing too much detail.",
                        lt
                    )
                  prefix <- Contexts.init.add(Role.assistant, summary)
                } yield {
                  prefix ++ keep
                }
              }
            }
        }

      val rolling = new Cut[Context, Context, AIs] {
        def apply[S2, S3](v: Context > S2)(f: Context => Context > (S3 | Aspects)) =
          v.map { ctx =>
            def loop(messages: List[Message], tokens: Int, acc: List[Message]): Context =
              messages match {
                case Nil =>
                  Context(ctx.model, acc.reverse, tokens)
                case head :: tail =>
                  if (tokens + head.embedding.tokens > ctx.model.maxTokens) {
                    Context(ctx.model, acc.reverse, tokens)
                  } else {
                    loop(tail, tokens + head.embedding.tokens, head :: acc)
                  }
              }
            loop(ctx.messages, 0, Nil)
          }
      }
      val aspect: Aspect[Context, Context, AIs] =
        Aspects.init[Context, Context, AIs](summarizing().andThen(rolling))
    }

  }
}
