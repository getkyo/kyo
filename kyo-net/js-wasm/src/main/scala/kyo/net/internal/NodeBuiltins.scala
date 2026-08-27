package kyo.net.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facades for the Node built-in modules the JS and Wasm transport drives.
  *
  * `@JSImport` compiles to `require(...)` under the CommonJS (js) backend and to `import` under the ESModule (wasm) backend, so one source
  * serves both. `js.Dynamic.global.require("net")` does not: `require` is a CommonJS binding and is not defined in an ES module scope, which
  * is the module kind the WebAssembly backend mandates. Every network operation on Wasm therefore died with
  * `ReferenceError: require is not defined` while backend selection itself worked and correctly picked `node`, so the whole networked
  * surface was unreachable there without a `createRequire` shim published into the global scope before the module body ran. Under the SQL
  * driver the same fault was swallowed and re-reported as a connection timeout, which is what made it look like a slow database rather than
  * a missing binding.
  *
  * The `node:` scheme is required because under ESModule a namespace import of a bare id ("net") does not reliably expose the module's named
  * members. The members are reached dynamically at the call sites, matching the existing usage, so the facades are plain `js.Object`.
  */
@js.native
@JSImport("node:net", JSImport.Namespace)
private[net] object NodeNet extends js.Object

@js.native
@JSImport("node:tls", JSImport.Namespace)
private[net] object NodeTls extends js.Object

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[net] object NodeFs extends js.Object

@js.native
@JSImport("node:crypto", JSImport.Namespace)
private[net] object NodeCrypto extends js.Object
