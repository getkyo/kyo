package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*

class CdpTypesTest extends kyo.Test:

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
