package kyo.internal

class JsonWriterTest extends kyo.test.Test[Any]:

    "arrayStart nesting past 64-depth boundary round-trips" in {
        // 120 levels of array nesting: each arrayStart pushes depth by 1.
        // Depth 1..63 lives in word 0; depth 64..120 lives in word 1, so
        // needsComma must grow from length 1 to length 2 at depth 64.
        val depth = 120
        val w     = JsonWriter()
        var i     = 0
        while i < depth do
            w.arrayStart(1)
            i += 1
        w.int(42)
        i = 0
        while i < depth do
            w.arrayEnd()
            i += 1
        val json     = w.resultString
        val expected = "[" * depth + "42" + "]" * depth
        assert(json == expected, s"mismatch at depth=$depth")
    }

    "objectStart nesting past 64-depth boundary round-trips" in {
        // 100 levels of object nesting: each objectStart pushes depth.
        val depth = 100
        val w     = JsonWriter()
        var i     = 0
        while i < depth do
            w.objectStart("o", 1)
            w.field("k", 0)
            i += 1
        end while
        w.int(7)
        i = 0
        while i < depth do
            w.objectEnd()
            i += 1
        val json = w.resultString
        // Decode back as deeply-nested raw structure via Json.decode with
        // matching Schema is awkward; instead verify that the output starts
        // with the expected open-brace sequence and ends with close-braces.
        assert(json.startsWith("{\"k\":" * depth + "7"), s"unexpected prefix at depth=$depth: ${json.take(40)}...")
        assert(json.endsWith("}" * depth), s"unexpected suffix at depth=$depth: ...${json.takeRight(40)}")
    }

    "exact word boundary depth 64 is correct" in {
        // depth exactly 64 triggers the first grow (since initial size is 1 Long = slots 0-63).
        val depth = 64
        val w     = JsonWriter()
        var i     = 0
        while i < depth do
            w.arrayStart(1)
            i += 1
        w.int(1)
        w.int(2) // second element at depth 64; exercises setFlag/getFlag at slot 0 of word 1
        i = 0
        while i < depth do
            w.arrayEnd()
            i += 1
        val json = w.resultString
        // Innermost array has "1,2", wrapped by 63 additional "[...]"
        val expected = "[" * depth + "1,2" + "]" * depth
        assert(json == expected)
    }

    "word-boundary depth 65 is correct" in {
        // Guards against off-by-one in `depth & 63` on the second word.
        val depth = 65
        val w     = JsonWriter()
        var i     = 0
        while i < depth do
            w.arrayStart(1)
            i += 1
        w.int(1)
        w.int(2) // two comma-separated elements at depth 65, slot 1 of word 1
        i = 0
        while i < depth do
            w.arrayEnd()
            i += 1
        val json     = w.resultString
        val expected = "[" * depth + "1,2" + "]" * depth
        assert(json == expected)
    }
end JsonWriterTest
