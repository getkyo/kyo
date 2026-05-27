# html-comment-hard-hide

A hard-hidden setup fixture inside an HTML comment, followed by a visible block.

<!-- doctest:setup
```scala
case class Widget(name: String, value: Int)
val defaultWidget = Widget("default", 0)
```
-->

The `Widget` type and `defaultWidget` value are defined in a hard-hidden fixture
above. Readers never see the fixture; the validator compiles it as a setup prelude.

```scala
val w = Widget("custom", 42)
val n = w.name
```
