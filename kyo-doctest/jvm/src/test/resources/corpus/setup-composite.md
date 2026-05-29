# setup-composite

A setup block in the `env:__doc__` scope (the `doctest:setup` composite)
provides bindings to all subsequent blocks in the file.

<details>
<summary>Setup</summary>

```scala doctest:setup
case class Point(x: Int, y: Int)
val origin = Point(0, 0)
```

</details>

The non-setup blocks below see `Point` and `origin` from the setup block.

```scala
val p = Point(3, 4)
val dist = math.sqrt((p.x * p.x + p.y * p.y).toDouble)
```

```scala
val moved = Point(origin.x + 1, origin.y + 1)
```
