package kyo

/** Covers the startup check on `.error[E]` coverage.
  *
  * A handler body's abort type is absorbed into `E` whether or not a mapping exists for it, so an
  * unmapped one used to be invisible: nothing complained at compile time or at `McpServer.init`, and the
  * gap surfaced a round trip later as a `-32603` carrying the exception's `toString`. That leaks whatever
  * the exception carries (a query, a connection id) inside a code the caller cannot discriminate from a
  * genuine server fault. The engine has the information to refuse the handler at startup, so it does.
  *
  * The fixtures all return a value on their success path. A body that ONLY aborts leaves `Out`
  * uninferred, since Scala widens `Nothing` to `Any`, and the output-type guard refuses that with its
  * own message: such a handler has no output schema to advertise, so it has to name its return type.
  */
class McpServerErrorMappingTest extends Test:

    case class Query(sql: String) derives Schema, CanEqual
    case class Rows(count: Int) derives Schema, CanEqual

    case class QueryFailed(reason: String) derives Schema, CanEqual
    case class Denied(who: String) derives Schema, CanEqual

    /** A domain failure hierarchy, for the supertype-mapping case. */
    sealed abstract class StoreFailure(val detail: String)
    case class StoreOffline(override val detail: String) extends StoreFailure(detail)

    private def startup(handlers: McpHandler[?, ?, ?]*)(using Frame): Result[Throwable, Unit] < (Async & Scope) =
        JsonRpcTransport.inMemory.map { (serverSide, _) =>
            Abort.run[Throwable](McpServer.init(serverSide, handlers*).unit)
        }

    private def refusal(result: Result[Throwable, Unit]): String =
        result match
            case Result.Failure(ex) => ex.getMessage
            case Result.Panic(ex)   => ex.getMessage
            case Result.Success(_)  => "<accepted>"

    "a handler whose body aborts an unmapped error is refused at init" in {
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(QueryFailed("empty query")) else Rows(q.sql.length)
            }
        Scope.run(startup(handler)).map { result =>
            val message = refusal(result)
            assert(result.isSuccess == false, s"the handler must be refused; got: $message")
            assert(message.contains("QueryFailed"), s"the refusal must name the unmapped type; got: $message")
            assert(message.contains("run_select"), s"the refusal must name the handler; got: $message")
            assert(message.contains("error"), s"the refusal must point at `.error`; got: $message")
        }
    }

    "the same handler with the mapping registered starts" in {
        // The control: the check must not refuse a correct handler.
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(QueryFailed("empty query")) else Rows(q.sql.length)
            }.error[QueryFailed](-40001, "query-failed")
        Scope.run(startup(handler)).map { result =>
            assert(result.isSuccess, s"a mapped handler must start; got: ${refusal(result)}")
        }
    }

    "a handler that aborts nothing starts" in {
        val handler = McpHandler.tool[Query]("run_select", "Run a query")(q => Rows(q.sql.length))
        Scope.run(startup(handler)).map { result =>
            assert(result.isSuccess, s"a handler with no declared error must start; got: ${refusal(result)}")
        }
    }

    "a mapping on a supertype covers a subtype the body aborts" in {
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(StoreOffline("no route to host")) else Rows(q.sql.length)
            }.error[StoreFailure](-40002, "store-failure")
        Scope.run(startup(handler)).map { result =>
            assert(result.isSuccess, s"a supertype mapping must cover the leaf; got: ${refusal(result)}")
        }
    }

    "a union body error with only one leaf mapped is refused, naming the leaf that is missing" in {
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(QueryFailed("empty query"))
                else if q.sql == "denied" then Abort.fail(Denied("nobody"))
                else Rows(q.sql.length)
            }.error[QueryFailed](-40001, "query-failed")
        Scope.run(startup(handler)).map { result =>
            val message = refusal(result)
            assert(result.isSuccess == false, s"the handler must be refused; got: $message")
            assert(message.contains("Denied"), s"the refusal must name the UNMAPPED leaf; got: $message")
        }
    }

    "a union body error with every leaf mapped starts" in {
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(QueryFailed("empty query"))
                else if q.sql == "denied" then Abort.fail(Denied("nobody"))
                else Rows(q.sql.length)
            }.error[QueryFailed](-40001, "query-failed")
                .error[Denied](-40002, "denied")
        Scope.run(startup(handler)).map { result =>
            assert(result.isSuccess, s"every leaf is mapped, so it must start; got: ${refusal(result)}")
        }
    }

    "a body aborting an McpException needs no mapping" in {
        // Already wire-shaped: it carries its own code and message, and the dispatch boundary forwards
        // it rather than taking the unmapped path, so requiring a mapping would refuse exactly the
        // handlers that already answer correctly.
        val handler =
            McpHandler.toolRaw[Query]("run_select") { _ =>
                Abort.fail(McpToolExecutionException(tool = "run_select", reason = "upstream refused"))
            }
        Scope.run(startup(handler)).map { result =>
            assert(result.isSuccess, s"a wire-shaped error needs no mapping; got: ${refusal(result)}")
        }
    }

    "a reserved-range error code is still refused" in {
        // The sibling check this one is modelled on, pinned here because nothing else covered it.
        val handler =
            McpHandler.tool[Query]("run_select", "Run a query") { q =>
                if q.sql.isEmpty then Abort.fail(QueryFailed("empty query")) else Rows(q.sql.length)
            }.error[QueryFailed](-32001, "query-failed")
        Scope.run(startup(handler)).map { result =>
            val message = refusal(result)
            assert(result.isSuccess == false, s"a reserved code must be refused; got: $message")
            assert(message.contains("reserved"), s"the refusal must say why; got: $message")
        }
    }

end McpServerErrorMappingTest
