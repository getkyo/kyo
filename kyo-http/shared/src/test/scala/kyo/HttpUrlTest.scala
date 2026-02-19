package kyo

import java.net.URI
import kyo.Maybe
import kyo.Maybe.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class HttpUrlTest extends AnyFreeSpec with NonImplicitAssertions:

    /** Compare HttpUrl.apply output against java.net.URI for the given URL. */
    private def assertMatchesUri(url: String): Unit =
        val uri    = new URI(url)
        val parsed = HttpUrl(url)
        val expectedHost = Option(uri.getHost).getOrElse("") match
            case h if h.startsWith("[") && h.endsWith("]") => h.substring(1, h.length - 1)
            case h                                         => h
        assert(parsed.host == expectedHost, s"host mismatch for: $url")
        if uri.getPort != -1 then
            assert(parsed.port == uri.getPort, s"port mismatch for: $url"): Unit
        val expectedPath = if uri.getRawPath == null || uri.getRawPath.isEmpty then "/" else uri.getRawPath
        assert(parsed.rawPath == expectedPath, s"path mismatch for: $url")
        val expectedQuery = if uri.getRawQuery == null || uri.getRawQuery.isEmpty then Absent else Present(uri.getRawQuery)
        assert(parsed.rawQuery == expectedQuery, s"query mismatch for: $url"): Unit
    end assertMatchesUri

    // ---- apply(String) - URL parsing ----

    "apply(String)" - {

        "basic URLs" - {

            "http with path" in {
                assertMatchesUri("http://example.com/path")
            }

            "https with path" in {
                assertMatchesUri("https://example.com/path")
            }

            "root path" in {
                assertMatchesUri("http://example.com/")
            }

            "host only (no trailing slash)" in {
                assertMatchesUri("http://example.com")
            }

            "deep path" in {
                assertMatchesUri("http://example.com/a/b/c/d/e")
            }
        }

        "query strings" - {

            "single query param" in {
                assertMatchesUri("http://example.com/path?key=value")
            }

            "multiple query params" in {
                assertMatchesUri("http://example.com/path?a=1&b=2&c=3")
            }

            "query with no path" in {
                assertMatchesUri("http://example.com?key=value")
            }

            "empty query (bare ?)" in {
                assertMatchesUri("http://example.com/path?")
            }

            "second ? is literal in query" in {
                assertMatchesUri("http://example.com/path?a=1?b=2")
            }

            "encoded & and = in query" in {
                assertMatchesUri("http://example.com/path?q=%26a%3Db")
            }
        }

        "fragments" - {

            "path with fragment" in {
                assertMatchesUri("http://example.com/path#frag")
            }

            "query and fragment" in {
                assertMatchesUri("http://example.com/path?q=1#frag")
            }

            "empty fragment (bare #)" in {
                assertMatchesUri("http://example.com/path#")
            }

            "empty query and empty fragment" in {
                assertMatchesUri("http://example.com/path?#")
            }

            "? inside fragment is not a query" in {
                assertMatchesUri("http://example.com/path#frag?notquery")
            }
        }

        "ports" - {

            "explicit port" in {
                assertMatchesUri("http://example.com:8080/path")
            }

            "https with explicit port" in {
                assertMatchesUri("https://example.com:8443/secure")
            }

            "port and query" in {
                assertMatchesUri("http://example.com:9090/api?format=json")
            }

            "port zero" in {
                assertMatchesUri("http://example.com:0/path")
            }

            "max port 65535" in {
                assertMatchesUri("http://example.com:65535/path")
            }

            "default port for http is 80" in {
                val url = HttpUrl("http://example.com/path")
                assert(url.port == 80)
            }

            "default port for https is 443" in {
                val url = HttpUrl("https://example.com/path")
                assert(url.port == 443)
            }

            "explicit port overrides default" in {
                val url = HttpUrl("http://example.com:3000/path")
                assert(url.port == 3000)
            }
        }

        "userinfo" - {

            "user:pass@host" in {
                assertMatchesUri("http://user:pass@example.com/path")
            }

            "user:pass@host with port, query, and fragment" in {
                assertMatchesUri("http://user:pass@foo:21/bar;par?b#c")
            }

            "empty password" in {
                assertMatchesUri("https://test:@example.com/path")
            }

            "empty user and password" in {
                assertMatchesUri("https://:@example.com/path")
            }

            "encoded @ in password" in {
                assertMatchesUri("http://user:p%40ss@example.com/path")
            }
        }

        "IPv6 addresses" - {

            "IPv6 with port" in {
                val url = HttpUrl("http://[::1]:8080/path")
                assert(url.host == "::1")
                assert(url.port == 8080)
                assertMatchesUri("http://[::1]:8080/path")
            }

            "IPv6 without port" in {
                val url = HttpUrl("http://[::1]/path")
                assert(url.host == "::1")
                assert(url.port == 80)
                assertMatchesUri("http://[::1]/path")
            }

            "IPv6 full address with port" in {
                assertMatchesUri("http://[2001:db8::1]:8080/path?q=1#frag")
            }

            "IPv6 loopback root" in {
                assertMatchesUri("http://[::1]/")
            }

            "IPv6 strips brackets from host" in {
                val url = HttpUrl("http://[2001:db8::1]/path")
                assert(url.host == "2001:db8::1")
                assert(!url.host.contains("["))
            }
        }

        "percent-encoded characters" - {

            "encoded spaces in path" in {
                assertMatchesUri("http://example.com/path%20with%20spaces")
            }

            "encoded query values" in {
                assertMatchesUri("http://example.com/path?key=hello%20world")
            }

            "encoded slash in path" in {
                assertMatchesUri("http://example.com/a%2Fb/c")
            }

            "UTF-8 encoded path" in {
                assertMatchesUri("http://example.com/%E4%B8%AD%E6%96%87/path")
            }
        }

        "path-only URLs (no scheme)" - {

            "simple path" in {
                val url = HttpUrl("/just/a/path")
                assert(url.host == "")
                assert(url.port == 80)
                assert(url.rawPath == "/just/a/path")
                assert(url.rawQuery == Absent)
            }

            "path with query" in {
                val url = HttpUrl("/path?key=val")
                assert(url.host == "")
                assert(url.rawPath == "/path")
                assert(url.rawQuery == Present("key=val"))
            }

            "path with fragment" in {
                val url = HttpUrl("/path#frag")
                assert(url.host == "")
                assert(url.rawPath == "/path")
                assert(url.rawQuery == Absent)
            }

            "path with query and fragment" in {
                val url = HttpUrl("/path?q=1#frag")
                assert(url.host == "")
                assert(url.rawPath == "/path")
                assert(url.rawQuery == Present("q=1"))
            }
        }

        "complex combinations" - {

            "all components present" in {
                assertMatchesUri("https://user:pass@example.com:8443/path/to/resource?key=value&other=123#section")
            }

            "path with semicolon params" in {
                assertMatchesUri("http://example.com/path;params?query#frag")
            }

            "multiple path segments with encoded chars" in {
                assertMatchesUri("https://api.example.com:443/v2/users/123/posts?page=1&limit=20")
            }

            "query with plus signs" in {
                assertMatchesUri("http://example.com/search?q=hello+world")
            }

            "path with dots" in {
                assertMatchesUri("http://example.com/a/../b/./c")
            }

            "subdomain with port" in {
                assertMatchesUri("https://sub.domain.example.com:9443/api/v1")
            }
        }

        "edge cases" - {

            "double-encoded percent in query" in {
                assertMatchesUri("http://example.com/path?q=%2520")
            }

            "path with trailing slash" in {
                assertMatchesUri("http://example.com/path/")
            }

            "path with consecutive slashes" in {
                assertMatchesUri("http://example.com//double//slashes")
            }

            "very long URL" in {
                val longPath = "/" + "a" * 8000
                assertMatchesUri(s"http://example.com$longPath")
            }

            "empty path with port" in {
                assertMatchesUri("http://example.com:8080")
            }

            "query with empty key" in {
                assertMatchesUri("http://example.com?=value")
            }

            "query with only ampersands" in {
                assertMatchesUri("http://example.com?&&")
            }

            "fragment only (no path)" in {
                val url = HttpUrl("http://example.com#frag")
                assert(url.host == "example.com")
                assert(url.rawPath == "/")
                assert(url.rawQuery == Absent)
            }

            "path with dot segments" in {
                assertMatchesUri("http://example.com/a/b/../c")
            }

            "URL with encoded backslash %5C" in {
                assertMatchesUri("http://example.com/path%5Cfile")
            }

            "URL with encoded null %00" in {
                assertMatchesUri("http://example.com/path%00file")
            }

            "host with trailing dot" in {
                assertMatchesUri("http://example.com./path")
            }

            "URL with empty path segments" in {
                assertMatchesUri("http://example.com/a//b///c")
            }

            "query with hash in value" in {
                assertMatchesUri("http://example.com/path?q=a%23b")
            }
        }
    }

    // ---- apply(components) ----

    "apply(components)" - {

        "basic construction" in {
            val url = HttpUrl("example.com", 8080, "/path", Present("q=1"))
            assert(url.host == "example.com")
            assert(url.port == 8080)
            assert(url.rawPath == "/path")
            assert(url.rawQuery == Present("q=1"))
        }

        "empty host" in {
            val url = HttpUrl("", 80, "/path", Absent)
            assert(url.host == "")
            assert(url.rawPath == "/path")
        }

        "IPv6 host" in {
            val url = HttpUrl("::1", 443, "/path", Absent)
            assert(url.host == "::1")
            assert(url.ssl)
        }
    }

    // ---- ssl ----

    "ssl" - {

        "true for port 443" in {
            assert(HttpUrl("https://example.com/path").ssl)
        }

        "false for port 80" in {
            assert(!HttpUrl("http://example.com/path").ssl)
        }

        "false for non-standard port" in {
            assert(!HttpUrl("http://example.com:8080/path").ssl)
        }

        "true for explicit 443 on http scheme" in {
            // port 443 = ssl regardless of scheme used to parse
            assert(HttpUrl("example.com", 443, "/path", Absent).ssl)
        }
    }

    // ---- isSsl ----

    "isSsl" - {

        "true for 443" in {
            assert(HttpUrl.isSsl(443))
        }

        "false for 80" in {
            assert(!HttpUrl.isSsl(80))
        }

        "false for 8080" in {
            assert(!HttpUrl.isSsl(8080))
        }

        "false for 0" in {
            assert(!HttpUrl.isSsl(0))
        }
    }

    // ---- isDefaultPort ----

    "isDefaultPort" - {

        "true for 80" in {
            assert(HttpUrl.isDefaultPort(80))
        }

        "true for 443" in {
            assert(HttpUrl.isDefaultPort(443))
        }

        "false for 8080" in {
            assert(!HttpUrl.isDefaultPort(8080))
        }

        "false for 0" in {
            assert(!HttpUrl.isDefaultPort(0))
        }

        "false for negative" in {
            assert(!HttpUrl.isDefaultPort(-1))
        }
    }

    // ---- full ----

    "full" - {

        "standard http" in {
            assert(HttpUrl("http://example.com/path?q=1").full == "http://example.com/path?q=1")
        }

        "standard https" in {
            assert(HttpUrl("https://example.com/path").full == "https://example.com/path")
        }

        "non-standard port included" in {
            assert(HttpUrl("http://example.com:8080/path").full == "http://example.com:8080/path")
        }

        "omits default http port" in {
            assert(HttpUrl("http://example.com:80/path").full == "http://example.com/path")
        }

        "omits default https port" in {
            assert(HttpUrl("https://example.com:443/path").full == "https://example.com/path")
        }

        "IPv6 wraps in brackets" in {
            assert(HttpUrl("http://[::1]:8080/path").full == "http://[::1]:8080/path")
        }

        "IPv6 with default https port omits port" in {
            assert(HttpUrl("https://[::1]/path").full == "https://[::1]/path")
        }

        "IPv6 with default http port omits port" in {
            assert(HttpUrl("http://[::1]/path").full == "http://[::1]/path")
        }

        "root path with query" in {
            assert(HttpUrl("http://example.com/?q=1").full == "http://example.com/?q=1")
        }

        "empty host returns path only" in {
            assert(HttpUrl("", 80, "/path", Present("q=1")).full == "/path?q=1")
        }

        "empty host no query returns path" in {
            assert(HttpUrl("", 80, "/path", Absent).full == "/path")
        }

        "round-trip preserves URL" in {
            // Note: scheme is derived from port (443=https, else http),
            // so only URLs where scheme matches port convention round-trip.
            val urls = Seq(
                "http://example.com/path",
                "https://example.com/api?key=val",
                "http://[::1]:9090/test",
                "http://example.com/"
            )
            urls.foreach { u =>
                assert(HttpUrl(u).full == u, s"round-trip failed for: $u"): Unit
            }
        }
    }

    // ---- toString ----

    "toString" - {

        "delegates to full" in {
            val url = HttpUrl("http://example.com/path?q=1")
            assert(url.toString == url.full)
        }

        "works for empty host" in {
            val url = HttpUrl("", 80, "/path", Absent)
            assert(url.toString == "/path")
        }
    }

    // ---- resolve ----

    "resolve" - {

        "absolute http redirect" in {
            val resolved = HttpUrl("http://example.com/old").resolve("http://other.com/new")
            assert(resolved.host == "other.com")
            assert(resolved.port == 80)
            assert(resolved.rawPath == "/new")
        }

        "absolute https redirect" in {
            val resolved = HttpUrl("http://example.com/old").resolve("https://other.com/new")
            assert(resolved.host == "other.com")
            assert(resolved.port == 443)
            assert(resolved.rawPath == "/new")
        }

        "relative redirect" in {
            val resolved = HttpUrl("http://example.com/a/b").resolve("c")
            assert(resolved.host == "example.com")
            assert(resolved.rawPath == "/a/c")
        }

        "absolute path redirect" in {
            val resolved = HttpUrl("http://example.com/a/b").resolve("/new")
            assert(resolved.host == "example.com")
            assert(resolved.rawPath == "/new")
        }

        "redirect with query" in {
            val resolved = HttpUrl("http://example.com/old").resolve("/new?key=val")
            assert(resolved.rawPath == "/new")
            assert(resolved.rawQuery == Present("key=val"))
        }

        "redirect with query and fragment" in {
            val resolved = HttpUrl("http://example.com/old").resolve("/new?q=1#frag")
            assert(resolved.rawPath == "/new")
            assert(resolved.rawQuery == Present("q=1"))
        }

        "relative parent redirect" in {
            val resolved = HttpUrl("https://example.com/a/b/c").resolve("../d")
            assert(resolved.host == "example.com")
            assert(resolved.port == 443)
            assert(resolved.rawPath == "/a/d")
        }

        "preserves scheme of absolute redirect" in {
            val resolved = HttpUrl("https://secure.com/path").resolve("http://insecure.com/other")
            assert(!resolved.ssl)
            assert(resolved.host == "insecure.com")
        }
    }

    // ---- ensureHostHeader ----

    "ensureHostHeader" - {

        "adds Host for simple host" in {
            val headers = HttpUrl("http://example.com/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com"))
        }

        "adds Host with non-standard port" in {
            val headers = HttpUrl("http://example.com:8080/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com:8080"))
        }

        "wraps IPv6 in brackets" in {
            val headers = HttpUrl("http://[::1]/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("[::1]"))
        }

        "IPv6 with non-standard port" in {
            val headers = HttpUrl("http://[::1]:9090/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("[::1]:9090"))
        }

        "does not overwrite existing Host" in {
            val existing = HttpHeaders.empty.add("Host", "existing.com")
            val headers  = HttpUrl("http://new.com/path").ensureHostHeader(existing)
            assert(headers.get("Host") == Present("existing.com"))
        }

        "empty host returns headers unchanged" in {
            val headers = HttpUrl("", 80, "/path", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(!headers.contains("Host"))
        }

        "standard http port not appended" in {
            val headers = HttpUrl("http://example.com/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com"))
        }

        "standard https port not appended" in {
            val headers = HttpUrl("https://example.com/path").ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com"))
        }

        "IPv6 with standard port omits port" in {
            val headers = HttpUrl("::1", 443, "/path", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("[::1]"))
        }

        "non-IPv6 with port 0" in {
            // port 0 is not > 0, so nonStdPort is false
            val headers = HttpUrl("example.com", 0, "/path", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com"))
        }

        "cached value is consistent across multiple calls" in {
            val url      = HttpUrl("http://example.com:9090/path")
            val headers1 = url.ensureHostHeader(HttpHeaders.empty)
            val headers2 = url.ensureHostHeader(HttpHeaders.empty)
            assert(headers1.get("Host") == Present("example.com:9090"))
            assert(headers2.get("Host") == Present("example.com:9090"))
        }

        "component constructor with non-standard port" in {
            val headers = HttpUrl("myhost", 9004, "/ping", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("myhost:9004"))
        }

        "component constructor with standard http port" in {
            val headers = HttpUrl("myhost", 80, "/ping", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("myhost"))
        }

        "component constructor with standard https port" in {
            val headers = HttpUrl("myhost", 443, "/ping", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("myhost"))
        }

        "component constructor IPv6 with non-standard port" in {
            val headers = HttpUrl("::1", 9004, "/ping", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("[::1]:9004"))
        }

        "component constructor IPv6 with standard port" in {
            val headers = HttpUrl("::1", 80, "/ping", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("[::1]"))
        }

        "negative port treated as standard (not > 0)" in {
            val headers = HttpUrl("example.com", -1, "/path", Absent).ensureHostHeader(HttpHeaders.empty)
            assert(headers.get("Host") == Present("example.com"))
        }
    }

    // ---- fromUri (via HttpRequest.fromRawHeaders) ----

    "fromUri" - {

        "path only" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Absent)
            assert(req.httpUrl.host == "")
        }

        "path with query" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path?key=val", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Present("key=val"))
        }

        "path with fragment strips fragment" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path#frag", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Absent)
        }

        "path with query and fragment" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path?q=1#frag", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Present("q=1"))
        }

        "fragment before query marker means no query" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path#frag?notquery", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Absent)
        }

        "query only defaults path to /" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "?q=1", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/")
            assert(req.httpUrl.rawQuery == Present("q=1"))
        }

        "empty string defaults path to /" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/")
            assert(req.httpUrl.rawQuery == Absent)
        }

        "empty query after ? is Absent" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path?", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/path")
            assert(req.httpUrl.rawQuery == Absent)
        }

        "fragment only defaults path to /" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "#frag", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawPath == "/")
            assert(req.httpUrl.rawQuery == Absent)
        }

        "multiple query params preserved" in {
            val req = HttpRequest.fromRawHeaders(HttpRequest.Method.GET, "/path?a=1&b=2&c=3", HttpHeaders.empty, Array.empty)
            assert(req.httpUrl.rawQuery == Present("a=1&b=2&c=3"))
        }

        "also works via fromRaw" in {
            val req = HttpRequest.fromRaw(HttpRequest.Method.POST, "/api?x=1", HttpHeaders.empty, "body".getBytes, Absent)
            assert(req.httpUrl.rawPath == "/api")
            assert(req.httpUrl.rawQuery == Present("x=1"))
        }
    }

    // ---- fromHostHeader (via HttpRequest server-side construction) ----

    "fromHostHeader" - {

        "absent host" in {
            val url = HttpUrl.fromHostHeader(Absent, Absent, "/path", Present("q=1"))
            assert(url.host == "")
            assert(url.port == 80)
            assert(url.rawPath == "/path")
            assert(url.rawQuery == Present("q=1"))
        }

        "simple host" in {
            val url = HttpUrl.fromHostHeader(Present("example.com"), Absent, "/path", Absent)
            assert(url.host == "example.com")
            assert(url.port == 80)
        }

        "host with port" in {
            val url = HttpUrl.fromHostHeader(Present("example.com:8080"), Absent, "/path", Absent)
            assert(url.host == "example.com")
            assert(url.port == 8080)
        }

        "host without port defaults to 80 for http" in {
            val url = HttpUrl.fromHostHeader(Present("example.com"), Absent, "/path", Absent)
            assert(url.port == 80)
        }

        "host without port defaults to 443 for https" in {
            val url = HttpUrl.fromHostHeader(Present("example.com"), Present("https"), "/path", Absent)
            assert(url.port == 443)
        }

        "IPv6 with port" in {
            val url = HttpUrl.fromHostHeader(Present("[::1]:9090"), Absent, "/path", Absent)
            assert(url.host == "::1")
            assert(url.port == 9090)
        }

        "IPv6 without port" in {
            val url = HttpUrl.fromHostHeader(Present("[::1]"), Absent, "/path", Absent)
            assert(url.host == "::1")
            assert(url.port == 80)
        }

        "IPv6 without port with https" in {
            val url = HttpUrl.fromHostHeader(Present("[::1]"), Present("https"), "/path", Absent)
            assert(url.host == "::1")
            assert(url.port == 443)
        }

        "IPv6 malformed (no closing bracket)" in {
            val url = HttpUrl.fromHostHeader(Present("[::1"), Absent, "/path", Absent)
            assert(url.host == "[::1")
            assert(url.port == 80)
        }

        "preserves rawPath and rawQuery" in {
            val url = HttpUrl.fromHostHeader(Present("example.com"), Absent, "/api/v1", Present("page=2"))
            assert(url.rawPath == "/api/v1")
            assert(url.rawQuery == Present("page=2"))
        }
    }

    // ---- effective ----

    "effective" - {

        "no baseUrl uses request httpUrl" in {
            val req = HttpRequest.get("/path")
            val url = HttpUrl.effective(Absent, req)
            assert(url.rawPath == "/path")
        }

        "baseUrl resolves relative path" in {
            val req = HttpRequest.get("/api/users")
            val url = HttpUrl.effective(Present("http://example.com"), req)
            assert(url.host == "example.com")
            assert(url.rawPath == "/api/users")
        }

        "baseUrl with absolute URL in request returns request URL" in {
            val req = HttpRequest.get("http://other.com/path")
            val url = HttpUrl.effective(Present("http://example.com"), req)
            assert(url.host == "other.com")
            assert(url.rawPath == "/path")
        }

        "baseUrl adds leading slash if missing" in {
            val req = HttpRequest.get("api/users")
            val url = HttpUrl.effective(Present("http://example.com"), req)
            assert(url.rawPath == "/api/users")
        }

        "baseUrl with trailing slash" in {
            val req = HttpRequest.get("/api/users")
            val url = HttpUrl.effective(Present("http://example.com/"), req)
            assert(url.host == "example.com")
            assert(url.rawPath == "/api/users")
        }

        "baseUrl with path component" in {
            val req = HttpRequest.get("/users")
            val url = HttpUrl.effective(Present("http://example.com/base"), req)
            assert(url.host == "example.com")
            assert(url.rawPath == "/users")
        }

        "request with host skips baseUrl resolution" in {
            val req = HttpRequest.get("http://actual.com/path")
            val url = HttpUrl.effective(Present("http://base.com"), req)
            assert(url.host == "actual.com")
            assert(url.rawPath == "/path")
        }

        "request with query and baseUrl" in {
            val req = HttpRequest.get("/api?page=1")
            val url = HttpUrl.effective(Present("http://example.com"), req)
            assert(url.rawPath == "/api")
            assert(url.rawQuery == Present("page=1"))
        }
    }

end HttpUrlTest
