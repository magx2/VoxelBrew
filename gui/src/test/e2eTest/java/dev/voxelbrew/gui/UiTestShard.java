package dev.voxelbrew.gui;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Partition Robot methods across isolated CI desktops, never threads on the same desktop. */
final class UiTestShard implements ExecutionCondition {
    static int shardFor(String methodName, int count) {
        if (count < 1) throw new IllegalArgumentException("Shard count must be positive");
        return Math.floorMod(methodName.hashCode(), count) + 1;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        int count = Integer.parseInt(System.getProperty("voxelbrew.test.shardCount", "1"));
        int shard = Integer.parseInt(System.getProperty("voxelbrew.test.shard", "1"));
        if (count < 1 || shard < 1 || shard > count) {
            throw new IllegalArgumentException("Invalid GUI test shard " + shard + "/" + count);
        }
        if (context.getTestMethod().isEmpty()
                || shardFor(context.getRequiredTestMethod().getName(), count) == shard) {
            return ConditionEvaluationResult.enabled("GUI shard " + shard + "/" + count);
        }
        return ConditionEvaluationResult.disabled("Runs on another GUI shard");
    }
}
