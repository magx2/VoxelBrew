package dev.voxelbrew.printer.api;

import java.util.OptionalDouble;

public record PrinterStatus(
        State state,
        String filename,
        OptionalDouble progress,
        OptionalDouble nozzle,
        OptionalDouble bed,
        OptionalDouble remainingSeconds,
        String detail) {
    public enum State {
        OFFLINE,
        UNKNOWN,
        IDLE,
        PRINTING,
        PAUSED,
        COMPLETE,
        ERROR
    }

    public static PrinterStatus unknown(String detail) {
        return new PrinterStatus(
                State.UNKNOWN,
                "",
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                detail);
    }

    public boolean canStart() {
        return state == State.IDLE || state == State.COMPLETE;
    }
}
