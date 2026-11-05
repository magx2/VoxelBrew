package dev.voxelbrew.util.di;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.*;
import java.util.Objects;
import javax.inject.Singleton;

/** Runtime inputs and shared application services; one instance per application graph. */
@Module
public final class ApplicationModule {
    private final UserConfiguration configuration;
    private final UpdateServiceFactory updates;

    public ApplicationModule(UserConfiguration configuration, UpdateServiceFactory updates) {
        this.configuration = Objects.requireNonNull(configuration);
        this.updates = Objects.requireNonNull(updates);
    }

    public static ApplicationModule system(ApplicationImage.Component component) {
        return new ApplicationModule(
                UserConfiguration.user(), () -> UpdateService.system(component));
    }

    @Provides
    @Singleton
    UserConfiguration configuration() {
        return configuration;
    }

    @Provides
    @Singleton
    static Translations translations(UserConfiguration configuration) {
        return Translations.configured(configuration);
    }

    @Provides
    @Singleton
    UpdateServiceFactory updates() {
        return updates;
    }
}
