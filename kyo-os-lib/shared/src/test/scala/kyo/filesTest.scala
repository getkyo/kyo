package kyo

import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import java.nio.file.Paths
import kyo.*

class filesTest extends KyoTest:

    def createFile(name: String, text: String) =
        JFiles.write(Paths.get(name), text.getBytes())

    def destroyFile(name: String) =
        JFiles.delete(Paths.get(name))

    def useFile(name: String, text: String) =
        Resources.acquireRelease(IOs(createFile(name, text)))(_ => IOs(destroyFile(name)))

    "read and write files" - {
        "read file as string" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).read
                }
            yield assert(v == text)
        }
        "read file as bytes" in run {
            val name = "read-file-string"
            val text = "some text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).readBytes
                }
            yield assert(v.toList == Array(115, 111, 109, 101, 32, 116, 101, 120, 116).toList)
        }
        "read file as lines" in run {
            val name = "read-file-string.txt"
            val text = "some text\nmore text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).readLines
                }
            yield assert(v == List("some text", "more text"))
        }

        "read file as string stream" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).readStream()
                }.map(_.runSeq)
            yield assert(v._1 == IndexedSeq("some text"))
            end for
        }
        "read file as bytes stream" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).readBytesStream
                }.map(_.runSeq)
            yield assert(v._1 == IndexedSeq[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116))
        }
        "read file as lines stream" in run {
            val name = "read-file-string.txt"
            val text = "some text\nmore text"
            for
                v <- useFile(name, text).map { _ =>
                    Files(name).readLinesStream()
                }.map(_.runSeq)
            yield assert(v._1 == IndexedSeq("some text", "more text"))
        }

        "append to file from string" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            val file = Files(name)
            for
                v <- useFile(name, "text before ").map { _ =>
                    file.append(text).map(_ => file.read)
                }
            yield assert(v == "text before some text")
        }

        "append to file from bytes" in run {
            val name = "read-file-string.txt"
            val text = Array[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116)
            val file = Files(name)
            for
                v <- useFile(name, "text before ").map { _ =>
                    file.appendBytes(text).map(_ => file.read)
                }
            yield assert(v == "text before some text")
        }

        "append to file from lines" in run {
            val name = "read-file-string.txt"
            val text = "some text" :: "more text" :: Nil
            val file = Files(name)
            for
                v <- useFile(name, "text before ").map { _ =>
                    file.appendLines(text).map(_ => file.readLines)
                }
            yield assert(v == List("text before some text", "more text"))
        }

        "write file from string" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            val file = Files(name)
            for
                v <- useFile(name, "").map { _ =>
                    file.write(text).map(_ => file.read)
                }
            yield assert(v == "some text")
        }

        "write file from bytes" in run {
            val name = "read-file-string.txt"
            val text = Array[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116)
            val file = Files(name)
            for
                v <- useFile(name, "").map { _ =>
                    file.writeBytes(text).map(_ => file.read)
                }
            yield assert(v == "some text")
        }

        "write file from lines" in run {
            val name = "read-file-string.txt"
            val text = "some text" :: "more text" :: Nil
            val file = Files(name)
            for
                v <- useFile(name, "").map { _ =>
                    file.writeLines(text).map(_ => file.readLines)
                }
            yield assert(v == List("some text", "more text"))
        }

        "write file from string stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init("some text"))
            val file   = Files(name)
            for
                v <- useFile(name, "").map { _ =>
                    stream.sink(file).map(_ => file.read)
                }
            yield assert(v == "some text")
        }

        "write file from bytes stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116))
            val file   = Files(name)
            for
                _   <- IOs(createFile(name, ""))
                _   <- stream.sink(file)
                res <- file.read
                _   <- IOs(destroyFile(name))
            yield assert(res == "some text")
            end for

        }

        "write file from lines stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init("some text", "more text"))
            val file   = Files(name)
            for
                _   <- IOs(createFile(name, ""))
                _   <- stream.sinkLines(file)
                res <- file.readLines
                _   <- IOs(destroyFile(name))
            yield assert(res == List("some text", "more text"))
            end for
        }
    }

    "manipulate files and dirs" - {
        "create and destroy" in run {
            val file = Files("some-file.txt")
            for
                existed   <- file.exists
                _         <- file.mkDir
                created   <- file.exists
                _         <- file.remove
                destroyed <- file.exists
            yield assert((existed, created, destroyed) == (false, true, false))
            end for

        }

        "destroy dir recursively" in run {
            val dir  = Files("folder")
            val file = Files("folder/some.file.txt")
            for
                _             <- dir.mkDir
                _             <- file.mkFile
                created       <- file.exists
                _             <- dir.removeAll
                destroyedFile <- file.exists
                destroyedDir  <- dir.exists
            yield assert((created, destroyedFile, destroyedDir) == (true, false, false))
            end for
        }

        "move path" in run {
            val folder1 = Files("folder1")
            val folder2 = Files("folder2")
            val path1   = Files("folder1/some-file.txt")
            val path2   = Files("folder2/some-file.txt")
            for
                _         <- folder1.mkDir
                _         <- folder2.mkDir
                _         <- path1.mkFile
                _         <- path1.move(path2, false, true, true)
                fstExists <- path1.exists
                sndExists <- path2.exists
                _         <- folder1.removeAll
                _         <- folder2.removeAll
            yield assert((fstExists, sndExists) == (false, true))
            end for
        }

        "copy path" in run {
            val folder3 = Files("folder3")
            val folder4 = Files("folder4")
            val path1   = Files("folder3/some-file.txt")
            val path2   = Files("folder4/some-file.txt")
            for
                _         <- folder3.mkDir
                _         <- folder4.mkDir
                _         <- path1.mkFile
                _         <- path1.copy(path2)
                fstExists <- path1.exists
                sndExists <- path2.exists
                _         <- folder3.removeAll
                _         <- folder4.removeAll
            yield assert((fstExists, sndExists) == (true, true))
            end for
        }

        "walk" in run {
            val sep    = java.io.File.separator
            val folder = Files("folder")
            val path1  = Files("folder/path1")
            val path2  = Files("folder/path2")
            for
                _ <- folder.mkDir
                _ <- path1.mkFile
                _ <- path2.mkFile
                v <- folder.walk.map(_.runSeq)
                _ <- folder.removeAll
            yield assert(v._1.toSet.map(_.toString) == Set(
                """Files("folder")""",
                s"""Files("folder${sep}path1")""",
                s"""Files("folder${sep}path2")"""
            ))
            end for
        }

        "list" in run {
            val sep    = java.io.File.separator
            val folder = Files("folder")
            val path1  = Files("folder/path1")
            val path2  = Files("folder/path2")
            for
                _ <- folder.mkDir
                _ <- path1.mkFile
                _ <- path2.mkFile
                v <- folder.list
                _ <- folder.removeAll
            yield assert(v.toSet.map(_.toString) == Set(s"""Files("folder${sep}path1")""", s"""Files("folder${sep}path2")"""))
            end for
        }

        "list with extension" in run {
            val sep    = java.io.File.separator
            val folder = Files("folder")
            val path1  = Files("folder/path1.txt")
            val path2  = Files("folder/path2")
            for
                _ <- folder.mkDir
                _ <- path1.mkFile
                _ <- path2.mkFile
                v <- folder.list("txt")
                _ <- folder.removeAll
            yield assert(v.toSet.map(_.toString) == Set(s"""Files("folder${sep}path1.txt")"""))
            end for
        }
    }

end filesTest
