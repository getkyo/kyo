package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** Prints the path of the chrome-headless-shell executable for the version given as the only argument, downloading it first when it is not
  * cached.
  *
  * `linkCheck` serves a program's bundled page to this Chrome and records what the page fetches. It takes the executable from kyo-browser, so
  * the check runs the same Chrome as the browser test rows, from the same cache.
  */
object ChromeExecutable extends KyoApp:
    run {
        args.headMaybe match
            case Present(version) =>
                Browser.chromeForTestingLaunchConfig(version = Present(version)).map(launch => Console.printLine(launch.executable))
            case Absent =>
                Abort.fail(new IllegalArgumentException("usage: ChromeExecutable <chrome-headless-shell version>"))
    }
end ChromeExecutable
