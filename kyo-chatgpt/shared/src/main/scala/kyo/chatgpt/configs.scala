package kyo.chatgpt

import kyo._
import kyo.ios._
import kyo.locals._

object configs {

  case class Model(name: String, maxTokens: Int)

  object Model {
    val gpt4            = Model("gpt-4", 8192)
    val gpt4_32k        = Model("gpt-4-32k", 32768)
    val gpt35_turbo     = Model("gpt-3.5-turbo", 4097)
    val gpt35_turbo_16k = Model("gpt-3.5-turbo-16k", 16385)
  }

  case class Config(apiKey: String, model: Model) {
    def apiKey(key: String): Config =
      copy(apiKey = key)
    def model(model: Model): Config =
      copy(model = model)
  }

  object Configs {

    private val local = Locals.init[Config] {
      val apiKeyProp = "OPENAI_API_KEY"
      val apiKey =
        Option(System.getenv(apiKeyProp))
          .orElse(Option(System.getProperty(apiKeyProp)))
          .getOrElse("undefined")
      Config(apiKey, Model.gpt4)
    }

    def get: Config > IOs =
      local.get

    def let[T, S](f: Config => Config)(v: T > S): T > (IOs with S) =
      local.get.map { c =>
        local.let(f(c))(v)
      }
  }
}
