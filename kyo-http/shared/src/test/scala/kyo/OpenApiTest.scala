package kyo

class OpenApiTest extends Test:

    case class User(id: Int, name: String) derives Schema
    case class CreateUser(name: String, email: String) derives Schema

    "OpenApi" - {

        "fromRoutes" - {

            "generates spec from single route" in {
                val route = HttpRoute.get("/users").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                assert(spec.openapi == "3.0.0")
                assert(spec.paths.contains("/users"))
                assert(spec.paths("/users").get.isDefined)
            }

            "generates spec from multiple routes" in {
                val getRoute    = HttpRoute.get("/users").responseBody[List[User]]
                val postRoute   = HttpRoute.post("/users").requestBody[CreateUser].responseBody[User]
                val deleteRoute = HttpRoute.delete("/users").responseBody[Unit]

                val spec = HttpOpenApi.fromRoutes(getRoute, postRoute, deleteRoute)

                assert(spec.paths("/users").get.isDefined)
                assert(spec.paths("/users").post.isDefined)
                assert(spec.paths("/users").delete.isDefined)
            }

            "uses custom config" in {
                val route = HttpRoute.get("/users").responseBody[List[User]]
                val config = HttpOpenApi.Config(
                    title = "My API",
                    version = "2.0.0",
                    description = Present("A test API")
                )
                val spec = HttpOpenApi.fromRoutes(config)(route)

                assert(spec.info.title == "My API")
                assert(spec.info.version == "2.0.0")
                assert(spec.info.description == Some("A test API"))
            }

            "includes route metadata" in {
                val route = HttpRoute.get("/users")
                    .tag("users")
                    .summary("Get all users")
                    .responseBody[List[User]]

                val spec = HttpOpenApi.fromRoutes(route)
                val op   = spec.paths("/users").get.get

                assert(op.tags == Some(List("users")))
                assert(op.summary == Some("Get all users"))
            }

            "includes path parameters" in {
                import HttpPath./

                val route = HttpRoute.get("/users" / HttpPath.int("id")).responseBody[User]
                val spec  = HttpOpenApi.fromRoutes(route)
                val op    = spec.paths("/users/{id}").get.get

                assert(op.parameters.isDefined)
                assert(op.parameters.get.exists(p => p.name == "id" && p.in == "path"))
            }

            "includes query parameters" in {
                val route = HttpRoute.get("/users")
                    .query[Int]("limit")
                    .responseBody[List[User]]

                val spec = HttpOpenApi.fromRoutes(route)
                val op   = spec.paths("/users").get.get

                assert(op.parameters.get.exists(p => p.name == "limit" && p.in == "query"))
            }
        }

        "fromHandlers" - {

            "generates spec from handlers" in run {
                val route   = HttpRoute.get("/users").responseBody[List[User]]
                val handler = route.handle(_ => List(User(1, "Alice")))
                val spec    = HttpOpenApi.fromHandlers(handler)

                assert(spec.paths.contains("/users"))
                assert(spec.paths("/users").get.isDefined)
            }

            "uses custom config" in run {
                val route   = HttpRoute.get("/users").responseBody[List[User]]
                val handler = route.handle(_ => List(User(1, "Alice")))
                val config  = HttpOpenApi.Config(title = "Handler API", version = "3.0.0")
                val spec    = HttpOpenApi.fromHandlers(config)(handler)

                assert(spec.info.title == "Handler API")
                assert(spec.info.version == "3.0.0")
            }
        }

        "toJson" - {

            "encodes spec to JSON" in {
                val route = HttpRoute.get("/users").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)
                val json  = spec.toJson

                assert(json.contains("\"openapi\":\"3.0.0\""))
                assert(json.contains("\"/users\""))
            }
        }

        "Config" - {

            "has sensible defaults" in {
                val config = HttpOpenApi.Config.default

                assert(config.title == "API")
                assert(config.version == "1.0.0")
                assert(config.description == Absent)
            }
        }

        "handler" - {

            "serves OpenAPI spec at default path" in run {
                val route      = HttpRoute.get("/users").responseBody[List[User]]
                val apiHandler = HttpOpenApi.handler(route)

                HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(apiHandler).map { server =>
                    testGet(server.port, "/openapi.json").map { response =>
                        assertStatus(response, HttpResponse.Status.OK)
                        assertHeader(response, "Content-Type", "application/json")
                        assertBodyContains(response, "\"openapi\":\"3.0.0\"")
                        assertBodyContains(response, "\"/users\"")
                    }
                }
            }

            "serves OpenAPI spec at custom path" in run {
                val route      = HttpRoute.get("/users").responseBody[List[User]]
                val apiHandler = HttpOpenApi.handler("/api-docs")(route)

                HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(apiHandler).map { server =>
                    testGet(server.port, "/api-docs").map { response =>
                        assertStatus(response, HttpResponse.Status.OK)
                        assertBodyContains(response, "\"openapi\":\"3.0.0\"")
                    }
                }
            }

            "uses custom config" in run {
                val route      = HttpRoute.get("/users").responseBody[List[User]]
                val config     = HttpOpenApi.Config(title = "Users API", version = "2.0.0")
                val apiHandler = HttpOpenApi.handler("/openapi.json", config)(route)

                HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(apiHandler).map { server =>
                    testGet(server.port, "/openapi.json").map { response =>
                        assertBodyContains(response, "\"title\":\"Users API\"")
                        assertBodyContains(response, "\"version\":\"2.0.0\"")
                    }
                }
            }

            "works alongside other handlers" in run {
                val usersRoute   = HttpRoute.get("/users").responseBody[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))
                val apiHandler   = HttpOpenApi.handler(usersRoute)

                HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(usersHandler, apiHandler).map { server =>
                    testGet(server.port, "/users").map { usersResp =>
                        assertBodyContains(usersResp, "Alice")
                    }.andThen {
                        testGet(server.port, "/openapi.json").map { apiResp =>
                            assertBodyContains(apiResp, "\"/users\"")
                        }
                    }
                }
            }
        }

        "server config openApi" - {

            "automatically serves OpenAPI spec" in run {
                val usersRoute   = HttpRoute.get("/users").responseBody[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).openApi()

                HttpServer.init(config, PlatformTestBackend.server)(usersHandler).map { server =>
                    testGet(server.port, "/openapi.json").map { response =>
                        assertStatus(response, HttpResponse.Status.OK)
                        assertHeader(response, "Content-Type", "application/json")
                        assertBodyContains(response, "\"openapi\":\"3.0.0\"")
                        assertBodyContains(response, "\"/users\"")
                    }
                }
            }

            "uses custom path" in run {
                val usersRoute   = HttpRoute.get("/users").responseBody[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).openApi(path = "/api-docs")

                HttpServer.init(config, PlatformTestBackend.server)(usersHandler).map { server =>
                    testGet(server.port, "/api-docs").map { response =>
                        assertStatus(response, HttpResponse.Status.OK)
                        assertBodyContains(response, "\"openapi\":\"3.0.0\"")
                    }
                }
            }

            "uses custom title and version" in run {
                val usersRoute   = HttpRoute.get("/users").responseBody[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).openApi(title = "Users API", version = "2.0.0")

                HttpServer.init(config, PlatformTestBackend.server)(usersHandler).map { server =>
                    testGet(server.port, "/openapi.json").map { response =>
                        assertBodyContains(response, "\"title\":\"Users API\"")
                        assertBodyContains(response, "\"version\":\"2.0.0\"")
                    }
                }
            }

            "includes all handlers in spec" in run {
                val usersRoute   = HttpRoute.get("/users").responseBody[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val createRoute   = HttpRoute.post("/users").requestBody[CreateUser].responseBody[User]
                val createHandler = createRoute.handle(in => User(2, in.body.name))

                val config = HttpServer.Config(port = 0).openApi()

                HttpServer.init(config, PlatformTestBackend.server)(usersHandler, createHandler).map { server =>
                    testGet(server.port, "/openapi.json").map { response =>
                        // Should include both GET and POST /users
                        assertBodyContains(response, "\"/users\"")
                        assertBodyContains(response, "\"get\"")
                        assertBodyContains(response, "\"post\"")
                    }
                }
            }
        }

        "security schemes" - {

            "route with security scheme includes security in operation" in {
                val route = HttpRoute.get("/users").authBearer.security("bearerAuth").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                val op = spec.paths("/users").get.get
                assert(op.security.isDefined)
                assert(op.security.get == List(Map("bearerAuth" -> List.empty[String])))
            }

            "route with bearer auth includes security scheme in components" in {
                val route = HttpRoute.get("/users").authBearer.security("bearerAuth").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                assert(spec.components.isDefined)
                val schemes = spec.components.get.securitySchemes.get
                assert(schemes.contains("bearerAuth"))
                assert(schemes("bearerAuth").`type` == "http")
                assert(schemes("bearerAuth").scheme == Some("bearer"))
            }

            "route with basic auth includes security scheme in components" in {
                val route = HttpRoute.get("/users").authBasic.security("basicAuth").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                assert(spec.components.isDefined)
                val schemes = spec.components.get.securitySchemes.get
                assert(schemes.contains("basicAuth"))
                assert(schemes("basicAuth").`type` == "http")
                assert(schemes("basicAuth").scheme == Some("basic"))
            }

            "route without security scheme has no security on operation" in {
                val route = HttpRoute.get("/users").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                val op = spec.paths("/users").get.get
                assert(op.security.isEmpty)
            }

            "no components when no security schemes" in {
                val route = HttpRoute.get("/users").responseBody[List[User]]
                val spec  = HttpOpenApi.fromRoutes(route)

                assert(spec.components.isEmpty)
            }
        }
    }

end OpenApiTest
