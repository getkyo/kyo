package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

object JavaFxExperimentApp extends KyoApp:

    run {
        for
            ui      <- ExperimentUI.ui
            session <- new JavaFxBackend(title = "Kyo UI Experiment", width = 800, height = 600).render(ui)
            _       <- session.await
        yield ()
    }
end JavaFxExperimentApp
