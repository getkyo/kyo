package kyo.http2

import kyo.Absent
import kyo.Present
import kyo.Record2
import kyo.Record2.~
import kyo.Result
import kyo.Test

class HttpRequestTest extends Test:

    "construction" - {
        "from method and HttpUrl" in {
            val url = HttpUrl.parse("https://example.com/path").getOrThrow
            val req = HttpRequest(HttpMethod.GET, url)
            assert(req.method == HttpMethod.GET)
            assert(req.url == url)
            assert(req.headers.isEmpty)
            assert(req.fields == Record2.empty)
        }

        "full constructor" in {
            val url     = HttpUrl.parse("https://example.com").getOrThrow
            val headers = HttpHeaders.empty.add("X-Test", "1")
            val fields  = "key" ~ "val"
            val req     = HttpRequest(HttpMethod.POST, url, headers, fields)
            assert(req.method == HttpMethod.POST)
            assert(req.headers.get("X-Test") == Present("1"))
            assert(req.fields.key == "val")
        }
    }

    "parse" - {
        "valid URL" in {
            val result = HttpRequest.parse(HttpMethod.GET, "https://example.com/api")
            assert(result.isSuccess)
            val req = result.getOrThrow
            assert(req.method == HttpMethod.GET)
            assert(req.url.host == "example.com")
            assert(req.url.path == "/api")
        }

        "invalid URL" in {
            val result = HttpRequest.parse(HttpMethod.GET, "")
            assert(result.isFailure)
        }

        "preserves query string" in {
            val result = HttpRequest.parse(HttpMethod.GET, "https://example.com/search?q=hello&page=2")
            assert(result.isSuccess)
            val req = result.getOrThrow
            assert(req.query("q") == Present("hello"))
            assert(req.query("page") == Present("2"))
        }
    }

    "HttpUrl factory methods" - {
        "get" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            val req = HttpRequest.get(url)
            assert(req.method == HttpMethod.GET)
            assert(req.url == url)
        }

        "post" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            val req = HttpRequest.post(url)
            assert(req.method == HttpMethod.POST)
        }

        "put" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            assert(HttpRequest.put(url).method == HttpMethod.PUT)
        }

        "patch" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            assert(HttpRequest.patch(url).method == HttpMethod.PATCH)
        }

        "delete" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            assert(HttpRequest.delete(url).method == HttpMethod.DELETE)
        }

        "head" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            assert(HttpRequest.head(url).method == HttpMethod.HEAD)
        }

        "options" in {
            val url = HttpUrl.parse("https://example.com").getOrThrow
            assert(HttpRequest.options(url).method == HttpMethod.OPTIONS)
        }
    }

    "string factory methods" - {
        "get" in {
            val result = HttpRequest.get("https://example.com/items")
            assert(result.isSuccess)
            val req = result.getOrThrow
            assert(req.method == HttpMethod.GET)
            assert(req.url.path == "/items")
        }

        "post" in {
            val result = HttpRequest.post("https://example.com/items")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.POST)
        }

        "put" in {
            val result = HttpRequest.put("https://example.com/items/1")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.PUT)
        }

        "patch" in {
            val result = HttpRequest.patch("https://example.com/items/1")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.PATCH)
        }

        "delete" in {
            val result = HttpRequest.delete("https://example.com/items/1")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.DELETE)
        }

        "head" in {
            val result = HttpRequest.head("https://example.com")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.HEAD)
        }

        "options" in {
            val result = HttpRequest.options("https://example.com")
            assert(result.isSuccess)
            assert(result.getOrThrow.method == HttpMethod.OPTIONS)
        }

        "returns failure for invalid URL" in {
            val result = HttpRequest.get("")
            assert(result.isFailure)
        }

        "preserves query parameters" in {
            val result = HttpRequest.get("https://example.com?key=value")
            assert(result.isSuccess)
            assert(result.getOrThrow.query("key") == Present("value"))
        }

        "preserves path" in {
            val result = HttpRequest.post("https://example.com/a/b/c")
            assert(result.isSuccess)
            assert(result.getOrThrow.path == "/a/b/c")
        }
    }

    "path delegation" in {
        val url = HttpUrl.fromUri("/users/123")
        val req = HttpRequest(HttpMethod.GET, url)
        assert(req.path == "/users/123")
    }

    "query delegation" - {
        "present" in {
            val url = HttpUrl.fromUri("/search?q=hello")
            val req = HttpRequest(HttpMethod.GET, url)
            assert(req.query("q") == Present("hello"))
        }

        "absent" in {
            val url = HttpUrl.fromUri("/search")
            val req = HttpRequest(HttpMethod.GET, url)
            assert(req.query("q") == Absent)
        }
    }

    "queryAll delegation" in {
        val url = HttpUrl.fromUri("/search?tag=a&tag=b&tag=c")
        val req = HttpRequest(HttpMethod.GET, url)
        assert(req.queryAll("tag") == Seq("a", "b", "c"))
    }

    "addField" in {
        val url = HttpUrl.fromUri("/")
        val req = HttpRequest(HttpMethod.GET, url).addField("count", 42)
        assert(req.fields.count == 42)
        assert(req.method == HttpMethod.GET)
    }

    "addFields" in {
        val url    = HttpUrl.fromUri("/")
        val fields = "a" ~ 1 & "b" ~ "two"
        val req    = HttpRequest(HttpMethod.GET, url).addFields(fields)
        assert(req.fields.a == 1)
        assert(req.fields.b == "two")
    }

    "addHeader" in {
        val url = HttpUrl.fromUri("/")
        val req = HttpRequest(HttpMethod.GET, url)
            .addHeader("X-A", "1")
            .addHeader("X-A", "2")
        assert(req.headers.getAll("X-A") == Seq("1", "2"))
    }

    "setHeader" in {
        val url = HttpUrl.fromUri("/")
        val req = HttpRequest(HttpMethod.GET, url)
            .addHeader("X-A", "1")
            .setHeader("X-A", "replaced")
        assert(req.headers.get("X-A") == Present("replaced"))
        assert(req.headers.getAll("X-A") == Seq("replaced"))
    }

    "immutability" in {
        val url  = HttpUrl.fromUri("/")
        val req1 = HttpRequest(HttpMethod.GET, url)
        val req2 = req1.addHeader("X-Test", "value")
        val req3 = req1.addField("key", "val")
        assert(req1.headers.isEmpty)
        assert(req1.fields == Record2.empty)
        assert(req2.headers.get("X-Test") == Present("value"))
        assert(req3.fields.key == "val")
    }

end HttpRequestTest
