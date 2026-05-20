package kyo

class HttpQueryParamsTest extends Test:

    "empty" - {
        "is empty" in {
            assert(HttpQueryParams.empty.isEmpty)
        }
        "has size 0" in {
            assert(HttpQueryParams.empty.size == 0)
        }
        "nonEmpty is false" in {
            assert(!HttpQueryParams.empty.nonEmpty)
        }
        "toQueryString is empty string" in {
            assert(HttpQueryParams.empty.toQueryString == "")
        }
        "toSeq is Nil" in {
            assert(HttpQueryParams.empty.toSeq == Nil)
        }
        "get returns Absent" in {
            assert(HttpQueryParams.empty.get("x") == Absent)
        }
        "getAll returns empty Seq" in {
            assert(HttpQueryParams.empty.getAll("x") == Seq.empty)
        }
    }

    "init(name, value)" - {
        "creates single param" in {
            val q = HttpQueryParams.init("key", "value")
            assert(q.size == 1)
            assert(q.toSeq == Seq(("key", "value")))
        }
        "get finds the param" in {
            val q = HttpQueryParams.init("foo", "bar")
            assert(q.get("foo") == Present("bar"))
        }
        "get returns Absent for missing name" in {
            val q = HttpQueryParams.init("foo", "bar")
            assert(q.get("baz") == Absent)
        }
    }

    "init(varargs)" - {
        "creates empty from no args" in {
            val q = HttpQueryParams.init()
            assert(q.isEmpty)
        }
        "creates single param from one tuple" in {
            val q = HttpQueryParams.init(("a", "1"))
            assert(q.toSeq == Seq(("a", "1")))
        }
        "creates multiple params" in {
            val q = HttpQueryParams.init(("a", "1"), ("b", "2"), ("c", "3"))
            assert(q.size == 3)
            assert(q.toSeq == Seq(("a", "1"), ("b", "2"), ("c", "3")))
        }
        "preserves insertion order" in {
            val q = HttpQueryParams.init(("z", "last"), ("a", "first"))
            assert(q.toSeq.head == ("z", "last"))
            assert(q.toSeq.last == ("a", "first"))
        }
    }

    "add" - {
        "appends to empty" in {
            val q = HttpQueryParams.empty.add("k", "v")
            assert(q.toSeq == Seq(("k", "v")))
        }
        "appends to existing" in {
            val q = HttpQueryParams.init("a", "1").add("b", "2")
            assert(q.toSeq == Seq(("a", "1"), ("b", "2")))
        }
        "allows duplicate keys" in {
            val q = HttpQueryParams.init("tag", "a").add("tag", "b")
            assert(q.size == 2)
            assert(q.toSeq == Seq(("tag", "a"), ("tag", "b")))
        }
    }

    "get" - {
        "finds first matching name" in {
            val q = HttpQueryParams.init(("tag", "a"), ("tag", "b"))
            assert(q.get("tag") == Present("a"))
        }
        "returns Absent for missing name" in {
            val q = HttpQueryParams.init(("x", "1"))
            assert(q.get("y") == Absent)
        }
        "is case-sensitive" in {
            val q = HttpQueryParams.init("Key", "value")
            assert(q.get("key") == Absent)
            assert(q.get("Key") == Present("value"))
        }
    }

    "getAll" - {
        "finds all matching names" in {
            val q = HttpQueryParams.init(("tag", "a"), ("other", "x"), ("tag", "b"), ("tag", "c"))
            assert(q.getAll("tag") == Seq("a", "b", "c"))
        }
        "returns empty Seq for missing name" in {
            val q = HttpQueryParams.init(("x", "1"))
            assert(q.getAll("y") == Seq.empty)
        }
        "returns single element when one match" in {
            val q = HttpQueryParams.init(("k", "v"))
            assert(q.getAll("k") == Seq("v"))
        }
    }

    "toQueryString" - {
        "single param" in {
            val q = HttpQueryParams.init("key", "value")
            assert(q.toQueryString == "key=value")
        }
        "multiple params joined with &" in {
            val q = HttpQueryParams.init(("a", "1"), ("b", "2"))
            assert(q.toQueryString == "a=1&b=2")
        }
        "URL-encodes spaces in keys and values" in {
            val q = HttpQueryParams.init("my key", "my value")
            assert(q.toQueryString == "my%20key=my%20value")
        }
        "URL-encodes special characters" in {
            val q  = HttpQueryParams.init("q", "hello world&foo=bar")
            val qs = q.toQueryString
            assert(!qs.contains(" "))
            assert(!qs.contains("&foo=bar"))
        }
        "URL-encodes percent sign" in {
            val q = HttpQueryParams.init("p", "50%")
            assert(q.toQueryString == "p=50%25")
        }
        "preserves duplicate keys" in {
            val q = HttpQueryParams.init(("tag", "a"), ("tag", "b"))
            assert(q.toQueryString == "tag=a&tag=b")
        }
    }

    "foreach" - {
        "iterates over all pairs" in {
            val q      = HttpQueryParams.init(("a", "1"), ("b", "2"))
            val result = collection.mutable.ArrayBuffer[(String, String)]()
            q.foreach((k, v) => discard(result += ((k, v))))
            assert(result.toSeq == Seq(("a", "1"), ("b", "2")))
        }
        "does nothing on empty" in {
            var count = 0
            HttpQueryParams.empty.foreach((_, _) => count += 1)
            assert(count == 0)
        }
    }

end HttpQueryParamsTest
