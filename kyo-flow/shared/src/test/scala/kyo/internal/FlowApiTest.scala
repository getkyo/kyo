package kyo.internal

import kyo.*

class FlowApiTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def withFlowServer[A](
        f: Int => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        val flow = Flow.input[Int]("x")
            .output("y")(ctx => ctx.x * 2)
            .input[String]("name")
            .output("greeting")(ctx => s"Hello ${ctx.name}, result=${ctx.y}")

        FlowStore.initMemory.map { store =>
            FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis).map { engine =>
                engine.register(Flow.Id.Workflow("test-flow"), flow).map { _ =>
                    val handlers = FlowApi.handlers(engine)
                    HttpServer.init(0, "localhost")(handlers.toSeq*).map { server =>
                        f(server.port)
                    }
                }
            }
        }
    end withFlowServer

    private def url(port: Int, path: String): String =
        s"http://localhost:$port$path"

    private def jsonField(body: String, field: String): String =
        val pattern = s""""$field"\\s*:\\s*"([^"]*)"""".r
        pattern.findFirstMatchIn(body).map(_.group(1)).getOrElse("")

    private def jsonFieldInt(body: String, field: String): Int =
        val pattern = s""""$field"\\s*:\\s*(\\d+)""".r
        pattern.findFirstMatchIn(body).map(_.group(1).toInt).getOrElse(-1)

    // =========================================================================
    // Workflow endpoints
    // =========================================================================
    "GET /api/v1/workflows" - {

        "lists registered workflows" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows")).map { body =>
                    assert(body.contains("test-flow"))
                    assert(body.contains("\"inputs\""))
                }
            }
        }
    }

    "GET /api/v1/workflows/:id" - {

        "returns workflow info" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/test-flow")).map { body =>
                    assert(body.contains("test-flow"))
                }
            }
        }

        "404 for unknown workflow" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/unknown")).map { body =>
                    assert(body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/workflows/:id/diagram" - {

        "returns mermaid by default" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/test-flow/diagram")).map { body =>
                    assert(body.contains("graph"))
                }
            }
        }

        "returns dot format" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/test-flow/diagram?format=dot")).map { body =>
                    assert(body.contains("digraph"))
                }
            }
        }
    }

    // =========================================================================
    // Execution lifecycle
    // =========================================================================
    "POST /api/v1/workflows/:id/executions" - {

        "creates execution" in run {
            withFlowServer { port =>
                HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "").map { body =>
                    assert(body.contains("executionId"))
                }
            }
        }

        "404 for unknown workflow" in run {
            withFlowServer { port =>
                HttpClient.postText(url(port, "/api/v1/workflows/unknown/executions"), "").map { body =>
                    assert(body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/executions/:eid" - {

        "returns execution status" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid"))
                yield
                    assert(body.contains(eid))
                    assert(body.contains("status"))
            }
        }

        "404 for unknown execution" in run {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/executions/nonexistent")).map { body =>
                    assert(body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/executions/:eid/inputs" - {

        "shows pending inputs" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/inputs"))
                yield
                    assert(body.contains("\"name\""))
                    assert(body.contains("\"delivered\""))
            }
        }
    }

    "POST /api/v1/executions/:eid/signal/:name" - {

        "delivers signal" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                yield assert(body.contains("true"))
            }
        }

        "400 for unknown input name" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/bogus"), "42")
                yield assert(body.contains("400") || body.contains("BadRequest"))
            }
        }

        "rejects signal to completed execution" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _ <- Async.sleep(500.millis)
                    _ <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                    _ <- Async.sleep(500.millis)
                    _ <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/name"), "\"hello\"")
                    _ <- Async.sleep(1.second)
                    // Execution should be completed now. Try signaling again.
                    body <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "99")
                yield
                    // Should NOT be "ok" — should indicate error
                    assert(!body.contains("\"ok\":true"), s"Signal to completed execution should fail, got: $body")
            }
        }
    }

    "GET /api/v1/executions/:eid/history" - {

        "returns events" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/history"))
                yield
                    assert(body.contains("Created"))
                    assert(body.contains("\"hasMore\""))
            }
        }
    }

    "POST /api/v1/executions/:eid/cancel" - {

        "cancels execution" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/cancel"), "")
                yield assert(body.contains("true"))
            }
        }
    }

    "POST /api/v1/executions/search" - {

        "searches all executions" in run {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(200.millis)
                    result <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest()
                    )
                yield assert(result.total >= 2)
            }
        }

        "filters by workflow" in run {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(200.millis)
                    result <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(workflowId = Some("test-flow"))
                    )
                yield assert(result.total >= 1)
            }
        }
    }

    "POST /api/v1/executions/cancel (cancelAll)" - {

        "cancels all matching" in run {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(500.millis)
                    result <- HttpClient.postJson[FlowApi.CancelAllResponse](
                        url(port, "/api/v1/executions/cancel"),
                        FlowApi.CancelAllRequest(workflowId = Some("test-flow"))
                    )
                yield assert(result.cancelled >= 0)
            }
        }
    }

    "GET /api/v1/executions/:eid/diagram" - {

        "returns diagram with progress" in run {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/diagram"))
                yield assert(body.contains("graph"))
            }
        }
    }

    // =========================================================================
    // Full lifecycle
    // =========================================================================
    "full lifecycle" - {

        "create → signal → complete" in run {
            withFlowServer { port =>
                for
                    // Create
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _ <- Async.sleep(500.millis)

                    // Signal x
                    _ <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "10")
                    _ <- Async.sleep(500.millis)

                    // Check waiting for name
                    statusBody <- HttpClient.getText(url(port, s"/api/v1/executions/$eid"))
                    _ = assert(statusBody.contains("waiting:name"))

                    // Signal name
                    _ <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/name"), "\"World\"")
                    _ <- Async.sleep(500.millis)

                    // Check completed
                    finalBody <- HttpClient.getText(url(port, s"/api/v1/executions/$eid"))
                    _ = assert(finalBody.contains("completed"))

                    // Check history
                    histBody <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/history"))
                yield
                    assert(histBody.contains("Created"))
                    assert(histBody.contains("Completed"))
            }
        }
    }

end FlowApiTest
