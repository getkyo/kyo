package kyo.integration

import kyo.*

/** A request that arrives before the server finishes wiring itself must still be served.
  *
  * The engine builds the live `McpServer` only after `JsonRpcHandler.initUnscoped` returns, and that call
  * has already started the dispatch loop. A request arriving in between used to find the forward
  * reference empty and be answered with `-32603 "Handler 'tools/call' panicked: McpServer not initialised
  * for 'tools/call'"`, which sends the author hunting a bug in a handler that was never called, and which
  * a well-behaved client can still provoke by pipelining.
  *
  * Every burst here is queued on the transport BEFORE the server is created, so the dispatch loop starts
  * with work already waiting.
  *
  * Read what this suite is and is not. It pins the CONTRACT (a pipelined call is served, and no response
  * ever blames a handler that was not called), and it is a genuine regression guard for that. It does NOT
  * reproduce the underlying race: with the pre-fix read restored, these tests still pass, because the
  * engine publishes the server before the dispatch fiber is scheduled on this runtime. The race was
  * reported on Native, whose scheduler makes different choices. Widening the window from outside the
  * engine is not possible, so the evidence for the fix is structural rather than empirical: dispatch now
  * waits on the reference, and the code that could answer "McpServer not initialised" no longer exists.
  */
class McpServerEarlyRequestTest extends Test:

    case class Query(sql: String) derives Schema, CanEqual
    case class Rows(count: Int) derives Schema, CanEqual

    private val protocolVersion = "2025-06-18"

    private def initializeParams: Structure.Value =
        Structure.Value.Record(Chunk(
            "protocolVersion" -> Structure.Value.Str(protocolVersion),
            "capabilities"    -> Structure.Value.Record(Chunk.empty),
            "clientInfo" -> Structure.Value.Record(Chunk(
                "name"    -> Structure.Value.Str("early-client"),
                "version" -> Structure.Value.Str("0.0.0")
            ))
        ))

    private def callParams: Structure.Value =
        Structure.Value.Record(Chunk(
            "name" -> Structure.Value.Str("run_select"),
            "arguments" -> Structure.Value.Record(Chunk(
                "sql" -> Structure.Value.Str("select 1")
            ))
        ))

    private val tool =
        McpHandler.tool[Query]("run_select", "Run a query")(q => Rows(q.sql.length))

    /** Queues the whole burst, starts the server, and returns the responses it sent back. */
    private def burst(using Frame): Chunk[JsonRpcResponse] < (Async & Scope & Abort[Closed | McpException]) =
        JsonRpcTransport.inMemory.flatMap { (serverSide, clientSide) =>
            for
                // Pipelined, with no wait for the initialize response: the burst is already sitting on
                // the transport when the dispatch loop starts.
                _         <- clientSide.send(JsonRpcRequest(JsonRpcId.Num(1), "initialize", Present(initializeParams), Absent))
                _         <- clientSide.send(JsonRpcNotification("notifications/initialized", Absent, Absent))
                _         <- clientSide.send(JsonRpcRequest(JsonRpcId.Num(2), "tools/call", Present(callParams), Absent))
                _         <- McpServer.init(serverSide, tool)
                responses <- clientSide.incoming.take(2).run
            yield responses.collect { case r: JsonRpcResponse => r }
        }

    "a tools/call pipelined with the handshake is served, not reported as a handler panic" in {
        Scope.run(burst).map { responses =>
            val call = responses.find(_.id == JsonRpcId.Num(2))
            assert(call.isDefined, s"the pipelined call must be answered; got: $responses")
            val answer = call.get
            answer.error match
                case Present(err) =>
                    fail(s"the pipelined call must not fail; got code ${err.code}: ${err.message}")
                case Absent =>
                    assert(answer.result.isDefined, s"the call must carry a result; got: $answer")
            end match
        }
    }

    "no response in the burst blames a handler that was never called" in {
        // The exact reported symptom, kept as its own assertion so a regression names itself.
        Scope.run(burst).map { responses =>
            val faults = responses.flatMap(_.error.toChunk).filter(e =>
                e.message.contains("panicked") || e.message.contains("not initialised")
            )
            assert(faults.isEmpty, s"a startup race must never surface as a handler panic; got: $faults")
        }
    }

    "the burst behaves the same way when repeated" in {
        // One pass proves little about a timing-dependent path, so this exercises a fresh engine each
        // time. It has never been observed to fail, before or after the fix; see the note on this
        // suite about what that does and does not establish.
        Kyo.foreach(Chunk.range(0, 20)) { _ =>
            Scope.run(burst).map { responses =>
                responses.flatMap(_.error.toChunk).filter(e =>
                    e.message.contains("panicked") || e.message.contains("not initialised")
                )
            }
        }.map { faultsPerRun =>
            val faults = faultsPerRun.flatten
            assert(faults.isEmpty, s"no run may report a startup race; got: $faults")
        }
    }

end McpServerEarlyRequestTest
