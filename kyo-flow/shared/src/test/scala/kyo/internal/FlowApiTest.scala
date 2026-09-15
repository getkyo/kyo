package kyo.internal

import kyo.*

class FlowApiTest extends kyo.test.Test[Any]:

    // This suite is real-time by nature (a live HTTP server and client; Clock.withTimeControl does not reach it),
    // and in a full-module run it executes concurrently with sibling suites whose leaves each spin engines, workers
    // and renewal fibers on the shared scheduler. Its slowest leaf, "rejects signal to completed execution", is three
    // assertEventually polls over real round-trips and takes 1.5s standalone; under that contention it starves past
    // the 120-second default budget. The larger budget absorbs the starvation without touching what any leaf asserts;
    // a genuinely stuck leaf still stops. Suite-level isolation would be the sharper fix, but test parallelism is a
    // build-level setting, not this suite's.
    override def timeout = 300.seconds

    // Socket-only opt-out: this suite runs an HttpServer/HttpClient on the NIO transport, whose closed-channel fd
    // close is deferred to the idle selector's next select() (an opaque socket:[inode] no allowlist matches), the
    // same transport-deferred reason as BaseHttpTest. Thread, fiber, and file-descriptor detection stay on.
    override def config = super.config.leakCheckSockets(false)

    given CanEqual[Any, Any] = CanEqual.derived

    private def withFlowServer[A](
        f: Int => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        withFlowServerStore((port, _) => f(port))

    /** The same server, with the store it is backed by.
      *
      * A leaf that needs an execution the engine will never claim has to write one, and the only way to write one is the store: every
      * execution reachable over HTTP belongs to the workflow this engine serves, and therefore terminalises on its own the moment
      * anything asks it to stop.
      */
    private def withFlowServerStore[A](
        f: (Int, FlowStore) => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        val flow = Flow.input[Int]("x")
            .output("y")(ctx => ctx.x * 2)
            .input[String]("name")
            .output("greeting")(ctx => s"Hello ${ctx.name}, result=${ctx.y}")

        FlowStore.initMemory.map { store =>
            FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis).map { engine =>
                engine.register(Flow.Id.Workflow("test-flow"), flow).map { _ =>
                    val handlers = FlowApi.handlers(engine)
                    HttpServer.init(0, "127.0.0.1")(handlers.toSeq*).map { server =>
                        HttpClient.withConfig(_.timeout(30.seconds))(f(server.port, store))
                    }
                }
            }
        }
    end withFlowServerStore

    private def url(port: Int, path: String): String =
        s"http://127.0.0.1:$port$path"

    private def jsonField(body: String, field: String): String =
        val pattern = s""""$field"\\s*:\\s*"([^"]*)"""".r
        pattern.findFirstMatchIn(body).map(_.group(1)).getOrElse("")

    private def jsonFieldInt(body: String, field: String): Int =
        val pattern = s""""$field"\\s*:\\s*(\\d+)""".r
        pattern.findFirstMatchIn(body).map(_.group(1).toInt).getOrElse(-1)

    /** Whether an execution-detail body says the execution is waiting for the named input.
      *
      * The lifecycle string cannot answer it: an execution waiting for an input is `running`, and what it waits for is one row per
      * waiting node, which the body carries beside the status.
      */
    private def waitsFor(name: String)(body: String): Boolean =
        body.replace(" ", "").contains(s""""path":"$name"""")

    private def awaitStatus(port: Int, eid: String)(pred: String => Boolean)(using
        Frame,
        kyo.test.AssertScope
    ): String < (Async & Abort[Any]) =
        AtomicRef.init("").map { last =>
            assertEventually {
                HttpClient.getText(url(port, s"/api/v1/executions/$eid")).map { body =>
                    last.set(body).andThen(pred(body))
                }
            }.andThen(last.get)
        }

    // =========================================================================
    // Workflow endpoints
    // =========================================================================
    "GET /api/v1/workflows" - {

        "lists registered workflows" in {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows")).map { body =>
                    assert(body.contains("test-flow"))
                    assert(body.contains("\"inputs\""))
                }
            }
        }
    }

    "GET /api/v1/workflows/:id" - {

        "returns workflow info" in {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/test-flow")).map { body =>
                    assert(body.contains("test-flow"))
                }
            }
        }

        "404 for unknown workflow" in {
            withFlowServer { port =>
                HttpClient.getTextResponse(url(port, "/api/v1/workflows/unknown"), failOnError = false).map { resp =>
                    val body = resp.fields.body
                    assert(resp.status.code == 404 || body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/workflows/:id/diagram" - {

        "returns mermaid by default" in {
            withFlowServer { port =>
                HttpClient.getText(url(port, "/api/v1/workflows/test-flow/diagram")).map { body =>
                    assert(body.contains("graph"))
                }
            }
        }

        "returns dot format" in {
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

        "creates execution" in {
            withFlowServer { port =>
                HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "").map { body =>
                    assert(body.contains("executionId"))
                }
            }
        }

        "404 for unknown workflow" in {
            withFlowServer { port =>
                HttpClient.postTextResponse(url(port, "/api/v1/workflows/unknown/executions"), "", failOnError = false).map { resp =>
                    val body = resp.fields.body
                    assert(resp.status.code == 404 || body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/executions/:eid" - {

        "returns execution status" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid"))
                yield
                    assert(body.contains(eid))
                    assert(body.contains("status"))
            }
        }

        /** The detail endpoint answers what an execution is doing, not only that it is running.
          *
          * `FlowEngine.ExecutionDetail` carries the per-node progress and the input delivery state, and the endpoint must pass both
          * through. An endpoint projecting it down to a few scalars sends anyone asking "which node is it on" to the store instead.
          */
        "returns the per-node progress and the input state" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    _    <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                    _    <- Async.sleep(500.millis)
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid"))
                yield
                    assert(body.contains("\"progress\""), s"the detail must carry progress: $body")
                    assert(body.contains("\"inputs\""), s"the detail must carry the inputs: $body")
                    // The flow's own nodes, by name, each with the status the engine derived for it.
                    assert(body.contains("\"y\""), s"progress must name the flow's nodes: $body")
                    assert(body.contains("\"greeting\""), s"progress must name the flow's nodes: $body")
                    // `x` was signalled and `name` was not, so the two inputs disagree on delivery.
                    assert(body.contains("\"delivered\":true"), s"a delivered input must say so: $body")
                    assert(body.contains("\"delivered\":false"), s"a pending input must say so: $body")
                    assert(body.contains("completed") || body.contains("running"), s"a node must carry a status: $body")
            }
        }

        /** The detail says what the execution is WAITING for, which its lifecycle cannot.
          *
          * `status` answers `running` for an execution that is working and for one that is waiting alike, and an execution can be
          * waiting on several things at once, so the answer is a row per waiting node: which node, what kind of wait, and the
          * condition.
          */
        "reports what a waiting execution is waiting for" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- awaitStatus(port, eid)(waitsFor("x"))
                yield
                    val compact = body.replace(" ", "")
                    assert(compact.contains("\"status\":\"running\""), s"a waiting execution is running: $body")
                    assert(compact.contains("\"path\":\"x\""), s"the wait must name the node waiting: $body")
                    assert(compact.contains("\"kind\":\"waiting\""), s"the wait must say what kind it is: $body")
                    assert(compact.contains("\"cancelRequested\":false"), s"and whether somebody asked it to stop: $body")
            }
        }

        "404 for unknown execution" in {
            withFlowServer { port =>
                HttpClient.getTextResponse(url(port, "/api/v1/executions/nonexistent"), failOnError = false).map { resp =>
                    val body = resp.fields.body
                    assert(resp.status.code == 404 || body.contains("404") || body.contains("NotFound"))
                }
            }
        }
    }

    "GET /api/v1/executions/:eid/inputs" - {

        "shows pending inputs" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/inputs"))
                yield
                    assert(body.contains("\"name\""))
                    assert(body.contains("\"delivered\""))
            }
        }
    }

    "POST /api/v1/executions/:eid/signal/:name" - {

        "delivers signal" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                yield assert(body.contains("true"))
            }
        }

        /** A signal delivered over HTTP is recorded in the execution's history, like one delivered through the engine.
          *
          * The endpoint delivers through `engine.executions.signal`, which records `Flow.Event.InputReceived` in the same
          * transition as the write. Writing the field directly instead is durable but not auditable: the input simply appears,
          * with nothing in history saying when it arrived or that it arrived at all. One verb, one set of guards, one event.
          *
          * History is the operator's account of what happened to an execution, and this is the only surface that can put a value into
          * a running execution from outside. `InputWaiting` is recorded when the flow starts waiting, so a history that never shows
          * the matching `InputReceived` reads as an execution still blocked on an input it has already been given.
          */
        "records the delivery in history" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _        <- Async.sleep(500.millis)
                    _        <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                    _        <- Async.sleep(500.millis)
                    histBody <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/history"))
                yield assert(
                    histBody.contains("InputReceived"),
                    s"a signal delivered over HTTP must be recorded like any other, history was: $histBody"
                )
            }
        }

        "400 for unknown input name" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    resp <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/$eid/signal/bogus"), "42", failOnError = false)
                    body = resp.fields.body
                yield assert(resp.status.code == 400 || body.contains("400") || body.contains("BadRequest"))
            }
        }

        "rejects signal to completed execution" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- awaitStatus(port, eid)(waitsFor("x"))
                    _    <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "42")
                    _    <- awaitStatus(port, eid)(waitsFor("name"))
                    _    <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/name"), "\"hello\"")
                    _    <- awaitStatus(port, eid)(_.contains("completed"))
                    resp <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/$eid/signal/x"), "99", failOnError = false)
                    body = resp.fields.body
                yield assert(
                    !resp.status.isSuccess || !body.contains("\"ok\":true"),
                    s"Signal to completed execution should fail, got: status=${resp.status.code} body=$body"
                )
            }
        }
    }

    "GET /api/v1/executions/:eid/history" - {

        "returns events" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/history"))
                yield
                    assert(body.contains("Created"))
                    assert(body.contains("\"hasMore\""))
            }
        }

        /** A negative page offset is refused here too, for the reason the search route refuses it.
          *
          * This is the route where forwarding hurts most: it has no error mapping of its own, so a store that rejects `OFFSET -1`
          * would surface a client's typo as a 500. The check is at the wire because the wire is the only layer that can tell a bad
          * query parameter from a store fault.
          */
        "a negative history offset is refused" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(200.millis)
                    resp <- HttpClient.getTextResponse(url(port, s"/api/v1/executions/$eid/history?offset=-1"), failOnError = false)
                yield assert(
                    resp.status.code >= 400 && resp.status.code < 500,
                    s"a negative offset is a client error, got ${resp.status.code} with ${resp.fields.body.take(200)}"
                )
            }
        }
    }

    "POST /api/v1/executions/:eid/cancel" - {

        /** A cancel the store took answers that it took it, and says so with a code of its own.
          *
          * Answering `ok: true` to all three outcomes leaves no HTTP caller able to tell a request that was taken from one that
          * fell on an execution which finished last week. Nothing about a cancel is observable on return (the execution runs its
          * compensations afterwards), so this response is the only thing the caller has.
          */
        "cancels execution" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _    <- Async.sleep(500.millis)
                    resp <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/$eid/cancel"), "", failOnError = false)
                    body = resp.fields.body
                yield
                    assert(
                        resp.status.code == 202,
                        s"a request taken whose work has not been done answers 202, got ${resp.status.code} with $body"
                    )
                    assert(
                        jsonField(body, "outcome") == "accepted",
                        s"the outcome must say the request was taken, got $body"
                    )
                end for
            }
        }

        /** Asking twice answers that somebody already asked, which is a different instruction to the caller.
          *
          * Staged on an execution no engine here serves, so it is unclaimable and cannot terminalise between the two asks: the leaf is
          * about which answer a repeat gets, and racing an unwind would make it about scheduling instead.
          */
        "a repeated cancel answers already-requested" in {
            withFlowServerStore { (port, store) =>
                val eid = Flow.Id.Execution("api-cancel-twice")
                for
                    now <- Clock.now
                    _ <- store.createExecutionIfAbsent(
                        eid,
                        Flow.Status.Running,
                        Flow.Event.Created(Flow.Id.Workflow("unserved-flow"), eid, now),
                        "",
                        Dict.empty
                    )
                    first  <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/${eid.value}/cancel"), "", failOnError = false)
                    second <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/${eid.value}/cancel"), "", failOnError = false)
                yield
                    assert(
                        jsonField(first.fields.body, "outcome") == "accepted",
                        s"the premise is that the first ask was taken, got ${first.fields.body}"
                    )
                    assert(
                        second.status.code == 200,
                        s"a repeat changed nothing and is not an error, got ${second.status.code}"
                    )
                    assert(
                        jsonField(second.fields.body, "outcome") == "already-requested",
                        s"the repeat must say somebody asked first, got ${second.fields.body}"
                    )
                end for
            }
        }

        /** Cancelling something that is over is answered, not refused, and the answer carries what it ended as.
          *
          * The caller got the outcome it wanted, so this is no client error; what it needs back is the terminal status, because its
          * next call is a read of the result rather than another cancel.
          */
        "a cancel on a finished execution answers already-terminal with its status" in {
            withFlowServerStore { (port, store) =>
                val eid = Flow.Id.Execution("api-cancel-finished")
                for
                    now <- Clock.now
                    _ <- store.createExecutionIfAbsent(
                        eid,
                        Flow.Status.Completed,
                        Flow.Event.Created(Flow.Id.Workflow("unserved-flow"), eid, now),
                        "",
                        Dict.empty
                    )
                    resp <- HttpClient.postTextResponse(url(port, s"/api/v1/executions/${eid.value}/cancel"), "", failOnError = false)
                    body = resp.fields.body
                yield
                    assert(
                        resp.status.code == 200,
                        s"an execution that is already over is an answer, not a client error, got ${resp.status.code}"
                    )
                    assert(
                        jsonField(body, "outcome") == "already-terminal",
                        s"the outcome must say there was nothing to cancel, got $body"
                    )
                    assert(
                        jsonField(body, "status") == Flow.Status.Completed.show,
                        s"and it must carry what the execution ended as, got $body"
                    )
                end for
            }
        }
    }

    "POST /api/v1/executions/search" - {

        "searches all executions" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    result <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest()
                    )
                yield assert(result.total >= 2)
            }
        }

        "filters by workflow" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    result <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(workflowId = Some("test-flow"))
                    )
                yield assert(result.total >= 1)
            }
        }

        /** The wire keeps the kind-scoped failure filter, not just the bare name.
          *
          * `Failed`'s kind exists so an operator can ask "how many failed for payment reasons", and the wire syntax is where that ask
          * is spelled. A parse that dropped the suffix would answer every failure to a caller who asked for one kind, with a 200 and
          * no way to tell: the same shape as the unrecognized filter refused below, one narrowing further in.
          */
        "narrows a failure filter by kind" in {
            withFlowServerStore { (port, store) =>
                val declined = Flow.Id.Execution("api-filter-declined")
                val panicked = Flow.Id.Execution("api-filter-panicked")
                val wfId     = Flow.Id.Workflow("test-flow")
                for
                    now <- Clock.now
                    _ <- store.createExecutionIfAbsent(
                        declined,
                        Flow.Status.Failed("declined", Maybe("ChargeDeclined")),
                        Flow.Event.Created(wfId, declined, now),
                        "",
                        Dict.empty
                    )
                    _ <- store.createExecutionIfAbsent(
                        panicked,
                        Flow.Status.Failed("boom", Maybe.empty),
                        Flow.Event.Created(wfId, panicked, now),
                        "",
                        Dict.empty
                    )
                    narrowed <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(status = Some("failed:ChargeDeclined"))
                    )
                    bare <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(status = Some("failed"))
                    )
                yield
                    assert(
                        narrowed.items.map(_.executionId) == Seq(declined.value),
                        s"the kind-scoped ask must answer only that kind, got ${narrowed.items.map(_.executionId)}"
                    )
                    assert(
                        bare.items.map(_.executionId).toSet == Set(declined.value, panicked.value),
                        s"and the bare ask must still answer every failure, got ${bare.items.map(_.executionId)}"
                    )
                end for
            }
        }

        /** The wire keeps the name-scoped input filter, which is the capability the arm carries its parameter for.
          *
          * An operator hunting "stuck waiting for approval" is asking about one input's name, and the names are the caller's own
          * vocabulary because `signal` takes them. A parse that coarsened `waiting:<name>` to bare `waiting` answers every waiting
          * execution to that ask.
          */
        "narrows an input-wait filter by name" in {
            withFlowServer { port =>
                for
                    firstBody  <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    secondBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    onX    = jsonField(firstBody, "executionId")
                    onName = jsonField(secondBody, "executionId")
                    // The second is walked past its first input so the two executions hold rows for DIFFERENT names, which is what
                    // makes the narrowing observable at all.
                    _ <- awaitStatus(port, onName)(waitsFor("x"))
                    _ <- HttpClient.postText(url(port, s"/api/v1/executions/$onName/signal/x"), "42")
                    _ <- awaitStatus(port, onName)(waitsFor("name"))
                    _ <- awaitStatus(port, onX)(waitsFor("x"))
                    forName <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(status = Some("waiting:name"))
                    )
                    anyWait <- HttpClient.postJson[FlowApi.SearchResponse](
                        url(port, "/api/v1/executions/search"),
                        FlowApi.SearchRequest(status = Some("waiting"))
                    )
                yield
                    assert(
                        forName.items.map(_.executionId) == Seq(onName),
                        s"the name-scoped ask must answer only the execution waiting on that input, got ${forName.items.map(_.executionId)}"
                    )
                    assert(
                        anyWait.items.map(_.executionId).toSet == Set(onX, onName),
                        s"and the bare ask must answer both input waits, got ${anyWait.items.map(_.executionId)}"
                    )
                end for
            }
        }

        /** An unrecognized filter must be refused, not silently dropped.
          *
          * `FlowApi.parseFilter` answers `Maybe.empty` for a string it does not know, and a route reading that as NO filter would
          * return every execution as if it matched the typo: the one answer that is wrong for every caller, delivered with a 200.
          * A filter the server cannot parse is a client error and answers 4xx, the same contract the signal route keeps for an
          * unknown input name.
          */
        "an unrecognized filter is refused, not ignored" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(200.millis)
                    resp <- HttpClient.postTextResponse(
                        url(port, "/api/v1/executions/search"),
                        """{"status":"bananas"}""",
                        headers = Seq("Content-Type" -> "application/json"),
                        failOnError = false
                    )
                yield assert(
                    resp.status.code >= 400 && resp.status.code < 500,
                    s"a filter the server cannot parse is a client error, got ${resp.status.code} with body " +
                        s"${resp.fields.body.take(200)}"
                )
            }
        }

        /** A narrowing prefix with nothing after it is refused the same way an unrecognized filter is.
          *
          * A parse that took `failed:` and `waiting:` as a narrowing on the empty string would match nothing, since nothing carries
          * an empty kind or an empty input name, and the caller would get an empty result set with a 200. That is the same typo as
          * `bananas` above telling the opposite lie: nothing matched, rather than everything did, and neither answer lets the
          * caller find the mistake.
          */
        "a filter with an empty narrowing is refused" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(200.millis)
                    failedEmpty <- HttpClient.postTextResponse(
                        url(port, "/api/v1/executions/search"),
                        """{"status":"failed:"}""",
                        headers = Seq("Content-Type" -> "application/json"),
                        failOnError = false
                    )
                    waitingEmpty <- HttpClient.postTextResponse(
                        url(port, "/api/v1/executions/search"),
                        """{"status":"waiting:"}""",
                        headers = Seq("Content-Type" -> "application/json"),
                        failOnError = false
                    )
                yield
                    assert(
                        failedEmpty.status.code >= 400 && failedEmpty.status.code < 500,
                        s"an empty kind narrows to nothing and must be refused, got ${failedEmpty.status.code}"
                    )
                    assert(
                        waitingEmpty.status.code >= 400 && waitingEmpty.status.code < 500,
                        s"an empty input name narrows to nothing and must be refused, got ${waitingEmpty.status.code}"
                    )
                end for
            }
        }

        /** A negative page offset is a client error, and this is the layer that can say so.
          *
          * The SPI makes a non-negative `offset` the caller's obligation, and this handler is the caller. Unlike a negative `limit`,
          * which the whole stack reads as "everything", a negative offset has no reading to fall back on: forwarded, it is tolerated
          * by a store that drops from a chunk and rejected by one that writes it into SQL, where a client's typo would come back as a
          * 500 and send an operator hunting a server fault.
          */
        "a negative search offset is refused" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- Async.sleep(200.millis)
                    resp <- HttpClient.postTextResponse(
                        url(port, "/api/v1/executions/search"),
                        """{"offset":-1}""",
                        headers = Seq("Content-Type" -> "application/json"),
                        failOnError = false
                    )
                yield assert(
                    resp.status.code >= 400 && resp.status.code < 500,
                    s"a negative offset is a client error, got ${resp.status.code} with ${resp.fields.body.take(200)}"
                )
            }
        }
    }

    "POST /api/v1/executions/cancel (cancelAll)" - {

        "cancels all matching" in {
            withFlowServer { port =>
                for
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    _ <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    result <- HttpClient.postJson[FlowApi.CancelAllResponse](
                        url(port, "/api/v1/executions/cancel"),
                        FlowApi.CancelAllRequest(workflowId = Some("test-flow"))
                    )
                yield assert(
                    result.requested == 2,
                    s"both executions park on an input nobody delivers, so both are asked to stop, got ${result.requested}"
                )
            }
        }
    }

    "GET /api/v1/executions/:eid/diagram" - {

        "returns diagram with progress" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    body <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/diagram"))
                yield assert(body.contains("graph"))
            }
        }
    }

    // =========================================================================
    // Full lifecycle
    // =========================================================================
    "full lifecycle" - {

        "create → signal → complete" in {
            withFlowServer { port =>
                for
                    createBody <- HttpClient.postText(url(port, "/api/v1/workflows/test-flow/executions"), "")
                    eid = jsonField(createBody, "executionId")
                    _        <- awaitStatus(port, eid)(waitsFor("x"))
                    _        <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/x"), "10")
                    _        <- awaitStatus(port, eid)(waitsFor("name"))
                    _        <- HttpClient.postText(url(port, s"/api/v1/executions/$eid/signal/name"), "\"World\"")
                    _        <- awaitStatus(port, eid)(_.contains("completed"))
                    histBody <- HttpClient.getText(url(port, s"/api/v1/executions/$eid/history"))
                yield
                    assert(histBody.contains("Created"))
                    assert(histBody.contains("Completed"))
            }
        }
    }

end FlowApiTest
