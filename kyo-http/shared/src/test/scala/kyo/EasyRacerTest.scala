// Old kyo package test — commented out, replaced by kyo.http2 tests
// package kyo
//
// import java.time.Duration as JDuration
// import scala.collection.immutable.SortedMap
//
// class EasyRacerTest extends Test:
//
//     private def req(url: String)(using Frame): String < (Async & Abort[HttpError]) =
//         HttpClient.get[String](url)
//
//     "scenario 1 - race 2 concurrent requests" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/1") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 1 then
//                             promise.get.andThen(HttpResponse.ok("right"))
//                         else
//                             promise.completeUnitDiscard.andThen {
//                                 Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/1"
//                     Async.race(req(url), req(url)).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 2 - race 2 concurrent requests, one connection error" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/2") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 1 then
//                             promise.get.andThen {
//                                 Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                             }
//                         else
//                             promise.completeUnitDiscard.andThen {
//                                 HttpResponse.serverError("connection error")
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/2"
//                     Async.race(req(url), req(url)).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 3 - race 100 concurrent requests" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/3") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num < 100 then
//                             promise.get.andThen {
//                                 Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                             }
//                         else
//                             promise.completeUnitDiscard.andThen {
//                                 HttpResponse.ok("right")
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url  = s"http://localhost:$port/3"
//                     val reqs = Seq.fill(100)(req(url))
//                     Async.race(reqs).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 4 - race 2 concurrent requests, one with 1s timeout" in run {
//         val handler = HttpHandler.get("/4") { _ =>
//             Async.sleep(3.seconds).andThen(HttpResponse.ok("right"))
//         }
//         startTestServer(handler).map { port =>
//             val url       = s"http://localhost:$port/4"
//             val normalReq = req(url)
//             val reqWithTimeout =
//                 Abort.run[Timeout](Async.timeout(1.second)(req(url)))
//                     .map(_ => Async.sleep(1.hour))
//                     .andThen("never")
//             Async.race(normalReq, reqWithTimeout).map { result =>
//                 assert(result == "right")
//             }
//         }
//     }
//
//     "scenario 5 - race 2 concurrent requests, non-200 is loser" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/5") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 1 then
//                             promise.get.andThen(HttpResponse.serverError("wrong"))
//                         else
//                             promise.completeUnitDiscard.andThen {
//                                 Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/5"
//                     Async.race(req(url), req(url)).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 6 - race 3 concurrent requests, non-200 is loser" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/6") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 1 then
//                             promise.get.andThen(HttpResponse.serverError("wrong"))
//                         else if num == 2 then
//                             promise.get.andThen {
//                                 Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                             }
//                         else
//                             promise.completeUnitDiscard.andThen {
//                                 Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/6"
//                     Async.race(req(url), req(url), req(url)).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 7 - hedging with delayed second request" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Instant, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/7") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 1 then
//                             Clock.now.map { firstTime =>
//                                 promise.get.map { secondTime =>
//                                     if JDuration.between(firstTime.toJava, secondTime.toJava).getSeconds >= 2 then
//                                         HttpResponse.ok("right")
//                                     else
//                                         HttpResponse.ok("wrong")
//                                 }
//                             }
//                         else
//                             Clock.now.map { now =>
//                                 promise.complete(Result.succeed(now)).andThen {
//                                     Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                                 }
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url        = s"http://localhost:$port/7"
//                     val r          = req(url)
//                     val delayedReq = Async.delay(4.seconds)(req(url))
//                     Async.race(r, delayedReq).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 8 - resource acquire/use/release with race" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Promise[String, Nothing], Nothing].map { promise =>
//                 val handler = HttpHandler.get("/8") { in =>
//                     val hasOpen  = in.request.query("open")
//                     val hasUse   = in.request.query("use")
//                     val hasClose = in.request.query("close")
//
//                     hasOpen match
//                         case Present(_) =>
//                             Random.nextStringAlphanumeric(16).map { id =>
//                                 HttpResponse.ok(id)
//                             }
//                         case Absent =>
//                             hasUse match
//                                 case Present(id) =>
//                                     counter.incrementAndGet.map { num =>
//                                         if num == 1 then
//                                             promise.get.andThen(HttpResponse.serverError("wrong"))
//                                         else
//                                             Promise.init[String, Nothing].map { nextPromise =>
//                                                 promise.complete(Result.succeed(nextPromise)).andThen {
//                                                     nextPromise.get.map { closedId =>
//                                                         if id != closedId then HttpResponse.ok("right")
//                                                         else HttpResponse.ok("wrong")
//                                                     }
//                                                 }
//                                             }
//                                     }
//                                 case Absent =>
//                                     hasClose match
//                                         case Present(id) =>
//                                             promise.get.map { innerPromise =>
//                                                 innerPromise.complete(Result.succeed(id)).andThen {
//                                                     HttpResponse.ok
//                                                 }
//                                             }
//                                         case Absent =>
//                                             HttpResponse(HttpStatus.NotAcceptable): HttpResponse[?] < (Async & Any)
//                     end match
//                 }
//                 startTestServer(handler).map { port =>
//                     val baseUrl = s"http://localhost:$port/8"
//
//                     case class MyResource(id: String)
//
//                     val acquireAndUse: String < (Async & Abort[HttpError] & Scope) =
//                         Scope.acquireRelease {
//                             req(s"$baseUrl?open").map(MyResource(_))
//                         } { resource =>
//                             Abort.run[HttpError](req(s"$baseUrl?close=${resource.id}")).unit
//                         }.map { resource =>
//                             req(s"$baseUrl?use=${resource.id}")
//                         }
//
//                     val reqRes = Scope.run(acquireAndUse)
//                     Async.race(reqRes, reqRes).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 9 - assemble letters from concurrent requests by response order" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Channel[Maybe[(Char, Int)]], Nothing].map { promise =>
//                 def letterOrFail(ch: Channel[Maybe[(Char, Int)]])(using Frame): HttpResponse[?] < (Async & Abort[Closed]) =
//                     ch.take.map {
//                         case Absent =>
//                             HttpResponse.serverError("wrong")
//                         case Present((char, seconds)) =>
//                             Async.sleep(seconds.seconds).andThen {
//                                 HttpResponse.ok(char.toString)
//                             }
//                     }
//
//                 val handler = HttpHandler.get("/9") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num < 10 then
//                             promise.get.map { ch =>
//                                 letterOrFail(ch)
//                             }
//                         else
//                             val letters: Seq[Maybe[(Char, Int)]] =
//                                 "right".zipWithIndex.map { case (c, i) => Present((c, i)) } ++
//                                     Seq.fill(5)(Absent)
//
//                             Random.shuffle(letters).map { shuffled =>
//                                 Channel.initUnscoped[Maybe[(Char, Int)]](16).map { ch =>
//                                     Kyo.foreach(shuffled)(item => ch.put(item)).andThen {
//                                         promise.complete(Result.succeed(ch)).andThen {
//                                             letterOrFail(ch)
//                                         }
//                                     }
//                                 }
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/9"
//
//                     val makeReq: Result[HttpError, (Instant, String)] < Async =
//                         Abort.run[HttpError] {
//                             req(url).map { body =>
//                                 Clock.now.map { now =>
//                                     (now, body)
//                                 }
//                             }
//                         }
//
//                     val reqs = Seq.fill(10)(makeReq)
//
//                     Async.gather(reqs).map { results =>
//                         val successes = results.toSeq.collect { case Result.Success((instant, body)) => instant -> body }
//                         val sorted    = SortedMap.from(successes)
//                         val word      = sorted.values.mkString
//                         assert(word == "right")
//                     }
//                 }
//             }
//         }
//     }
//
//     "scenario 11 - nested race (race of 2 vs single request)" in run {
//         AtomicInt.init.map { counter =>
//             Promise.init[Unit, Nothing].map { promise =>
//                 val handler = HttpHandler.get("/11") { _ =>
//                     counter.incrementAndGet.map { num =>
//                         if num == 3 then
//                             promise.completeUnitDiscard.andThen(HttpResponse.ok("right"))
//                         else
//                             promise.get.andThen {
//                                 HttpResponse.serverError("wrong")
//                             }
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val url = s"http://localhost:$port/11"
//                     Async.race(Async.race(req(url), req(url)), req(url)).map { result =>
//                         assert(result == "right")
//                     }
//                 }
//             }
//         }
//     }
//
// end EasyRacerTest
