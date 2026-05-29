# expect-warns

A block that is expected to produce a compiler warning.
Using a deprecated method with `-deprecation` scalac option active will
trigger a warning. kyo-doctest reports success when the warning is emitted.

Note: the CorpusTest for this file passes `-deprecation` as a scalac option.

```scala doctest:expect=warns
object DeprecatedExample {
  @deprecated("use newMethod instead", "1.0")
  def oldMethod(): Int = 42
  val result = oldMethod()
}
```
