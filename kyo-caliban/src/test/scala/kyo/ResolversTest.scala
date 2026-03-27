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

class ResolverTest extends Test:

    val client = kyo.internal.HttpPlatformBackend.client

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
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        client.connectWith("localhost", port, ssl = false, Absent) { conn =>
            client.sendWith(conn, route, request)(identity)
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
        object schema extends SchemaDerivation[Runner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val layer = ZLayer.succeed(new Runner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v)))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k }").provide(layer)
        yield assert(res.data.toString == """{"k":4}""")
        end for
    }

    // ==================== Server - POST ====================

    "POST - JSON body" in run {
        for
            server <- startServer
            res    <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k1 k2 k3 k4 k5 }"}""")
        yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}}""")
        end for
    }

    "POST - application/graphql content-type" in run {
        for
            server            <- startServer
            (status, body, _) <- postGql(server.port, "{ k1 }", "Content-Type" -> "application/graphql")
        yield
            assert(status == HttpStatus.OK)
            assert(body == """{"data":{"k1":42}}""")
        end for
    }

    "POST - federation tracing header" in run {
        for
            server            <- startServer
            (status, body, _) <- postGql(server.port, """{"query":"{ k1 }"}""", "apollo-federation-include-trace" -> "ftv1")
        yield
            assert(status == HttpStatus.OK)
            assert(body.contains(""""k1":42"""))
        end for
    }

    // ==================== Server - GET ====================

    "GET - query param" in run {
        for
            server <- startServer
            resp   <- HttpClient.getText(s"http://localhost:${server.port}/api/graphql?query=%7B%20k1%20%7D")
        yield assert(resp == """{"data":{"k1":42}}""")
        end for
    }

    "GET - query with variables" in run {
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

    "GET - query with operationName" in run {
        for
            server       <- startServer
            (_, body, _) <- getGql(server.port, "query=query%20MyOp%7Bk1%7D&operationName=MyOp")
        yield assert(body == """{"data":{"k1":42}}""")
        end for
    }

    "GET - query with extensions" in run {
        for
            server       <- startServer
            (_, body, _) <- getGql(server.port, "query=%7Bk1%7D&extensions=%7B%7D")
        yield assert(body.contains(""""k1":42"""))
        end for
    }

    // ==================== Server - Config ====================

    "Config - custom config" in run {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.enableIntrospection(true))
            res         <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k1 }"}""")
        yield assert(res == """{"data":{"k1":42}}""")
        end for
    }

    "Config - Runner with default config" in run {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[Runner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val runner = new Runner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v))

        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, runner)
            res         <- HttpClient.postText(s"http://localhost:${server.port}/api/graphql", """{"query":"{ k }"}""")
        yield assert(res == """{"data":{"k":4}}""")
        end for
    }

    "Config - Runner with custom config" in run {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[Runner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(Env.get[String].map(_.length))))
        val runner = new Runner[Environment]:
            def apply[A](v: A < Environment): A < (Abort[Throwable] & Async) = Env.run("kyo")(Var.run(0)(v))
        val config = Resolvers.Config.default.path("gql")

        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, runner, config)
            res         <- HttpClient.postText(s"http://localhost:${server.port}/gql", """{"query":"{ k }"}""")
        yield assert(res == """{"data":{"k":3}}""")
        end for
    }

    "Config - custom path" in run {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.path("custom/graphql"))
            res         <- HttpClient.postText(s"http://localhost:${server.port}/custom/graphql", """{"query":"{ k1 }"}""")
        yield assert(res == """{"data":{"k1":42}}""")
        end for
    }

    "Config - graphiql disabled" in run {
        val graphiqlRoute = HttpRoute.getRaw("graphiql").response(_.bodyBinary)
        val api           = graphQL(RootResolver(defaultQuery))
        for
            interpreter <- Resolvers.get(api)
            server      <- Resolvers.run(interpreter, Resolvers.Config.default.graphiql(false))
            resp        <- send(server.port, graphiqlRoute, HttpRequest.getRaw(HttpUrl.fromUri("/graphiql")))
        yield assert(resp.status == HttpStatus.NotFound)
        end for
    }

    "Config - filter adds response header" in run {
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

    "Config - skipValidation allows invalid query" in run {
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

    "Config - enableIntrospection false rejects __schema queries" in run {
        val api = graphQL(RootResolver(defaultQuery))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter, Resolvers.Config.default.enableIntrospection(false))
            (status, body, _) <- postGql(server.port, """{"query":"{ __schema { types { name } } }"}""")
        yield assert(body.contains("Introspection is disabled"), s"Expected introspection disabled error, got: $body")
        end for
    }

    "Config - enableIntrospection true allows __schema queries" in run {
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

    "Config - allowMutationsOverGetRequests false blocks GET mutations" in run {
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

    "Config - allowMutationsOverGetRequests true allows GET mutations" in run {
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

    "Config - queryExecution Sequential executes fields sequentially" in run {
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

    "SSE - one-shot query" in run {
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

    "SSE - subscription streaming" in run {
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

    "defer - multipart response" in run {
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

    // ==================== Server - Upload ====================

    "upload - single file" in run {
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

    "upload - missing operations part" in run {
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

    "GraphiQL - serves HTML" in run {
        for
            server <- startServer
            resp   <- HttpClient.getText(s"http://localhost:${server.port}/graphiql")
        yield
            assert(resp.contains("<!DOCTYPE html>") || resp.contains("<html"))
            assert(resp.contains("graphiql") || resp.contains("GraphiQL"))
        end for
    }

    // ==================== Response encoding ====================

    "response - error in JSON" in run {
        for
            server       <- startServer
            (_, body, _) <- postGql(server.port, """{"query":"{ nonexistent }"}""")
        yield assert(body.contains("errors"))
        end for
    }

    "response - Accept application/graphql-response+json" in run {
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

    "response - 400 for validation error with graphql-response+json" in run {
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

    "response - mutation over GET returns 400" in run {
        val api = graphQL(RootResolver(defaultQuery, Mutation(99)))
        for
            interpreter       <- Resolvers.get(api)
            server            <- Resolvers.run(interpreter)
            (status, body, _) <- getGql(server.port, "query=mutation%7BdoSomething%7D")
        yield
            assert(status == HttpStatus.BadRequest)
            assert(body.contains("errors"))
        end for
    }

    "response - cache-control header" in run {
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
            assert(cc.exists(_.contains("max-age")))
        end for
    }

end ResolverTest
