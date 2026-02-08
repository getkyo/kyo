package kyo

import HttpPath./
import HttpPath.Inputs
import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status

class HttpRouteTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String, email: String) derives Schema, CanEqual
    case class NotFoundError(message: String) derives Schema, CanEqual
    case class ValidationError(field: String, message: String) derives Schema, CanEqual

    "Path" - {

        "construction" - {
            "from string" in {
                val p: HttpPath[Unit] = HttpPath("users")
                succeed
            }

            "implicit string conversion" in {
                val p: HttpPath[Unit] = "users"
                succeed
            }
        }

        "captures" - {
            "int" in {
                val p: HttpPath[Int] = HttpPath.int("id")
                succeed
            }

            "long" in {
                val p: HttpPath[Long] = HttpPath.long("id")
                succeed
            }

            "string" in {
                val p: HttpPath[String] = HttpPath.string("slug")
                succeed
            }

            "uuid" in {
                val p: HttpPath[java.util.UUID] = HttpPath.uuid("id")
                succeed
            }

            "boolean" in {
                val p: HttpPath[Boolean] = HttpPath.boolean("active")
                succeed
            }
        }

        "concatenation" - {
            "string / string" in {
                val p: HttpPath[Unit] = "api" / "v1" / "users"
                succeed
            }

            "string / capture" in {
                val p: HttpPath[Int] = "users" / HttpPath.int("id")
                succeed
            }

            "capture / string" in {
                val p: HttpPath[Int] = HttpPath.int("id") / "details"
                succeed
            }

            "two captures" in {
                val p: HttpPath[(Int, Int)] = "users" / HttpPath.int("userId") / "posts" / HttpPath.int("postId")
                succeed
            }

            "three captures" in {
                val p: HttpPath[(Int, String, Long)] =
                    "org" / HttpPath.int("orgId") / "users" / HttpPath.string("name") / "items" / HttpPath.long("itemId")
                succeed
            }

            "four captures" in {
                val p: HttpPath[(Int, String, Long, Boolean)] =
                    "a" / HttpPath.int("a") / "b" / HttpPath.string("b") / "c" / HttpPath.long("c") / "d" / HttpPath.boolean("d")
                succeed
            }

            "capture / capture" in {
                val p: HttpPath[(Int, String)] = HttpPath.int("id") / HttpPath.string("name")
                succeed
            }
        }

        "edge cases" - {
            "empty string segment" in {
                val p: HttpPath[Unit] = HttpPath("")
                succeed
            }

            "segment with special characters" in {
                val p: HttpPath[Unit] = "users-v2" / "items_list"
                succeed
            }

            "segment with encoded slashes" in {
                // Path segments shouldn't contain raw slashes
                val p: HttpPath[Unit] = "users%2Flist"
                succeed
            }

            "leading slash" in {
                // Leading slash should be normalized
                val p: HttpPath[Unit] = HttpPath("/users")
                succeed
            }

            "trailing slash" in {
                val p: HttpPath[Unit] = "users/"
                succeed
            }

            "double slashes" in {
                // Double slashes should be normalized
                val p: HttpPath[Unit] = "api" / "" / "users"
                succeed
            }

            "dot segments" in {
                val p: HttpPath[Unit] = "api" / "." / "users"
                succeed
            }
        }

        "parsing failures" - {
            "int capture with non-numeric value" in run {
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
                val handler = route.handle(id => User(id, s"User$id"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid int, got ${response.status}")
                    }
                }
            }

            "int capture with overflow" in run {
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
                val handler = route.handle(id => User(id, s"User$id"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/99999999999999999999").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for int overflow, got ${response.status}")
                    }
                }
            }

            "long capture with non-numeric value" in run {
                val route   = HttpRoute.get("items" / HttpPath.long("id")).output[User]
                val handler = route.handle(id => User(id.toInt, s"Item$id"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid long, got ${response.status}")
                    }
                }
            }

            "long capture with overflow" in run {
                val route   = HttpRoute.get("items" / HttpPath.long("id")).output[User]
                val handler = route.handle(id => User(id.toInt, s"Item$id"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/999999999999999999999999999999").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for long overflow, got ${response.status}")
                    }
                }
            }

            "uuid capture with invalid format" in run {
                val route   = HttpRoute.get("items" / HttpPath.uuid("id")).output[User]
                val handler = route.handle(id => User(1, id.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/not-a-uuid").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid uuid, got ${response.status}")
                    }
                }
            }

            "boolean capture with invalid value" in run {
                val route   = HttpRoute.get("flags" / HttpPath.boolean("active")).output[User]
                val handler = route.handle(active => User(if active then 1 else 0, active.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/flags/maybe").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid boolean, got ${response.status}")
                    }
                }
            }
        }

        "capture name edge cases" - {
            "empty capture name throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpPath.int("")
                }
            }

            "capture name with special characters" in {
                // Capture names should allow reasonable characters
                val p: HttpPath[Int] = HttpPath.int("user_id")
                succeed
            }

            "duplicate capture names" in {
                // Same capture name used twice - might be allowed but confusing
                val p: HttpPath[(Int, Int)] = HttpPath.int("id") / "sub" / HttpPath.int("id")
                succeed
            }
        }
    }

    "IntoTuple type" - {
        "non-tuple becomes singleton tuple" in {
            val _: HttpPath.IntoTuple[Int] = Tuple1(42)
            succeed
        }

        "tuple stays as tuple" in {
            val _: HttpPath.IntoTuple[(Int, String)] = (42, "hello")
            succeed
        }

        "Unit becomes singleton tuple" in {
            val _: HttpPath.IntoTuple[Unit] = Tuple1(())
            succeed
        }
    }

    "Inputs type" - {
        "Unit + Unit = Unit" in {
            val _: Inputs[Unit, Unit] = ()
            succeed
        }

        "Unit + A = A" in {
            val _: Inputs[Unit, Int] = 42
            succeed
        }

        "A + Unit = A" in {
            val _: Inputs[Int, Unit] = 42
            succeed
        }

        "A + B = (A, B)" in {
            val _: Inputs[Int, String] = (42, "hello")
            succeed
        }

        "tuple expansion" in {
            val _: Inputs[(Int, String), Long] = (42, "hello", 100L)
            succeed
        }

        "five element accumulation" in {
            val _: Inputs[(Int, String, Long, Boolean), Double] = (42, "hello", 100L, true, 3.14)
            succeed
        }

        "six element accumulation" in {
            val _: Inputs[(Int, String, Long, Boolean, Double), Char] = (42, "hello", 100L, true, 3.14, 'x')
            succeed
        }
    }

    "Route construction" - {

        "HTTP methods" - {
            "get" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.get("users")
                assert(r.method == Method.GET)
            }

            "post" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.post("users")
                assert(r.method == Method.POST)
            }

            "put" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.put("users")
                assert(r.method == Method.PUT)
            }

            "patch" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.patch("users")
                assert(r.method == Method.PATCH)
            }

            "delete" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.delete("users")
                assert(r.method == Method.DELETE)
            }

            "head" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.head("users")
                assert(r.method == Method.HEAD)
            }

            "options" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.options("users")
                assert(r.method == Method.OPTIONS)
            }
        }

        "with path captures" - {
            "single capture" in {
                val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users" / HttpPath.int("id"))
                assert(r.method == Method.GET)
            }

            "multiple captures" in {
                val r: HttpRoute[(Int, Int), Unit, Nothing] =
                    HttpRoute.get("users" / HttpPath.int("userId") / "posts" / HttpPath.int("postId"))
                assert(r.method == Method.GET)
            }
        }
    }

    "Query parameters" - {
        "required" in {
            val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users").query[Int]("limit")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "limit")
        }

        "with default" in {
            val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users").query[Int]("limit", 20)
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "limit")
        }

        "optional via Maybe" in {
            val r: HttpRoute[Maybe[String], Unit, Nothing] = HttpRoute.get("users").query[Maybe[String]]("search")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "search")
        }

        "multiple" in {
            val r: HttpRoute[(Int, Int), Unit, Nothing] =
                HttpRoute.get("users")
                    .query[Int]("limit")
                    .query[Int]("offset", 0)
            assert(r.queryParams.size == 2)
            assert(r.queryParams.map(_.name) == Seq("limit", "offset"))
        }

        "combined with path capture" in {
            val r: HttpRoute[(Int, String), Unit, Nothing] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .query[String]("fields")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "fields")
        }

        "edge cases" - {
            "empty query name throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRoute.get("users").query[Int]("")
                }
            }

            "query with empty value" in {
                val r = HttpRoute.get("users").query[String]("filter", "")
                assert(r.queryParams.size == 1)
                assert(r.queryParams.head.name == "filter")
            }

            "duplicate query parameter names" in {
                val r = HttpRoute.get("users")
                    .query[Int]("page")
                    .query[Int]("page", 1)
                assert(r.queryParams.size == 2)
            }

            "query value parsing failure" in run {
                val route   = HttpRoute.get("users").query[Int]("limit").output[Seq[User]]
                val handler = route.handle { limit => Seq(User(1, s"limit=$limit")) }
                startTestServer(handler).map { port =>
                    testGet(port, "/users?limit=abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid query int, got ${response.status}")
                    }
                }
            }

            "URL-encoded query value" in run {
                val route   = HttpRoute.get("users").query[String]("search").output[Seq[User]]
                val handler = route.handle { search => Seq(User(1, search)) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/users?search=hello%20world").map { users =>
                        assert(users.head.name == "hello world")
                    }
                }
            }
        }
    }

    "Headers" - {
        "required" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").header("X-Request-Id")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "X-Request-Id")
        }

        "with default" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").header("Accept", "application/json")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Accept")
        }

        "multiple" in {
            val r: HttpRoute[(String, String), Unit, Nothing] =
                HttpRoute.get("users")
                    .header("X-Request-Id")
                    .header("X-Trace-Id")
            assert(r.headerParams.size == 2)
            assert(r.headerParams.map(_.name) == Seq("X-Request-Id", "X-Trace-Id"))
        }

        "empty header name throws" in {
            assertThrows[IllegalArgumentException] {
                HttpRoute.get("users").header("")
            }
        }
    }

    "Cookies" - {
        "required" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("dashboard").cookie("session")
            assert(r.cookieParams.size == 1)
            assert(r.cookieParams.head.name == "session")
        }

        "with default" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("dashboard").cookie("theme", "light")
            assert(r.cookieParams.size == 1)
            assert(r.cookieParams.head.name == "theme")
        }

        "empty cookie name throws" in {
            assertThrows[IllegalArgumentException] {
                HttpRoute.get("dashboard").cookie("")
            }
        }
    }

    "Authentication" - {
        "bearer token" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").authBearer
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "basic auth" in {
            val r: HttpRoute[(String, String), Unit, Nothing] = HttpRoute.get("users").authBasic
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "API key" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").authApiKey("X-API-Key")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "X-API-Key")
        }

        "combined with path" in {
            val r: HttpRoute[(Int, String), Unit, Nothing] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .authBearer
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "empty API key header name throws" in {
            assertThrows[IllegalArgumentException] {
                HttpRoute.get("users").authApiKey("")
            }
        }
    }

    "Request body" - {
        "JSON input" in {
            val r: HttpRoute[CreateUser, Unit, Nothing] = HttpRoute.post("users").input[CreateUser]
            assert(r.inputSchema.isDefined)
        }

        "text input" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.post("echo").inputText
            assert(r.inputSchema.isDefined)
        }

        "form input" in {
            val r: HttpRoute[CreateUser, Unit, Nothing] = HttpRoute.post("login").inputForm[CreateUser]
            assert(r.inputSchema.isDefined)
        }

        "multipart input" in {
            val r: HttpRoute[Seq[Part], Unit, Nothing] = HttpRoute.post("upload").inputMultipart
            assert(r.method == Method.POST)
        }

        "combined with path and query" in {
            val r: HttpRoute[(Int, String, CreateUser), Unit, Nothing] =
                HttpRoute.put("users" / HttpPath.int("id"))
                    .query[String]("reason")
                    .input[CreateUser]
            assert(r.inputSchema.isDefined)
            assert(r.queryParams.size == 1)
        }
    }

    "Response body" - {
        "JSON output" in {
            val r: HttpRoute[Unit, Seq[User], Nothing] = HttpRoute.get("users").output[Seq[User]]
            assert(r.outputSchema.isDefined)
        }

        "JSON output with status" in {
            val r: HttpRoute[Unit, User, Nothing] = HttpRoute.post("users").output[User](Status.Created)
            assert(r.outputSchema.isDefined)
            assert(r.outputStatus == Status.Created)
        }

        "text output" in {
            val r: HttpRoute[Unit, String, Nothing] = HttpRoute.get("health").outputText
            assert(r.outputSchema.isDefined)
        }

    }

    "Errors" - {
        "single error type" in {
            val r: HttpRoute[Int, User, NotFoundError] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
            assert(r.errorSchemas.size == 1)
            assert(r.errorSchemas.head._1 == Status.NotFound)
        }

        "multiple error types" in {
            val r: HttpRoute[CreateUser, User, NotFoundError | ValidationError] =
                HttpRoute.post("users")
                    .input[CreateUser]
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .error[ValidationError](Status.BadRequest)
            assert(r.errorSchemas.size == 2)
            assert(r.errorSchemas.map(_._1) == Seq(Status.NotFound, Status.BadRequest))
        }
    }

    "Documentation" - {
        "withTag" in {
            val r = HttpRoute.get("users").tag("Users")
            assert(r.tag == Present("Users"))
        }

        "withSummary" in {
            val r = HttpRoute.get("users").summary("List all users")
            assert(r.summary == Present("List all users"))
        }

        "withDescription" in {
            val r = HttpRoute.get("users").description("Returns a paginated list")
            assert(r.description == Present("Returns a paginated list"))
        }

        "withOperationId" in {
            val r = HttpRoute.get("users").operationId("listUsers")
            assert(r.operationId == Present("listUsers"))
        }

        "deprecated" in {
            val r = HttpRoute.get("users/old").deprecated
            assert(r.isDeprecated)
        }

        "externalDocs with url" in {
            val r = HttpRoute.get("users").externalDocs("https://docs.example.com")
            assert(r.externalDocsUrl == Present("https://docs.example.com"))
        }

        "externalDocs with url and description" in {
            val r = HttpRoute.get("users").externalDocs("https://docs.example.com", "API Docs")
            assert(r.externalDocsUrl == Present("https://docs.example.com"))
            assert(r.externalDocsDesc == Present("API Docs"))
        }

        "security" in {
            val r = HttpRoute.get("users").security("bearerAuth")
            assert(r.securityScheme == Present("bearerAuth"))
        }

        "chaining all documentation" in {
            val r = HttpRoute.get("users")
                .output[Seq[User]]
                .tag("Users")
                .summary("List users")
                .description("Returns paginated list of users")
                .operationId("listUsers")
                .security("bearerAuth")
            assert(r.tag == Present("Users"))
            assert(r.summary == Present("List users"))
            assert(r.description == Present("Returns paginated list of users"))
            assert(r.operationId == Present("listUsers"))
            assert(r.securityScheme == Present("bearerAuth"))
        }
    }

    "Client call" - {
        "invokes route with input" in run {
            val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
            val handler = route.handle(id => User(id, s"User$id"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    route.call(42)
                }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "returns typed output" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    route.call(())
                }.map { users =>
                    assert(users.size == 2)
                    assert(users.head == User(1, "Alice"))
                }
            }
        }

        "propagates errors via Abort" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                if id == 999 then Abort.fail(NotFoundError("Not found"))
                else User(id, s"User$id")
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    Abort.run[NotFoundError](route.call(999))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "Handler creation" - {
        "from route" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id")).output[User]
            val handler: HttpHandler[Any] = route.handle { id =>
                User(id, "test")
            }
            startTestServer(handler).map { port =>
                testGetAs[User](port, "/users/7").map { user =>
                    assert(user == User(7, "test"))
                }
            }
        }

        "with multiple inputs" in run {
            val route = HttpRoute.put("users" / HttpPath.int("id"))
                .query[Boolean]("notify", false)
                .input[CreateUser]
                .output[User]
            val handler: HttpHandler[Any] = route.handle { case (id, notify, user) =>
                User(id, user.name)
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.put(s"http://localhost:$port/users/5?notify=true", CreateUser("Alice", "a@b.com"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "Alice")
                }
            }
        }

        "with effects" in {
            val route = HttpRoute.get("users").output[Seq[User]]
            val handler: HttpHandler[Env[String]] = route.handle { _ =>
                Env.get[String].map(prefix => Seq(User(1, s"$prefix-Alice")))
            }
            assert(handler.route eq route)
        }
    }

    "Route matching" - {
        "exact path match" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq(User(1, "Alice")))
            startTestServer(handler).map { port =>
                for
                    r1 <- testGet(port, "/users")
                    r2 <- testGet(port, "/users/123")
                yield
                    assertStatus(r1, Status.OK)
                    assertStatus(r2, Status.NotFound)
                end for
            }
        }

        "path with captures match" in run {
            val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
            val handler = route.handle(id => User(id, s"User$id"))
            startTestServer(handler).map { port =>
                for
                    r1 <- testGet(port, "/users/123")
                    r2 <- testGet(port, "/users")
                yield
                    assertStatus(r1, Status.OK)
                    assertStatus(r2, Status.NotFound)
                end for
            }
        }

        "method mismatch" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testPost(port, "/users", User(1, "test")).map { response =>
                    assertStatus(response, Status.MethodNotAllowed)
                }
            }
        }

        "path mismatch" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/posts").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }

        "partial path match fails" in run {
            val route   = HttpRoute.get("api" / "v1" / "users").output[Seq[User]]
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/api/v1").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }

        "extra path segments fail" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/users/extra").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }
    }

    "Full route examples" - {
        "CRUD API" in {
            object UserRoutes:
                val list = HttpRoute.get("users")
                    .query[Int]("limit", 20)
                    .query[Int]("offset", 0)
                    .output[Seq[User]]
                    .tag("Users")

                val get = HttpRoute.get("users" / HttpPath.int("id"))
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .tag("Users")

                val create = HttpRoute.post("users")
                    .authBearer
                    .input[CreateUser]
                    .output[User](Status.Created)
                    .error[ValidationError](Status.BadRequest)
                    .tag("Users")

                val update = HttpRoute.put("users" / HttpPath.int("id"))
                    .authBearer
                    .input[CreateUser]
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .error[ValidationError](Status.BadRequest)
                    .tag("Users")

                val delete = HttpRoute.delete("users" / HttpPath.int("id"))
                    .authBearer
                    .output[Unit]
                    .error[NotFoundError](Status.NotFound)
                    .tag("Users")
            end UserRoutes
            assert(UserRoutes.list.method == Method.GET)
            assert(UserRoutes.list.queryParams.size == 2)
            assert(UserRoutes.get.errorSchemas.size == 1)
            assert(UserRoutes.create.method == Method.POST)
            assert(UserRoutes.create.outputStatus == Status.Created)
            assert(UserRoutes.create.inputSchema.isDefined)
            assert(UserRoutes.update.method == Method.PUT)
            assert(UserRoutes.update.errorSchemas.size == 2)
            assert(UserRoutes.delete.method == Method.DELETE)
        }
    }

end HttpRouteTest
