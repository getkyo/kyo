package kyo.internal

import java.net.URI
import kyo.Maybe
import kyo.Maybe.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class UrlParserTest extends AnyFreeSpec with NonImplicitAssertions:

    private def maybeFromNullable(s: String | Null): Maybe[String] =
        if s == null || s.isEmpty then Absent else Present(s)

    /** Compare UrlParser.parseUrlParts output against java.net.URI for the given URL.
      *
      * Note: URI returns "" for path when no path is present (e.g. "http://example.com"), while UrlParser normalizes to "/". This is the
      * only intentional difference.
      */
    private def assertMatchesUri(url: String): Unit =
        val uri = new URI(url)
        UrlParser.parseUrlParts(url) { (scheme, host, port, path, query) =>
            assert(scheme == maybeFromNullable(uri.getScheme), s"scheme mismatch for: $url")
            assert(host == maybeFromNullable(uri.getHost), s"host mismatch for: $url")
            if uri.getPort != -1 then
                assert(port == uri.getPort, s"port mismatch for: $url"): Unit
            val expectedPath = if uri.getRawPath == null || uri.getRawPath.isEmpty then "/" else uri.getRawPath
            assert(path == expectedPath, s"path mismatch for: $url")
            assert(query == maybeFromNullable(uri.getRawQuery), s"query mismatch for: $url")
            ()
        }
    end assertMatchesUri

    /** Compare UrlParser.splitPathQuery output against java.net.URI for path+query extraction. */
    private def assertSplitMatchesUri(url: String): Unit =
        val uri = new URI(url)
        UrlParser.splitPathQuery(url) { (path, query) =>
            val expectedPath = if uri.getRawPath == null || uri.getRawPath.isEmpty then "/" else uri.getRawPath
            assert(path == expectedPath, s"splitPathQuery path mismatch for: $url")
            assert(query == maybeFromNullable(uri.getRawQuery), s"splitPathQuery query mismatch for: $url")
            ()
        }
    end assertSplitMatchesUri

    "parseUrlParts" - {

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
                UrlParser.parseUrlParts("http://example.com/path") { (_, _, port, _, _) =>
                    assert(port == 80)
                }
            }

            "default port for https is 443" in {
                UrlParser.parseUrlParts("https://example.com/path") { (_, _, port, _, _) =>
                    assert(port == 443)
                }
            }

            "explicit port overrides default" in {
                UrlParser.parseUrlParts("http://example.com:3000/path") { (_, _, port, _, _) =>
                    assert(port == 3000)
                }
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
                assertMatchesUri("http://[::1]:8080/path")
            }

            "IPv6 without port" in {
                assertMatchesUri("http://[::1]/path")
            }

            "IPv6 full address with port" in {
                assertMatchesUri("http://[2001:db8::1]:8080/path?q=1#frag")
            }

            "IPv6 loopback root" in {
                assertMatchesUri("http://[::1]/")
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
                UrlParser.parseUrlParts("/just/a/path") { (scheme, host, port, path, query) =>
                    assert(scheme == Absent)
                    assert(host == Absent)
                    assert(port == -1)
                    assert(path == "/just/a/path")
                    assert(query == Absent)
                }
            }

            "path with query" in {
                UrlParser.parseUrlParts("/path?key=val") { (scheme, host, port, path, query) =>
                    assert(scheme == Absent)
                    assert(host == Absent)
                    assert(port == -1)
                    assert(path == "/path")
                    assert(query == Present("key=val"))
                }
            }

            "path with fragment" in {
                UrlParser.parseUrlParts("/path#frag") { (scheme, host, port, path, query) =>
                    assert(scheme == Absent)
                    assert(host == Absent)
                    assert(path == "/path")
                    assert(query == Absent)
                }
            }

            "path with query and fragment" in {
                UrlParser.parseUrlParts("/path?q=1#frag") { (scheme, host, port, path, query) =>
                    assert(scheme == Absent)
                    assert(host == Absent)
                    assert(path == "/path")
                    assert(query == Present("q=1"))
                }
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
                // %2520 means literal %20 (the % is encoded as %25)
                assertMatchesUri("http://example.com/path?q=%2520")
            }

            "query with plus sign (literal)" in {
                // + in URLs is literal per RFC 3986 (only means space in form-encoded)
                assertMatchesUri("http://example.com/search?q=hello+world")
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
                UrlParser.parseUrlParts("http://example.com#frag") { (scheme, host, port, path, query) =>
                    assert(scheme == Present("http"))
                    assert(host == Present("example.com"))
                    assert(path == "/")
                    assert(query == Absent)
                }
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
                // ? after # is not a query separator
                assertMatchesUri("http://example.com/path?q=a%23b")
            }
        }
    }

    "splitPathQuery" - {

        "basic cases" - {

            "path only" in {
                assertSplitMatchesUri("http://example.com/path")
            }

            "path with query" in {
                assertSplitMatchesUri("http://example.com/path?q=1")
            }
        }

        "path-only strings" - {

            "simple path" in {
                UrlParser.splitPathQuery("/foo/bar") { (path, query) =>
                    assert(path == "/foo/bar")
                    assert(query == Absent)
                }
            }

            "path with query" in {
                UrlParser.splitPathQuery("/foo?bar=baz") { (path, query) =>
                    assert(path == "/foo")
                    assert(query == Present("bar=baz"))
                }
            }

            "empty string defaults to /" in {
                UrlParser.splitPathQuery("") { (path, query) =>
                    assert(path == "/")
                    assert(query == Absent)
                }
            }

            "query-only defaults path to /" in {
                UrlParser.splitPathQuery("?q=1") { (path, query) =>
                    assert(path == "/")
                    assert(query == Present("q=1"))
                }
            }
        }

        "fragment stripping" - {

            "path with fragment" in {
                UrlParser.splitPathQuery("/foo#section") { (path, query) =>
                    assert(path == "/foo")
                    assert(query == Absent)
                }
            }

            "query with fragment" in {
                UrlParser.splitPathQuery("/foo?bar=1#section") { (path, query) =>
                    assert(path == "/foo")
                    assert(query == Present("bar=1"))
                }
            }
        }

        "scheme+authority stripping" - {

            "full URL" in {
                UrlParser.splitPathQuery("http://example.com/path?q=1") { (path, query) =>
                    assert(path == "/path")
                    assert(query == Present("q=1"))
                }
            }

            "host-only URL with query" in {
                UrlParser.splitPathQuery("http://example.com?q=1") { (path, query) =>
                    assert(path == "/")
                    assert(query == Present("q=1"))
                }
            }

            "host-only URL without path" in {
                UrlParser.splitPathQuery("http://example.com") { (path, query) =>
                    assert(path == "/")
                    assert(query == Absent)
                }
            }
        }
    }

end UrlParserTest
