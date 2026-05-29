# scope-isolated

Three isolated blocks. Each block is fully self-contained.
The second block does NOT see the first block's `x` binding.
The third block re-declares its own values independently.

```scala
val x = 10
val y = x + 1
```

```scala
val z = 20
```

```scala
val result = List(1, 2, 3).map(_ * 2)
```
