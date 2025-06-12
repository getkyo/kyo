Use Scala 3's new control syntax which omits the parentheses and braces.

Use Scala 3's universal apply methods an omit the new keyword.

Use Scala 3's optional brace rules to omit braces.

In Scaladoc use [[]] to refer to methods the first time that they are mentioned. Subsequent mentions should use ``.

Use ScalaTest's FreeSpec syntax to define tests.

## The "Pending" type: <

In Kyo, computations are expressed via the infix type `<`, known as "Pending". It takes two type parameters:

1. The type of the expected output.
2. The pending effects that need to be handled, represented as an unordered type-level set via a type intersection.

```scala
import kyo.*

// 'Int' pending 'Abort[Absent]'
// 'Absent' is Kyo's equivalent of 'None' via the 'Maybe' type
Int < Abort[Absent]

// 'String' pending 'Abort[Absent]' and 'IO'
String < (Abort[Absent] & IO)
```