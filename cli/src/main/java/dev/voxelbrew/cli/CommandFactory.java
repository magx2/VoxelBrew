package dev.voxelbrew.cli;

import java.util.Map;
import javax.inject.Inject;
import javax.inject.Provider;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

/** Picocli's construction boundary; business commands never look up services. */
@javax.inject.Singleton
@RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @Inject)
final class CommandFactory implements CommandLine.IFactory {
    private final Map<Class<?>, Provider<Object>> commands;

    @Override
    public <K> K create(Class<K> type) throws Exception {
        var provider = commands.get(type);
        if (provider != null) return type.cast(provider.get());
        if (type.isAnnotationPresent(CommandLine.Command.class)
                && type.getPackageName().equals(VoxelBrew.class.getPackageName()))
            throw new IllegalArgumentException("Missing Dagger command binding: " + type.getName());
        return CommandLine.defaultFactory().create(type);
    }
}
