package kyoTest

import java.io.*
import java.nio.file.*
import kyo.*
import kyo.files.*
import kyo.files.given

class filesTest extends KyoTest:

    def createFile(name: String, text: String) =
        Files.write(Paths.get(name), text.getBytes())

    def destroyFile(name: String) =
        Files.delete(Paths.get(name))

    def useFile(name: String, text: String) =
        Resources.acquireRelease(IOs(createFile(name, text)))(_ => IOs(destroyFile(name)))

    "read and write files" - {
        "read file as string" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            val eff = useFile(name, text).map { _ =>
                name.read
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == text)
        }
        "read file as bytes" in run {
            val name = "read-file-string"
            val text = "some text"
            val eff = useFile(name, text).map { _ =>
                name.readBytes
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v.toList == Array(115, 111, 109, 101, 32, 116, 101, 120, 116).toList)
        }
        "read file as lines" in run {
            val name = "read-file-string.txt"
            val text = "some text\nmore text"
            val eff = useFile(name, text).map { _ =>
                name.readLines
            }

            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == List("some text", "more text"))
        }

        // These tests should be passing
        /*"read file as string stream" in run {
            val name = "read-file-string.txt"
            val text = "some text"


            for
                _   <- IOs(createFile(name, text))
                res <- Resources.run(name.readStream())
                _   <- IOs(destroyFile(name))
                v   <- Fibers.init(res.runSeq).map(_.get)
            yield assert(v._1 == List("some text"))
            end for
        }
        "read file as bytes stream" in run {
            val name = "read-file-string"
            val text = "some text"
            val eff = useFile(name, text).map { _ =>
                name.readBytesStream()
            }.map(_.runSeq)
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v._1 == List(Array(115, 111, 109, 101, 32, 116, 101, 120, 116)))
        }
        "read file as lines stream" in run {
            val name = "read-file-string.txt"
            val text = "some text\nmore text"
            val eff = useFile(name, text).map { _ =>
                name.readLinesStream()
            }.map(_.runSeq)
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v._1 == List("some text", "more text"))
        }*/

        "write file from string" in run {
            val name = "read-file-string.txt"
            val text = "some text"
            val eff = useFile(name, "").map { _ =>
                name.write(text).map(_ => name.read)
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == "some text")
        }

        "write file from bytes" in run {
            val name = "read-file-string.txt"
            val text = Array[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116)
            val eff = useFile(name, "").map { _ =>
                name.writeBytes(text).map(_ => name.read)
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == "some text")
        }

        "write file from lines" in run {
            val name = "read-file-string.txt"
            val text = "some text" :: "more text" :: Nil
            val eff = useFile(name, "").map { _ =>
                name.writeLines(text).map(_ => name.readLines)
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == List("some text", "more text"))
        }

        "write file from string stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init("some text"))
            val eff = useFile(name, "").map { _ =>
                stream.sink(name.toJPath).map(_ => name.read)
            }
            for
                v <- Fibers.init(eff).map(_.get)
            yield assert(v == "some text")
        }

        "write file from bytes stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init[Byte](115, 111, 109, 101, 32, 116, 101, 120, 116))
            for
                _   <- IOs(createFile(name, ""))
                _   <- Fibers.init(stream.sink(name.toJPath)).map(_.get)
                res <- name.read
                _   <- IOs(destroyFile(name))
            yield assert(res == "some text")
            end for

        }

        "write file from lines stream" in run {
            val name   = "read-file-string.txt"
            val stream = Streams.initChunk(Chunks.init("some text", "more text"))
            for
                _   <- IOs(createFile(name, ""))
                _   <- Fibers.init(stream.sinkLines(name.toJPath)).map(_.get)
                res <- name.readLines
                _   <- IOs(destroyFile(name))
            yield assert(res == List("some text", "more text"))
            end for
        }
    }

    "manipulate files and dirs" - {
        "create and destroy" in run {
            val name = "some-file.txt"
            for
                existed   <- name.exists
                _         <- name.mkDir
                created   <- name.exists
                _         <- name.remove
                destroyed <- name.exists
            yield assert((existed, created, destroyed) == (false, true, false))
            end for

        }

        "destroy dir recursively" in run {
            val dir  = "folder"
            val file = dir / "some-file.txt"
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
            val folder1 = "folder1"
            val folder2 = "folder2"
            val path1   = folder1 / "some-file.txt"
            val path2   = folder2 / "some-file.txt"
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
            val folder3 = "folder3"
            val folder4 = "folder4"
            val path1   = folder3 / "some-file.txt"
            val path2   = folder4 / "some-file.txt"
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

        /*"walk" in run {
            val folder = "folder"
            val path1 = folder / "path1"
            val path2 = folder / "path2"
            val createFolders = for {
                _ <- folder.mkDir
                _ <- path1.mkFile
                _ <- path2.mkFile
                
            } yield ()
            assert (IOs.run(createFolders.andThen(folder.walk.map(_.runSeq))).pure._1.map(_.toString).toSet == Set("folder/path1", "folder/path2"))
        }*/
    }

end filesTest
