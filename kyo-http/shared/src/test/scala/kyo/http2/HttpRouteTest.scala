package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Record.~
import kyo.Test
import scala.language.implicitConversions

class HttpRouteTest extends Test:

    import HttpPath.*
    import HttpRoute.*

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String, email: String) derives Schema, CanEqual
    case class NotFoundError(message: String) derives Schema, CanEqual
    case class ValidationError(field: String, message: String) derives Schema, CanEqual

    "Path" - {

        "capture defaults wireName to fieldName" in {
            val p = HttpPath.Capture[Int]("id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "id")
                    assert(wn == "id")
                case _ => fail("Expected Capture")
            end match
        }

        "capture with custom wireName" in {
            val p = HttpPath.Capture[Int]("id", "user_id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "id")
                    assert(wn == "user_id")
                case _ => fail("Expected Capture")
            end match
        }

        "rest extracts fieldName" in {
            val p = HttpPath.Rest("path")
            p match
                case HttpPath.Rest(fn) => assert(fn == "path")
                case _                 => fail("Expected Rest")
            end match
        }

        "capture types" - {
            "int" in {
                val p = HttpPath.Capture[Int]("id")
                typeCheck("""val _: HttpPath["id" ~ Int] = p""")
            }

            "long" in {
                val p = HttpPath.Capture[Long]("id")
                typeCheck("""val _: HttpPath["id" ~ Long] = p""")
            }

            "string" in {
                val p = HttpPath.Capture[String]("slug")
                typeCheck("""val _: HttpPath["slug" ~ String] = p""")
            }

            "boolean" in {
                val p = HttpPath.Capture[Boolean]("active")
                typeCheck("""val _: HttpPath["active" ~ Boolean] = p""")
            }

            "double" in {
                val p = HttpPath.Capture[Double]("score")
                typeCheck("""val _: HttpPath["score" ~ Double] = p""")
            }

            "float" in {
                val p = HttpPath.Capture[Float]("weight")
                typeCheck("""val _: HttpPath["weight" ~ Float] = p""")
            }

            "uuid" in {
                val p = HttpPath.Capture[java.util.UUID]("id")
                typeCheck("""val _: HttpPath["id" ~ java.util.UUID] = p""")
            }
        }

        "concatenation" - {
            "string / string" in {
                val p = "api" / "v1" / "users"
                typeCheck("""val _: HttpPath[Any] = p""")
            }

            "string / capture" in {
                val p = "users" / HttpPath.Capture[Int]("id")
                typeCheck("""val _: HttpPath["id" ~ Int] = p""")
            }

            "capture / string" in {
                val p = HttpPath.Capture[Int]("id") / "details"
                typeCheck("""val _: HttpPath["id" ~ Int] = p""")
            }

            "two captures" in {
                val p = "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId")
                typeCheck("""val _: HttpPath["userId" ~ Int & "postId" ~ Int] = p""")
            }

            "three captures" in {
                val p = "org" / HttpPath.Capture[Int]("orgId") / "users" / HttpPath.Capture[String](
                    "name"
                ) / "items" / HttpPath.Capture[Long]("itemId")
                typeCheck("""val _: HttpPath["orgId" ~ Int & "name" ~ String & "itemId" ~ Long] = p""")
            }

            "four captures" in {
                val p = "a" / HttpPath.Capture[Int]("a") / "b" / HttpPath.Capture[String]("b") / "c" / HttpPath.Capture[Long](
                    "c"
                ) / "d" / HttpPath.Capture[Boolean]("d")
                typeCheck("""val _: HttpPath["a" ~ Int & "b" ~ String & "c" ~ Long & "d" ~ Boolean] = p""")
            }

            "capture / capture (adjacent)" in {
                val p = HttpPath.Capture[Int]("id") / HttpPath.Capture[String]("name")
                typeCheck("""val _: HttpPath["id" ~ Int & "name" ~ String] = p""")
            }

            "capture / rest" in {
                val p = "items" / HttpPath.Capture[Int]("id") / HttpPath.Rest("rest")
                typeCheck("""val _: HttpPath["id" ~ Int & "rest" ~ String] = p""")
            }
        }

        "type safety" - {
            "capture tracks field type" in {
                val p = HttpPath.Capture[Int]("id")
                typeCheck("""val _: HttpPath["id" ~ Int] = p""")
            }

            "concat tracks intersection" in {
                val p = HttpPath.Concat(HttpPath.Capture[Int]("a"), HttpPath.Capture[String]("b"))
                typeCheck("""val _: HttpPath["a" ~ Int & "b" ~ String] = p""")
            }

            "rest tracks String type" in {
                val p = HttpPath.Rest("remainder")
                typeCheck("""val _: HttpPath["remainder" ~ String] = p""")
            }
        }

        "edge cases" - {
            "empty string segment" in {
                val p = HttpPath.Literal("")
                p match
                    case HttpPath.Literal(v) => assert(v == "")
                    case _                   => fail("Expected Literal")
                end match
            }

            "segment with special characters" in {
                val p = "users-v2" / "items_list"
                p match
                    case HttpPath.Concat(HttpPath.Literal(l), HttpPath.Literal(r)) =>
                        assert(l == "users-v2")
                        assert(r == "items_list")
                    case _ => fail("Expected Concat of two Literals")
                end match
            }

            "empty capture name compiles" in {
                val p = HttpPath.Capture[Int]("")
                p match
                    case HttpPath.Capture(fn, _, _) => assert(fn == "")
                    case _                          => fail("Expected Capture")
                end match
            }

            "capture name with underscore" in {
                val p = HttpPath.Capture[Int]("user_id")
                p match
                    case HttpPath.Capture(fn, wn, _) =>
                        assert(fn == "user_id")
                        assert(wn == "user_id")
                    case _ => fail("Expected Capture")
                end match
            }
        }
    }

    "Route construction" - {

        "HTTP methods" - {
            "get" in {
                val r = HttpRoute.get("users")
                assert(r.method == HttpMethod.GET)
            }

            "post" in {
                val r = HttpRoute.post("users")
                assert(r.method == HttpMethod.POST)
            }

            "put" in {
                val r = HttpRoute.put("users")
                assert(r.method == HttpMethod.PUT)
            }

            "patch" in {
                val r = HttpRoute.patch("users")
                assert(r.method == HttpMethod.PATCH)
            }

            "delete" in {
                val r = HttpRoute.delete("users")
                assert(r.method == HttpMethod.DELETE)
            }

            "head" in {
                val r = HttpRoute.head("users")
                assert(r.method == HttpMethod.HEAD)
            }

            "options" in {
                val r = HttpRoute.options("users")
                assert(r.method == HttpMethod.OPTIONS)
            }
        }

        "with path capture tracks PathIn" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
            assert(r.method == HttpMethod.GET)
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any, Nothing] = r""")
        }

        "change path via path method" in {
            val r = HttpRoute.get("users").path("items" / HttpPath.Capture[String]("slug"))
            typeCheck("""val _: HttpRoute["slug" ~ String, Any, Any, Nothing] = r""")
        }

        "change path via lambda" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id")).path(_ / "details")
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any, Nothing] = r""")
        }
    }

    "Query parameters" - {

        "required stores correct kind, fieldName, optional=false" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit"))
            assert(r.request.fields.size == 1)
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Query, fn, wn, _, _, opt, _) =>
                    assert(fn == "limit")
                    assert(wn == "")
                    assert(!opt)
                case _ => fail("Expected Query Param")
            end match
        }

        "custom wireName" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit", wireName = "page_size"))
            r.request.fields(0) match
                case Field.Param(_, _, wn, _, _, _, _) => assert(wn == "page_size")
                case _                                 => fail("Expected Query Param")
            end match
        }

        "with default value" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit", default = Present(20)))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default.contains(20))
                case _ => fail("Expected Query Param")
            end match
        }

        "with description" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit", description = "Max results"))
            r.request.fields(0) match
                case Field.Param(_, _, _, _, _, _, desc) => assert(desc == "Max results")
                case _                                   => fail("Expected Query Param")
            end match
        }

        "optional stores optional=true" in {
            val r = HttpRoute.get("users").request(_.queryOpt[String]("search"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Query, fn, _, _, _, opt, _) =>
                    assert(fn == "search")
                    assert(opt)
                case _ => fail("Expected Query Param")
            end match
        }

        "multiple accumulates fields in order" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit").query[Int]("offset"))
            assert(r.request.fields.size == 2)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "limit")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "offset")
        }

        "type tracks required fields" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit").query[Int]("offset"))
            typeCheck("""val _: HttpRoute[Any, "limit" ~ Int & "offset" ~ Int, Any, Nothing] = r""")
        }

        "type tracks optional fields as Maybe" in {
            val r = HttpRoute.get("users").request(_.queryOpt[String]("search"))
            typeCheck("""val _: HttpRoute[Any, "search" ~ kyo.Maybe[String], Any, Nothing] = r""")
        }

        "PathIn and In tracked independently" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id")).request(_.query[String]("fields"))
            typeCheck("""val _: HttpRoute["id" ~ Int, "fields" ~ String, Any, Nothing] = r""")
        }
    }

    "Headers" - {

        "required" in {
            val r = HttpRoute.get("users").request(_.header[String]("X-Request-Id"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Header, fn, _, _, _, opt, _) =>
                    assert(fn == "X-Request-Id")
                    assert(!opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "optional" in {
            val r = HttpRoute.get("users").request(_.headerOpt[String]("X-Trace-Id"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Header, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "type tracks required and optional" in {
            val r = HttpRoute.get("users")
                .request(_.header[String]("X-Request-Id").headerOpt[String]("X-Trace-Id"))
            typeCheck("""val _: HttpRoute[Any, "X-Request-Id" ~ String & "X-Trace-Id" ~ kyo.Maybe[String], Any, Nothing] = r""")
        }
    }

    "Cookies" - {

        "request cookie" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String]("session"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Cookie, fn, _, _, _, opt, _) =>
                    assert(fn == "session")
                    assert(!opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "request cookie optional" in {
            val r = HttpRoute.get("dashboard").request(_.cookieOpt[String]("theme"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Kind.Cookie, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "request cookie type tracks HttpCookie.Request" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String]("session").cookieOpt[String]("theme"))
            typeCheck(
                """val _: HttpRoute[Any, "session" ~ HttpCookie.Request[String] & "theme" ~ kyo.Maybe[HttpCookie.Request[String]], Any, Nothing] = r"""
            )
        }

        "response cookie" in {
            val r = HttpRoute.get("login").response(_.cookie[String]("session"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Kind.Cookie, fn, _, _, _, opt, _) =>
                    assert(fn == "session")
                    assert(!opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "response cookie optional" in {
            val r = HttpRoute.get("login").response(_.cookieOpt[String]("prefs"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Kind.Cookie, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "response cookie type tracks HttpCookie.Response" in {
            val r = HttpRoute.get("login").response(_.cookie[String]("session"))
            typeCheck(
                """val _: HttpRoute[Any, Any, "session" ~ HttpCookie.Response[String], Nothing] = r"""
            )
        }
    }

    "Request body" - {

        "JSON stores field name and schema" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser])
            assert(r.request.fields.size == 1)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Json(_), _) =>
                    assert(fn == "body")
                case _ => fail("Expected Body with Json")
            end match
        }

        "JSON with custom field name" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser]("payload"))
            r.request.fields(0) match
                case Field.Body(fn, _, _) => assert(fn == "payload")
                case _                    => fail("Expected Body")
            end match
        }

        "JSON with description" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser]("payload", description = "User data"))
            r.request.fields(0) match
                case Field.Body(_, _, desc) => assert(desc == "User data")
                case _                      => fail("Expected Body")
            end match
        }

        "text" in {
            val r = HttpRoute.post("echo").request(_.bodyText)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Text(), _) => assert(fn == "body")
                case _                                     => fail("Expected Text body")
            end match
        }

        "binary" in {
            val r = HttpRoute.post("upload").request(_.bodyBinary)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Binary(), _) => assert(fn == "body")
                case _                                       => fail("Expected Binary body")
            end match
        }

        "stream" in {
            val r = HttpRoute.post("upload").request(_.bodyStream)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.ByteStream(), _) => assert(fn == "body")
                case _                                           => fail("Expected ByteStream body")
            end match
        }

        "multipart" in {
            val r = HttpRoute.post("upload").request(_.bodyMultipart)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Multipart(), _) => assert(fn == "body")
                case _                                          => fail("Expected Multipart body")
            end match
        }

        "multipartStream" in {
            val r = HttpRoute.post("upload").request(_.bodyMultipartStream)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.MultipartStream(), _) => assert(fn == "body")
                case _                                                => fail("Expected MultipartStream body")
            end match
        }

        "type tracks body field" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser])
            typeCheck("""val _: HttpRoute[Any, "body" ~ CreateUser, Any, Nothing] = r""")
        }

        "type tracks custom-named body field" in {
            val r = HttpRoute.post("users").request(_.bodyJson[CreateUser]("payload"))
            typeCheck("""val _: HttpRoute[Any, "payload" ~ CreateUser, Any, Nothing] = r""")
        }

        "combined with path and query" in {
            val r = HttpRoute.put("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("reason").bodyJson[CreateUser])
            assert(r.request.fields.size == 2)
            typeCheck("""val _: HttpRoute["id" ~ Int, "reason" ~ String & "body" ~ CreateUser, Any, Nothing] = r""")
        }
    }

    "Response" - {

        "header" in {
            val r = HttpRoute.get("users").response(_.header[String]("X-Request-Id"))
            assert(r.response.fields.size == 1)
            r.response.fields(0) match
                case Field.Param(Field.Param.Kind.Header, fn, _, _, _, opt, _) =>
                    assert(fn == "X-Request-Id")
                    assert(!opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "headerOpt" in {
            val r = HttpRoute.get("users").response(_.headerOpt[String]("X-Trace-Id"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Kind.Header, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "body JSON" in {
            val r = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
        }

        "body SSE" in {
            val r = HttpRoute.get("events").response(_.bodySse[User])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Sse(_, _), _) => assert(fn == "body")
                case _                                        => fail("Expected Sse body")
            end match
        }

        "body NDJSON" in {
            val r = HttpRoute.get("data").response(_.bodyNdjson[User])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Ndjson(_, _), _) => assert(fn == "body")
                case _                                           => fail("Expected Ndjson body")
            end match
        }

        "body text" in {
            val r = HttpRoute.get("health").response(_.bodyText)
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Text(), _) => assert(fn == "body")
                case _                                     => fail("Expected Text body")
            end match
        }

        "status" in {
            val r = HttpRoute.post("users").response(_.status(HttpStatus.Created))
            assert(r.response.status == HttpStatus.Created)
        }

        "type tracks response fields" in {
            val r = HttpRoute.get("users").response(_.header[String]("X-Request-Id").bodyJson[Seq[User]])
            typeCheck("""val _: HttpRoute[Any, Any, "X-Request-Id" ~ String & "body" ~ Seq[User], Nothing] = r""")
        }
    }

    "Errors" - {

        "single error type" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            assert(r.response.errors.size == 1)
            assert(r.response.errors(0).status == HttpStatus.NotFound)
        }

        "multiple error types accumulate" in {
            val r = HttpRoute.post("users")
                .request(_.bodyJson[CreateUser])
                .response(_.bodyJson[User]
                    .error[NotFoundError](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest))
            assert(r.response.errors.size == 2)
            assert(r.response.errors(0).status == HttpStatus.NotFound)
            assert(r.response.errors(1).status == HttpStatus.BadRequest)
        }

        "type tracks error union" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User]
                    .error[NotFoundError](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest))
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, "body" ~ User, NotFoundError | ValidationError] = r""")
        }
    }

    "Metadata" - {

        "tag" in {
            val r = HttpRoute.get("users").metadata(_.tag("Users"))
            assert(r.metadata.tags.contains("Users"))
        }

        "multiple tags" in {
            val r = HttpRoute.get("users").metadata(_.tags("Users", "Admin"))
            assert(r.metadata.tags == Seq("Users", "Admin"))
        }

        "summary" in {
            val r = HttpRoute.get("users").metadata(_.summary("List all users"))
            assert(r.metadata.summary == Present("List all users"))
        }

        "description" in {
            val r = HttpRoute.get("users").metadata(_.description("Returns a paginated list"))
            assert(r.metadata.description == Present("Returns a paginated list"))
        }

        "operationId" in {
            val r = HttpRoute.get("users").metadata(_.operationId("listUsers"))
            assert(r.metadata.operationId == Present("listUsers"))
        }

        "deprecated" in {
            val r = HttpRoute.get("users/old").metadata(_.markDeprecated)
            assert(r.metadata.deprecated)
        }

        "externalDocs url only" in {
            val r = HttpRoute.get("users").metadata(_.externalDocs("https://docs.example.com"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
        }

        "externalDocs url and description" in {
            val r = HttpRoute.get("users").metadata(_.externalDocs("https://docs.example.com", "API Docs"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
            assert(r.metadata.externalDocsDesc == Present("API Docs"))
        }

        "security" in {
            val r = HttpRoute.get("users").metadata(_.security("bearerAuth"))
            assert(r.metadata.security == Present("bearerAuth"))
        }

        "chaining" in {
            val r = HttpRoute.get("users")
                .metadata(
                    _.tag("Users")
                        .summary("List users")
                        .description("Returns paginated list")
                        .operationId("listUsers")
                        .security("bearerAuth")
                        .markDeprecated
                )
            assert(r.metadata.tags == Seq("Users"))
            assert(r.metadata.summary == Present("List users"))
            assert(r.metadata.description == Present("Returns paginated list"))
            assert(r.metadata.operationId == Present("listUsers"))
            assert(r.metadata.security == Present("bearerAuth"))
            assert(r.metadata.deprecated)
        }
    }

    "Mixed inputs" - {

        "path + query + header + body" in {
            val r = HttpRoute.post("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[Int]("limit").header[String]("X-Tenant").bodyJson[CreateUser])
            assert(r.request.fields.size == 3)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Query)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "limit")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Header)
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Tenant")
            r.request.fields(2) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
            typeCheck("""val _: HttpRoute["id" ~ Int, "limit" ~ Int & "X-Tenant" ~ String & "body" ~ CreateUser, Any, Nothing] = r""")
        }

        "path + query + cookie + optional header" in {
            val r = HttpRoute.get("data" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("format").cookie[String]("session").headerOpt[String]("X-Trace"))
            assert(r.request.fields.size == 3)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Query)
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Cookie)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Header)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].optional)
            typeCheck(
                """val _: HttpRoute["id" ~ Int, "format" ~ String & "session" ~ HttpCookie.Request[String] & "X-Trace" ~ kyo.Maybe[String], Any, Nothing] = r"""
            )
        }

        "path + header + body with response headers and errors" in {
            val r = HttpRoute.post("items" / HttpPath.Capture[Int]("id"))
                .request(_.header[String]("X-Tenant").bodyJson[CreateUser])
                .response(_.header[String]("X-Request-Id").bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            assert(r.request.fields.size == 2)
            assert(r.response.fields.size == 2)
            assert(r.response.errors.size == 1)
            typeCheck(
                """val _: HttpRoute["id" ~ Int, "X-Tenant" ~ String & "body" ~ CreateUser, "X-Request-Id" ~ String & "body" ~ User, NotFoundError] = r"""
            )
        }

        "multiple queries + cookie + optional query" in {
            val r = HttpRoute.get("search")
                .request(_.query[String]("q").query[Int]("page").queryOpt[Int]("pageSize").cookie[String]("session"))
            assert(r.request.fields.size == 4)
            assert(!r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(!r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(r.request.fields(3).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Cookie)
            typeCheck(
                """val _: HttpRoute[Any, "q" ~ String & "page" ~ Int & "pageSize" ~ kyo.Maybe[Int] & "session" ~ HttpCookie.Request[String], Any, Nothing] = r"""
            )
        }

        "response with header + cookie + body + multiple errors" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
                .response(
                    _.header[String]("X-Request-Id")
                        .cookie[String]("session")
                        .bodyJson[User]
                        .error[NotFoundError](HttpStatus.NotFound)
                        .error[ValidationError](HttpStatus.BadRequest)
                )
            assert(r.response.fields.size == 3)
            assert(r.response.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Header)
            assert(r.response.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Kind.Cookie)
            r.response.fields(2) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
            assert(r.response.errors.size == 2)
            typeCheck(
                """val _: HttpRoute["id" ~ Int, Any, "X-Request-Id" ~ String & "session" ~ HttpCookie.Response[String] & "body" ~ User, NotFoundError | ValidationError] = r"""
            )
        }

        "full route with request and response mixing all field kinds" in {
            val r = HttpRoute.put("orgs" / HttpPath.Capture[String]("org") / "items" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("reason").header[String]("X-Tenant").cookie[String]("auth").bodyJson[CreateUser])
                .response(
                    _.header[String]("X-Request-Id")
                        .cookie[String]("session")
                        .bodyJson[User]
                        .status(HttpStatus.Success.OK)
                        .error[NotFoundError](HttpStatus.NotFound)
                        .error[ValidationError](HttpStatus.BadRequest)
                )
                .metadata(_.tag("Orgs").summary("Update item").security("bearerAuth"))
            // Request fields
            assert(r.request.fields.size == 4)
            // Response fields
            assert(r.response.fields.size == 3)
            assert(r.response.errors.size == 2)
            // Metadata
            assert(r.metadata.tags == Seq("Orgs"))
            assert(r.metadata.summary == Present("Update item"))
            assert(r.metadata.security == Present("bearerAuth"))
            // Types
            typeCheck(
                """val _: HttpRoute[
                    "org" ~ String & "id" ~ Int,
                    "reason" ~ String & "X-Tenant" ~ String & "auth" ~ HttpCookie.Request[String] & "body" ~ CreateUser,
                    "X-Request-Id" ~ String & "session" ~ HttpCookie.Response[String] & "body" ~ User,
                    NotFoundError | ValidationError
                ] = r"""
            )
        }
    }

    "Name conflicts" - {

        "duplicate query parameter names rejected at compile time" in {
            typeCheckFailure("""
                HttpRoute.get("users")
                    .request(_.query[Int]("page").query[Int]("page"))
            """)("Duplicate request field")
        }

        "query and header with same name rejected at compile time" in {
            typeCheckFailure("""
                HttpRoute.get("users")
                    .request(_.query[String]("token").header[String]("token"))
            """)("Duplicate request field")
        }

        "query named 'body' conflicts with bodyJson" in {
            typeCheckFailure("""
                HttpRoute.post("data")
                    .request(_.query[String]("body").bodyJson[String])
            """)("Duplicate request field")
        }

        "path and query with same name compiles (no cross-source duplicate detection)" in {
            val r = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("id"))
            assert(r.request.fields.size == 1)
        }

        "duplicate response header names rejected at compile time" in {
            typeCheckFailure("""
                HttpRoute.get("users")
                    .response(_.header[String]("X-Id").header[String]("X-Id"))
            """)("Duplicate response field")
        }
    }

    "Query parameter defaults" - {

        "with default value stores default" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit", default = Present(20)))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default == Present(20))
                case _ => fail("Expected Query Param")
            end match
        }

        "without default has Absent" in {
            val r = HttpRoute.get("users").request(_.query[Int]("limit"))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default == Absent)
                case _ => fail("Expected Query Param")
            end match
        }
    }

    "Header defaults" - {

        "with default value" in {
            val r = HttpRoute.get("users").request(_.header[String]("Accept", default = Present("application/json")))
            r.request.fields(0) match
                case p: Field.Param[?, String, ?] =>
                    assert(p.default == Present("application/json"))
                case _ => fail("Expected Header Param")
            end match
        }

        "multiple headers accumulate in order" in {
            val r = HttpRoute.get("users").request(_.header[String]("X-Request-Id").header[String]("X-Trace-Id"))
            assert(r.request.fields.size == 2)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Request-Id")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Trace-Id")
        }
    }

    "Cookie defaults" - {

        "with default value" in {
            val r = HttpRoute.get("dashboard").request(_.cookie[String]("theme", default = Present("light")))
            r.request.fields(0) match
                case p: Field.Param[?, String, ?] =>
                    assert(p.default == Present("light"))
                case _ => fail("Expected Cookie Param")
            end match
        }
    }

    "Full route examples" - {

        "CRUD API" in {
            val list = HttpRoute.get("users")
                .request(_.query[Int]("limit").query[Int]("offset"))
                .response(_.bodyJson[Seq[User]])
                .metadata(_.tag("Users"))

            val get = HttpRoute.get("users" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
                .metadata(_.tag("Users"))

            val create = HttpRoute.post("users")
                .request(_.bodyJson[CreateUser])
                .response(_.bodyJson[User].error[ValidationError](HttpStatus.BadRequest))
                .metadata(_.tag("Users"))

            val update = HttpRoute.put("users" / HttpPath.Capture[Int]("id"))
                .request(_.bodyJson[CreateUser])
                .response(_.bodyJson[User]
                    .error[NotFoundError](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest))
                .metadata(_.tag("Users"))

            assert(list.method == HttpMethod.GET)
            assert(list.request.fields.size == 2)
            assert(get.response.errors.size == 1)
            assert(create.method == HttpMethod.POST)
            assert(create.request.fields.size == 1)
            assert(update.method == HttpMethod.PUT)
            assert(update.response.errors.size == 2)

            typeCheck("""val _: HttpRoute[Any, "limit" ~ Int & "offset" ~ Int, "body" ~ Seq[User], Nothing] = list""")
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, "body" ~ User, NotFoundError] = get""")
            typeCheck("""val _: HttpRoute[Any, "body" ~ CreateUser, "body" ~ User, ValidationError] = create""")
            typeCheck(
                """val _: HttpRoute["id" ~ Int, "body" ~ CreateUser, "body" ~ User, NotFoundError | ValidationError] = update"""
            )
        }

        "streaming SSE endpoint" in {
            val r = HttpRoute.get("events" / HttpPath.Capture[String]("channel"))
                .request(_.queryOpt[String]("filter"))
                .response(_.bodySse[User])
                .metadata(_.tag("Events").summary("Subscribe to events"))
            assert(r.method == HttpMethod.GET)
            assert(r.request.fields.size == 1)
            assert(r.metadata.tags == Seq("Events"))
        }
    }

end HttpRouteTest
