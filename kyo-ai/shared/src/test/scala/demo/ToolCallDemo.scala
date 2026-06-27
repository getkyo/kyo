package demo

import kyo.*

/** Tool use: give the model a typed function it can call, and let it decide to call it.
  *
  * A support assistant answers "where is my order" by invoking a `lookup_order` tool. The model chooses to
  * call the tool, the eval loop runs it, feeds the typed `Order` back, and the model answers in natural
  * language using data it could not have known on its own. The no-argument `LLM.run` resolves the provider
  * from the environment (the present API key selects it).
  *
  * Demonstrates: Tool.init, AI.enable, LLM.run (auto-config), AI.gen
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.ToolCallDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.ToolCallDemo"
  */
object ToolCallDemo extends KyoApp:

    case class OrderQuery(orderId: Int) derives Schema
    case class Order(id: Int, status: String, etaDays: Int) derives Schema

    // A stand-in order database the model can reach only through the tool.
    val orders = Map(4242 -> Order(4242, "shipped", 2))

    val lookupOrder =
        Tool.init[OrderQuery]("lookup_order", "Look up an order's status and ETA in days by its numeric id") { query =>
            orders.getOrElse(query.orderId, Order(query.orderId, "not found", 0))
        }

    run {
        for
            answer <- LLM.run(AI.enable(lookupOrder)(AI.gen[String]("Where is my order 4242, and when will it arrive?")))
            _      <- Console.printLine(s"assistant: $answer")
        yield ()
    }
end ToolCallDemo
