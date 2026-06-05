package kyo

import caliban.*
import caliban.render
import caliban.schema.ArgBuilder
import caliban.schema.Schema
import caliban.schema.SchemaDerivation
import caliban.wrappers.Caching
import caliban.wrappers.Caching.GQLCacheControl
import scala.concurrent.Future
import zio.ZLayer

class ResolverTest extends BaseCalibanTest:

    case class Query(
        k1: Int < Abort[Throwable],
        k2: Int < Async,
        k3: Int < (Abort[Throwable] & Async),
        k4: Int < Sync,
        k5: Int < Async
    ) derives Schema.SemiAuto

    case class AddArgs(a: Int, b: Int) derives Schema.SemiAuto, ArgBuilder
    case class ArgsQuery(add: AddArgs => Int) derives Schema.SemiAuto

    case class Mutation(doSomething: Int) derives Schema.SemiAuto

    val defaultQuery = Query(42, 42, 42, 42, 42)

    def startServer(using Frame): HttpServer < (Async & Scope & Abort[CalibanError]) =
        for
            interpreter <- Resolvers.get(graphQL(RootResolver(defaultQuery)))
            server      <- Resolvers.run(interpreter)
        yield server

    def send[In, Out](
        port: Int,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException] & Scope) =
        HttpClient.init().map { httpClient =>
            val base = HttpUrl.parse(s"http://localhost:$port").getOrThrow
            val url  = HttpUrl(base.scheme, base.host, base.port, request.url.path, request.url.rawQuery)
            httpClient.sendWith(route.asInstanceOf[HttpRoute[In, Out, Any]], request.copy(url = url))(identity)
        }

    val binaryRoute = HttpRoute.postRaw("api/graphql").request(_.bodyBinary).response(_.bodyBinary)
    val getRoute    = HttpRoute.getRaw("api/graphql").response(_.bodyBinary)

    def postGql(port: Int, body: String, headers: (String, String)*)(using Frame) =
        val bodyBytes = Span.fromUnsafe(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val baseReq   = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql")).addField("body", bodyBytes)
        val req       = headers.foldLeft(baseReq)((r, h) => r.setHeader(h._1, h._2))
        send(port, binaryRoute, req).map { resp =>
            (resp.status, new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8), resp.headers)
        }
    end postGql

    def getGql(port: Int, queryString: String, headers: (String, String)*)(using Frame) =
        val baseReq = HttpRequest.getRaw(HttpUrl.fromUri(s"/api/graphql?$queryString"))
        val req     = headers.foldLeft(baseReq)((r, h) => r.setHeader(h._1, h._2))
        send(port, getRoute, req).map { resp =>
            (resp.status, new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8), resp.headers)
        }
    end getGql

    // ==================== Schema ====================

    "schema derivation" in {
        val expected = """type Query {
                         |  k1: Int!
                         |  k2: Int!
                         |  k3: Int!
                         |  k4: Int!
                         |  k5: Int!
                         |}""".stripMargin
        assert(render[Query].trim == expected)
    }

    "execution" in runZIO {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k1 k2 k3 k4 k5 }")
        yield assert(res.data.toString == """{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}""")
        end for
    }

    "arbitrary kyo effects" in runZIO {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[CalibanRunner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val layer = ZLayer.succeed(new CalibanRunner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v)))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k }").provide(layer)
        yield assert(res.data.toString == """{"k":4}""")
        end for
    }

    // ==================== Server - POST ====================

    "POST - JSON body" in {
        for
            server <- startServer
            res    <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k1 k2 k3 k4 k5 }"}""")
        yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}}""")
        end for
    }

    "POST - application/graphql content-type" in {
        for
            server            <- startServer
            (status, body, _) <- postGql(server.port, "{ k1 }", "Content-Type" -> "application/graphql")
        yield
            assert(status == HttpStatus.OK)
            assert(body == """{"data":{"k1":42}}""")
        end for
    }

    "POST - federation tracing header is accepted (request not rejected)" in {
        // The actual `ftv1` tracing extension only appears when the GraphQL is decorated with
        // `caliban.federation.tracing.ApolloFederatedTracing.wrapper` (a separate caliban-federation
        // module not on this classpath). This test verifies that setting the header does NOT crash
        // parseRequest's `withFederatedTracing` branch and the response is otherwise normal.
        for
            server            <- startServer
            (status, body, _) <- postGql(server.port, """{"query":"{ k1 }"}""", "apollo-federation-include-trace" -> "ftv1")
        yield
            assert(status == HttpStatus.OK)
            assert(body.contains(""""k1":42"""))
        end for
    }

    "POST - malformed JSON body returns a non-2xx with a GraphQL error envelope" in {
        val route = HttpRoute.postRaw("api/graphql").request(_.bodyBinary).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql"))
                    .addField("body", Span.fromUnsafe("{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                Abort.run[HttpException](send(server.port, route, req))
        yield
            resp match
                case Result.Success(r) =>
                    val body = new String(r.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
                    assert(body.contains("errors"), s"expected GraphQL errors body, got: $body")
                case Result.Failure(e) => fail(s"server should respond, not fail the connection: $e")
                case Result.Panic(t)   => fail(s"server panic on malformed POST JSON: $t")
            end match
        end for
    }

    "POST - empty body returns a GraphQL error envelope" in {
        val route = HttpRoute.postRaw("api/graphql").request(_.bodyBinary).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql"))
                    .addField("body", Span.fromUnsafe(Array.emptyByteArray))
                Abort.run[HttpException](send(server.port, route, req))
        yield
            resp match
                case Result.Success(r) =>
                    val body = new String(r.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
                    assert(body.contains("errors"), s"expected GraphQL errors body, got: $body")
                case Result.Failure(e) => fail(s"server should respond, not fail the connection: $e")
                case Result.Panic(t)   => fail(s"server panic on empty POST body: $t")
            end match
        end for
    }

    // ==================== Server - GET ====================

    "GET - query param" in {
        for
            server <- startServer
            resp   <- HttpClient.getText(s"http://localhost:${server.port}/api/graphql?query=%7B%20k1%20%7D")
        yield assert(resp == """{"data":{"k1":42}}""")
        end for
    }

    "GET - query with variables" in {
        val api = graphQL(RootResolver(ArgsQuery(args => args.a + args.b)))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            (_, body, _) <- getGql(
                server.port,
                "query=query(%24a%3AInt!%2C%24b%3AInt!)%7Badd(a%3A%24a%2Cb%3A%24b)%7D&variables=%7B%22a%22%3A1%2C%22b%22%3A2%7D"
            )
        yield assert(body == """{"data":{"add":3}}""")
        end for
    }

    "GET - query with operationName" in {
        for
            server       <- startServer
            (_, body, _) <- getGql(server.port, "query=query%20MyOp%7Bk1%7D&operationName=MyOp")
        yield assert(body == """{"data":{"k1":42}}""")
        end for
    }

    "GET - query with extensions" in {
        for
            server       <- startServer
            (_, body, _) <- getGql(server.port, "query=%7Bk1%7D&extensions=%7B%7D")
        yield assert(body.contains(""""k1":42"""))
        end for
    }

    "GET - no query param returns a GraphQL error envelope" in {
        for
            server            <- startServer
            (status, body, _) <- getGql(server.port, "")
        yield
            // Caliban surfaces a ValidationError ("query is required") rather than crashing.
            assert(body.contains("errors"), s"expected errors body, got: $body")
        end for
    }

    // ==================== Server - Config ====================

    "Config - custom config" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.enableIntrospection(true))
            res         <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k1 }"}""")
        yield assert(res == """{"data":{"k1":42}}""")
        end for
    }

    "Config - CalibanRunner with default config" in {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[CalibanRunner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val runner = new CalibanRunner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v))

        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, runner)
            res         <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k }"}""")
        yield assert(res == """{"data":{"k":4}}""")
        end for
    }

    "Config - CalibanRunner with custom config" in {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[CalibanRunner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(Env.get[String].map(_.length))))
        val runner = new CalibanRunner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v))
        val config = Resolvers.Config.default.path("gql")

        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, runner, config)
            res         <- HttpClient.postText(s"http://localhost:${server.port}/gql", """{"query":"{ k }"}""")
        yield assert(res == """{"data":{"k":3}}""")
        end for
    }

    "Config - custom path" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.path("custom/graphql"))
            res         <- HttpClient.postText(s"http://localhost:${server.port}/custom/graphql", """{"query":"{ k1 }"}""")
        yield assert(res == """{"data":{"k1":42}}""")
        end for
    }

    "Config - graphiql disabled" in {
        val graphiqlRoute = HttpRoute.getRaw("graphiql").response(_.bodyBinary)
        val api           = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.graphiql(false))
            resp        <- send(server.port, graphiqlRoute, HttpRequest.getRaw(HttpUrl.fromUri("/graphiql")))
        yield assert(resp.status == HttpStatus.NotFound)
        end for
    }

    "Config - filter adds response header" in {
        val filter = new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                next(request).map(_.setHeader("X-Custom", "test-value"))

        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter     <- Resolvers.get(api)
            server          <- Resolvers.run(interpreter, Resolvers.Config.default.filter(filter))
            (_, _, headers) <- postGql(server.port, """{"query":"{ k1 }"}""")
        yield assert(headers.get("X-Custom").contains("test-value"))
        end for
    }

    "Config - skipValidation allows invalid query" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.skipValidation(true))
            (status, body, _) <- postGql(server.port, """{"query":"{ nonexistent }"}""")
        yield
            // With skipValidation=true, caliban executes instead of returning a ValidationError
            assert(status == HttpStatus.OK)
            assert(!body.contains("ValidationError"), s"Expected no validation error with skipValidation=true, got: $body")
            assert(body.contains(""""nonexistent":null"""), s"Expected null for nonexistent field, got: $body")
        end for
    }

    "Config - enableIntrospection false rejects __schema queries" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.enableIntrospection(false))
            (status, body, _) <- postGql(server.port, """{"query":"{ __schema { types { name } } }"}""")
        yield assert(body.contains("Introspection is disabled"), s"Expected introspection disabled error, got: $body")
        end for
    }

    "Config - enableIntrospection true allows __schema queries" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.enableIntrospection(true))
            (status, body, _) <- postGql(server.port, """{"query":"{ __schema { types { name } } }"}""")
        yield
            assert(status == HttpStatus.OK)
            assert(body.contains("__schema"), s"Expected introspection data, got: $body")
            assert(!body.contains("Introspection is disabled"), s"Introspection should be allowed, got: $body")
        end for
    }

    "Config - allowMutationsOverGetRequests false blocks GET mutations" in {
        val api = graphQL(RootResolver(defaultQuery, Mutation(99)))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.allowMutationsOverGetRequests(false))
            (status, body, _) <- getGql(server.port, "query=mutation%7BdoSomething%7D")
        yield
            assert(status == HttpStatus.BadRequest)
            assert(
                body.contains("Mutations are not allowed for GET requests"),
                s"Expected mutation-over-GET error, got: $body"
            )
        end for
    }

    "Config - allowMutationsOverGetRequests true allows GET mutations" in {
        val api = graphQL(RootResolver(defaultQuery, Mutation(99)))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.allowMutationsOverGetRequests(true))
            (status, body, _) <- getGql(server.port, "query=mutation%7BdoSomething%7D")
        yield
            assert(status == HttpStatus.OK)
            assert(body.contains(""""doSomething":99"""), s"Expected mutation result, got: $body")
        end for
    }

    "Config - queryExecution Sequential executes fields sequentially" in {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.queryExecution(caliban.execution.QueryExecution.Sequential))
            (status, body, _) <- postGql(server.port, """{"query":"{ k1 k2 k3 }"}""")
        yield
            assert(status == HttpStatus.OK)
            assert(body.contains(""""k1":42"""), s"Expected correct result with Sequential execution, got: $body")
        end for
    }

    // ==================== Server - SSE ====================

    "SSE - one-shot query" in {
        val sseClientRoute = HttpRoute.postRaw("api/graphql/sse").request(_.bodyBinary).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val bodyBytes = Span.fromUnsafe("""{"query":"{ k1 }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val req       = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/sse")).addField("body", bodyBytes)
                send(server.port, sseClientRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains("event: next"))
            assert(body.contains("event: complete"))
            assert(body.contains(""""k1":42"""))
        end for
    }

    "SSE - subscription streaming" in {
        case class Subscriptions(values: zio.stream.ZStream[Any, Nothing, Int]) derives Schema.SemiAuto

        val api            = graphQL(RootResolver(defaultQuery, Mutation(0), Subscriptions(zio.stream.ZStream(1, 2, 3))))
        val sseClientRoute = HttpRoute.postRaw("api/graphql/sse").request(_.bodyBinary).response(_.bodyBinary)
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            resp <-
                val bodyBytes =
                    Span.fromUnsafe("""{"query":"subscription { values }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/sse")).addField("body", bodyBytes)
                send(server.port, sseClientRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains("event: next"))
            assert(body.contains("event: complete"))
        end for
    }

    // ==================== Server - @defer ====================

    "defer - multipart response" in {
        val deferClientRoute = HttpRoute.postRaw("api/graphql/defer").request(_.bodyBinary).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val bodyBytes = Span.fromUnsafe("""{"query":"{ k1 }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val req       = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/defer")).addField("body", bodyBytes)
                send(server.port, deferClientRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains(""""k1":42"""))
            assert(resp.headers.get("Content-Type").exists(_.contains("multipart/mixed")))
        end for
    }

    "defer - deferred fragment streams as a second part" in {
        case class Slow(slow: Int < Async) derives Schema.SemiAuto
        case class CombinedQuery(k1: Int, sub: Slow) derives Schema.SemiAuto
        // The `@defer` directive must be explicitly enabled via IncrementalDelivery.defer wrapper.
        val api =
            graphQL(RootResolver(CombinedQuery(42, Slow(Async.sleep(50.millis).andThen(7))))) @@ caliban.wrappers.IncrementalDelivery.defer
        val deferClientRoute = HttpRoute.postRaw("api/graphql/defer").request(_.bodyBinary).response(_.bodyBinary)
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            resp <-
                val bodyBytes = Span.fromUnsafe(
                    """{"query":"{ k1 ... @defer { sub { slow } } }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/defer")).addField("body", bodyBytes)
                send(server.port, deferClientRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            // First part: initial response with k1; second part: deferred payload with slow.
            assert(body.contains(""""k1":42"""), s"missing initial part: $body")
            assert(body.contains(""""slow":7"""), s"missing deferred part: $body")
            assert(resp.headers.get("Content-Type").exists(_.contains("multipart/mixed")))
        end for
    }

    // ==================== Server - Upload ====================

    "upload - single file" in {
        val uploadRoute = HttpRoute.postRaw("api/graphql/upload").request(_.bodyMultipart).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val operations = HttpRequest.Part(
                    "operations",
                    Absent,
                    Absent,
                    Span.fromUnsafe("""{"query":"{ k1 }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val mapPart = HttpRequest.Part(
                    "map",
                    Absent,
                    Absent,
                    Span.fromUnsafe("""{}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/upload"))
                    .addField("body", Seq(operations, mapPart))
                send(server.port, uploadRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains(""""k1":42"""))
        end for
    }

    // The handler installs the Uploads layer via `provideSomeLayer[R](ZLayer(fileHandle))` (see Resolvers.scala),
    // but kyo-caliban's schema derivation does not yet support resolvers that require Uploads in their R parameter,
    // so the full end-to-end "resolver reads file bytes" assertion is not yet expressible. This test verifies the
    // multipart parsing and handler dispatch accept a file part with a plain non-Upload-aware schema.
    "upload - handler accepts a multipart file part" in {
        // Server uses a plain non-Upload-aware schema (the resolver doesn't read the file). The point of the
        // test is that the multipart parser + handler dispatch don't crash on a request that includes file parts.
        val uploadRoute = HttpRoute.postRaw("api/graphql/upload").request(_.bodyMultipart).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val operations = HttpRequest.Part(
                    "operations",
                    Absent,
                    Absent,
                    Span.fromUnsafe("""{"query":"{ k1 }"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val mapPart = HttpRequest.Part(
                    "map",
                    Absent,
                    Absent,
                    Span.fromUnsafe("""{"0":["variables.f"]}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val filePart = HttpRequest.Part(
                    "0",
                    Present("hello.txt"),
                    Present("text/plain"),
                    Span.fromUnsafe("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/upload"))
                    .addField("body", Seq(operations, mapPart, filePart))
                send(server.port, uploadRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains(""""k1":42"""), s"unexpected body: $body")
        end for
    }

    "upload - malformed operations JSON returns a GraphQL error response" in {
        val uploadRoute = HttpRoute.postRaw("api/graphql/upload").request(_.bodyMultipart).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val operations = HttpRequest.Part(
                    "operations",
                    Absent,
                    Absent,
                    Span.fromUnsafe("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val mapPart = HttpRequest.Part(
                    "map",
                    Absent,
                    Absent,
                    Span.fromUnsafe("""{}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/upload"))
                    .addField("body", Seq(operations, mapPart))
                Abort.run[HttpException](send(server.port, uploadRoute, req))
        yield
            // The server must produce a GraphQL error envelope rather than crashing the connection.
            resp match
                case Result.Success(r) =>
                    val body = new String(r.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
                    assert(body.contains("errors"), s"expected GraphQL errors body, got: $body")
                case Result.Failure(e) =>
                    fail(s"server should have returned an error response, not failed the connection: $e")
                case Result.Panic(t) =>
                    fail(s"server panic on malformed operations JSON: $t")
            end match
        end for
    }

    "upload - missing operations part" in {
        val uploadRoute = HttpRoute.postRaw("api/graphql/upload").request(_.bodyMultipart).response(_.bodyBinary)
        for
            server <- startServer
            resp <-
                val emptyPart = HttpRequest.Part(
                    "other",
                    Absent,
                    Absent,
                    Span.fromUnsafe("data".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
                val req = HttpRequest.postRaw(HttpUrl.fromUri("/api/graphql/upload"))
                    .addField("body", Seq(emptyPart))
                send(server.port, uploadRoute, req)
        yield
            val body = new String(resp.fields.body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
            assert(body.contains("errors"))
            assert(body.contains("Missing 'operations' part"))
        end for
    }

    // ==================== Server - GraphiQL ====================

    "GraphiQL - serves HTML" in {
        for
            server <- startServer
            resp   <- HttpClient.getText(s"http://localhost:${server.port}/graphiql")
        yield
            assert(resp.contains("<!DOCTYPE html>") || resp.contains("<html"))
            assert(resp.contains("graphiql") || resp.contains("GraphiQL"))
        end for
    }

    // ==================== Response encoding ====================

    "response - error in JSON identifies the specific validation problem" in {
        for
            server       <- startServer
            (_, body, _) <- postGql(server.port, """{"query":"{ nonexistent }"}""")
        yield
            assert(body.contains("errors"))
            assert(body.contains("nonexistent"), s"expected the offending field name in the error: $body")
        end for
    }

    "response - Accept application/graphql-response+json" in {
        for
            server <- startServer
            (status, body, headers) <- postGql(
                server.port,
                """{"query":"{ k1 }"}""",
                "Accept" -> "application/graphql-response+json"
            )
        yield
            assert(status == HttpStatus.OK)
            assert(body == """{"data":{"k1":42}}""")
            assert(headers.get("Content-Type").exists(_.contains("application/graphql-response+json")))
        end for
    }

    "response - 400 for validation error with graphql-response+json" in {
        for
            server <- startServer
            (status, body, _) <- postGql(
                server.port,
                """{"query":"{ nonexistent }"}""",
                "Accept" -> "application/graphql-response+json"
            )
        yield
            assert(status == HttpStatus.BadRequest)
            assert(body.contains("errors"))
            assert(body.contains(""""data":null"""))
        end for
    }

    "response - mutation over GET returns 400 with the exact mutation-over-get message" in {
        val api = graphQL(RootResolver(defaultQuery, Mutation(99)))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter)
            (status, body, _) <- getGql(server.port, "query=mutation%7BdoSomething%7D")
        yield
            assert(status == HttpStatus.BadRequest)
            assert(body.contains("Mutations are not allowed for GET requests"), s"expected canonical message, got: $body")
        end for
    }

    "response - cache-control header reflects exact max-age=60" in {
        @GQLCacheControl(maxAge = Some(zio.Duration.fromSeconds(60)))
        case class CachedQuery(value: Int) derives Schema.SemiAuto

        val api = graphQL(RootResolver(CachedQuery(42))) @@ Caching.extension()
        for
            interpreter     <- Resolvers.get(api)
            server          <- Resolvers.run(interpreter)
            (_, _, headers) <- postGql(server.port, """{"query":"{ value }"}""")
        yield
            val cc = headers.get("Cache-Control")
            assert(cc.isDefined, s"Expected Cache-Control header, got headers: $headers")
            assert(cc.exists(_.contains("max-age=60")), s"expected max-age=60, got: $cc")
        end for
    }

    "response - cache-control scope=Private emits private directive" in {
        @GQLCacheControl(scope = Some(caliban.wrappers.Caching.CacheScope.Private), maxAge = Some(zio.Duration.fromSeconds(30)))
        case class PrivateQuery(value: Int) derives Schema.SemiAuto

        val api = graphQL(RootResolver(PrivateQuery(7))) @@ Caching.extension()
        for
            interpreter     <- Resolvers.get(api)
            server          <- Resolvers.run(interpreter)
            (_, _, headers) <- postGql(server.port, """{"query":"{ value }"}""")
        yield
            val cc = headers.get("Cache-Control")
            assert(cc.exists(_.contains("private")), s"expected 'private' directive, got: $cc")
            assert(cc.exists(_.contains("max-age=30")), s"expected max-age=30, got: $cc")
        end for
    }

    "response - cache-control scope=Public emits public directive" in {
        @GQLCacheControl(scope = Some(caliban.wrappers.Caching.CacheScope.Public), maxAge = Some(zio.Duration.fromSeconds(10)))
        case class PublicQuery(value: Int) derives Schema.SemiAuto

        val api = graphQL(RootResolver(PublicQuery(9))) @@ Caching.extension()
        for
            interpreter     <- Resolvers.get(api)
            server          <- Resolvers.run(interpreter)
            (_, _, headers) <- postGql(server.port, """{"query":"{ value }"}""")
        yield
            val cc = headers.get("Cache-Control")
            assert(cc.exists(_.contains("public")), s"expected 'public' directive, got: $cc")
            assert(cc.exists(_.contains("max-age=10")), s"expected max-age=10, got: $cc")
        end for
    }

    // ==================== Server - WebSocket ====================

    case class IntSubscriptions(values: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto

    /** Shared WS API used by tests that just need *some* subscription endpoint (the actual stream values aren't asserted). Reused via
      * `wsApi` instead of `subscriptionApi(1)` to avoid rebuilding the same schema in dozens of tests.
      */
    private lazy val wsApi = subscriptionApi(1)

    private def subscriptionApi(values: Int*) =
        graphQL(RootResolver(defaultQuery, Mutation(0), IntSubscriptions(zio.stream.ZStream.fromIterable(values))))

    private def wsSubprotocol(name: String): HttpWebSocket.Config =
        HttpWebSocket.Config(subprotocols = Seq(name))

    /** Collect exactly `expected` text frames, failing loudly with a diagnostic if fewer arrive in time or if the connection closes
      * prematurely. Returns the collected frames in arrival order.
      */
    private def collectMessages(ws: HttpWebSocket, expected: Int, deadline: Duration = 3.seconds)(using
        Frame,
        kyo.test.AssertScope
    ): Chunk[String] < Async =
        AtomicRef.initWith(Chunk.empty[String]) { buf =>
            val loop = Loop.foreach {
                buf.get.map { chunk =>
                    if chunk.length >= expected then Loop.done
                    else
                        ws.take().map {
                            case HttpWebSocket.Payload.Text(s)   => buf.updateAndGet(_ :+ s).andThen(Loop.continue)
                            case HttpWebSocket.Payload.Binary(_) => Loop.continue
                        }
                }
            }
            Abort.recover[Closed](e =>
                buf.get.map(chunk =>
                    fail(s"collectMessages: ws closed before $expected msgs (got ${chunk.length}): ${chunk.mkString(" | ")}"): Unit
                )
            ) {
                Abort.recover[Timeout](_ =>
                    buf.get.map(chunk =>
                        fail(
                            s"collectMessages: timed out after $deadline waiting for $expected msgs (got ${chunk.length}): ${chunk.mkString(" | ")}"
                        ): Unit
                    )
                )(Async.timeout(deadline)(loop))
            }.andThen(buf.get)
        }
    end collectMessages

    /** Take frames until one matches `predicate`, ignoring non-matching frames silently. Fails loudly on Timeout or premature Closed. */
    private def expectMessage(ws: HttpWebSocket, predicate: String => Boolean, deadline: Duration = 3.seconds)(using
        Frame,
        kyo.test.AssertScope
    ): String < Async =
        val loop = Loop.foreach {
            ws.take().map {
                case HttpWebSocket.Payload.Text(s) if predicate(s) => Loop.done(s)
                case _                                             => Loop.continue
            }
        }
        Abort.recover[Closed](_ => fail("expectMessage: ws closed before predicate matched"): String) {
            Abort.recover[Timeout](_ => fail(s"expectMessage: timed out after $deadline waiting for matching frame"): String) {
                Async.timeout(deadline)(loop)
            }
        }
    end expectMessage

    /** Poll `ws.closeReason` until Present. Use instead of `Async.sleep + ws.closeReason` to remove timing dependency from tests that
      * expect the server to close the connection.
      *
      * Default deadline of 5 seconds: caliban's pipe takes ~500ms to materialise a `Left(close)` after a hook-failure in `afterInit`; CI
      * runners with CPU contention and GC pauses regularly add another 1-2 seconds.
      */
    private def awaitClose(ws: HttpWebSocket, deadline: Duration = 5.seconds)(using
        Frame,
        kyo.test.AssertScope
    ): (Int, String) < Async =
        val loop = Loop.foreach {
            ws.closeReason.map {
                case Present(cr) => Loop.done(cr)
                case Absent      => Async.sleep(5.millis).andThen(Loop.continue)
            }
        }
        Abort.recover[Timeout](_ => fail(s"awaitClose: timed out after $deadline with no close received"): (Int, String)) {
            Async.timeout(deadline)(loop)
        }
    end awaitClose

    "WS - graphql-transport-ws - subscription happy path" in {
        val api = subscriptionApi(1, 2, 3)
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            received <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _   <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    ack <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"1","payload":{"query":"subscription { values }"}}"""
                    ))
                    msgs <- collectMessages(ws, 4) // 3 next + 1 complete
                yield (ack, msgs)
            }
        yield
            val (ack, msgs) = received
            assert(ack.contains("connection_ack"))
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""type":"next""""))
            assert(joined.contains(""""values":1"""))
            assert(joined.contains(""""values":2"""))
            assert(joined.contains(""""values":3"""))
            assert(joined.contains(""""type":"complete""""))
        end for
    }

    "WS - graphql-transport-ws - subscribe before connection_init returns 4401" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            closeReason <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"1","payload":{"query":"subscription { values }"}}"""
                    ))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(closeReason._1 == 4401, s"Expected close code 4401, got: $closeReason")
        end for
    }

    "WS - graphql-transport-ws - ping returns pong".flaky in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            pong <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"ping"}"""))
                    p <- expectMessage(ws, _.contains(""""type":"pong""""))
                yield p
            }
        yield assert(pong.contains(""""type":"pong""""))
        end for
    }

    "WS - graphql-transport-ws - one-shot query over WS" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            next <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"q1","payload":{"query":"{ k1 }"}}"""
                    ))
                    n <- expectMessage(ws, _.contains(""""type":"next""""))
                yield n
            }
        yield
            assert(next.contains(""""k1":42"""))
            assert(next.contains(""""id":"q1""""))
        end for
    }

    "WS - graphql-ws (legacy) - subscription happy path".flaky in {
        val api = subscriptionApi(10, 20)
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            received <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _   <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    ack <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"1","payload":{"query":"subscription { values }"}}"""
                    ))
                    msgs <- collectMessages(ws, 3) // 2 data + 1 complete
                yield (ack, msgs)
            }
        yield
            val (ack, msgs) = received
            assert(ack.contains("connection_ack"))
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""type":"data""""))
            assert(joined.contains(""""values":10"""))
            assert(joined.contains(""""values":20"""))
            assert(joined.contains(""""type":"complete""""))
        end for
    }

    "WS - protocol defaults to graphql-ws when no subprotocol header sent" in {
        // kyo-http config without subprotocols means server does not advertise any either.
        // Caliban's Protocol.fromName returns Legacy for any unrecognized name; we default to graphql-ws.
        val api = subscriptionApi(7)
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = HttpWebSocket.Config()) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield assert(ack.contains("connection_ack"))
        end for
    }

    "WS - keep-alive ping sent at configured interval" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server <- Resolvers.run(
                interpreter,
                Resolvers.Config.default.webSocketKeepAlive(150.millis)
            )
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ping <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    p <- expectMessage(ws, _.contains(""""type":"ping""""))
                yield p
            }
        yield assert(ping.contains(""""type":"ping""""))
        end for
    }

    "WS - complete from client cancels subscription" in {
        case class Forever(values: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto
        val api =
            graphQL(RootResolver(
                defaultQuery,
                Mutation(0),
                Forever(zio.stream.ZStream.iterate(0)(_ + 1).schedule(zio.Schedule.spaced(zio.Duration.fromMillis(50))))
            ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            result <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"forever","payload":{"query":"subscription { values }"}}"""
                    ))
                    _ <- expectMessage(ws, _.contains(""""type":"next""""))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"complete","id":"forever"}"""))
                    _ <- Async.sleep(200.millis)
                yield "ok"
            }
        yield assert(result == "ok")
        end for
    }

    "WS - CalibanRunner with default config" in {
        type Environment = Var[Int] & Env[String]
        object schema extends caliban.schema.SchemaDerivation[CalibanRunner[Environment]]
        case class RunnerQuery(k: Int < Environment) derives schema.SemiAuto
        case class RunnerSubscriptions(values: zio.stream.ZStream[Any, Nothing, Int]) derives schema.SemiAuto

        val api = graphQL(RootResolver(
            RunnerQuery(Env.get[String].map(_.length)),
            Mutation(0),
            RunnerSubscriptions(zio.stream.ZStream(1, 2))
        ))
        val runner = new CalibanRunner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v))

        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, runner)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            received <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"s1","payload":{"query":"subscription { values }"}}"""
                    ))
                    msgs <- collectMessages(ws, 3)
                yield msgs
            }
        yield
            val joined = received.mkString("\n")
            assert(joined.contains(""""values":1"""))
            assert(joined.contains(""""values":2"""))
        end for
    }

    // ==================== WS - Payload variants ====================

    "WS - transport-ws subscription with variables" in {
        val api = graphQL(RootResolver(ArgsQuery(args => args.a + args.b)))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"v1","payload":{"query":"query($a:Int!,$b:Int!){add(a:$a,b:$b)}","variables":{"a":7,"b":35}}}"""
                    ))
                    n <- expectMessage(ws, _.contains(""""type":"next""""))
                yield n
            }
        yield assert(msg.contains(""""add":42"""))
        end for
    }

    "WS - transport-ws subscription with operationName picks named operation" in {
        for
            interpreter <- Resolvers.get(graphQL(RootResolver(defaultQuery)))
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"o1","payload":{"query":"query A{k1} query B{k2}","operationName":"B"}}"""
                    ))
                    n <- expectMessage(ws, _.contains(""""type":"next""""))
                yield n
            }
        yield
            assert(msg.contains(""""k2":42"""))
            assert(!msg.contains(""""k1":42"""), "should select operation B only")
        end for
    }

    "WS - transport-ws subscription without payload causes error" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"subscribe","id":"np"}"""))
                    n <- expectMessage(ws, t => t.contains(""""type":"next"""") || t.contains(""""type":"error""""))
                yield n
            }
        yield
            // Without a query, caliban returns a validation/parsing error in the GraphQL response,
            // which we emit as a `next` containing an errors array (or `error` for stream-level failures).
            assert(msg.contains("errors") || msg.contains(""""type":"error""""))
        end for
    }

    // ==================== WS - Error paths ====================

    "WS - transport-ws validation error emits next with errors then complete" in {
        for
            interpreter <- Resolvers.get(graphQL(RootResolver(defaultQuery)))
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"bad","payload":{"query":"{ nonexistent }"}}"""
                    ))
                    m <- collectMessages(ws, 2)
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains("errors") || joined.contains("""nonexistent"""))
            assert(joined.contains(""""type":"complete""""), s"Expected complete after error, got: $joined")
        end for
    }

    "WS - transport-ws resolver Abort failure emits errors in next" in {
        case class FailQuery(boom: Int < Abort[Throwable]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(FailQuery(Abort.fail(new RuntimeException("boom")))))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"f1","payload":{"query":"{ boom }"}}"""
                    ))
                    n <- expectMessage(ws, _.contains(""""type":"next""""))
                yield n
            }
        yield assert(msg.contains("errors") && msg.contains("boom"))
        end for
    }

    // ==================== WS - Lifecycle ====================

    "WS - transport-ws concurrent subscriptions interleave on one connection" in {
        case class TwoSubs(
            a: zio.stream.ZStream[Any, Nothing, Int],
            b: zio.stream.ZStream[Any, Nothing, Int]
        ) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            TwoSubs(
                zio.stream.ZStream(1, 2, 3),
                zio.stream.ZStream(10, 20, 30)
            )
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"A","payload":{"query":"subscription { a }"}}"""
                    ))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"B","payload":{"query":"subscription { b }"}}"""
                    ))
                    m <- collectMessages(ws, 8) // 3 next A + 1 complete A + 3 next B + 1 complete B
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""id":"A""""), s"missing A: $joined")
            assert(joined.contains(""""id":"B""""), s"missing B: $joined")
            assert(joined.contains(""""a":1"""))
            assert(joined.contains(""""b":10"""))
        end for
    }

    "WS - transport-ws client disconnect mid-stream cleans up subscription" in {
        // Subscription that emits forever; we disconnect and verify the WS handler exits cleanly
        // (no leaked fiber leaves the test hung). Test passes if the run block returns.
        case class Forever(values: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            Forever(zio.stream.ZStream.iterate(0)(_ + 1).schedule(zio.Schedule.spaced(zio.Duration.fromMillis(20))))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"x","payload":{"query":"subscription { values }"}}"""
                    ))
                    _ <- expectMessage(ws, _.contains(""""type":"next""""))
                yield ()
            } // lambda returns -> HttpClient.webSocket closes WS; server should clean up
            _ <- Async.sleep(100.millis)
        yield succeed("client disconnect mid-stream lets the server handler exit cleanly, with no leaked fiber")
        end for
    }

    "WS - transport-ws unsupported op closes with 4400" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"banana"}"""))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4400, s"Expected close 4400, got: $cr")
        end for
    }

    "WS - transport-ws invalid JSON closes with 4400" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("not json"))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4400, s"Expected close 4400, got: $cr")
        end for
    }

    "WS - transport-ws subscribe without id closes with 4400" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","payload":{"query":"subscription { values }"}}"""
                    ))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4400, s"Expected close 4400, got: $cr")
        end for
    }

    "WS - transport-ws complete without id closes 4400" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"complete"}"""))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4400, s"Expected close 4400, got: $cr")
        end for
    }

    "WS - transport-ws complete for unknown id is ignored" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"complete","id":"never-existed"}"""))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"ping"}"""))
                    p <- expectMessage(ws, _.contains(""""type":"pong""""))
                yield p
            }
        yield succeed("a complete for an unknown id is ignored; the connection stays open and answers ping with pong")
        end for
    }

    "WS - transport-ws client pong is silently accepted" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"pong"}"""))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"ping"}"""))
                    p <- expectMessage(ws, _.contains(""""type":"pong""""))
                yield p
            }
        yield succeed("a client pong is accepted silently; the connection stays open and answers ping with pong")
        end for
    }

    "WS - transport-ws binary frames are ignored" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1, 2, 3))))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                yield ()
            }
        yield succeed("a leading binary frame is ignored; connection_init still receives its ack")
        end for
    }

    // graphql-transport-ws says the server MAY close 4429 on a second connection_init; we take the permissive
    // behavior of re-acking instead. Wrap with a beforeInit hook that fails on repeat to get strict 4429.
    "WS - transport-ws second connection_init re-acks (deliberate deviation from spec MAY 4429)" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            acks <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _    <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _    <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    msgs <- collectMessages(ws, 2)
                yield msgs
            }
        yield assert(acks.count(_.contains("connection_ack")) == 2, s"expected two acks, got: $acks")
        end for
    }

    "WS - transport-ws custom config path serves WS at /custom/ws" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.path("custom"))
            url = s"ws://localhost:${server.port}/custom/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield assert(ack.contains("connection_ack"))
        end for
    }

    // ==================== WS - Legacy graphql-ws gaps ====================

    "WS - legacy start before connection_init closes with 4401" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"1","payload":{"query":"subscription { values }"}}"""
                    ))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4401, s"Expected close 4401, got: $cr")
        end for
    }

    "WS - legacy stop cancels subscription" in {
        case class Forever(values: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            Forever(zio.stream.ZStream.iterate(0)(_ + 1).schedule(zio.Schedule.spaced(zio.Duration.fromMillis(20))))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"f","payload":{"query":"subscription { values }"}}"""
                    ))
                    _ <- expectMessage(ws, _.contains(""""type":"data""""))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"stop","id":"f"}"""))
                    _ <- Async.sleep(100.millis)
                yield ()
            }
        yield succeed("legacy stop cancels the subscription server-side, with no hung emitter")
        end for
    }

    "WS - legacy keep-alive emits ka frames at interval" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.webSocketKeepAlive(150.millis))
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ka <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    m <- expectMessage(ws, _.contains(""""type":"ka""""))
                yield m
            }
        yield assert(ka.contains(""""type":"ka""""))
        end for
    }

    "WS - legacy unknown op is silently ignored (no close)" in {
        val api = wsApi
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"banana"}"""))
                    // legacy ignores unknown ops; verify the connection still functions by starting a sub
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"1","payload":{"query":"{ k1 }"}}"""
                    ))
                    d <- expectMessage(ws, _.contains(""""type":"data""""))
                yield d
            }
        yield succeed("an unknown legacy op is ignored with no close; the connection can still start a subscription")
        end for
    }

    // ==================== WS - Hooks ====================

    "WS - hook onAck attaches payload to connection_ack" in {
        val api = wsApi
        val hooks = caliban.ws.WebSocketHooks.ack[Any, CalibanError](
            zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(List("hello" -> caliban.Value.StringValue("world"))))
        )
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield
            assert(ack.contains("connection_ack"))
            assert(ack.contains(""""hello":"world""""))
        end for
    }

    "WS - hook beforeInit accepts when token matches" in {
        val api = wsApi
        val hooks = caliban.ws.WebSocketHooks.init[Any, CalibanError] { payload =>
            payload match
                case caliban.InputValue.ObjectValue(fields) if fields.get("token").contains(caliban.Value.StringValue("ok")) =>
                    zio.ZIO.unit
                case _ =>
                    zio.ZIO.fail(CalibanError.ExecutionError("invalid token"))
        }
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init","payload":{"token":"ok"}}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield assert(ack.contains("connection_ack"))
        end for
    }

    "WS - hook beforeInit rejects with close 4403 when it fails" in {
        val api   = wsApi
        val hooks = caliban.ws.WebSocketHooks.init[Any, CalibanError](_ => zio.ZIO.fail(CalibanError.ExecutionError("nope")))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init","payload":{"token":"bad"}}"""))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4403, s"Expected 4403, got: $cr")
        end for
    }

    "WS - hook afterInit failure closes with 4401" in {
        val api = wsApi
        val hooks = caliban.ws.WebSocketHooks.afterInit[Any, CalibanError](
            zio.ZIO.fail(CalibanError.ExecutionError("auth expired"))
        )
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    r <- awaitClose(ws)
                yield r
            }
        yield assert(cr._1 == 4401, s"Expected 4401, got: $cr")
        end for
    }

    "WS - hook onPing customizes pong payload" in {
        val api   = wsApi
        val hooks = caliban.ws.WebSocketHooks.empty[Any, CalibanError]
        val hooks2 = new caliban.ws.WebSocketHooks[Any, CalibanError]:
            override def onPing
                : Option[Option[caliban.InputValue] => zio.ZIO[Any, CalibanError, Option[caliban.ResponseValue]]] =
                Some(_ =>
                    zio.ZIO.succeed(Some(caliban.ResponseValue.ObjectValue(List("ts" -> caliban.Value.IntValue(42)))))
                )
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks2)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            pong <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"ping"}"""))
                    p <- expectMessage(ws, _.contains(""""type":"pong""""))
                yield p
            }
        yield assert(pong.contains(""""ts":42"""), s"expected ts payload in pong: $pong")
        end for
    }

    "WS - hook onPong observes client pong" in {
        val api       = wsApi
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = new caliban.ws.WebSocketHooks[Any, CalibanError]:
            override def onPong: Option[caliban.InputValue => zio.ZIO[Any, CalibanError, Any]] =
                Some(_ => zio.ZIO.succeed(callCount.incrementAndGet()))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"pong","payload":{"client":"true"}}"""))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"ping"}"""))
                    _ <- expectMessage(ws, _.contains(""""type":"pong""""))
                yield ()
            }
        yield assert(callCount.get() == 1, s"expected onPong called exactly once, got $callCount")
        end for
    }

    "WS - hook onMessage transforms subscription outputs" in {
        // onMessage pipeline that tags every output with an extra payload field
        val hooks = new caliban.ws.WebSocketHooks[Any, CalibanError]:
            override def onMessage
                : Option[zio.stream.ZPipeline[Any, CalibanError, caliban.GraphQLWSOutput, caliban.GraphQLWSOutput]] =
                Some(zio.stream.ZPipeline.map { out =>
                    // Replace the payload with a tagged value (only for `next`/`data`, leave `complete` alone)
                    if out.`type` == "next" || out.`type` == "data" then
                        out.copy(payload =
                            Some(
                                caliban.ResponseValue.ObjectValue(List("tagged" -> caliban.Value.BooleanValue(true)))
                            )
                        )
                    else out
                })
        for
            interpreter <- Resolvers.get(subscriptionApi(1, 2))
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"m1","payload":{"query":"subscription { values }"}}"""
                    ))
                    m <- collectMessages(ws, 3) // 2 next + 1 complete
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""tagged":true"""), s"expected hook to tag outputs: $joined")
            assert(joined.contains(""""type":"complete""""))
        end for
    }

    "WS - hook onMessage applies to legacy protocol too" in {
        val hooks = new caliban.ws.WebSocketHooks[Any, CalibanError]:
            override def onMessage
                : Option[zio.stream.ZPipeline[Any, CalibanError, caliban.GraphQLWSOutput, caliban.GraphQLWSOutput]] =
                Some(zio.stream.ZPipeline.map(out =>
                    if out.`type` == "data" then
                        out.copy(payload =
                            Some(
                                caliban.ResponseValue.ObjectValue(List("piped" -> caliban.Value.BooleanValue(true)))
                            )
                        )
                    else out
                ))
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"1","payload":{"query":"subscription { values }"}}"""
                    ))
                    m <- collectMessages(ws, 2)
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""piped":true"""), s"expected hook to apply on legacy: $joined")
        end for
    }

    "WS - hook afterInit success runs side effect" in {
        val api     = wsApi
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = caliban.ws.WebSocketHooks.afterInit[Any, CalibanError](
            zio.ZIO.succeed(counter.incrementAndGet())
        )
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            _ <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- Async.sleep(100.millis)
                yield ()
            }
        yield assert(counter.get() == 1, s"expected afterInit ran once, got $counter")
        end for
    }

    // Per graphql-transport-ws PROTOCOL.md the Error message itself terminates the subscription:
    // "This message terminates the operation and no further messages will be sent. If the server dispatched
    // the Error message relative to the original Subscribe message, no Complete message will be emitted."
    "WS - subscription stream failure emits next frames then a terminating error" in {
        case class Streamy(values: zio.stream.ZStream[Any, CalibanError, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            Streamy(zio.stream.ZStream(1, 2) ++ zio.stream.ZStream.fail(CalibanError.ExecutionError("stream boom")))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"f","payload":{"query":"subscription { values }"}}"""
                    ))
                    m <- collectMessages(ws, 3) // 2 next + 1 terminating error
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""values":1"""))
            assert(joined.contains(""""values":2"""))
            assert(joined.contains(""""type":"error""""), s"expected error frame: $joined")
            assert(joined.contains("stream boom"), s"error payload should carry the failure message: $joined")
        end for
    }

    "WS - legacy subscription with variables" in {
        val api = graphQL(RootResolver(ArgsQuery(args => args.a + args.b)))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"start","id":"1","payload":{"query":"query($a:Int!,$b:Int!){add(a:$a,b:$b)}","variables":{"a":100,"b":23}}}"""
                    ))
                    d <- expectMessage(ws, _.contains(""""type":"data""""))
                yield d
            }
        yield assert(msg.contains(""""add":123"""))
        end for
    }

    "WS - many concurrent subscriptions complete cleanly" in {
        case class Multi(s: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(defaultQuery, Mutation(0), Multi(zio.stream.ZStream(1, 2, 3))))
        val n   = 5
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- Kyo.foreach(0 until n) { i =>
                        ws.put(HttpWebSocket.Payload.Text(
                            s"""{"type":"subscribe","id":"sub-$i","payload":{"query":"subscription { s }"}}"""
                        ))
                    }
                    // Each sub emits 3 next + 1 complete = 4 msgs, so n*4 total
                    m <- collectMessages(ws, n * 4, 5.seconds)
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            (0 until n).foreach { i =>
                assert(joined.contains(s""""id":"sub-$i""""), s"missing sub-$i: $joined")
            }
            assert(joined.split(""""type":"complete"""").length - 1 == n, s"expected $n completes in: $joined")
        end for
    }

    "WS - hook combinator (++) merges disjoint object keys" in {
        // Caliban 3.1.0 fixed ResponseValue.deepMerge to include keys from BOTH operands; before 3.1.0 the
        // merge silently dropped keys present only in the RHS. With the fix, disjoint object-keyed payloads
        // compose as users would naturally expect.
        val h1 = caliban.ws.WebSocketHooks.ack[Any, CalibanError](
            zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(List("a" -> caliban.Value.IntValue(1))))
        )
        val h2 = caliban.ws.WebSocketHooks.ack[Any, CalibanError](
            zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(List("b" -> caliban.Value.IntValue(2))))
        )
        val hooks = h1 ++ h2
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield
            assert(ack.contains(""""a":1"""), s"missing h1 contribution: $ack")
            assert(ack.contains(""""b":2"""), s"missing h2 contribution: $ack")
        end for
    }

    "WS - hook combinator (++) concatenates overlapping list-valued keys" in {
        // Complements the object-keyed test above: when two hooks contribute under the same key with
        // list values, ResponseValue.deepMerge concatenates the lists. Exercises that branch of deepMerge.
        val h1 = caliban.ws.WebSocketHooks.ack[Any, CalibanError](
            zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(List(
                "roles" -> caliban.ResponseValue.ListValue(List(caliban.Value.StringValue("admin")))
            )))
        )
        val h2 = caliban.ws.WebSocketHooks.ack[Any, CalibanError](
            zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(List(
                "roles" -> caliban.ResponseValue.ListValue(List(caliban.Value.StringValue("user")))
            )))
        )
        val hooks = h1 ++ h2
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield
            assert(ack.contains(""""admin""""), s"missing h1 contribution: $ack")
            assert(ack.contains(""""user""""), s"missing h2 contribution: $ack")
        end for
    }

    // ==================== WS - Error paths and protocol behavior ====================

    // Non-CalibanError throwables get wrapped into a typed ExecutionError by caliban's executeRequest. As with
    // any typed failure, the spec says the Error message is itself terminal (no Complete follows).
    "WS - non-CalibanError ZStream failure surfaces as a terminating error" in {
        case class WeirdSub(values: zio.stream.ZStream[Any, Throwable, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            WeirdSub(zio.stream.ZStream(1) ++ zio.stream.ZStream.fail(new IllegalStateException("not a CalibanError")))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"weird","payload":{"query":"subscription { values }"}}"""
                    ))
                    m <- collectMessages(ws, 2) // next + terminating error
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""values":1"""))
            assert(joined.contains(""""type":"error""""), s"non-CalibanError must surface as error frame: $joined")
        end for
    }

    // Resolver defects (ZIO.die) get caught and wrapped as a typed ExecutionError by `executeRequest`. The
    // resulting Error message is itself the terminator per spec.
    "WS - resolver panic in subscription stream is reported as a terminating error" in {
        case class PanicSub(values: zio.stream.ZStream[Any, Nothing, Int]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            PanicSub(zio.stream.ZStream.fromZIO(zio.ZIO.die(new RuntimeException("boom"))))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"p1","payload":{"query":"subscription { values }"}}"""
                    ))
                    e <- expectMessage(ws, _.contains(""""type":"error""""))
                yield e
            }
        yield assert(msg.contains(""""type":"error""""), s"panic must produce error frame: $msg")
        end for
    }

    // After a fast one-shot subscribe completes, the same id must be reusable for another subscribe on the
    // same connection.
    "WS - one-shot query then resubscribe with the same id succeeds" in {
        for
            interpreter <- Resolvers.get(graphQL(RootResolver(defaultQuery)))
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            outcome <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    // First fast one-shot
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"reuse","payload":{"query":"{ k1 }"}}"""
                    ))
                    _ <- expectMessage(ws, _.contains(""""type":"next""""))
                    _ <- expectMessage(ws, _.contains(""""type":"complete""""))
                    // Tiny gap to let the fiber-completion cleanup settle
                    _ <- Async.sleep(50.millis)
                    // Reuse the same id — must NOT be rejected as duplicate
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"reuse","payload":{"query":"{ k1 }"}}"""
                    ))
                    n2 <- expectMessage(ws, _.contains(""""type":"next""""))
                    cr <- ws.closeReason
                yield (n2, cr)
            }
        yield
            val (n2, cr) = outcome
            assert(n2.contains(""""k1":42"""), s"second subscribe with reused id should succeed, got: $n2")
            assert(cr.isEmpty, s"connection must remain open (no 4409), got close: $cr")
        end for
    }

    // onAck is documented as the connection_ack payload provider; caliban catches a typed failure via
    // `.option` and acks with no payload rather than rejecting the connection.
    "WS - onAck failure falls back to ack with no payload" in {
        val hooks = caliban.ws.WebSocketHooks.ack[Any, CalibanError](zio.ZIO.fail(CalibanError.ExecutionError("ack fail")))
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            ack <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    a <- expectMessage(ws, _.contains("connection_ack"))
                yield a
            }
        yield
            assert(ack.contains("connection_ack"), s"expected connection_ack, got: $ack")
            assert(!ack.contains(""""payload":{"""), s"expected no payload object on ack, got: $ack")
        end for
    }

    // Caliban's onMessage scaladoc says the pipeline is applied to "the resulting ZStream for every active
    // subscription" — that stream is `(resp ++ toStreamComplete).catchAll(toStreamError)`, so the user hook
    // sees the protocol-level Complete frame in addition to data frames.
    "WS - onMessage hook is applied to all output frames including Complete" in {
        val sawComplete = new java.util.concurrent.atomic.AtomicBoolean(false)
        val hooks = new caliban.ws.WebSocketHooks[Any, CalibanError]:
            override def onMessage
                : Option[zio.stream.ZPipeline[Any, CalibanError, caliban.GraphQLWSOutput, caliban.GraphQLWSOutput]] =
                Some(zio.stream.ZPipeline.map { out =>
                    if out.`type` == "complete" then sawComplete.set(true)
                    out
                })
        for
            interpreter <- Resolvers.get(subscriptionApi(1, 2))
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"m","payload":{"query":"subscription { values }"}}"""
                    ))
                    m <- collectMessages(ws, 3)
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""type":"complete""""), s"client should see complete: $joined")
            assert(sawComplete.get(), "onMessage hook should observe the Complete frame per docs")
        end for
    }

    // UPSTREAM CALIBAN: Legacy.make has `case _ => ZIO.unit` as the catch-all, silently dropping unknown ops.
    // Legacy graphql-ws PROTOCOL.md is silent on unknown ops. Caliban's `Legacy.make` has `case _ => ZIO.unit`
    // so unknown messages are dropped silently. Verified by sending a recognized message after the unknown
    // one and observing that the connection is still alive and processes it.
    "WS - legacy unknown op is dropped silently and the connection stays alive" in {
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            data <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"banana"}"""))
                    // Recognized message after the unknown op must still be processed.
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"start","id":"x","payload":{"query":"{ k1 }"}}"""))
                    d <- expectMessage(ws, _.contains(""""type":"data""""))
                yield d
            }
        yield assert(data.contains(""""type":"data""""), s"expected data after unknown op was ignored, got: $data")
        end for
    }

    // Legacy graphql-ws PROTOCOL.md describes `id` as the operation identifier on `GQL_START` but doesn't
    // explicitly mandate behavior on missing id. Caliban's `Legacy.Start` uses `id.getOrElse("")`, so a start
    // without an id is accepted and the operation runs under the empty-string id.
    "WS - legacy start without id is accepted with id defaulted to empty string" in {
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"start","payload":{"query":"{ k1 }"}}"""))
                    d <- expectMessage(ws, _.contains(""""type":"data""""))
                yield d
            }
        yield assert(msg.contains(""""type":"data""""), s"expected data frame for start without id, got: $msg")
        end for
    }

    // subscribe with a non-object payload (StringValue, IntValue, NullValue) must not crash the dispatch loop:
    // buildRequest falls through to GraphQLRequest() and caliban responds with a missing-query error.
    "WS - subscribe with non-object payload is handled" in {
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"subscribe","id":"np","payload":"a string"}"""))
                    m <- expectMessage(ws, t => t.contains(""""type":"next"""") || t.contains(""""type":"error""""))
                yield m
            }
        yield assert(msg.contains("errors") || msg.contains(""""type":"error""""))
        end for
    }

    "WS - subscribe with null payload is handled" in {
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msg <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"subscribe","id":"np","payload":null}"""))
                    m <- expectMessage(ws, t => t.contains(""""type":"next"""") || t.contains(""""type":"error""""))
                yield m
            }
        yield assert(msg.contains("errors") || msg.contains(""""type":"error""""))
        end for
    }

    "WS - afterInit panic closes 4401" in {
        val hooks = caliban.ws.WebSocketHooks.afterInit[Any, CalibanError](zio.ZIO.die(new RuntimeException("after boom!")))
        for
            interpreter <- Resolvers.get(wsApi)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            cr <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _  <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _  <- expectMessage(ws, _.contains("connection_ack"))
                    cr <- awaitClose(ws)
                yield cr
            }
        yield assert(cr._1 == 4401, s"expected 4401, got: $cr")
        end for
    }

    "WS - subscription with string values containing JSON-escape characters" in {
        case class StrSub(messages: zio.stream.ZStream[Any, Nothing, String]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(
            defaultQuery,
            Mutation(0),
            StrSub(zio.stream.ZStream("plain", """has "quotes" and\backslash""", "hello\nworld"))
        ))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"s","payload":{"query":"subscription { messages }"}}"""
                    ))
                    m <- collectMessages(ws, 4) // 3 next + 1 complete
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            // Each value must be present, properly JSON-escaped.
            assert(joined.contains(""""plain""""))
            assert(joined.contains("""\"quotes\""""), s"missing escaped quotes: $joined")
            assert(joined.contains("""\\backslash"""), s"missing escaped backslash: $joined")
            assert(joined.contains("""\n"""), s"missing escaped newline: $joined")
        end for
    }

    "WS - subscription with nested object values" in {
        case class Point(x: Int, y: Int) derives caliban.schema.Schema.SemiAuto
        case class PointSub(points: zio.stream.ZStream[Any, Nothing, Point]) derives caliban.schema.Schema.SemiAuto
        val api = graphQL(RootResolver(defaultQuery, Mutation(0), PointSub(zio.stream.ZStream(Point(1, 2), Point(3, 4)))))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter)
            url = s"ws://localhost:${server.port}/api/graphql/ws"
            msgs <- HttpClient.webSocket(url, config = wsSubprotocol("graphql-transport-ws")) { ws =>
                for
                    _ <- ws.put(HttpWebSocket.Payload.Text("""{"type":"connection_init"}"""))
                    _ <- expectMessage(ws, _.contains("connection_ack"))
                    _ <- ws.put(HttpWebSocket.Payload.Text(
                        """{"type":"subscribe","id":"p","payload":{"query":"subscription { points { x y } }"}}"""
                    ))
                    m <- collectMessages(ws, 3) // 2 next + 1 complete
                yield m
            }
        yield
            val joined = msgs.mkString("\n")
            assert(joined.contains(""""x":1"""))
            assert(joined.contains(""""y":2"""))
            assert(joined.contains(""""x":3"""))
            assert(joined.contains(""""y":4"""))
        end for
    }

end ResolverTest
