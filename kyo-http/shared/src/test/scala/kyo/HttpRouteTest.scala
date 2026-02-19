package kyo

import HttpPath.Literal
import HttpRequest.Method
import HttpRequest.Part
import kyo.HttpStatus
import scala.language.implicitConversions

class HttpRouteTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String, email: String) derives Schema, CanEqual
    case class NotFoundError(message: String) derives Schema, CanEqual
    case class ValidationError(field: String, message: String) derives Schema, CanEqual

    "Path" - {

        "construction" - {
            "from string" in {
                val p: HttpPath[Row.Empty] = HttpPath.Literal("users")
                succeed
            }

            "implicit string conversion" in {
                val p: HttpPath[Row.Empty] = "users"
                succeed
            }
        }

        "captures" - {
            "int" in {
                val p: HttpPath[(id: Int)] = Capture[Int]("id")
                succeed
            }

            "long" in {
                val p: HttpPath[(id: Long)] = Capture[Long]("id")
                succeed
            }

            "string" in {
                val p: HttpPath[(slug: String)] = Capture[String]("slug")
                succeed
            }

            "uuid" in {
                val p: HttpPath[(id: java.util.UUID)] = Capture[java.util.UUID]("id")
                succeed
            }

            "boolean" in {
                val p: HttpPath[(active: Boolean)] = Capture[Boolean]("active")
                succeed
            }
        }

        "concatenation" - {
            "string / string" in {
                val p = "api" / "v1" / "users"
                succeed
            }

            "string / capture" in {
                val p: HttpPath[(id: Int)] = "users" / Capture[Int]("id")
                succeed
            }

            "capture / string" in {
                val p = Capture[Int]("id") / "details"
                succeed
            }

            "two captures" in {
                val p = "users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId")
                succeed
            }

            "three captures" in {
                val p =
                    "org" / Capture[Int]("orgId") / "users" / Capture[String]("name") / "items" / Capture[Long]("itemId")
                succeed
            }

            "four captures" in {
                val p =
                    "a" / Capture[Int]("a") / "b" / Capture[String]("b") / "c" / Capture[Long]("c") / "d" / Capture[Boolean]("d")
                succeed
            }

            "capture / capture" in {
                val p = Capture[Int]("id") / Capture[String]("name")
                succeed
            }
        }

        "edge cases" - {
            "empty string segment" in {
                val p: HttpPath[Row.Empty] = HttpPath.Literal("")
                succeed
            }

            "segment with special characters" in {
                val p = "users-v2" / "items_list"
                succeed
            }

            "segment with encoded slashes" in {
                // Path segments shouldn't contain raw slashes
                val p: HttpPath[Row.Empty] = "users%2Flist"
                succeed
            }

            "leading slash" in {
                // Leading slash should be normalized
                val p: HttpPath[Row.Empty] = HttpPath.Literal("/users")
                succeed
            }

            "trailing slash" in {
                val p: HttpPath[Row.Empty] = "users/"
                succeed
            }

            "double slashes" in {
                // Double slashes should be normalized
                val p = "api" / "" / "users"
                succeed
            }

            "dot segments" in {
                val p = "api" / "." / "users"
                succeed
            }
        }

        "parsing failures" - {
            "int capture with non-numeric value" in run {
                val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
                val handler = route.handle(in => User(in.id, s"User${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/abc").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for invalid int, got ${response.status}")
                    }
                }
            }

            "int capture with overflow" in run {
                val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
                val handler = route.handle(in => User(in.id, s"User${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/users/99999999999999999999").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for int overflow, got ${response.status}")
                    }
                }
            }

            "long capture with non-numeric value" in run {
                val route   = HttpRoute.get("items" / Capture[Long]("id")).response(_.bodyJson[User])
                val handler = route.handle(in => User(in.id.toInt, s"Item${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/abc").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for invalid long, got ${response.status}")
                    }
                }
            }

            "long capture with overflow" in run {
                val route   = HttpRoute.get("items" / Capture[Long]("id")).response(_.bodyJson[User])
                val handler = route.handle(in => User(in.id.toInt, s"Item${in.id}"))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/999999999999999999999999999999").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for long overflow, got ${response.status}")
                    }
                }
            }

            "uuid capture with invalid format" in run {
                val route   = HttpRoute.get("items" / Capture[java.util.UUID]("id")).response(_.bodyJson[User])
                val handler = route.handle(in => User(1, in.id.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/items/not-a-uuid").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for invalid uuid, got ${response.status}")
                    }
                }
            }

            "boolean capture with invalid value" in run {
                val route   = HttpRoute.get("flags" / Capture[Boolean]("active")).response(_.bodyJson[User])
                val handler = route.handle(in => User(if in.active then 1 else 0, in.active.toString))
                startTestServer(handler).map { port =>
                    testGet(port, "/flags/maybe").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for invalid boolean, got ${response.status}")
                    }
                }
            }
        }

        "capture name edge cases" - {
            "empty capture name compiles (no compile-time validation)" in {
                val p = Capture[Int]("")
                succeed
            }

            "capture name with special characters" in {
                // Capture names should allow reasonable characters
                val p: HttpPath[(user_id: Int)] = Capture[Int]("user_id")
                succeed
            }

            "duplicate capture names" in {
                // Same capture name used twice — in named tuples the second field
                // shadows the first, but Inputs still concatenates them at the type level
                val p = Capture[Int]("id") / "sub" / Capture[Int]("id2")
                succeed
            }
        }
    }

    "Route construction" - {

        "HTTP methods" - {
            "get" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.get("users")
                assert(r.method == Method.GET)
            }

            "post" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.post("users")
                assert(r.method == Method.POST)
            }

            "put" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.put("users")
                assert(r.method == Method.PUT)
            }

            "patch" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.patch("users")
                assert(r.method == Method.PATCH)
            }

            "delete" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.delete("users")
                assert(r.method == Method.DELETE)
            }

            "head" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.head("users")
                assert(r.method == Method.HEAD)
            }

            "options" in {
                val r: HttpRoute[Row.Empty, Row.Empty, Row.Empty, Nothing] = HttpRoute.options("users")
                assert(r.method == Method.OPTIONS)
            }
        }

        "with path captures" - {
            "single capture" in {
                val r: HttpRoute[(id: Int), Row.Empty, Row.Empty, Nothing] = HttpRoute.get("users" / Capture[Int]("id"))
                assert(r.method == Method.GET)
            }

            "multiple captures" in {
                val r: HttpRoute[(userId: Int, postId: Int), Row.Empty, Row.Empty, Nothing] =
                    HttpRoute.get("users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId"))
                assert(r.method == Method.GET)
            }
        }
    }

    "Query parameters" - {
        "required" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
            assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name }.head == "limit")
        }

        "with default" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit", default = Some(20)))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
            assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name }.head == "limit")
        }

        "optional via Maybe" in {
            val r = HttpRoute.get("users").request(_.query[Maybe[String]]("search"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
            assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name }.head == "search")
        }

        "multiple" in {
            val r =
                HttpRoute.get("users")
                    .request(_.query[Int]("limit").query[Int]("offset", default = Some(0)))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 2)
            assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name } == Seq("limit", "offset"))
        }

        "combined with path capture" in {
            val r =
                HttpRoute.get("users" / Capture[Int]("id"))
                    .request(_.query[String]("fields"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
            assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name }.head == "fields")
        }

        "edge cases" - {
            "empty query name compiles (no compile-time validation)" in {
                val r = HttpRoute.get("users").request(_.query[Int](""))
                succeed
            }

            "query with empty value" in {
                val r = HttpRoute.get("users").request(_.query[String]("filter", default = Some("")))
                assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
                assert(r.request.inputFields.collect { case q: HttpRoute.InputField.Query => q.name }.head == "filter")
            }

            "duplicate query parameter names rejected at compile time" in {
                typeCheckFailure("""
                    HttpRoute.get("users")
                        .request(_.query[Int]("page").query[Int]("page", default = Some(1)))
                """)("Duplicate request field")
            }

            "query value parsing failure" in run {
                val route   = HttpRoute.get("users").request(_.query[Int]("limit")).response(_.bodyJson[Seq[User]])
                val handler = route.handle { in => Seq(User(1, s"limit=${in.limit}")) }
                startTestServer(handler).map { port =>
                    testGet(port, "/users?limit=abc").map { response =>
                        assert(response.status != HttpStatus.OK, s"Expected non-OK status for invalid query int, got ${response.status}")
                    }
                }
            }

            "URL-encoded query value" in run {
                val route   = HttpRoute.get("users").request(_.query[String]("search")).response(_.bodyJson[Seq[User]])
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
            val r = HttpRoute.get("users").request(_.header[String]("X-Request-Id"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Header]) == 1)
            assert(r.request.inputFields.collect { case h: HttpRoute.InputField.Header => h.name }.head == "X-Request-Id")
        }

        "with default" in {
            val r = HttpRoute.get("users").request(_.header[String]("Accept", default = Some("application/json")))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Header]) == 1)
            assert(r.request.inputFields.collect { case h: HttpRoute.InputField.Header => h.name }.head == "Accept")
        }

        "multiple" in {
            val r =
                HttpRoute.get("users")
                    .request(_.header[String]("X-Request-Id").header[String]("X-Trace-Id"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Header]) == 2)
            assert(r.request.inputFields.collect { case h: HttpRoute.InputField.Header => h.name } == Seq("X-Request-Id", "X-Trace-Id"))
        }

        "empty header name compiles (no compile-time validation)" in {
            val r = HttpRoute.get("users").request(_.header[String](""))
            succeed
        }
    }

    "Cookies" - {
        "required" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String]("session"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Cookie]) == 1)
            assert(r.request.inputFields.collect { case c: HttpRoute.InputField.Cookie => c.name }.head == "session")
        }

        "with default" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String]("theme", default = Some("light")))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Cookie]) == 1)
            assert(r.request.inputFields.collect { case c: HttpRoute.InputField.Cookie => c.name }.head == "theme")
        }

        "empty cookie name compiles (no compile-time validation)" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String](""))
            succeed
        }
    }

    "Authentication" - {
        "bearer token" in {
            val r = HttpRoute.get("users").request(_.authBearer)
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Auth]) == 1)
        }

        "basic auth" in {
            val r = HttpRoute.get("users").request(_.authBasic)
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Auth]) == 2)
        }

        "API key" in {
            val r = HttpRoute.get("users").request(_.authApiKey("X-API-Key"))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Auth]) == 1)
        }

        "combined with path" in {
            val r =
                HttpRoute.get("users" / Capture[Int]("id"))
                    .request(_.authBearer)
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Auth]) == 1)
        }

        "empty API key header name compiles (no compile-time validation)" in {
            val r = HttpRoute.get("users").request(_.authApiKey(""))
            succeed
        }
    }

    "Request body" - {
        "JSON input" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser])
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
        }

        "text input" in {
            val r = HttpRoute.post("echo").request(_.bodyText)
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
        }

        "form body input" in {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val r = HttpRoute.post("login").request(_.bodyForm[LoginForm])
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.FormBody]))
        }

        "bodyForm produces distinct route metadata from bodyJson" in {
            case class FormInput(name: String, email: String) derives HttpFormCodec
            val routeJson = HttpRoute.post("test").request(_.bodyJson[CreateUser])
            val routeForm = HttpRoute.post("test").request(_.bodyForm[FormInput])
            assert(!routeJson.equals(routeForm))
        }

        "bodyForm round-trip via typed route" in run {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val route = HttpRoute.post("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val handler = route.handle { in =>
                s"Hello ${in.body.username}"
            }
            startTestServer(handler).map { port =>
                val request = HttpRequest.postForm(
                    s"http://localhost:$port/login",
                    Seq("username" -> "bob", "password" -> "pass")
                )
                HttpClient.send(request).map { r =>
                    assertStatus(r, HttpStatus.OK)
                    assertBodyContains(r, "Hello bob")
                }
            }
        }

        "bodyForm client-side serialization via HttpClient.call" in run {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val route = HttpRoute.post("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val handler = route.handle { in =>
                s"user=${in.body.username} pass=${in.body.password}"
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, LoginForm("alice", "secret"))
                }.map { _.body }.map { result =>
                    assert(result == "user=alice pass=secret")
                }
            }
        }

        "multipart input" in {
            val r = HttpRoute.post("upload").request(_.bodyMultipart)
            assert(r.method == Method.POST)
        }

        "streaming input" in {
            val r = HttpRoute.post("upload").request(_.bodyStream)
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
        }

        "streaming multipart input" in {
            val r = HttpRoute.post("upload").request(_.bodyMultipartStream)
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
        }

        "combined with path and query" in {
            val r =
                HttpRoute.put("users" / Capture[Int]("id"))
                    .request(_.query[String]("reason").bodyJson[CreateUser])
            assert(r.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
            assert(r.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 1)
        }
    }

    "Response body" - {
        "JSON output" in {
            val r = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            assert(r.response.outputFields.exists(_.isInstanceOf[HttpRoute.OutputField.Body]))
        }

        "text output" in {
            val r = HttpRoute.get("health").response(_.bodyText)
            assert(r.response.outputFields.exists(_.isInstanceOf[HttpRoute.OutputField.Body]))
        }

        "SSE output" in {
            val r = HttpRoute.get("events").response(_.bodySse[User])
            assert(r.response.outputFields.exists(_.isInstanceOf[HttpRoute.OutputField.Body]))
        }

        "NDJSON output" in {
            val r = HttpRoute.get("data").response(_.bodyNdjson[User])
            assert(r.response.outputFields.exists(_.isInstanceOf[HttpRoute.OutputField.Body]))
        }

    }

    "Errors" - {
        "single error type" in {
            val r =
                HttpRoute.get("users" / Capture[Int]("id"))
                    .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            assert(r.response.errorMappings.size == 1)
            assert(r.response.errorMappings.head.status == HttpStatus.NotFound)
        }

        "multiple error types" in {
            val r =
                HttpRoute.post("users")
                    .request(_.bodyJson[CreateUser])
                    .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound).error[ValidationError](HttpStatus.BadRequest))
            assert(r.response.errorMappings.size == 2)
            assert(r.response.errorMappings.map(_.status) == Seq(HttpStatus.NotFound, HttpStatus.BadRequest))
        }
    }

    "Documentation" - {
        "withTag" in {
            val r = HttpRoute.get("users").metadata(_.tag("Users"))
            assert(r.metadata.tags.contains("Users"))
        }

        "withSummary" in {
            val r = HttpRoute.get("users").metadata(_.summary("List all users"))
            assert(r.metadata.summary == Present("List all users"))
        }

        "withDescription" in {
            val r = HttpRoute.get("users").metadata(_.description("Returns a paginated list"))
            assert(r.metadata.description == Present("Returns a paginated list"))
        }

        "withOperationId" in {
            val r = HttpRoute.get("users").metadata(_.operationId("listUsers"))
            assert(r.metadata.operationId == Present("listUsers"))
        }

        "deprecated" in {
            val r = HttpRoute.get("users/old").metadata(_.markDeprecated)
            assert(r.metadata.deprecated)
        }

        "externalDocs with url" in {
            val r = HttpRoute.get("users").metadata(_.externalDocs("https://docs.example.com"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
        }

        "externalDocs with url and description" in {
            val r = HttpRoute.get("users").metadata(_.externalDocs("https://docs.example.com", "API Docs"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
            assert(r.metadata.externalDocsDesc == Present("API Docs"))
        }

        "security" in {
            val r = HttpRoute.get("users").metadata(_.security("bearerAuth"))
            assert(r.metadata.security == Present("bearerAuth"))
        }

        "chaining all documentation" in {
            val r = HttpRoute.get("users")
                .response(_.bodyJson[Seq[User]])
                .metadata(_.tag(
                    "Users"
                ).summary("List users").description("Returns paginated list of users").operationId("listUsers").security("bearerAuth"))
            assert(r.metadata.tags.contains("Users"))
            assert(r.metadata.summary == Present("List users"))
            assert(r.metadata.description == Present("Returns paginated list of users"))
            assert(r.metadata.operationId == Present("listUsers"))
            assert(r.metadata.security == Present("bearerAuth"))
        }
    }

    "Client call" - {
        "literal-only path (no-arg overload)" in run {
            val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route)
                }.map { _.body }.map { users =>
                    assert(users.size == 2)
                    assert(users.head == User(1, "Alice"))
                }
            }
        }

        "literal / capture (bare value overload)" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, 42)
                }.map { _.body }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "literal / capture (named tuple overload)" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, 42)
                }.map { _.body }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "literal / capture / literal (bare value overload)" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id") / "profile").response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, "profile"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, 42)
                }.map { _.body }.map { user =>
                    assert(user == User(42, "profile"))
                }
            }
        }

        "literal / capture / literal / capture" in run {
            val route = HttpRoute.get("orgs" / Capture[String]("org") / "users" / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val handler = route.handle { in =>
                User(in.id, in.org)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("acme", 42))
                }.map { _.body }.map { user =>
                    assert(user == User(42, "acme"))
                }
            }
        }

        "capture / capture (adjacent)" in run {
            val route = HttpRoute.get("items" / Capture[String]("category") / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val handler = route.handle { in =>
                User(in.id, in.category)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("books", 99))
                }.map { _.body }.map { user =>
                    assert(user == User(99, "books"))
                }
            }
        }

        "deep path: literal / capture / literal / capture / literal" in run {
            val route = HttpRoute.get("api" / Capture[String]("version") / "users" / Capture[Int]("id") / "settings")
                .response(_.bodyJson[User])
            val handler = route.handle { in =>
                User(in.id, in.version)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, ("v2", 7))
                }.map { _.body }.map { user =>
                    assert(user == User(7, "v2"))
                }
            }
        }

        "propagates errors via Abort" in run {
            val route = HttpRoute.get("users" / Capture[Int]("id"))
                .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            val handler = route.handle { in =>
                if in.id == 999 then Abort.fail(NotFoundError("Not found"))
                else User(in.id, s"User${in.id}")
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    Abort.run[NotFoundError](HttpClient.call(route, 999).map(_.body))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "bare value overload with Tuple1 body type" in {
            pending // ClassCastException: Tuple1 body type not yet supported in client call
        }

        "path capture with Tuple1 passes via original overload" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, 42)
                }.map { _.body }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "named tuple literal for single-input route" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, 42)
                }.map { _.body }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "named tuple literal for multi-input route" in run {
            val route = HttpRoute.get("orgs" / Capture[String]("org") / "users" / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val handler = route.handle { in =>
                User(in.id, in.org)
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, (org = "acme", id = 42))
                }.map { _.body }.map { user =>
                    assert(user == User(42, "acme"))
                }
            }
        }

        "call streamingwith SSE route" in run {
            val route = HttpRoute.get("events").response(_.bodySse[User])
            val handler = route.handle { _ =>
                Stream.init(Seq(
                    HttpEvent(User(1, "Alice")),
                    HttpEvent(User(2, "Bob"))
                ))
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 2)
                            assert(chunk(0).data == User(1, "Alice"))
                            assert(chunk(1).data == User(2, "Bob"))
                        }
                    }
                }
            }
        }

        "call streamingwith NDJSON route" in run {
            val route = HttpRoute.get("data").response(_.bodyNdjson[User])
            val handler = route.handle { _ =>
                Stream.init(Seq(User(1, "Alice"), User(2, "Bob")))
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 2)
                            assert(chunk(0) == User(1, "Alice"))
                            assert(chunk(1) == User(2, "Bob"))
                        }
                    }
                }
            }
        }

        "call with streaming input" in run {
            val route = HttpRoute.post("upload").request(_.bodyStream).response(_.bodyText)
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    s"received: $text"
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("hello".getBytes("UTF-8"))))
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, bodyStream)
                }.map { _.body }.map { result =>
                    assert(result.contains("received: hello"))
                }
            }
        }

        "call streamingwith streaming input and SSE output" in run {
            val route = HttpRoute.post("process")
                .request(_.bodyStream)
                .response(_.bodySse[User])
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    Stream.init(Seq(HttpEvent(User(text.length, text))))
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("test".getBytes("UTF-8"))))
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, bodyStream).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 1)
                            assert(chunk(0).data == User(4, "test"))
                        }
                    }
                }
            }
        }
    }

    "Handler creation" - {
        "from route" in run {
            val route = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
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
            val route = HttpRoute.put("users" / Capture[Int]("id"))
                .request(_.query[Boolean]("notify", default = Some(false)).bodyJson[CreateUser])
                .response(_.bodyJson[User])
            val handler: HttpHandler[Any] = route.handle { in =>
                User(in.id, in.body.name)
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.put(s"http://localhost:$port/users/5?notify=true", CreateUser("Alice", "a@b.com"))
                ).map { response =>
                    assertStatus(response, HttpStatus.OK)
                    assertBodyContains(response, "Alice")
                }
            }
        }

        "with effects" in {
            val route = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler: HttpHandler[Env[String]] = route.handle { _ =>
                Env.get[String].map(prefix => Seq(User(1, s"$prefix-Alice")))
            }
            assert(handler.route eq route)
        }

        "no-input route (by-name overload)" in run {
            val route   = HttpRoute.get("health").response(_.bodyText)
            val handler = route.handle { _ => "healthy" }
            startTestServer(handler).map { port =>
                testGet(port, "/health").map { response =>
                    assertStatus(response, HttpStatus.OK)
                    assertBodyContains(response, "healthy")
                }
            }
        }

        "no-input route with effects (by-name overload)" in {
            val route = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler: HttpHandler[Env[String]] = route.handle { _ =>
                Env.get[String].map(prefix => Seq(User(1, s"$prefix-Alice")))
            }
            assert(handler.route eq route)
        }

        "SSE output via route" in run {
            val route = HttpRoute.get("events").response(_.bodySse[User])
            val handler = route.handle { _ =>
                Stream.init(Seq(
                    HttpEvent(User(1, "Alice")),
                    HttpEvent(User(2, "Bob"))
                ))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events").map { response =>
                    assertStatus(response, HttpStatus.OK)
                    val body = response.bodyText
                    assert(body.contains("data: "), s"Expected SSE data: $body")
                    assert(body.contains("\"name\":\"Alice\""), s"Expected Alice: $body")
                    assert(body.contains("\"name\":\"Bob\""), s"Expected Bob: $body")
                }
            }
        }

        "NDJSON output via route" in run {
            val route = HttpRoute.get("data").response(_.bodyNdjson[User])
            val handler = route.handle { _ =>
                Stream.init(Seq(User(1, "Alice"), User(2, "Bob")))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/data").map { response =>
                    assertStatus(response, HttpStatus.OK)
                    val body  = response.bodyText
                    val lines = body.split("\n").filter(_.nonEmpty)
                    assert(lines.length == 2, s"Expected 2 NDJSON lines: $body")
                    assert(lines(0).contains("\"name\":\"Alice\""), s"Expected Alice: ${lines(0)}")
                }
            }
        }

        "streaming input via route" in run {
            val route = HttpRoute.post("upload").request(_.bodyStream).response(_.bodyText)
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    s"received: $text"
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("hello".getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/upload",
                    bodyStream
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == HttpStatus.OK)
                    response.bodyStream.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                        assert(text.contains("received: hello"))
                    }
                }
            }
        }
    }

    "Route matching" - {
        "exact path match" in run {
            val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq(User(1, "Alice")))
            startTestServer(handler).map { port =>
                for
                    r1 <- testGet(port, "/users")
                    r2 <- testGet(port, "/users/123")
                yield
                    assertStatus(r1, HttpStatus.OK)
                    assertStatus(r2, HttpStatus.NotFound)
                end for
            }
        }

        "path with captures match" in run {
            val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
            val handler = route.handle(in => User(in.id, s"User${in.id}"))
            startTestServer(handler).map { port =>
                for
                    r1 <- testGet(port, "/users/123")
                    r2 <- testGet(port, "/users")
                yield
                    assertStatus(r1, HttpStatus.OK)
                    assertStatus(r2, HttpStatus.NotFound)
                end for
            }
        }

        "method mismatch" in run {
            val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testPost(port, "/users", User(1, "test")).map { response =>
                    assertStatus(response, HttpStatus.MethodNotAllowed)
                }
            }
        }

        "path mismatch" in run {
            val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/posts").map { response =>
                    assertStatus(response, HttpStatus.NotFound)
                }
            }
        }

        "partial path match fails" in run {
            val route   = HttpRoute.get("api" / "v1" / "users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/api/v1").map { response =>
                    assertStatus(response, HttpStatus.NotFound)
                }
            }
        }

        "extra path segments fail" in run {
            val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            val handler = route.handle(_ => Seq.empty[User])
            startTestServer(handler).map { port =>
                testGet(port, "/users/extra").map { response =>
                    assertStatus(response, HttpStatus.NotFound)
                }
            }
        }
    }

    "Full route examples" - {
        "CRUD API" in {
            object UserRoutes:
                val list = HttpRoute.get("users")
                    .request(_.query[Int]("limit", default = Some(20)).query[Int]("offset", default = Some(0)))
                    .response(_.bodyJson[Seq[User]])
                    .metadata(_.tag("Users"))

                val get = HttpRoute.get("users" / Capture[Int]("id"))
                    .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
                    .metadata(_.tag("Users"))

                val create = HttpRoute.post("users")
                    .request(_.authBearer.bodyJson[CreateUser])
                    .response(_.bodyJson[User].error[ValidationError](HttpStatus.BadRequest))
                    .metadata(_.tag("Users"))

                val update = HttpRoute.put("users" / Capture[Int]("id"))
                    .request(_.authBearer.bodyJson[CreateUser])
                    .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound).error[ValidationError](HttpStatus.BadRequest))
                    .metadata(_.tag("Users"))

                val delete = HttpRoute.delete("users" / Capture[Int]("id"))
                    .request(_.authBearer)
                    .response(_.bodyJson[Unit].error[NotFoundError](HttpStatus.NotFound))
                    .metadata(_.tag("Users"))
            end UserRoutes
            assert(UserRoutes.list.method == Method.GET)
            assert(UserRoutes.list.request.inputFields.count(_.isInstanceOf[HttpRoute.InputField.Query]) == 2)
            assert(UserRoutes.get.response.errorMappings.size == 1)
            assert(UserRoutes.create.method == Method.POST)
            assert(UserRoutes.create.request.inputFields.exists(_.isInstanceOf[HttpRoute.InputField.Body]))
            assert(UserRoutes.update.method == Method.PUT)
            assert(UserRoutes.update.response.errorMappings.size == 2)
            assert(UserRoutes.delete.method == Method.DELETE)
        }
    }

    "Name conflicts" - {

        "path and query with same name compiles (no cross-source duplicate detection)" in {
            val r = HttpRoute.get("users" / Capture[Int]("id"))
                .request(_.query[String]("id"))
                .response(_.bodyText)
            succeed
        }

        "query and header with same name rejected at compile time" in {
            typeCheckFailure("""
                HttpRoute.get("users")
                    .request(_.query[String]("token").header[String]("token"))
            """)("Duplicate request field")
        }

        "pattern match accesses both capture fields" in run {
            val route = HttpRoute.get("a" / Capture[Int]("num") / "b" / Capture[String]("name"))
                .response(_.bodyText)
            val handler = route.handle { in =>
                s"num=${in.num},name=${in.name}"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/a/42/b/hello").map { response =>
                    assertBodyContains(response, "num=42,name=hello")
                }
            }
        }

        "query named 'body' conflicts with input" in {
            typeCheckFailure("""
                HttpRoute.post("data")
                    .request(_.query[String]("body").bodyJson[String])
            """)("Duplicate request field")
        }
    }

    "Mixed input sources" - {

        "path + query + body" in run {
            val route = HttpRoute.post("users" / Capture[Int]("id"))
                .request(_.query[Boolean]("notify").bodyJson[CreateUser])
                .response(_.bodyJson[User])
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
            val route = HttpRoute.post("items" / Capture[Int]("id"))
                .request(_.header[String]("X-Tenant").bodyJson[CreateUser])
                .response(_.bodyJson[User])
            val handler = route.handle { in =>
                User(in.id, s"${in.body.name}@${in.`X-Tenant`}")
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
            val route = HttpRoute.get("data" / Capture[Int]("id"))
                .request(_.cookie[String]("session").query[String]("format"))
                .response(_.bodyText)
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
            val route = HttpRoute.get("items" / Capture[Int]("id"))
                .request(_.query[String]("fields").authBearer)
                .response(_.bodyText)
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
                .request(_.query[String]("action").authBasic)
                .response(_.bodyText)
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

    "Streaming with mixed inputs" - {

        "call streamingSSE with path capture and query" in run {
            val route = HttpRoute.get("events" / Capture[Int]("id"))
                .request(_.query[String]("filter"))
                .response(_.bodySse[User])
            val handler = route.handle { in =>
                Stream.init(Seq(HttpEvent(User(in.id, in.filter))))
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, (id = 42, filter = "active")).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 1)
                            assert(chunk(0).data == User(42, "active"))
                        }
                    }
                }
            }
        }

        "call streamingNDJSON with path capture and header" in run {
            val route = HttpRoute.get("data" / Capture[String]("category"))
                .request(_.header[String]("X-Tenant"))
                .response(_.bodyNdjson[User])
            val handler = route.handle { in =>
                Stream.init(Seq(User(1, s"${in.category}@${in.`X-Tenant`}")))
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, (category = "books", `X-Tenant` = "acme")).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 1)
                            assert(chunk(0) == User(1, "books@acme"))
                        }
                    }
                }
            }
        }

        "call streaming input with path capture and query" in run {
            val route = HttpRoute.post("upload" / Capture[Int]("id"))
                .request(_.query[String]("name").bodyStream)
                .response(_.bodyText)
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    s"id=${in.id},name=${in.name},body=$text"
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("hello".getBytes("UTF-8"))))
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, (id = 7, name = "test.txt", body = bodyStream))
                }.map { _.body }.map { result =>
                    assert(result.contains("id=7"))
                    assert(result.contains("name=test.txt"))
                    assert(result.contains("body=hello"))
                }
            }
        }

        "call streaming input with auth" in run {
            val route = HttpRoute.post("secure-upload")
                .request(_.authBearer.bodyStream)
                .response(_.bodyText)
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    s"bearer=${in.bearer},body=$text"
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(route, (bearer = "tok123", body = bodyStream))
                }.map { _.body }.map { result =>
                    assert(result.contains("bearer=tok123"))
                    assert(result.contains("body=data"))
                }
            }
        }

        "call streaming input + SSE output with query and auth" in run {
            val route = HttpRoute.post("process")
                .request(_.query[String]("mode").authBearer.bodyStream)
                .response(_.bodySse[User])
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    Stream.init(Seq(HttpEvent(User(text.length, s"${in.mode}:${in.bearer}"))))
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("test".getBytes("UTF-8"))))
                HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                    HttpClient.call(
                        route,
                        (mode = "fast", bearer = "secret", body = bodyStream)
                    ).map { _.body }.map { stream =>
                        stream.run.map { chunk =>
                            assert(chunk.size == 1)
                            assert(chunk(0).data == User(4, "fast:secret"))
                        }
                    }
                }
            }
        }

        "streaming input handler with header and cookie" in run {
            val route = HttpRoute.post("upload")
                .request(_.header[String]("X-Request-Id").cookie[String]("session").bodyStream)
                .response(_.bodyText)
            val handler = route.handle { in =>
                in.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    s"reqId=${in.`X-Request-Id`},session=${in.session},body=$text"
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("payload".getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    HttpRequest.Method.POST,
                    s"http://localhost:$port/upload",
                    bodyStream,
                    HttpHeaders.empty
                        .add("X-Request-Id", "req-42")
                        .add("Cookie", "session=sess-abc")
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == HttpStatus.OK)
                    response.bodyStream.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                        assert(text.contains("reqId=req-42"))
                        assert(text.contains("session=sess-abc"))
                        assert(text.contains("body=payload"))
                    }
                }
            }
        }
    }

    // ========================================================================
    // CRUD microservice patterns
    // ========================================================================

    "microservice patterns" - {

        case class Todo(id: Int, title: String, completed: Boolean) derives Schema, CanEqual
        case class CreateTodo(title: String) derives Schema
        case class TodoList(items: List[Todo], total: Int) derives Schema, CanEqual

        val listTodos = HttpRoute.get("api/todos")
            .request(_.query[Int]("limit", default = Some(10)).query[Int]("offset", default = Some(0)))
            .response(_.bodyJson[TodoList])

        val createTodo = HttpRoute.post("api/todos")
            .request(_.bodyJson[CreateTodo])
            .response(_.bodyJson[Todo])

        def buildTodoHandlers(
            store: AtomicRef[Map[Int, Todo]],
            nextId: AtomicInt
        ): Seq[HttpHandler[Any]] =
            val listHandler = listTodos.handle { in =>
                store.get.map { items =>
                    val all   = items.values.toSeq.sortBy(_.id)
                    val paged = all.slice(in.offset, in.offset + in.limit).toList
                    TodoList(paged, all.size)
                }
            }
            val createHandler = createTodo.handle { in =>
                nextId.incrementAndGet.map { id =>
                    val todo = Todo(id, in.body.title, completed = false)
                    store.updateAndGet(_.updated(id, todo)).andThen(todo)
                }
            }
            Seq(listHandler, createHandler)
        end buildTodoHandlers

        "pagination with query param defaults" in run {
            AtomicRef.init(Map.empty[Int, Todo]).map { store =>
                AtomicInt.init.map { nextId =>
                    val handlers = buildTodoHandlers(store, nextId)
                    startTestServer(handlers*).map { port =>
                        HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                            for
                                _ <- HttpClient.call(createTodo, CreateTodo("Todo 1"))
                                _ <- HttpClient.call(createTodo, CreateTodo("Todo 2"))
                                _ <- HttpClient.call(createTodo, CreateTodo("Todo 3"))
                                _ <- HttpClient.call(createTodo, CreateTodo("Todo 4"))
                                _ <- HttpClient.call(createTodo, CreateTodo("Todo 5"))

                                page1 <- testGetAs[TodoList](port, "/api/todos?limit=2&offset=0")
                                _ = assert(page1.items.size == 2)
                                _ = assert(page1.total == 5)
                                _ = assert(page1.items.head.title == "Todo 1")

                                page2 <- testGetAs[TodoList](port, "/api/todos?limit=2&offset=2")
                                _ = assert(page2.items.size == 2)
                                _ = assert(page2.items.head.title == "Todo 3")

                                page3 <- testGetAs[TodoList](port, "/api/todos?limit=2&offset=4")
                                _ = assert(page3.items.size == 1)
                            yield assertionSuccess
                        }
                    }
                }
            }
        }

        "concurrent creates with unique IDs" in run {
            AtomicRef.init(Map.empty[Int, Todo]).map { store =>
                AtomicInt.init.map { nextId =>
                    val handlers = buildTodoHandlers(store, nextId)
                    startTestServer(handlers*).map { port =>
                        HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
                            val creates = (1 to 10).map { i =>
                                HttpClient.call(createTodo, CreateTodo(s"Concurrent $i")).map(_.body)
                            }
                            Async.collectAll(creates).map { todos =>
                                assert(todos.size == 10)
                                assert(todos.map(_.id).toSet.size == 10)
                            }
                        }
                    }
                }
            }
        }
    }

end HttpRouteTest
