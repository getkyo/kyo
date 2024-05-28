package kyo

object HelloWorldServer extends KyoApp:
  // Use 'run' blocks to execute Kyo computations.
  // The execution of the run block is lazy to avoid
  // field initialization issues.
  run {
    for {
      _ <- Consoles.println(s"Main args: $args")
      currentTime <- Clocks.now
      _ <- Consoles.println(s"Current time is: $currentTime")
      randomNumber <- Randoms.nextInt(100)
      _ <- Consoles.println(s"Generated random number: $randomNumber")
    } yield {
      // The produced value can be of any type and is
      // automatically printed to the console.
      "example"
    }
  }
