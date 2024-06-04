package kyo

import java.io.BufferedReader
import java.io.IOException
import java.nio.*
import java.nio.channels.FileChannel
import java.nio.charset.*
import java.nio.file.*
import java.nio.file.Files as JFiles
import java.nio.file.Path as JPath
import java.nio.file.attribute.BasicFileAttributes
import kyo.*
import kyo.internal.Trace
import scala.io.*
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*

class Files(val path: List[String]):
    def toJava: JPath                = Paths.get(path.mkString("/"))
    lazy val parts: List[Files.Part] = path

    /** Methods to read files completely
      */
    def read(using Trace): String < IOs =
        IOs(JFiles.readString(toJava))

    def read(charset: Charset)(using Trace): String < IOs =
        IOs(JFiles.readString(toJava, charset))

    def readAll(extension: String)(using Trace): Seq[(String, String)] < IOs =
        list(extension).map { paths =>
            Seqs.map(paths) { p =>
                p.read.map(content => p.toJava.getName(0).toString() -> content)
            }
        }

    def readBytes(using Trace): Array[Byte] < IOs =
        IOs(JFiles.readAllBytes(toJava))

    def readLines(using Trace): List[String] < IOs =
        IOs(JFiles.readAllLines(toJava).asScala.toList)

    def readLines(
        charSet: Charset = java.nio.charset.StandardCharsets.UTF_8
    )(using Trace): List[String] < IOs =
        IOs(JFiles.readAllLines(toJava, charSet).asScala.toList)

    /** Methods to append and write to files
      */

    /** Appends a String to this path.
      */
    def append(value: String, createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then IOs(JFiles.writeString(toJava, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.writeString(toJava, value, StandardOpenOption.APPEND)).unit
                else IOs.unit
            }

    /** Appends a Bytes Array to this path.
      */
    def appendBytes(value: Array[Byte], createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then IOs(JFiles.write(toJava, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.write(toJava, value, StandardOpenOption.APPEND)).unit
                else IOs.unit
            }

    /** Appends lines of String to this path.
      */
    def appendLines(value: List[String], createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then
            IOs(JFiles.write(toJava, value.asJava, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.write(toJava, value.asJava, StandardOpenOption.APPEND)).unit
                else IOs.unit
            }

    /** Writes a String to this path.
      */
    def write(value: String, createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then IOs(JFiles.writeString(toJava, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.writeString(toJava, value, StandardOpenOption.WRITE)).unit
                else IOs.unit
            }

    /** Writes a Bytes Array to this path.
      */
    def writeBytes(value: Array[Byte], createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then IOs(JFiles.write(toJava, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.write(toJava, value, StandardOpenOption.WRITE)).unit
                else IOs.unit
            }

    /** Writes lines of String to this path.
      */
    def writeLines(value: List[String], createFolders: Boolean = true)(using Trace): Unit < IOs =
        if createFolders then
            IOs(JFiles.write(toJava, value.asJava, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
        else
            IOs(javaExists(toJava.getParent())).map { parentExists =>
                if parentExists then IOs(JFiles.write(toJava, value.asJava, StandardOpenOption.WRITE)).unit
                else IOs.unit
            }

    /** Methods to read files into streams
      */

    /** Reads a file returning its contents as a Stream of Strings
      */
    def readStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Trace): Stream[Unit, String, Fibers] < Resources =
        readLoop[String, Array[Byte], (FileChannel, ByteBuffer)](
            (FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
            ch => ch._1.close(),
            readOnceBytes,
            arr => Chunks.init(new String(arr, charset))
        )

    /** Reads a file returning its contents as a Stream of Lines
      */
    def readLinesStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Trace): Stream[Unit, String, Fibers] < Resources =
        readLoop[String, String, BufferedReader](
            JFiles.newBufferedReader(toJava, Charset.defaultCharset()),
            reader => reader.close(),
            readOnceLines,
            line => Chunks.init(line)
        )

    /** Reads a file returning its contents as a Stream of Bytes
      */
    def readBytesStream(using Trace): Stream[Unit, Byte, Fibers] < Resources =
        readLoop[Byte, Array[Byte], (FileChannel, ByteBuffer)](
            (FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
            ch => ch._1.close(),
            readOnceBytes,
            arr => Chunks.initSeq(arr.toSeq)
        )

    private def readOnceLines(reader: BufferedReader) =
        IOs {
            val line = reader.readLine()
            if line == null then None else Some(line)
        }

    private def readOnceBytes(res: (FileChannel, ByteBuffer)) =
        IOs {
            val (fileChannel, buf) = res
            val bytesRead          = fileChannel.read(buf)
            if bytesRead < 1 then None
            else
                buf.flip()
                val arr = new Array[Byte](bytesRead)
                buf.get(arr)
                Some(arr)
            end if
        }

    private def readLoop[A, ReadTpe, Res](
        acquire: Res,
        release: Res => Unit < Fibers,
        readOnce: Res => Option[ReadTpe] < IOs,
        writeOnce: ReadTpe => Chunk[A]
    )(using Tag[Streams[A]]) =
        Resources.acquireRelease(acquire)(release).map { res =>
            Channels.init[Chunk[A] | Stream.Done](16).map { ch =>
                readOnce(res).map { case state =>
                    Loops.transform(state) {
                        case None => ch.put(Stream.Done).map(_ => Loops.done(()))
                        case Some(content) =>
                            for
                                _     <- ch.put(writeOnce(content))
                                state <- readOnce(res)
                            yield Loops.continue(state)
                    }
                }.map(_ => Streams.initChannel(ch))
            }
        }

    /** Other file utilities
      */

    /** Truncates the content of this file
      */
    def truncate(size: Long)(using Trace): FileChannel < Resources =
        Resources
            .acquireRelease(FileChannel.open(toJava, StandardOpenOption.WRITE))(ch => ch.close())
            .map { ch =>
                ch.truncate(size)
                ch
            }
    end truncate

    /** List contents of path
      */
    def list(using Trace): IndexedSeq[Files] < IOs =
        IOs(JFiles.list(toJava).toScala(LazyList).toIndexedSeq).map(_.map(path => Files(path.toString)))

    /** List contents of path with given extension
      */
    def list(extension: String)(using Trace): IndexedSeq[Files] < IOs =
        IOs(JFiles.list(toJava).toScala(LazyList)).map(_.filter(path =>
            path.getFileName().toString().split('.').toList.lastOption.getOrElse("") == extension
        )).map(_.toIndexedSeq).map(_.map(path => Files(path.toString)))

    /** Returns if the path exists
      */
    def exists(using Trace): Boolean < IOs =
        exists(true)

    /** Returns if the path exists
      */
    def exists(followLinks: Boolean)(using Trace): Boolean < IOs =
        val path = toJava
        if path == null then IOs(false)
        else if followLinks then IOs(JFiles.exists(path))
        else IOs(JFiles.exists(path, LinkOption.NOFOLLOW_LINKS))
    end exists

    private def javaExists(jPath: JPath): Boolean =
        if jPath == null then false
        else JFiles.exists(jPath, LinkOption.NOFOLLOW_LINKS)

    /** Returns if the path represents a directory
      */
    def isDir(using Trace): Boolean < IOs =
        IOs(JFiles.isDirectory(toJava))

    /** Returns if the path represents a file
      */
    def isFile(using Trace): Boolean < IOs =
        IOs(JFiles.isRegularFile(toJava))

    /** Returns if the path represents a symbolic link
      */
    def isLink(using Trace): Boolean < IOs =
        IOs(JFiles.isSymbolicLink(toJava))

    /** Creates a directory in this path
      */
    def mkDir(using Trace): Unit < IOs =
        IOs(javaExists(toJava.getParent())).map { parentsExist =>
            if parentsExist == true then IOs(JFiles.createDirectory(toJava))
            else IOs(JFiles.createDirectories(toJava))
        }.unit

    /** Creates a directory in this path
      */
    def mkFile(using Trace): Unit < IOs =
        IOs(javaExists(toJava.getParent())).map { parentsExist =>
            if parentsExist == true then IOs(JFiles.createDirectory(toJava))
            else IOs(JFiles.createDirectories(toJava))
        }.unit
    end mkFile

    /** Moves the content of this path to another path
      */
    def move(
        to: Files,
        replaceExisting: Boolean = false,
        atomicMove: Boolean = false,
        createFolders: Boolean = true
    )(using Trace): Unit < IOs =
        val opts = (if atomicMove then List(StandardCopyOption.ATOMIC_MOVE) else Nil) ++ (if replaceExisting then
                                                                                              List(StandardCopyOption.REPLACE_EXISTING)
                                                                                          else Nil)
        IOs(javaExists(toJava.getParent())).map { parentExists =>
            (parentExists, createFolders) match
                case (true, _)     => IOs(JFiles.move(toJava, to.toJava, opts*)).unit
                case (false, true) => Files(toJava.getParent().toString).mkDir.andThen(IOs(JFiles.move(toJava, to.toJava, opts*)).unit)
                case _             => ()
        }
    end move

    /** Copies the content of this path to another path
      */
    def copy(
        to: Files,
        followLinks: Boolean = true,
        replaceExisting: Boolean = false,
        copyAttributes: Boolean = false,
        createFolders: Boolean = true,
        mergeFolders: Boolean = false
    )(using Trace): Unit < IOs =
        val opts = (if followLinks then List.empty[CopyOption] else List[CopyOption](LinkOption.NOFOLLOW_LINKS)) ++
            (if copyAttributes then List[CopyOption](StandardCopyOption.COPY_ATTRIBUTES) else List.empty[CopyOption]) ++
            (if replaceExisting then List[CopyOption](StandardCopyOption.REPLACE_EXISTING) else List.empty[CopyOption])
        IOs(javaExists(toJava.getParent())).map { parentExists =>
            (parentExists, createFolders) match
                case (true, _)     => IOs(JFiles.copy(toJava, to.toJava, opts*)).unit
                case (false, true) => Files(toJava.getParent().toString).mkDir.andThen(IOs(JFiles.copy(toJava, to.toJava, opts*)).unit)
                case _             => IOs.unit
        }
    end copy

    /** Removes this path if it is empty
      */
    def remove(using Trace): Boolean < IOs =
        remove(false)

    /** Removes this path if it is empty
      */
    def remove(checkExists: Boolean)(using Trace): Boolean < IOs =
        IOs {
            if checkExists then
                JFiles.delete(toJava)
                true
            else
                JFiles.deleteIfExists(toJava)
        }

    /** Removes this path and all its contents
      */
    def removeAll(using Trace): Unit < IOs =
        IOs {
            val path = toJava
            if javaExists(path) then
                val visitor = new SimpleFileVisitor[JPath]:
                    override def visitFile(path: JPath, basicFileAttributes: BasicFileAttributes): FileVisitResult =
                        JFiles.delete(path)
                        FileVisitResult.CONTINUE

                    override def postVisitDirectory(path: JPath, ioException: IOException): FileVisitResult =
                        JFiles.delete(path)
                        FileVisitResult.CONTINUE
                discard(JFiles.walkFileTree(path, visitor))
            end if
            ()
        }

    /** Creates a stream of the contents of this path with maximum depth
      */
    def walk(using Trace): Stream[Unit, Files, Any] < IOs =
        walk(Int.MaxValue)

    /** Creates a stream of the contents of this path with given depth
      */
    def walk(maxDepth: Int)(using Trace): Stream[Unit, Files, Any] < IOs =
        IOs(JFiles.walk(toJava).toScala(LazyList)).map(seq =>
            Streams.initSeq(seq).transform(path => Files(path.toString))
        )

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

extension [S](stream: Stream[Unit, Byte, S])
    def sink(path: Files)(using Trace): Unit < (Resources & S) =
        Resources.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
            stream.transformChunks(bytes =>
                IOs(fileCh.write(ByteBuffer.wrap(bytes.toArray))).map(_ => Chunk.empty[Byte])
            ).runDiscard
        }
end extension

extension [S](stream: Stream[Unit, String, S])
    @scala.annotation.targetName("stringSink")
    def sink(path: Files, charset: Codec = java.nio.charset.StandardCharsets.UTF_8)(using Trace): Unit < (Resources & S) =
        Resources.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
            stream.transform(s =>
                IOs(fileCh.write(ByteBuffer.wrap(s.getBytes))).unit
            ).runDiscard
        }

    def sinkLines(
        path: Files,
        charset: Codec = java.nio.charset.StandardCharsets.UTF_8
    )(using Trace): Unit < (Resources & S) =
        Resources.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
            stream.transform(line =>
                IOs {
                    fileCh.write(ByteBuffer.wrap(line.getBytes))
                    fileCh.write(ByteBuffer.wrap(System.lineSeparator().getBytes))
                }.map(_ =>
                    ()
                )
            ).runDiscard
        }
end extension
