package kyo

import kyo.Json.JsonSchema
import kyo.ai.Config
import kyo.ai.Context.*

/** Covers the schema-at-runtime tool surface: `Tool.initDynamic` and the `Tool.fromMcp` bridge.
  *
  * The acceptance property the whole surface exists for is that a tool set is built from what a server
  * says about itself at runtime, with no `Schema[In]` anywhere in the calling code. Several tests below
  * therefore drive a server tool that has NO Scala input type at all: its `ToolMeta` is assembled from a
  * hand-built `JsonSchema` and its handler takes the open `Structure.Value`. That is the in-repo stand-in
  * for a third-party server whose Scala types are not on the classpath, and it is a stricter one, since
  * here those types do not exist to be imported.
  */
class ToolMcpTest extends kyo.test.Test[Any]:

    private val clientInfo = McpInfo("kyo-ai-bridge-test", "0.0.0")
    private val clientCaps = McpCapabilities.Client()

    private def withServer[A, S](handlers: McpHandler[?, ?, ?]*)(f: McpClient => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[McpException]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, handlers*).flatMap { _ =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap(f)
            }
        }

    /** The schema a third-party server would publish: required and optional properties, descriptions. */
    private val weatherSchema: JsonSchema =
        JsonSchema.Obj(
            properties = List(
                "city"  -> JsonSchema.Str(description = Present("the city to look up")),
                "units" -> JsonSchema.Str(description = Present("metric or imperial"))
            ),
            required = List("city")
        )

    /** A server tool with no Scala input type: runtime meta plus an open-valued handler. */
    private def weatherTool(seen: AtomicRef[Chunk[Structure.Value]])(using Frame): McpHandler[?, ?, ?] =
        new McpHandler.ToolMultiHandler[Structure.Value, Nothing](
            McpHandler.ToolMeta(
                name = "lookup_weather",
                description = Present("Look up the weather in a city"),
                inputSchema = weatherSchema,
                outputSchema = Absent,
                annotations = Absent
            ),
            summon[Schema[Structure.Value]],
            input =>
                seen.getAndUpdate(_.append(input)).andThen(
                    McpHandler.ToolOutcome.okWith(
                        content = Chunk(McpContent.text("sunny, 21C")),
                        structuredContent = Present(Structure.Value.Record(Chunk(
                            "summary" -> Structure.Value.Str("sunny"),
                            "celsius" -> Structure.Value.Integer(21)
                        )))
                    )
                ),
            Chunk.empty
        )

    /** Dispatches one model tool call through the eval loop's own handler and returns the tool message. */
    private def dispatch(tool: Tool[?], name: String, arguments: String)(using Frame): String < (Async & Abort[AIGenException]) =
        val callId = CallId("call-1")
        val call   = Call(callId, name, arguments)
        val infos  = tool.infos.asInstanceOf[Chunk[Tool.internal.Info[?, ?, LLM]]]
        LLM.run(
            AI.init.map { ai =>
                ai.updateContext(_.assistantMessage("", Chunk(call)))
                    .andThen(Tool.internal.handle(ai, infos, Chunk(call)))
                    .andThen(ai.context)
            }
        ).map { ctx =>
            ctx.messages.collect { case ToolMessage(id, content) if id == callId => content }.last
        }
    end dispatch

    "initDynamic" - {

        "advertises the supplied schema verbatim, constraints and all" in {
            // A schema recovered through the structural vocabulary would lose `pattern` and the root
            // description; the tool must show the model exactly what it was handed.
            val declared = JsonSchema.Obj(
                properties = List("sql" -> JsonSchema.Str(pattern = Present("^select"), description = Present("a SELECT"))),
                required = List("sql"),
                description = Present("run a query")
            )
            val tool = Tool.initDynamic("run_select", declared)(args => args)
            assert(tool.infos.head.wireInputSchema == declared)
        }

        "hands the run the decoded arguments" in {
            AtomicRef.init(Maybe.empty[Structure.Value]).map { captured =>
                val tool = Tool.initDynamic("echo", weatherSchema) { args =>
                    captured.set(Present(args)).andThen(Structure.Value.Str("done"))
                }
                Scope.run(dispatch(tool, "echo", """{"city":"Paris","units":"metric"}""")).andThen {
                    captured.get.map { seen =>
                        assert(seen == Present(Structure.Value.Record(Chunk(
                            "city"  -> Structure.Value.Str("Paris"),
                            "units" -> Structure.Value.Str("metric")
                        ))))
                    }
                }
            }
        }

        "rejects arguments that do not conform to the declared schema, without running the body" in {
            AtomicInt.init(0).map { runs =>
                val tool = Tool.initDynamic("echo", weatherSchema) { _ =>
                    runs.incrementAndGet.andThen(Structure.Value.Str("done"))
                }
                Scope.run(dispatch(tool, "echo", """{"units":"metric"}""")).map { message =>
                    runs.get.map { count =>
                        assert(count == 0, "a non-conforming call must not reach the body")
                        assert(message.contains("do not conform"), s"the model must see why; got: $message")
                        assert(message.contains("city"), s"the violation must name the field; got: $message")
                    }
                }
            }
        }
    }

    "fromMcp" - {

        "builds one tool per server tool, carrying the server's own name, description and schema" in {
            Scope.run {
                AtomicRef.init(Chunk.empty[Structure.Value]).map { seen =>
                    withServer(weatherTool(seen)) { client =>
                        Tool.fromMcp(client).map { tool =>
                            val infos = tool.infos
                            assert(infos.size == 1, s"expected one bridged tool, got ${infos.size}")
                            val info = infos.head
                            assert(info.name == "lookup_weather")
                            assert(info.description == "Look up the weather in a city")
                            // Verbatim: the model sees what the server published, not a re-derivation.
                            assert(info.wireInputSchema == weatherSchema)
                        }
                    }
                }
            }
        }

        "dispatches the model's arguments to the server and returns its structured result" in {
            Scope.run {
                AtomicRef.init(Chunk.empty[Structure.Value]).map { seen =>
                    withServer(weatherTool(seen)) { client =>
                        Tool.fromMcp(client).map { tool =>
                            dispatch(tool, "lookup_weather", """{"city":"Paris"}""").map { message =>
                                seen.get.map { received =>
                                    assert(
                                        received == Chunk(Structure.Value.Record(Chunk("city" -> Structure.Value.Str("Paris")))),
                                        s"the server must receive the model's arguments; got: $received"
                                    )
                                    assert(message.contains("sunny"), s"the tool result must reach the model; got: $message")
                                    assert(message.contains("21"), s"the structured payload must reach the model; got: $message")
                                }
                            }
                        }
                    }
                }
            }
        }

        "rejects a non-conforming call against the server's runtime schema before it leaves the process" in {
            Scope.run {
                AtomicRef.init(Chunk.empty[Structure.Value]).map { seen =>
                    withServer(weatherTool(seen)) { client =>
                        Tool.fromMcp(client).map { tool =>
                            dispatch(tool, "lookup_weather", """{"units":"metric"}""").map { message =>
                                seen.get.map { received =>
                                    assert(received.isEmpty, s"the server must not be called; got: $received")
                                    assert(message.contains("do not conform"), s"the model must see why; got: $message")
                                }
                            }
                        }
                    }
                }
            }
        }

        "surfaces a server-side tool failure to the model rather than failing the generation" in {
            val failing = new McpHandler.ToolMultiHandler[Structure.Value, Nothing](
                McpHandler.ToolMeta(
                    name = "always_fails",
                    description = Present("fails"),
                    inputSchema = JsonSchema.Obj(List("x" -> JsonSchema.Str()), List("x")),
                    outputSchema = Absent,
                    annotations = Absent
                ),
                summon[Schema[Structure.Value]],
                _ => McpHandler.ToolOutcome.error("the upstream said no"),
                Chunk.empty
            )
            Scope.run {
                withServer(failing) { client =>
                    Tool.fromMcp(client).map { tool =>
                        dispatch(tool, "always_fails", """{"x":"y"}""").map { message =>
                            assert(message.contains("the upstream said no"), s"got: $message")
                        }
                    }
                }
            }
        }

        "bridges every tool the server publishes" in {
            Scope.run {
                AtomicRef.init(Chunk.empty[Structure.Value]).map { seen =>
                    val second = new McpHandler.ToolMultiHandler[Structure.Value, Nothing](
                        McpHandler.ToolMeta(
                            name = "list_cities",
                            description = Present("list known cities"),
                            inputSchema = JsonSchema.Obj(List.empty, List.empty),
                            outputSchema = Absent,
                            annotations = Absent
                        ),
                        summon[Schema[Structure.Value]],
                        _ => McpHandler.ToolOutcome.ok(McpContent.text("Paris, Berlin")),
                        Chunk.empty
                    )
                    withServer(weatherTool(seen), second) { client =>
                        Tool.fromMcp(client).map { tool =>
                            assert(tool.infos.map(_.name).toSet == Set("lookup_weather", "list_cities"))
                        }
                    }
                }
            }
        }
    }

    "an agent completes a generation that calls a bridged tool it has no Scala types for" in {
        // The end-to-end acceptance check: the model is offered the server's tool, calls it, and the
        // result feeds the final answer. Nothing in this block names an input or output type for
        // `lookup_weather`, because none exists.
        TestCompletionServer.run { server =>
            val config = Config.OpenAI.default
                .apiKey("test")
                .model(
                    Config.OpenAI,
                    "gpt-4o",
                    128000,
                    Config.OutputMaximum.Verified(16384),
                    Config.ReasoningEncoding.Unavailable,
                    true,
                    true
                )
                .apiUrl(server.baseUrl)
            AtomicRef.init(Chunk.empty[Structure.Value]).map { seen =>
                withServer(weatherTool(seen)) { client =>
                    Tool.fromMcp(client).map { tools =>
                        val toolCall =
                            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"t1","type":"function","function":{"name":"lookup_weather","arguments":"{\"city\":\"Paris\"}"}}]}}]}"""
                        val answer =
                            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"{\"resultValue\":\"sunny in Paris\"}"}}]}}]}"""
                        server.enqueueBody(toolCall).andThen(server.enqueueBody(answer)).andThen {
                            LLM.run(config)(AI.enable(tools)(AI.gen[String])).map { answer =>
                                seen.get.map { received =>
                                    assert(answer == "sunny in Paris", s"got: $answer")
                                    assert(
                                        received == Chunk(Structure.Value.Record(Chunk("city" -> Structure.Value.Str("Paris")))),
                                        s"the bridged tool must have run against the server; got: $received"
                                    )
                                    server.captured.map { caps =>
                                        val firstRequest = caps.head.body
                                        assert(
                                            firstRequest.contains("lookup_weather"),
                                            "the server's tool must be advertised to the model"
                                        )
                                        assert(
                                            firstRequest.contains("the city to look up"),
                                            s"the server's own property descriptions must reach the model; body: $firstRequest"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end ToolMcpTest
