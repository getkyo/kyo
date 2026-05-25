package kyo.internal.reflect.reads

import kyo.*
import kyo.Reflect.*

/** Runtime helper for macro-derived Reads instances.
  *
  * Sequences N per-field reader lambdas over a Symbol and constructs the product via the Array[Any] => A bridge.
  *
  * `readFieldsLazy` is used for recursive case classes: slot indices marked in `isRecSlot` delegate to `self.read(sym)` (Self field), and
  * indices marked in `isChunkSelf` delegate to reading declarations via `self` (Chunk[Self] field). Product width is capped at 64 fields by
  * the macro at expansion time.
  */
object ReflectRuntime:

    def readFields[A](
        sym: Reflect.Symbol,
        readers: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])],
        construct: Array[Any] => A
    )(using Frame): A < (Sync & Async & Abort[ReflectError]) =
        val n      = readers.length
        val result = new Array[Any](n)
        loop(sym, readers, result, 0, n, construct)
    end readFields

    def readFieldsLazy[A](
        sym: Reflect.Symbol,
        nonRecReaders: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])],
        isRecSlot: Long,
        isChunkSelf: Long,
        self: => Reflect.Reads[A],
        construct: Array[Any] => A
    )(using Frame): A < (Sync & Async & Abort[ReflectError]) =
        val n      = nonRecReaders.length
        val result = new Array[Any](n)
        loopLazy(sym, nonRecReaders, result, 0, n, isRecSlot, isChunkSelf, self, construct)
    end readFieldsLazy

    private def loop[A](
        sym: Reflect.Symbol,
        readers: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])],
        result: Array[Any],
        idx: Int,
        n: Int,
        construct: Array[Any] => A
    )(using Frame): A < (Sync & Async & Abort[ReflectError]) =
        if idx >= n then
            Kyo.lift(construct(result))
        else
            readers(idx)(sym).map { v =>
                result(idx) = v
                loop(sym, readers, result, idx + 1, n, construct)
            }

    private def loopLazy[A](
        sym: Reflect.Symbol,
        nonRecReaders: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])],
        result: Array[Any],
        idx: Int,
        n: Int,
        isRecSlot: Long,
        isChunkSelf: Long,
        self: => Reflect.Reads[A],
        construct: Array[Any] => A
    )(using Frame): A < (Sync & Async & Abort[ReflectError]) =
        if idx >= n then
            Kyo.lift(construct(result))
        else
            val bit = 1L << idx
            val readTask: Any < (Sync & Async & Abort[ReflectError]) =
                if (isRecSlot & bit) != 0L then
                    self.read(sym)
                else if (isChunkSelf & bit) != 0L then
                    sym.declarations.flatMap: decls =>
                        Kyo.foreach(decls.filter(d => self.symbolKinds.contains(d.kind)))(d => self.read(d))
                else
                    nonRecReaders(idx)(sym)

            readTask.map { v =>
                result(idx) = v
                loopLazy(sym, nonRecReaders, result, idx + 1, n, isRecSlot, isChunkSelf, self, construct)
            }

end ReflectRuntime
