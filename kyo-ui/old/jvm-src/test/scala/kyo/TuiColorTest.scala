package kyo

import kyo.Style.Color
import kyo.internal.TuiColor

class TuiColorTest extends Test:

    "pack and unpack" - {
        "round-trips RGB components" in {
            val c = TuiColor.pack(0x12, 0x34, 0x56)
            assert(TuiColor.r(c) == 0x12)
            assert(TuiColor.g(c) == 0x34)
            assert(TuiColor.b(c) == 0x56)
            succeed
        }
        "black" in {
            val c = TuiColor.pack(0, 0, 0)
            assert(c == 0)
            assert(TuiColor.r(c) == 0)
            assert(TuiColor.g(c) == 0)
            assert(TuiColor.b(c) == 0)
            succeed
        }
        "white" in {
            val c = TuiColor.pack(255, 255, 255)
            assert(TuiColor.r(c) == 255)
            assert(TuiColor.g(c) == 255)
            assert(TuiColor.b(c) == 255)
            succeed
        }
    }

    "resolve" - {
        "Rgb" in {
            val c = TuiColor.resolve(Color.rgb(100, 200, 50))
            assert(TuiColor.r(c) == 100)
            assert(TuiColor.g(c) == 200)
            assert(TuiColor.b(c) == 50)
            succeed
        }
        "Rgba ignores alpha" in {
            val c = TuiColor.resolve(Color.rgba(10, 20, 30, 0.5))
            assert(TuiColor.r(c) == 10)
            assert(TuiColor.g(c) == 20)
            assert(TuiColor.b(c) == 30)
            succeed
        }
        "Hex 6-char" in {
            val c = TuiColor.resolve(Color.hex("#ff8800"))
            assert(TuiColor.r(c) == 0xff)
            assert(TuiColor.g(c) == 0x88)
            assert(TuiColor.b(c) == 0x00)
            succeed
        }
        "Hex 3-char shorthand" in {
            val c = TuiColor.resolve(Color.hex("#f80"))
            assert(TuiColor.r(c) == 0xff)
            assert(TuiColor.g(c) == 0x88)
            assert(TuiColor.b(c) == 0x00)
            succeed
        }
        "Hex white shorthand" in {
            val c = TuiColor.resolve(Color.hex("#fff"))
            assert(TuiColor.r(c) == 0xff)
            assert(TuiColor.g(c) == 0xff)
            assert(TuiColor.b(c) == 0xff)
            succeed
        }
        "Hex black" in {
            val c = TuiColor.resolve(Color.hex("#000000"))
            assert(c == 0)
            succeed
        }
        "transparent returns Absent" in {
            assert(TuiColor.resolve(Color.hex("transparent")) == TuiColor.Absent)
            succeed
        }
    }

    "blend" - {
        "alpha 1.0 returns src" in {
            val src = TuiColor.pack(255, 0, 0)
            val dst = TuiColor.pack(0, 0, 255)
            assert(TuiColor.blend(src, dst, 1.0f) == src)
            succeed
        }
        "alpha 0.0 returns dst" in {
            val src = TuiColor.pack(255, 0, 0)
            val dst = TuiColor.pack(0, 0, 255)
            assert(TuiColor.blend(src, dst, 0.0f) == dst)
            succeed
        }
        "alpha 0.5 mixes evenly" in {
            val src = TuiColor.pack(200, 100, 0)
            val dst = TuiColor.pack(0, 100, 200)
            val c   = TuiColor.blend(src, dst, 0.5f)
            assert(TuiColor.r(c) == 100)
            assert(TuiColor.g(c) == 100)
            assert(TuiColor.b(c) == 100)
            succeed
        }
        "Absent src returns dst" in {
            val dst = TuiColor.pack(50, 60, 70)
            assert(TuiColor.blend(TuiColor.Absent, dst, 0.5f) == dst)
            succeed
        }
        "Absent dst returns src" in {
            val src = TuiColor.pack(50, 60, 70)
            assert(TuiColor.blend(src, TuiColor.Absent, 0.5f) == src)
            succeed
        }
    }

    "to256" - {
        "black maps to cube black" in {
            val idx = TuiColor.to256(TuiColor.pack(0, 0, 0))
            assert(idx == 16) // cube index 0,0,0 = 16
            succeed
        }
        "white maps to cube white" in {
            val idx = TuiColor.to256(TuiColor.pack(255, 255, 255))
            assert(idx == 231) // cube index 5,5,5 = 16+36*5+6*5+5 = 231
            succeed
        }
        "pure red" in {
            val idx = TuiColor.to256(TuiColor.pack(255, 0, 0))
            assert(idx == 196) // 16 + 36*5 + 0 + 0 = 196
            succeed
        }
        "gray picks grayscale ramp when closer" in {
            val idx = TuiColor.to256(TuiColor.pack(128, 128, 128))
            // Gray 128 → nearest gray index: (128-8+5)/10 = 12 → value 128
            // Cube: nearestCube(128) = (128-35)/40 = 2 → 0x87=135, dist = 3*49=147
            // Gray: index 12, value 128, dist = 0
            assert(idx == 244) // 232 + 12
            succeed
        }
    }

    "to16" - {
        "black" in {
            assert(TuiColor.to16(TuiColor.pack(0, 0, 0)) == 0)
            succeed
        }
        "white" in {
            assert(TuiColor.to16(TuiColor.pack(255, 255, 255)) == 15)
            succeed
        }
        "pure red" in {
            assert(TuiColor.to16(TuiColor.pack(255, 0, 0)) == 9) // bright red
            succeed
        }
        "pure green" in {
            assert(TuiColor.to16(TuiColor.pack(0, 255, 0)) == 10) // bright green
            succeed
        }
        "pure blue" in {
            assert(TuiColor.to16(TuiColor.pack(0, 0, 255)) == 12) // bright blue
            succeed
        }
    }

end TuiColorTest
