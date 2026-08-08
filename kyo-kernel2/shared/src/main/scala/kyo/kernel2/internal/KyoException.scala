package kyo.kernel2.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

// TODO ah, KyoException is in kyo-data. HOw can we avoid it here?
final private[kyo] class KyoException extends Exception(null, null, false, false):
    var frames: Chunk[(String, Frame)] = Chunk.empty
    var installed: Int                 = 0
    override def getMessage =
        frames.map((op, f) => "at " + KyoException.describe(op, f) + "(" + f.position.show + ")")
            .mkString("effect trace: ", "; ", "")
end KyoException

private[kyo] object KyoException:

    private inline def MaxFrames = 64

    def describe(op: String, f: Frame): String =
        val cls    = f.className.split('.').last.stripSuffix("$")
        val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
        op + " @ " + cls + "." + caller
    end describe

    def attach(ex: Throwable, op: String, frame: Frame): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) =>
                o.frames = o.frames.append((op, frame))
                if o.frames.size > MaxFrames then
                    val dropped = o.frames.size - MaxFrames
                    o.frames = o.frames.dropLeft(dropped)
                    o.installed = Integer.max(0, o.installed - dropped)
                end if
            case None =>
                val o = new KyoException
                o.frames = Chunk((op, frame))
                ex.addSuppressed(o)

    def install(ex: Throwable): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) if o.frames.size > o.installed =>
                val pending = o.frames.dropLeft(o.installed)
                val collapsed = pending.foldLeft(Chunk.empty[(String, Frame)]) { (acc, f) =>
                    if acc.nonEmpty && acc.last == f then acc else acc.append(f)
                }
                val fresh = collapsed.map { (op, f) =>
                    val cls    = f.className.split('.').last.stripSuffix("$")
                    val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
                    StackTraceElement(op + " @ " + cls, caller, f.position.fileName, f.position.lineNumber)
                }
                val user = ex.getStackTrace.filterNot(e => e.getClassName.startsWith("kyo.kernel2"))
                ex.setStackTrace((fresh.toArray ++ user))
                o.installed = o.frames.size
            case _ =>
                ()
end KyoException
