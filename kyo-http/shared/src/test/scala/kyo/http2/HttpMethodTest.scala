package kyo.http2

import kyo.Test

class HttpMethodTest extends Test:

    "constants" - {
        "GET" in {
            assert(HttpMethod.GET.name == "GET")
        }

        "POST" in {
            assert(HttpMethod.POST.name == "POST")
        }

        "PUT" in {
            assert(HttpMethod.PUT.name == "PUT")
        }

        "PATCH" in {
            assert(HttpMethod.PATCH.name == "PATCH")
        }

        "DELETE" in {
            assert(HttpMethod.DELETE.name == "DELETE")
        }

        "HEAD" in {
            assert(HttpMethod.HEAD.name == "HEAD")
        }

        "OPTIONS" in {
            assert(HttpMethod.OPTIONS.name == "OPTIONS")
        }

        "TRACE" in {
            assert(HttpMethod.TRACE.name == "TRACE")
        }

        "CONNECT" in {
            assert(HttpMethod.CONNECT.name == "CONNECT")
        }
    }

    "unsafe" - {
        "creates method from string" in {
            val m = HttpMethod.unsafe("CUSTOM")
            assert(m.name == "CUSTOM")
        }

        "round-trips standard method names" in {
            assert(HttpMethod.unsafe("GET") == HttpMethod.GET)
            assert(HttpMethod.unsafe("POST") == HttpMethod.POST)
        }

        "preserves case" in {
            val m = HttpMethod.unsafe("get")
            assert(m.name == "get")
            assert(m != HttpMethod.GET)
        }

        "allows empty string" in {
            val m = HttpMethod.unsafe("")
            assert(m.name == "")
        }
    }

    "equality" - {
        "same methods are equal" in {
            assert(HttpMethod.GET == HttpMethod.GET)
            assert(HttpMethod.POST == HttpMethod.POST)
        }

        "different methods are not equal" in {
            assert(HttpMethod.GET != HttpMethod.POST)
            assert(HttpMethod.PUT != HttpMethod.PATCH)
        }

        "unsafe-created method equals constant with same name" in {
            assert(HttpMethod.unsafe("DELETE") == HttpMethod.DELETE)
        }

        "different case is not equal" in {
            assert(HttpMethod.unsafe("get") != HttpMethod.GET)
        }
    }

    "name extension" - {
        "returns underlying string" in {
            assert(HttpMethod.GET.name == "GET")
            assert(HttpMethod.unsafe("CUSTOM").name == "CUSTOM")
        }
    }

end HttpMethodTest
