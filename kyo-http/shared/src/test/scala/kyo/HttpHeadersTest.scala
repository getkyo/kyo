package kyo

class HttpHeadersTest extends Test:

    "factories and basics" - {
        "empty has size 0" in {
            val h = HttpHeaders.empty
            assert(h.size == 0)
            assert(h.isEmpty)
            assert(!h.nonEmpty)
        }

        "single header has size 1" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(h.size == 1)
            assert(!h.isEmpty)
            assert(h.nonEmpty)
        }

        "multiple headers have correct size" in {
            val h = HttpHeaders.empty
                .add("A", "1")
                .add("B", "2")
                .add("C", "3")
            assert(h.size == 3)
        }

        "fromFlatArrayNoCopy rejects odd-length array" in {
            assertThrows[IllegalArgumentException] {
                HttpHeaders.fromFlatArrayNoCopy(Array("name"))
            }
        }
    }

    "get" - {
        "returns value for existing header" in {
            val h = HttpHeaders.empty.add("Content-Type", "text/plain")
            assert(h.get("Content-Type") == Present("text/plain"))
        }

        "case-insensitive lookup" in {
            val h = HttpHeaders.empty.add("Content-Type", "text/plain")
            assert(h.get("content-type") == Present("text/plain"))
            assert(h.get("CONTENT-TYPE") == Present("text/plain"))
        }

        "returns Absent for missing header" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(h.get("X-Bar") == Absent)
        }

        "returns first value when multiple headers with same name" in {
            val h = HttpHeaders.empty
                .add("X-Multi", "first")
                .add("X-Multi", "second")
                .add("X-Multi", "third")
            assert(h.get("X-Multi") == Present("first"))
        }

        "returns Absent on empty headers" in {
            assert(HttpHeaders.empty.get("Anything") == Absent)
        }
    }

    "getLast" - {
        "returns last value when multiple headers with same name" in {
            val h = HttpHeaders.empty
                .add("X-Multi", "first")
                .add("X-Multi", "second")
                .add("X-Multi", "third")
            assert(h.getLast("X-Multi") == Present("third"))
        }

        "returns Absent for missing header" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(h.getLast("X-Bar") == Absent)
        }

        "returns single value when only one exists" in {
            val h = HttpHeaders.empty.add("X-Single", "only")
            assert(h.getLast("X-Single") == Present("only"))
        }
    }

    "contains" - {
        "true for existing header" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(h.contains("X-Foo"))
        }

        "case-insensitive" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(h.contains("x-foo"))
            assert(h.contains("X-FOO"))
        }

        "false for missing header" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(!h.contains("X-Bar"))
        }
    }

    "exists" - {
        "matches on predicate" in {
            val h = HttpHeaders.empty
                .add("X-Foo", "bar")
                .add("X-Baz", "qux")
            assert(h.exists((name, value) => value == "qux"))
        }

        "returns false when no match" in {
            val h = HttpHeaders.empty.add("X-Foo", "bar")
            assert(!h.exists((_, value) => value == "missing"))
        }
    }

    "add vs set" - {
        "add preserves duplicate names" in {
            val h = HttpHeaders.empty
                .add("X-Dup", "first")
                .add("X-Dup", "second")
            assert(h.size == 2)
            assert(h.get("X-Dup") == Present("first"))
            assert(h.getLast("X-Dup") == Present("second"))
        }

        "set replaces existing header" in {
            val h = HttpHeaders.empty
                .add("X-Foo", "old")
                .set("X-Foo", "new")
            assert(h.size == 1)
            assert(h.get("X-Foo") == Present("new"))
        }

        "set on non-existing header appends" in {
            val h = HttpHeaders.empty
                .add("X-Existing", "value")
                .set("X-New", "added")
            assert(h.size == 2)
            assert(h.get("X-Existing") == Present("value"))
            assert(h.get("X-New") == Present("added"))
        }

        "set replaces all duplicates" in {
            val h = HttpHeaders.empty
                .add("X-Dup", "first")
                .add("X-Dup", "second")
                .add("X-Dup", "third")
                .set("X-Dup", "replaced")
            assert(h.size == 1)
            assert(h.get("X-Dup") == Present("replaced"))
        }
    }

    "remove" - {
        "removes all headers with matching name" in {
            val h = HttpHeaders.empty
                .add("X-Remove", "a")
                .add("X-Keep", "b")
                .add("X-Remove", "c")
                .remove("X-Remove")
            assert(h.size == 1)
            assert(!h.contains("X-Remove"))
            assert(h.get("X-Keep") == Present("b"))
        }

        "case-insensitive removal" in {
            val h = HttpHeaders.empty
                .add("Content-Type", "text/plain")
                .remove("content-type")
            assert(h.isEmpty)
        }

        "removing non-existent header returns equivalent headers" in {
            val h       = HttpHeaders.empty.add("X-Foo", "bar")
            val removed = h.remove("X-Missing")
            assert(removed.size == 1)
            assert(removed.get("X-Foo") == Present("bar"))
        }
    }

    "concat" - {
        "merges two non-empty header collections" in {
            val a      = HttpHeaders.empty.add("A", "1")
            val b      = HttpHeaders.empty.add("B", "2")
            val merged = a.concat(b)
            assert(merged.size == 2)
            assert(merged.get("A") == Present("1"))
            assert(merged.get("B") == Present("2"))
        }

        "concat with empty left returns right" in {
            val b      = HttpHeaders.empty.add("B", "2")
            val merged = HttpHeaders.empty.concat(b)
            assert(merged.size == 1)
            assert(merged.get("B") == Present("2"))
        }

        "concat with empty right returns left" in {
            val a      = HttpHeaders.empty.add("A", "1")
            val merged = a.concat(HttpHeaders.empty)
            assert(merged.size == 1)
            assert(merged.get("A") == Present("1"))
        }
    }

    "foreach" - {
        "iterates all name-value pairs" in {
            val h = HttpHeaders.empty
                .add("A", "1")
                .add("B", "2")
                .add("C", "3")
            var collected = List.empty[(String, String)]
            h.foreach((name, value) => collected = (name, value) :: collected)
            assert(collected.size == 3)
            assert(collected.reverse == List(("A", "1"), ("B", "2"), ("C", "3")))
        }

        "no-op on empty headers" in {
            var called = false
            HttpHeaders.empty.foreach((_, _) => called = true)
            assert(!called)
        }
    }

end HttpHeadersTest
