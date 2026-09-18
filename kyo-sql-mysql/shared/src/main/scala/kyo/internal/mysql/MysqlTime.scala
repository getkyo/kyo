package kyo.internal.mysql

/** The span a `TIME` column holds, named once because both encode paths must agree on it.
  *
  * `-838:59:59` to `838:59:59`, which has nothing to do with the four-byte day count the binary wire struct carries: a guard on the wire
  * bound is one nothing reaches. Enforced before the value goes out, because the server substitutes its own ceiling and reports success.
  */
private[mysql] object MysqlTime:

    /** `838:59:59` in seconds, the largest absolute span a `TIME` column carries. */
    val MaxSpanSeconds: Long = 838L * 3600L + 59L * 60L + 59L

    /** How the bound is named in the error a refused span raises. */
    val SpanLimitDescription: String = "the MySQL TIME range of 838:59:59"

end MysqlTime
