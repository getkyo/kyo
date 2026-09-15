package kyo.test.snapshot.internal

import java.io.IOException
import kyo.Maybe
import kyo.Span
import kyo.internal.Platform
import kyo.internal.PlatformJs
import scala.scalajs.js

/** The members of Node's `fs` module the snapshot store uses. */
@js.native
private trait SnapshotNodeFs extends js.Object:
    def existsSync(path: String): Boolean                                 = js.native
    def readFileSync(path: String, encoding: String): String              = js.native
    def readFileSync(path: String): js.typedarray.Uint8Array              = js.native
    def writeFileSync(path: String, data: String, opts: js.Dynamic): Unit = js.native
    def writeFileSync(path: String, data: js.typedarray.Uint8Array): Unit = js.native
    def mkdirSync(path: String, opts: js.Dynamic): Unit                   = js.native
end SnapshotNodeFs

/** The member of Node's `path` module the snapshot store uses. */
@js.native
private trait SnapshotNodePath extends js.Object:
    def dirname(path: String): String = js.native
end SnapshotNodePath

/** JS snapshot file I/O backed by Node's `fs`.
  *
  * The modules are reached through `process.getBuiltinModule` at the call rather than a static import, which a browser cannot load, so a
  * test bundle that links snapshot support still loads there. A host without them fails the call with an `UnsupportedOperationException`
  * naming the host. A failure of the file-system call itself is an `IOException` carrying Node's error, as `java.nio.file` reports one on
  * the JVM and Native.
  */
private[snapshot] object SnapshotStorePlatform:

    private def module(id: String): js.Dynamic =
        PlatformJs.nodeBuiltin(id).getOrElse(
            throw new UnsupportedOperationException(
                s"Snapshot file I/O needs Node's $id module (Node, Bun or Deno); this host is ${Platform.host}"
            )
        )

    private def fs: SnapshotNodeFs     = module("node:fs").asInstanceOf[SnapshotNodeFs]
    private def path: SnapshotNodePath = module("node:path").asInstanceOf[SnapshotNodePath]

    /** Runs a file-system call on `target`, reporting a Node error as an `IOException` that names the path and keeps the error as cause. */
    private def io[A](operation: String, target: String)(f: SnapshotNodeFs => A): A =
        val node = fs
        try f(node)
        catch
            case e: js.JavaScriptException =>
                throw new IOException(s"Snapshot $operation failed for $target: ${e.getMessage}", e)
        end try
    end io

    private def createParent(node: SnapshotNodeFs, target: String): Unit =
        val parent = path.dirname(target)
        if parent.nonEmpty && parent != target then
            node.mkdirSync(parent, js.Dynamic.literal(recursive = true))
    end createParent

    def read(target: String): Maybe[String] =
        io("read", target) { node =>
            if node.existsSync(target) then Maybe.Present(node.readFileSync(target, "utf8"))
            else Maybe.Absent
        }

    def write(target: String, content: String): Unit =
        io("write", target) { node =>
            createParent(node, target)
            node.writeFileSync(target, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def readBytes(target: String): Maybe[Span[Byte]] =
        io("read", target) { node =>
            if node.existsSync(target) then
                val u8 = node.readFileSync(target)
                val i8 = new js.typedarray.Int8Array(u8.buffer, u8.byteOffset, u8.length)
                Maybe.Present(Span.fromUnsafe(js.typedarray.int8Array2ByteArray(i8)))
            else Maybe.Absent
        }

    def writeBytes(target: String, content: Span[Byte]): Unit =
        io("write", target) { node =>
            createParent(node, target)
            val i8 = js.typedarray.byteArray2Int8Array(content.toArrayUnsafe)
            val u8 = new js.typedarray.Uint8Array(i8.buffer, i8.byteOffset, i8.length)
            node.writeFileSync(target, u8)
        }

end SnapshotStorePlatform
