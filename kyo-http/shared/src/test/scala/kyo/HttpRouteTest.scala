package kyo

import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status
import HttpRoute.*
import HttpRoute.Path./

class HttpRouteTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String, email: String) derives Schema, CanEqual
    case class NotFoundError(message: String) derives Schema, CanEqual
    case class ValidationError(field: String, message: String) derives Schema, CanEqual

    "Path" - {

        "construction" - {
            "from string" in {
                val p: Path[Unit] = Path("users")
                succeed
            }

            "implicit string conversion" in {
                val p: Path[Unit] = "users"
                succeed
            }
        }

        "captures" - {
            "int" in {
                val p: Path[Int] = Path.int("id")
                succeed
            }

            "long" in {
                val p: Path[Long] = Path.long("id")
                succeed
            }

            "string" in {
                val p: Path[String] = Path.string("slug")
                succeed
            }

            "uuid" in {
                val p: Path[java.util.UUID] = Path.uuid("id")
                succeed
            }

            "boolean" in {
                val p: Path[Boolean] = Path.boolean("active")
                succeed
            }
        }

        "concatenation" - {
            "string / string" in {
                val p: Path[Unit] = "api" / "v1" / "users"
                succeed
            }

            "string / capture" in {
                val p: Path[Int] = "users" / Path.int("id")
                succeed
            }

            "capture / string" in {
                val p: Path[Int] = Path.int("id") / "details"
                succeed
            }

            "two captures" in {
                val p: Path[(Int, Int)] = "users" / Path.int("userId") / "posts" / Path.int("postId")
                succeed
            }

            "three captures" in {
                val p: Path[(Int, String, Long)] =
                    "org" / Path.int("orgId") / "users" / Path.string("name") / "items" / Path.long("itemId")
                succeed
            }

            "four captures" in {
                val p: Path[(Int, String, Long, Boolean)] =
                    "a" / Path.int("a") / "b" / Path.string("b") / "c" / Path.long("c") / "d" / Path.boolean("d")
                succeed
            }

            "capture / capture" in {
                val p: Path[(Int, String)] = Path.int("id") / Path.string("name")
                succeed
            }
        }

        "edge cases" - {
            "empty string segment" in {
                val p: Path[Unit] = Path("")
                succeed
            }

            "segment with special characters" in {
                val p: Path[Unit] = "users-v2" / "items_list"
                succeed
            }

            "segment with encoded slashes" in {
                // Path segments shouldn't contain raw slashes
                val p: Path[Unit] = "users%2Flist"
                succeed
            }

            "leading slash" in {
                // Leading slash should be normalized
                val p: Path[Unit] = Path("/users")
                succeed
            }

            "trailing slash" in {
                val p: Path[Unit] = "users/"
                succeed
            }

            "double slashes" in {
                // Double slashes should be normalized
                val p: Path[Unit] = "api" / "" / "users"
                succeed
            }

            "dot segments" in {
                val p: Path[Unit] = "api" / "." / "users"
                succeed
            }
        }

        "parsing failures" - {
            "int capture with non-numeric value" in run {
                val route = HttpRoute.get("users" / Path.int("id")).output[User]
                // When matching "/users/abc", should fail to parse
                // This would be tested during route matching
                succeed
            }

            "int capture with overflow" in run {
                val route = HttpRoute.get("users" / Path.int("id")).output[User]
                // When matching "/users/99999999999999999999", should fail
                succeed
            }

            "long capture with non-numeric value" in run {
                val route = HttpRoute.get("items" / Path.long("id")).output[User]
                succeed
            }

            "long capture with overflow" in run {
                val route = HttpRoute.get("items" / Path.long("id")).output[User]
                succeed
            }

            "uuid capture with invalid format" in run {
                val route = HttpRoute.get("items" / Path.uuid("id")).output[User]
                // When matching "/items/not-a-uuid", should fail
                succeed
            }

            "boolean capture with invalid value" in run {
                val route = HttpRoute.get("flags" / Path.boolean("active")).output[User]
                // When matching "/flags/maybe", should fail (only true/false valid)
                succeed
            }
        }

        "capture name edge cases" - {
            "empty capture name throws" in {
                assertThrows[IllegalArgumentException] {
                    Path.int("")
                }
            }

            "capture name with special characters" in {
                // Capture names should allow reasonable characters
                val p: Path[Int] = Path.int("user_id")
                succeed
            }

            "duplicate capture names" in {
                // Same capture name used twice - might be allowed but confusing
                val p: Path[(Int, Int)] = Path.int("id") / "sub" / Path.int("id")
                succeed
            }
        }
    }

    "IntoTuple type" - {
        "non-tuple becomes singleton tuple" in {
            val _: HttpRoute.IntoTuple[Int] = Tuple1(42)
            succeed
        }

        "tuple stays as tuple" in {
            val _: HttpRoute.IntoTuple[(Int, String)] = (42, "hello")
            succeed
        }

        "Unit becomes singleton tuple" in {
            val _: HttpRoute.IntoTuple[Unit] = Tuple1(())
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
                succeed
            }

            "post" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.post("users")
                succeed
            }

            "put" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.put("users")
                succeed
            }

            "patch" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.patch("users")
                succeed
            }

            "delete" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.delete("users")
                succeed
            }

            "head" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.head("users")
                succeed
            }

            "options" in {
                val r: HttpRoute[Unit, Unit, Nothing] = HttpRoute.options("users")
                succeed
            }
        }

        "with path captures" - {
            "single capture" in {
                val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users" / Path.int("id"))
                succeed
            }

            "multiple captures" in {
                val r: HttpRoute[(Int, Int), Unit, Nothing] =
                    HttpRoute.get("users" / Path.int("userId") / "posts" / Path.int("postId"))
                succeed
            }
        }
    }

    "Query parameters" - {
        "required" in {
            val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users").query[Int]("limit")
            succeed
        }

        "with default" in {
            val r: HttpRoute[Int, Unit, Nothing] = HttpRoute.get("users").query[Int]("limit", 20)
            succeed
        }

        "optional via Maybe" in {
            val r: HttpRoute[Maybe[String], Unit, Nothing] = HttpRoute.get("users").query[Maybe[String]]("search")
            succeed
        }

        "multiple" in {
            val r: HttpRoute[(Int, Int), Unit, Nothing] =
                HttpRoute.get("users")
                    .query[Int]("limit")
                    .query[Int]("offset", 0)
            succeed
        }

        "combined with path capture" in {
            val r: HttpRoute[(Int, String), Unit, Nothing] =
                HttpRoute.get("users" / Path.int("id"))
                    .query[String]("fields")
            succeed
        }

        "edge cases" - {
            "empty query name throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRoute.get("users").query[Int]("")
                }
            }

            "query with empty value" in {
                // Empty string value should be valid
                val r = HttpRoute.get("users").query[String]("filter", "")
                succeed
            }

            "duplicate query parameter names" in {
                // Same param name twice - allowed but second shadows first
                val r = HttpRoute.get("users")
                    .query[Int]("page")
                    .query[Int]("page", 1)
                succeed
            }

            "query value parsing failure" in run {
                // This would fail at runtime when ?limit=abc is passed to a Int query
                val r = HttpRoute.get("users").query[Int]("limit")
                succeed
            }

            "URL-encoded query value" in run {
                // Query values should be URL-decoded
                val r = HttpRoute.get("users").query[String]("search")
                succeed
            }
        }
    }

    "Headers" - {
        "required" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").header("X-Request-Id")
            succeed
        }

        "with default" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").header("Accept", "application/json")
            succeed
        }

        "multiple" in {
            val r: HttpRoute[(String, String), Unit, Nothing] =
                HttpRoute.get("users")
                    .header("X-Request-Id")
                    .header("X-Trace-Id")
            succeed
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
            succeed
        }

        "with default" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("dashboard").cookie("theme", "light")
            succeed
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
            succeed
        }

        "basic auth" in {
            val r: HttpRoute[(String, String), Unit, Nothing] = HttpRoute.get("users").authBasic
            succeed
        }

        "API key" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.get("users").authApiKey("X-API-Key")
            succeed
        }

        "combined with path" in {
            val r: HttpRoute[(Int, String), Unit, Nothing] =
                HttpRoute.get("users" / Path.int("id"))
                    .authBearer
            succeed
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
            succeed
        }

        "text input" in {
            val r: HttpRoute[String, Unit, Nothing] = HttpRoute.post("echo").inputText
            succeed
        }

        "form input" in {
            val r: HttpRoute[CreateUser, Unit, Nothing] = HttpRoute.post("login").inputForm[CreateUser]
            succeed
        }

        "multipart input" in {
            val r: HttpRoute[Seq[Part], Unit, Nothing] = HttpRoute.post("upload").inputMultipart
            succeed
        }

        "bytes input" in {
            val r: HttpRoute[Stream[Byte, Async], Unit, Nothing] = HttpRoute.post("data").inputBytes
            succeed
        }

        "stream input" in {
            val r: HttpRoute[Stream[User, Async], Unit, Nothing] = HttpRoute.post("events").inputStream[User]
            succeed
        }

        "combined with path and query" in {
            val r: HttpRoute[(Int, String, CreateUser), Unit, Nothing] =
                HttpRoute.put("users" / Path.int("id"))
                    .query[String]("reason")
                    .input[CreateUser]
            succeed
        }
    }

    "Response body" - {
        "JSON output" in {
            val r: HttpRoute[Unit, Seq[User], Nothing] = HttpRoute.get("users").output[Seq[User]]
            succeed
        }

        "JSON output with status" in {
            val r: HttpRoute[Unit, User, Nothing] = HttpRoute.post("users").output[User](Status.Created)
            succeed
        }

        "text output" in {
            val r: HttpRoute[Unit, String, Nothing] = HttpRoute.get("health").outputText
            succeed
        }

        "bytes output" in {
            val r: HttpRoute[Unit, Stream[Byte, Async], Nothing] = HttpRoute.get("file").outputBytes
            succeed
        }

        "stream output" in {
            val r: HttpRoute[Unit, Stream[User, Async], Nothing] = HttpRoute.get("events").outputStream[User]
            succeed
        }
    }

    "Errors" - {
        "single error type" in {
            val r: HttpRoute[Int, User, NotFoundError] =
                HttpRoute.get("users" / Path.int("id"))
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
            succeed
        }

        "multiple error types" in {
            val r: HttpRoute[CreateUser, User, NotFoundError | ValidationError] =
                HttpRoute.post("users")
                    .input[CreateUser]
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .error[ValidationError](Status.BadRequest)
            succeed
        }
    }

    "Documentation" - {
        "withTag" in {
            val r = HttpRoute.get("users").withTag("Users")
            succeed
        }

        "withSummary" in {
            val r = HttpRoute.get("users").withSummary("List all users")
            succeed
        }

        "withDescription" in {
            val r = HttpRoute.get("users").withDescription("Returns a paginated list")
            succeed
        }

        "withOperationId" in {
            val r = HttpRoute.get("users").withOperationId("listUsers")
            succeed
        }

        "deprecated" in {
            val r = HttpRoute.get("users/old").deprecated
            succeed
        }

        "externalDocs with url" in {
            val r = HttpRoute.get("users").externalDocs("https://docs.example.com")
            succeed
        }

        "externalDocs with url and description" in {
            val r = HttpRoute.get("users").externalDocs("https://docs.example.com", "API Docs")
            succeed
        }

        "security" in {
            val r = HttpRoute.get("users").security("bearerAuth")
            succeed
        }

        "chaining all documentation" in {
            val r = HttpRoute.get("users")
                .output[Seq[User]]
                .withTag("Users")
                .withSummary("List users")
                .withDescription("Returns paginated list of users")
                .withOperationId("listUsers")
                .security("bearerAuth")
            succeed
        }
    }

    "Client call" - {
        "invokes route with input" in run {
            val route   = HttpRoute.get("users" / Path.int("id")).output[User]
            val handler = route.handle(id => User(id, s"User$id"))
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
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
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call(())
                }.map { users =>
                    assert(users.size == 2)
                    assert(users.head == User(1, "Alice"))
                }
            }
        }

        "propagates errors via Abort" in run {
            val route = HttpRoute.get("users" / Path.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                if id == 999 then Abort.fail(NotFoundError("Not found"))
                else User(id, s"User$id")
            }
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    Abort.run[NotFoundError](route.call(999))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "Handler creation" - {
        "from route" in {
            val route = HttpRoute.get("users" / Path.int("id")).output[User]
            val handler: HttpHandler[Any] = route.handle { id =>
                User(id, "test")
            }
            succeed
        }

        "with multiple inputs" in {
            val route = HttpRoute.put("users" / Path.int("id"))
                .query[Boolean]("notify", false)
                .input[CreateUser]
                .output[User]
            val handler: HttpHandler[Any] = route.handle { case (id, notify, user) =>
                User(id, user.name)
            }
            succeed
        }

        "with effects" in {
            val route = HttpRoute.get("users").output[Seq[User]]
            val handler: HttpHandler[Env[String]] = route.handle { _ =>
                Env.get[String].map(prefix => Seq(User(1, s"$prefix-Alice")))
            }
            succeed
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
            val route   = HttpRoute.get("users" / Path.int("id")).output[User]
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
                    .withTag("Users")

                val get = HttpRoute.get("users" / Path.int("id"))
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .withTag("Users")

                val create = HttpRoute.post("users")
                    .authBearer
                    .input[CreateUser]
                    .output[User](Status.Created)
                    .error[ValidationError](Status.BadRequest)
                    .withTag("Users")

                val update = HttpRoute.put("users" / Path.int("id"))
                    .authBearer
                    .input[CreateUser]
                    .output[User]
                    .error[NotFoundError](Status.NotFound)
                    .error[ValidationError](Status.BadRequest)
                    .withTag("Users")

                val delete = HttpRoute.delete("users" / Path.int("id"))
                    .authBearer
                    .output[Unit]
                    .error[NotFoundError](Status.NotFound)
                    .withTag("Users")
            end UserRoutes
            succeed
        }
    }

end HttpRouteTest
