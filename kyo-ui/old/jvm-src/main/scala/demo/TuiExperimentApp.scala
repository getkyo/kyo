package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

object TuiExperimentApp extends KyoApp:

    run {
        for
            ui      <- ExperimentUI.ui
            session <- TuiBackend.render(ui)
            _       <- session.await
        yield ()
    }
end TuiExperimentApp
