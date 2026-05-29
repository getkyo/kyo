# macro-fence

Exercises kyo macro resolution: `derives Schema` via kyo-schema's
derivation macro, and `ConcreteTag` from kyo-core.

```scala
import kyo.*

case class MacroPoint(x: Int, y: Int) derives Schema

val schemaInstance = summon[Schema[MacroPoint]]
```

```scala
import kyo.*

val intTag: Tag[Int] = Tag[Int]
```

```scala
import kyo.*

val ct: kyo.ConcreteTag[String | Int] = summon[kyo.ConcreteTag[String | Int]]
```

```scala
import kyo.*

val program: Int < Sync = direct { Sync.defer(42).now + 1 }
```
