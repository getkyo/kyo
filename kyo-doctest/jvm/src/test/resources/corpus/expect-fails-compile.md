# expect-fails-compile

A block that is expected to fail compilation. The type mismatch below is
intentional: assigning a String literal to an Int binding must be rejected.
kyo-doctest reports a success when the block fails to compile as expected.

```scala doctest:expect=fails-compile
val broken: Int = "this is not an Int"
```
