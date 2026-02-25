// Old kyo package test — commented out, replaced by kyo.http2 tests
// package kyo.internal
//
// import kyo.*
// import kyo.HttpOpenApi.*
// import kyo.HttpRequest.Method
// import kyo.HttpStatus
//
// class OpenApiGeneratorTest extends Test:
//
//     case class User(id: Int, name: String) derives Schema
//     case class CreateUser(name: String, email: String) derives Schema
//     case class ErrorResponse(code: Int, message: String) derives Schema
//
//     "OpenApiGenerator" - {
//
//         "generates basic OpenAPI structure" in run {
//             val handler = HttpHandler.get("/health") { _ =>
//                 HttpResponse.ok("OK")
//             }
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.openapi == "3.0.0")
//                 assert(spec.info.title == "API")
//                 assert(spec.info.version == "1.0.0")
//                 assert(spec.paths.nonEmpty)
//             }
//         }
//
//         "includes custom title and version" in run {
//             val handler = HttpHandler.get("/health") { _ =>
//                 HttpResponse.ok("OK")
//             }
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi("My API", "2.0.0")
//                 assert(spec.info.title == "My API")
//                 assert(spec.info.version == "2.0.0")
//             }
//         }
//
//         "includes description" in run {
//             val handler = HttpHandler.get("/health") { _ =>
//                 HttpResponse.ok("OK")
//             }
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi("My API", "1.0.0", Present("A test API"))
//                 assert(spec.info.description == Some("A test API"))
//             }
//         }
//
//         "generates path for GET route" in run {
//             val route   = HttpRoute.get("/users").response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.paths.contains("/users"))
//                 val pathItem = spec.paths("/users")
//                 assert(pathItem.get.isDefined)
//                 assert(pathItem.post.isEmpty)
//             }
//         }
//
//         "generates path for POST route" in run {
//             val route   = HttpRoute.post("/users").request(_.bodyJson[CreateUser]).response(_.bodyJson[User])
//             val handler = route.handle(in => User(1, in.body.name))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec     = server.openApi
//                 val pathItem = spec.paths("/users")
//                 assert(pathItem.post.isDefined)
//                 val op = pathItem.post.get
//                 assert(op.requestBody.isDefined)
//                 assert(op.requestBody.get.required == Some(true))
//                 assert(op.requestBody.get.content.contains("application/json"))
//             }
//         }
//
//         "generates path parameters" in run {
//
//             val route   = HttpRoute.get("/users" / HttpPath.Capture[Int]("id")).response(_.bodyJson[User])
//             val handler = route.handle(in => User(in.id, s"User${in.id}"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.paths.contains("/users/{id}"))
//                 val op = spec.paths("/users/{id}").get.get
//                 assert(op.parameters.isDefined)
//                 val params = op.parameters.get
//                 assert(params.exists(p => p.name == "id" && p.in == "path" && p.required == Some(true)))
//             }
//         }
//
//         "generates query parameters" in run {
//             val route = HttpRoute.get("/users")
//                 .request(_.query[Int]("limit").query[Int]("offset", default = Some(0)))
//                 .response(_.bodyJson[List[User]])
//             val handler = route.handle { in =>
//                 List(User(1, "Alice"))
//             }
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val op     = spec.paths("/users").get.get
//                 val params = op.parameters.get
//                 assert(params.exists(p => p.name == "limit" && p.in == "query" && p.required == Some(true)))
//                 assert(params.exists(p => p.name == "offset" && p.in == "query" && p.required.isEmpty))
//             }
//         }
//
//         "generates header parameters" in run {
//             val route = HttpRoute.get("/protected")
//                 .request(_.header[String]("Authorization"))
//                 .response(_.bodyJson[User])
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val op     = spec.paths("/protected").get.get
//                 val params = op.parameters.get
//                 assert(params.exists(p => p.name == "Authorization" && p.in == "header"))
//             }
//         }
//
//         "generates response schema" in run {
//             val route   = HttpRoute.get("/users").response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/users").get.get
//                 assert(op.responses.contains("200"))
//                 val response = op.responses("200")
//                 assert(response.description == "Success")
//                 assert(response.content.isDefined)
//                 assert(response.content.get.contains("application/json"))
//                 val schema = response.content.get("application/json").schema
//                 assert(schema.`type` == Some("array"))
//                 assert(schema.items.isDefined)
//             }
//         }
//
//         "generates error responses" in run {
//             val route = HttpRoute.get("/users")
//                 .response(_.bodyJson[User].error[ErrorResponse](HttpStatus.BadRequest))
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/users").get.get
//                 assert(op.responses.contains("200"))
//                 assert(op.responses.contains("400"))
//                 val errorResponse = op.responses("400")
//                 assert(errorResponse.description == "Error")
//             }
//         }
//
//         "includes operation metadata" in run {
//             val route = HttpRoute.get("/users")
//                 .metadata(_.tag("users").summary("Get all users").description("Returns a list of all users"))
//                 .response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/users").get.get
//                 assert(op.tags == Some(List("users")))
//                 assert(op.summary == Some("Get all users"))
//                 assert(op.description == Some("Returns a list of all users"))
//             }
//         }
//
//         "generates object schema for case class" in run {
//             val route   = HttpRoute.get("/user").response(_.bodyJson[User])
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val schema = spec.paths("/user").get.get.responses("200").content.get("application/json").schema
//                 assert(schema.`type` == Some("object"))
//                 assert(schema.properties.isDefined)
//                 val props = schema.properties.get
//                 assert(props.contains("id"))
//                 assert(props.contains("name"))
//                 assert(props("id").`type` == Some("integer"))
//                 assert(props("name").`type` == Some("string"))
//             }
//         }
//
//         "generates array schema for List" in run {
//             val route   = HttpRoute.get("/users").response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val schema = spec.paths("/users").get.get.responses("200").content.get("application/json").schema
//                 assert(schema.`type` == Some("array"))
//                 assert(schema.items.isDefined)
//                 assert(schema.items.get.`type` == Some("object"))
//             }
//         }
//
//         "combines multiple handlers" in run {
//             val getRoute    = HttpRoute.get("/users").response(_.bodyJson[List[User]])
//             val postRoute   = HttpRoute.post("/users").request(_.bodyJson[CreateUser]).response(_.bodyJson[User])
//             val getHandler  = getRoute.handle(_ => List(User(1, "Alice")))
//             val postHandler = postRoute.handle(in => User(1, in.body.name))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(getHandler, postHandler).map { server =>
//                 val spec     = server.openApi
//                 val pathItem = spec.paths("/users")
//                 assert(pathItem.get.isDefined)
//                 assert(pathItem.post.isDefined)
//             }
//         }
//
//         "generates valid JSON string" in run {
//             val route   = HttpRoute.get("/users").response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val json = server.openApi.toJson
//                 assert(json.contains("\"openapi\":\"3.0.0\""))
//                 assert(json.contains("\"paths\""))
//                 assert(json.contains("\"/users\""))
//             }
//         }
//
//         "generates operationId" in run {
//             val route = HttpRoute.get("/users")
//                 .metadata(_.operationId("listUsers"))
//                 .response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/users").get.get
//                 assert(op.operationId == Some("listUsers"))
//             }
//         }
//
//         "generates deprecated flag" in run {
//             val route = HttpRoute.get("/old")
//                 .metadata(_.markDeprecated)
//                 .response(_.bodyJson[User])
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/old").get.get
//                 assert(op.deprecated == Some(true))
//             }
//         }
//
//         "generates cookie parameters" in run {
//             val route = HttpRoute.get("/session")
//                 .request(_.cookie[String]("sessionId"))
//                 .response(_.bodyJson[User])
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val op     = spec.paths("/session").get.get
//                 val params = op.parameters.get
//                 assert(params.exists(p => p.name == "sessionId" && p.in == "cookie"))
//             }
//         }
//
//         "generates PUT method" in run {
//             val route   = HttpRoute.put("/users").request(_.bodyJson[User]).response(_.bodyJson[User])
//             val handler = route.handle(in => in.body)
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.paths("/users").put.isDefined)
//             }
//         }
//
//         "generates DELETE method" in run {
//             val route   = HttpRoute.delete("/users").response(_.bodyJson[Unit])
//             val handler = route.handle(_ => ())
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.paths("/users").delete.isDefined)
//             }
//         }
//
//         "generates PATCH method" in run {
//             val route   = HttpRoute.patch("/users").request(_.bodyJson[User]).response(_.bodyJson[User])
//             val handler = route.handle(in => in.body)
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 assert(spec.paths("/users").patch.isDefined)
//             }
//         }
//
//         "generates default 200 success status" in run {
//             val route   = HttpRoute.post("/users").request(_.bodyJson[CreateUser]).response(_.bodyJson[User])
//             val handler = route.handle(in => User(1, in.body.name))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec = server.openApi
//                 val op   = spec.paths("/users").post.get
//                 assert(op.responses.contains("200"))
//             }
//         }
//
//         "generates integer schema for Int path param" in run {
//
//             val route   = HttpRoute.get("/users" / HttpPath.Capture[Int]("id")).response(_.bodyJson[User])
//             val handler = route.handle(in => User(in.id, s"User${in.id}"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec    = server.openApi
//                 val op      = spec.paths("/users/{id}").get.get
//                 val params  = op.parameters.get
//                 val idParam = params.find(_.name == "id").get
//                 assert(idParam.schema.`type` == Some("integer"))
//             }
//         }
//
//         "generates long schema for Long path param" in run {
//
//             val route   = HttpRoute.get("/items" / HttpPath.Capture[Long]("id")).response(_.bodyJson[User])
//             val handler = route.handle(in => User(in.id.toInt, s"Item${in.id}"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec    = server.openApi
//                 val op      = spec.paths("/items/{id}").get.get
//                 val params  = op.parameters.get
//                 val idParam = params.find(_.name == "id").get
//                 assert(idParam.schema.`type` == Some("integer"))
//                 assert(idParam.schema.format == Some("int64"))
//             }
//         }
//
//         "generates uuid schema for UUID path param" in run {
//
//             val route   = HttpRoute.get("/items" / HttpPath.Capture[java.util.UUID]("id")).response(_.bodyJson[User])
//             val handler = route.handle(in => User(1, in.id.toString))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec    = server.openApi
//                 val op      = spec.paths("/items/{id}").get.get
//                 val params  = op.parameters.get
//                 val idParam = params.find(_.name == "id").get
//                 assert(idParam.schema.`type` == Some("string"))
//                 assert(idParam.schema.format == Some("uuid"))
//             }
//         }
//
//         "generates boolean schema for boolean query param" in run {
//             val route = HttpRoute.get("/users")
//                 .request(_.query[Boolean]("active"))
//                 .response(_.bodyJson[List[User]])
//             val handler = route.handle(_ => List(User(1, "Alice")))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec        = server.openApi
//                 val op          = spec.paths("/users").get.get
//                 val params      = op.parameters.get
//                 val activeParam = params.find(_.name == "active").get
//                 // Query parameter schema inference not yet implemented; defaults to string
//                 assert(activeParam.schema.`type` == Some("string"))
//             }
//         }
//
//         "generates multiple path parameters" in run {
//
//             val route = HttpRoute.get(
//                 "/orgs" / HttpPath.Capture[String]("org") / "repos" / HttpPath.Capture[String]("repo")
//             ).response(_.bodyJson[User])
//             val handler = route.handle { in => User(1, s"${in.org}/${in.repo}") }
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val op     = spec.paths("/orgs/{org}/repos/{repo}").get.get
//                 val params = op.parameters.get
//                 assert(params.exists(_.name == "org"))
//                 assert(params.exists(_.name == "repo"))
//             }
//         }
//
//         "generates required fields for case class" in run {
//             val route   = HttpRoute.get("/user").response(_.bodyJson[User])
//             val handler = route.handle(_ => User(1, "Alice"))
//             HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handler).map { server =>
//                 val spec   = server.openApi
//                 val schema = spec.paths("/user").get.get.responses("200").content.get("application/json").schema
//                 assert(schema.required.isDefined)
//                 assert(schema.required.get.contains("id"))
//                 assert(schema.required.get.contains("name"))
//             }
//         }
//     }
//
// end OpenApiGeneratorTest
