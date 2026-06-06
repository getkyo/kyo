package kyo.internal

private[kyo] object NumberFormat:

    /** Formats a Double identically on JVM and Scala.js.
      *
      * Scala.js's `Double.toString` follows JavaScript's `Number.prototype.toString` (whole numbers render without a decimal, e.g. "10"),
      * while the JVM follows Java's spec ("10.0"). This pins both platforms to the JS-style form so rendered numeric output is identical
      * everywhere.
      */
    def double(v: Double): String =
        if v == v.toLong then v.toLong.toString else v.toString

end NumberFormat
