package kyo.internal

/** The members of [[PlatformJs]] that depend on how the application is linked, for the Scala 3.3 LTS line: none.
  *
  * Each one needs a branch the linker resolves (`moduleRequire` reads `import.meta`, which a link that is not an ES module rejects), and the
  * Scala.js backend of Scala 3.3 does not resolve `LinkingInfo.linkTimeIf`. The modules that use them build only on the Next line
  * (`scala-3-next`).
  */
trait PlatformJsStatic
