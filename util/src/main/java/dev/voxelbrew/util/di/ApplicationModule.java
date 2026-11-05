package dev.voxelbrew.util.di;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.*;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Runtime inputs and shared application services; one instance per application graph. */
@Module
@RequiredArgsConstructor
public final class ApplicationModule {
    @NonNull private final UserConfiguration configuration;
    @NonNull private final UpdateServiceFactory updates;

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
