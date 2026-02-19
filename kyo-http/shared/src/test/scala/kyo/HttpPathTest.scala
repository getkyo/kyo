package kyo

import HttpPath.Literal
import java.util.UUID

class HttpPathTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual

    "construction" - {
        "apply creates literal path" in {
            val p: HttpPath[Row.Empty] = Literal("/api/users")
            succeed
        }

        "implicit string conversion" in {
            val p: HttpPath[Row.Empty] = "/api/users"
            succeed
        }
    }

    "captures" - {
        "int capture" in {
            val p: HttpPath[(id: Int)] = Capture[Int]("id")
            succeed
        }

        "long capture" in {
            val p: HttpPath[(id: Long)] = Capture[Long]("id")
            succeed
        }

        "string capture" in {
            val p: HttpPath[(name: String)] = Capture[String]("name")
            succeed
        }

        "uuid capture" in {
            val p: HttpPath[(id: UUID)] = Capture[java.util.UUID]("id")
            succeed
        }

        "boolean capture" in {
            val p: HttpPath[(flag: Boolean)] = Capture[Boolean]("flag")
            succeed
        }

        "empty capture name compiles (no compile-time validation)" in {
            val p = Capture[Int]("")
            succeed
        }
    }

    "/ composition" - {
        "two literals" in {
            val p = Literal("/api") / Literal("/users")
            succeed
        }

        "literal and capture" in {
            val p: HttpPath[(id: Int)] = "/users" / Capture[Int]("id")
            succeed
        }

        "capture and literal" in {
            val p: HttpPath[(id: Int)] = Capture[Int]("id") / "details"
            succeed
        }

        "two captures" in {
            val p: HttpPath[(name: String, id: Int)] = Capture[String]("name") / Capture[Int]("id")
            succeed
        }

        "three captures" in {
            val p: HttpPath[(a: String, b: Int, c: Boolean)] =
                Capture[String]("a") / Capture[Int]("b") / Capture[Boolean]("c")
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
            val handler = HttpHandler.get("/users" / Capture[Int]("id")) { in =>
                HttpResponse.ok(s"user:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/42")
            yield assertBodyText(response, "user:42")
            end for
        }

        "string capture extracts value" in run {
            val handler = HttpHandler.get("/greet" / Capture[String]("name")) { in =>
                HttpResponse.ok(s"hello:${in.name}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/greet/world")
            yield assertBodyText(response, "hello:world")
            end for
        }

        "multiple captures extract values" in run {
            val handler = HttpHandler.get("/users" / Capture[String]("name") / Capture[Int]("id")) { in =>
                HttpResponse.ok(s"${in.name}:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/alice/99")
            yield assertBodyText(response, "alice:99")
            end for
        }

        "url-encoded capture" in run {
            val handler = HttpHandler.get("/search" / Capture[String]("q")) { in =>
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
