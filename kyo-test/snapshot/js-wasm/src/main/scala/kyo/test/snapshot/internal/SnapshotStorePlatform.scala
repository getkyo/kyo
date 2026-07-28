package kyo.test.snapshot.internal

import kyo.Maybe
import kyo.Span
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Minimal Node.js `fs` facade for snapshot file I/O. */
@js.native @JSImport("node:fs", JSImport.Namespace)
private object NodeFsSnap extends js.Object:
    def existsSync(path: String): Boolean                                 = js.native
    def readFileSync(path: String, encoding: String): String              = js.native
    def readFileSync(path: String): js.typedarray.Uint8Array              = js.native
    def writeFileSync(path: String, data: String, opts: js.Dynamic): Unit = js.native
    def writeFileSync(path: String, data: js.typedarray.Uint8Array): Unit = js.native
    def mkdirSync(path: String, opts: js.Dynamic): Unit                   = js.native
end NodeFsSnap

/** Minimal Node.js `path` facade for dirname resolution. */
@js.native @JSImport("node:path", JSImport.Namespace)
private object NodePathSnap extends js.Object:
    def dirname(path: String): String = js.native
end NodePathSnap

/** JS snapshot file I/O backed by Node.js `fs`.
  *
  * Snapshot tests are test infrastructure and run under Node.js (not in a browser). If Node.js `fs` is unavailable (e.g., browser
  * environment), the `js.JavaScriptException` thrown by the native module import is caught and re-thrown as `UnsupportedOperationException`
  * with a clear message.
  */
private[snapshot] object SnapshotStorePlatform:

    def read(path: String): Maybe[String] =
        try
            if NodeFsSnap.existsSync(path) then
                Maybe.Present(NodeFsSnap.readFileSync(path, "utf8"))
            else
                Maybe.Absent
        catch
            case _: js.JavaScriptException =>
                throw new UnsupportedOperationException(
                    "Snapshot file I/O is not available in browser environments; use Node.js for snapshot tests"
                )

    def write(path: String, content: String): Unit =
        try
            val parent = NodePathSnap.dirname(path)
            if parent.nonEmpty && parent != path then
                NodeFsSnap.mkdirSync(parent, js.Dynamic.literal(recursive = true))
            NodeFsSnap.writeFileSync(path, content, js.Dynamic.literal(encoding = "utf8"))
        catch
            case _: js.JavaScriptException =>
                throw new UnsupportedOperationException(
                    "Snapshot file I/O is not available in browser environments; use Node.js for snapshot tests"
                )

    def readBytes(path: String): Maybe[Span[Byte]] =
        try
            if NodeFsSnap.existsSync(path) then
                val u8 = NodeFsSnap.readFileSync(path)
                val i8 = new js.typedarray.Int8Array(u8.buffer, u8.byteOffset, u8.length)
                Maybe.Present(Span.fromUnsafe(js.typedarray.int8Array2ByteArray(i8)))
            else
                Maybe.Absent
        catch
            case _: js.JavaScriptException =>
                throw new UnsupportedOperationException(
                    "Snapshot file I/O is not available in browser environments; use Node.js for snapshot tests"
                )

    def writeBytes(path: String, content: Span[Byte]): Unit =
        try
            val parent = NodePathSnap.dirname(path)
            if parent.nonEmpty && parent != path then
                NodeFsSnap.mkdirSync(parent, js.Dynamic.literal(recursive = true))
            val i8 = js.typedarray.byteArray2Int8Array(content.toArrayUnsafe)
            val u8 = new js.typedarray.Uint8Array(i8.buffer, i8.byteOffset, i8.length)
            NodeFsSnap.writeFileSync(path, u8)
        catch
            case _: js.JavaScriptException =>
                throw new UnsupportedOperationException(
                    "Snapshot file I/O is not available in browser environments; use Node.js for snapshot tests"
                )

end SnapshotStorePlatform
