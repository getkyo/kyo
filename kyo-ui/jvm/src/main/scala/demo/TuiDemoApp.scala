package demo

import kyo.*
import scala.language.implicitConversions

object TuiDemoApp extends KyoApp with UIScope:

    run {
        for
            ui      <- DemoUI.build
            session <- TuiBackend.render(ui)
            _       <- session.await
        yield ()
    }
end TuiDemoApp
