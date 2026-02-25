package demo

import kyo.*

/** MCP (Model Context Protocol) server implementing Streamable HTTP transport.
  *
  * Implements the 2025-06-18 spec: POST receives JSON-RPC messages, responds with JSON or SSE. GET opens an SSE stream for server-initiated
  * messages.
  */
object McpServer extends KyoApp:

    // --- JSON-RPC types ---
    case class JsonRpcRequest(jsonrpc: String, id: Option[Int], method: String, params: Option[RpcParams]) derives Schema
    case class RpcParams(
        protocolVersion: Option[String],
        capabilities: Option[Capabilities],
        clientInfo: Option[ClientInfo],
        name: Option[String],
        arguments: Option[Map[String, String]]
    ) derives Schema
    case class Capabilities(tools: Option[ToolCapabilities]) derives Schema
    case class ToolCapabilities(listChanged: Option[Boolean]) derives Schema
    case class ClientInfo(name: String, version: String) derives Schema

    case class JsonRpcResponse(jsonrpc: String, id: Option[Int], result: Option[RpcResult], error: Option[RpcError]) derives Schema
    case class RpcError(code: Int, message: String) derives Schema
    case class RpcResult(
        protocolVersion: Option[String],
        capabilities: Option[ServerCapabilities],
        serverInfo: Option[ServerInfo],
        tools: Option[List[ToolDef]],
        content: Option[List[Content]]
    ) derives Schema
    case class ServerCapabilities(tools: Option[ToolCapabilities]) derives Schema
    case class ServerInfo(name: String, version: String) derives Schema
    case class ToolDef(name: String, description: String, inputSchema: InputSchema) derives Schema
    case class InputSchema(`type`: String, properties: Map[String, PropDef], required: Option[List[String]]) derives Schema
    case class PropDef(`type`: String, description: String) derives Schema
    case class Content(`type`: String, text: String) derives Schema

    // --- Weather client (calls Open-Meteo) ---
    case class WeatherResponse(current: CurrentWeather) derives Schema
    case class CurrentWeather(temperature_2m: Double, wind_speed_10m: Double) derives Schema

    def fetchWeather(city: String): String < (Async & Abort[HttpError]) =
        val (lat, lon) = city.toLowerCase match
            case "london"    => (51.51, -0.13)
            case "tokyo"     => (35.68, 139.69)
            case "new york"  => (40.71, -74.01)
            case "paris"     => (48.86, 2.35)
            case "berlin"    => (52.52, 13.41)
            case "sao paulo" => (-23.55, -46.63)
            case _           => (0.0, 0.0)

        val url = s"https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,wind_speed_10m"
        HttpClient.getJson[WeatherResponse](url).map { w =>
            s"$city: ${w.current.temperature_2m}°C, wind ${w.current.wind_speed_10m} km/h"
        }
    end fetchWeather

    // --- Tool definitions ---
    val tools = List(
        ToolDef(
            "weather",
            "Get current weather for a city",
            InputSchema(
                "object",
                Map("city" -> PropDef("string", "City name (e.g. london, tokyo, new york)")),
                Some(List("city"))
            )
        ),
        ToolDef(
            "echo",
            "Echo back the input message",
            InputSchema(
                "object",
                Map("message" -> PropDef("string", "Message to echo")),
                Some(List("message"))
            )
        )
    )

    // --- JSON-RPC dispatch ---
    def handleRpc(req: JsonRpcRequest): JsonRpcResponse < (Async & Abort[HttpError]) =
        val params = req.params.getOrElse(RpcParams(None, None, None, None, None))
        req.method match
            case "initialize" =>
                JsonRpcResponse(
                    "2.0",
                    req.id,
                    Some(RpcResult(
                        protocolVersion = Some("2025-06-18"),
                        capabilities = Some(ServerCapabilities(Some(ToolCapabilities(Some(false))))),
                        serverInfo = Some(ServerInfo("kyo-mcp-demo", "0.1.0")),
                        tools = None,
                        content = None
                    )),
                    None
                )
            case "notifications/initialized" =>
                JsonRpcResponse("2.0", req.id, Some(RpcResult(None, None, None, None, None)), None)
            case "tools/list" =>
                JsonRpcResponse("2.0", req.id, Some(RpcResult(None, None, None, Some(tools), None)), None)
            case "tools/call" =>
                val toolName = params.name.getOrElse("")
                val args     = params.arguments.getOrElse(Map.empty)
                toolName match
                    case "weather" =>
                        val city = args.getOrElse("city", "london")
                        fetchWeather(city).map { result =>
                            JsonRpcResponse(
                                "2.0",
                                req.id,
                                Some(RpcResult(None, None, None, None, Some(List(Content("text", result))))),
                                None
                            )
                        }
                    case "echo" =>
                        val msg = args.getOrElse("message", "")
                        JsonRpcResponse("2.0", req.id, Some(RpcResult(None, None, None, None, Some(List(Content("text", msg))))), None)
                    case other =>
                        JsonRpcResponse("2.0", req.id, None, Some(RpcError(-32601, s"Unknown tool: $other")))
                end match
            case other =>
                JsonRpcResponse("2.0", req.id, None, Some(RpcError(-32601, s"Method not found: $other")))
        end match
    end handleRpc

    // --- HTTP handlers ---

    val corsFilter = HttpFilter.server.cors()
        .andThen(HttpFilter.server.logging)

    // POST /mcp - receive JSON-RPC, return JSON response
    val mcpPost = HttpRoute
        .postRaw("mcp")
        .filter(corsFilter)
        .request(_.bodyJson[JsonRpcRequest])
        .response(_.bodyJson[JsonRpcResponse])
        .metadata(_.summary("MCP Streamable HTTP endpoint"))
        .handler { req =>
            handleRpc(req.fields.body).map(HttpResponse.okJson(_))
        }

    // GET /mcp - SSE stream for server-initiated messages (heartbeat)
    val mcpGet = HttpHandler.getSseJson[JsonRpcResponse]("mcp") { _ =>
        // Send periodic heartbeat notifications
        Stream.init(Chunk.from(1 to 60)).map { i =>
            Async.delay(5.seconds) {
                HttpEvent(
                    data = JsonRpcResponse(
                        "2.0",
                        None,
                        Some(RpcResult(None, None, None, None, Some(List(Content("text", s"heartbeat $i"))))),
                        None
                    ),
                    event = Present("message")
                )
            }
        }
    }

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        HttpServer.init(
            HttpServer.Config().port(port).openApi("/mcp/openapi.json", "MCP Demo Server")
        )(mcpPost, mcpGet).map { server =>
            for
                _ <- Console.printLine(s"MCP server running on http://localhost:${server.port}/mcp")
                _ <- Console.printLine(s"Test: curl -X POST http://localhost:${server.port}/mcp -H 'Content-Type: application/json' \\")
                _ <- Console.printLine(
                    """  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}'"""
                )
                _ <- Console.printLine(
                    s"""Tools: curl -X POST http://localhost:${server.port}/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"weather","arguments":{"city":"tokyo"}}}'"""
                )
                _ <- server.await
            yield ()
        }
    }
end McpServer
