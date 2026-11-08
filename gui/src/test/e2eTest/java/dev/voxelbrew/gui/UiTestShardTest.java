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
        var assigned = new HashSet<String>();
        for (int shard = 1; shard <= 4; shard++) {
            int selectedShard = shard;
            var selected = methods.stream()
                    .filter(name -> UiTestShard.shardFor(name, 4) == selectedShard)
                    .toList();
            assertFalse(selected.isEmpty(), "Empty shard " + shard);
            for (String name : selected) assertTrue(assigned.add(name), "Duplicate test " + name);
        }
        assertEquals(new HashSet<>(methods), assigned);
        for (String name : methods) assertEquals(1, UiTestShard.shardFor(name, 1));
        assertThrows(IllegalArgumentException.class, () -> UiTestShard.shardFor("test", 0));
    }
}
