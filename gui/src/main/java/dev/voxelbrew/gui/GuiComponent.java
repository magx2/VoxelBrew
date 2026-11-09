package dev.voxelbrew.gui;

import dagger.BindsInstance;
import dagger.Component;
import dev.voxelbrew.util.di.ApplicationModule;
import java.nio.file.Path;
import javafx.stage.Stage;
import javax.inject.*;

/**
 * A separate graph for each window: documents and mutable controllers are never process globals.
 */
@Singleton
@Component(modules = {ApplicationModule.class, FileIoModule.class, GuiModule.class})
interface GuiComponent {
    Workbench workbench();

    WindowResources resources();

    @Component.Factory
    interface Factory {
        GuiComponent create(
                @BindsInstance Stage stage,
                @BindsInstance @Named("plugins") Path plugins,
                ApplicationModule application,
                FileIoModule files);
    }
}
