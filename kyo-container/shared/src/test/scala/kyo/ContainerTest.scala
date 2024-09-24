// package kyo

// import kyo.debug.Debug

// class ContainerTest extends Test:

//     "mocked" - {
//         class MockService extends Container.Service:
//             var createCalls: List[Container.Config]       = Nil
//             var startCalls: List[String]                  = Nil
//             var stopCalls: List[String]                   = Nil
//             var stateCalls: List[String]                  = Nil
//             var executeCalls: List[(String, String)]      = Nil
//             var copyToCalls: List[(String, Path, Path)]   = Nil
//             var copyFromCalls: List[(String, Path, Path)] = Nil
//             var logsCalls: List[String]                   = Nil

//             def create(config: Container.Config)(using Frame) =
//                 createCalls = config :: createCalls
//                 "mock-container-id"

//             def start(id: String)(using Frame) =
//                 startCalls = id :: startCalls

//             def stop(id: String)(using Frame) =
//                 stopCalls = id :: stopCalls

//             def state(id: String)(using Frame) =
//                 stateCalls = id :: stateCalls
//                 Container.State.Running

//             def execute(id: String, command: String)(using Frame) =
//                 executeCalls = (id, command) :: executeCalls
//                 if command == "healthcheck" then "healthy"
//                 else "mock-execution-output"
//             end execute

//             def copyTo(id: String, source: Path, destination: Path)(using Frame) =
//                 copyToCalls = (id, source, destination) :: copyToCalls

//             def copyFrom(id: String, source: Path, destination: Path)(using Frame) =
//                 copyFromCalls = (id, source, destination) :: copyFromCalls

//             def logs(id: String)(using Frame) =
//                 logsCalls = id :: logsCalls
//                 "mock-logs-output"

//         end MockService

//         "init creates and starts a container" in run {
//             val mockService = new MockService
//             val config      = Container.Config("test-image")

//             Container.Service.let(mockService) {
//                 Container.init(config)
//             }.map { container =>
//                 assert(container.id == "mock-container-id")
//                 assert(mockService.createCalls == List(config))
//                 assert(mockService.startCalls == List("mock-container-id"))
//             }
//         }

//         "state returns the container state" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             Container.Service.let(mockService) {
//                 container.state
//             }.map { state =>
//                 assert(state == Container.State.Running)
//                 assert(mockService.stateCalls == List("test-id"))
//             }
//         }

//         "stop stops the container" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             Container.Service.let(mockService) {
//                 container.stop
//             }.map { _ =>
//                 assert(mockService.stopCalls == List("test-id"))
//             }
//         }

//         "execute runs a command in the container" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             Container.Service.let(mockService) {
//                 container.execute("test-command")
//             }.map { output =>
//                 assert(output == "mock-execution-output")
//                 assert(mockService.executeCalls == List(("test-id", "test-command")))
//             }
//         }

//         "copyTo copies files to the container" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             val sourcePath  = Path("/source")
//             val destPath    = Path("/dest")
//             Container.Service.let(mockService) {
//                 container.copyTo(sourcePath, destPath)
//             }.map { _ =>
//                 assert(mockService.copyToCalls == List(("test-id", sourcePath, destPath)))
//             }
//         }

//         "copyFrom copies files from the container" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             val sourcePath  = Path("/source")
//             val destPath    = Path("/dest")
//             Container.Service.let(mockService) {
//                 container.copyFrom(sourcePath, destPath)
//             }.map { _ =>
//                 assert(mockService.copyFromCalls == List(("test-id", sourcePath, destPath)))
//             }
//         }

//         "logs returns container logs" in run {
//             val mockService = new MockService
//             val container   = Container("test-id")
//             Container.Service.let(mockService) {
//                 container.logs
//             }.map { logs =>
//                 assert(logs == "mock-logs-output")
//                 assert(mockService.logsCalls == List("test-id"))
//             }
//         }
//     }

//     "live" - {
//         "test" in run {
//             Container.init("test").map(_ => succeed)
//         }
//     }
// end ContainerTest
