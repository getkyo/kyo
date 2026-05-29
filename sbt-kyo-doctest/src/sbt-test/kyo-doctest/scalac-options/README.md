# Scalac Options Test

This fence has an unused import. With -Werror enabled it becomes a compile error.

```scala
import scala.collection.mutable.ArrayBuffer
val x = 42
```
