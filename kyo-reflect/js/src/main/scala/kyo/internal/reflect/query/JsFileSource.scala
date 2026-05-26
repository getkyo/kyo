package kyo.internal.reflect.query

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as jsGlobal

/** JS implementation of `FileSource`.
  *
  * Node.js path (process object present): uses `fs.readFileSync`, `fs.readdirSync`, `fs.statSync`.
  *
  * Browser path (no process object): all `read` and `list` calls return `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`
  * because there is no filesystem access in browsers. The `exists` method returns `false` and `stat` returns the same error. Consumers on
  * the browser platform should use `Reflect.Classpath.fromPickles` directly.
  *
  * Node.js Buffer detection: `fs.readFileSync(path)` returns a Node.js `Buffer` (which is an `Int8Array` subclass). The `length` property
  * gives the byte count; element access `buf(i)` returns the byte at position `i`. We copy element-by-element into a Scala `Array[Byte]`.
  */
object JsFileSource extends FileSource:

    /** Detect Node.js environment (process.platform must be defined). */
    private val isNode: Boolean =
        js.typeOf(jsGlobal.process) != "undefined" &&
            js.typeOf(jsGlobal.process.platform) != "undefined"

    private val browserError: ReflectError.FileNotFound = ReflectError.FileNotFound("browser: use fromPickles")

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        if !isNode then Abort.fail(browserError)
        else
            Sync.defer:
                try
                    val fs  = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val buf = fs.readFileSync(path)
                    val len = buf.length.asInstanceOf[Int]
                    val arr = new Array[Byte](len)
                    var i   = 0
                    while i < len do
                        arr(i) = buf.applyDynamic("readInt8")(i).asInstanceOf[Byte]
                        i += 1
                    arr
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
        if !isNode then Abort.fail(ReflectError.SnapshotIoError("browser: no filesystem"))
        else
            Sync.defer:
                try
                    val fs      = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val int8Arr = new js.typedarray.Int8Array(bytes.length)
                    var i       = 0
                    while i < bytes.length do
                        int8Arr(i) = bytes(i)
                        i += 1
                    val _ = fs.writeFileSync(path, int8Arr)
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
        if !isNode then Abort.fail(ReflectError.SnapshotIoError("browser: no filesystem"))
        else
            Sync.defer:
                try
                    val fs = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val _  = fs.renameSync(from, to)
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
        if !isNode then Abort.fail(ReflectError.SnapshotIoError("browser: no filesystem"))
        else
            Sync.defer:
                try
                    val fs = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val _  = fs.mkdirSync(path, js.Dynamic.literal(recursive = true))
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else if !isNode then Abort.fail(browserError)
        else
            Sync.defer:
                try
                    val fs    = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val path0 = jsGlobal.require("path").asInstanceOf[js.Dynamic]
                    Chunk.from(listNodeSyncMulti(fs, path0, dir, suffixes))
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.FileNotFound(s"$dir: ${ex.getMessage}"))

    def exists(path: String)(using Frame): Boolean < Sync =
        if !isNode then false
        else
            Sync.defer:
                try
                    val fs = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    fs.existsSync(path).asInstanceOf[Boolean]
                catch
                    case _: Throwable => false

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
        if !isNode then Abort.fail(browserError)
        else
            Sync.defer:
                try
                    val fs    = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
                    val stat  = fs.statSync(path).asInstanceOf[js.Dynamic]
                    val mtime = stat.mtimeMs.asInstanceOf[Double].toLong
                    val size  = stat.size.asInstanceOf[Double].toLong
                    FileSource.FileStat(mtime, size)
                catch
                    case ex: Throwable =>
                        Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))

    /** Synchronous recursive directory listing (Node.js only), single suffix. */
    private def listNodeSync(
        fs: js.Dynamic,
        path0: js.Dynamic,
        dir: String,
        suffix: String
    ): Seq[String] =
        try
            val entries = fs.readdirSync(dir).asInstanceOf[js.Array[String]]
            entries.toSeq.flatMap: entry =>
                val full = path0.join(dir, entry).asInstanceOf[String]
                val stat = fs.statSync(full).asInstanceOf[js.Dynamic]
                if stat.isFile().asInstanceOf[Boolean] && entry.endsWith(suffix) then Seq(full)
                else if stat.isDirectory().asInstanceOf[Boolean] then listNodeSync(fs, path0, full, suffix)
                else Seq.empty
        catch
            case _: Throwable => Seq.empty

    /** Synchronous recursive directory listing (Node.js only), multi-suffix: single walk matching any suffix. */
    private def listNodeSyncMulti(
        fs: js.Dynamic,
        path0: js.Dynamic,
        dir: String,
        suffixes: Chunk[String]
    ): Seq[String] =
        try
            val entries = fs.readdirSync(dir).asInstanceOf[js.Array[String]]
            entries.toSeq.flatMap: entry =>
                val full = path0.join(dir, entry).asInstanceOf[String]
                val stat = fs.statSync(full).asInstanceOf[js.Dynamic]
                if stat.isFile().asInstanceOf[Boolean] then
                    var i     = 0
                    var found = false
                    while i < suffixes.length && !found do
                        if entry.endsWith(suffixes(i)) then found = true
                        i += 1
                    if found then Seq(full) else Seq.empty
                else if stat.isDirectory().asInstanceOf[Boolean] then
                    listNodeSyncMulti(fs, path0, full, suffixes)
                else Seq.empty
                end if
        catch
            case _: Throwable => Seq.empty

end JsFileSource
