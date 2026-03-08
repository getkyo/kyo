package demo

import kyo.*
import scala.language.implicitConversions

object JavaFxExperimentApp extends KyoApp with UIScope:

    run {
        for
            ui      <- ExperimentUI.ui
            session <- new JavaFxBackend(title = "Kyo UI Experiment", width = 800, height = 600).render(ui)
            _       <- session.await
        yield ()
    }
end JavaFxExperimentApp
