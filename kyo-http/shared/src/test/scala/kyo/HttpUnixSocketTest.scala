package kyo

import kyo.*

class HttpUnixSocketUrlTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    // ── HttpUrl parsing (unit tests, no server needed) ──────────────────────

    "HttpUrl parsing" - {

        "http+unix scheme normalizes to http" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
        }

        "https+unix scheme normalizes to https" in {
            val url = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("https"))
        }

        "http+unix url.ssl returns false" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(!url.ssl)
        }

        "https+unix url.ssl returns true" in {
            val url = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.ssl)
        }

        "socket path URL-decoded correctly" in {
            val url = HttpUrl.parse("http+unix://%2Fvar%2Frun%2Fdocker.sock/v1/info").getOrThrow
            assert(url.unixSocket == Present("/var/run/docker.sock"))
        }

        "host defaults to localhost" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.host == "localhost")
        }

        "port defaults to 80 for http, 443 for https" in {
            val httpUrl = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(httpUrl.port == 80)

            val httpsUrl = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(httpsUrl.port == 443)
        }

        "path extracted correctly after authority" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/containers/json").getOrThrow
            assert(url.path == "/v1/containers/json")
        }

        "query parameters preserved" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val&a=b").getOrThrow
            assert(url.rawQuery == Present("key=val&a=b"))
            assert(url.query("key") == Present("val"))
            assert(url.query("a") == Present("b"))
        }

        "full reconstructs the original http+unix URL" in {
            val url  = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val").getOrThrow
            val full = url.full
            assert(full.startsWith("http+unix://"))
            assert(full.contains("key=val"))
            // Parse the reconstructed URL and verify round-trip
            val reparsed = HttpUrl.parse(full).getOrThrow
            assert(reparsed.scheme == url.scheme)
            assert(reparsed.host == url.host)
            assert(reparsed.port == url.port)
            assert(reparsed.path == url.path)
            assert(reparsed.rawQuery == url.rawQuery)
            assert(reparsed.unixSocket == url.unixSocket)
        }

        "baseUrl reconstructs without query" in {
            val url  = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val").getOrThrow
            val base = url.baseUrl
            assert(base.startsWith("http+unix://"))
            assert(!base.contains("key=val"))
            assert(base.contains("/v1/info"))
        }

        "regular http URLs still work with unixSocket Absent" in {
            val url = HttpUrl.parse("http://example.com/path").getOrThrow
            assert(url.unixSocket == Absent)
            assert(url.host == "example.com")
            assert(url.port == 80)
            assert(url.scheme == Present("http"))
        }

        "invalid scheme rejected" in {
            val result = HttpUrl.parse("")
            assert(result.isFailure)
        }

        "no path after socket defaults to /" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock").getOrThrow
            assert(url.unixSocket == Present("/tmp/test.sock"))
            assert(url.path == "/")
        }

        "root path only" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/").getOrThrow
            assert(url.unixSocket == Present("/tmp/test.sock"))
            assert(url.path == "/")
        }

        "socket path with special chars" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Fmy-app.v2.sock/path").getOrThrow
            assert(url.unixSocket == Present("/tmp/my-app.v2.sock"))
        }

        "socket path with spaces" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Fmy%20app.sock/path").getOrThrow
            assert(url.unixSocket == Present("/tmp/my app.sock"))
        }

        "double-encoded slashes stay encoded" in {
            val url = HttpUrl.parse("http+unix://%252Ftmp%252Ftest.sock/path").getOrThrow
            assert(url.unixSocket == Present("%2Ftmp%2Ftest.sock"))
        }

        "empty socket path" in {
            val url = HttpUrl.parse("http+unix:///path").getOrThrow
            assert(url.unixSocket == Present(""))
            assert(url.path == "/path")
        }

        "fragment is stripped from path" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path#frag").getOrThrow
            assert(url.path == "/path")
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "fragment after query" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path?k=v#frag").getOrThrow
            assert(url.path == "/path")
            assert(url.rawQuery == Present("k=v"))
        }

        "mixed case scheme" in {
            val url = HttpUrl.parse("HTTP+UNIX://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "Http+Unix mixed case" in {
            val url = HttpUrl.parse("Http+Unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "query without path" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock?key=val").getOrThrow
            assert(url.path == "/")
            assert(url.rawQuery == Present("key=val"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "deeply nested socket path" in {
            val url = HttpUrl.parse("http+unix://%2Fvar%2Frun%2Fapp%2Fsubdir%2Fmy.sock/api/v2").getOrThrow
            assert(url.unixSocket == Present("/var/run/app/subdir/my.sock"))
            assert(url.path == "/api/v2")
        }

        "round-trip preserves all fields" in {
            val original = "http+unix://%2Ftmp%2Ftest.sock/api?a=1&b=2"
            val url      = HttpUrl.parse(original).getOrThrow
            val reparsed = HttpUrl.parse(url.full).getOrThrow
            assert(reparsed.scheme == url.scheme)
            assert(reparsed.unixSocket == url.unixSocket)
            assert(reparsed.path == url.path)
            assert(reparsed.rawQuery == url.rawQuery)
            assert(reparsed.host == url.host)
            assert(reparsed.port == url.port)
        }
    }

end HttpUnixSocketUrlTest
