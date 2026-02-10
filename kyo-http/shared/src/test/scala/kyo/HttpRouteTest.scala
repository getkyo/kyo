package kyo

import HttpPath./
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
                val p: HttpPath[EmptyTuple] = HttpPath("users")
                succeed
            }

            "implicit string conversion" in {
                val p: HttpPath[EmptyTuple] = "users"
                succeed
            }
        }

        "captures" - {
            "int" in {
                val p: HttpPath[(id: Int)] = HttpPath.int("id")
                succeed
            }

            "long" in {
                val p: HttpPath[(id: Long)] = HttpPath.long("id")
                succeed
            }

            "string" in {
                val p: HttpPath[(slug: String)] = HttpPath.string("slug")
                succeed
            }

            "uuid" in {
                val p: HttpPath[(id: java.util.UUID)] = HttpPath.uuid("id")
                succeed
            }

            "boolean" in {
                val p: HttpPath[(active: Boolean)] = HttpPath.boolean("active")
                succeed
            }
        }

        "concatenation" - {
            "string / string" in {
                val p: HttpPath[EmptyTuple] = "api" / "v1" / "users"
                succeed
            }

            "string / capture" in {
                val p: HttpPath[(id: Int)] = "users" / HttpPath.int("id")
                succeed
            }

            "capture / string" in {
                val p: HttpPath[(id: Int)] = HttpPath.int("id") / "details"
                succeed
            }

            "two captures" in {
                val p: HttpPath[(userId: Int, postId: Int)] = "users" / HttpPath.int("userId") / "posts" / HttpPath.int("postId")
                succeed
            }

            "three captures" in {
                val p: HttpPath[(orgId: Int, name: String, itemId: Long)] =
                    "org" / HttpPath.int("orgId") / "users" / HttpPath.string("name") / "items" / HttpPath.long("itemId")
                succeed
            }

            "four captures" in {
                val p: HttpPath[(a: Int, b: String, c: Long, d: Boolean)] =
                    "a" / HttpPath.int("a") / "b" / HttpPath.string("b") / "c" / HttpPath.long("c") / "d" / HttpPath.boolean("d")
                succeed
            }

            "capture / capture" in {
                val p: HttpPath[(id: Int, name: String)] = HttpPath.int("id") / HttpPath.string("name")
                succeed
            }
        }

        "edge cases" - {
            "empty string segment" in {
                val p: HttpPath[EmptyTuple] = HttpPath("")
                succeed
            }

            "segment with special characters" in {
                val p: HttpPath[EmptyTuple] = "users-v2" / "items_list"
                succeed
            }

            "segment with encoded slashes" in {
                // Path segments shouldn't contain raw slashes
                val p: HttpPath[EmptyTuple] = "users%2Flist"
                succeed
            }

            "leading slash" in {
                // Leading slash should be normalized
                val p: HttpPath[EmptyTuple] = HttpPath("/users")
                succeed
            }

            "trailing slash" in {
                val p: HttpPath[EmptyTuple] = "users/"
                succeed
            }

            "double slashes" in {
                // Double slashes should be normalized
                val p: HttpPath[EmptyTuple] = "api" / "" / "users"
                succeed
            }

            "dot segments" in {
                val p: HttpPath[EmptyTuple] = "api" / "." / "users"
                succeed
            }
        }

        "parsing failures" - {
            "int capture with non-numeric value" in run {
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
                val handler = route.handle(in => User(in.id, s"User${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid int, got ${response.status}")
                    }
                }
            }

            "int capture with overflow" in run {
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
                val handler = route.handle(in => User(in.id, s"User${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/99999999999999999999").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for int overflow, got ${response.status}")
                    }
                }
            }

            "long capture with non-numeric value" in run {
                val route   = HttpRoute.get("items" / HttpPath.long("id")).output[User]
                val handler = route.handle(in => User(in.id.toInt, s"Item${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid long, got ${response.status}")
                    }
                }
            }

            "long capture with overflow" in run {
                val route   = HttpRoute.get("items" / HttpPath.long("id")).output[User]
                val handler = route.handle(in => User(in.id.toInt, s"Item${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/999999999999999999999999999999").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for long overflow, got ${response.status}")
                    }
                }
            }

            "uuid capture with invalid format" in run {
                val route   = HttpRoute.get("items" / HttpPath.uuid("id")).output[User]
                val handler = route.handle(in => User(1, in.id.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/not-a-uuid").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid uuid, got ${response.status}")
                    }
                }
            }

            "boolean capture with invalid value" in run {
                val route   = HttpRoute.get("flags" / HttpPath.boolean("active")).output[User]
                val handler = route.handle(in => User(if in.active then 1 else 0, in.active.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/flags/maybe").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid boolean, got ${response.status}")
                    }
                }
            }
        }

        "capture name edge cases" - {
            "empty capture name rejected at compile time" in pendingUntilFixed {
                typeCheckFailure("""HttpPath.int("")""")("Capture name cannot be empty")
            }

            "capture name with special characters" in {
                // Capture names should allow reasonable characters
                val p: HttpPath[(user_id: Int)] = HttpPath.int("user_id")
                succeed
            }

            "duplicate capture names" in {
                // Same capture name used twice — in named tuples the second field
                // shadows the first, but Combine still concatenates them at the type level
                val p = HttpPath.int("id") / "sub" / HttpPath.int("id2")
                succeed
            }
        }
    }

    "Route construction" - {

        "HTTP methods" - {
            "get" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.get("users")
                assert(r.method == Method.GET)
            }

            "post" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.post("users")
                assert(r.method == Method.POST)
            }

            "put" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.put("users")
                assert(r.method == Method.PUT)
            }

            "patch" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.patch("users")
                assert(r.method == Method.PATCH)
            }

            "delete" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.delete("users")
                assert(r.method == Method.DELETE)
            }

            "head" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.head("users")
                assert(r.method == Method.HEAD)
            }

            "options" in {
                val r: HttpRoute[EmptyTuple, Unit, Nothing] = HttpRoute.options("users")
                assert(r.method == Method.OPTIONS)
            }
        }

        "with path captures" - {
            "single capture" in {
                val r: HttpRoute[(id: Int), Unit, Nothing] = HttpRoute.get("users" / HttpPath.int("id"))
                assert(r.method == Method.GET)
            }

            "multiple captures" in {
                val r: HttpRoute[(userId: Int, postId: Int), Unit, Nothing] =
                    HttpRoute.get("users" / HttpPath.int("userId") / "posts" / HttpPath.int("postId"))
                assert(r.method == Method.GET)
            }
        }
    }

    "Query parameters" - {
        "required" in {
            val r: HttpRoute[(limit: Int), Unit, Nothing] = HttpRoute.get("users").query[Int]("limit")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "limit")
        }

        "with default" in {
            val r: HttpRoute[(limit: Int), Unit, Nothing] = HttpRoute.get("users").query[Int]("limit", 20)
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "limit")
        }

        "optional via Maybe" in {
            val r: HttpRoute[(search: Maybe[String]), Unit, Nothing] = HttpRoute.get("users").query[Maybe[String]]("search")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "search")
        }

        "multiple" in {
            val r: HttpRoute[(limit: Int, offset: Int), Unit, Nothing] =
                HttpRoute.get("users")
                    .query[Int]("limit")
                    .query[Int]("offset", 0)
            assert(r.queryParams.size == 2)
            assert(r.queryParams.map(_.name) == Seq("limit", "offset"))
        }

        "combined with path capture" in {
            val r: HttpRoute[(id: Int, fields: String), Unit, Nothing] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .query[String]("fields")
            assert(r.queryParams.size == 1)
            assert(r.queryParams.head.name == "fields")
        }

        "dynamic capture names" - {
            "variable name becomes field name" in run {
                val limit   = "limit"
                val route   = HttpRoute.get("users").query[Int](limit).output[Seq[User]]
                val handler = route.handle { in => Seq(User(1, s"limit=${in.limit}")) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/users?limit=10").map { users =>
                        assert(users.head.name == "limit=10")
                    }
                }
            }

            "inline val name becomes field name" in run {
                inline val pageSize = "page_size"
                val route           = HttpRoute.get("items").query[Int](pageSize).output[Seq[User]]
                val handler         = route.handle { in => Seq(User(1, s"size=${in.pageSize}")) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/items?page_size=25").map { users =>
                        assert(users.head.name == "size=25")
                    }
                }
            }

            "dotted path becomes underscore-joined field name" in run {
                object params:
                    val sortBy = "sort_by"
                val route   = HttpRoute.get("items").query[String](params.sortBy).output[Seq[User]]
                val handler = route.handle { in => Seq(User(1, in.params_sortBy)) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/items?sort_by=name").map { users =>
                        assert(users.head.name == "name")
                    }
                }
            }

            "backtick identifier strips backticks" in run {
                val `field-name` = "field-name"
                val route        = HttpRoute.get("items").query[String](`field-name`).output[Seq[User]]
                val handler      = route.handle { in => Seq(User(1, in.field_name)) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/items?field-name=hello").map { users =>
                        assert(users.head.name == "hello")
                    }
                }
            }
        }

        "edge cases" - {
            "empty query name rejected at compile time" in pendingUntilFixed {
                // The macro rejects empty names at compile time via report.errorAndAbort.
                // typeCheckFailure cannot verify this (transparent inline + macro splice limitation).
                typeCheckFailure("""HttpRoute.get("users").query[Int]("")""")("Capture name cannot be empty")
            }

            "query with empty value" in {
                val r = HttpRoute.get("users").query[String]("filter", "")
                assert(r.queryParams.size == 1)
                assert(r.queryParams.head.name == "filter")
            }

            "duplicate query parameter names rejected at compile time" in pendingUntilFixed {
                typeCheckFailure("""
                    HttpRoute.get("users")
                        .query[Int]("page")
                        .query[Int]("page", 1)
                """)("Duplicate field name 'page'")
            }

            "query value parsing failure" in run {
                val route   = HttpRoute.get("users").query[Int]("limit").output[Seq[User]]
                val handler = route.handle { in => Seq(User(1, s"limit=${in.limit}")) }
                startTestServer(handler).map { port =>
                    testGet(port, "/users?limit=abc").map { response =>
                        assert(response.status != Status.OK, s"Expected non-OK status for invalid query int, got ${response.status}")
                    }
                }
            }

            "URL-encoded query value" in run {
                val route   = HttpRoute.get("users").query[String]("search").output[Seq[User]]
                val handler = route.handle { in => Seq(User(1, in.search)) }
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
            val r = HttpRoute.get("users").header("X-Request-Id")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "X-Request-Id")
        }

        "with default" in {
            val r = HttpRoute.get("users").header("Accept", "application/json")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Accept")
        }

        "multiple" in {
            val r =
                HttpRoute.get("users")
                    .header("X-Request-Id")
                    .header("X-Trace-Id")
            assert(r.headerParams.size == 2)
            assert(r.headerParams.map(_.name) == Seq("X-Request-Id", "X-Trace-Id"))
        }

        "dynamic capture names" - {
            "variable name for header" in run {
                val tenant  = "X-Tenant"
                val route   = HttpRoute.get("data").header(tenant).output[User]
                val handler = route.handle { in => User(1, in.tenant) }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/data").addHeader("X-Tenant", "acme")
                    ).map { response =>
                        val user = Schema[User].decode(response.bodyText)
                        assert(user.name == "acme")
                    }
                }
            }

            "inline val name for header" in run {
                inline val reqId = "X-Request-Id"
                val route        = HttpRoute.get("echo").header(reqId).output[User]
                val handler      = route.handle { in => User(1, in.reqId) }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/echo").addHeader("X-Request-Id", "abc-123")
                    ).map { response =>
                        val user = Schema[User].decode(response.bodyText)
                        assert(user.name == "abc-123")
                    }
                }
            }
        }

        "empty header name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""HttpRoute.get("users").header("")""")("Capture name cannot be empty")
        }
    }

    "Cookies" - {
        "required" in {
            val r: HttpRoute[(session: String), Unit, Nothing] = HttpRoute.get("dashboard").cookie("session")
            assert(r.cookieParams.size == 1)
            assert(r.cookieParams.head.name == "session")
        }

        "with default" in {
            val r: HttpRoute[(theme: String), Unit, Nothing] = HttpRoute.get("dashboard").cookie("theme", "light")
            assert(r.cookieParams.size == 1)
            assert(r.cookieParams.head.name == "theme")
        }

        "empty cookie name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""HttpRoute.get("dashboard").cookie("")""")("Capture name cannot be empty")
        }
    }

    "Authentication" - {
        "bearer token" in {
            val r: HttpRoute[(bearer: String), Unit, Nothing] = HttpRoute.get("users").authBearer
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "basic auth" in {
            val r: HttpRoute[(username: String, password: String), Unit, Nothing] = HttpRoute.get("users").authBasic
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "API key" in {
            val r = HttpRoute.get("users").authApiKey("X-API-Key")
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "X-API-Key")
        }

        "combined with path" in {
            val r: HttpRoute[(id: Int, bearer: String), Unit, Nothing] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .authBearer
            assert(r.headerParams.size == 1)
            assert(r.headerParams.head.name == "Authorization")
        }

        "empty API key header name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""HttpRoute.get("users").authApiKey("")""")("Capture name cannot be empty")
        }
    }

    "Request body" - {
        "JSON input" in {
            val r: HttpRoute[(body: CreateUser), Unit, Nothing] = HttpRoute.post("users").input[CreateUser]
            assert(r.inputSchema.isDefined)
        }

        "text input" in {
            val r: HttpRoute[(body: String), Unit, Nothing] = HttpRoute.post("echo").inputText
            assert(r.inputSchema.isDefined)
        }

        "form input" in {
            val r: HttpRoute[(body: CreateUser), Unit, Nothing] = HttpRoute.post("login").inputForm[CreateUser]
            assert(r.inputSchema.isDefined)
        }

        "multipart input" in {
            val r: HttpRoute[(parts: Seq[Part]), Unit, Nothing] = HttpRoute.post("upload").inputMultipart
            assert(r.method == Method.POST)
        }

        "combined with path and query" in {
            val r: HttpRoute[(id: Int, reason: String, body: CreateUser), Unit, Nothing] =
                HttpRoute.put("users" / HttpPath.int("id"))
                    .query[String]("reason")
                    .input[CreateUser]
            assert(r.inputSchema.isDefined)
            assert(r.queryParams.size == 1)
        }
    }

    "Response body" - {
        "JSON output" in {
            val r: HttpRoute[EmptyTuple, Seq[User], Nothing] = HttpRoute.get("users").output[Seq[User]]
            assert(r.outputSchema.isDefined)
        }

        "JSON output with status" in {
            val r: HttpRoute[EmptyTuple, User, Nothing] = HttpRoute.post("users").output[User](Status.Created)
            assert(r.outputSchema.isDefined)
            assert(r.outputStatus == Status.Created)
        }

        "text output" in {
            val r: HttpRoute[EmptyTuple, String, Nothing] = HttpRoute.get("health").outputText
            assert(r.outputSchema.isDefined)
        }

    }

    "Errors" - {
        "single error type" in {
            val r: HttpRoute[(id: Int), User, NotFoundError] =
                HttpRoute.get("users" / HttpPath.int("id"))
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
            assert(r.errorSchemas.size == 1)
            assert(r.errorSchemas.head._1 == Status.NotFound)
        }

        "multiple error types" in {
            val r: HttpRoute[(body: CreateUser), User, NotFoundError | ValidationError] =
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
        "literal-only path" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, EmptyTuple)
                }.map { users =>
                    assert(users.size == 2)
                    assert(users.head == User(1, "Alice"))
                }
            }
        }

        "literal / capture" in run {
            val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, Tuple1(42))
                }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "literal / capture / literal" in run {
            val route   = HttpRoute.get("users" / HttpPath.int("id") / "profile").output[User]
            val handler = route.handle(in => User(in.id, "profile"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, Tuple1(42))
                }.map { user =>
                    assert(user == User(42, "profile"))
                }
            }
        }

        "literal / capture / literal / capture" in run {
            val route = HttpRoute.get("orgs" / HttpPath.string("org") / "users" / HttpPath.int("id"))
                .output[User]
            val handler = route.handle { in =>
                User(in.id, in.org)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("acme", 42))
                }.map { user =>
                    assert(user == User(42, "acme"))
                }
            }
        }

        "capture / capture (adjacent)" in run {
            val route = HttpRoute.get("items" / HttpPath.string("category") / HttpPath.int("id"))
                .output[User]
            val handler = route.handle { in =>
                User(in.id, in.category)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("books", 99))
                }.map { user =>
                    assert(user == User(99, "books"))
                }
            }
        }

        "deep path: literal / capture / literal / capture / literal" in run {
            val route = HttpRoute.get("api" / HttpPath.string("version") / "users" / HttpPath.int("id") / "settings")
                .output[User]
            val handler = route.handle { in =>
                User(in.id, in.version)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("v2", 7))
                }.map { user =>
                    assert(user == User(7, "v2"))
                }
            }
        }

        "propagates errors via Abort" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { in =>
                if in.id == 999 then Abort.fail(NotFoundError("Not found"))
                else User(in.id, s"User${in.id}")
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    Abort.run[NotFoundError](HttpClient.call(route, Tuple1(999)))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "Handler creation" - {
        "from route" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id")).output[User]
            val handler: HttpHandler[Any] = route.handle { in =>
                User(in.id, "test")
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
            val handler: HttpHandler[Any] = route.handle { in =>
                User(in.id, in.body.name)
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
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
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

    "Name conflicts" - {

        "duplicate path capture names: first wins at type level" in run {
            val route = HttpRoute.get("a" / HttpPath.int("id") / "b" / HttpPath.string("id"))
                .outputText
            val handler = route.handle { in =>
                // in.id resolves to the first (Int) field
                val value: Int = in.id
                s"id=$value"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/a/42/b/hello").map { response =>
                    assertBodyContains(response, "id=42")
                }
            }
        }

        "path and query with same name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.get("users" / HttpPath.int("id"))
                    .query[String]("id")
                    .outputText
            """)("Duplicate field name 'id'")
        }

        "query and header with same name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.get("users")
                    .query[String]("token")
                    .header("token")
            """)("Duplicate field name 'token'")
        }

        "pattern match accesses both duplicate fields" in run {
            val route = HttpRoute.get("a" / HttpPath.int("id") / "b" / HttpPath.string("id"))
                .outputText
            val handler = route.handle { in =>
                val (pathId, nameId) = (in: (Int, String))
                s"path=$pathId,name=$nameId"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/a/42/b/hello").map { response =>
                    assertBodyContains(response, "path=42,name=hello")
                }
            }
        }

        "path capture named 'body' conflicts with input" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.post("data" / HttpPath.string("body"))
                    .input[String]
            """)("Duplicate field name 'body'")
        }

        "path capture named 'bearer' conflicts with authBearer" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.get("auth" / HttpPath.string("bearer"))
                    .authBearer
            """)("Duplicate field name 'bearer'")
        }

        "query named 'body' conflicts with input" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.post("data")
                    .query[String]("body")
                    .input[String]
            """)("Duplicate field name 'body'")
        }
    }

    "Mixed input sources" - {

        "path + query + body" in run {
            val route = HttpRoute.post("users" / HttpPath.int("id"))
                .query[Boolean]("notify")
                .input[CreateUser]
                .output[User]
            val handler = route.handle { in =>
                User(in.id, s"${in.body.name},notify=${in.notify}")
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.post(s"http://localhost:$port/users/42?notify=true", CreateUser("Alice", "a@b.com"))
                ).map { response =>
                    assertBodyContains(response, "Alice,notify=true")
                }
            }
        }

        "path + header + body" in run {
            val route = HttpRoute.post("items" / HttpPath.int("id"))
                .header("X-Tenant")
                .input[CreateUser]
                .output[User]
            val handler = route.handle { in =>
                User(in.id, s"${in.body.name}@${in.X_Tenant}")
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.post(s"http://localhost:$port/items/1", CreateUser("Bob", "b@c.com"))
                        .addHeader("X-Tenant", "acme")
                ).map { response =>
                    assertBodyContains(response, "Bob@acme")
                }
            }
        }

        "path + cookie + query" in run {
            val route = HttpRoute.get("data" / HttpPath.int("id"))
                .cookie("session")
                .query[String]("format")
                .outputText
            val handler = route.handle { in =>
                s"id=${in.id},session=${in.session},format=${in.format}"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(
                        s"http://localhost:$port/data/7?format=json",
                        HttpHeaders.empty.add("Cookie", "session=abc123")
                    )
                ).map { response =>
                    assertBodyContains(response, "id=7")
                    assertBodyContains(response, "session=abc123")
                    assertBodyContains(response, "format=json")
                }
            }
        }

        "path + query + authBearer" in run {
            val route = HttpRoute.get("items" / HttpPath.int("id"))
                .query[String]("fields")
                .authBearer
                .outputText
            val handler = route.handle { in =>
                s"id=${in.id},fields=${in.fields},bearer=${in.bearer}"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(
                        s"http://localhost:$port/items/5?fields=name",
                        HttpHeaders.empty.add("Authorization", "Bearer tok123")
                    )
                ).map { response =>
                    assertBodyContains(response, "id=5")
                    assertBodyContains(response, "fields=name")
                    assertBodyContains(response, "bearer=tok123")
                }
            }
        }

        "query + authBasic" in run {
            val route = HttpRoute.get("secure")
                .query[String]("action")
                .authBasic
                .outputText
            val handler = route.handle { in =>
                s"action=${in.action},user=${in.username}"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(
                        s"http://localhost:$port/secure?action=read",
                        HttpHeaders.empty.add("Authorization", "Basic " + java.util.Base64.getEncoder.encodeToString("admin:pass".getBytes))
                    )
                ).map { response =>
                    assertBodyContains(response, "action=read")
                    assertBodyContains(response, "user=admin")
                }
            }
        }
    }

end HttpRouteTest
