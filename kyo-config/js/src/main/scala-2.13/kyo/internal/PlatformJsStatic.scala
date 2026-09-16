package kyo.internal

/** The members of [[PlatformJs]] that depend on how the application is linked, for the Scala 2.13 line: none.
  *
  * Each one needs a branch the linker resolves (`moduleRequire` reads `import.meta`, which a link that is not an ES module rejects), and
  * Scala 2.13 has no `inline` to hand the linker such a branch. The modules that use them build only on the Scala 3 Next line
  * (`scala-3-next`).
  */
trait PlatformJsStatic
