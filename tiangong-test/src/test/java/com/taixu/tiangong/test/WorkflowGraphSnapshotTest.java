package com.taixu.tiangong.test;

import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.agent.runtime.WorkflowGraphSnapshot;
import com.taixu.tiangong.test.node.LinearWorkflow;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphSnapshotTest {

    @Test
    void snapshotIsStableAndDetectsDrift() throws Exception {
        AgentNodeRegistry registry = TestHarness.registryFor(new LinearWorkflow());
        String snapshot = WorkflowGraphSnapshot.snapshot(registry);

        assertTrue(snapshot.startsWith("# tiangong-workflow-graph v1"));
        assertTrue(snapshot.contains("node linear-start|START|"));
        assertTrue(snapshot.contains("node linear-step1|COMMON|"));
        assertTrue(snapshot.contains("start linear-start"));
        assertTrue(snapshot.contains("end 1"));

        Path golden = Files.createTempFile("tiangong-graph-", ".snapshot");
        try {
            WorkflowGraphSnapshot.write(registry, golden);
            WorkflowGraphSnapshot.assertMatches(registry, golden);

            Files.writeString(golden, snapshot.replace("linear-step1", "linear-step1-renamed"));
            assertThrows(AssertionError.class,
                    () -> WorkflowGraphSnapshot.assertMatches(registry, golden));
        } finally {
            Files.deleteIfExists(golden);
        }
    }
}
