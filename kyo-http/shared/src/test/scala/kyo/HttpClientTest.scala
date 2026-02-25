// Old kyo package test — commented out, replaced by kyo.http2 tests
// package kyo
//
// import kyo.HttpStatus
//
// class HttpClientTest extends Test:
//
//     case class User(id: Int, name: String) derives Schema, CanEqual
//     case class CreateUser(name: String, email: String) derives Schema, CanEqual
//
//     // Helper to extract body text for assertions
//     private def getBodyText(response: HttpResponse[HttpBody.Bytes]): String =
//         response.bodyText
//
//     "HttpClient.Config" - {
//
//         "default values" in {
//             val config = HttpClient.Config.default
//             assert(config.baseUrl == Absent)
//             assert(config.timeout == 5.seconds)
//             assert(config.connectTimeout == Absent)
//             assert(config.followRedirects == true)
//             assert(config.maxRedirects == 10)
//             assert(config.retrySchedule == Absent)
//         }
//
//         "default retryOn" in {
//             val config = HttpClient.Config.default
//             assert(config.retryOn(HttpResponse(HttpStatus.InternalServerError)) == true)
//             assert(config.retryOn(HttpResponse(HttpStatus.BadGateway)) == true)
//             assert(config.retryOn(HttpResponse(HttpStatus.OK)) == false)
//             assert(config.retryOn(HttpResponse(HttpStatus.BadRequest)) == false)
//         }
//
//         "construction with base URL" in {
//             val config = HttpClient.Config("https://api.example.com")
//             assert(config.baseUrl == Present("https://api.example.com"))
//         }
//
//         "builder methods" - {
//             "timeout" in {
//                 val config = HttpClient.Config.default.timeout(30.seconds)
//                 assert(config.timeout == 30.seconds)
//             }
//
//             "connectTimeout" in {
//                 val config = HttpClient.Config.default.connectTimeout(5.seconds)
//                 assert(config.connectTimeout == Present(5.seconds))
//             }
//
//             "followRedirects true" in {
//                 val config = HttpClient.Config.default.followRedirects(true)
//                 assert(config.followRedirects == true)
//             }
//
//             "followRedirects false" in {
//                 val config = HttpClient.Config.default.followRedirects(false)
//                 assert(config.followRedirects == false)
//             }
//
//             "maxRedirects" in {
//                 val config = HttpClient.Config.default.maxRedirects(5)
//                 assert(config.maxRedirects == 5)
//             }
//
//             "retry with schedule" in {
//                 val schedule = Schedule.exponential(100.millis, 2.0).take(3)
//                 val config   = HttpClient.Config.default.retry(schedule)
//                 assert(config.retrySchedule.isDefined)
//             }
//
//             "retryWhen with predicate" in {
//                 val config = HttpClient.Config.default.retryWhen(_.status.isServerError)
//                 assert(config.retryOn(HttpResponse(HttpStatus.InternalServerError)) == true)
//                 assert(config.retryOn(HttpResponse(HttpStatus.OK)) == false)
//             }
//
//             "chaining" in {
//                 val config = HttpClient.Config.default
//                     .timeout(30.seconds)
//                     .connectTimeout(5.seconds)
//                     .followRedirects(false)
//                     .maxRedirects(3)
//                 assert(config.timeout == 30.seconds)
//                 assert(config.connectTimeout == Present(5.seconds))
//                 assert(config.followRedirects == false)
//                 assert(config.maxRedirects == 3)
//             }
//         }
//
//         "validation" - {
//             "negative maxRedirects throws" in {
//                 assertThrows[IllegalArgumentException] {
//                     HttpClient.Config.default.maxRedirects(-1)
//                 }
//             }
//
//             "negative timeout throws" in {
//                 assertThrows[IllegalArgumentException] {
//                     HttpClient.Config.default.timeout(-1.seconds)
//                 }
//             }
//
//             "negative connectTimeout throws" in {
//                 assertThrows[IllegalArgumentException] {
//                     HttpClient.Config.default.connectTimeout(-1.seconds)
//                 }
//             }
//         }
//     }
//
//     "HttpClient.init" - {
//
//         "with all defaults" in run {
//             val handler = HttpHandler.health("/ping")
//             startTestServer(handler).map { port =>
//                 HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                     client.send(HttpRequest.get(s"http://localhost:$port/ping")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                     }
//                 }
//             }
//         }
//
//         "with pool settings" in run {
//             val handler = HttpHandler.health("/ping")
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(10), backend = PlatformTestBackend.client).map { client =>
//                     client.send(HttpRequest.get(s"http://localhost:$port/ping")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                     }
//                 }
//             }
//         }
//     }
//
//     "HttpClient extensions" - {
//
//         "send" - {
//             "successful request" in run {
//                 val handler = HttpHandler.health("/test")
//                 startTestServer(handler).map { port =>
//                     HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//
//             "returns response with body" in run {
//                 val handler = HttpHandler.get("/data") { _ =>
//                     HttpResponse.ok("test-data")
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                             assertBodyText(response, "test-data")
//                         }
//                     }
//                 }
//             }
//
//             "timeout handling" in run {
//                 startTestServer(neverRespondHandler("/slow")).map { port =>
//                     HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                         HttpClient.withConfig(_.timeout(100.millis)) {
//                             Abort.run(client.send(HttpRequest.get(s"http://localhost:$port/slow"))).map { result =>
//                                 assert(result.isFailure)
//                             }
//                         }
//                     }
//                 }
//             }
//
//             "connection error" in run {
//                 HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                     Abort.run(client.send(HttpRequest.get("http://localhost:59999/test"))).map { result =>
//                         assert(result.isFailure)
//                     }
//                 }
//             }
//         }
//
//         "close" - {
//             "closes connection" in run {
//                 HttpClient.initUnscoped(backend = PlatformTestBackend.client).map { client =>
//                     client.closeNow.map(_ => succeed)
//                 }
//             }
//
//             "idempotent" in run {
//                 HttpClient.initUnscoped(backend = PlatformTestBackend.client).map { client =>
//                     client.closeNow.andThen(client.closeNow).map(_ => succeed)
//                 }
//             }
//         }
//     }
//
//     "Quick methods" - {
//
//         "get" - {
//             "simple URL" in run {
//                 val handler = HttpHandler.get("/data") { _ =>
//                     HttpResponse.ok("hello")
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.get[String](s"http://localhost:$port/data").map { body =>
//                         assert(body == "hello")
//                     }
//                 }
//             }
//
//             "deserializes response" in run {
//                 val handler = jsonHandler("/users/1", User(1, "Alice"))
//                 startTestServer(handler).map { port =>
//                     HttpClient.get[User](s"http://localhost:$port/users/1").map { user =>
//                         assert(user == User(1, "Alice"))
//                     }
//                 }
//             }
//
//             "handles error status" in run {
//                 val handler = HttpHandler.const(HttpRequest.Method.GET, "/users/999", HttpStatus.NotFound)
//                 startTestServer(handler).map { port =>
//                     Abort.run(HttpClient.get[User](s"http://localhost:$port/users/999")).map { result =>
//                         assert(result.isFailure)
//                     }
//                 }
//             }
//         }
//
//         "post" - {
//             "with typed body" in run {
//                 val handler = HttpHandler.post("/users") { in =>
//                     in.request.bodyAs[CreateUser].map(input =>
//                         HttpResponse.created(User(1, input.name))
//                     )
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.post[User, CreateUser](
//                         s"http://localhost:$port/users",
//                         CreateUser("Alice", "alice@example.com")
//                     ).map { user =>
//                         assert(user.name == "Alice")
//                     }
//                 }
//             }
//
//             "deserializes response" in run {
//                 val handler = HttpHandler.post("/users") { _ =>
//                     HttpResponse.created(User(2, "Bob"))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.post[User, CreateUser](
//                         s"http://localhost:$port/users",
//                         CreateUser("Bob", "bob@example.com")
//                     ).map { user =>
//                         assert(user == User(2, "Bob"))
//                     }
//                 }
//             }
//         }
//
//         "put" - {
//             "with typed body" in run {
//                 val handler = HttpHandler.put("/users/1") { in =>
//                     in.request.bodyAs[CreateUser].map(input =>
//                         HttpResponse.ok(User(1, input.name))
//                     )
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.put[User, CreateUser](
//                         s"http://localhost:$port/users/1",
//                         CreateUser("Updated", "updated@example.com")
//                     ).map { user =>
//                         assert(user.name == "Updated")
//                     }
//                 }
//             }
//         }
//
//         "delete" - {
//             "simple URL" in run {
//                 val handler = HttpHandler.delete("/users/1") { _ =>
//                     HttpResponse.noContent
//                 }
//                 startTestServer(handler).map { port =>
//                     testDelete(port, "/users/1").map { response =>
//                         assertStatus(response, HttpStatus.NoContent)
//                     }
//                 }
//             }
//
//             "returns deleted entity" in run {
//                 val handler = HttpHandler.delete("/users/1") { _ =>
//                     HttpResponse.ok(User(1, "Deleted"))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.delete[User](s"http://localhost:$port/users/1").map { user =>
//                         assert(user == User(1, "Deleted"))
//                     }
//                 }
//             }
//
//             "delete[Unit] for 204 No Content" in run {
//                 val handler = HttpHandler.delete("/users/1") { _ =>
//                     HttpResponse.noContent
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.delete[Unit](s"http://localhost:$port/users/1").map { _ =>
//                         succeed
//                     }
//                 }
//             }
//
//             "delete[Unit] fails on error status" in run {
//                 val handler = HttpHandler.delete("/users/1") { _ =>
//                     HttpResponse.notFound("not found")
//                 }
//                 startTestServer(handler).map { port =>
//                     Abort.run[HttpError](HttpClient.delete[Unit](s"http://localhost:$port/users/1")).map { result =>
//                         assert(result.isFailure)
//                     }
//                 }
//             }
//         }
//
//         "send with HttpRequest" - {
//             "get request" in run {
//                 val handler = HttpHandler.health("/health")
//                 startTestServer(handler).map { port =>
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/health")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                     }
//                 }
//             }
//
//             "post request" in run {
//                 val handler = HttpHandler.post("/users") { _ =>
//                     HttpResponse.created(User(1, "Test"))
//                 }
//                 startTestServer(handler).map { port =>
//                     val request = HttpRequest.post(s"http://localhost:$port/users", CreateUser("Test", "test@example.com"))
//                     HttpClient.send(request).map { response =>
//                         assertStatus(response, HttpStatus.Created)
//                     }
//                 }
//             }
//
//             "custom headers" in run {
//                 val handler = HttpHandler.get("/auth") { in =>
//                     in.request.header("Authorization") match
//                         case Present(auth) => HttpResponse.ok(auth)
//                         case Absent        => HttpResponse.unauthorized
//                 }
//                 startTestServer(handler).map { port =>
//                     val request = HttpRequest.get(
//                         s"http://localhost:$port/auth",
//                         HttpHeaders.empty.add("Authorization", "Bearer token123")
//                     )
//                     HttpClient.send(request).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assertBodyText(response, "Bearer token123")
//                     }
//                 }
//             }
//         }
//     }
//
//     "Context management" - {
//
//         "let" - {
//             "overrides config for scope" in run {
//                 val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("ok") }
//                 startTestServer(handler).map { port =>
//                     // Use a very short timeout - if config is applied, slow requests would fail
//                     HttpClient.withConfig(_.timeout(5.seconds)) {
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//
//             "restores after scope" in run {
//                 val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("ok") }
//                 startTestServer(handler).map { port =>
//                     // Set a config in inner scope
//                     HttpClient.withConfig(_.maxRedirects(1)) {
//                         succeed
//                     }.andThen {
//                         // After scope, should be able to follow more redirects (default is 10)
//                         val target = HttpHandler.get("/target") { _ => HttpResponse.ok("done") }
//                         val redir1 = HttpHandler.get("/r1") { _ => HttpResponse.redirect("/r2") }
//                         val redir2 = HttpHandler.get("/r2") { _ => HttpResponse.redirect("/target") }
//                         startTestServer(target, redir1, redir2).map { port2 =>
//                             HttpClient.get[String](s"http://localhost:$port2/r1").map { body =>
//                                 assert(body == "done")
//                             }
//                         }
//                     }
//                 }
//             }
//
//             "nested let" in run {
//                 val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("ok") }
//                 startTestServer(handler).map { port =>
//                     HttpClient.withConfig(_.maxRedirects(5)) {
//                         HttpClient.withConfig(_.timeout(10.seconds)) {
//                             // Both configs should be applied - inner timeout, outer maxRedirects
//                             HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                                 assertStatus(response, HttpStatus.OK)
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//
//         "update" - {
//             "modifies current config" in run {
//                 val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("ok") }
//                 startTestServer(handler).map { port =>
//                     // Start with a base config
//                     HttpClient.withConfig(_.maxRedirects(5)) {
//                         // Modify it further
//                         HttpClient.withConfig(_.timeout(10.seconds)) {
//                             HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                                 assertStatus(response, HttpStatus.OK)
//                             }
//                         }
//                     }
//                 }
//             }
//
//             "restores after scope" in run {
//                 val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("ok") }
//                 startTestServer(handler).map { port =>
//                     HttpClient.withConfig(_.timeout(1.seconds)) {
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }.andThen {
//                         // After scope, config should be restored - request should still work
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//         }
//
//         "baseUrl" - {
//             "prefixes all requests" in run {
//                 val handler = HttpHandler.get("/api/data") { _ => HttpResponse.ok("prefixed") }
//                 startTestServer(handler).map { port =>
//                     HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                         // Request with relative path should be prefixed with baseUrl
//                         HttpClient.send(HttpRequest.get("/api/data")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                             assertBodyText(response, "prefixed")
//                         }
//                     }
//                 }
//             }
//
//             "nested baseUrl" in run {
//                 val outerHandler = HttpHandler.get("/outer") { _ => HttpResponse.ok("outer") }
//                 val innerHandler = HttpHandler.get("/inner") { _ => HttpResponse.ok("inner") }
//                 startTestServer(outerHandler, innerHandler).map { port =>
//                     HttpClient.withConfig(_.baseUrl(s"http://localhost:$port/outer")) {
//                         // Inner baseUrl should replace outer baseUrl
//                         HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                             HttpClient.send(HttpRequest.get("/inner")).map { response =>
//                                 assertStatus(response, HttpStatus.OK)
//                                 assertBodyText(response, "inner")
//                             }
//                         }
//                     }
//                 }
//             }
//
//             "with absolute URL in request" in run {
//                 // Both handlers on same server - test that absolute URL bypasses baseUrl
//                 val baseHandler     = HttpHandler.get("/data") { _ => HttpResponse.ok("from-base") }
//                 val absoluteHandler = HttpHandler.get("/other") { _ => HttpResponse.ok("from-absolute") }
//                 startTestServer(baseHandler, absoluteHandler).map { port =>
//                     // Set baseUrl pointing to /data, but request absolute URL to /other
//                     HttpClient.withConfig(_.baseUrl(s"http://localhost:$port/data")) {
//                         // Absolute URL should bypass baseUrl entirely
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/other")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                             assertBodyText(response, "from-absolute")
//                         }
//                     }
//                 }
//             }
//         }
//     }
//
//     "Retry behavior" - {
//
//         "no retry by default on client error" in run {
//             // 4xx errors should not trigger retry (default retryOn is _.status.isServerError)
//             var attempts = 0
//             val handler = HttpHandler.get("/client-error") { _ =>
//                 attempts += 1
//                 HttpResponse.badRequest
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.retry(Schedule.repeat(3))) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/client-error"))
//                 }.map { response =>
//                     assertStatus(response, HttpStatus.BadRequest)
//                     assert(attempts == 1, s"Expected 1 attempt but got $attempts")
//                 }
//             }
//         }
//
//         "retry on server error" in run {
//             // 5xx errors should trigger retry
//             var attempts = 0
//             val handler = HttpHandler.get("/server-error") { _ =>
//                 attempts += 1
//                 if attempts < 3 then HttpResponse.serverError("temporary")
//                 else HttpResponse.ok("recovered")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.retry(Schedule.repeat(5))) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/server-error"))
//                 }.map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     assertBodyText(response, "recovered")
//                     assert(attempts == 3, s"Expected 3 attempts but got $attempts")
//                 }
//             }
//         }
//
//         "retry with custom schedule" - {
//             "exponential backoff" in run {
//                 var attempts   = 0
//                 var timestamps = List.empty[Long]
//                 val handler = HttpHandler.get("/slow-recovery") { _ =>
//                     attempts += 1
//                     timestamps = timestamps :+ java.lang.System.currentTimeMillis()
//                     if attempts < 3 then HttpResponse.serverError("wait")
//                     else HttpResponse.ok("done")
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.withConfig(_.retry(Schedule.exponential(50.millis, 2.0).take(5))) {
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow-recovery"))
//                     }.map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assert(attempts == 3)
//                         // Verify delays are increasing (roughly exponential)
//                         if timestamps.size >= 3 then
//                             val delay1 = timestamps(1) - timestamps(0)
//                             val delay2 = timestamps(2) - timestamps(1)
//                             assert(delay2 >= delay1, s"Expected increasing delays: $delay1, $delay2")
//                         else
//                             succeed
//                         end if
//                     }
//                 }
//             }
//
//             "max attempts" in run {
//                 var attempts = 0
//                 val handler = HttpHandler.get("/always-fail") { _ =>
//                     attempts += 1
//                     HttpResponse.serverError("always fails")
//                 }
//                 startTestServer(handler).map { port =>
//                     Abort.run {
//                         HttpClient.withConfig(_.retry(Schedule.repeat(3))) {
//                             HttpClient.send(HttpRequest.get(s"http://localhost:$port/always-fail"))
//                         }
//                     }.map {
//                         case Result.Failure(HttpError.RetriesExhausted(attemptCount, status, body)) =>
//                             // After 3 retries (4 total attempts), should fail with RetriesExhausted
//                             assert(attempts == 4, s"Expected 4 attempts (1 + 3 retries) but got $attempts")
//                             assert(attemptCount == 4, s"Expected attemptCount=4 but got $attemptCount")
//                             assert(status == HttpStatus.InternalServerError)
//                             assert(body.contains("always fails"))
//                         case other =>
//                             fail(s"Expected RetriesExhausted but got $other")
//                     }
//                 }
//             }
//         }
//
//         "custom retry predicate" in run {
//             // Retry on 503 Service Unavailable but not on 500
//             var attempts = 0
//             val handler = HttpHandler.get("/custom") { _ =>
//                 attempts += 1
//                 if attempts == 1 then HttpResponse(HttpStatus.ServiceUnavailable)
//                 else if attempts == 2 then HttpResponse.serverError("500 error")
//                 else HttpResponse.ok("done")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(
//                     _.retry(Schedule.repeat(5))
//                         .retryWhen(_.status == HttpStatus.ServiceUnavailable)
//                 ) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/custom"))
//                 }.map { response =>
//                     // Should retry 503, then stop at 500 (not matching predicate)
//                     assertStatus(response, HttpStatus.InternalServerError)
//                     assert(attempts == 2, s"Expected 2 attempts but got $attempts")
//                 }
//             }
//         }
//
//         "no retry after success" in run {
//             var attempts = 0
//             val handler = HttpHandler.get("/success") { _ =>
//                 attempts += 1
//                 HttpResponse.ok("immediate success")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.retry(Schedule.repeat(5))) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/success"))
//                 }.map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     assert(attempts == 1, s"Expected 1 attempt but got $attempts")
//                 }
//             }
//         }
//     }
//
//     "Redirect handling" - {
//
//         "follows redirects by default" in run {
//             val target   = HttpHandler.get("/target") { _ => HttpResponse.ok("final") }
//             val redirect = HttpHandler.get("/start") { _ => HttpResponse.redirect("/target") }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.get[String](s"http://localhost:$port/start").map { body =>
//                     assert(body == "final")
//                 }
//             }
//         }
//
//         "follows redirects with timeout configured" in run {
//             val target   = HttpHandler.get("/target") { _ => HttpResponse.ok("final") }
//             val redirect = HttpHandler.get("/start") { _ => HttpResponse.redirect("/target") }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.withConfig(_.timeout(5.seconds)) {
//                     HttpClient.get[String](s"http://localhost:$port/start")
//                 }.map { body =>
//                     assert(body == "final")
//                 }
//             }
//         }
//
//         "respects maxRedirects" in run {
//             val redirect = HttpHandler.get("/loop") { _ => HttpResponse.redirect("/loop") }
//             startTestServer(redirect).map { port =>
//                 HttpClient.withConfig(_.maxRedirects(2)) {
//                     Abort.run(HttpClient.get[String](s"http://localhost:$port/loop"))
//                 }.map { result =>
//                     assert(result.isFailure)
//                 }
//             }
//         }
//
//         "does not follow when disabled" in run {
//             val redirect = HttpHandler.get("/redir") { _ => HttpResponse.redirect("/target") }
//             startTestServer(redirect).map { port =>
//                 HttpClient.withConfig(_.followRedirects(false)) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/redir"))
//                 }.map { response =>
//                     assert(response.status.isRedirect)
//                 }
//             }
//         }
//
//         "disabled preserves Location header" in run {
//             val redirect = HttpHandler.get("/redir") { _ => HttpResponse.redirect("/target") }
//             startTestServer(redirect).map { port =>
//                 HttpClient.withConfig(_.followRedirects(false)) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/redir"))
//                 }.map { response =>
//                     assertStatus(response, HttpStatus.Found)
//                     assertHeader(response, "Location", "/target")
//                 }
//             }
//         }
//
//         "disabled preserves Set-Cookie on redirect response" in run {
//             val redirect = HttpHandler.get("/login") { _ =>
//                 HttpResponse.redirect("/dashboard")
//                     .addCookie(HttpResponse.Cookie("session", "abc123"))
//             }
//             startTestServer(redirect).map { port =>
//                 HttpClient.withConfig(_.followRedirects(false)) {
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/login"))
//                 }.map { response =>
//                     assert(response.status.isRedirect)
//                     val setCookie = response.header("Set-Cookie")
//                     assert(setCookie.isDefined, "Expected Set-Cookie header on redirect response")
//                     assert(setCookie.exists(_.contains("session=abc123")))
//                 }
//             }
//         }
//
//         "handles redirect loop" in run {
//             val redirect = HttpHandler.get("/loop") { _ => HttpResponse.redirect("/loop") }
//             startTestServer(redirect).map { port =>
//                 HttpClient.withConfig(_.maxRedirects(5)) {
//                     Abort.run(HttpClient.get[String](s"http://localhost:$port/loop"))
//                 }.map { result =>
//                     assert(result.isFailure)
//                 }
//             }
//         }
//
//         "301 permanent redirect" in run {
//             val target   = HttpHandler.get("/new") { _ => HttpResponse.ok("moved") }
//             val redirect = HttpHandler.get("/old") { _ => HttpResponse.movedPermanently("/new") }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     assertBodyText(response, "moved")
//                 }
//             }
//         }
//
//         "302 found" in run {
//             val target   = HttpHandler.get("/new") { _ => HttpResponse.ok("found") }
//             val redirect = HttpHandler.get("/old") { _ => HttpResponse.redirect("/new", HttpStatus.Found) }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                 }
//             }
//         }
//
//         "307 temporary redirect" in run {
//             val target   = HttpHandler.get("/new") { _ => HttpResponse.ok("temp") }
//             val redirect = HttpHandler.get("/old") { _ => HttpResponse.redirect("/new", HttpStatus.TemporaryRedirect) }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                 }
//             }
//         }
//
//         "308 permanent redirect" in run {
//             val target   = HttpHandler.get("/new") { _ => HttpResponse.ok("perm") }
//             val redirect = HttpHandler.get("/old") { _ => HttpResponse.redirect("/new", HttpStatus.PermanentRedirect) }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                 }
//             }
//         }
//     }
//
//     "Timeout handling" - {
//
//         "request timeout" in run {
//             startTestServer(neverRespondHandler("/slow")).map { port =>
//                 HttpClient.withConfig(_.timeout(100.millis)) {
//                     Abort.run(HttpClient.get[String](s"http://localhost:$port/slow"))
//                 }.map { result =>
//                     assert(result.isFailure)
//                 }
//             }
//         }
//
//         "connect timeout" in run {
//             // Use a non-routable IP to simulate slow/hanging connection
//             // 10.255.255.1 is typically non-routable and will cause connection to hang
//             HttpClient.withConfig(_.connectTimeout(100.millis)) {
//                 Abort.run(HttpClient.get[String]("http://10.255.255.1:12345/test"))
//             }.map { result =>
//                 assert(result.isFailure)
//             }
//         }
//
//         "no timeout by default" in {
//             val config = HttpClient.Config.default
//             assert(config.timeout == 5.seconds)
//             assert(config.connectTimeout == Absent)
//         }
//     }
//
//     "Error scenarios" - {
//
//         "connection refused" in run {
//             Abort.run(HttpClient.get[String]("http://localhost:59999")).map { result =>
//                 assert(result.isFailure)
//             }
//         }
//
//         "DNS resolution failure" in run {
//             Abort.run(HttpClient.get[String]("http://nonexistent.invalid.domain.test")).map { result =>
//                 assert(result.isFailure)
//             }
//         }
//
//         "response parsing error" in run {
//             val handler = HttpHandler.get("/html") { _ =>
//                 HttpResponse.ok("<html>not json</html>")
//             }
//             startTestServer(handler).map { port =>
//                 Abort.run(HttpClient.get[User](s"http://localhost:$port/html")).map { result =>
//                     assert(result.isFailure)
//                 }
//             }
//         }
//
//         "invalid URL throws" in {
//             assertThrows[Exception] {
//                 HttpRequest.get("not a valid url")
//             }
//         }
//
//         "empty URL throws" in {
//             assertThrows[Exception] {
//                 HttpRequest.get("")
//             }
//         }
//
//         "invalid baseUrl throws" in {
//             assertThrows[Exception] {
//                 HttpClient.Config("not a valid url")
//             }
//         }
//     }
//
//     "HttpError types" - {
//
//         "ConnectionFailed for connection refused" in run {
//             Abort.run(HttpClient.get[String]("http://localhost:59999")).map {
//                 case Result.Failure(HttpError.ConnectionFailed(host, port, _)) =>
//                     assert(host == "localhost")
//                     assert(port == 59999)
//                 case other =>
//                     fail(s"Expected ConnectionFailed but got $other")
//             }
//         }
//
//         "InvalidResponse for HTTP error status" in run {
//             val handler = HttpHandler.const(HttpRequest.Method.GET, "/notfound", HttpStatus.NotFound)
//             startTestServer(handler).map { port =>
//                 Abort.run(HttpClient.get[User](s"http://localhost:$port/notfound")).map {
//                     case Result.Failure(HttpError.StatusError(status, _)) =>
//                         assert(status == HttpStatus.NotFound)
//                     case other =>
//                         fail(s"Expected StatusError but got $other")
//                 }
//             }
//         }
//
//         "ParseError for parsing error" in run {
//             val handler = HttpHandler.get("/html") { _ =>
//                 HttpResponse.ok("<html>not json</html>")
//             }
//             startTestServer(handler).map { port =>
//                 Abort.run(HttpClient.get[User](s"http://localhost:$port/html")).map {
//                     case Result.Failure(HttpError.ParseError(msg, _)) =>
//                         assert(msg.contains("decode error") || msg.contains("Failed to parse"))
//                     case other =>
//                         fail(s"Expected ParseError but got $other")
//                 }
//             }
//         }
//
//         "TooManyRedirects when exceeding limit" in run {
//             val handler = HttpHandler.get("/redirect") { _ =>
//                 HttpResponse(HttpStatus.Found).setHeader("Location", "/redirect")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.maxRedirects(3)) {
//                     Abort.run(HttpClient.send(HttpRequest.get(s"http://localhost:$port/redirect")))
//                 }.map {
//                     case Result.Failure(HttpError.TooManyRedirects(count)) =>
//                         assert(count == 3)
//                     case other =>
//                         fail(s"Expected TooManyRedirects but got $other")
//                 }
//             }
//         }
//
//         "Timeout when request exceeds duration" in run {
//             val handler = neverRespondHandler("/slow")
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.timeout(100.millis)) {
//                     Abort.run(HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow")))
//                 }.map {
//                     case Result.Failure(HttpError.Timeout(msg)) =>
//                         assert(msg.contains("timed out"))
//                     case other =>
//                         fail(s"Expected Timeout but got $other")
//                 }
//             }
//         }
//     }
//
//     "Concurrency" - {
//
//         val iterations = 20
//
//         "single client parallel requests" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Async.fill(10, 10)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))).map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                         assert(responses.forall(r => getBodyText(r) == "pong"))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "parallel requests with delay" in run {
//             val handler = HttpHandler.get("/slow") { _ =>
//                 Async.delay(10.millis)(HttpResponse.ok("done"))
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Async.fill(5, 5)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow"))).map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "sequential then parallel" in run {
//             val handler = HttpHandler.get("/data") { _ =>
//                 HttpResponse.ok("data")
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { _ =>
//                     for
//                         r1       <- HttpClient.send(HttpRequest.get(s"http://localhost:$port/data"))
//                         r2       <- HttpClient.send(HttpRequest.get(s"http://localhost:$port/data"))
//                         parallel <- Async.fill(3, 3)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/data")))
//                     yield
//                         assert(r1.status == HttpStatus.OK)
//                         assert(r2.status == HttpStatus.OK)
//                         assert(parallel.forall(_.status == HttpStatus.OK))
//                 }.andThen(succeed)
//             }
//         }
//
//         "high concurrency" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 val concurrency = Runtime.getRuntime().availableProcessors()
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))).map {
//                         responses =>
//                             assert(responses.size == concurrency)
//                             assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "interleaved client and server" in run {
//             val handler = HttpHandler.get("/echo") { in =>
//                 HttpResponse.ok(in.request.header("X-Request-Id").getOrElse("unknown"))
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { iter =>
//                     val requests = (1 to 10).map { i =>
//                         HttpClient.send(HttpRequest.get(
//                             s"http://localhost:$port/echo",
//                             HttpHeaders.empty.add("X-Request-Id", s"req-$iter-$i")
//                         ))
//                     }
//                     Async.collectAll(requests).map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                         val bodies = responses.map(_.bodyText).toSet
//                         assert(bodies.size == 10)
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "multiple clients parallel" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Async.fill(3, 3) {
//                         HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                             client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                         }
//                     }.map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "race between requests" in run {
//             val slowHandler = HttpHandler.get("/slow") { _ =>
//                 Async.delay(1.second)(HttpResponse.ok("slow"))
//             }
//             val fastHandler = HttpHandler.get("/fast") { _ =>
//                 HttpResponse.ok("fast")
//             }
//             startTestServer(slowHandler, fastHandler).map { port =>
//                 Kyo.foreach(1 to 100) { _ =>
//                     Async.race(
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow")),
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/fast"))
//                     ).map { response =>
//                         assert(getBodyText(response) == "fast")
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "concurrent requests with shared state handler" in run {
//             val counter = new java.util.concurrent.atomic.AtomicInteger(0)
//             val handler = HttpHandler.get("/count") { _ =>
//                 val count = counter.incrementAndGet()
//                 HttpResponse.ok(count.toString)
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { iter =>
//                     val startCount = counter.get()
//                     Async.fill(20, 20)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/count"))).map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                         val counts = responses.map(r => getBodyText(r).toInt)
//                         // All counts in this batch should be unique (no lost updates)
//                         assert(counts.toSet.size == 20)
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "stress test - rapid sequential requests" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Kyo.foreach(1 to 50) { _ =>
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                     }.map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "benchmark-like pattern - single request" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 val url = s"http://localhost:$port/ping"
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Abort.run[HttpError](HttpClient.send(HttpRequest.get(url)).map(_.bodyText)).map {
//                         case Result.Success(body) => assert(body == "pong")
//                         case Result.Failure(e)    => fail(s"Request failed: $e")
//                         case Result.Panic(e)      => fail(s"Request panicked: $e")
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "benchmark-like pattern - concurrent fill" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             val concurrency = Runtime.getRuntime().availableProcessors()
//             startTestServer(handler).map { port =>
//                 val url = s"http://localhost:$port/ping"
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Abort.run[HttpError](Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(url)).map(_.bodyText))).map {
//                         case Result.Success(bodies) =>
//                             assert(bodies.size == concurrency)
//                             assert(bodies.forall(_ == "pong"))
//                         case Result.Failure(e) => fail(s"Request failed: $e")
//                         case Result.Panic(e)   => fail(s"Request panicked: $e")
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//         "benchmark-like pattern - repeated concurrent fill" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             val concurrency = Runtime.getRuntime().availableProcessors()
//             startTestServer(handler).map { port =>
//                 val url = s"http://localhost:$port/ping"
//                 Kyo.foreach(1 to iterations) { _ =>
//                     Kyo.foreach(1 to 5) { _ =>
//                         Abort.run[HttpError](Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(url)).map(_.bodyText)))
//                     }.map { results =>
//                         assert(results.forall(_.isSuccess))
//                     }
//                 }.andThen(succeed)
//             }
//         }
//
//     }
//
//     "Integration with HttpRoute" - {
//
//         "call route (no-arg overload)" in run {
//             val route   = HttpRoute.get("users").response(_.bodyJson[Seq[User]])
//             val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route)
//                 }.map { result =>
//                     assert(result.body == Seq(User(1, "Alice"), User(2, "Bob")))
//                 }
//             }
//         }
//
//         "route with path params (bare value overload)" in run {
//
//             val route   = HttpRoute.get("users" / Capture[Int]("id")).response(_.bodyJson[User])
//             val handler = route.handle(in => User(in.id, s"User${in.id}"))
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, 42)
//                 }.map { result =>
//                     assert(result.body == User(42, "User42"))
//                 }
//             }
//         }
//
//         "route with query params" in run {
//             val route = HttpRoute.get("users")
//                 .request(_.query[Int]("limit", default = Some(20)).query[Int]("offset", default = Some(0)))
//                 .response(_.bodyJson[Seq[User]])
//             val handler = route.handle { in =>
//                 (in.offset until (in.offset + in.limit)).map(i => User(i, s"User$i"))
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (3, 10))
//                 }.map { result =>
//                     assert(result.body.size == 3)
//                     assert(result.body.head.id == 10)
//                 }
//             }
//         }
//
//         "route with headers" in run {
//             val route = HttpRoute.get("users")
//                 .request(_.header[String]("X-Request-Id"))
//                 .response(_.bodyJson[Seq[User]])
//             val handler = route.handle { in =>
//                 Seq(User(1, in.`X-Request-Id`))
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, "req-123")
//                 }.map { result =>
//                     assert(result.body.head.name == "req-123")
//                 }
//             }
//         }
//
//         "route with body" in run {
//             val route = HttpRoute.post("users")
//                 .request(_.bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(1, in.body.name)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, CreateUser("Alice", "alice@example.com"))
//                 }.map { result =>
//                     assert(result.body == User(1, "Alice"))
//                 }
//             }
//         }
//
//         "route with path params and body" in run {
//
//             val route = HttpRoute.post("users" / Capture[Int]("id"))
//                 .request(_.bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, in.body.name)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(42, "Alice"))
//                 }
//             }
//         }
//
//         "route with query params and body" in run {
//             val route = HttpRoute.post("users")
//                 .request(_.query[String]("role").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(1, s"${in.body.name}:${in.role}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("admin", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(1, "Alice:admin"))
//                 }
//             }
//         }
//
//         "route with header and body" in run {
//             val route = HttpRoute.post("users")
//                 .request(_.header[String]("X-Tenant").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(1, s"${in.body.name}@${in.`X-Tenant`}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("acme", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(1, "Alice@acme"))
//                 }
//             }
//         }
//
//         "route with path and query params" in run {
//
//             val route = HttpRoute.get("users" / Capture[Int]("id"))
//                 .request(_.query[String]("fields"))
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, in.fields)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "name,email"))
//                 }.map { result =>
//                     assert(result.body == User(42, "name,email"))
//                 }
//             }
//         }
//
//         "route with path, query, and body" in run {
//
//             val route = HttpRoute.post("users" / Capture[Int]("id"))
//                 .request(_.query[String]("action").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, s"${in.body.name}:${in.action}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "update", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(42, "Alice:update"))
//                 }
//             }
//         }
//
//         "route with path, header, and body" in run {
//
//             val route = HttpRoute.post("users" / Capture[Int]("id"))
//                 .request(_.header[String]("X-Tenant").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, s"${in.body.name}@${in.`X-Tenant`}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "acme", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(42, "Alice@acme"))
//                 }
//             }
//         }
//
//         "route with path, query, header, and body" in run {
//
//             val route = HttpRoute.post("users" / Capture[Int]("id"))
//                 .request(_.query[String]("action").header[String]("X-Tenant").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, s"${in.body.name}:${in.action}@${in.`X-Tenant`}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "create", "acme", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(42, "Alice:create@acme"))
//                 }
//             }
//         }
//
//         "route with multiple path captures and body" in run {
//
//             val route = HttpRoute.post("orgs" / Capture[String]("org") / "users" / Capture[Int]("id"))
//                 .request(_.bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, s"${in.body.name}@${in.org}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("acme", 42, CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(42, "Alice@acme"))
//                 }
//             }
//         }
//
//         "route with multipart body" in run {
//             val route = HttpRoute.post("upload")
//                 .request(_.bodyMultipart)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 val summary = in.parts.map(p => s"${p.name}:${p.data.size}").mkString(",")
//                 s"count=${in.parts.size},$summary"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     val parts = Seq(
//                         HttpRequest.Part("file", Present("test.txt"), Present("text/plain"), Span.fromUnsafe("hello".getBytes("UTF-8")))
//                     )
//                     HttpClient.call(route, parts)
//                 }.map { result =>
//                     assert(result.body == "count=1,file:5")
//                 }
//             }
//         }
//
//         "route with path params and multipart body" in run {
//
//             val route = HttpRoute.post("uploads" / Capture[String]("category"))
//                 .request(_.bodyMultipart)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"category=${in.category},count=${in.parts.size}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     val parts = Seq(
//                         HttpRequest.Part("file", Present("a.txt"), Present("text/plain"), Span.fromUnsafe("aaa".getBytes("UTF-8"))),
//                         HttpRequest.Part("file2", Present("b.txt"), Present("text/plain"), Span.fromUnsafe("bbb".getBytes("UTF-8")))
//                     )
//                     HttpClient.call(route, ("photos", parts))
//                 }.map { result =>
//                     assert(result.body == "category=photos,count=2")
//                 }
//             }
//         }
//
//         "route with query params and multipart body" in run {
//             val route = HttpRoute.post("upload")
//                 .request(_.query[String]("tag").bodyMultipart)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"tag=${in.tag},count=${in.parts.size}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     val parts = Seq(
//                         HttpRequest.Part(
//                             "doc",
//                             Present("doc.pdf"),
//                             Present("application/pdf"),
//                             Span.fromUnsafe("pdf-data".getBytes("UTF-8"))
//                         )
//                     )
//                     HttpClient.call(route, ("important", parts))
//                 }.map { result =>
//                     assert(result.body == "tag=important,count=1")
//                 }
//             }
//         }
//
//         "route with multipart preserves part details" in run {
//             val route = HttpRoute.post("upload")
//                 .request(_.bodyMultipart)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 val p = in.parts.head
//                 s"name=${p.name},filename=${p.filename.getOrElse("none")},ct=${p.contentType.getOrElse("none")},size=${p.data.size}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     val parts = Seq(
//                         HttpRequest.Part("photo", Present("sunset.jpg"), Present("image/jpeg"), Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5)))
//                     )
//                     HttpClient.call(route, parts)
//                 }.map { result =>
//                     assert(result.body == "name=photo,filename=sunset.jpg,ct=image/jpeg,size=5")
//                 }
//             }
//         }
//
//         "route with multipart form fields and files" in run {
//             val route = HttpRoute.post("upload")
//                 .request(_.bodyMultipart)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 in.parts.map { p =>
//                     val isFile = p.filename.isDefined
//                     val value  = new String(p.data.toArrayUnsafe, "UTF-8")
//                     s"${p.name}:${if isFile then "file" else "field"}=$value"
//                 }.mkString(";")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     val parts = Seq(
//                         HttpRequest.Part("title", Absent, Absent, Span.fromUnsafe("My Doc".getBytes("UTF-8"))),
//                         HttpRequest.Part("file", Present("doc.txt"), Present("text/plain"), Span.fromUnsafe("content".getBytes("UTF-8")))
//                     )
//                     HttpClient.call(route, parts)
//                 }.map { result =>
//                     assert(result.body == "title:field=My Doc;file:file=content")
//                 }
//             }
//         }
//
//         "route with cookie" in run {
//             val route = HttpRoute.get("dashboard")
//                 .request(_.cookie[String]("session"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"session=${in.session}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, "abc123")
//                 }.map { result =>
//                     assert(result.body == "session=abc123")
//                 }
//             }
//         }
//
//         "route with cookie and path params" in run {
//
//             val route = HttpRoute.get("users" / Capture[Int]("id"))
//                 .request(_.cookie[String]("token"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"id=${in.id},token=${in.token}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "tok-xyz"))
//                 }.map { result =>
//                     assert(result.body == "id=42,token=tok-xyz")
//                 }
//             }
//         }
//
//         "route with authBearer" in run {
//             val route = HttpRoute.get("protected")
//                 .request(_.authBearer)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"bearer=${in.bearer}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, "my-token")
//                 }.map { result =>
//                     assert(result.body == "bearer=my-token")
//                 }
//             }
//         }
//
//         "route with authBearer and path params" in run {
//
//             val route = HttpRoute.get("users" / Capture[Int]("id"))
//                 .request(_.authBearer)
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(in.id, in.bearer)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, (42, "secret-token"))
//                 }.map { result =>
//                     assert(result.body == User(42, "secret-token"))
//                 }
//             }
//         }
//
//         "route with authBasic" in run {
//             val route = HttpRoute.get("admin")
//                 .request(_.authBasic)
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"user=${in.username},pass=${in.password}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("alice", "secret"))
//                 }.map { result =>
//                     assert(result.body == "user=alice,pass=secret")
//                 }
//             }
//         }
//
//         "route with authApiKey" in run {
//             val route = HttpRoute.get("api")
//                 .request(_.authApiKey("X-Api-Key"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"key=${in.`X-Api-Key`}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, "api-key-123")
//                 }.map { result =>
//                     assert(result.body == "key=api-key-123")
//                 }
//             }
//         }
//
//         "route with authApiKey and body" in run {
//             val route = HttpRoute.post("items")
//                 .request(_.authApiKey("X-Api-Key").bodyJson[CreateUser])
//                 .response(_.bodyJson[User])
//             val handler = route.handle { in =>
//                 User(1, s"${in.body.name}:${in.`X-Api-Key`}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("key-abc", CreateUser("Alice", "alice@example.com")))
//                 }.map { result =>
//                     assert(result.body == User(1, "Alice:key-abc"))
//                 }
//             }
//         }
//
//         // BUG: Client assumes fixed extraction order [path|query|header|cookie|body]
//         // but the named tuple type follows declaration order. When methods are called
//         // in non-standard order, the client extracts values from wrong tuple positions.
//
//         "route with header before query (declaration order mismatch)" in run {
//             val route = HttpRoute.get("test")
//                 .request(_.header[String]("X-Tenant").query[Int]("limit"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"tenant=${in.`X-Tenant`},limit=${in.limit}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("acme", 42))
//                 }.map { result =>
//                     assert(result.body == "tenant=acme,limit=42")
//                 }
//             }
//         }
//
//         "route with cookie before query (declaration order mismatch)" in run {
//             val route = HttpRoute.get("test")
//                 .request(_.cookie[String]("session").query[Int]("limit"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"session=${in.session},limit=${in.limit}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("abc", 42))
//                 }.map { result =>
//                     assert(result.body == "session=abc,limit=42")
//                 }
//             }
//         }
//
//         "route with authBearer before query (declaration order mismatch)" in run {
//             val route = HttpRoute.get("test")
//                 .request(_.authBearer.query[Int]("limit"))
//                 .response(_.bodyText)
//             val handler = route.handle { in =>
//                 s"bearer=${in.bearer},limit=${in.limit}"
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     HttpClient.call(route, ("my-token", 42))
//                 }.map { result =>
//                     assert(result.body == "bearer=my-token,limit=42")
//                 }
//             }
//         }
//
//         "route error handling" in run {
//
//             case class NotFoundError(message: String) derives Schema, CanEqual
//             val route = HttpRoute.get("users" / Capture[Int]("id"))
//                 .response(_.bodyJson[User].error[NotFoundError](HttpStatus.NotFound))
//             val handler = route.handle { in =>
//                 if in.id == 999 then Abort.fail(NotFoundError("User not found"))
//                 else User(in.id, s"User${in.id}")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.baseUrl(s"http://localhost:$port")) {
//                     // Test successful case
//                     HttpClient.call(route, 1).map { result =>
//                         assert(result.body == User(1, "User1"))
//                     }.andThen {
//                         // Test error case - should return 404 with error body
//                         HttpClient.send(HttpRequest.get(s"http://localhost:$port/users/999")).map { response =>
//                             assertStatus(response, HttpStatus.NotFound)
//                             assertBodyContains(response, "User not found")
//                         }
//                     }
//                 }
//             }
//         }
//     }
//
//     "Connection reuse" - {
//
//         "reuses connections for sequential requests to same host" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(10), backend = PlatformTestBackend.client).map { client =>
//                     Kyo.foreach(1 to 5) { _ =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                     }.map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }
//             }
//         }
//
//         "reuses connections for parallel requests to same host" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(5), backend = PlatformTestBackend.client).map { client =>
//                     Async.fill(10, 10) {
//                         client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                     }.map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }
//             }
//         }
//
//         "respects maxConnectionsPerHost limit" in run {
//             val handler = HttpHandler.get("/slow") { _ =>
//                 Async.delay(100.millis)(HttpResponse.ok("done"))
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(2), backend = PlatformTestBackend.client).map { client =>
//                     Async.fill(4, 4) {
//                         client.send(HttpRequest.get(s"http://localhost:$port/slow"))
//                     }.map { responses =>
//                         assert(responses.size == 4)
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }
//             }
//         }
//
//         "maintains separate pools per host" in run {
//             val handler1 = HttpHandler.get("/h1") { _ => HttpResponse.ok("host1") }
//             val handler2 = HttpHandler.get("/h2") { _ => HttpResponse.ok("host2") }
//             startTestServer(handler1, handler2).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(2), backend = PlatformTestBackend.client).map { client =>
//                     val requests = Seq(
//                         client.send(HttpRequest.get(s"http://localhost:$port/h1")),
//                         client.send(HttpRequest.get(s"http://localhost:$port/h2")),
//                         client.send(HttpRequest.get(s"http://localhost:$port/h1")),
//                         client.send(HttpRequest.get(s"http://localhost:$port/h2"))
//                     )
//                     Async.collectAll(requests).map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }
//             }
//         }
//
//         "handles connection close by server gracefully" in run {
//             val handler = HttpHandler.get("/test") { _ =>
//                 HttpResponse.ok("ok")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(1), backend = PlatformTestBackend.client).map { client =>
//                     client.send(HttpRequest.get(s"http://localhost:$port/test")).map { r1 =>
//                         assertStatus(r1, HttpStatus.OK)
//                     }.andThen {
//                         client.send(HttpRequest.get(s"http://localhost:$port/test")).map { r2 =>
//                             assertStatus(r2, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//         }
//
//         "warmup pre-establishes connections" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(5), backend = PlatformTestBackend.client).map { client =>
//                     client.warmupUrl(s"http://localhost:$port", 1.second).andThen {
//                         Async.fill(3, 3) {
//                             client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                         }.map { responses =>
//                             assert(responses.forall(_.status == HttpStatus.OK))
//                         }
//                     }
//                 }
//             }
//         }
//
//         "warmup with multiple URLs" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                     client.warmupUrls(
//                         Seq(
//                             s"http://localhost:$port/ping",
//                             s"http://localhost:$port/ping"
//                         ),
//                         1.second
//                     ).andThen {
//                         client.send(HttpRequest.get(s"http://localhost:$port/ping")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//         }
//
//         "connections survive across multiple request batches" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(3), backend = PlatformTestBackend.client).map { client =>
//                     Kyo.foreach(1 to 5) { _ =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                     }.andThen {
//                         Kyo.foreach(1 to 5) { _ =>
//                             client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                         }
//                     }.andThen {
//                         Kyo.foreach(1 to 5) { _ =>
//                             client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                         }.map { responses =>
//                             assert(responses.forall(_.status == HttpStatus.OK))
//                         }
//                     }
//                 }
//             }
//         }
//
//         "unlimited pool (no maxConnectionsPerHost)" in run {
//             val handler = HttpHandler.get("/ping") { _ =>
//                 HttpResponse.ok("pong")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(backend = PlatformTestBackend.client).map { client =>
//                     Async.fill(20, 20) {
//                         client.send(HttpRequest.get(s"http://localhost:$port/ping"))
//                     }.map { responses =>
//                         assert(responses.size == 20)
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                     }
//                 }
//             }
//         }
//
//         "pool health check removes bad connections" in run {
//             val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
//             val handler = HttpHandler.get("/test") { _ =>
//                 val count = requestCount.incrementAndGet()
//                 HttpResponse.ok(s"request-$count")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(2), backend = PlatformTestBackend.client).map { client =>
//                     Kyo.foreach(1 to 10) { _ =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/test"))
//                     }.map { responses =>
//                         assert(responses.forall(_.status == HttpStatus.OK))
//                         assert(requestCount.get() == 10)
//                     }
//                 }
//             }
//         }
//
//         "shared client reuses connections" in run {
//             val handler = HttpHandler.get("/shared") { _ =>
//                 HttpResponse.ok("shared-response")
//             }
//             startTestServer(handler).map { port =>
//                 Kyo.foreach(1 to 5) { _ =>
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port/shared"))
//                 }.map { responses =>
//                     assert(responses.forall(_.status == HttpStatus.OK))
//                     assert(responses.forall(r => getBodyText(r) == "shared-response"))
//                 }
//             }
//         }
//
//     }
//
//     "Redirect edge cases" - {
//
//         "redirect preserves query parameters in Location" in run {
//             val target   = HttpHandler.get("/target") { in => HttpResponse.ok(s"q=${in.request.query("q").getOrElse("none")}") }
//             val redirect = HttpHandler.get("/start") { _ => HttpResponse.redirect("/target?q=hello") }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/start")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     assertBodyText(response, "q=hello")
//                 }
//             }
//         }
//
//         "redirect chain (A -> B -> C)" in run {
//             val c = HttpHandler.get("/c") { _ => HttpResponse.ok("final") }
//             val b = HttpHandler.get("/b") { _ => HttpResponse.redirect("/c") }
//             val a = HttpHandler.get("/a") { _ => HttpResponse.redirect("/b") }
//             startTestServer(a, b, c).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/a")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     assertBodyText(response, "final")
//                 }
//             }
//         }
//
//         "redirect with absolute URL in Location" in run {
//             val target = HttpHandler.get("/target") { _ => HttpResponse.ok("arrived") }
//             startTestServer(target).map { port =>
//                 val redirect = HttpHandler.get("/start") { _ =>
//                     HttpResponse.redirect(s"http://localhost:$port/target")
//                 }
//                 startTestServer(redirect).map { port2 =>
//                     HttpClient.send(HttpRequest.get(s"http://localhost:$port2/start")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assertBodyText(response, "arrived")
//                     }
//                 }
//             }
//         }
//     }
//
//     "204 NoContent client handling" - {
//
//         "send returns 204 with empty body" in run {
//             val handler = HttpHandler.delete("/items/1") { _ => HttpResponse.noContent }
//             startTestServer(handler).map { port =>
//                 HttpClient.send(HttpRequest.delete(s"http://localhost:$port/items/1")).map { response =>
//                     assertStatus(response, HttpStatus.NoContent)
//                     assert(response.bodyText.isEmpty)
//                 }
//             }
//         }
//     }
//
//     "HEAD request handling" - {
//
//         "HEAD on GET route returns headers without body" in run {
//             val handler = HttpHandler.get("/data") { _ =>
//                 HttpResponse.ok("some content here")
//                     .setHeader("X-Custom", "test-value")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.withConfig(_.timeout(5.seconds)) {
//                     HttpClient.send(HttpRequest.head(s"http://localhost:$port/data")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assert(response.header("X-Custom") == Maybe.Present("test-value"))
//                         assert(response.bodyText.isEmpty)
//                     }
//                 }
//             }
//         }
//     }
//
//     "Redirect with cookies" - {
//
//         "redirect target receives cookies set during redirect" in run {
//             val target = HttpHandler.get("/dashboard") { in =>
//                 val session = in.request.cookie("session").map(_.value).getOrElse("none")
//                 HttpResponse.ok(s"session=$session")
//             }
//             val login = HttpHandler.get("/login") { _ =>
//                 HttpResponse.redirect("/dashboard")
//                     .addCookie(HttpResponse.Cookie("session", "abc123"))
//             }
//             startTestServer(target, login).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/login")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     // Whether cookies are propagated during redirects depends on implementation
//                     // At minimum, the redirect should complete successfully
//                     assert(response.bodyText.contains("session="))
//                 }
//             }
//         }
//     }
//
//     "Large response handling" - {
//
//         "client receives large response body (100KB)" in run {
//             val largeBody = "x" * (100 * 1024)
//             val handler = HttpHandler.get("/large") { _ =>
//                 HttpResponse.ok(largeBody)
//             }
//             HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 200 * 1024), PlatformTestBackend.server)(handler).map {
//                 server =>
//                     HttpClient.send(HttpRequest.get(s"http://localhost:${server.port}/large")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assert(response.bodyText.length == 100 * 1024)
//                     }
//             }
//         }
//     }
//
//     "Binary body handling" - {
//
//         "preserves non-UTF-8 binary bytes" in run {
//             val binaryData = Array[Byte](0, 1, 2, 127, -128, -1, 0x7f, 0x80.toByte, 0xff.toByte, 0xfe.toByte)
//             val handler = HttpHandler.get("/binary") { _ =>
//                 HttpResponse.initBytes(
//                     HttpStatus.OK,
//                     HttpHeaders.empty.add("Content-Type", "application/octet-stream"),
//                     Span.fromUnsafe(binaryData)
//                 )
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/binary")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     val received = response.body.data
//                     assert(
//                         received.toSeq == binaryData.toSeq,
//                         s"Binary body corrupted: expected ${binaryData.toSeq} but got ${received.toSeq}"
//                     )
//                 }
//             }
//         }
//
//         "preserves high-byte binary content" in run {
//             // All byte values 0-255 to detect any encoding corruption
//             val allBytes = Array.tabulate[Byte](256)(_.toByte)
//             val handler = HttpHandler.get("/allbytes") { _ =>
//                 HttpResponse.initBytes(
//                     HttpStatus.OK,
//                     HttpHeaders.empty.add("Content-Type", "application/octet-stream"),
//                     Span.fromUnsafe(allBytes)
//                 )
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.send(HttpRequest.get(s"http://localhost:$port/allbytes")).map { response =>
//                     assertStatus(response, HttpStatus.OK)
//                     val received = response.body.data
//                     assert(received.length == 256, s"Expected 256 bytes but got ${received.length}")
//                     assert(received.toSeq == allBytes.toSeq, "Binary body corrupted: byte values not preserved through roundtrip")
//                 }
//             }
//         }
//     }
//
//     "Response size limit" - {
//
//         "rejects response exceeding maxResponseSizeBytes" in run {
//             val bigBody = "x" * 2000
//             val handler = HttpHandler.get("/big") { _ =>
//                 HttpResponse.ok(bigBody)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxResponseSizeBytes = 100, backend = PlatformTestBackend.client).map { client =>
//                     Abort.run(client.send(HttpRequest.get(s"http://localhost:$port/big"))).map { result =>
//                         assert(result.isFailure, "Expected failure for response exceeding maxResponseSizeBytes")
//                     }
//                 }
//             }
//         }
//
//         "allows response within maxResponseSizeBytes" in run {
//             val smallBody = "x" * 50
//             val handler = HttpHandler.get("/small") { _ =>
//                 HttpResponse.ok(smallBody)
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxResponseSizeBytes = 1000, backend = PlatformTestBackend.client).map { client =>
//                     client.send(HttpRequest.get(s"http://localhost:$port/small")).map { response =>
//                         assertStatus(response, HttpStatus.OK)
//                         assertBodyText(response, smallBody)
//                     }
//                 }
//             }
//         }
//     }
//
//     "Streaming resource cleanup" - {
//
//         "abandoned stream does not block connection pool" in run {
//             val handler = HttpHandler.get("/data") { _ => HttpResponse.ok("streamed-body") }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(1), backend = PlatformTestBackend.client).map { client =>
//                     // Start and abandon a stream (scope closes, connection should be released)
//                     Scope.run {
//                         client.stream(HttpRequest.get(s"http://localhost:$port/data")).unit
//                     }.andThen {
//                         // Pool should still work with maxConnectionsPerHost=1
//                         client.send(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                         }
//                     }
//                 }
//             }
//         }
//
//         "partially consumed stream does not block connection pool" in run {
//             val handler = HttpHandler.get("/data") { _ => HttpResponse.ok("streamed-body-content") }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(1), backend = PlatformTestBackend.client).map { client =>
//                     // Start stream, read partially, then exit scope
//                     Scope.run {
//                         client.stream(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
//                             assert(response.status == HttpStatus.OK)
//                             // Don't consume bodyStream — just check headers and exit
//                         }
//                     }.andThen {
//                         // Subsequent buffered request should work
//                         client.send(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
//                             assertStatus(response, HttpStatus.OK)
//                             assertBodyContains(response, "streamed-body-content")
//                         }
//                     }
//                 }
//             }
//         }
//     }
//
//     "Connection pool error recovery" - {
//
//         "pool works after receiving error responses" in run {
//             var count = 0
//             val handler = HttpHandler.get("/maybe") { _ =>
//                 count += 1
//                 if count <= 3 then HttpResponse.serverError("fail")
//                 else HttpResponse.ok("recovered")
//             }
//             startTestServer(handler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(2), backend = PlatformTestBackend.client).map { client =>
//                     // First 3 requests get 500
//                     Kyo.foreach(1 to 3) { _ =>
//                         client.send(HttpRequest.get(s"http://localhost:$port/maybe")).map { r =>
//                             assertStatus(r, HttpStatus.InternalServerError)
//                         }
//                     }.andThen {
//                         // Pool should still work after error responses
//                         client.send(HttpRequest.get(s"http://localhost:$port/maybe")).map { r =>
//                             assertStatus(r, HttpStatus.OK)
//                             assertBodyText(r, "recovered")
//                         }
//                     }
//                 }
//             }
//         }
//
//         "pool works after request timeout" in run {
//             val handler = HttpHandler.get("/test") { _ =>
//                 HttpResponse.ok("ok")
//             }
//             val slowHandler = neverRespondHandler("/slow")
//             startTestServer(handler, slowHandler).map { port =>
//                 HttpClient.init(maxConnectionsPerHost = Present(2), backend = PlatformTestBackend.client).map { client =>
//                     // Timed out request
//                     HttpClient.withConfig(_.timeout(50.millis)) {
//                         Abort.run(client.send(HttpRequest.get(s"http://localhost:$port/slow")))
//                     }.andThen {
//                         // Pool should still work after timeout
//                         client.send(HttpRequest.get(s"http://localhost:$port/test")).map { r =>
//                             assertStatus(r, HttpStatus.OK)
//                             assertBodyText(r, "ok")
//                         }
//                     }
//                 }
//             }
//         }
//     }
//
//     "Streaming" - {
//
//         case class Item(name: String) derives Schema, CanEqual
//
//         "stream" - {
//             "basic streaming response" in run {
//                 val handler = HttpHandler.get("/data") { _ =>
//                     HttpResponse.ok("streamed-body")
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.stream(s"http://localhost:$port/data").map { response =>
//                         assert(response.status == HttpStatus.OK)
//                         response.bodyStream.run.map { chunks =>
//                             val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                             assert(body.contains("streamed-body"))
//                         }
//                     }
//                 }
//             }
//
//             "error status" in run {
//                 val handler = HttpHandler.const(HttpRequest.Method.GET, "/missing", HttpStatus.NotFound)
//                 startTestServer(handler).map { port =>
//                     HttpClient.stream(s"http://localhost:$port/missing").map { response =>
//                         assert(response.status == HttpStatus.NotFound)
//                     }
//                 }
//             }
//
//             "with streaming request body" in run {
//                 val handler = HttpHandler.streamingBody(HttpRequest.Method.POST, "/upload") { in =>
//                     in.request.bodyStream.run.map { chunks =>
//                         val totalBytes = chunks.foldLeft(0)(_ + _.size)
//                         HttpResponse.ok(s"received $totalBytes bytes")
//                     }
//                 }
//                 startTestServer(handler).map { port =>
//                     val bodyStream = Stream.init(Seq(Span.fromUnsafe("hello".getBytes("UTF-8"))))
//                     val request = HttpRequest.stream(
//                         HttpRequest.Method.POST,
//                         s"http://localhost:$port/upload",
//                         bodyStream
//                     )
//                     HttpClient.stream(request).map { response =>
//                         assert(response.status == HttpStatus.OK)
//                         response.bodyStream.run.map { chunks =>
//                             val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                             assert(body.contains("received 5 bytes"))
//                         }
//                     }
//                 }
//             }
//
//             "with HttpRequest object" in run {
//                 val handler = HttpHandler.get("/echo") { in =>
//                     in.request.header("X-Custom") match
//                         case Present(v) => HttpResponse.ok(v)
//                         case Absent     => HttpResponse.ok("no-header")
//                 }
//                 startTestServer(handler).map { port =>
//                     val request = HttpRequest.get(s"http://localhost:$port/echo", HttpHeaders.empty.add("X-Custom", "test-value"))
//                     HttpClient.stream(request).map { response =>
//                         assert(response.status == HttpStatus.OK)
//                         response.bodyStream.run.map { chunks =>
//                             val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                             assert(body.contains("test-value"))
//                         }
//                     }
//                 }
//             }
//         }
//
//         "streamSse" - {
//             "receives SSE events" in run {
//                 val handler = HttpHandler.streamSse[Item]("/events") { _ =>
//                     Stream.init(Seq(
//                         HttpEvent(Item("a")),
//                         HttpEvent(Item("b")),
//                         HttpEvent(Item("c"))
//                     ))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamSse[Item](s"http://localhost:$port/events").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.size == 3)
//                             assert(chunk(0).data == Item("a"))
//                             assert(chunk(1).data == Item("b"))
//                             assert(chunk(2).data == Item("c"))
//                         }
//                     }
//                 }
//             }
//
//             "empty stream" in run {
//                 val handler = HttpHandler.streamSse[Item]("/empty") { _ =>
//                     Stream.empty[HttpEvent[Item]]
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamSse[Item](s"http://localhost:$port/empty").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.isEmpty)
//                         }
//                     }
//                 }
//             }
//
//             "with URL" in run {
//                 val handler = HttpHandler.streamSse[Item]("/events") { _ =>
//                     Stream.init(Seq(HttpEvent(Item("x"))))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamSse[Item](s"http://localhost:$port/events").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.size == 1)
//                             assert(chunk(0).data == Item("x"))
//                         }
//                     }
//                 }
//             }
//         }
//
//         "streamNdjson" - {
//             "receives NDJSON values" in run {
//                 val handler = HttpHandler.streamNdjson[Item]("/data") { _ =>
//                     Stream.init(Seq(Item("x"), Item("y"), Item("z")))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamNdjson[Item](s"http://localhost:$port/data").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.size == 3)
//                             assert(chunk(0) == Item("x"))
//                             assert(chunk(1) == Item("y"))
//                             assert(chunk(2) == Item("z"))
//                         }
//                     }
//                 }
//             }
//
//             "empty stream" in run {
//                 val handler = HttpHandler.streamNdjson[Item]("/empty") { _ =>
//                     Stream.empty[Item]
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamNdjson[Item](s"http://localhost:$port/empty").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.isEmpty)
//                         }
//                     }
//                 }
//             }
//
//             "with URL" in run {
//                 val handler = HttpHandler.streamNdjson[Item]("/data") { _ =>
//                     Stream.init(Seq(Item("q")))
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpClient.streamNdjson[Item](s"http://localhost:$port/data").map { stream =>
//                         stream.run.map { chunk =>
//                             assert(chunk.size == 1)
//                             assert(chunk(0) == Item("q"))
//                         }
//                     }
//                 }
//             }
//         }
//
//         "filter application to streaming" - {
//             "applies filter to stream request" in run {
//                 val handler = HttpHandler.get("/auth") { in =>
//                     in.request.header("Authorization") match
//                         case Present(auth) => HttpResponse.ok(auth)
//                         case Absent        => HttpResponse.unauthorized
//                 }
//                 startTestServer(handler).map { port =>
//                     HttpFilter.client.bearerAuth("my-token").enable {
//                         HttpClient.stream(s"http://localhost:$port/auth").map { response =>
//                             assert(response.status == HttpStatus.OK)
//                             response.bodyStream.run.map { chunks =>
//                                 val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                                 assert(body.contains("Bearer my-token"))
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//     }
//
//     "Stream redirect following" - {
//
//         "follows 301 redirect" in run {
//             val target = HttpHandler.get("/target") { _ =>
//                 HttpResponse.ok("final destination")
//             }
//             val redirect = HttpHandler.get("/redirect") { _ =>
//                 HttpResponse(HttpStatus.MovedPermanently).setHeader("Location", "/target")
//             }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.stream(s"http://localhost:$port/redirect").map { response =>
//                     assert(response.status == HttpStatus.OK)
//                     response.bodyStream.run.map { chunks =>
//                         val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                         assert(body == "final destination")
//                     }
//                 }
//             }
//         }
//
//         "follows 302 redirect" in run {
//             val target = HttpHandler.get("/target") { _ =>
//                 HttpResponse.ok("found here")
//             }
//             val redirect = HttpHandler.get("/redirect") { _ =>
//                 HttpResponse(HttpStatus.Found).setHeader("Location", "/target")
//             }
//             startTestServer(target, redirect).map { port =>
//                 HttpClient.stream(s"http://localhost:$port/redirect").map { response =>
//                     assert(response.status == HttpStatus.OK)
//                     response.bodyStream.run.map { chunks =>
//                         val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
//                         assert(body == "found here")
//                     }
//                 }
//             }
//         }
//
//         "does not follow 307 redirect" in run {
//             val redirect = HttpHandler.get("/redirect") { _ =>
//                 HttpResponse(HttpStatus.TemporaryRedirect).setHeader("Location", "/target")
//             }
//             startTestServer(redirect).map { port =>
//                 HttpClient.stream(s"http://localhost:$port/redirect").map { response =>
//                     assert(response.status == HttpStatus.TemporaryRedirect)
//                 }
//             }
//         }
//     }
//
// end HttpClientTest
