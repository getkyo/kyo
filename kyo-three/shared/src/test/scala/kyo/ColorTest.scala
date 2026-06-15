package kyo

class ColorTest extends kyo.test.Test[Any]:

    "hex parses a valid #rrggbb" in {
        assert(Color.hex("#ff0000") == Present(Color.red))
    }

    "hex parses a no-# body" in {
        assert(Color.hex("00ff00") == Present(Color.green))
    }

    "hex returns Absent on a malformed string and never throws" in {
        assert(Color.hex("not-a-color") == Absent)
    }

    "hex returns Absent on a wrong-length string" in {
        assert(Color.hex("#fff") == Absent)
    }

    "rgb clamps out-of-range channels" in {
        val c = Color.rgb(300, -5, 128)
        assert(c.r == 255)
        assert(c.g == 0)
        assert(c.b == 128)
        assert(c == Color.rgb(255, 0, 128))
    }

    "hsl produces the expected primary" in {
        assert(Color.hsl(0, 1.0, 0.5) == Color.red)
    }

end ColorTest
