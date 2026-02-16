package kyo

import HttpPath./
import java.util.UUID

class HttpPathTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual

    "construction" - {
        "apply creates literal path" in {
            val p: HttpPath[EmptyTuple] = HttpPath("/api/users")
            succeed
        }

        "implicit string conversion" in {
            val p: HttpPath[EmptyTuple] = "/api/users"
            succeed
        }
    }

    "captures" - {
        "int capture" in {
            val p: HttpPath[(id: Int)] = HttpPath.int("id")
            succeed
        }

        "long capture" in {
            val p: HttpPath[(id: Long)] = HttpPath.long("id")
            succeed
        }

        "string capture" in {
            val p: HttpPath[(name: String)] = HttpPath.string("name")
            succeed
        }

        "uuid capture" in {
            val p: HttpPath[(id: UUID)] = HttpPath.uuid("id")
            succeed
        }

        "boolean capture" in {
            val p: HttpPath[(flag: Boolean)] = HttpPath.boolean("flag")
            succeed
        }

        "empty capture name rejected at compile time" in {
            typeCheckFailure("""HttpPath.int("")""")("Parameter name cannot be empty")
        }
    }

    "/ composition" - {
        "two literals" in {
            val p: HttpPath[EmptyTuple] = HttpPath("/api") / HttpPath("/users")
            succeed
        }

        "literal and capture" in {
            val p: HttpPath[(id: Int)] = HttpPath("/users") / HttpPath.int("id")
            succeed
        }

        "capture and literal" in {
            val p: HttpPath[(id: Int)] = HttpPath.int("id") / HttpPath("/details")
            succeed
        }

        "two captures" in {
            val p: HttpPath[(name: String, id: Int)] = HttpPath.string("name") / HttpPath.int("id")
            succeed
        }

        "three captures" in {
            val p: HttpPath[(a: String, b: Int, c: Boolean)] =
                HttpPath.string("a") / HttpPath.int("b") / HttpPath.boolean("c")
            succeed
        }
    }

    "parseSegments" - {
        "simple path" in {
            assert(HttpPath.parseSegments("/api/users/123") == List("api", "users", "123"))
        }

        "leading slash" in {
            assert(HttpPath.parseSegments("/hello") == List("hello"))
        }

        "no leading slash" in {
            assert(HttpPath.parseSegments("hello/world") == List("hello", "world"))
        }

        "multiple consecutive slashes" in {
            assert(HttpPath.parseSegments("/api///users") == List("api", "users"))
        }

        "trailing slash" in {
            assert(HttpPath.parseSegments("/api/users/") == List("api", "users"))
        }

        "empty path" in {
            assert(HttpPath.parseSegments("") == Nil)
        }

        "root path" in {
            assert(HttpPath.parseSegments("/") == Nil)
        }

        "single segment" in {
            assert(HttpPath.parseSegments("hello") == List("hello"))
        }
    }

    "countSegments" - {
        "matches parseSegments length" in {
            val paths = Seq("/api/users/123", "/hello", "hello/world", "/api///users", "/", "", "a/b/c/d")
            paths.foreach { path =>
                assert(
                    HttpPath.countSegments(path) == HttpPath.parseSegments(path).length,
                    s"countSegments mismatch for path: $path"
                )
            }
            succeed
        }

        "empty path returns 0" in {
            assert(HttpPath.countSegments("") == 0)
        }

        "root returns 0" in {
            assert(HttpPath.countSegments("/") == 0)
        }

        "multi-segment path" in {
            assert(HttpPath.countSegments("/a/b/c") == 3)
        }
    }

    "integration with handler" - {
        "int capture extracts value" in run {
            val handler = HttpHandler.get("/users" / HttpPath.int("id")) { in =>
                HttpResponse.ok(s"user:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/42")
            yield assertBodyText(response, "user:42")
            end for
        }

        "string capture extracts value" in run {
            val handler = HttpHandler.get("/greet" / HttpPath.string("name")) { in =>
                HttpResponse.ok(s"hello:${in.name}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/greet/world")
            yield assertBodyText(response, "hello:world")
            end for
        }

        "multiple captures extract values" in run {
            val handler = HttpHandler.get("/users" / HttpPath.string("name") / HttpPath.int("id")) { in =>
                HttpResponse.ok(s"${in.name}:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/alice/99")
            yield assertBodyText(response, "alice:99")
            end for
        }

        "url-encoded capture" in run {
            val handler = HttpHandler.get("/search" / HttpPath.string("q")) { in =>
                HttpResponse.ok(s"query:${in.q}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/search/hello%20world")
            yield assertBodyText(response, "query:hello world")
            end for
        }
    }

end HttpPathTest
