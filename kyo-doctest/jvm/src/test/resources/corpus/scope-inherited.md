# scope-inherited

Three inherited blocks. Each block sees bindings from all prior blocks.

The first block defines `baseValue`:

```scala doctest:scope=inherited
val baseValue = 100
```

The second block uses `baseValue` from the first:

```scala doctest:scope=inherited
val doubled = baseValue * 2
```

The third block uses `baseValue` and `doubled` from both prior blocks:

```scala doctest:scope=inherited
val total = baseValue + doubled
```
