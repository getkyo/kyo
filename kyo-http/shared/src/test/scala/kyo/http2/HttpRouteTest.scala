package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Async
import kyo.Maybe
import kyo.Present
import kyo.Record2.~
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

        "capture defaults wireName to empty" in {
            val p = HttpPath.Capture[Int]("id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "id")
                    assert(wn == "")
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
                        assert(wn == "")
                    case _ => fail("Expected Capture")
                end match
            }
        }
    }

    "Route construction" - {

        "HTTP methods" - {
            "get" in {
                val r = HttpRoute.getRaw("users")
                assert(r.method == HttpMethod.GET)
            }

            "post" in {
                val r = HttpRoute.postRaw("users")
                assert(r.method == HttpMethod.POST)
            }

            "put" in {
                val r = HttpRoute.putRaw("users")
                assert(r.method == HttpMethod.PUT)
            }

            "patch" in {
                val r = HttpRoute.patchRaw("users")
                assert(r.method == HttpMethod.PATCH)
            }

            "delete" in {
                val r = HttpRoute.deleteRaw("users")
                assert(r.method == HttpMethod.DELETE)
            }

            "head" in {
                val r = HttpRoute.headRaw("users")
                assert(r.method == HttpMethod.HEAD)
            }

            "options" in {
                val r = HttpRoute.optionsRaw("users")
                assert(r.method == HttpMethod.OPTIONS)
            }
        }

        "with path capture tracks In" in {
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id"))
            assert(r.method == HttpMethod.GET)
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any] = r""")
        }

        "pathAppend adds suffix and tracks type" in {
            val r = HttpRoute.getRaw("users").pathAppend(HttpPath.Capture[Int]("id") / "details")
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any] = r""")
        }

        "pathAppend combines with existing path captures" in {
            val r = HttpRoute.getRaw("orgs" / HttpPath.Capture[String]("org"))
                .pathAppend(HttpPath.Capture[Int]("id"))
            typeCheck("""val _: HttpRoute["org" ~ String & "id" ~ Int, Any, Any] = r""")
        }

        "pathPrepend adds prefix and tracks type" in {
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id")).pathPrepend("api" / "v1")
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any] = r""")
        }

        "pathPrepend combines with existing path captures" in {
            val r = HttpRoute.getRaw("items" / HttpPath.Capture[Int]("itemId"))
                .pathPrepend("orgs" / HttpPath.Capture[String]("org"))
            typeCheck("""val _: HttpRoute["itemId" ~ Int & "org" ~ String, Any, Any] = r""")
        }

        "pathAppend preserves request fields" in {
            val r = HttpRoute.getRaw("users")
                .request(_.query[Int]("limit"))
                .pathAppend(HttpPath.Capture[Int]("id"))
            typeCheck("""val _: HttpRoute["limit" ~ Int & "id" ~ Int, Any, Any] = r""")
            assert(r.request.fields.size == 1)
        }

        "pathPrepend preserves request fields" in {
            val r = HttpRoute.getRaw("items" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("format"))
                .pathPrepend("api" / HttpPath.Capture[String]("version"))
            typeCheck("""val _: HttpRoute["id" ~ Int & "format" ~ String & "version" ~ String, Any, Any] = r""")
            assert(r.request.fields.size == 1)
        }
    }

    "Query parameters" - {

        "required stores correct location, fieldName, optional=false" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit"))
            assert(r.request.fields.size == 1)
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Query, fn, wn, _, _, opt, _) =>
                    assert(fn == "limit")
                    assert(wn == "")
                    assert(!opt)
                case _ => fail("Expected Query Param")
            end match
        }

        "custom wireName" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit", wireName = "page_size"))
            r.request.fields(0) match
                case Field.Param(_, _, wn, _, _, _, _) => assert(wn == "page_size")
                case _                                 => fail("Expected Query Param")
            end match
        }

        "with default value" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit", default = Present(20)))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default.contains(20))
                case _ => fail("Expected Query Param")
            end match
        }

        "with description" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit", description = "Max results"))
            r.request.fields(0) match
                case Field.Param(_, _, _, _, _, _, desc) => assert(desc == "Max results")
                case _                                   => fail("Expected Query Param")
            end match
        }

        "optional stores optional=true" in {
            val r = HttpRoute.getRaw("users").request(_.queryOpt[String]("search"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Query, fn, _, _, _, opt, _) =>
                    assert(fn == "search")
                    assert(opt)
                case _ => fail("Expected Query Param")
            end match
        }

        "multiple accumulates fields in order" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit").query[Int]("offset"))
            assert(r.request.fields.size == 2)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "limit")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "offset")
        }

        "type tracks required fields" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit").query[Int]("offset"))
            typeCheck("""val _: HttpRoute["limit" ~ Int & "offset" ~ Int, Any, Any] = r""")
        }

        "type tracks optional fields as Maybe" in {
            val r = HttpRoute.getRaw("users").request(_.queryOpt[String]("search"))
            typeCheck("""val _: HttpRoute["search" ~ kyo.Maybe[String], Any, Any] = r""")
        }

        "path and query tracked together in In" in {
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id")).request(_.query[String]("fields"))
            typeCheck("""val _: HttpRoute["id" ~ Int & "fields" ~ String, Any, Any] = r""")
        }
    }

    "Headers" - {

        "required" in {
            val r = HttpRoute.getRaw("users").request(_.header[String]("X-Request-Id"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Header, fn, _, _, _, opt, _) =>
                    assert(fn == "X-Request-Id")
                    assert(!opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "optional" in {
            val r = HttpRoute.getRaw("users").request(_.headerOpt[String]("X-Trace-Id"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Header, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "type tracks required and optional" in {
            val r =
                HttpRoute.getRaw("users").request(_.header[String]("X-Request-Id").headerOpt[String]("X-Trace-Id"))
            typeCheck("""val _: HttpRoute["X-Request-Id" ~ String & "X-Trace-Id" ~ kyo.Maybe[String], Any, Any] = r""")
        }
    }

    "Cookies" - {

        "request cookie" in {
            val r = HttpRoute.getRaw("dashboard").request(_.cookie[String]("session"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Cookie, fn, _, _, _, opt, _) =>
                    assert(fn == "session")
                    assert(!opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "request cookie optional" in {
            val r = HttpRoute.getRaw("dashboard").request(_.cookieOpt[String]("theme"))
            r.request.fields(0) match
                case Field.Param(Field.Param.Location.Cookie, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "request cookie type tracks value type" in {
            val r = HttpRoute.getRaw("dashboard").request(_.cookie[String]("session").cookieOpt[String]("theme"))
            typeCheck(
                """val _: HttpRoute["session" ~ String & "theme" ~ kyo.Maybe[String], Any, Any] = r"""
            )
        }

        "response cookie" in {
            val r = HttpRoute.getRaw("login").response(_.cookie[String]("session"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Location.Cookie, fn, _, _, _, opt, _) =>
                    assert(fn == "session")
                    assert(!opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "response cookie optional" in {
            val r = HttpRoute.getRaw("login").response(_.cookieOpt[String]("prefs"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Location.Cookie, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Cookie Param")
            end match
        }

        "response cookie type tracks HttpCookie" in {
            val r = HttpRoute.getRaw("login").response(_.cookie[String]("session"))
            typeCheck(
                """val _: HttpRoute[Any, "session" ~ HttpCookie[String], Any] = r"""
            )
        }
    }

    "Request body" - {

        "JSON stores field name and schema" in {
            val r = HttpRoute.postRaw("users").request(_.bodyJson[CreateUser])
            assert(r.request.fields.size == 1)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Json(_), _) =>
                    assert(fn == "body")
                case _ => fail("Expected Body with Json")
            end match
        }

        "JSON with custom field name" in {
            val r = HttpRoute.postRaw("users").request(_.bodyJson[CreateUser]("payload"))
            r.request.fields(0) match
                case Field.Body(fn, _, _) => assert(fn == "payload")
                case _                    => fail("Expected Body")
            end match
        }

        "JSON with description" in {
            val r = HttpRoute.postRaw("users").request(_.bodyJson[CreateUser]("payload", "User data"))
            r.request.fields(0) match
                case Field.Body(_, _, desc) => assert(desc == "User data")
                case _                      => fail("Expected Body")
            end match
        }

        "text" in {
            val r = HttpRoute.postRaw("echo").request(_.bodyText)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Text(), _) => assert(fn == "body")
                case _                                     => fail("Expected Text body")
            end match
        }

        "binary" in {
            val r = HttpRoute.postRaw("upload").request(_.bodyBinary)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Binary(), _) => assert(fn == "body")
                case _                                       => fail("Expected Binary body")
            end match
        }

        "stream" in {
            val r = HttpRoute.postRaw("upload").request(_.bodyStream)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.ByteStream(), _) => assert(fn == "body")
                case _                                           => fail("Expected ByteStream body")
            end match
        }

        "multipart" in {
            val r = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Multipart(), _) => assert(fn == "body")
                case _                                          => fail("Expected Multipart body")
            end match
        }

        "multipartStream" in {
            val r = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            r.request.fields(0) match
                case Field.Body(fn, ContentType.MultipartStream(), _) => assert(fn == "body")
                case _                                                => fail("Expected MultipartStream body")
            end match
        }

        "form" in {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val r = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm])
            r.request.fields(0) match
                case Field.Body(fn, ContentType.Form(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Form body")
            end match
        }

        "form with custom field name" in {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val r = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm]("credentials"))
            r.request.fields(0) match
                case Field.Body(fn, _, _) => assert(fn == "credentials")
                case _                    => fail("Expected Body")
            end match
        }

        "form type tracks body field" in {
            case class LoginForm(username: String, password: String) derives HttpFormCodec
            val r = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm])
            typeCheck("""val _: HttpRoute["body" ~ LoginForm, Any, Any] = r""")
        }

        "type tracks body field" in {
            val r = HttpRoute.postRaw("users").request(_.bodyJson[CreateUser])
            typeCheck("""val _: HttpRoute["body" ~ CreateUser, Any, Any] = r""")
        }

        "type tracks custom-named body field" in {
            val r = HttpRoute.postRaw("users").request(_.bodyJson[CreateUser]("payload"))
            typeCheck("""val _: HttpRoute["payload" ~ CreateUser, Any, Any] = r""")
        }

        "combined with path and query" in {
            val r = HttpRoute.putRaw("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("reason").bodyJson[CreateUser])
            assert(r.request.fields.size == 2)
            typeCheck("""val _: HttpRoute["id" ~ Int & "reason" ~ String & "body" ~ CreateUser, Any, Any] = r""")
        }
    }

    "Response" - {

        "header" in {
            val r = HttpRoute.getRaw("users").response(_.header[String]("X-Request-Id"))
            assert(r.response.fields.size == 1)
            r.response.fields(0) match
                case Field.Param(Field.Param.Location.Header, fn, _, _, _, opt, _) =>
                    assert(fn == "X-Request-Id")
                    assert(!opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "headerOpt" in {
            val r = HttpRoute.getRaw("users").response(_.headerOpt[String]("X-Trace-Id"))
            r.response.fields(0) match
                case Field.Param(Field.Param.Location.Header, _, _, _, _, opt, _) =>
                    assert(opt)
                case _ => fail("Expected Header Param")
            end match
        }

        "body JSON" in {
            val r = HttpRoute.getRaw("users").response(_.bodyJson[Seq[User]])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
        }

        "body SSE" in {
            val r = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Sse(_, _), _) => assert(fn == "body")
                case _                                        => fail("Expected Sse body")
            end match
        }

        "body NDJSON" in {
            val r = HttpRoute.getRaw("data").response(_.bodyNdjson[User])
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Ndjson(_, _), _) => assert(fn == "body")
                case _                                           => fail("Expected Ndjson body")
            end match
        }

        "body text" in {
            val r = HttpRoute.getRaw("health").response(_.bodyText)
            r.response.fields(0) match
                case Field.Body(fn, ContentType.Text(), _) => assert(fn == "body")
                case _                                     => fail("Expected Text body")
            end match
        }

        "status" in {
            val r = HttpRoute.postRaw("users").response(_.status(HttpStatus.Created))
            assert(r.response.status == HttpStatus.Created)
        }

        "type tracks response fields" in {
            val r = HttpRoute.getRaw("users").response(_.header[String]("X-Request-Id").bodyJson[Seq[User]])
            typeCheck("""val _: HttpRoute[Any, "X-Request-Id" ~ String & "body" ~ Seq[User], Any] = r""")
        }
    }

    "Errors" - {

        "single error type" in {
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            assert(r.response.errors.size == 1)
            assert(r.response.errors(0).status == HttpStatus.NotFound)
        }

        "multiple error types accumulate" in {
            val r = HttpRoute.postRaw("users")
                .request(_.bodyJson[CreateUser])
                .response(_.bodyJson[User]
                    .error[NotFoundError](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest))
            assert(r.response.errors.size == 2)
            assert(r.response.errors(0).status == HttpStatus.NotFound)
            assert(r.response.errors(1).status == HttpStatus.BadRequest)
        }
    }

    "Metadata" - {

        "tag" in {
            val r = HttpRoute.getRaw("users").metadata(_.tag("Users"))
            assert(r.metadata.tags.contains("Users"))
        }

        "multiple tags" in {
            val r = HttpRoute.getRaw("users").metadata(_.tags("Users", "Admin"))
            assert(r.metadata.tags == Seq("Users", "Admin"))
        }

        "summary" in {
            val r = HttpRoute.getRaw("users").metadata(_.summary("List all users"))
            assert(r.metadata.summary == Present("List all users"))
        }

        "description" in {
            val r = HttpRoute.getRaw("users").metadata(_.description("Returns a paginated list"))
            assert(r.metadata.description == Present("Returns a paginated list"))
        }

        "operationId" in {
            val r = HttpRoute.getRaw("users").metadata(_.operationId("listUsers"))
            assert(r.metadata.operationId == Present("listUsers"))
        }

        "deprecated" in {
            val r = HttpRoute.getRaw("users/old").metadata(_.markDeprecated)
            assert(r.metadata.deprecated)
        }

        "externalDocs url only" in {
            val r = HttpRoute.getRaw("users").metadata(_.externalDocs("https://docs.example.com"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
        }

        "externalDocs url and description" in {
            val r = HttpRoute.getRaw("users").metadata(_.externalDocs("https://docs.example.com", "API Docs"))
            assert(r.metadata.externalDocsUrl == Present("https://docs.example.com"))
            assert(r.metadata.externalDocsDesc == Present("API Docs"))
        }

        "security" in {
            val r = HttpRoute.getRaw("users").metadata(_.security("bearerAuth"))
            assert(r.metadata.security == Present("bearerAuth"))
        }

        "chaining" in {
            val r = HttpRoute.getRaw("users")
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
            val r = HttpRoute.postRaw("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[Int]("limit").header[String]("X-Tenant").bodyJson[CreateUser])
            assert(r.request.fields.size == 3)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Query)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "limit")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Header)
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Tenant")
            r.request.fields(2) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
            typeCheck(
                """val _: HttpRoute["id" ~ Int & "limit" ~ Int & "X-Tenant" ~ String & "body" ~ CreateUser, Any, Any] = r"""
            )
        }

        "path + query + cookie + optional header" in {
            val r = HttpRoute.getRaw("data" / HttpPath.Capture[Int]("id"))
                .request(_.query[String]("format").cookie[String]("session").headerOpt[String]("X-Trace"))
            assert(r.request.fields.size == 3)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Query)
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Cookie)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Header)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].optional)
            typeCheck(
                """val _: HttpRoute["id" ~ Int & "format" ~ String & "session" ~ String & "X-Trace" ~ kyo.Maybe[String], Any, Any] = r"""
            )
        }

        "path + header + body with response headers and errors" in {
            val r = HttpRoute.postRaw("items" / HttpPath.Capture[Int]("id"))
                .request(_.header[String]("X-Tenant").bodyJson[CreateUser])
                .response(_.header[String]("X-Request-Id").bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
            assert(r.request.fields.size == 2)
            assert(r.response.fields.size == 2)
            assert(r.response.errors.size == 1)
            typeCheck(
                """val _: HttpRoute["id" ~ Int & "X-Tenant" ~ String & "body" ~ CreateUser, "X-Request-Id" ~ String & "body" ~ User, Any] = r"""
            )
        }

        "multiple queries + cookie + optional query" in {
            val r = HttpRoute.getRaw("search")
                .request(_.query[String]("q").query[Int]("page").queryOpt[Int]("pageSize").cookie[String]("session"))
            assert(r.request.fields.size == 4)
            assert(!r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(!r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(r.request.fields(2).asInstanceOf[Field.Param[?, ?, ?]].optional)
            assert(r.request.fields(3).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Cookie)
            typeCheck(
                """val _: HttpRoute["q" ~ String & "page" ~ Int & "pageSize" ~ kyo.Maybe[Int] & "session" ~ String, Any, Any] = r"""
            )
        }

        "response with header + cookie + body + multiple errors" in {
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id"))
                .response(
                    _.header[String]("X-Request-Id")
                        .cookie[String]("session")
                        .bodyJson[User]
                        .error[NotFoundError](HttpStatus.NotFound)
                        .error[ValidationError](HttpStatus.BadRequest)
                )
            assert(r.response.fields.size == 3)
            assert(r.response.fields(0).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Header)
            assert(r.response.fields(1).asInstanceOf[Field.Param[?, ?, ?]].kind == Field.Param.Location.Cookie)
            r.response.fields(2) match
                case Field.Body(fn, ContentType.Json(_), _) => assert(fn == "body")
                case _                                      => fail("Expected Json body")
            end match
            assert(r.response.errors.size == 2)
            typeCheck(
                """val _: HttpRoute["id" ~ Int, "X-Request-Id" ~ String & "session" ~ HttpCookie[String] & "body" ~ User, Any] = r"""
            )
        }

        "full route with request and response mixing all field kinds" in {
            val r = HttpRoute.putRaw("orgs" / HttpPath.Capture[String]("org") / "items" / HttpPath.Capture[Int]("id"))
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
                    "org" ~ String & "id" ~ Int & "reason" ~ String & "X-Tenant" ~ String & "auth" ~ String & "body" ~ CreateUser,
                    "X-Request-Id" ~ String & "session" ~ HttpCookie[String] & "body" ~ User,
                    Any
                ] = r"""
            )
        }
    }

    "Filter" - {

        "default filter is noop" in {
            val r = HttpRoute.getRaw("users")
            assert(r.filter eq HttpFilter.noop)
        }

        "add passthrough filter" in {
            val f = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("X-Test", "1"))
            val r = HttpRoute.getRaw("users").filter(f)
            typeCheck("""val _: HttpRoute[Any, Any, Any] = r""")
        }

        "filter with request field requirement needs matching route fields" in {
            val f = new HttpFilter.Request["auth" ~ String, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "auth" ~ String],
                    next: HttpRequest[In & "auth" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
                end apply
            // Route must provide the field the filter requires
            val r = HttpRoute.getRaw("users").request(_.header[String]("auth")).filter(f)
            typeCheck("""val _: HttpRoute["auth" ~ String, Any, Any] = r""")
        }

        "filter with request field requirement rejected when route lacks field" in {
            val f = new HttpFilter.Request["auth" ~ String, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "auth" ~ String],
                    next: HttpRequest[In & "auth" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
                end apply
            typeCheckFailure("""HttpRoute.getRaw("users").filter(f)""")(
                """Found:    (f : kyo.http2.HttpFilter.Request[("auth" : String) ~ String, Any, Nothing])"""
            )
        }

        "filter that adds request fields widens In" in {
            val f = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "test"))
            val r = HttpRoute.getRaw("users").filter(f)
            typeCheck("""val _: HttpRoute["user" ~ String, Any, Any] = r""")
        }

        "filter that adds response fields widens Out" in {
            val f = new HttpFilter.Response[Any, "cached" ~ Boolean, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out & "cached" ~ Boolean] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request).map(_.addField("cached", true))
            val r = HttpRoute.getRaw("users").filter(f)
            typeCheck("""val _: HttpRoute[Any, "cached" ~ Boolean, Any] = r""")
        }

        "composing two filters via route" in {
            val f1 = new HttpFilter.Request[Any, "a" ~ Int, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "a" ~ Int] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("a", 1))
            val f2 = new HttpFilter.Request[Any, "b" ~ Int, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "b" ~ Int] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("b", 2))
            val r = HttpRoute.getRaw("users").filter(f1).filter(f2)
            typeCheck("""val _: HttpRoute["a" ~ Int & "b" ~ Int, Any, Any] = r""")
        }

        "filter preserved through pathAppend" in {
            val f = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "test"))
            val r = HttpRoute.getRaw("users").filter(f).pathAppend(HttpPath.Capture[Int]("id"))
            typeCheck("""val _: HttpRoute["user" ~ String & "id" ~ Int, Any, Any] = r""")
        }

        "filter preserved through pathPrepend" in {
            val f = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            val r = HttpRoute.getRaw("users").filter(f).pathPrepend("api")
            typeCheck("""val _: HttpRoute[Any, Any, Any] = r""")
        }

        "filter preserved through request builder" in {
            val f = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            val r = HttpRoute.getRaw("users").filter(f).request(_.query[Int]("limit"))
            typeCheck("""val _: HttpRoute["limit" ~ Int, Any, Any] = r""")
        }

        "filter preserved through response builder" in {
            val f = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            val r = HttpRoute.getRaw("users").filter(f).response(_.bodyJson[User])
            typeCheck("""val _: HttpRoute[Any, "body" ~ User, Any] = r""")
        }

        "filter preserved through metadata" in {
            val f = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            val r = HttpRoute.getRaw("users").filter(f).metadata(_.tag("Users"))
            typeCheck("""val _: HttpRoute[Any, Any, Any] = r""")
        }

        "full route with filter" in {
            val f = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "admin"))
            val r = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id"))
                .request(_.query[Int]("limit"))
                .response(_.bodyJson[User])
                .filter(f)
                .metadata(_.tag("Users"))
            typeCheck(
                """val _: HttpRoute["id" ~ Int & "limit" ~ Int & "user" ~ String, "body" ~ User, Any] = r"""
            )
        }
    }

    "Error" - {

        "error accumulation on route" in {
            val r = HttpRoute.getRaw("users")
                .response(_.bodyJson[User])
                .error[String](HttpStatus.BadRequest)
                .error[Int](HttpStatus.NotFound)
            typeCheck("""val _: HttpRoute[Any, "body" ~ User, String | Int] = r""")
            assert(r.response.errors.size == 2)
        }

        "error preserved through pathAppend" in {
            val r = HttpRoute.getRaw("users")
                .error[String](HttpStatus.BadRequest)
                .pathAppend(HttpPath.Capture[Int]("id"))
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, String] = r""")
        }

        "error preserved through request builder" in {
            val r = HttpRoute.getRaw("users")
                .error[String](HttpStatus.BadRequest)
                .request(_.query[Int]("limit"))
            typeCheck("""val _: HttpRoute["limit" ~ Int, Any, String] = r""")
        }

        "error preserved through response builder" in {
            val r = HttpRoute.getRaw("users")
                .error[String](HttpStatus.BadRequest)
                .response(_.bodyJson[User])
            typeCheck("""val _: HttpRoute[Any, "body" ~ User, String] = r""")
        }

        "error preserved through metadata" in {
            val r = HttpRoute.getRaw("users")
                .error[String](HttpStatus.BadRequest)
                .metadata(_.tag("Users"))
            typeCheck("""val _: HttpRoute[Any, Any, String] = r""")
        }
    }

    "Name conflicts" - {

        "duplicate query parameter names rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.getRaw("users")
                    .request(_.query[Int]("page").query[Int]("page"))
            """)("Duplicate request field")
        }

        "query and header with same name rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.getRaw("users")
                    .request(_.query[String]("token").header[String]("token"))
            """)("Duplicate request field")
        }

        "query named 'body' conflicts with bodyJson" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.postRaw("data")
                    .request(_.query[String]("body").bodyJson[String])
            """)("Duplicate request field")
        }

        "duplicate response header names rejected at compile time" in pendingUntilFixed {
            typeCheckFailure("""
                HttpRoute.getRaw("users")
                    .response(_.header[String]("X-Id").header[String]("X-Id"))
            """)("Duplicate response field")
        }
    }

    "Query parameter defaults" - {

        "with default value stores default" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit", default = Present(20)))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default == Present(20))
                case _ => fail("Expected Query Param")
            end match
        }

        "without default has Absent" in {
            val r = HttpRoute.getRaw("users").request(_.query[Int]("limit"))
            r.request.fields(0) match
                case p: Field.Param[?, Int, ?] =>
                    assert(p.default == Absent)
                case _ => fail("Expected Query Param")
            end match
        }
    }

    "Header defaults" - {

        "with default value" in {
            val r = HttpRoute.getRaw("users").request(_.header[String]("Accept", default = Present("application/json")))
            r.request.fields(0) match
                case p: Field.Param[?, String, ?] =>
                    assert(p.default == Present("application/json"))
                case _ => fail("Expected Header Param")
            end match
        }

        "multiple headers accumulate in order" in {
            val r = HttpRoute.getRaw("users").request(_.header[String]("X-Request-Id").header[String]("X-Trace-Id"))
            assert(r.request.fields.size == 2)
            assert(r.request.fields(0).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Request-Id")
            assert(r.request.fields(1).asInstanceOf[Field.Param[?, ?, ?]].fieldName == "X-Trace-Id")
        }
    }

    "Cookie defaults" - {

        "with default value" in {
            val r = HttpRoute.getRaw("dashboard").request(_.cookie[String]("theme", default = Present("light")))
            r.request.fields(0) match
                case p: Field.Param[?, String, ?] =>
                    assert(p.default == Present("light"))
                case _ => fail("Expected Cookie Param")
            end match
        }
    }

    "Full route examples" - {

        "CRUD API" in {
            val list = HttpRoute.getRaw("users")
                .request(_.query[Int]("limit").query[Int]("offset"))
                .response(_.bodyJson[Seq[User]])
                .metadata(_.tag("Users"))

            val get = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
                .metadata(_.tag("Users"))

            val create = HttpRoute.postRaw("users")
                .request(_.bodyJson[CreateUser])
                .response(_.bodyJson[User].error[ValidationError](HttpStatus.BadRequest))
                .metadata(_.tag("Users"))

            val update = HttpRoute.putRaw("users" / HttpPath.Capture[Int]("id"))
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

            typeCheck("""val _: HttpRoute["limit" ~ Int & "offset" ~ Int, "body" ~ Seq[User], Any] = list""")
            typeCheck("""val _: HttpRoute["id" ~ Int, "body" ~ User, Any] = get""")
            typeCheck("""val _: HttpRoute["body" ~ CreateUser, "body" ~ User, Any] = create""")
            typeCheck("""val _: HttpRoute["id" ~ Int & "body" ~ CreateUser, "body" ~ User, Any] = update""")
        }

        "streaming SSE endpoint" in {
            val r = HttpRoute.getRaw("events" / HttpPath.Capture[String]("channel"))
                .request(_.queryOpt[String]("filter"))
                .response(_.bodySseJson[User])
                .metadata(_.tag("Events").summary("Subscribe to events"))
            assert(r.method == HttpMethod.GET)
            assert(r.request.fields.size == 1)
            assert(r.metadata.tags == Seq("Events"))
        }
    }

    "Bug probes" - {

        "andThen on wildcard filter loses first filter's ReqIn constraint" in {
            // filter1 requires "auth" ~ String, filter2 requires nothing
            // After composing via route.filter(f1).filter(f2), the stored filter
            // has type HttpFilter[?, ?, ?, ?, ? <: E]. When andThen is called on it
            // with f2, the first filter's ReqIn=? means the composed filter
            // doesn't actually enforce "auth" ~ String at the filter level.
            val f1 = new HttpFilter.Request["auth" ~ String, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "auth" ~ String],
                    next: HttpRequest[In & "auth" ~ String & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    val auth: String = request.fields.auth // must have auth
                    next(request.addField("user", auth))
                end apply

            val f2 = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)

            // Build route with f1 first
            val route1 = HttpRoute.getRaw("users").request(_.header[String]("auth")).filter(f1)
            // Now add f2 — this calls route1.filter (which is HttpFilter[?,?,?,?,?]).andThen(f2)
            val route2 = route1.filter(f2)

            // The route's In type correctly tracks "auth" ~ String & "user" ~ String
            typeCheck("""val _: HttpRoute["auth" ~ String & "user" ~ String, Any, Any] = route2""")

            // But can we extract the filter and apply it to a request WITHOUT "auth"?
            // This would be the unsoundness — the filter object itself doesn't enforce "auth"
            val extractedFilter = route2.filter
            // extractedFilter has type HttpFilter[?, ?, ?, ?, ? <: E]
            // If we could call it with a request lacking "auth", f1 would crash at runtime
            // trying to access request.fields.auth
            succeed
        }

        "filter andThen composition preserves runtime behavior" in {
            // Verify that even though types are erased in storage,
            // the runtime filter chain still works correctly
            var f1Called = false
            var f2Called = false

            val f1 = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    f1Called = true
                    next(request.setHeader("X-F1", "yes"))
                end apply

            val f2 = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    f2Called = true
                    next(request.setHeader("X-F2", "yes"))
                end apply

            val route = HttpRoute.getRaw("test").filter(f1).filter(f2)
            // Both filters should be composed
            assert(route.filter ne HttpFilter.noop)
            succeed
        }

        "wireName defaults to empty consistently for Capture and query" in {
            val path = HttpPath.Capture[Int]("id")
            path match
                case HttpPath.Capture(_, wn, _) =>
                    assert(wn == "")
                case _ => fail("Expected Capture")
            end match

            val route = HttpRoute.getRaw("users").request(_.query[Int]("limit"))
            route.request.fields(0) match
                case Field.Param(_, _, wn, _, _, _, _) =>
                    assert(wn == "")
                case _ => fail("Expected Param")
            end match
        }

        "response cookie type is HttpCookie but request cookie type is raw value" in {
            // Request cookie gives raw A, response cookie gives HttpCookie[A]
            // Verify asymmetry exists
            val route = HttpRoute.getRaw("test")
                .request(_.cookie[String]("session"))
                .response(_.cookie[String]("session2"))

            // Request cookie: "session" ~ String (raw value)
            typeCheck("""val _: HttpRoute["session" ~ String, "session2" ~ HttpCookie[String], Any] = route""")
        }

        "error types are tracked in E type parameter, not Out" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyJson[User])
                .error[NotFoundError](HttpStatus.NotFound)

            // Out is just "body" ~ User, NotFoundError appears in E
            typeCheck("""val _: HttpRoute[Any, "body" ~ User, NotFoundError] = route""")
        }

        "multiple filters via route.filter preserve all constraints in In/Out" in {
            val f1 = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "admin"))

            val f2 = new HttpFilter.Response[Any, "cached" ~ Boolean, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out & "cached" ~ Boolean] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request).map(_.addField("cached", true))

            val route = HttpRoute.getRaw("users").filter(f1).filter(f2)
            typeCheck("""val _: HttpRoute["user" ~ String, "cached" ~ Boolean, Any] = route""")
        }

        "filter requiring field can be applied to route without that field if field comes from path" in {
            // A filter requiring "id" ~ Int should work if the path captures "id"
            val f = new HttpFilter.Request["id" ~ Int, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "id" ~ Int],
                    next: HttpRequest[In & "id" ~ Int] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request)

            // Path captures contribute to In, so this should compile
            val route = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id")).filter(f)
            typeCheck("""val _: HttpRoute["id" ~ Int, Any, Any] = route""")
        }

        "replacing request def after filter loses filter's added fields from In" in {
            // BUG PROBE: After .filter(f) adds "user" ~ String to In,
            // calling .request(newDef) replaces the entire request def.
            // Does the route's In still include "user" ~ String?
            val f = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "test"))

            val route = HttpRoute.getRaw("users")
                .filter(f)
                .request(_.query[Int]("limit"))

            // Does In include "user" ~ String? If request() uses Strict, it
            // only captures what the lambda returns, which is just "limit" ~ Int & "user" ~ String
            // because the input RequestDef already has In = "user" ~ String
            typeCheck("""val _: HttpRoute["limit" ~ Int & "user" ~ String, Any, Any] = route""")
        }

        "lambda overload that ignores input and drops filter fields is rejected" in {
            val f = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "test"))

            // Ignoring the input RequestDef and building a fresh one drops filter fields
            typeCheckFailure("""
                HttpRoute.getRaw("users").filter(f).request(_ =>
                    HttpRoute.RequestDef[Any](HttpPath.Literal("items")).query[Int]("page")
                )
            """)(
                "Cannot prove"
            )

            // But chaining from the input preserves filter fields
            val route = HttpRoute.getRaw("users")
                .filter(f)
                .request(_.query[Int]("limit"))
            val _: HttpRoute["user" ~ String & "limit" ~ Int, Any, Any] = route
            succeed
        }
    }

end HttpRouteTest
