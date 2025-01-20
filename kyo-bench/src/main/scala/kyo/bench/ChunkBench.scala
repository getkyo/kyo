package kyo.bench

import kyo.Chunk
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized
import scala.reflect.ClassTag
import scala.util.Random
import zio.Chunk as ZChunk

class ChunkBench extends Bench(()):
    @Param(Array("1024", "1048576"))
    var size: Int = uninitialized

    var intKyoChunk: Chunk[Int]  = uninitialized
    var intZioChunk: ZChunk[Int] = uninitialized
    var intVector: Vector[Int]   = uninitialized
    var intList: List[Int]       = uninitialized

    var strKyoChunk: Chunk[String]  = uninitialized
    var strZioChunk: ZChunk[String] = uninitialized
    var strVector: Vector[String]   = uninitialized
    var strList: List[String]       = uninitialized

    def intFillFn: Int               = Random.nextInt()
    val intFn: Int => Int            = _ + 1
    val intFilterFn: Int => Boolean  = _ % 2 == 0
    val intFoldFn: (Int, Int) => Int = _ + _
    inline def intX: Int             = 0

    def strFillFn: String                     = Random.nextInt().toString
    val strFn: String => String               = _ + "a"
    val strFilterFn: String => Boolean        = _.length % 2 == 0
    val strFoldFn: (String, String) => String = _ + _
    inline def strX: String                   = ""

    @Setup(Level.Trial)
    def setup(): Unit =
        val intArray = Array.fill(size)(intFillFn)
        intKyoChunk = Chunk.from(intArray)
        intZioChunk = ZChunk.from(intArray)
        intVector = intArray.toVector
        intList = intArray.toList

        val strArray = Array.fill(size)(strFillFn)
        strKyoChunk = Chunk.from(strArray)
        strZioChunk = ZChunk.from(strArray)
        strVector = strArray.toVector
        strList = strArray.toList
    end setup

    @Benchmark def mapIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.map(intFn))
    @Benchmark def mapStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.map(strFn))

    @Benchmark def mapIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.map(intFn))
    @Benchmark def mapStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.map(strFn))

    @Benchmark def mapIntVector(bh: Blackhole) = bh.consume(intVector.map(intFn))
    @Benchmark def mapStrVector(bh: Blackhole) = bh.consume(strVector.map(strFn))

    @Benchmark def mapIntList(bh: Blackhole) = bh.consume(intList.map(intFn))
    @Benchmark def mapStrList(bh: Blackhole) = bh.consume(strList.map(strFn))

    @Benchmark def flatMapIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.flatMap(a => Chunk(a, intFn(a))))
    @Benchmark def flatMapStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.flatMap(a => Chunk(a, strFn(a))))

    @Benchmark def flatMapIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.flatMap(a => ZChunk(a, intFn(a))))
    @Benchmark def flatMapStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.flatMap(a => ZChunk(a, strFn(a))))

    @Benchmark def flatMapIntVector(bh: Blackhole) = bh.consume(intVector.flatMap(a => Vector(a, intFn(a))))
    @Benchmark def flatMapStrVector(bh: Blackhole) = bh.consume(strVector.flatMap(a => Vector(a, strFn(a))))

    @Benchmark def flatMapIntList(bh: Blackhole) = bh.consume(intList.flatMap(a => List(a, intFn(a))))
    @Benchmark def flatMapStrList(bh: Blackhole) = bh.consume(strList.flatMap(a => List(a, strFn(a))))

    @Benchmark def filterIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.filter(intFilterFn))
    @Benchmark def filterStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.filter(strFilterFn))

    @Benchmark def filterIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.filter(intFilterFn))
    @Benchmark def filterStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.filter(strFilterFn))

    @Benchmark def filterIntVector(bh: Blackhole) = bh.consume(intVector.filter(intFilterFn))
    @Benchmark def filterStrVector(bh: Blackhole) = bh.consume(strVector.filter(strFilterFn))

    @Benchmark def filterIntList(bh: Blackhole) = bh.consume(intList.filter(intFilterFn))
    @Benchmark def filterStrList(bh: Blackhole) = bh.consume(strList.filter(strFilterFn))

    @Benchmark def foldIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.foldLeft(intX)(intFoldFn))
    @Benchmark def foldStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.foldLeft(strX)(strFoldFn))

    @Benchmark def foldIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.foldLeft(intX)(intFoldFn))
    @Benchmark def foldStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.foldLeft(strX)(strFoldFn))

    @Benchmark def foldIntVector(bh: Blackhole) = bh.consume(intVector.foldLeft(intX)(intFoldFn))
    @Benchmark def foldStrVector(bh: Blackhole) = bh.consume(strVector.foldLeft(strX)(strFoldFn))

    @Benchmark def foldIntList(bh: Blackhole) = bh.consume(intList.foldLeft(intX)(intFoldFn))
    @Benchmark def foldStrList(bh: Blackhole) = bh.consume(strList.foldLeft(strX)(strFoldFn))

    @Benchmark def appendIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.appended(intX))
    @Benchmark def appendStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.appended(strX))

    @Benchmark def appendIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.appended(intX))
    @Benchmark def appendStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.appended(strX))

    @Benchmark def appendIntVector(bh: Blackhole) = bh.consume(intVector.appended(intX))
    @Benchmark def appendStrVector(bh: Blackhole) = bh.consume(strVector.appended(strX))

    @Benchmark def appendIntList(bh: Blackhole) = bh.consume(intList.appended(intX))
    @Benchmark def appendStrList(bh: Blackhole) = bh.consume(strList.appended(strX))

    @Benchmark def prependIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.prepended(intX))
    @Benchmark def prependStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.prepended(strX))

    @Benchmark def prependIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.prepended(intX))
    @Benchmark def prependStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.prepended(strX))

    @Benchmark def prependIntVector(bh: Blackhole) = bh.consume(intVector.prepended(intX))
    @Benchmark def prependStrVector(bh: Blackhole) = bh.consume(strVector.prepended(strX))

    @Benchmark def prependIntList(bh: Blackhole) = bh.consume(intList.prepended(intX))
    @Benchmark def prependStrList(bh: Blackhole) = bh.consume(strList.prepended(strX))

    @Benchmark def takeIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.take(size / 2))
    @Benchmark def takeStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.take(size / 2))

    @Benchmark def takeIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.take(size / 2))
    @Benchmark def takeStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.take(size / 2))

    @Benchmark def takeIntVector(bh: Blackhole) = bh.consume(intVector.take(size / 2))
    @Benchmark def takeStrVector(bh: Blackhole) = bh.consume(strVector.take(size / 2))

    @Benchmark def takeIntList(bh: Blackhole) = bh.consume(intList.take(size / 2))
    @Benchmark def takeStrList(bh: Blackhole) = bh.consume(strList.take(size / 2))

    @Benchmark def dropIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.drop(size / 2))
    @Benchmark def dropStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.drop(size / 2))

    @Benchmark def dropIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.drop(size / 2))
    @Benchmark def dropStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.drop(size / 2))

    @Benchmark def dropIntVector(bh: Blackhole) = bh.consume(intVector.drop(size / 2))
    @Benchmark def dropStrVector(bh: Blackhole) = bh.consume(strVector.drop(size / 2))

    @Benchmark def dropIntList(bh: Blackhole) = bh.consume(intList.drop(size / 2))
    @Benchmark def dropStrList(bh: Blackhole) = bh.consume(strList.drop(size / 2))

    @Benchmark def concatIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk ++ intKyoChunk)
    @Benchmark def concatStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk ++ strKyoChunk)

    @Benchmark def concatIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk ++ intZioChunk)
    @Benchmark def concatStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk ++ strZioChunk)

    @Benchmark def concatIntVector(bh: Blackhole) = bh.consume(intVector ++ intVector)
    @Benchmark def concatStrVector(bh: Blackhole) = bh.consume(strVector ++ strVector)

    @Benchmark def concatIntList(bh: Blackhole) = bh.consume(intList ++ intList)
    @Benchmark def concatStrList(bh: Blackhole) = bh.consume(strList ++ strList)

    @Benchmark def applyIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk(size / 2))
    @Benchmark def applyStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk(size / 2))

    @Benchmark def applyIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk(size / 2))
    @Benchmark def applyStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk(size / 2))

    @Benchmark def applyIntVector(bh: Blackhole) = bh.consume(intVector(size / 2))
    @Benchmark def applyStrVector(bh: Blackhole) = bh.consume(strVector(size / 2))

    @Benchmark def applyIntList(bh: Blackhole) = bh.consume(intList(size / 2))
    @Benchmark def applyStrList(bh: Blackhole) = bh.consume(strList(size / 2))

    @Benchmark def sliceIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.slice(size / 4, size / 2))
    @Benchmark def sliceStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.slice(size / 4, size / 2))

    @Benchmark def sliceIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.slice(size / 4, size / 2))
    @Benchmark def sliceStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.slice(size / 4, size / 2))

    @Benchmark def sliceIntVector(bh: Blackhole) = bh.consume(intVector.slice(size / 4, size / 2))
    @Benchmark def sliceStrVector(bh: Blackhole) = bh.consume(strVector.slice(size / 4, size / 2))

    @Benchmark def sliceIntList(bh: Blackhole) = bh.consume(intList.slice(size / 4, size / 2))
    @Benchmark def sliceStrList(bh: Blackhole) = bh.consume(strList.slice(size / 4, size / 2))

    @Benchmark def reverseIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.reverse)
    @Benchmark def reverseStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.reverse)

    @Benchmark def reverseIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.reverse)
    @Benchmark def reverseStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.reverse)

    @Benchmark def reverseIntVector(bh: Blackhole) = bh.consume(intVector.reverse)
    @Benchmark def reverseStrVector(bh: Blackhole) = bh.consume(strVector.reverse)

    @Benchmark def reverseIntList(bh: Blackhole) = bh.consume(intList.reverse)
    @Benchmark def reverseStrList(bh: Blackhole) = bh.consume(strList.reverse)

    @Benchmark def sizeIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.size)
    @Benchmark def sizeStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.size)

    @Benchmark def sizeIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.size)
    @Benchmark def sizeStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.size)

    @Benchmark def sizeIntVector(bh: Blackhole) = bh.consume(intVector.size)
    @Benchmark def sizeStrVector(bh: Blackhole) = bh.consume(strVector.size)

    @Benchmark def sizeIntList(bh: Blackhole) = bh.consume(intList.size)
    @Benchmark def sizeStrList(bh: Blackhole) = bh.consume(strList.size)

    @Benchmark def isEmptyIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.isEmpty)
    @Benchmark def isEmptyStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.isEmpty)

    @Benchmark def isEmptyIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.isEmpty)
    @Benchmark def isEmptyStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.isEmpty)

    @Benchmark def isEmptyIntVector(bh: Blackhole) = bh.consume(intVector.isEmpty)
    @Benchmark def isEmptyStrVector(bh: Blackhole) = bh.consume(strVector.isEmpty)

    @Benchmark def isEmptyIntList(bh: Blackhole) = bh.consume(intList.isEmpty)
    @Benchmark def isEmptyStrList(bh: Blackhole) = bh.consume(strList.isEmpty)

    @Benchmark def containsIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.contains(intKyoChunk(size / 2)))
    @Benchmark def containsStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.contains(strKyoChunk(size / 2)))

    @Benchmark def containsIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.contains(intZioChunk(size / 2)))
    @Benchmark def containsStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.contains(strZioChunk(size / 2)))

    @Benchmark def containsIntVector(bh: Blackhole) = bh.consume(intVector.contains(intVector(size / 2)))
    @Benchmark def containsStrVector(bh: Blackhole) = bh.consume(strVector.contains(strVector(size / 2)))

    @Benchmark def containsIntList(bh: Blackhole) = bh.consume(intList.contains(intList(size / 2)))
    @Benchmark def containsStrList(bh: Blackhole) = bh.consume(strList.contains(strList(size / 2)))

    @Benchmark def distinctIntKyoChunk(bh: Blackhole) = bh.consume(intKyoChunk.distinct)
    @Benchmark def distinctStrKyoChunk(bh: Blackhole) = bh.consume(strKyoChunk.distinct)

    @Benchmark def distinctIntZioChunk(bh: Blackhole) = bh.consume(intZioChunk.distinct)
    @Benchmark def distinctStrZioChunk(bh: Blackhole) = bh.consume(strZioChunk.distinct)

    @Benchmark def distinctIntVector(bh: Blackhole) = bh.consume(intVector.distinct)
    @Benchmark def distinctStrVector(bh: Blackhole) = bh.consume(strVector.distinct)

    @Benchmark def distinctIntList(bh: Blackhole) = bh.consume(intList.distinct)
    @Benchmark def distinctStrList(bh: Blackhole) = bh.consume(strList.distinct)
end ChunkBench
