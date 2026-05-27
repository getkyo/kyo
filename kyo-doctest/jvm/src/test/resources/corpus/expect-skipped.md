# expect-skipped

A skipped block is parsed but never sent to the compiler.
The body below is intentionally not valid Scala; the validator skips it.

```scala doctest:expect=skipped
this is not valid scala syntax !!! @@@ ???
```

A second, valid block that IS compiled:

```scala
val valid = 42
```
