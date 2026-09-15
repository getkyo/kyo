package kyo

/** A currency from ISO 4217, identified by its three-letter alphabetic code (for example `USD`, `BRL`, `JPY`).
  *
  * The currency table is part of this type and identical on every platform, so a code that parses on the JVM parses on Scala.js and Scala
  * Native too. That is the difference from `java.util.Currency`, whose instances exist off the JVM only when the application links a
  * locale data artifact, and which otherwise fails at run time on every lookup.
  *
  * The table is the one the JDK carries (`java.util.Currency.getAvailableCurrencies`): the current ISO 4217 currencies plus the withdrawn
  * codes the JDK still accepts, such as `DEM` and `FRF`. A JVM test holds the two equal, code by code.
  *
  * A `Currency` is its code at run time, so it prints as the code and compares by it.
  *
  * @see
  *   [[Currency.parse]] for turning a code into a `Currency`
  * @see
  *   [[Currency.all]] for every known currency, in code order
  */
opaque type Currency = String

object Currency:

    /** One record per currency, sorted by code: three letters, three numeric digits, then the minor units digit or `-` for none. Generated
      * from `java.util.Currency.getAvailableCurrencies` on JDK 25; `CurrencyJdkTest` holds it equal to the running JDK.
      */
    private val table: String =
        "ADP0200AED7842AFA0042AFN9712ALL0082AMD0512ANG5322AOA9732ARS0322ATS0402AUD0362AWG5332AYM9452AZM0312AZN9442BAM9772BBD0522BDT0502" +
            "BEF0560BGL1002BGN9752BHD0483BIF1080BMD0602BND0962BOB0682BOV9842BRL9862BSD0442BTN0642BWP0722BYB1120BYN9332BYR9740BZD0842CAD1242" +
            "CDF9762CHE9472CHF7562CHW9482CLF9904CLP1520CNY1562COP1702COU9702CRC1882CSD8912CUC9312CUP1922CVE1322CYP1962CZK2032DEM2762DJF2620" +
            "DKK2082DOP2142DZD0122EEK2332EGP8182ERN2322ESP7240ETB2302EUR9782FIM2462FJD2422FKP2382FRF2502GBP8262GEL9812GHC2882GHS9362GIP2922" +
            "GMD2702GNF3240GRD3000GTQ3202GWP6242GYD3282HKD3442HNL3402HRK1912HTG3322HUF3482IDR3602IEP3722ILS3762INR3562IQD3683IRR3642ISK3520" +
            "ITL3800JMD3882JOD4003JPY3920KES4042KGS4172KHR1162KMF1740KPW4082KRW4100KWD4143KYD1362KZT3982LAK4182LBP4222LKR1442LRD4302LSL4262" +
            "LTL4402LUF4420LVL4282LYD4343MAD5042MDL4982MGA9692MGF4500MKD8072MMK1042MNT4962MOP4462MRO4782MRU9292MTL4702MUR4802MVR4622MWK4542" +
            "MXN4842MXV9792MYR4582MZM5082MZN9432NAD5162NGN5662NIO5582NLG5282NOK5782NPR5242NZD5542OMR5123PAB5902PEN6042PGK5982PHP6082PKR5862" +
            "PLN9852PTE6200PYG6000QAR6342ROL6420RON9462RSD9412RUB6432RUR8102RWF6460SAR6822SBD0902SCR6902SDD7362SDG9382SEK7522SGD7022SHP6542" +
            "SIT7052SKK7032SLE9252SLL6942SOS7062SRD9682SRG7402SSP7282STD6782STN9302SVC2222SYP7602SZL7482THB7642TJS9722TMM7952TMT9342TND7883" +
            "TOP7762TPE6260TRL7920TRY9492TTD7802TWD9012TZS8342UAH9802UGX8000USD8402USN9972USS9982UYI9400UYU8582UZS8602VEB8622VED9262VEF9372" +
            "VES9282VND7040VUV5480WST8822XAD3962XAF9500XAG961-XAU959-XBA955-XBB956-XBC957-XBD958-XCD9512XCG5322XDR960-XFO000-XFU000-XOF9520" +
            "XPD964-XPF9530XPT962-XSU994-XTS963-XUA965-XXX999-YER8862YUM8912ZAR7102ZMK8942ZMW9672ZWD7162ZWG9242ZWL9322ZWN9422ZWR9352"

    private inline val RecordWidth = 7

    private val size: Int = table.length / RecordWidth

    inline given CanEqual[Currency, Currency] = CanEqual.derived

    given Ordering[Currency] with
        def compare(x: Currency, y: Currency): Int = x.compareTo(y)

    /** Raised when a code is not an ISO 4217 currency code.
      *
      * @param code
      *   the rejected text
      */
    final class InvalidCurrency(val code: String)(using Frame) extends KyoException(s"'$code' is not an ISO 4217 currency code")

    /** Every currency, in code order. */
    val all: Chunk[Currency] = Chunk.from(Array.tabulate(size)(i => table.substring(i * RecordWidth, i * RecordWidth + 3)))

    /** Parses an ISO 4217 alphabetic code. The code must be exactly three uppercase letters, as `java.util.Currency.getInstance` requires.
      *
      * @param code
      *   the text to parse
      * @return
      *   the currency, or an [[InvalidCurrency]] when the table has no such code
      */
    def parse(code: String)(using Frame): Result[InvalidCurrency, Currency] =
        val index = indexOf(code)
        if index < 0 then Result.fail(InvalidCurrency(code))
        else Result.succeed(all(index))
    end parse

    extension (self: Currency)

        /** The three-letter alphabetic code, for example `USD`. */
        def code: String = self

        /** The three-digit ISO 4217 numeric code, for example `840` for `USD`. Zero for the few codes ISO assigns none. */
        def numericCode: Int =
            val at = indexOf(self) * RecordWidth
            (table.charAt(at + 3) - '0') * 100 + (table.charAt(at + 4) - '0') * 10 + (table.charAt(at + 5) - '0')

        /** The number of digits after the decimal separator, for example `2` for `USD` and `0` for `JPY`. [[Absent]] for codes that have no
          * minor unit, such as the precious metals `XAU` and `XAG`.
          */
        def minorUnits: Maybe[Int] =
            val digit = table.charAt(indexOf(self) * RecordWidth + 6)
            if digit == '-' then Absent else Present(digit - '0')

        /** The alphabetic code. */
        def show: String = self
    end extension

    /** The record index of `code`, or -1: a binary search over the sorted records, comparing the three code characters in place. */
    private def indexOf(code: String): Int =
        if code.length != 3 then -1
        else
            var low   = 0
            var high  = size - 1
            var found = -1
            while found < 0 && low <= high do
                val mid = (low + high) >>> 1
                val at  = mid * RecordWidth
                var cmp = table.charAt(at) - code.charAt(0)
                if cmp == 0 then cmp = table.charAt(at + 1) - code.charAt(1)
                if cmp == 0 then cmp = table.charAt(at + 2) - code.charAt(2)
                if cmp == 0 then found = mid
                else if cmp < 0 then low = mid + 1
                else high = mid - 1
            end while
            found
    end indexOf

end Currency
