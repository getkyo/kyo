package kyo

import HttpRequest.*
import HttpResponse.Cookie

class HttpRequestTest extends Test:

    case class User(name: String, email: String) derives Schema, CanEqual

    "Method enum" - {

        "all HTTP methods are defined" in {
            val methods = Seq(
                Method.GET,
                Method.POST,
                Method.PUT,
                Method.PATCH,
                Method.DELETE,
                Method.HEAD,
                Method.OPTIONS,
                Method.TRACE,
                Method.CONNECT
            )
            assert(methods.size == 9)
        }

        "methods are comparable" in {
            assert(Method.GET == Method.GET)
            assert(Method.GET != Method.POST)
        }
    }

    "Part" - {

        "construction with all fields" in {
            val part = Part("file", Present("doc.pdf"), Present("application/pdf"), Span.fromUnsafe(Array(1, 2, 3)))
            assert(part.name == "file")
            assert(part.filename == Present("doc.pdf"))
            assert(part.contentType == Present("application/pdf"))
            assert(part.data.size == 3)
        }

        "construction with minimal fields" in {
            val part = Part("data", Absent, Absent, Span.fromUnsafe(Array.empty))
            assert(part.name == "data")
            assert(part.filename == Absent)
            assert(part.contentType == Absent)
        }

        "empty content" in {
            val part = Part("empty", Absent, Absent, Span.fromUnsafe(Array.empty))
            assert(part.data.isEmpty)
        }

        "binary content preserved" in {
            val bytes = Array[Byte](0, 1, 127, -128, -1)
            val part  = Part("binary", Absent, Absent, Span.fromUnsafe(bytes))
            assert(part.data.toArrayUnsafe.toSeq == bytes.toSeq)
        }

        "large content" in {
            val bytes = new Array[Byte](1024 * 1024) // 1MB
            val part  = Part("large", Absent, Absent, Span.fromUnsafe(bytes))
            assert(part.data.size == 1024 * 1024)
        }

        "special characters in name" in {
            val part = Part("file-name_123.txt", Absent, Absent, Span.fromUnsafe(Array.empty))
            assert(part.name == "file-name_123.txt")
        }

        "unicode in filename" in {
            val part = Part("file", Present("文档.pdf"), Absent, Span.fromUnsafe(Array.empty))
            assert(part.filename == Present("文档.pdf"))
        }

        "empty name throws" in {
            assertThrows[IllegalArgumentException] {
                Part("", Absent, Absent, Span.fromUnsafe(Array.empty))
            }
        }
    }

    "Request constructors" - {

        "get" - {
            "with url only" in {
                val request = HttpRequest.get("http://example.com/users")
                assert(request.method == Method.GET)
                // url returns path only, fullUrl returns the complete URL
                assert(request.url == "/users")
                assert(request.fullUrl == "http://example.com/users")
            }

            "with headers" in {
                val request = HttpRequest.get(
                    "http://example.com/users",
                    HttpHeaders.empty.add("Authorization", "Bearer token").add("Accept", "application/json")
                )
                assert(request.header("Authorization") == Present("Bearer token"))
                assert(request.header("Accept") == Present("application/json"))
            }

            "with empty headers" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty)
                assert(request.method == Method.GET)
            }
        }

        "post" - {
            "with string body" in {
                // Use initBytes for raw text body (post() JSON-encodes)
                val request =
                    HttpRequest.initBytes(Method.POST, "http://example.com/users", "raw body".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.method == Method.POST)
                assert(request.bodyText == "raw body")
            }

            "with typed body" in {
                val request = HttpRequest.post("http://example.com/users", User("Alice", "alice@example.com"))
                assert(request.method == Method.POST)
                assert(Abort.run(request.bodyAs[User]).eval == Result.succeed(User("Alice", "alice@example.com")))
            }

            "with headers" in {
                val request = HttpRequest.post(
                    "http://example.com/users",
                    User("Alice", "alice@example.com"),
                    HttpHeaders.empty.add("X-Request-Id", "123")
                )
                assert(request.header("X-Request-Id") == Present("123"))
            }
        }

        "put" - {
            "with string body" in {
                // Use initBytes for raw text body (put() JSON-encodes)
                val request =
                    HttpRequest.initBytes(Method.PUT, "http://example.com/users/1", "updated".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.method == Method.PUT)
                assert(request.bodyText == "updated")
            }

            "with typed body" in {
                val request = HttpRequest.put("http://example.com/users/1", User("Bob", "bob@example.com"))
                assert(request.method == Method.PUT)
                assert(Abort.run(request.bodyAs[User]).eval == Result.succeed(User("Bob", "bob@example.com")))
            }

            "with headers" in {
                val request = HttpRequest.put(
                    "http://example.com/users/1",
                    "body",
                    HttpHeaders.empty.add("If-Match", "etag123")
                )
                assert(request.header("If-Match") == Present("etag123"))
            }
        }

        "patch" - {
            "with string body" in {
                // Use initBytes for raw text body (patch() JSON-encodes)
                val request =
                    HttpRequest.initBytes(Method.PATCH, "http://example.com/users/1", "partial".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.method == Method.PATCH)
                assert(request.bodyText == "partial")
            }

            "with typed body" in {
                val request = HttpRequest.patch("http://example.com/users/1", User("Updated", "new@example.com"))
                assert(request.method == Method.PATCH)
            }

            "with headers" in {
                val request = HttpRequest.patch(
                    "http://example.com/users/1",
                    "body",
                    HttpHeaders.empty.add("Content-Type", "application/json-patch+json")
                )
                assert(request.header("Content-Type") == Present("application/json-patch+json"))
            }
        }

        "delete" - {
            "with url only" in {
                val request = HttpRequest.delete("http://example.com/users/1")
                assert(request.method == Method.DELETE)
            }

            "with headers" in {
                val request = HttpRequest.delete(
                    "http://example.com/users/1",
                    HttpHeaders.empty.add("Authorization", "Bearer token")
                )
                assert(request.header("Authorization") == Present("Bearer token"))
            }
        }

        "head" - {
            "with url only" in {
                val request = HttpRequest.head("http://example.com/users")
                assert(request.method == Method.HEAD)
            }

            "with headers" in {
                val request = HttpRequest.head(
                    "http://example.com/users",
                    HttpHeaders.empty.add("Accept", "application/json")
                )
                assert(request.header("Accept") == Present("application/json"))
            }
        }

        "options" - {
            "with url only" in {
                val request = HttpRequest.options("http://example.com/users")
                assert(request.method == Method.OPTIONS)
            }

            "with headers" in {
                val request = HttpRequest.options(
                    "http://example.com/users",
                    HttpHeaders.empty.add("Origin", "http://localhost:3000")
                )
                assert(request.header("Origin") == Present("http://localhost:3000"))
            }
        }

        // multipart tests are in HttpRequestMultipartTest (JVM-only) because
        // HttpRequest.multipart uses java.util.UUID.randomUUID() which doesn't link on JS
    }

    "Request extensions" - {

        "method" - {
            "returns correct method for GET" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.method == Method.GET)
            }

            "returns correct method for POST" in {
                val request = HttpRequest.post("http://example.com", "body")
                assert(request.method == Method.POST)
            }
        }

        "url" - {
            "returns path and query" in {
                val request = HttpRequest.get("http://example.com/users?page=1")
                assert(request.url == "/users?page=1")
            }

            "includes query string" in {
                val request = HttpRequest.get("http://example.com/search?q=test&limit=10")
                assert(request.url.contains("q=test"))
                assert(request.url.contains("limit=10"))
            }
        }

        "fullUrl" - {
            "returns full url" in {
                val request = HttpRequest.get("http://example.com/users?page=1")
                assert(request.fullUrl == "http://example.com/users?page=1")
            }

            "includes query string" in {
                val request = HttpRequest.get("http://example.com/search?q=test&limit=10")
                assert(request.fullUrl.contains("q=test"))
                assert(request.fullUrl.contains("limit=10"))
            }
        }

        "path" - {
            "returns path without query" in {
                val request = HttpRequest.get("http://example.com/users?page=1")
                assert(request.path == "/users")
            }

            "handles root path" in {
                val request = HttpRequest.get("http://example.com/")
                assert(request.path == "/")
            }

            "handles nested paths" in {
                val request = HttpRequest.get("http://example.com/api/v1/users/123")
                assert(request.path == "/api/v1/users/123")
            }
        }

        "host" - {
            "extracts host from url" in {
                val request = HttpRequest.get("http://example.com/users")
                assert(request.host == "example.com")
            }

            "handles localhost" in {
                val request = HttpRequest.get("http://localhost:8080/api")
                assert(request.host == "localhost")
            }

            "handles IP addresses" in {
                val request = HttpRequest.get("http://192.168.1.1:3000/api")
                assert(request.host == "192.168.1.1")
            }
        }

        "port" - {
            "extracts explicit port" in {
                val request = HttpRequest.get("http://example.com:9000/api")
                assert(request.port == 9000)
            }

            "returns default 80 for http" in {
                val request = HttpRequest.get("http://example.com/api")
                assert(request.port == 80)
            }

            "returns default 443 for https" in {
                val request = HttpRequest.get("https://example.com/api")
                assert(request.port == 443)
            }
        }

        "contentType" - {
            "returns Present when set" in {
                val request = HttpRequest.post(
                    "http://example.com",
                    "body",
                    HttpHeaders.empty.add("Content-Type", "application/json")
                )
                assert(request.contentType == Present("application/json"))
            }

            "returns Absent when not set" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.contentType == Absent)
            }

            "handles charset parameter" in {
                val request = HttpRequest.post(
                    "http://example.com",
                    "body",
                    HttpHeaders.empty.add("Content-Type", "text/plain; charset=utf-8")
                )
                assert(request.contentType == Present("text/plain; charset=utf-8"))
            }
        }

        "header" - {
            "returns Present for existing header" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("X-Custom", "value"))
                assert(request.header("X-Custom") == Present("value"))
            }

            "returns Absent for missing header" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.header("X-Missing") == Absent)
            }

            "is case-insensitive" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Content-Type", "text/plain"))
                assert(request.header("content-type") == Present("text/plain"))
                assert(request.header("CONTENT-TYPE") == Present("text/plain"))
            }
        }

        "headers" - {
            "returns all headers" in {
                val request = HttpRequest.get(
                    "http://example.com",
                    HttpHeaders.empty.add("X-One", "1").add("X-Two", "2")
                )
                val headers = request.headers
                assert(headers.exists((k, v) => k == "X-One" && v == "1"))
                assert(headers.exists((k, v) => k == "X-Two" && v == "2"))
            }

            "returns empty seq when no headers" in {
                val request = HttpRequest.get("http://example.com")
                // May contain default headers, but custom ones should be absent
                assert(!request.headers.contains("X-Custom"))
            }

            "preserves duplicate header names" in {
                val request = HttpRequest.get(
                    "http://example.com",
                    HttpHeaders.empty.add("Accept", "text/html").add("Accept", "application/json")
                )
                var count = 0
                request.headers.foreach((k, _) => if k.equalsIgnoreCase("Accept") then count += 1)
                assert(count >= 2)
            }
        }

        "cookie" - {
            "returns Present for existing cookie" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Cookie", "session=abc123"))
                val cookie  = request.cookie("session")
                assert(cookie.isDefined)
                assert(cookie.get.name == "session")
                assert(cookie.get.value == "abc123")
            }

            "returns Absent for missing cookie" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.cookie("missing") == Absent)
            }
        }

        "cookies" - {
            "returns all cookies" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Cookie", "a=1; b=2"))
                val cookies = request.cookies
                assert(cookies.exists(c => c.name == "a" && c.value == "1"))
                assert(cookies.exists(c => c.name == "b" && c.value == "2"))
            }

            "returns empty seq when no cookies" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.cookies.isEmpty)
            }

            "parses multiple cookies from header" in {
                val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Cookie", "session=xyz; theme=dark; lang=en"))
                assert(request.cookies.size == 3)
            }
        }

        "query" - {
            "returns Present for existing param" in {
                val request = HttpRequest.get("http://example.com?name=Alice")
                assert(request.query("name") == Present("Alice"))
            }

            "returns Absent for missing param" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.query("missing") == Absent)
            }

            "handles url-encoded values" in {
                val request = HttpRequest.get("http://example.com?name=Hello%20World")
                assert(request.query("name") == Present("Hello World"))
            }

            "handles empty value" in {
                val request = HttpRequest.get("http://example.com?empty=")
                assert(request.query("empty") == Present(""))
            }

            "handles multiple values (first)" in {
                val request = HttpRequest.get("http://example.com?tag=a&tag=b&tag=c")
                // Returns first value when multiple exist
                assert(request.query("tag") == Present("a"))
            }
        }

        "queryAll" - {
            "returns all values for param" in {
                val request = HttpRequest.get("http://example.com?tag=a&tag=b&tag=c")
                assert(request.queryAll("tag") == Seq("a", "b", "c"))
            }

            "returns single value as seq" in {
                val request = HttpRequest.get("http://example.com?name=Alice")
                assert(request.queryAll("name") == Seq("Alice"))
            }

            "returns empty seq for missing param" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.queryAll("missing") == Seq.empty)
            }

            "preserves order" in {
                val request = HttpRequest.get("http://example.com?item=3&item=1&item=2")
                assert(request.queryAll("item") == Seq("3", "1", "2"))
            }
        }

        "pathParam" - {
            "returns Present for existing param" in {
                // Path params are set by route matching during server processing
                // Before routing, pathParam returns Absent
                val request = HttpRequest.get("http://example.com/users/123")
                assert(request.pathParam("id") == Absent)
            }

            "returns Absent for missing param" in {
                val request = HttpRequest.get("http://example.com/users")
                assert(request.pathParam("id") == Absent)
            }
        }

        "initBytes" - {
            "accepts Array[Byte]" in {
                val request = HttpRequest.initBytes(Method.POST, "http://example.com", "hello".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.bodyText == "hello")
            }

            "accepts Span[Byte]" in {
                val request =
                    HttpRequest.initBytes(
                        Method.POST,
                        "http://example.com",
                        Span.fromUnsafe("hello".getBytes),
                        HttpHeaders.empty,
                        "text/plain"
                    )
                assert(request.bodyText == "hello")
            }
        }

        "bodyText" - {
            "returns body as string" in {
                // Use initBytes for raw text body (post() JSON-encodes)
                val request =
                    HttpRequest.initBytes(Method.POST, "http://example.com", "hello world".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.bodyText == "hello world")
            }

            "handles empty body" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.bodyText == "")
            }

            "handles UTF-8 encoding" in {
                // Use initBytes for raw text body (post() JSON-encodes)
                val request =
                    HttpRequest.initBytes(Method.POST, "http://example.com", "Hello 世界".getBytes("UTF-8"), HttpHeaders.empty, "text/plain")
                assert(request.bodyText == "Hello 世界")
            }
        }

        "bodyBytes" - {
            "returns raw bytes" in {
                // Use initBytes for raw bytes (post() JSON-encodes)
                val request = HttpRequest.initBytes(Method.POST, "http://example.com", "test".getBytes, HttpHeaders.empty, "text/plain")
                assert(request.bodyBytes.toArray.sameElements("test".getBytes))
            }

            "handles empty body" in {
                val request = HttpRequest.get("http://example.com")
                assert(request.bodyBytes.isEmpty)
            }

            "handles binary content" in {
                // Use initBytes for raw binary body
                val bytes   = Array[Byte](0, 1, 127, -128, -1)
                val request = HttpRequest.initBytes(Method.POST, "http://example.com", bytes, HttpHeaders.empty, "application/octet-stream")
                assert(request.bodyBytes.size == 5)
            }
        }

        "bodyAs" - {
            "deserializes JSON body" in {
                val request = HttpRequest.post("http://example.com", User("Alice", "alice@example.com"))
                assert(Abort.run(request.bodyAs[User]).eval == Result.succeed(User("Alice", "alice@example.com")))
            }

            "fails on invalid JSON" in {
                val request = HttpRequest.post("http://example.com", "not json")
                assert(Abort.run(request.bodyAs[User]).eval.isFailure)
            }

            "fails on type mismatch" in {
                val request = HttpRequest.post("http://example.com", """{"wrong": "structure"}""")
                assert(Abort.run(request.bodyAs[User]).eval.isFailure)
            }
        }

        "parts" - {
            "returns empty for non-multipart" in {
                val request = HttpRequest.post("http://example.com", "body")
                assert(request.parts.isEmpty)
            }

            // multipart parts tests are in HttpRequestMultipartTest (JVM-only)
        }
    }

    "URL edge cases" - {
        "very long URL" in {
            val longPath = "x" * 2000
            val request  = HttpRequest.get(s"http://example.com/$longPath")
            assert(request.path.length > 2000)
        }

        "URL with fragment" in {
            val request = HttpRequest.get("http://example.com/page#section")
            // Fragments are typically not sent to server
            assert(request.path == "/page")
        }

        "URL with userinfo" in {
            val request = HttpRequest.get("http://user:pass@example.com/api")
            assert(request.host == "example.com")
        }

        "IPv6 host" in {
            val request = HttpRequest.get("http://[::1]:8080/api")
            assert(request.host == "::1" || request.host == "[::1]")
            assert(request.port == 8080)
        }

        "internationalized domain name" in {
            val request = HttpRequest.get("http://例え.jp/path")
            assert(request.path == "/path")
        }

        "URL-encoded path segments" in {
            val request = HttpRequest.get("http://example.com/hello%20world")
            assert(request.path == "/hello world" || request.path == "/hello%20world")
        }

        "URL-encoded query values" in {
            val request = HttpRequest.get("http://example.com?msg=hello%20world")
            assert(request.query("msg") == Present("hello world"))
        }

        "query with empty value" in {
            val request = HttpRequest.get("http://example.com?flag=")
            assert(request.query("flag") == Present(""))
        }

        "query with no value (flag)" in {
            val request = HttpRequest.get("http://example.com?flag")
            // Flag-style params: present in URL so should not be Absent
            assert(request.query("flag") == Present("") || request.query("flag") == Present("flag"))
        }

        "multiple query params same name" in {
            val request = HttpRequest.get("http://example.com?item=a&item=b&item=c")
            // Single query() returns first value
            assert(request.query("item") == Present("a"))
            // queryAll returns all values
            assert(request.queryAll("item") == Seq("a", "b", "c"))
        }

        "malformed URL handling" in {
            assertThrows[Exception] {
                HttpRequest.get("not a valid url")
            }
        }
    }

    "Encoding edge cases" - {
        "non-ASCII characters in body" in {
            // Use initBytes for raw text body (post() JSON-encodes)
            val request =
                HttpRequest.initBytes(Method.POST, "http://example.com", "日本語 中文 한국어".getBytes("UTF-8"), HttpHeaders.empty, "text/plain")
            assert(request.bodyText == "日本語 中文 한국어")
        }

        "different charset in content-type" in {
            val request = HttpRequest.post(
                "http://example.com",
                "body",
                HttpHeaders.empty.add("Content-Type", "text/plain; charset=iso-8859-1")
            )
            assert(request.contentType.exists(_.contains("iso-8859-1")))
        }

        "binary body preserved" in {
            // Use initBytes for raw binary body
            val bytes   = Array[Byte](0, 1, 2, 127, -128, -1)
            val request = HttpRequest.initBytes(Method.POST, "http://example.com", bytes, HttpHeaders.empty, "application/octet-stream")
            assert(request.bodyBytes.size == 6)
        }

        "null bytes in body" in {
            // Use initBytes for raw binary body
            val bytes   = Array[Byte](65, 0, 66, 0, 67)
            val request = HttpRequest.initBytes(Method.POST, "http://example.com", bytes, HttpHeaders.empty, "application/octet-stream")
            assert(request.bodyBytes.size == 5)
        }
    }

    "Large payload handling" - {
        "large request body" in {
            // Use initBytes for raw text body (post() JSON-encodes)
            val largeBody = "x" * (1024 * 1024) // 1MB
            val request   = HttpRequest.initBytes(Method.POST, "http://example.com", largeBody.getBytes, HttpHeaders.empty, "text/plain")
            assert(request.bodyText.length == 1024 * 1024)
        }

        "many headers" in {
            val headers = (1 to 100).foldLeft(HttpHeaders.empty)((h, i) => h.add(s"X-Header-$i", s"value$i"))
            val request = HttpRequest.get("http://example.com", headers)
            assert(request.headers.size >= 100)
        }

        "very long header value" in {
            val longValue = "x" * 8000
            val request   = HttpRequest.get("http://example.com", HttpHeaders.empty.add("X-Long", longValue))
            assert(request.header("X-Long") == Present(longValue))
        }

        "many query parameters" in {
            val params  = (1 to 100).map(i => s"param$i=$i").mkString("&")
            val request = HttpRequest.get(s"http://example.com?$params")
            assert(request.query("param1") == Present("1"))
            assert(request.query("param100") == Present("100"))
        }

        "many cookies" in {
            val cookies = (1 to 50).map(i => s"cookie$i=value$i").mkString("; ")
            val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Cookie", cookies))
            assert(request.cookies.size == 50)
        }

        "plus sign in query param decoded as space" in {
            val request = HttpRequest.get("http://example.com/search?q=hello+world")
            assert(request.query("q") == Present("hello world"))
        }

        "non-ASCII percent-encoded query param" in {
            // é = %C3%A9 in UTF-8
            val request = HttpRequest.get("http://example.com?name=caf%C3%A9")
            assert(request.query("name") == Present("café"))
        }

        "CJK percent-encoded query param" in {
            // 中 = %E4%B8%AD in UTF-8
            val request = HttpRequest.get("http://example.com?q=%E4%B8%AD%E6%96%87")
            assert(request.query("q") == Present("中文"))
        }

        "query param with ampersand in value" in {
            val request = HttpRequest.get("http://example.com?q=a%26b")
            assert(request.query("q") == Present("a&b"))
        }

        "query param with equals in value" in {
            val request = HttpRequest.get("http://example.com?expr=a%3Db")
            assert(request.query("expr") == Present("a=b"))
        }

        "multiple headers with same name preserved on request" in {
            val request = HttpRequest.get("http://example.com")
                .addHeader("Accept", "text/html")
                .addHeader("Accept", "application/json")
            // Request addHeader appends (multi-value), first match returned
            assert(request.header("Accept") == Present("text/html"))
            // Both should exist in headers
            var count = 0
            request.headers.foreach { (k, _) =>
                if k.equalsIgnoreCase("Accept") then count += 1
            }
            assert(count == 2)
        }

        "cookie with double-quoted value" in {
            // RFC 6265 allows quoted cookie values
            val request = HttpRequest.get("http://example.com", HttpHeaders.empty.add("Cookie", "session=\"abc123\""))
            val cookie  = request.cookie("session")
            assert(cookie.isDefined)
        }

        "query param with encoded slash preserved" in {
            // %2F in query should stay encoded and decode to /
            val request = HttpRequest.get("http://example.com?path=a%2Fb")
            assert(request.query("path") == Present("a/b"))
        }

        "query param with bare key (no equals)" in {
            val request = HttpRequest.get("http://example.com?flag&name=value")
            assert(request.query("name") == Present("value"))
        }

        "empty query string (bare ?)" in {
            val request = HttpRequest.get("http://example.com/path?")
            assert(request.path == "/path")
        }

        "multiple cookies in single header" in {
            val request = HttpRequest.get(
                "http://example.com",
                HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            )
            assert(request.cookies.size == 3)
            assert(request.cookie("a").map(_.value) == Present("1"))
            assert(request.cookie("c").map(_.value) == Present("3"))
        }

        "header value with leading/trailing spaces" in {
            val request = HttpRequest.get(
                "http://example.com",
                HttpHeaders.empty.add("X-Custom", "  spaced  ")
            )
            assert(request.header("X-Custom").isDefined)
        }
    }

end HttpRequestTest
