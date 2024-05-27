package kyo

import java.io.IOException
import java.nio.*
import java.nio.channels.FileChannel
import java.nio.charset.*
import java.nio.file.*
import java.nio.file.Path as JPath
import java.nio.file.attribute.BasicFileAttributes
import kyo.*
import kyo.Flat.unsafe.*
import scala.io.*
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*

object files:
    trait PathLike[Path]:
        def parts(path: Path): List[String]
        def toJPath(path: Path): JPath
        def toCustomPath(path: JPath): Path
    end PathLike

    given PathLike[String] with
        def parts(s: String): List[String]    = s.split('/').toList
        def toJPath(path: String): JPath      = Paths.get(path)
        def toCustomPath(path: JPath): String = path.toString
    end given

    given PathLike[List[String]] with
        def parts(s: List[String]): List[String]    = s.flatMap(_.split('/').toList)
        def toJPath(path: List[String]): JPath      = Paths.get(path.mkString("/"))
        def toCustomPath(path: JPath): List[String] = path.toString().split('/').toList
    end given

    given PathLike[JPath] with
        def parts(s: JPath): List[String]    = s.toString().split('/').toList
        def toJPath(path: JPath): JPath      = path
        def toCustomPath(path: JPath): JPath = path
    end given

    extension [Path: PathLike: Flat: Tag](path: Path)
        def toJPath: JPath      = implicitly[PathLike[Path]].toJPath(path)
        def parts: List[String] = implicitly[PathLike[Path]].parts(path)
        def /[Path2: PathLike](p2: Path2): JPath =
            implicitly[PathLike[Path]].toJPath(path).resolve(implicitly[PathLike[Path2]].toJPath(p2))
        def osPath: JPath = toJPath.toAbsolutePath()

        /** Methods to read files completely
          */
        def read: String < IOs =
            IOs(Files.readString(path.toJPath))

        def read(
            charset: Charset = StandardCharsets.UTF_8,
            offset: Long = 0,
            count: Int = Int.MaxValue
        ): String < IOs =
            IOs(Files.readString(path.toJPath, charset))

        def readAll(extension: String): Seq[(String, String)] < IOs =
            list(extension).map { paths =>
                Seqs.map(paths) { p =>
                    p.read.map(content => p.getName(0).toString() -> content)
                }
            }

        def readBytes: Array[Byte] < IOs =
            IOs(Files.readAllBytes(path.toJPath))

        def readLines: List[String] < IOs =
            IOs(Files.readAllLines(path.toJPath).asScala.toList)

        def readLines(
            charSet: Charset = java.nio.charset.StandardCharsets.UTF_8
        ): List[String] < IOs =
            IOs(Files.readAllLines(path.toJPath, charSet).asScala.toList)

        /** Methods to append and write to files
          */

        /** Appends a String to this path.
          */
        def append(value: String, createFolders: Boolean = true): Unit < IOs =
            if createFolders then IOs(Files.writeString(toJPath, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.writeString(toJPath, value, StandardOpenOption.APPEND)).map(_ => ())
                    else IOs.unit
                }

        /** Appends a Bytes Array to this path.
          */
        def appendBytes(value: Array[Byte], createFolders: Boolean = true): Unit < IOs =
            if createFolders then IOs(Files.write(toJPath, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.write(toJPath, value, StandardOpenOption.APPEND)).map(_ => ())
                    else IOs.unit
                }

        /** Appends lines of String to this path.
          */
        def appendLines(value: List[String], createFolders: Boolean = true): Unit < IOs =
            if createFolders then
                IOs(Files.write(toJPath, value.asJava, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.write(toJPath, value.asJava, StandardOpenOption.APPEND)).map(_ => ())
                    else IOs.unit
                }

        /** Writes a String to this path.
          */
        def write(value: String, createFolders: Boolean = true): Unit < IOs =
            if createFolders then IOs(Files.writeString(toJPath, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.writeString(toJPath, value, StandardOpenOption.WRITE)).map(_ => ())
                    else IOs.unit
                }

        /** Writes a Bytes Array to this path.
          */
        def writeBytes(value: Array[Byte], createFolders: Boolean = true): Unit < IOs =
            if createFolders then IOs(Files.write(toJPath, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.write(toJPath, value, StandardOpenOption.WRITE)).map(_ => ())
                    else IOs.unit
                }

        /** Writes lines of String to this path.
          */
        def writeLines(value: List[String], createFolders: Boolean = true): Unit < IOs =
            if createFolders then
                IOs(Files.write(toJPath, value.asJava, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).map(_ => ())
            else
                toJPath.getParent().exists.map { parentExists =>
                    if parentExists then IOs(Files.write(toJPath, value.asJava, StandardOpenOption.WRITE)).map(_ => ())
                    else IOs.unit
                }

        /** Methods to read files into streams
          */

        /** Reads a file returning its contents as a Stream of Strings
          */
        def readStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8): Stream[Unit, String, Fibers] < Resources =
            Resources.acquireRelease(FileChannel.open(toJPath))(ch => ch.close()).map { fileCh =>
                for
                    ch <- Channels.init[Chunk[String] | Stream.Done](16)
                    _  <- readLoop(fileCh, ByteBuffer.allocate(2048), ch, charset)
                yield Streams.initChannel(ch)
            }

        /** Reads a file returning its contents as a Stream of Lines
          */
        def readLinesStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8): Stream[Unit, String, Fibers] < Resources =
            Resources.acquireRelease(FileChannel.open(toJPath))(ch => ch.close()).map { fileCh =>
                Channels.init[Chunk[String] | Stream.Done](16).map { ch =>
                    readLoop(fileCh, ByteBuffer.allocate(2048), ch, charset).map(_ => Streams.initChannel(ch))
                }
            }

        /** Reads a file returning its contents as a Stream of Bytes
          */
        def readBytesStream: Stream[Unit, Byte, Fibers] < Resources =
            Resources.acquireRelease(FileChannel.open(toJPath))(ch => ch.close()).map { fileCh =>
                Channels.init[Chunk[Byte] | Stream.Done](16).map { ch =>
                    readLoopBytes(fileCh, ByteBuffer.allocate(2048), ch).map(_ => Streams.initChannel(ch))
                }
            }

        private def readLoop(
            fileChannel: FileChannel,
            buf: ByteBuffer,
            channel: Channel[Chunk[String] | Stream.Done],
            charset: Charset
        ) =
            for
                bytesRead <- IOs(fileChannel.read(buf))
                f = Loops.transform(bytesRead) { b =>
                    if b == -1 then
                        for
                            _ <- channel.put(Stream.Done)
                        yield Loops.done(())
                    else
                        for
                            s <- IOs {
                                val arr = new Array[Byte](b)
                                buf.get(arr)
                                new String(arr, charset)
                            }
                            _            <- channel.put(Chunks.init(s))
                            newBytesRead <- IOs(fileChannel.read(buf))
                        yield Loops.continue(newBytesRead)
                }
                _ <- IOs(f)
            yield ()

        private def readLoopBytes(fileChannel: FileChannel, buf: ByteBuffer, channel: Channel[Chunk[Byte] | Stream.Done]) =
            IOs(fileChannel.read(buf)).map { bytesRead =>
                Loops.transform(bytesRead) { b =>
                    if b == -1 then channel.put(Stream.Done).map(_ => Loops.done(b))
                    else
                        val arr = new Array[Byte](b)
                        buf.get(arr)
                        channel.put(Chunks.initSeq(arr.toSeq)).andThen(fileChannel.read(buf)).map(Loops.continue)
                }
            }

        /** Other file utilities
          */

        /** Truncates the content of this file
          */
        def truncate(size: Long): Unit < Resources =
            val resources = Resources
                .acquireRelease(FileChannel.open(toJPath, StandardOpenOption.WRITE))(ch => ch.close())
                .map(ch => ch.truncate(size))
                .map(_ => ())
        end truncate

        /** List contents of path
          */
        def list: IndexedSeq[JPath] < IOs =
            IOs(Files.list(toJPath).toScala(LazyList).toIndexedSeq)

        /** List contents of path with given extension
          */
        def list(extension: String): IndexedSeq[JPath] < IOs =
            IOs(Files.list(toJPath).toScala(LazyList)).map(_.filter(path =>
                path.getFileName().toString().split('.').toList.lastOption.getOrElse("") == extension
            )).map(_.toIndexedSeq)

        /** Returns if the path exists
          */
        def exists: Boolean < IOs =
            exists(true)

        /** Returns if the path exists
          */
        def exists(followLinks: Boolean): Boolean < IOs =
            val path = toJPath
            if path == null then IOs(false)
            else if followLinks then IOs(Files.exists(path))
            else IOs(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        end exists

        /** Returns if the path represents a directory
          */
        def isDir: Boolean < IOs =
            IOs(Files.isDirectory(toJPath))

        /** Returns if the path represents a file
          */
        def isFile: Boolean < IOs =
            IOs(Files.isRegularFile(toJPath))

        /** Returns if the path represents a symbolic link
          */
        def isLink: Boolean < IOs =
            IOs(Files.isSymbolicLink(toJPath))

        /** Creates a directory in this path
          */
        def mkDir: Unit < IOs =
            toJPath.getParent().exists.map { parentsExist =>
                if parentsExist == true then IOs(Files.createDirectory(toJPath))
                else IOs(Files.createDirectories(toJPath))
            }.map(_ => ())

        /** Creates a directory in this path
          */
        def mkFile: Unit < IOs =
            toJPath.getParent().exists.map { parentsExist =>
                if parentsExist == true then IOs(Files.createDirectory(toJPath))
                else IOs(Files.createDirectories(toJPath))
            }.map(_ => ())
        end mkFile

        /** Moves the content of this path to another path
          */
        def move[Path2: PathLike: Flat: Tag](
            to: Path2,
            replaceExisting: Boolean = false,
            atomicMove: Boolean = false,
            createFolders: Boolean = true
        ): Unit < IOs =
            val opts = (if atomicMove then List(StandardCopyOption.ATOMIC_MOVE) else Nil) ++ (if replaceExisting then
                                                                                                  List(StandardCopyOption.REPLACE_EXISTING)
                                                                                              else Nil)
            toJPath.getParent().exists.map { parentExists =>
                (parentExists, createFolders) match
                    case (true, _)     => IOs(Files.move(toJPath, to.toJPath, opts*)).map(_ => ())
                    case (false, true) => toJPath.getParent().mkDir.andThen(IOs(Files.move(toJPath, to.toJPath, opts*)).map(_ => ()))
                    case _             => IOs {}
            }
        end move

        /** Copies the content of this path to another path
          */
        def copy[Path2: PathLike: Flat: Tag](
            to: Path2,
            followLinks: Boolean = true,
            replaceExisting: Boolean = false,
            copyAttributes: Boolean = false,
            createFolders: Boolean = true,
            mergeFolders: Boolean = false
        ): Unit < IOs =
            val opts = (if followLinks then List.empty[CopyOption] else List[CopyOption](LinkOption.NOFOLLOW_LINKS)) ++
                (if copyAttributes then List[CopyOption](StandardCopyOption.COPY_ATTRIBUTES) else List.empty[CopyOption]) ++
                (if replaceExisting then List[CopyOption](StandardCopyOption.REPLACE_EXISTING) else List.empty[CopyOption])
            toJPath.getParent().exists.map { parentExists =>
                (parentExists, createFolders) match
                    case (true, _)     => IOs(Files.copy(toJPath, to.toJPath, opts*)).map(_ => ())
                    case (false, true) => toJPath.getParent().mkDir.andThen(IOs(Files.copy(toJPath, to.toJPath, opts*)).map(_ => ()))
                    case _             => IOs.unit
            }
        end copy

        /** Removes this path if it is empty
          */
        def remove: Boolean < IOs =
            remove(false)

        /** Removes this path if it is empty
          */
        def remove(checkExists: Boolean): Boolean < IOs =
            IOs {
                if checkExists then
                    Files.delete(toJPath)
                    true
                else
                    Files.deleteIfExists(toJPath)
            }

        /** Removes this path and all its contents
          */
        def removeAll: Unit < IOs =
            IOs {
                val path = toJPath
                if Files.exists(path) then
                    val visitor = new SimpleFileVisitor[JPath]:
                        override def visitFile(path: JPath, basicFileAttributes: BasicFileAttributes): FileVisitResult =
                            Files.delete(path)
                            FileVisitResult.CONTINUE

                        override def postVisitDirectory(path: JPath, ioException: IOException): FileVisitResult =
                            Files.delete(path)
                            FileVisitResult.CONTINUE
                    Files.walkFileTree(path, visitor)
                end if
                ()
            }

        /** Creates a stream of the contents of this path with maximum depth
          */
        def walk: Stream[Unit, Path, Any] < IOs =
            walk(Int.MaxValue)

        /** Creates a stream of the contents of this path with given depth
          */
        def walk(maxDepth: Int): Stream[Unit, Path, Any] < IOs =
            IOs(Files.walk(toJPath).toScala(LazyList)).map(seq =>
                Streams.initSeq(seq).transform(p => implicitly[PathLike[Path]].toCustomPath(p))
            )

        def toString: String = toJPath.toString
    end extension

    extension [S](stream: Stream[Unit, Byte, S])
        def sink[Path: PathLike: Flat: Tag](path: Path): Unit < (Resources & S) =
            Resources.acquireRelease(FileChannel.open(path.toJPath, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
                stream.transformChunks(bytes =>
                    IOs(fileCh.write(ByteBuffer.wrap(bytes.toArray))).map(_ => Chunk.empty)
                ).runDiscard
            }
    end extension

    extension [S](stream: Stream[Unit, String, S])
        @scala.annotation.targetName("stringSink")
        def sink[Path: PathLike: Flat: Tag](path: Path, charset: Codec = java.nio.charset.StandardCharsets.UTF_8): Unit < (Resources & S) =
            Resources.acquireRelease(FileChannel.open(path.toJPath, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
                stream.transform(s =>
                    IOs(fileCh.write(ByteBuffer.wrap(s.getBytes))).map(_ => ())
                ).runDiscard
            }

        def sinkLines[Path: PathLike: Flat: Tag](
            path: Path,
            charset: Codec = java.nio.charset.StandardCharsets.UTF_8
        ): Unit < (Resources & S) =
            Resources.acquireRelease(FileChannel.open(path.toJPath, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
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
end files
