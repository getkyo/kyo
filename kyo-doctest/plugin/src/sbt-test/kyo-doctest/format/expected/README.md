# Format Test

This README contains one scala fence that is not in scalafmt style.

```scala
val x = List(1, 2, 3)
assert(x.sum == 6)
```

A block that does not parse is left as written:

```scala doctest:expect=fails-compile
val   broken   =   (
```

A block tagged `noformat` is left as written:

```scala noformat
val   kept   =   1
```
