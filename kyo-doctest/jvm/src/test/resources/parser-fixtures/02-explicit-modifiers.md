```scala doctest:expect=fails-compile
val x: String = 42
```
```scala doctest:scope=env:tutorial
val n = 1
```
```scala doctest:scope=nested expect=warns platform=jvm
println("warn")
```
```scala doctest:setup
import scala.util.*
```
