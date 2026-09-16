package kyo.internal

import scala.scalajs.LinkingInfo
import scala.scalajs.js

/** The members of [[PlatformJs]] that depend on how the application is linked, on Scala 3.
  *
  * Each is `inline`, so its body is compiled where it is called rather than where kyo-config is. That is what makes it work from a release:
  * kyo-config is published for the Scala 3.3 LTS line, whose Scala.js backend does not resolve `LinkingInfo.linkTimeIf`, and the modules that
  * call these build on the Next line, whose backend does. A caller compiled for the 3.3 line cannot use them.
  *
  * Scala 2.13 has no `inline` to hand the linker such a branch, so that line declares none (`scala-2.13`).
  */
trait PlatformJsStatic:

    /** A `require` function that resolves a module id from where the linked application is, the way an `import` written in the application
      * would, or `undefined` on a host that has none, such as a page.
      *
      * This is how a synchronous path reaches an npm package (koffi) or a file inside one. A dynamic `import()` resolves the same way but
      * cannot serve such a path, because its result is never synchronously observable, and a static `@JSImport` is resolved when the module
      * graph loads, so it fails the load on a host without the package.
      *
      * Resolving from the application, rather than from the working directory, is what finds a package installed beside a program that is
      * started from somewhere else. The two module kinds give the application's location in different forms, so the linker picks the branch:
      *   - under ESModule, which every WasmGC link is, `createRequire(import.meta.url)`, with `createRequire` taken from `node:module` through
      *     [[PlatformJs.nodeBuiltin]]. `import.meta` exists only in an ES module, and a link of another kind rejects it, so this branch is
      *     never part of one;
      *   - under CommonJS and NoModule there is no `import.meta`. Node runs such a file inside a wrapper function whose `require` parameter
      *     resolves from the file, and that binding is not a property of `globalThis`, so it is read by name. The read sits behind an inline
      *     `typeof` test on the same name, the one form that cannot throw a `ReferenceError`, so a NoModule script in a page reads nothing.
      */
    inline def moduleRequire: js.UndefOr[js.Dynamic] =
        LinkingInfo.linkTimeIf[js.UndefOr[js.Dynamic]](LinkingInfo.moduleKind == LinkingInfo.ModuleKind.ESModule) {
            PlatformJs.nodeBuiltin("node:module").map(module => module.createRequire(js.`import`.meta.url))
        } {
            if js.typeOf(js.Dynamic.global.require) == "function" then js.Dynamic.global.require
            else js.undefined
        }
end PlatformJsStatic
