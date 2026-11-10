package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class UiTestShardTest {
    @Test
    void everyRobotMethodBelongsToExactlyOneNonemptyShard() {
        assertTrue(Arrays.asList(WorkbenchUiTest.class
                        .getAnnotation(org.junit.jupiter.api.extension.ExtendWith.class)
                        .value())
                .contains(UiTestShard.class));
        var methods = Arrays.stream(WorkbenchUiTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(method -> method.getName())
                .toList();
        assertFalse(methods.isEmpty());
        for (int count : new int[] {4, 5}) {
            var assigned = new HashSet<String>();
            int minimum = Integer.MAX_VALUE, maximum = 0;
            for (int shard = 1; shard <= count; shard++) {
                int selectedShard = shard;
                var selected = methods.stream()
                        .filter(name -> UiTestShard.shardFor(name, count) == selectedShard)
                        .toList();
                assertFalse(selected.isEmpty(), "Empty shard " + shard + "/" + count);
                minimum = Math.min(minimum, selected.size());
                maximum = Math.max(maximum, selected.size());
                for (String name : selected) assertTrue(assigned.add(name), "Duplicate test " + name);
            }
            assertEquals(new HashSet<>(methods), assigned);
            assertTrue(maximum - minimum <= 1, "Shard sizes must differ by at most one test");
        }
        for (String name : methods) assertEquals(1, UiTestShard.shardFor(name, 1));
        assertThrows(IllegalArgumentException.class, () -> UiTestShard.shardFor("test", 0));
        assertThrows(IllegalArgumentException.class, () -> UiTestShard.shardFor("missingTest", 5));
    }
}
