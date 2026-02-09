package kyo

import HttpPath./
import java.util.UUID

class HttpPathTest extends Test:

    "construction" - {
        "apply creates literal path" in {
            val p: HttpPath[Unit] = HttpPath("/api/users")
            succeed
        }

        "implicit string conversion" in {
            val p: HttpPath[Unit] = "/api/users"
            succeed
        }
    }

    "captures" - {
        "int capture" in {
            val p: HttpPath[Int] = HttpPath.int("id")
            succeed
        }

        "long capture" in {
            val p: HttpPath[Long] = HttpPath.long("id")
            succeed
        }

        "string capture" in {
            val p: HttpPath[String] = HttpPath.string("name")
            succeed
        }

        "uuid capture" in {
            val p: HttpPath[UUID] = HttpPath.uuid("id")
            succeed
        }

        "boolean capture" in {
            val p: HttpPath[Boolean] = HttpPath.boolean("flag")
            succeed
        }

        "empty capture name throws" in {
            assertThrows[IllegalArgumentException] { HttpPath.int("") }
            assertThrows[IllegalArgumentException] { HttpPath.long("") }
            assertThrows[IllegalArgumentException] { HttpPath.string("") }
            assertThrows[IllegalArgumentException] { HttpPath.uuid("") }
            assertThrows[IllegalArgumentException] { HttpPath.boolean("") }
        }
    }

    "/ composition" - {
        "two literals" in {
            val p: HttpPath[Unit] = HttpPath("/api") / HttpPath("/users")
            succeed
        }

        "literal and capture" in {
            val p: HttpPath[Int] = HttpPath("/users") / HttpPath.int("id")
            succeed
        }

        "capture and literal" in {
            val p: HttpPath[Int] = HttpPath.int("id") / HttpPath("/details")
            succeed
        }

        "two captures" in {
            val p: HttpPath[(String, Int)] = HttpPath.string("name") / HttpPath.int("id")
            succeed
        }

        "three captures" in {
            val p: HttpPath[(String, Int, Boolean)] =
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
            val handler = HttpHandler.get("/users" / HttpPath.int("id")) { (id, req) =>
                HttpResponse.ok(s"user:$id")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/42")
            yield assertBodyText(response, "user:42")
            end for
        }

        "string capture extracts value" in run {
            val handler = HttpHandler.get("/greet" / HttpPath.string("name")) { (name, req) =>
                HttpResponse.ok(s"hello:$name")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/greet/world")
            yield assertBodyText(response, "hello:world")
            end for
        }

        "multiple captures extract values" in run {
            val handler = HttpHandler.get("/users" / HttpPath.string("name") / HttpPath.int("id")) { (name, id, req) =>
                HttpResponse.ok(s"$name:$id")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/alice/99")
            yield assertBodyText(response, "alice:99")
            end for
        }

        "url-encoded capture" in run {
            val handler = HttpHandler.get("/search" / HttpPath.string("q")) { (q, req) =>
                HttpResponse.ok(s"query:$q")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/search/hello%20world")
            yield assertBodyText(response, "query:hello world")
            end for
        }
    }

end HttpPathTest
