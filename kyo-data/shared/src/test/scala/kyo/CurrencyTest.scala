package kyo

class CurrencyTest extends kyo.test.Test[Any]:

    private def parse(code: String): Currency = Currency.parse(code).getOrThrow

    "parse" - {
        "accepts current ISO 4217 codes with their numeric codes and minor units" in {
            val usd = parse("USD")
            assert(usd.code == "USD")
            assert(usd.numericCode == 840)
            assert(usd.minorUnits == Present(2))
            val jpy = parse("JPY")
            assert(jpy.numericCode == 392)
            assert(jpy.minorUnits == Present(0))
            val bhd = parse("BHD")
            assert(bhd.numericCode == 48)
            assert(bhd.minorUnits == Present(3))
            assert(parse("CLF").minorUnits == Present(4))
            assert(parse("BRL").numericCode == 986)
        }

        "accepts withdrawn codes the JDK still carries" in {
            assert(parse("DEM").numericCode == 276)
            assert(parse("FRF").minorUnits == Present(2))
        }

        "reports no minor unit for codes that have none" in {
            assert(parse("XAU").minorUnits == Absent)
            assert(parse("XAG").minorUnits == Absent)
            assert(parse("XXX").numericCode == 999)
        }

        "reports a numeric code of zero for codes ISO assigns none" in {
            assert(parse("XFO").numericCode == 0)
            assert(parse("XFU").numericCode == 0)
        }

        "rejects text that is not an exact uppercase code" in {
            Seq("", "US", "USDD", "usd", "Usd", "ABC", "NOTACURRENCY", "US ", " USD").foreach { text =>
                Currency.parse(text) match
                    case Result.Failure(e: Currency.InvalidCurrency) =>
                        assert(e.code == text)
                        assert(e.getMessage.contains(s"'$text'"))
                    case other => fail(s"expected InvalidCurrency for '$text' but got $other")
            }
            succeed
        }

        "finds the first and last codes in the table" in {
            assert(parse("ADP").code == "ADP")
            assert(parse("ZWR").code == "ZWR")
        }
    }

    "all" - {
        "lists every currency once, in code order" in {
            val codes = Currency.all.map(_.code)
            assert(codes.size == 233)
            assert(codes.toSeq == codes.toSeq.sorted)
            assert(codes.toSeq.distinct.size == codes.size)
        }

        "parses back to the same currencies" in {
            Currency.all.foreach(c => assert(parse(c.code) == c))
            succeed
        }
    }

    "a currency is its code" in {
        val usd = parse("USD")
        assert(usd.show == "USD")
        assert(s"$usd" == "USD")
        assert(summon[Ordering[Currency]].compare(parse("BRL"), usd) < 0)
    }
end CurrencyTest
