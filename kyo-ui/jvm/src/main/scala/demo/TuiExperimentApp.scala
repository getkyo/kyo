package demo

import kyo.*
import scala.language.implicitConversions

object TuiExperimentApp extends KyoApp with UIScope:

    run {
        for
            ui      <- ExperimentUI.ui
            session <- TuiBackend.render(ui)
            _       <- session.await
        yield ()
    }
end TuiExperimentApp
