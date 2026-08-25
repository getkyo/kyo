package kyo

class ByteSizeTest extends kyo.test.Test[Any]:

    "Long extension" - {
        "bytes" in {
            assert(0L.bytes == ByteSize.Zero)
            assert(1L.bytes.toBytes == 1L)
            assert(1024L.bytes.toBytes == 1024L)
        }

        "kib" in {
            assert(1L.kib.toBytes == 1024L)
            assert(64L.kib.toBytes == 65536L)
        }

        "mib" in {
            assert(1L.mib.toBytes == 1048576L)
            assert(64L.mib.toBytes == 67108864L)
        }

        "gib" in {
            assert(1L.gib.toBytes == 1073741824L)
            assert(2L.gib.toBytes == 2147483648L)
        }

        "tib" in {
            assert(1L.tib.toBytes == 1099511627776L)
        }

        "negative clamps to Zero" in {
            assert((-1L).bytes == ByteSize.Zero)
            assert((-1L).kib == ByteSize.Zero)
            assert((-1L).mib == ByteSize.Zero)
            assert((-1L).gib == ByteSize.Zero)
            assert((-1L).tib == ByteSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(0L.kib == ByteSize.Zero)
            assert(0L.mib == ByteSize.Zero)
        }
    }

    "Int extension" - {
        "bytes" in {
            assert(0.bytes == ByteSize.Zero)
            assert(1.bytes.toBytes == 1L)
            assert(1024.bytes.toBytes == 1024L)
        }

        "kib" in {
            assert(1.kib.toBytes == 1024L)
            assert(2.kib.toBytes == 2048L)
        }

        "mib" in {
            assert(1.mib.toBytes == 1048576L)
            assert(512.mib.toBytes == 536870912L)
        }

        "gib" in {
            assert(1.gib.toBytes == 1073741824L)
            assert(2.gib.toBytes == 2147483648L)
        }

        "tib" in {
            assert(1.tib.toBytes == 1099511627776L)
        }

        "negative clamps to Zero" in {
            assert((-1).bytes == ByteSize.Zero)
            assert((-1).kib == ByteSize.Zero)
            assert((-1).mib == ByteSize.Zero)
            assert((-1).gib == ByteSize.Zero)
            assert((-1).tib == ByteSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(0.kib == ByteSize.Zero)
            assert(0.mib == ByteSize.Zero)
        }

        "Int.MaxValue.tib saturates to Long.MaxValue" in {
            assert(Int.MaxValue.tib.toBytes == Long.MaxValue)
        }

        "equality across Int and Long forms" in {
            assert(2.mib == 2L.mib)
            assert(1.gib == 1L.gib)
            assert(512.kib == 512L.kib)
        }
    }

    "decimal unit extensions" - {
        "Long.kb" in {
            assert(1L.kb.toBytes == 1000L)
            assert(3L.kb.toBytes == 3000L)
        }

        "Long.mb" in {
            assert(1L.mb.toBytes == 1000000L)
            assert(5L.mb.toBytes == 5000000L)
        }

        "Long.gb" in {
            assert(1L.gb.toBytes == 1000000000L)
            assert(2L.gb.toBytes == 2000000000L)
        }

        "Int.kb" in {
            assert(1.kb.toBytes == 1000L)
            assert(3.kb.toBytes == 3000L)
        }

        "Int.mb" in {
            assert(1.mb.toBytes == 1000000L)
            assert(5.mb.toBytes == 5000000L)
        }

        "Int.gb" in {
            assert(1.gb.toBytes == 1000000000L)
            assert(2.gb.toBytes == 2000000000L)
        }

        "negative Long.kb clamps to Zero" in {
            assert((-1L).kb == ByteSize.Zero)
            assert((-1L).mb == ByteSize.Zero)
            assert((-1L).gb == ByteSize.Zero)
        }

        "negative Int.kb clamps to Zero" in {
            assert((-1).kb == ByteSize.Zero)
            assert((-1).mb == ByteSize.Zero)
            assert((-1).gb == ByteSize.Zero)
        }

        "Int and Long decimal forms are equal" in {
            assert(3.kb == 3L.kb)
            assert(5.mb == 5L.mb)
            assert(2.gb == 2L.gb)
        }

        "large Long.gb is exact" in {
            assert(8589934591L.gb.toBytes == 8589934591000000000L)
        }

        "large Long.mb is exact" in {
            assert(1099511627775L.mb.toBytes == 1099511627775000000L)
        }

        "large Long.kb is exact" in {
            assert(73838224265209L.kb.toBytes == 73838224265209000L)
        }
    }

    "ByteSize.fromBytes" - {
        "positive value" in {
            assert(ByteSize.fromBytes(512L).toBytes == 512L)
        }

        "negative clamps to Zero" in {
            assert(ByteSize.fromBytes(-1L) == ByteSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(ByteSize.fromBytes(0L) == ByteSize.Zero)
        }
    }

    "ByteSize.fromUnits" - {
        "binary units" in {
            assert(ByteSize.fromUnits(1L, ByteSize.Units.Bytes).toBytes == 1L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.KiB).toBytes == 1024L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.MiB).toBytes == 1048576L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.GiB).toBytes == 1073741824L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.TiB).toBytes == 1099511627776L)
        }

        "decimal units" in {
            assert(ByteSize.fromUnits(1L, ByteSize.Units.KB).toBytes == 1000L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.MB).toBytes == 1000000L)
            assert(ByteSize.fromUnits(1L, ByteSize.Units.GB).toBytes == 1000000000L)
        }

        "negative clamps to Zero" in {
            assert(ByteSize.fromUnits(-1L, ByteSize.Units.MiB) == ByteSize.Zero)
        }
    }

    "to(unit) conversion" - {
        "exact binary" in {
            assert(64L.mib.to(ByteSize.Units.MiB) == 64.0)
            assert(1L.gib.to(ByteSize.Units.MiB) == 1024.0)
            assert(1L.tib.to(ByteSize.Units.GiB) == 1024.0)
        }

        "fractional result" in {
            val half = ByteSize.fromBytes(512L)
            assert(half.to(ByteSize.Units.KiB) == 0.5)
        }

        "bytes" in {
            assert(1L.kib.to(ByteSize.Units.Bytes) == 1024.0)
        }

        "decimal units" in {
            assert(ByteSize.fromUnits(1L, ByteSize.Units.MB).to(ByteSize.Units.KB) == 1000.0)
        }
    }

    "ByteSize.parse" - {
        "binary unit symbols" in {
            assert(ByteSize.parse("512B") == Result.succeed(512L.bytes))
            assert(ByteSize.parse("1KiB") == Result.succeed(1L.kib))
            assert(ByteSize.parse("64MiB") == Result.succeed(64L.mib))
            assert(ByteSize.parse("1GiB") == Result.succeed(1L.gib))
            assert(ByteSize.parse("1TiB") == Result.succeed(1L.tib))
        }

        "decimal unit symbols" in {
            assert(ByteSize.parse("1KB") == Result.succeed(ByteSize.fromUnits(1L, ByteSize.Units.KB)))
            assert(ByteSize.parse("10MB") == Result.succeed(ByteSize.fromUnits(10L, ByteSize.Units.MB)))
            assert(ByteSize.parse("2GB") == Result.succeed(ByteSize.fromUnits(2L, ByteSize.Units.GB)))
        }

        "large integral decimal value is exact" in {
            assert(ByteSize.parse("8589934591GB") == Result.succeed(ByteSize.fromBytes(8589934591000000000L)))
        }

        "whitespace between number and unit" in {
            assert(ByteSize.parse("64 MiB") == Result.succeed(64L.mib))
            assert(ByteSize.parse("1  GiB") == Result.succeed(1L.gib))
        }

        "case insensitive units" in {
            assert(ByteSize.parse("64mib") == Result.succeed(64L.mib))
            assert(ByteSize.parse("64MIB") == Result.succeed(64L.mib))
            assert(ByteSize.parse("64Mib") == Result.succeed(64L.mib))
            assert(ByteSize.parse("1kib") == Result.succeed(1L.kib))
        }

        "fractional values" in {
            assert(ByteSize.parse("1.5GiB") == Result.succeed(ByteSize.fromBytes(Math.round(1.5 * (1L << 30)))))
            assert(ByteSize.parse("0.5MiB") == Result.succeed(ByteSize.fromBytes(512L * 1024L)))
        }

        "bare number is bytes" in {
            assert(ByteSize.parse("1024") == Result.succeed(1024L.bytes))
            assert(ByteSize.parse("0") == Result.succeed(ByteSize.Zero))
        }

        "leading and trailing whitespace" in {
            assert(ByteSize.parse("  64MiB  ") == Result.succeed(64L.mib))
        }
    }

    "ByteSize.parse rejections" - {
        "empty string" in {
            assert(ByteSize.parse("").isFailure)
        }

        "garbage input" in {
            assert(ByteSize.parse("hello").isFailure)
            assert(ByteSize.parse("abc123").isFailure)
            assert(ByteSize.parse("!@#").isFailure)
        }

        "negative number" in {
            assert(ByteSize.parse("-64MiB").isFailure)
            assert(ByteSize.parse("-1").isFailure)
        }

        "unknown unit" in {
            assert(ByteSize.parse("64PiB").isFailure)
            assert(ByteSize.parse("1lightyear").isFailure)
        }

        "overflow past Long.MaxValue" in {
            assert(ByteSize.parse("9999999999TiB").isFailure)
            assert(ByteSize.parse("99999999999999GiB").isFailure)
        }
    }

    "ByteSize.show" - {
        "zero" in {
            assert(ByteSize.Zero.show == "ByteSize.Zero")
        }

        "exact binary units" in {
            assert(1L.bytes.show == "1.bytes")
            assert(1L.kib.show == "1.kib")
            assert(64L.mib.show == "64.mib")
            assert(1L.gib.show == "1.gib")
            assert(1L.tib.show == "1.tib")
        }

        "coarsest lossless unit" in {
            assert((1024L.bytes).show == "1.kib")
            assert((1024L.kib).show == "1.mib")
            assert((1024L.mib).show == "1.gib")
            assert((1024L.gib).show == "1.tib")
        }

        "non-exact falls to smaller binary unit" in {
            assert(ByteSize.fromBytes(1536L).show == "1536.bytes")
            assert(ByteSize.fromBytes(1536L * 1024L).show == "1536.kib")
        }

        "multi-kib value" in {
            assert(ByteSize.fromBytes(2048L).show == "2.kib")
        }
    }

    "arithmetic" - {
        "addition" in {
            assert(1L.mib + 1L.mib == 2L.mib)
            assert(ByteSize.Zero + 64L.mib == 64L.mib)
        }

        "addition overflow saturates" in {
            val big = ByteSize.fromBytes(Long.MaxValue)
            assert((big + 1L.bytes).toBytes == Long.MaxValue)
            assert((big + big).toBytes == Long.MaxValue)
        }

        "subtraction" in {
            assert(64L.mib - 32L.mib == 32L.mib)
        }

        "subtraction underflow clamps to Zero" in {
            assert(1L.mib - 2L.mib == ByteSize.Zero)
            assert(ByteSize.Zero - 1L.bytes == ByteSize.Zero)
        }

        "subtraction equal values gives Zero" in {
            assert(64L.mib - 64L.mib == ByteSize.Zero)
        }

        "multiply by factor" in {
            assert((64L.mib * 2.0).toBytes == 64L.mib.toBytes * 2L)
            assert((1L.gib * 0.5).toBytes == 512L.mib.toBytes)
        }

        "multiply by zero clamps to Zero" in {
            assert(64L.mib * 0.0 == ByteSize.Zero)
        }

        "multiply by negative clamps to Zero" in {
            assert(64L.mib * -1.0 == ByteSize.Zero)
        }

        "multiply overflow saturates" in {
            val big = ByteSize.fromBytes(Long.MaxValue / 2 + 1)
            assert((big * 3.0).toBytes == Long.MaxValue)
        }

        "max" in {
            assert((32L.mib).max(64L.mib) == 64L.mib)
            assert((64L.mib).max(32L.mib) == 64L.mib)
        }

        "min" in {
            assert((32L.mib).min(64L.mib) == 32L.mib)
            assert((64L.mib).min(32L.mib) == 32L.mib)
        }
    }

    "comparisons" - {
        "equality via CanEqual" in {
            assert(64L.mib == 64L.mib)
            assert(64L.mib != 32L.mib)
        }

        ">= and <=" in {
            assert(64L.mib >= 32L.mib)
            assert(32L.mib <= 64L.mib)
            assert(64L.mib >= 64L.mib)
            assert(64L.mib <= 64L.mib)
        }

        "> and <" in {
            assert(64L.mib > 32L.mib)
            assert(32L.mib < 64L.mib)
            assert(!(64L.mib > 64L.mib))
            assert(!(64L.mib < 64L.mib))
        }

        "Zero is smallest" in {
            assert(ByteSize.Zero <= 1L.bytes)
            assert(!(ByteSize.Zero > 1L.bytes))
        }
    }

    "parse/show round-trip" - {
        "lossless binary values" in {
            val values = Seq(1L.bytes, 1L.kib, 1L.mib, 64L.mib, 1L.gib, 1L.tib, 512L.kib, 256L.mib)
            values.foreach { size =>
                assert(ByteSize.parse(size.show) == Result.succeed(size))
            }
            ()
        }

        "Zero round-trips" in {
            assert(ByteSize.parse(ByteSize.Zero.show) == Result.succeed(ByteSize.Zero))
        }
    }

    "Flag.Reader" - {
        val reader = summon[Flag.Reader[ByteSize]]

        "typeName" in {
            assert(reader.typeName == "ByteSize")
        }

        "binary units" in {
            assert(reader("64MiB") == Right(64L.mib))
            assert(reader("1GiB") == Right(1L.gib))
            assert(reader("512B") == Right(512L.bytes))
        }

        "decimal units" in {
            assert(reader("10MB") == Right(ByteSize.fromUnits(10L, ByteSize.Units.MB)))
        }

        "large integral decimal value is exact" in {
            assert(reader("8589934591GB") == Right(ByteSize.fromBytes(8589934591000000000L)))
        }

        "bare number is bytes" in {
            assert(reader("1024") == Right(1024L.bytes))
        }

        "case insensitive" in {
            assert(reader("64mib") == Right(64L.mib))
            assert(reader("64MIB") == Right(64L.mib))
        }

        "whitespace between number and unit" in {
            assert(reader("64 MiB") == Right(64L.mib))
        }

        "invalid format" in {
            assert(reader("not-a-size").isLeft)
        }

        "invalid unit" in {
            assert(reader("64PiB").isLeft)
        }

        "overflow" in {
            assert(reader("9999999999TiB").isLeft)
        }
    }

    "Units metadata" - {
        "byte counts" in {
            assert(ByteSize.Units.Bytes.bytes == 1L)
            assert(ByteSize.Units.KiB.bytes == 1024L)
            assert(ByteSize.Units.MiB.bytes == 1048576L)
            assert(ByteSize.Units.GiB.bytes == 1073741824L)
            assert(ByteSize.Units.TiB.bytes == 1099511627776L)
            assert(ByteSize.Units.KB.bytes == 1000L)
            assert(ByteSize.Units.MB.bytes == 1000000L)
            assert(ByteSize.Units.GB.bytes == 1000000000L)
        }

        "symbols" in {
            assert(ByteSize.Units.Bytes.symbol == "B")
            assert(ByteSize.Units.KiB.symbol == "KiB")
            assert(ByteSize.Units.MiB.symbol == "MiB")
            assert(ByteSize.Units.GiB.symbol == "GiB")
            assert(ByteSize.Units.TiB.symbol == "TiB")
            assert(ByteSize.Units.KB.symbol == "KB")
            assert(ByteSize.Units.MB.symbol == "MB")
            assert(ByteSize.Units.GB.symbol == "GB")
        }

        "all lists every unit in declaration order" in {
            assert(ByteSize.Units.all.length == 8)
            assert(ByteSize.Units.all(0) == ByteSize.Units.Bytes)
            assert(ByteSize.Units.all(1) == ByteSize.Units.KiB)
            assert(ByteSize.Units.all(2) == ByteSize.Units.MiB)
            assert(ByteSize.Units.all(3) == ByteSize.Units.GiB)
            assert(ByteSize.Units.all(4) == ByteSize.Units.TiB)
            assert(ByteSize.Units.all(5) == ByteSize.Units.KB)
            assert(ByteSize.Units.all(6) == ByteSize.Units.MB)
            assert(ByteSize.Units.all(7) == ByteSize.Units.GB)
        }
    }

    "ByteSize.fromUnits saturation boundary" - {
        "largest exact TiB value does not clamp" in {
            assert(ByteSize.fromUnits(8388607L, ByteSize.Units.TiB).toBytes == 9223370937343148032L)
        }

        "one TiB past the boundary clamps" in {
            assert(ByteSize.fromUnits(8388608L, ByteSize.Units.TiB).toBytes == Long.MaxValue)
        }

        "largest exact GiB value does not clamp" in {
            assert(ByteSize.fromUnits(8589934591L, ByteSize.Units.GiB).toBytes == 9223372035781033984L)
        }

        "one GiB past the boundary clamps" in {
            assert(ByteSize.fromUnits(8589934592L, ByteSize.Units.GiB).toBytes == Long.MaxValue)
        }

        "Long.MaxValue in Bytes is exact" in {
            assert(ByteSize.fromUnits(Long.MaxValue, ByteSize.Units.Bytes).toBytes == Long.MaxValue)
        }
    }

    "ByteSize.parse overflow boundary" - {
        "Long.MaxValue as bare bytes parses" in {
            assert(ByteSize.parse("9223372036854775807") == Result.succeed(ByteSize.fromBytes(Long.MaxValue)))
        }

        "one past Long.MaxValue as bare bytes is rejected" in {
            assert(ByteSize.parse("9223372036854775808").isFailure)
        }

        "largest exact TiB value parses" in {
            assert(ByteSize.parse("8388607TiB") == Result.succeed(ByteSize.fromBytes(9223370937343148032L)))
        }

        "one TiB past the boundary is rejected" in {
            assert(ByteSize.parse("8388608TiB").isFailure)
        }
    }

    "ByteSize.parse rounding" - {
        "fractional bytes round half up" in {
            assert(ByteSize.parse("1.5B") == Result.succeed(ByteSize.fromBytes(2L)))
            assert(ByteSize.parse("2.5B") == Result.succeed(ByteSize.fromBytes(3L)))
            assert(ByteSize.parse("0.5B") == Result.succeed(ByteSize.fromBytes(1L)))
        }

        "fractional bytes below the tie round down" in {
            assert(ByteSize.parse("0.4B") == Result.succeed(ByteSize.Zero))
            assert(ByteSize.parse("1.4B") == Result.succeed(ByteSize.fromBytes(1L)))
        }

        "1.5GiB is exactly 1610612736 bytes" in {
            assert(ByteSize.parse("1.5GiB") == Result.succeed(ByteSize.fromBytes(1610612736L)))
        }

        "trailing zeros in the fraction do not change the result" in {
            assert(ByteSize.parse("1.50GiB") == Result.succeed(ByteSize.fromBytes(1610612736L)))
        }
    }

    "arithmetic edge cases" - {
        "multiplication rounds half up" in {
            assert((ByteSize.fromBytes(3L) * 0.5).toBytes == 2L)
            assert((ByteSize.fromBytes(5L) * 0.5).toBytes == 3L)
        }

        "multiplication below the tie rounds down" in {
            assert((ByteSize.fromBytes(1L) * 0.4) == ByteSize.Zero)
        }

        "multiplication by one is identity" in {
            assert(64L.mib * 1.0 == 64L.mib)
        }

        "Zero multiplied by any factor stays Zero" in {
            assert(ByteSize.Zero * 1000.0 == ByteSize.Zero)
        }

        "subtraction from Long.MaxValue is exact" in {
            assert((ByteSize.fromBytes(Long.MaxValue) - 1L.bytes).toBytes == 9223372036854775806L)
        }

        "max and min against Zero" in {
            assert(ByteSize.Zero.max(64L.mib) == 64L.mib)
            assert(ByteSize.Zero.min(64L.mib) == ByteSize.Zero)
            assert(ByteSize.Zero.max(ByteSize.Zero) == ByteSize.Zero)
        }
    }

    "show edge cases" - {
        "Long.MaxValue is odd so it renders as bytes" in {
            assert(ByteSize.fromBytes(Long.MaxValue).show == "9223372036854775807.bytes")
        }

        "values above TiB stay in TiB" in {
            assert(ByteSize.fromBytes(1125899906842624L).show == "1024.tib")
        }

        "one byte below a KiB renders as bytes" in {
            assert(ByteSize.fromBytes(1023L).show == "1023.bytes")
        }
    }

    // Mirrors the exact values asserted in the Storage sizes section of kyo-data/README.md.
    // The doctest runner type-checks those blocks but does not execute them, so these leaves
    // are what actually holds the documented numbers to the implementation.
    "README examples" - {
        "construction" in {
            assert(512L.bytes.toBytes == 512L)
            assert(64L.mib.toBytes == 67108864L)
            assert(1L.gib.toBytes == 1073741824L)
            assert(512.mib == 512L.mib)
            assert(100.kb.toBytes == 100000L)
            assert(ByteSize.Zero.toBytes == 0L)
            assert(ByteSize.parse("64MiB") == Result.succeed(64L.mib))
        }

        "saturating arithmetic" in {
            assert(32L.mib + 32L.mib == 64L.mib)
            assert(1L.gib - 256L.mib == 768L.mib)
            assert(1L.mib * 128.0 == 128L.mib)
            assert(ByteSize.fromBytes(Long.MaxValue) + 1L.bytes == ByteSize.fromBytes(Long.MaxValue))
        }

        "to(unit) and show" in {
            assert(64L.mib.to(ByteSize.Units.GiB) == 0.0625)
            assert(64L.mib.show == "64.mib")
            assert(ByteSize.Zero.show == "ByteSize.Zero")
        }
    }

    "to(unit) at the extremes" - {
        "Long.MaxValue in bytes" in {
            assert(ByteSize.fromBytes(Long.MaxValue).to(ByteSize.Units.Bytes) == 9.223372036854776e18)
        }

        "Zero in any unit is 0.0" in {
            assert(ByteSize.Zero.to(ByteSize.Units.GiB) == 0.0)
            assert(ByteSize.Zero.to(ByteSize.Units.Bytes) == 0.0)
        }
    }

end ByteSizeTest
