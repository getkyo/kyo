// package kyo

// import java.security.MessageDigest
// import java.time.Instant
// import scala.collection.immutable.SortedMap

// class EasyRacerTest extends Test:

//     // The client-side scenario code mirrors the original easyracer scala-kyo client.
//     // Each test embeds the server behavior from the easyracer scenario-server, then
//     // runs the client scenario against it, asserting the result is "right".

//     // -- Shared helpers --

//     /** Makes a GET request and returns the body string, failing on non-200 (like Requests(_.get(url)) in kyo-sttp). */
//     private def req(url: String)(using Frame): String < (Async & Abort[HttpError]) =
//         HttpClient.get[String](url)

//     /** Builds a scenario URL given a port and scenario number. */
//     private def scenarioUrl(port: Int, scenario: Int): String =
//         s"http://localhost:$port/$scenario"

//     // -- Server-side session coordination (mirrors the easyracer scenario-server Session) --

//     private class Session[T]:
//         private val lock                                   = new java.util.concurrent.locks.ReentrantLock()
//         private val numRequestsRef                         = new java.util.concurrent.atomic.AtomicInteger(0)
//         @volatile private var promise: Promise[Nothing, T] = _

//         def init()(using Frame): Unit < Sync =
//             Promise.init[Nothing, T].map { p => promise = p }

//         def add()(using Frame): (Int, Promise[Nothing, T]) < Sync =
//             Sync.defer {
//                 lock.lock()
//                 try
//                     val num = numRequestsRef.incrementAndGet()
//                     val p   = promise
//                     (num, p)
//                 finally lock.unlock()
//                 end try
//             }

//         def remove()(using Frame): Unit < Sync =
//             Sync.defer {
//                 lock.lock()
//                 try
//                     val num = numRequestsRef.decrementAndGet()
//                     if num == 0 then
//                         import AllowUnsafe.embrace.danger
//                         promise = IOPromise[Nothing, T < Any]()
//                 finally lock.unlock()
//                 end try
//             }

//         def get()(using Frame): (Int, Promise[Nothing, T]) < Sync =
//             Sync.defer {
//                 lock.lock()
//                 try
//                     val num = numRequestsRef.get()
//                     val p   = promise
//                     (num, p)
//                 finally lock.unlock()
//                 end try
//             }
//     end Session

//     private def newSession[T]()(using Frame): Session[T] < Sync =
//         Sync.defer {
//             val s = new Session[T]
//             s.init().map(_ => s)
//         }

//     // -- Scenario 1: Race 2 concurrent requests --

//     "scenario 1 - race 2 concurrent requests" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/1") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 1 then
//                         promise.get.map(_ => HttpResponse.ok("right"))
//                     else
//                         promise.completeUnitDiscard.andThen {
//                             Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 1)
//                 Async.race(req(url), req(url)).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 2: Race 2 concurrent requests, where one produces a connection error --

//     "scenario 2 - race 2 concurrent requests, one connection error" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/2") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 1 then
//                         promise.get.andThen {
//                             Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                         }
//                     else
//                         promise.completeUnitDiscard.andThen {
//                             HttpResponse.serverError("connection error")
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 2)
//                 Async.race(req(url), req(url)).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 3: Race 10,000 concurrent requests --

//     "scenario 3 - race 10000 concurrent requests" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/3") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num < 10000 then
//                         promise.get.andThen {
//                             Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                         }
//                     else
//                         promise.completeUnitDiscard.andThen {
//                             HttpResponse.ok("right")
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url  = scenarioUrl(port, 3)
//                 val reqs = Seq.fill(10000)(req(url))
//                 Async.race(reqs).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 4: Race 2 concurrent requests but 1 has a 1 second timeout --

//     "scenario 4 - race 2 concurrent requests, one with 1s timeout" in run {
//         newSession[Unit]().map { session =>
//             // Server: once a request is cancelled (timed out), other pending requests return "right"
//             val handler = HttpHandler.get("/4") { (_, _) =>
//                 session.add().map { (_, promise) =>
//                     promise.get.andThen(HttpResponse.ok("right"))
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url            = scenarioUrl(port, 4)
//                 val normalReq      = req(url)
//                 val reqWithTimeout = Async.timeout(1.second)(req(url))
//                 Async.race(normalReq, reqWithTimeout).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 5: Race 2 concurrent requests where non-200 is a loser --

//     "scenario 5 - race 2 concurrent requests, non-200 is loser" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/5") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 1 then
//                         promise.get.andThen(HttpResponse.serverError("wrong"))
//                     else
//                         promise.completeUnitDiscard.andThen {
//                             Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 5)
//                 Async.race(req(url), req(url)).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 6: Race 3 concurrent requests where non-200 is a loser --

//     "scenario 6 - race 3 concurrent requests, non-200 is loser" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/6") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 1 then
//                         promise.get.andThen(HttpResponse.serverError("wrong"))
//                     else if num == 2 then
//                         promise.get.andThen {
//                             Async.sleep(1.second).andThen(HttpResponse.ok("right"))
//                         }
//                     else
//                         promise.completeUnitDiscard.andThen {
//                             Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 6)
//                 Async.race(req(url), req(url), req(url)).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 7: Hedging - start a request, wait at least 3 seconds then start a second request --

//     "scenario 7 - hedging with delayed second request" in run {
//         newSession[Instant]().map { session =>
//             val handler = HttpHandler.get("/7") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 1 then
//                         promise.get.map { secondTime =>
//                             Clock.now.map { firstTime =>
//                                 // Verify second request came at least 2 seconds after first
//                                 if java.time.Duration.between(firstTime, secondTime).getSeconds >= 2 then
//                                     HttpResponse.ok("right")
//                                 else
//                                     HttpResponse.ok("wrong")
//                             }
//                         }
//                     else
//                         Clock.now.map { now =>
//                             promise.complete(Result.succeed(now)).andThen {
//                                 Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
//                             }
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url        = scenarioUrl(port, 7)
//                 val r          = req(url)
//                 val delayedReq = Async.delay(3.seconds)(req(url))
//                 Async.race(r, delayedReq).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 8: Resource management with open/use/close --

//     "scenario 8 - resource acquire/use/release with race" in run {
//         newSession[Promise[Nothing, String]]().map { session =>
//             val handler = HttpHandler.get("/8") { (_, request) =>
//                 val hasOpen  = request.query("open")
//                 val hasUse   = request.query("use")
//                 val hasClose = request.query("close")

//                 hasOpen match
//                     case Present(_) =>
//                         Random.nextStringAlphanumeric(16).map { id =>
//                             HttpResponse.ok(id)
//                         }
//                     case Absent =>
//                         hasUse match
//                             case Present(id) =>
//                                 session.add().map { (num, promise) =>
//                                     if num == 1 then
//                                         promise.get.andThen(HttpResponse.serverError("wrong"))
//                                     else
//                                         Promise.init[Nothing, String].map { nextPromise =>
//                                             promise.complete(Result.succeed(nextPromise)).andThen {
//                                                 nextPromise.get.map { closedId =>
//                                                     if id != closedId then HttpResponse.ok("right")
//                                                     else HttpResponse.ok("wrong")
//                                                 }
//                                             }
//                                         }
//                                 }.map { resp =>
//                                     Sync.ensure(session.remove())(resp)
//                                 }
//                             case Absent =>
//                                 hasClose match
//                                     case Present(id) =>
//                                         session.get().map { (num, promise) =>
//                                             if num == 1 then
//                                                 promise.get.map { innerPromise =>
//                                                     innerPromise.complete(Result.succeed(id)).andThen {
//                                                         HttpResponse.ok
//                                                     }
//                                                 }
//                                             else
//                                                 HttpResponse.ok
//                                         }
//                                     case Absent =>
//                                         HttpResponse(HttpResponse.Status.NotAcceptable): HttpResponse[?] < (Async & Any)
//                 end match
//             }
//             startTestServer(handler).map { port =>
//                 val baseUrl = s"http://localhost:$port/8"

//                 case class MyResource(id: String)

//                 val acquireAndUse: String < (Async & Abort[HttpError] & Scope) =
//                     Scope.acquireRelease {
//                         req(s"$baseUrl?open").map(MyResource(_))
//                     } { resource =>
//                         Abort.run[HttpError](req(s"$baseUrl?close=${resource.id}")).unit
//                     }.map { resource =>
//                         req(s"$baseUrl?use=${resource.id}")
//                     }

//                 val reqRes = Scope.run(acquireAndUse)
//                 Async.race(reqRes, reqRes).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 9: 10 concurrent requests, 5 return letters, assembled by response time --

//     "scenario 9 - assemble letters from concurrent requests by response order" in run {
//         newSession[Queue[Maybe[(Char, Int)]]]().map { session =>
//             val handler = HttpHandler.get("/9") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num < 10 then
//                         promise.get.map { queue =>
//                             queue.take.map {
//                                 case Present(Absent) =>
//                                     HttpResponse.serverError("wrong")
//                                 case Present(Present((char, seconds))) =>
//                                     Async.sleep(seconds.seconds).andThen {
//                                         HttpResponse.ok(char.toString)
//                                     }
//                                 case _ =>
//                                     HttpResponse.serverError("wrong")
//                             }
//                         }
//                     else
//                         // 10th request: create the queue, distribute letters
//                         val letters: Seq[Maybe[(Char, Int)]] =
//                             "right".zipWithIndex.map { case (c, i) => Present((c, i)) } ++
//                                 Seq.fill(5)(Absent)

//                         Random.shuffle(letters).map { shuffled =>
//                             Queue.init[Maybe[(Char, Int)]](16).map { queue =>
//                                 Kyo.foreach(shuffled)(item => queue.add(item)).andThen {
//                                     promise.complete(Result.succeed(queue)).andThen {
//                                         queue.take.map {
//                                             case Present(Absent) =>
//                                                 HttpResponse.serverError("wrong")
//                                             case Present(Present((char, seconds))) =>
//                                                 Async.sleep(seconds.seconds).andThen {
//                                                     HttpResponse.ok(char.toString)
//                                                 }
//                                             case _ =>
//                                                 HttpResponse.serverError("wrong")
//                                         }
//                                     }
//                                 }
//                             }
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 9)

//                 val makeReq: (Instant, String) < (Async & Abort[HttpError]) =
//                     req(url).map { body =>
//                         Clock.now.map { now =>
//                             (now, body)
//                         }
//                     }

//                 val reqs = Seq.fill(10)(makeReq)

//                 Async.gather(reqs).map { results =>
//                     val sorted = SortedMap.from(results.toSeq.map { case (instant, body) => instant -> body })
//                     val word   = sorted.values.mkString
//                     assert(word == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 10: Cancellable computation with CPU load monitoring --

//     "scenario 10 - cancellable computation with load monitoring" in run {
//         import com.sun.management.OperatingSystemMXBean
//         import java.lang.management.ManagementFactory

//         val sessions = new java.util.concurrent.ConcurrentHashMap[String, (Seq[Double], Duration, Instant)]()

//         val handler = HttpHandler.get("/10") { (_, request) =>
//             // Parse query params: ?id or ?id=load
//             val queryString = request.url
//             val qIdx        = queryString.indexOf('?')
//             if qIdx < 0 then
//                 HttpResponse(HttpResponse.Status.BadRequest): HttpResponse[?] < (Async & Any)
//             else
//                 val paramStr = queryString.substring(qIdx + 1)
//                 val eqIdx    = paramStr.indexOf('=')
//                 if eqIdx < 0 then
//                     // Blocker: ?id (no value)
//                     val id = paramStr
//                     Random.nextInt(5).map { extra =>
//                         val duration = (5 + extra).seconds
//                         Clock.now.map { now =>
//                             sessions.put(id, (Seq.empty, duration, now))
//                             Async.sleep(duration).andThen(HttpResponse.ok)
//                         }
//                     }
//                 else
//                     // Reporter: ?id=load
//                     val id         = paramStr.substring(0, eqIdx)
//                     val loadString = paramStr.substring(eqIdx + 1)
//                     val load       = loadString.toDouble
//                     Clock.now.map { now =>
//                         Option(sessions.get(id)) match
//                             case None =>
//                                 HttpResponse(HttpResponse.Status.Found): HttpResponse[?] < (Async & Any)
//                             case Some((readings, duration, startTime)) =>
//                                 val isInBlocking = now.isBefore(startTime.plusSeconds(duration.toSeconds))
//                                 if isInBlocking then
//                                     sessions.put(id, (readings :+ load, duration, startTime))
//                                     HttpResponse(HttpResponse.Status.Found): HttpResponse[?] < (Async & Any)
//                                 else if readings.size < duration.toSeconds - 1 then
//                                     HttpResponse(HttpResponse.Status.BadRequest, "Not enough readings"): HttpResponse[?] < (Async & Any)
//                                 else
//                                     val meanLoad = readings.sum / readings.size
//                                     if load > 0.3 then
//                                         HttpResponse(HttpResponse.Status.Found, s"Load still high: $load"): HttpResponse[?] < (Async & Any)
//                                     else if meanLoad < 0.8 then
//                                         HttpResponse(
//                                             HttpResponse.Status.BadRequest,
//                                             s"CPU not loaded enough: $meanLoad"
//                                         ): HttpResponse[?] < (Async & Any)
//                                     else
//                                         HttpResponse.ok("right"): HttpResponse[?] < (Async & Any)
//                                     end if
//                                 end if
//                     }
//                 end if
//             end if
//         }
//         startTestServer(handler).map { port =>
//             val baseUrl       = s"http://localhost:$port/10"
//             val messageDigest = MessageDigest.getInstance("SHA-512")

//             def blocking(bytes: Seq[Byte]): Seq[Byte] < Async =
//                 Sync.defer {
//                     val next = messageDigest.digest(bytes.toArray).toSeq
//                     blocking(next)
//                 }

//             def blocker(id: String): String < (Async & Abort[HttpError]) =
//                 Async.race(
//                     HttpClient.send(HttpRequest.get(s"$baseUrl?$id")).map { resp =>
//                         resp.bodyText
//                     },
//                     Random.nextBytes(512).map(_.toSeq).map(blocking).map(_ => "")
//                 )

//             def reporter(id: String): String < (Async & Abort[HttpError]) =
//                 val osBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])
//                 val load   = osBean.getProcessCpuLoad * osBean.getAvailableProcessors
//                 HttpClient.send(HttpRequest.get(s"$baseUrl?$id=$load")).map { response =>
//                     if response.status.isRedirect then
//                         Async.delay(1.second)(reporter(id))
//                     else if response.status.isSuccess then
//                         response.bodyText
//                     else
//                         Abort.fail(HttpError.StatusError(response.status, response.bodyText))
//                 }
//             end reporter

//             Random.nextStringAlphanumeric(8).map { id =>
//                 Async.zip(blocker(id), reporter(id)).map { (_, result) =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

//     // -- Scenario 11: Race a request with another race of 2 requests --

//     "scenario 11 - nested race (race of 2 vs single request)" in run {
//         newSession[Unit]().map { session =>
//             val handler = HttpHandler.get("/11") { (_, _) =>
//                 session.add().map { (num, promise) =>
//                     if num == 3 then
//                         promise.completeUnitDiscard.andThen(HttpResponse.ok("right"))
//                     else
//                         promise.get.andThen {
//                             HttpResponse.serverError("wrong")
//                         }
//                 }.map { resp =>
//                     Sync.ensure(session.remove())(resp)
//                 }
//             }
//             startTestServer(handler).map { port =>
//                 val url = scenarioUrl(port, 11)
//                 Async.race(Async.race(req(url), req(url)), req(url)).map { result =>
//                     assert(result == "right")
//                 }
//             }
//         }
//     }

// end EasyRacerTest
