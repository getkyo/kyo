package kyo.ffi.it

import kyo.ffi.*

class ItEnumTest extends ItTestBase:

    private lazy val bindings = Ffi.load[ItEnumBindings]

    "color value roundtrip" in {
        assert(bindings.kyo_it_color_value(ItColor.Red) == 0)
    }

    "get color by index" in {
        assert(bindings.kyo_it_color_get(1) == ItColor.Green)
    }

    "next color" in {
        assert(bindings.kyo_it_next_color(ItColor.Green) == ItColor.Blue)
    }

    "enum identity" in {
        val color = bindings.kyo_it_color_get(bindings.kyo_it_color_value(ItColor.Red))
        assert(color == ItColor.Red)
    }

    "unknown value throws" in {
        interceptThrown[IllegalArgumentException] {
            bindings.kyo_it_color_get(999)
        }
    }

end ItEnumTest
