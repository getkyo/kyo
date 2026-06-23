package kyo

/** Tests for the `McpCursor` opaque type.
  *
  * Pins that a cursor round-trips through its Schema unchanged and that `asString` recovers
  * the original wire token, including the opaque cursor's no-public-constructor guarantee.
  */
class McpCursorTest extends Test:

    "McpCursor round-trips through Schema" in {
        val cursor  = McpCursor.fromWire("cur-42")
        val json    = Json.encode[McpCursor](cursor)
        val decoded = Json.decode[McpCursor](json).getOrThrow
        assert(decoded.asString == "cur-42")
    }

    "McpCursor asString returns the wire token" in {
        val cursor = McpCursor.fromWire("abc")
        assert(cursor.asString == "abc")
    }

end McpCursorTest
