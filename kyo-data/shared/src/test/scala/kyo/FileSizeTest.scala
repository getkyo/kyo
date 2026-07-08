package kyo

class FileSizeTest extends kyo.test.Test[Any]:

    "Long extension" - {
        "bytes" in {
            assert(0L.bytes == FileSize.Zero)
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
            assert((-1L).bytes == FileSize.Zero)
            assert((-1L).kib == FileSize.Zero)
            assert((-1L).mib == FileSize.Zero)
            assert((-1L).gib == FileSize.Zero)
            assert((-1L).tib == FileSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(0L.kib == FileSize.Zero)
            assert(0L.mib == FileSize.Zero)
        }
    }

    "Int extension" - {
        "bytes" in {
            assert(0.bytes == FileSize.Zero)
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
            assert((-1).bytes == FileSize.Zero)
            assert((-1).kib == FileSize.Zero)
            assert((-1).mib == FileSize.Zero)
            assert((-1).gib == FileSize.Zero)
            assert((-1).tib == FileSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(0.kib == FileSize.Zero)
            assert(0.mib == FileSize.Zero)
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
            assert((-1L).kb == FileSize.Zero)
            assert((-1L).mb == FileSize.Zero)
            assert((-1L).gb == FileSize.Zero)
        }

        "negative Int.kb clamps to Zero" in {
            assert((-1).kb == FileSize.Zero)
            assert((-1).mb == FileSize.Zero)
            assert((-1).gb == FileSize.Zero)
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

    "FileSize.fromBytes" - {
        "positive value" in {
            assert(FileSize.fromBytes(512L).toBytes == 512L)
        }

        "negative clamps to Zero" in {
            assert(FileSize.fromBytes(-1L) == FileSize.Zero)
        }

        "zero clamps to Zero" in {
            assert(FileSize.fromBytes(0L) == FileSize.Zero)
        }
    }

    "FileSize.fromUnits" - {
        "binary units" in {
            assert(FileSize.fromUnits(1L, FileSize.Units.Bytes).toBytes == 1L)
            assert(FileSize.fromUnits(1L, FileSize.Units.KiB).toBytes == 1024L)
            assert(FileSize.fromUnits(1L, FileSize.Units.MiB).toBytes == 1048576L)
            assert(FileSize.fromUnits(1L, FileSize.Units.GiB).toBytes == 1073741824L)
            assert(FileSize.fromUnits(1L, FileSize.Units.TiB).toBytes == 1099511627776L)
        }

        "decimal units" in {
            assert(FileSize.fromUnits(1L, FileSize.Units.KB).toBytes == 1000L)
            assert(FileSize.fromUnits(1L, FileSize.Units.MB).toBytes == 1000000L)
            assert(FileSize.fromUnits(1L, FileSize.Units.GB).toBytes == 1000000000L)
        }

        "negative clamps to Zero" in {
            assert(FileSize.fromUnits(-1L, FileSize.Units.MiB) == FileSize.Zero)
        }
    }

    "to(unit) conversion" - {
        "exact binary" in {
            assert(64L.mib.to(FileSize.Units.MiB) == 64.0)
            assert(1L.gib.to(FileSize.Units.MiB) == 1024.0)
            assert(1L.tib.to(FileSize.Units.GiB) == 1024.0)
        }

        "fractional result" in {
            val half = FileSize.fromBytes(512L)
            assert(half.to(FileSize.Units.KiB) == 0.5)
        }

        "bytes" in {
            assert(1L.kib.to(FileSize.Units.Bytes) == 1024.0)
        }

        "decimal units" in {
            assert(FileSize.fromUnits(1L, FileSize.Units.MB).to(FileSize.Units.KB) == 1000.0)
        }
    }

    "FileSize.parse" - {
        "binary unit symbols" in {
            assert(FileSize.parse("512B") == Result.succeed(512L.bytes))
            assert(FileSize.parse("1KiB") == Result.succeed(1L.kib))
            assert(FileSize.parse("64MiB") == Result.succeed(64L.mib))
            assert(FileSize.parse("1GiB") == Result.succeed(1L.gib))
            assert(FileSize.parse("1TiB") == Result.succeed(1L.tib))
        }

        "decimal unit symbols" in {
            assert(FileSize.parse("1KB") == Result.succeed(FileSize.fromUnits(1L, FileSize.Units.KB)))
            assert(FileSize.parse("10MB") == Result.succeed(FileSize.fromUnits(10L, FileSize.Units.MB)))
            assert(FileSize.parse("2GB") == Result.succeed(FileSize.fromUnits(2L, FileSize.Units.GB)))
        }

        "large integral decimal value is exact" in {
            assert(FileSize.parse("8589934591GB") == Result.succeed(FileSize.fromBytes(8589934591000000000L)))
        }

        "whitespace between number and unit" in {
            assert(FileSize.parse("64 MiB") == Result.succeed(64L.mib))
            assert(FileSize.parse("1  GiB") == Result.succeed(1L.gib))
        }

        "case insensitive units" in {
            assert(FileSize.parse("64mib") == Result.succeed(64L.mib))
            assert(FileSize.parse("64MIB") == Result.succeed(64L.mib))
            assert(FileSize.parse("64Mib") == Result.succeed(64L.mib))
            assert(FileSize.parse("1kib") == Result.succeed(1L.kib))
        }

        "fractional values" in {
            assert(FileSize.parse("1.5GiB") == Result.succeed(FileSize.fromBytes(Math.round(1.5 * (1L << 30)))))
            assert(FileSize.parse("0.5MiB") == Result.succeed(FileSize.fromBytes(512L * 1024L)))
        }

        "bare number is bytes" in {
            assert(FileSize.parse("1024") == Result.succeed(1024L.bytes))
            assert(FileSize.parse("0") == Result.succeed(FileSize.Zero))
        }

        "leading and trailing whitespace" in {
            assert(FileSize.parse("  64MiB  ") == Result.succeed(64L.mib))
        }
    }

    "FileSize.parse rejections" - {
        "empty string" in {
            assert(FileSize.parse("").isFailure)
        }

        "garbage input" in {
            assert(FileSize.parse("hello").isFailure)
            assert(FileSize.parse("abc123").isFailure)
            assert(FileSize.parse("!@#").isFailure)
        }

        "negative number" in {
            assert(FileSize.parse("-64MiB").isFailure)
            assert(FileSize.parse("-1").isFailure)
        }

        "unknown unit" in {
            assert(FileSize.parse("64PiB").isFailure)
            assert(FileSize.parse("1lightyear").isFailure)
        }

        "overflow past Long.MaxValue" in {
            assert(FileSize.parse("9999999999TiB").isFailure)
            assert(FileSize.parse("99999999999999GiB").isFailure)
        }
    }

    "FileSize.show" - {
        "zero" in {
            assert(FileSize.Zero.show == "FileSize.Zero")
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
            assert(FileSize.fromBytes(1536L).show == "1536.bytes")
            assert(FileSize.fromBytes(1536L * 1024L).show == "1536.kib")
        }

        "multi-kib value" in {
            assert(FileSize.fromBytes(2048L).show == "2.kib")
        }
    }

    "arithmetic" - {
        "addition" in {
            assert(1L.mib + 1L.mib == 2L.mib)
            assert(FileSize.Zero + 64L.mib == 64L.mib)
        }

        "addition overflow saturates" in {
            val big = FileSize.fromBytes(Long.MaxValue)
            assert((big + 1L.bytes).toBytes == Long.MaxValue)
            assert((big + big).toBytes == Long.MaxValue)
        }

        "subtraction" in {
            assert(64L.mib - 32L.mib == 32L.mib)
        }

        "subtraction underflow clamps to Zero" in {
            assert(1L.mib - 2L.mib == FileSize.Zero)
            assert(FileSize.Zero - 1L.bytes == FileSize.Zero)
        }

        "subtraction equal values gives Zero" in {
            assert(64L.mib - 64L.mib == FileSize.Zero)
        }

        "multiply by factor" in {
            assert((64L.mib * 2.0).toBytes == 64L.mib.toBytes * 2L)
            assert((1L.gib * 0.5).toBytes == 512L.mib.toBytes)
        }

        "multiply by zero clamps to Zero" in {
            assert(64L.mib * 0.0 == FileSize.Zero)
        }

        "multiply by negative clamps to Zero" in {
            assert(64L.mib * -1.0 == FileSize.Zero)
        }

        "multiply overflow saturates" in {
            val big = FileSize.fromBytes(Long.MaxValue / 2 + 1)
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
            assert(FileSize.Zero <= 1L.bytes)
            assert(!(FileSize.Zero > 1L.bytes))
        }
    }

    "parse/show round-trip" - {
        "lossless binary values" in {
            val values = Seq(1L.bytes, 1L.kib, 1L.mib, 64L.mib, 1L.gib, 1L.tib, 512L.kib, 256L.mib)
            values.foreach { size =>
                assert(FileSize.parse(size.show) == Result.succeed(size))
            }
            ()
        }

        "Zero round-trips" in {
            assert(FileSize.parse(FileSize.Zero.show) == Result.succeed(FileSize.Zero))
        }
    }

    "Flag.Reader" - {
        val reader = summon[Flag.Reader[FileSize]]

        "typeName" in {
            assert(reader.typeName == "FileSize")
        }

        "binary units" in {
            assert(reader("64MiB") == Right(64L.mib))
            assert(reader("1GiB") == Right(1L.gib))
            assert(reader("512B") == Right(512L.bytes))
        }

        "decimal units" in {
            assert(reader("10MB") == Right(FileSize.fromUnits(10L, FileSize.Units.MB)))
        }

        "large integral decimal value is exact" in {
            assert(reader("8589934591GB") == Right(FileSize.fromBytes(8589934591000000000L)))
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

end FileSizeTest
