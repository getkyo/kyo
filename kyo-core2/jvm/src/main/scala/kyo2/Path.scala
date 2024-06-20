// package kyo2

// import java.io.BufferedReader
// import java.io.IOException
// import java.nio.*
// import java.nio.channels.FileChannel
// import java.nio.charset.*
// import java.nio.file.*
// import java.nio.file.Files as JFiles
// import java.nio.file.Path as JPath
// import java.nio.file.attribute.BasicFileAttributes
// import kyo.Tag
// import kyo2.*
// import scala.io.*
// import scala.jdk.CollectionConverters.*
// import scala.jdk.StreamConverters.*

// class Path(val path: List[String]):
//     def toJava: JPath               = Paths.get(path.mkString("/"))
//     lazy val parts: List[Path.Part] = path

//     /** Methods to read files completely
//       */
//     def read(using Frame): String < IO =
//         IO(JFiles.readString(toJava))

//     def read(charset: Charset)(using Frame): String < IO =
//         IO(JFiles.readString(toJava, charset))

//     def readAll(extension: String)(using Frame): Seq[(String, String)] < IO =
//         list(extension).map { paths =>
//             Kyo.seq.map(paths) { p =>
//                 p.read.map(content => p.toJava.getName(0).toString() -> content)
//             }
//         }

//     def readBytes(using Frame): Array[Byte] < IO =
//         IO(JFiles.readAllBytes(toJava))

//     def readLines(using Frame): List[String] < IO =
//         IO(JFiles.readAllLines(toJava).asScala.toList)

//     def readLines(
//         charSet: Charset = java.nio.charset.StandardCharsets.UTF_8
//     )(using Frame): List[String] < IO =
//         IO(JFiles.readAllLines(toJava, charSet).asScala.toList)

//     /** Methods to append and write to files
//       */

//     /** Appends a String to this path.
//       */
//     def append(value: String, createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then IO(JFiles.writeString(toJava, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.writeString(toJava, value, StandardOpenOption.APPEND)).unit
//                 else IO.unit
//             }

//     /** Appends a Bytes Array to this path.
//       */
//     def appendBytes(value: Array[Byte], createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then IO(JFiles.write(toJava, value, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.write(toJava, value, StandardOpenOption.APPEND)).unit
//                 else IO.unit
//             }

//     /** Appends lines of String to this path.
//       */
//     def appendLines(value: List[String], createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then
//             IO(JFiles.write(toJava, value.asJava, StandardOpenOption.APPEND, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.write(toJava, value.asJava, StandardOpenOption.APPEND)).unit
//                 else IO.unit
//             }

//     /** Writes a String to this path.
//       */
//     def write(value: String, createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then IO(JFiles.writeString(toJava, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.writeString(toJava, value, StandardOpenOption.WRITE)).unit
//                 else IO.unit
//             }

//     /** Writes a Bytes Array to this path.
//       */
//     def writeBytes(value: Array[Byte], createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then IO(JFiles.write(toJava, value, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.write(toJava, value, StandardOpenOption.WRITE)).unit
//                 else IO.unit
//             }

//     /** Writes lines of String to this path.
//       */
//     def writeLines(value: List[String], createFolders: Boolean = true)(using Frame): Unit < IO =
//         if createFolders then
//             IO(JFiles.write(toJava, value.asJava, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).unit
//         else
//             IO(javaExists(toJava.getParent())).map { parentExists =>
//                 if parentExists then IO(JFiles.write(toJava, value.asJava, StandardOpenOption.WRITE)).unit
//                 else IO.unit
//             }

//     /** Methods to read files into Stream
//       */

//     /** Reads a file returning its contents as a Stream of Strings
//       */
//     def readStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Stream[String, Async] < Resource =
//         readLoop[String, Array[Byte], (FileChannel, ByteBuffer)](
//             (FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
//             ch => IO(ch._1.close()),
//             readOnceBytes,
//             arr => Chunk(new String(arr, charset))
//         )

//     /** Reads a file returning its contents as a Stream of Lines
//       */
//     def readLinesStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Stream[String, Async] < Resource =
//         readLoop[String, String, BufferedReader](
//             IO(JFiles.newBufferedReader(toJava, Charset.defaultCharset())),
//             reader => reader.close(),
//             readOnceLines,
//             line => Chunk(line)
//         )

//     /** Reads a file returning its contents as a Stream of Bytes
//       */
//     def readBytesStream(using Frame): Stream[Byte, Async] < Resource =
//         readLoop[Byte, Array[Byte], (FileChannel, ByteBuffer)](
//             IO(FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
//             ch => ch._1.close(),
//             readOnceBytes,
//             arr => Chunk.from(arr.toSeq)
//         )

//     private def readOnceLines(reader: BufferedReader) =
//         IO {
//             val line = reader.readLine()
//             if line == null then None else Some(line)
//         }

//     private def readOnceBytes(res: (FileChannel, ByteBuffer)) =
//         IO {
//             val (fileChannel, buf) = res
//             val bytesRead          = fileChannel.read(buf)
//             if bytesRead < 1 then None
//             else
//                 buf.flip()
//                 val arr = new Array[Byte](bytesRead)
//                 buf.get(arr)
//                 Some(arr)
//             end if
//         }

//     private def readLoop[A, ReadTpe, Res](
//         acquire: Res < IO,
//         release: Res => Unit < Async,
//         readOnce: Res => Option[ReadTpe] < IO,
//         writeOnce: ReadTpe => Chunk[A]
//     )(using Tag[Stream[A]]) =
//         Resource.acquireRelease(acquire)(release).map { res =>
//             Channel.init[Chunk[A] | Stream.Done](16).map { ch =>
//                 readOnce(res).map { case state =>
//                     Loops.transform(state) {
//                         case None => ch.put(Stream.Done).map(_ => Loops.done(()))
//                         case Some(content) =>
//                             for
//                                 _     <- ch.put(writeOnce(content))
//                                 state <- readOnce(res)
//                             yield Loops.continue(state)
//                     }
//                 }.map(_ => Stream.initChannel(ch))
//             }
//         }

//     /** Other file utilities
//       */

//     /** Truncates the content of this file
//       */
//     def truncate(size: Long)(using Frame): FileChannel < (Resource & IO) =
//         Resource
//             .acquireRelease(FileChannel.open(toJava, StandardOpenOption.WRITE))(ch => ch.close())
//             .map { ch =>
//                 ch.truncate(size)
//                 ch
//             }
//     end truncate

//     /** List contents of path
//       */
//     def list(using Frame): IndexedSeq[Path] < IO =
//         IO(JFiles.list(toJava).toScala(LazyList).toIndexedSeq).map(_.map(path => Path(path.toString)))

//     /** List contents of path with given extension
//       */
//     def list(extension: String)(using Frame): IndexedSeq[Path] < IO =
//         IO(JFiles.list(toJava).toScala(LazyList)).map(_.filter(path =>
//             path.getFileName().toString().split('.').toList.lastOption.getOrElse("") == extension
//         )).map(_.toIndexedSeq).map(_.map(path => Path(path.toString)))

//     /** Returns if the path exists
//       */
//     def exists(using Frame): Boolean < IO =
//         exists(true)

//     /** Returns if the path exists
//       */
//     def exists(followLinks: Boolean)(using Frame): Boolean < IO =
//         val path = toJava
//         if path == null then IO(false)
//         else if followLinks then IO(JFiles.exists(path))
//         else IO(JFiles.exists(path, LinkOption.NOFOLLOW_LINKS))
//     end exists

//     private def javaExists(jPath: JPath): Boolean =
//         if jPath == null then false
//         else JFiles.exists(jPath, LinkOption.NOFOLLOW_LINKS)

//     /** Returns if the path represents a directory
//       */
//     def isDir(using Frame): Boolean < IO =
//         IO(JFiles.isDirectory(toJava))

//     /** Returns if the path represents a file
//       */
//     def isFile(using Frame): Boolean < IO =
//         IO(JFiles.isRegularFile(toJava))

//     /** Returns if the path represents a symbolic link
//       */
//     def isLink(using Frame): Boolean < IO =
//         IO(JFiles.isSymbolicLink(toJava))

//     /** Creates a directory in this path
//       */
//     def mkDir(using Frame): Unit < IO =
//         IO(javaExists(toJava.getParent())).map { parentsExist =>
//             if parentsExist == true then IO(JFiles.createDirectory(toJava))
//             else IO(JFiles.createDirectories(toJava))
//         }.unit

//     /** Creates a directory in this path
//       */
//     def mkFile(using Frame): Unit < IO =
//         IO(javaExists(toJava.getParent())).map { parentsExist =>
//             if parentsExist == true then IO(JFiles.createDirectory(toJava))
//             else IO(JFiles.createDirectories(toJava))
//         }.unit
//     end mkFile

//     /** Moves the content of this path to another path
//       */
//     def move(
//         to: Path,
//         replaceExisting: Boolean = false,
//         atomicMove: Boolean = false,
//         createFolders: Boolean = true
//     )(using Frame): Unit < IO =
//         val opts = (if atomicMove then List(StandardCopyOption.ATOMIC_MOVE) else Nil) ++ (if replaceExisting then
//                                                                                               List(StandardCopyOption.REPLACE_EXISTING)
//                                                                                           else Nil)
//         IO(javaExists(toJava.getParent())).map { parentExists =>
//             (parentExists, createFolders) match
//                 case (true, _)     => IO(JFiles.move(toJava, to.toJava, opts*)).unit
//                 case (false, true) => Path(toJava.getParent().toString).mkDir.andThen(IO(JFiles.move(toJava, to.toJava, opts*)).unit)
//                 case _             => ()
//         }
//     end move

//     /** Copies the content of this path to another path
//       */
//     def copy(
//         to: Path,
//         followLinks: Boolean = true,
//         replaceExisting: Boolean = false,
//         copyAttributes: Boolean = false,
//         createFolders: Boolean = true,
//         mergeFolders: Boolean = false
//     )(using Frame): Unit < IO =
//         val opts = (if followLinks then List.empty[CopyOption] else List[CopyOption](LinkOption.NOFOLLOW_LINKS)) ++
//             (if copyAttributes then List[CopyOption](StandardCopyOption.COPY_ATTRIBUTES) else List.empty[CopyOption]) ++
//             (if replaceExisting then List[CopyOption](StandardCopyOption.REPLACE_EXISTING) else List.empty[CopyOption])
//         IO(javaExists(toJava.getParent())).map { parentExists =>
//             (parentExists, createFolders) match
//                 case (true, _)     => IO(JFiles.copy(toJava, to.toJava, opts*)).unit
//                 case (false, true) => Path(toJava.getParent().toString).mkDir.andThen(IO(JFiles.copy(toJava, to.toJava, opts*)).unit)
//                 case _             => IO.unit
//         }
//     end copy

//     /** Removes this path if it is empty
//       */
//     def remove(using Frame): Boolean < IO =
//         remove(false)

//     /** Removes this path if it is empty
//       */
//     def remove(checkExists: Boolean)(using Frame): Boolean < IO =
//         IO {
//             if checkExists then
//                 JFiles.delete(toJava)
//                 true
//             else
//                 JFiles.deleteIfExists(toJava)
//         }

//     /** Removes this path and all its contents
//       */
//     def removeAll(using Frame): Unit < IO =
//         IO {
//             val path = toJava
//             if javaExists(path) then
//                 val visitor = new SimpleFileVisitor[JPath]:
//                     override def visitFile(path: JPath, basicFileAttributes: BasicFileAttributes): FileVisitResult =
//                         JFiles.delete(path)
//                         FileVisitResult.CONTINUE

//                     override def postVisitDirectory(path: JPath, ioException: IOException): FileVisitResult =
//                         JFiles.delete(path)
//                         FileVisitResult.CONTINUE
//                 discard(JFiles.walkFileTree(path, visitor))
//             end if
//             ()
//         }

//     /** Creates a stream of the contents of this path with maximum depth
//       */
//     def walk(using Frame): Stream[Path, Any] < IO =
//         walk(Int.MaxValue)

//     /** Creates a stream of the contents of this path with given depth
//       */
//     def walk(maxDepth: Int)(using Frame): Stream[Path, Any] < IO =
//         IO(JFiles.walk(toJava).toScala(LazyList(_))).map(seq =>
//             Stream.initSeq(seq).transform(path => Path(path.toString))
//         )

//     override def toString = s"Path(\"${path.mkString("/")}\")"

// end Path

// object Path:

//     type Part = String | Path

//     def apply(path: List[Part]): Path =
//         def loop(path: List[Part], acc: List[String]): List[String] =
//             path match
//                 case _: Nil.type =>
//                     acc.reverse
//                 case h :: t =>
//                     h match
//                         case h: String =>
//                             loop(t, h.split('/').filter(_.nonEmpty).toList.reverse ::: acc)
//                         case h: Path =>
//                             loop(h.path ::: t, acc)
//         new Path(loop(path, Nil))
//     end apply

//     def apply(path: Part*): Path =
//         apply(path.toList)
// end Path

// extension [S](stream: Stream[S, Byte])
//     def sink(path: Path)(using Frame): Unit < (Resource & S) =
//         Resource.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
//             stream.map(bytes =>
//                 IO(fileCh.write(ByteBuffer.wrap(bytes.toArray))).map(_ => Chunk.empty[Byte])
//             ).runDiscard
//         }
// end extension

// extension [S](stream: Stream[S, String])
//     @scala.annotation.targetName("stringSink")
//     def sink(path: Path, charset: Codec = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Unit < (Resource & IO & S) =
//         Resource.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
//             stream.map(s =>
//                 IO(fileCh.write(ByteBuffer.wrap(s.getBytes))).unit
//             ).runDiscard
//         }

//     def sinkLines(
//         path: Path,
//         charset: Codec = java.nio.charset.StandardCharsets.UTF_8
//     )(using Frame): Unit < (Resource & IO & S) =
//         Resource.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
//             stream.map(line =>
//                 IO {
//                     fileCh.write(ByteBuffer.wrap(line.getBytes))
//                     fileCh.write(ByteBuffer.wrap(System.lineSeparator().getBytes))
//                 }.unit
//             ).runDiscard
//         }
// end extension
