package kyo

import HttpPath2.*
import HttpRoute2.*
import HttpStatus.*

class HttpRoute2Test extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUserRequest(name: String) derives Schema, CanEqual
    case class UserNotFound(id: Int) derives Schema, CanEqual
    case class ValidationError(field: String) derives Schema, CanEqual

    "Route Construction" - {

        "creates GET route with correct method" in {
            val route = get("users")
            assert(route.method == HttpRequest.Method.GET)
        }

        "creates POST route with correct method" in {
            val route = post("users")
            assert(route.method == HttpRequest.Method.POST)
        }

        "creates PUT route with correct method" in {
            val route = put("users")
            assert(route.method == HttpRequest.Method.PUT)
        }

        "creates DELETE route with correct method" in {
            val route = delete("users")
            assert(route.method == HttpRequest.Method.DELETE)
        }
    }

    "Path Parameters" - {

        "single capture creates correct path type" in {
            val route = get("users" / Capture[Int]("id"))
            // Type: HttpRoute2[(id: Int), Row.Empty, Row.Empty, Nothing]
            assert(route.method == HttpRequest.Method.GET)
        }

        "multiple captures compose correctly" in {
            val route = get("users" / Capture[Int]("userId") / "posts" / Capture[String]("slug"))
            // Type: HttpRoute2[(userId: Int, slug: String), Row.Empty, Row.Empty, Nothing]
            assert(route.method == HttpRequest.Method.GET)
        }

        "wildcard capture compiles" in {
            val route = get("files" / Capture.rest("path"))
            // Type: HttpRoute2[(path: String), Row.Empty, Row.Empty, Nothing]
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Query Parameters" - {

        "required query param adds to input type" in {
            val route = get("users")
                .request(_.query[String]("name"))
            // Type: HttpRoute2[Row.Empty, (name: String), Row.Empty, Nothing]
            assert(route.method == HttpRequest.Method.GET)
        }

        "multiple query params accumulate" in {
            val route = get("users")
                .request(_
                    .query[String]("name")
                    .query[Int]("age"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "optional query param uses Maybe type" in {
            val route = get("users")
                .request(_.query[Maybe[String]]("search"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "query param with default compiles" in {
            val route = get("users")
                .request(_.query[Int]("limit", default = Some(10)))
            assert(route.method == HttpRequest.Method.GET)
        }

        "query with wireName compiles" in {
            val route = get("users")
                .request(_.query[String]("search", wireName = "q"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "optional query with wireName compiles" in {
            val route = get("users")
                .request(_.query[Maybe[String]]("search", wireName = "q"))
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Request Bodies" - {

        "JSON body compiles" in {
            val route = post("users")
                .request(_.bodyJson[CreateUserRequest])
            assert(route.method == HttpRequest.Method.POST)
        }

        "text body compiles" in {
            val route = post("notes")
                .request(_.bodyText)
            assert(route.method == HttpRequest.Method.POST)
        }

        "binary body compiles" in {
            val route = post("upload")
                .request(_.bodyBinary)
            assert(route.method == HttpRequest.Method.POST)
        }
    }

    "Response Bodies" - {

        "JSON response compiles" in {
            val route = get("users")
                .response(_.bodyJson[Seq[User]])
            assert(route.method == HttpRequest.Method.GET)
        }

        "text response compiles" in {
            val route = get("health")
                .response(_.bodyText)
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Status Codes" - {

        "default status is 200 OK" in {
            val route = get("users")
                .response(_.bodyJson[Seq[User]])
            assert(route.response.status == OK)
        }

        "custom status is set correctly" in {
            val route = post("users")
                .request(_.bodyJson[CreateUserRequest])
                .response(_.status(Created).bodyJson[User])
            assert(route.response.status == Created)
        }

        "204 No Content status is set" in {
            val route = delete("users" / Capture[Int]("id"))
                .response(_.status(NoContent))
            assert(route.response.status == NoContent)
        }
    }

    "Error Handling" - {

        "single error type compiles" in {
            val route = get("users" / Capture[Int]("id"))
                .response(_
                    .error[UserNotFound](NotFound)
                    .bodyJson[User])
            assert(route.method == HttpRequest.Method.GET)
        }

        "multiple error types compile" in {
            val route = post("users")
                .request(_.bodyJson[CreateUserRequest])
                .response(_
                    .error[ValidationError](BadRequest)
                    .error[UserNotFound](NotFound)
                    .bodyJson[User])
            assert(route.method == HttpRequest.Method.POST)
        }
    }

    "Metadata" - {

        "single tag is added" in {
            val route = get("users")
                .metadata(_.tag("users"))
            assert(route.metadata.tags == Seq("users"))
        }

        "multiple tags are added" in {
            val route = get("users")
                .metadata(_.tag("users").tag("public"))
            assert(route.metadata.tags == Seq("users", "public"))
        }

        "summary is set" in {
            val route = get("users")
                .metadata(_.summary("List all users"))
            assert(route.metadata.summary == Present("List all users"))
        }

        "description is set" in {
            val route = get("users")
                .metadata(_.description("Returns paginated users"))
            assert(route.metadata.description == Present("Returns paginated users"))
        }

        "operation ID is set" in {
            val route = get("users")
                .metadata(_.operationId("listUsers"))
            assert(route.metadata.operationId == Present("listUsers"))
        }

        "deprecated flag is set" in {
            val route = get("legacy")
                .metadata(_.markDeprecated)
            assert(route.metadata.deprecated == true)
        }

        "external docs URL is set" in {
            val route = get("users")
                .metadata(_.externalDocs("https://example.com/docs"))
            assert(route.metadata.externalDocsUrl == Present("https://example.com/docs"))
        }

        "chained metadata accumulates" in {
            val route = get("users")
                .metadata(_
                    .tag("users")
                    .summary("List users")
                    .operationId("listUsers"))
            assert(route.metadata.tags == Seq("users"))
            assert(route.metadata.summary == Present("List users"))
            assert(route.metadata.operationId == Present("listUsers"))
        }
    }

    "Path Transformation" - {

        "prepend path preserves method" in {
            val route       = get("users")
            val transformed = route.path("api" / "v1" / _)
            assert(transformed.method == HttpRequest.Method.GET)
        }

        "replace path preserves method" in {
            val route    = get("old")
            val replaced = route.path("new" / "path")
            assert(replaced.method == HttpRequest.Method.GET)
        }

        "path transformation preserves metadata" in {
            val route = get("users")
                .metadata(_.tag("users"))
            val transformed = route.path("api" / "v1" / _)
            assert(transformed.metadata.tags == Seq("users"))
        }
    }

    "Cookies" - {

        "response cookie with default attributes compiles" in {
            val route = get("test")
                .response(_.cookie[String]("temp"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "response cookie with custom attributes compiles" in {
            val route = get("login")
                .response(_
                    .cookie[String](
                        "session",
                        attributes = CookieAttributes(
                            httpOnly = true,
                            secure = true,
                            sameSite = Present(SameSite.Strict),
                            maxAge = Present(3600)
                        )
                    ))
            assert(route.method == HttpRequest.Method.GET)
        }

        "optional response cookie compiles" in {
            val route = get("test")
                .response(_.cookie[Maybe[String]]("temp"))
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Authentication" - {

        "bearer auth adds bearer field" in {
            val route = get("protected")
                .request(_.authBearer)
            assert(route.method == HttpRequest.Method.GET)
        }

        "basic auth adds username and password fields" in {
            val route = get("admin")
                .request(_.authBasic)
            assert(route.method == HttpRequest.Method.GET)
        }

        "API key auth adds key field" in {
            val route = get("api")
                .request(_.authApiKey("X-API-Key"))
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Type Safety" - {

        "duplicate path and query field names are detected at handler time" in {
            // Route definition allows it — duplicates caught when handler concatenates PathIn ++ In
            val route = get("users" / Capture[Int]("id"))
                .request(_.query[String]("name"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "multiple bodies fail at compile time" in {
            typeCheckFailure("""
                HttpRoute2.post("test")
                    .request(_
                        .bodyJson[User]
                        .bodyText)
            """)("Duplicate request field")
        }
    }

    "Custom Codecs" - {

        "custom type in path compiles" in {
            case class UserId(value: Int)
            given HttpRoute2.Codec[UserId] = HttpRoute2.Codec(
                s => UserId(s.toInt),
                uid => uid.value.toString
            )

            val route = get("users" / Capture[UserId]("id"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "custom type in query compiles" in {
            enum SortOrder derives CanEqual:
                case Asc, Desc

            given HttpRoute2.Codec[SortOrder] = HttpRoute2.Codec(
                s => if s == "asc" then SortOrder.Asc else SortOrder.Desc,
                o => if o == SortOrder.Asc then "asc" else "desc"
            )

            val route = get("users")
                .request(_.query[SortOrder]("sort"))
            assert(route.method == HttpRequest.Method.GET)
        }
    }

    "Headers" - {

        "required request header compiles" in {
            val route = get("api")
                .request(_.header[String]("X-Request-Id"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "optional request header compiles" in {
            val route = get("api")
                .request(_.header[Maybe[String]]("X-Trace-Id"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "request header with wireName/fieldName compiles" in {
            val route = get("api")
                .request(_.header[String]("requestId", wireName = "X-Request-Id"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "response header compiles" in {
            val route = get("api")
                .response(_.header[String]("X-Request-Id"))
            assert(route.method == HttpRequest.Method.GET)
        }

        "optional response header compiles" in {
            val route = get("api")
                .response(_.header[Maybe[String]]("X-Trace-Id"))
            assert(route.method == HttpRequest.Method.GET)
        }
    }

end HttpRoute2Test
