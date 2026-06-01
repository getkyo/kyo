<details>
<summary>Outer</summary>

<details>
<summary>Inner</summary>

```scala
val inner = 1
```

</details>
</details>
<!-- doctest:setup
```scala
val fixture = "test"
```
-->
<!-- doctest:expect=fails-compile
```scala
val bad: Int = "oops"
```
-->
