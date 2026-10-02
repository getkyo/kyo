# Format Test

A block scalafmt rewrites:

```scala
val total = List(1, 2, 3).sum
```

A block that does not parse is left as written:

```scala doctest:expect=fails-compile
val   broken   =   (
```

A block tagged `noformat` is left as written:

```scala noformat
val   kept   =   1
```
