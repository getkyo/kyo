package kyo

import kyo.*
import kyo.internal.Trace
import os.*
import scala.io.*

class Files(val path: List[String]):

    def parts(using Trace): List[Files.Part] = path

    def osPath(using Trace) = path.foldLeft(os.root)(_ / _)

    def read(using Trace): String < IOs =
        IOs(os.read(osPath))

    def read(
        charSet: Codec = java.nio.charset.StandardCharsets.UTF_8,
        offset: Long = 0,
        count: Int = Int.MaxValue
    )(using Trace): String < IOs =
        IOs(os.read(osPath, charSet, offset, count))

    def readAll(extension: String)(using Trace): IndexedSeq[(String, String)] < IOs =
        IOs(os.list(osPath).filter(_.ext == extension).map(p => p.baseName -> os.read(p)))

    def readBytes(using Trace): Array[Byte] < IOs =
        IOs(os.read.bytes(osPath))

    def readBytes(
        offset: Long = 0,
        count: Int = Int.MaxValue
    )(path: String*)(using Trace): Array[Byte] < IOs =
        IOs(os.read.bytes(osPath, offset, count))

    def readLines: IndexedSeq[String] < IOs =
        IOs(os.read.lines(osPath))

    def readLines(
        charSet: Codec = java.nio.charset.StandardCharsets.UTF_8
    )(path: String*)(using Trace): IndexedSeq[String] < IOs =
        IOs(os.read.lines(osPath, charSet))

    def truncate(size: Long)(using Trace): Unit < IOs =
        IOs(os.truncate(osPath, size))

    def append(value: String, perms: PermSet = null, createFolders: Boolean = true)(using Trace) =
        IOs(os.write.append(osPath, value, perms, createFolders))

    def write(value: String, perms: PermSet = null, createFolders: Boolean = true)(using Trace) =
        IOs(os.write(osPath, value, perms, createFolders))

    def list(using Trace): IndexedSeq[Files] < IOs =
        list(true)

    def list(sort: Boolean)(using Trace): IndexedSeq[Files] < IOs =
        IOs(os.list(osPath, sort).map(p => new Files(p.segments.toList)))

    def list(extension: String)(using Trace): IndexedSeq[Files] < IOs =
        IOs(os.list(osPath).filter(_.last.endsWith(extension)).map(p =>
            new Files(p.segments.toList)
        ))

    def isDir(using Trace): Boolean < IOs =
        IOs(os.isDir(osPath))

    def isFile(using Trace): Boolean < IOs =
        IOs(os.isFile(osPath))

    def isLink(using Trace): Boolean < IOs =
        IOs(os.isLink(osPath))

    def mkDir(using Trace): Unit < IOs =
        IOs(os.makeDir.all(osPath))

    def move(
        to: Files,
        replaceExisting: Boolean = false,
        atomicMove: Boolean = false,
        createFolders: Boolean = true
    )(using Trace) =
        IOs(os.move(osPath, to.osPath, atomicMove, createFolders))

    def copy(
        to: Files,
        followLinks: Boolean = true,
        replaceExisting: Boolean = false,
        copyAttributes: Boolean = false,
        createFolders: Boolean = true,
        mergeFolders: Boolean = false
    )(using Trace): Unit < IOs =
        IOs(os.copy(
            osPath,
            to.osPath,
            followLinks,
            replaceExisting,
            copyAttributes,
            createFolders,
            mergeFolders
        ))

    def remove(using Trace): Boolean < IOs =
        remove(false)

    def remove(checkExists: Boolean)(using Trace): Boolean < IOs =
        IOs(os.remove(osPath, checkExists))

    def removeAll(using Trace): Unit < IOs =
        IOs(os.remove.all(osPath))

    def exists(using Trace): Boolean < IOs =
        exists(true)

    def exists(followLinks: Boolean)(using Trace): Boolean < IOs =
        IOs(os.exists(osPath, followLinks))

    override def toString = s"Files(\"${path.mkString("/")}\")"
end Files

object Files:

    type Part = String | Files

    def apply(path: List[Part]): Files =
        def loop(path: List[Part], acc: List[String]): List[String] =
            path match
                case _: Nil.type =>
                    acc.reverse
                case h :: t =>
                    h match
                        case h: String =>
                            loop(t, h.split('/').filter(_.nonEmpty).toList.reverse ::: acc)
                        case h: Files =>
                            loop(h.path ::: t, acc)
        new Files(loop(path, Nil))
    end apply

    def apply(path: Part*): Files =
        apply(path.toList)
end Files
