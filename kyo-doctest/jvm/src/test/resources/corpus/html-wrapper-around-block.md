# html-wrapper-around-block

An HTML wrapper around a setup block, followed by a visible block.

<details>
<summary>Setup</summary>

```scala doctest:setup
case class Item(id: Int, label: String)
```

</details>

The `Item` type defined in the setup block above is visible here because
the default scope for non-setup blocks in a file with a setup block is
`env:__doc__` for setup-visibility purposes.

```scala
val item = Item(1, "hello")
val id   = item.id
```
