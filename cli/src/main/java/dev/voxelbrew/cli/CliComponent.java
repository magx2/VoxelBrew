package dev.voxelbrew.cli;

import dagger.Component;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.i18n.Translations;
import javax.inject.Singleton;
import picocli.CommandLine;

@Singleton
@Component(modules = {ApplicationModule.class, FileIoModule.class, CommandBindings.class})
interface CliComponent {
    CommandFactory commands();

    Translations translations();

    default CommandLine commandLine() {
        return VoxelBrew.commandLine(commands(), translations());
    }

    @Component.Factory
    interface Factory {
        CliComponent create(ApplicationModule application, FileIoModule files);
    }
}
