package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*

class CdpTypesTest extends kyo.BaseBrowserTest:

    "special numbers round-trip through CDP unserializableValue" in {
        Chunk(
            (Double.NaN, "NaN"),
            (-0.0, "-0"),
            (Double.PositiveInfinity, "Infinity"),
            (Double.NegativeInfinity, "-Infinity")
        ).foreach { (value, literal) =>
            val remote: RemoteObject = RemoteObject.`number`(value)
            val json                 = s"""{"type":"number","unserializableValue":"$literal"}"""
            assert(Json.encode(remote) == json)
            assert(Json.decode[RemoteObject](json).map(_.text) == Result.succeed(literal))
        }
    }

    "bigints retain every digit in their wire literal" in {
        val json                 = """{"type":"bigint","unserializableValue":"9007199254740993n"}"""
        val remote: RemoteObject = RemoteObject.`bigint`("9007199254740993n")
        assert(Json.encode(remote) == json)
        assert(Json.decode[RemoteObject](json) == Result.succeed(remote))
    }

    "numbers reject missing contradictory and non-finite JSON values" in {
        Chunk(
            """{"type":"number"}""",
            """{"type":"number","value":3,"unserializableValue":"NaN"}""",
            """{"type":"number","unserializableValue":"nan"}""",
            """{"type":"number","value":"3"}""",
            """{"type":"number","value":"NaN"}""",
            """{"type":"number","value":"Infinity"}""",
            """{"type":"number","value":"-Infinity"}""",
            """{"type":"number","value":1e309}"""
        ).foreach(json => assert(Json.decode[RemoteObject](json).isFailure, s"accepted $json"))
    }

    "MouseEventType wire values" - {
        "Moved.wire == \"mouseMoved\"" in {
            assert(MouseEventType.Moved.wire == "mouseMoved")
        }
        "Pressed.wire == \"mousePressed\"" in {
            assert(MouseEventType.Pressed.wire == "mousePressed")
        }
        "Released.wire == \"mouseReleased\"" in {
            assert(MouseEventType.Released.wire == "mouseReleased")
        }
    }

    "KeyEventType wire values" - {
        "Down.wire == \"keyDown\"" in {
            assert(KeyEventType.Down.wire == "keyDown")
        }
        "Up.wire == \"keyUp\"" in {
            assert(KeyEventType.Up.wire == "keyUp")
        }
    }

end CdpTypesTest
