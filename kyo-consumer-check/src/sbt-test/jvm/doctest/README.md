# doctest consumer

A block that compiles:

```scala
val xs = List(1, 2, 3)
xs.sum
```

A block that must not, which only passes if the runner really compiled it:

```scala doctest:expect=fails-compile
val n: Int = "not an int"
```
