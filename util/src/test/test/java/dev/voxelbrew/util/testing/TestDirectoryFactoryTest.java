package dev.voxelbrew.util.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class TestDirectoryFactoryTest {
    private static final List<Path> paths = CleanupExample.paths;

    @Test
    void junitCleansBothSuccessfulAndFailedTestsWithoutChangingTheirResults() {
        paths.clear();
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(CleanupExample.class))
                .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
                .configurationParameter(
                        "junit.jupiter.tempdir.factory.default",
                        TestDirectoryFactory.class.getName())
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(1));
        assertEquals(2, paths.size());
        for (Path path : paths) assertFalse(Files.exists(path), path.toString());
    }

    @Test
    void deletionFailureLogsErrorAndDoesNotChangeEitherTestResult() {
        paths.clear();
        var records = new ArrayList<LogRecord>();
        Logger logger = Logger.getLogger(TestDirectoryFactory.class.getName());
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        logger.addHandler(handler);
        DeniedFactory.deny = true;
        try {
            EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(CleanupExample.class))
                    .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
                    .configurationParameter(
                            "junit.jupiter.tempdir.factory.default", DeniedFactory.class.getName())
                    .execute()
                    .testEvents()
                    .assertStatistics(stats -> stats.succeeded(1).failed(1));
            assertEquals(2, paths.size());
            assertTrue(paths.stream().allMatch(Files::exists));
            assertTrue(
                    records.stream()
                            .anyMatch(
                                    record ->
                                            record.getLevel() == Level.SEVERE
                                                    && record.getThrown() instanceof IOException
                                                    && record.getMessage().contains("locked.txt")));
        } finally {
            DeniedFactory.deny = false;
            DeniedFactory.instances.forEach(TestDirectoryFactory::close);
            DeniedFactory.instances.clear();
            logger.removeHandler(handler);
        }
        for (Path path : paths) assertFalse(Files.exists(path), path.toString());
    }

    public static class DeniedFactory extends TestDirectoryFactory {
        static boolean deny;
        static final List<DeniedFactory> instances = new ArrayList<>();

        public DeniedFactory() {
            instances.add(this);
        }

        @Override
        protected void delete(Path path) throws IOException {
            if (deny && path.getFileName().toString().equals("locked.txt"))
                throw new IOException("Simulated locked test file");
            super.delete(path);
        }
    }
}
