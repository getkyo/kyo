package kyo

import scala.jdk.CollectionConverters.*

/** Holds [[Currency]]'s table equal to the JDK's: the same codes, and for each the same numeric code and minor units. A JDK that gains or
  * changes a currency fails here, which is the signal to regenerate the table.
  */
class CurrencyJdkTest extends kyo.test.Test[Any]:

    "the table matches java.util.Currency code by code" in {
        val jdk  = java.util.Currency.getAvailableCurrencies.asScala.toSeq.sortBy(_.getCurrencyCode)
        val ours = Currency.all.toSeq
        assert(ours.map(_.code) == jdk.map(_.getCurrencyCode))
        jdk.zip(ours).foreach { case (j, k) =>
            assert(k.numericCode == j.getNumericCode, s"numeric code of ${j.getCurrencyCode}")
            val digits = j.getDefaultFractionDigits
            assert(k.minorUnits == (if digits < 0 then Absent else Present(digits)), s"minor units of ${j.getCurrencyCode}")
        }
        succeed
    }

    "parse accepts exactly what java.util.Currency.getInstance accepts" in {
        val candidates = Currency.all.map(_.code).toSeq ++ Seq("usd", "US", "USDX", "ABC", "", "XBT", "EURO")
        candidates.foreach { code =>
            val jdk = scala.util.Try(java.util.Currency.getInstance(code)).isSuccess
            assert(Currency.parse(code).isSuccess == jdk, s"code '$code'")
        }
        succeed
    }
end CurrencyJdkTest
