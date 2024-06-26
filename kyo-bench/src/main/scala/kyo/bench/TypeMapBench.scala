package kyo.bench

import kyo.TypeMap
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized
import zio.ZEnvironment

// Based on: https://github.com/zio/zio/blob/series/2.x/benchmarks/src/main/scala/zio/ZEnvironmentBenchmark.scala
class TypeMapBench extends Bench(()):
    import BenchmarkedEnvironment.*

    var zioEnv: ZEnvironment[Env]           = uninitialized
    var zioEnvSmall: ZEnvironment[SmallEnv] = uninitialized
    var kyoEnv: TypeMap[Env]                = uninitialized
    var kyoEnvSmall: TypeMap[SmallEnv]      = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        zioEnv = BenchmarkedEnvironment.makeLargeZIO()
        zioEnvSmall = BenchmarkedEnvironment.makeSmallZIO()
        kyoEnv = BenchmarkedEnvironment.makeLargeKyo()
        kyoEnvSmall = BenchmarkedEnvironment.makeSmallKyo()
    end setup

    @Benchmark
    def getZIO(bh: Blackhole) =
        bh.consume(zioEnv.get[Foo040])
        bh.consume(zioEnv.get[Foo041])
        bh.consume(zioEnv.get[Foo042])
        bh.consume(zioEnv.get[Foo043])
        bh.consume(zioEnv.get[Foo044])
        bh.consume(zioEnv.get[Foo045])
        bh.consume(zioEnv.get[Foo046])
        bh.consume(zioEnv.get[Foo047])
        bh.consume(zioEnv.get[Foo048])
        bh.consume(zioEnv.get[Foo049])
    end getZIO

    @Benchmark
    def getKyo(bh: Blackhole) =
        bh.consume(kyoEnv.get[Foo040])
        bh.consume(kyoEnv.get[Foo041])
        bh.consume(kyoEnv.get[Foo042])
        bh.consume(kyoEnv.get[Foo043])
        bh.consume(kyoEnv.get[Foo044])
        bh.consume(kyoEnv.get[Foo045])
        bh.consume(kyoEnv.get[Foo046])
        bh.consume(kyoEnv.get[Foo047])
        bh.consume(kyoEnv.get[Foo048])
        bh.consume(kyoEnv.get[Foo049])
    end getKyo

    @Benchmark
    def addZIO() =
        zioEnv.add(new Bar000).add(new Bar001).add(new Bar002).add(new Bar003).add(new Bar004)

    @Benchmark
    def addKyo() =
        kyoEnv.add(new Bar000).add(new Bar001).add(new Bar002).add(new Bar003).add(new Bar004)

    @Benchmark
    def addGetOneZIO() =
        zioEnv.add(new Bar000).get[Bar000]

    @Benchmark
    def addGetOneKyo() =
        kyoEnv.add(new Bar000).get[Bar000]

    @Benchmark
    @OperationsPerInvocation(10000)
    def addGetRepeatBaselineZIO(bh: Blackhole) =
        var i = 0
        var e = zioEnv
        while i < 10000 do
            e = zioEnv
                .add(new Foo040)
                .add(new Foo041)
                .add(new Foo042)
                .add(new Foo043)
                .add(new Foo044)
                .add(new Foo045)
                .add(new Foo046)
                .add(new Foo047)
                .add(new Foo048)
                .add(new Foo049)
            bh.consume(e.get[Foo040])
            i += 1
        end while
    end addGetRepeatBaselineZIO

    @Benchmark
    @OperationsPerInvocation(10000)
    def addGetRepeatBaselineKyo(bh: Blackhole) =
        var i = 0
        var e = kyoEnv
        while i < 10000 do
            e = kyoEnv
                .add(new Foo040)
                .add(new Foo041)
                .add(new Foo042)
                .add(new Foo043)
                .add(new Foo044)
                .add(new Foo045)
                .add(new Foo046)
                .add(new Foo047)
                .add(new Foo048)
                .add(new Foo049)
            bh.consume(e.get[Foo040])
            i += 1
        end while
    end addGetRepeatBaselineKyo

    @Benchmark
    def addGetMultiZIO(bh: Blackhole) =
        val e = zioEnv.add(new Bar001)
        bh.consume(e.get[Bar001])
        bh.consume(e.get[Foo040])
        bh.consume(e.get[Foo041])
        bh.consume(e.get[Foo042])
        bh.consume(e.get[Foo043])
        bh.consume(e.get[Foo044])
        bh.consume(e.get[Foo045])
        bh.consume(e.get[Foo046])
        bh.consume(e.get[Foo047])
        bh.consume(e.get[Foo048])
        bh.consume(e.get[Foo049])
    end addGetMultiZIO

    @Benchmark
    def addGetMultiKyo(bh: Blackhole) =
        val e = kyoEnv.add(new Bar001)
        bh.consume(e.get[Bar001])
        bh.consume(e.get[Foo040])
        bh.consume(e.get[Foo041])
        bh.consume(e.get[Foo042])
        bh.consume(e.get[Foo043])
        bh.consume(e.get[Foo044])
        bh.consume(e.get[Foo045])
        bh.consume(e.get[Foo046])
        bh.consume(e.get[Foo047])
        bh.consume(e.get[Foo048])
        bh.consume(e.get[Foo049])
    end addGetMultiKyo

    @Benchmark
    def unionZIO() =
        zioEnv.unionAll(zioEnvSmall)

    @Benchmark
    def unionKyo() =
        kyoEnv.union(kyoEnvSmall)

    // TODO: prune to an intersection once Kyo Tags support Intersections as String
    @Benchmark
    def pruneZIO() =
        zioEnv.prune[Foo001] // & Foo002 & Foo003]

    @Benchmark
    def pruneKyo() =
        kyoEnv.prune[Foo001] // & Foo002 & Foo003]

end TypeMapBench

object BenchmarkedEnvironment:

    final class Bar000
    final class Bar001
    final class Bar002
    final class Bar003
    final class Bar004

    final class Foo000
    final class Foo001
    final class Foo002
    final class Foo003
    final class Foo004
    final class Foo005
    final class Foo006
    final class Foo007
    final class Foo008
    final class Foo009

    final class Foo010
    final class Foo011
    final class Foo012
    final class Foo013
    final class Foo014
    final class Foo015
    final class Foo016
    final class Foo017
    final class Foo018
    final class Foo019

    final class Foo020
    final class Foo021
    final class Foo022
    final class Foo023
    final class Foo024
    final class Foo025
    final class Foo026
    final class Foo027
    final class Foo028
    final class Foo029

    final class Foo030
    final class Foo031
    final class Foo032
    final class Foo033
    final class Foo034
    final class Foo035
    final class Foo036
    final class Foo037
    final class Foo038
    final class Foo039

    final class Foo040
    final class Foo041
    final class Foo042
    final class Foo043
    final class Foo044
    final class Foo045
    final class Foo046
    final class Foo047
    final class Foo048
    final class Foo049

    def makeSmallKyo(): TypeMap[SmallEnv] =
        TypeMap.empty
            .add(new Bar000)
            .add(new Bar001)
            .add(new Bar002)
            .add(new Bar003)
            .add(new Bar004)

    def makeLargeKyo(): TypeMap[Env] =
        TypeMap.empty
            .add(new Foo000)
            .add(new Foo001)
            .add(new Foo002)
            .add(new Foo003)
            .add(new Foo004)
            .add(new Foo005)
            .add(new Foo006)
            .add(new Foo007)
            .add(new Foo008)
            .add(new Foo009)
            .add(new Foo010)
            .add(new Foo011)
            .add(new Foo012)
            .add(new Foo013)
            .add(new Foo014)
            .add(new Foo015)
            .add(new Foo016)
            .add(new Foo017)
            .add(new Foo018)
            .add(new Foo019)
            .add(new Foo020)
            .add(new Foo021)
            .add(new Foo022)
            .add(new Foo023)
            .add(new Foo024)
            .add(new Foo025)
            .add(new Foo026)
            .add(new Foo027)
            .add(new Foo028)
            .add(new Foo029)
            .add(new Foo030)
            .add(new Foo031)
            .add(new Foo032)
            .add(new Foo033)
            .add(new Foo034)
            .add(new Foo035)
            .add(new Foo036)
            .add(new Foo037)
            .add(new Foo038)
            .add(new Foo039)
            .add(new Foo040)
            .add(new Foo041)
            .add(new Foo042)
            .add(new Foo043)
            .add(new Foo044)
            .add(new Foo045)
            .add(new Foo046)
            .add(new Foo047)
            .add(new Foo048)
            .add(new Foo049)

    def makeSmallZIO(): ZEnvironment[SmallEnv] =
        ZEnvironment.empty
            .add(new Bar000)
            .add(new Bar001)
            .add(new Bar002)
            .add(new Bar003)
            .add(new Bar004)

    def makeLargeZIO(): ZEnvironment[Env] =
        ZEnvironment.empty
            .add(new Foo000)
            .add(new Foo001)
            .add(new Foo002)
            .add(new Foo003)
            .add(new Foo004)
            .add(new Foo005)
            .add(new Foo006)
            .add(new Foo007)
            .add(new Foo008)
            .add(new Foo009)
            .add(new Foo010)
            .add(new Foo011)
            .add(new Foo012)
            .add(new Foo013)
            .add(new Foo014)
            .add(new Foo015)
            .add(new Foo016)
            .add(new Foo017)
            .add(new Foo018)
            .add(new Foo019)
            .add(new Foo020)
            .add(new Foo021)
            .add(new Foo022)
            .add(new Foo023)
            .add(new Foo024)
            .add(new Foo025)
            .add(new Foo026)
            .add(new Foo027)
            .add(new Foo028)
            .add(new Foo029)
            .add(new Foo030)
            .add(new Foo031)
            .add(new Foo032)
            .add(new Foo033)
            .add(new Foo034)
            .add(new Foo035)
            .add(new Foo036)
            .add(new Foo037)
            .add(new Foo038)
            .add(new Foo039)
            .add(new Foo040)
            .add(new Foo041)
            .add(new Foo042)
            .add(new Foo043)
            .add(new Foo044)
            .add(new Foo045)
            .add(new Foo046)
            .add(new Foo047)
            .add(new Foo048)
            .add(new Foo049)

    type SmallEnv = Bar000 & Bar001 & Bar002 & Bar003 & Bar004

    type Env =
        Foo000
            & Foo001
            & Foo002
            & Foo003
            & Foo004
            & Foo005
            & Foo006
            & Foo007
            & Foo008
            & Foo009
            & Foo010
            & Foo011
            & Foo012
            & Foo013
            & Foo014
            & Foo015
            & Foo016
            & Foo017
            & Foo018
            & Foo019
            & Foo020
            & Foo021
            & Foo022
            & Foo023
            & Foo024
            & Foo025
            & Foo026
            & Foo027
            & Foo028
            & Foo029
            & Foo030
            & Foo031
            & Foo032
            & Foo033
            & Foo034
            & Foo035
            & Foo036
            & Foo037
            & Foo038
            & Foo039
            & Foo040
            & Foo041
            & Foo042
            & Foo043
            & Foo044
            & Foo045
            & Foo046
            & Foo047
            & Foo048
            & Foo049
end BenchmarkedEnvironment
