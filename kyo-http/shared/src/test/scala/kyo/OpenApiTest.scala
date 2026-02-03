package kyo

class OpenApiTest extends Test:

    case class User(id: Int, name: String) derives Schema
    case class CreateUser(name: String, email: String) derives Schema

    "OpenApi" - {

        "fromRoutes" - {

            "generates spec from single route" in {
                val route = HttpRoute.get("/users").output[List[User]]
                val spec  = OpenApi.fromRoutes(route)

                assert(spec.openapi == "3.0.0")
                assert(spec.paths.contains("/users"))
                assert(spec.paths("/users").get.isDefined)
                succeed
            }

            "generates spec from multiple routes" in {
                val getRoute    = HttpRoute.get("/users").output[List[User]]
                val postRoute   = HttpRoute.post("/users").input[CreateUser].output[User]
                val deleteRoute = HttpRoute.delete("/users").output[Unit]

                val spec = OpenApi.fromRoutes(getRoute, postRoute, deleteRoute)

                assert(spec.paths("/users").get.isDefined)
                assert(spec.paths("/users").post.isDefined)
                assert(spec.paths("/users").delete.isDefined)
                succeed
            }

            "uses custom config" in {
                val route = HttpRoute.get("/users").output[List[User]]
                val config = OpenApi.Config(
                    title = "My API",
                    version = "2.0.0",
                    description = Present("A test API")
                )
                val spec = OpenApi.fromRoutes(config)(route)

                assert(spec.info.title == "My API")
                assert(spec.info.version == "2.0.0")
                assert(spec.info.description == Some("A test API"))
                succeed
            }

            "includes route metadata" in {
                val route = HttpRoute.get("/users")
                    .withTag("users")
                    .withSummary("Get all users")
                    .output[List[User]]

                val spec = OpenApi.fromRoutes(route)
                val op   = spec.paths("/users").get.get

                assert(op.tags == Some(List("users")))
                assert(op.summary == Some("Get all users"))
                succeed
            }

            "includes path parameters" in {
                import HttpRoute.Path
                import HttpRoute.Path./

                val route = HttpRoute.get("/users" / Path.int("id")).output[User]
                val spec  = OpenApi.fromRoutes(route)
                val op    = spec.paths("/users/{id}").get.get

                assert(op.parameters.isDefined)
                assert(op.parameters.get.exists(p => p.name == "id" && p.in == "path"))
                succeed
            }

            "includes query parameters" in {
                val route = HttpRoute.get("/users")
                    .query[Int]("limit")
                    .output[List[User]]

                val spec = OpenApi.fromRoutes(route)
                val op   = spec.paths("/users").get.get

                assert(op.parameters.get.exists(p => p.name == "limit" && p.in == "query"))
                succeed
            }
        }

        "fromHandlers" - {

            "generates spec from handlers" in run {
                val route   = HttpRoute.get("/users").output[List[User]]
                val handler = route.handle(_ => List(User(1, "Alice")))
                val spec    = OpenApi.fromHandlers(handler)

                assert(spec.paths.contains("/users"))
                assert(spec.paths("/users").get.isDefined)
            }

            "uses custom config" in run {
                val route   = HttpRoute.get("/users").output[List[User]]
                val handler = route.handle(_ => List(User(1, "Alice")))
                val config  = OpenApi.Config(title = "Handler API", version = "3.0.0")
                val spec    = OpenApi.fromHandlers(config)(handler)

                assert(spec.info.title == "Handler API")
                assert(spec.info.version == "3.0.0")
            }
        }

        "toJson" - {

            "encodes spec to JSON" in {
                val route = HttpRoute.get("/users").output[List[User]]
                val spec  = OpenApi.fromRoutes(route)
                val json  = OpenApi.toJson(spec)

                assert(json.contains("\"openapi\":\"3.0.0\""))
                assert(json.contains("\"/users\""))
                succeed
            }
        }

        "Config" - {

            "has sensible defaults" in {
                val config = OpenApi.Config.default

                assert(config.title == "API")
                assert(config.version == "1.0.0")
                assert(config.description == Absent)
                succeed
            }
        }

        "handler" - {

            "serves OpenAPI spec at default path" in run {
                val route      = HttpRoute.get("/users").output[List[User]]
                val apiHandler = OpenApi.handler(route)

                HttpServer.init(HttpServer.Config(port = 0))(apiHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/openapi.json").map { response =>
                            assertStatus(response, HttpResponse.Status.OK)
                            assertHeader(response, "Content-Type", "application/json")
                            assert(response.bodyText.contains("\"openapi\":\"3.0.0\""))
                            assert(response.bodyText.contains("\"/users\""))
                        }
                    }
                }
            }

            "serves OpenAPI spec at custom path" in run {
                val route      = HttpRoute.get("/users").output[List[User]]
                val apiHandler = OpenApi.handler("/api-docs")(route)

                HttpServer.init(HttpServer.Config(port = 0))(apiHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/api-docs").map { response =>
                            assertStatus(response, HttpResponse.Status.OK)
                            assert(response.bodyText.contains("\"openapi\":\"3.0.0\""))
                        }
                    }
                }
            }

            "uses custom config" in run {
                val route      = HttpRoute.get("/users").output[List[User]]
                val config     = OpenApi.Config(title = "Users API", version = "2.0.0")
                val apiHandler = OpenApi.handler("/openapi.json", config)(route)

                HttpServer.init(HttpServer.Config(port = 0))(apiHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/openapi.json").map { response =>
                            assert(response.bodyText.contains("\"title\":\"Users API\""))
                            assert(response.bodyText.contains("\"version\":\"2.0.0\""))
                        }
                    }
                }
            }

            "works alongside other handlers" in run {
                val usersRoute   = HttpRoute.get("/users").output[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))
                val apiHandler   = OpenApi.handler(usersRoute)

                HttpServer.init(HttpServer.Config(port = 0))(usersHandler, apiHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/users").map { usersResp =>
                            assert(usersResp.bodyText.contains("Alice"))
                        }.andThen {
                            testGet(server.port, "/openapi.json").map { apiResp =>
                                assert(apiResp.bodyText.contains("\"/users\""))
                            }
                        }
                    }
                }
            }
        }

        "server config openApi" - {

            "automatically serves OpenAPI spec" in run {
                val usersRoute   = HttpRoute.get("/users").output[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).withOpenApi()

                HttpServer.init(config)(usersHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/openapi.json").map { response =>
                            assertStatus(response, HttpResponse.Status.OK)
                            assertHeader(response, "Content-Type", "application/json")
                            assert(response.bodyText.contains("\"openapi\":\"3.0.0\""))
                            assert(response.bodyText.contains("\"/users\""))
                        }
                    }
                }
            }

            "uses custom path" in run {
                val usersRoute   = HttpRoute.get("/users").output[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).withOpenApi(path = "/api-docs")

                HttpServer.init(config)(usersHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/api-docs").map { response =>
                            assertStatus(response, HttpResponse.Status.OK)
                            assert(response.bodyText.contains("\"openapi\":\"3.0.0\""))
                        }
                    }
                }
            }

            "uses custom title and version" in run {
                val usersRoute   = HttpRoute.get("/users").output[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val config = HttpServer.Config(port = 0).withOpenApi(title = "Users API", version = "2.0.0")

                HttpServer.init(config)(usersHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/openapi.json").map { response =>
                            assert(response.bodyText.contains("\"title\":\"Users API\""))
                            assert(response.bodyText.contains("\"version\":\"2.0.0\""))
                        }
                    }
                }
            }

            "includes all handlers in spec" in run {
                val usersRoute   = HttpRoute.get("/users").output[List[User]]
                val usersHandler = usersRoute.handle(_ => List(User(1, "Alice")))

                val createRoute   = HttpRoute.post("/users").input[CreateUser].output[User]
                val createHandler = createRoute.handle(input => User(2, input.name))

                val config = HttpServer.Config(port = 0).withOpenApi()

                HttpServer.init(config)(usersHandler, createHandler).map { server =>
                    Scope.ensure(server.stopNow).andThen {
                        testGet(server.port, "/openapi.json").map { response =>
                            // Should include both GET and POST /users
                            assert(response.bodyText.contains("\"/users\""))
                            assert(response.bodyText.contains("\"get\""))
                            assert(response.bodyText.contains("\"post\""))
                        }
                    }
                }
            }
        }
    }

end OpenApiTest
