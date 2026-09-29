package ut.igsl.com.jira.workflow;

import igsl.com.jira.workflow.domain.WorkflowGraph;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class WorkflowGraphTest {
    private WorkflowGraph graph(String... paths) {
        Set<String> states = new LinkedHashSet<>();
        List<WorkflowGraph.Edge> edges = new ArrayList<>();
        for (String path : paths) {
            String[] pair = path.split(">"); states.add(pair[0]); states.add(pair[1]);
            edges.add(new WorkflowGraph.Edge("t" + edges.size(), pair[0], pair[1]));
        }
        return new WorkflowGraph(states, edges);
    }
    @Test public void acceptsMultipleExtensionStepsAndPreservesRollback() {
        WorkflowGraph.validateInheritance(graph("A>B", "B>C", "B>A"),
                graph("A>X", "X>Y", "Y>B", "B>C", "B>A"));
    }
    @Test public void acceptsExtensionCyclesWithValidExit() {
        WorkflowGraph.validateInheritance(graph("A>B"), graph("A>X", "X>Y", "Y>X", "Y>B"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsShortcutAcrossRequiredState() {
        WorkflowGraph.validateInheritance(graph("A>B", "B>C"), graph("A>B", "B>C", "A>X", "X>C"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsRemovalOfParentBranch() {
        WorkflowGraph.validateInheritance(graph("A>B", "A>C"), graph("A>B", "B>C"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInheritedStateReplacement() {
        WorkflowGraph.validateInheritance(graph("A>B"), graph("A>X"));
    }
    @Test public void terminalOfMultipleExtensionsIsLastSegment() {
        WorkflowGraph child = graph("A>X", "X>Y", "Y>B", "B>C");
        WorkflowGraph.Edge terminal = child.uniqueTerminal("A", "B", Set.of("A", "B", "C"));
        assertEquals("Y", terminal.from); assertEquals("B", terminal.to);
    }
    @Test(expected = IllegalArgumentException.class) public void refusesAmbiguousAppendTarget() {
        graph("A>X", "X>B", "A>Y", "Y>B").uniqueTerminal("A", "B", Set.of("A", "B"));
    }
    @Test(expected = IllegalArgumentException.class) public void refusesParallelDirectTransitions() {
        graph("A>B", "A>B").uniqueTerminal("A", "B", Set.of("A", "B"));
    }
    @Test public void preservesAllowedParentSelfLoop() {
        WorkflowGraph.validateInheritance(graph("A>A", "A>B"), graph("A>X", "X>A", "A>B"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNewParentSelfLoop() {
        WorkflowGraph.validateInheritance(graph("A>B"), graph("A>X", "X>A", "X>B"));
    }
    @Test(expected = IllegalArgumentException.class) public void checksInitialPathCannotSkipParentStart() {
        WorkflowGraph parent = new WorkflowGraph(Set.of("A", "B"), List.of(
                new WorkflowGraph.Edge("create", WorkflowGraph.INITIAL, "A"), new WorkflowGraph.Edge("next", "A", "B")));
        WorkflowGraph child = new WorkflowGraph(Set.of("A", "B"), List.of(
                new WorkflowGraph.Edge("create", WorkflowGraph.INITIAL, "B"), new WorkflowGraph.Edge("next", "A", "B")));
        WorkflowGraph.validateInheritance(parent, child);
    }
}
