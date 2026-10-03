package kyo.crypto

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

/** Node's `crypto` module as a second oracle for the JS and Wasm suites.
  *
  * `@JSImport` compiles to `require` under the CommonJS JS backend and to `import` under the ES module Wasm backend, where `require` does not
  * exist, so one source serves both. Node's crypto is OpenSSL, an implementation independent of the JDK the JVM suites compare against.
  */
@js.native
@JSImport("node:crypto", JSImport.Namespace)
private object NodeCryptoModule extends js.Object

object NodeOracle:

    val crypto: js.Dynamic = NodeCryptoModule.asInstanceOf[js.Dynamic]

    private val Buffer: js.Dynamic = js.Dynamic.global.Buffer

    def buffer(bytes: Array[Byte]): js.Dynamic =
        val i8 = js.typedarray.byteArray2Int8Array(bytes)
        Buffer.from(i8.buffer, i8.byteOffset, i8.byteLength)

    def bytes(value: js.Dynamic): Array[Byte] =
        val u8 = value.asInstanceOf[Uint8Array]
        js.typedarray.int8Array2ByteArray(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength))

    def base64UrlBytes(text: String): Array[Byte] = bytes(Buffer.from(text, "base64url"))

    def base64Url(bytes: Array[Byte]): String =
        buffer(bytes).applyDynamic("toString")("base64url").asInstanceOf[String]

    def hash(algorithm: String, input: Array[Byte]): Array[Byte] =
        bytes(crypto.createHash(algorithm).update(buffer(input)).digest())

    def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
        bytes(crypto.createHmac("sha256", buffer(key)).update(buffer(message)).digest())

    def pbkdf2Sha256(password: Array[Byte], salt: Array[Byte], iterations: Int, length: Int): Array[Byte] =
        bytes(crypto.pbkdf2Sync(buffer(password), buffer(salt), iterations, length, "sha256"))

    /** Runs `f`, answering `false` for the exceptions Node throws on a key or signature it refuses to read. */
    def accepts(f: => Boolean): Boolean =
        try f
        catch case _: js.JavaScriptException => false

end NodeOracle
