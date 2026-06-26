package kyo

/** Base class for the kyo-threejs test suites: the UITest analog for this module.
  *
  * Node-testable suites (AST, reconciler, transforms, raycast, dispose, animation math) extend this
  * directly and run against real three.js on the Scala.js Node environment. The browser-backed suites
  * (the WebGL acceptance gate, the GL-submit and visual paths) extend it and drive a real Chrome
  * through kyo-browser; for those the `.sequential` config keeps each suite's leaves from launching
  * Chrome processes concurrently under the default leaf parallelism, which otherwise races the CDP
  * connections.
  */
abstract class ThreeTest extends kyo.test.Test[Any]:

    override def timeout = 60.seconds

    override def config = super.config.sequential

end ThreeTest
