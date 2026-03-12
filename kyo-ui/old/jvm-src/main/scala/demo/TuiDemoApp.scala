package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

object TuiDemoApp extends KyoApp:

    run {
        for
            ui      <- DemoUI.build
            session <- TuiBackend.render(ui)
            _       <- session.await
        yield ()
    }
end TuiDemoApp
