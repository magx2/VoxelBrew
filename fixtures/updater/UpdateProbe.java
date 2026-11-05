import dev.voxelbrew.engine.update.*;
import java.nio.file.*;

/** Fresh-process package acceptance: uses the installed engine and its bundled JVM. */
public final class UpdateProbe {
    private UpdateProbe() {}

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        var component = ApplicationImage.Component.valueOf(args[0]);
        try (var running = ApplicationImage.running(component)) {
            if (args[1].equals("hold")) {
                Files.writeString(Path.of(args[2]), "LOCKED");
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
                while (!Files.exists(Path.of(args[3]))) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("Holder timed out");
                    Thread.sleep(100);
                }
            } else {
                var service = UpdateService.system(component);
                var cancellation = new UpdateCancellation();
                var selected = service.check(new UpdateService.Request(null, false), cancellation);
                try (var prepared = service.prepare(selected, cancellation, System.out::println)) {
                    if (args[1].equals("cancel")) {
                        System.out.println("Cancelled prepared update: " + prepared.statusFile());
                        return;
                    }
                    prepared.handOff();
                    System.out.println("Status: " + prepared.statusFile());
                }
            }
        }
    }
}
