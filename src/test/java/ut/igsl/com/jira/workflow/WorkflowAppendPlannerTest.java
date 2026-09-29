package ut.igsl.com.jira.workflow;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.jira.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class WorkflowAppendPlannerTest {
    private static final DescriptorFactory F = DescriptorFactory.getFactory();
    private WorkflowDescriptor workflow(String... statuses) {
        WorkflowDescriptor d = F.createWorkflowDescriptor();
        for (int i = 0; i < statuses.length; i++) {
            StepDescriptor step = F.createStepDescriptor(); step.setId(i + 1); step.setName(statuses[i]);
            step.getMetaAttributes().put(JiraWorkflow.STEP_STATUS_KEY, statuses[i]); d.addStep(step);
        }
        d.addInitialAction(JiraGraphReaderTest.action(1, 1)); return d;
    }
    private void edge(WorkflowDescriptor d, int from, int to, int id) { d.getStep(from).getActions().add(JiraGraphReaderTest.action(id, to)); }
    private WorkflowDescriptor parent() { WorkflowDescriptor d = workflow("A", "B"); edge(d, 1, 2, 10); return d; }
    private WorkflowDescriptor child() {
        WorkflowDescriptor d = workflow("A", "B", "X", "Y"); edge(d, 1, 3, 10); edge(d, 3, 4, 20); edge(d, 4, 2, 30); return d;
    }
    private WorkflowDescriptor inserted() {
        WorkflowDescriptor d = workflow("A", "B", "D"); edge(d, 1, 3, 10); edge(d, 3, 2, 20); return d;
    }
    @Test public void insertsBeforeExtensionsRemapsIdsAndStagesWithoutChangingInputs() {
        WorkflowDescriptor original = child(), latest = inserted(); String before = original.asXML();
        original.getStep(4).getAction(30).getMetaAttributes().put("local", "keep"); before = original.asXML();
        latest.getStep(1).getAction(10).getMetaAttributes().put("parent-added", "yes");
        WorkflowAppendPlanner.Plan plan = WorkflowAppendPlanner.plan(parent(), latest, original, Map.of("X", "A", "Y", "A"));
        assertEquals(before, original.asXML());
        assertEquals(Map.of("X", "D", "Y", "D"), plan.stages());
        var graph = JiraGraphReader.read(plan.descriptor());
        assertEquals("D", graph.outgoing("A").get(0).to);
        assertEquals("X", graph.outgoing("D").get(0).to);
        assertEquals("Y", graph.outgoing("X").get(0).to);
        assertEquals("B", graph.outgoing("Y").get(0).to);
        var terminal = plan.descriptor().getStep(4).getAction(30);
        assertEquals("keep", terminal.getMetaAttributes().get("local"));
        assertEquals("yes", terminal.getMetaAttributes().get("parent-added"));
        assertNull(plan.descriptor().getStep(1).getAction(10).getMetaAttributes().get("parent-added"));
    }
    @Test public void configurationOnlyAppendIsIdempotent() {
        WorkflowDescriptor latest = parent(); latest.getStep(1).getAction(10).getMetaAttributes().put("added", "yes");
        var once = WorkflowAppendPlanner.plan(parent(), latest, child(), Map.of("X", "A", "Y", "A"));
        var twice = WorkflowAppendPlanner.plan(parent(), latest, once.descriptor(), once.stages());
        assertEquals(once.descriptor().asXML(), twice.descriptor().asXML());
    }
    @Test public void directChildReceivesInsertedState() {
        var plan = WorkflowAppendPlanner.plan(parent(), inserted(), parent(), Map.of());
        assertEquals("D", JiraGraphReader.read(plan.descriptor()).outgoing("A").get(0).to);
        assertEquals("B", JiraGraphReader.read(plan.descriptor()).outgoing("D").get(0).to);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsAmbiguousParallelPaths() {
        WorkflowDescriptor d = child(); edge(d, 1, 2, 40);
        WorkflowAppendPlanner.plan(parent(), inserted(), d, Map.of("X", "A", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsExplicitDifferentExtensionStage() {
        WorkflowAppendPlanner.plan(parent(), inserted(), child(), Map.of("X", "B", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsDeletingParentState() {
        WorkflowAppendPlanner.plan(parent(), workflow("A"), child(), Map.of("X", "A", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNewParentStateAlreadyUsedByChild() {
        WorkflowDescriptor latest = workflow("A", "B", "X"); edge(latest, 1, 3, 10); edge(latest, 3, 2, 20);
        WorkflowAppendPlanner.plan(parent(), latest, child(), Map.of("X", "A", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void willNotSilentlyDropChangedStateProperties() {
        WorkflowDescriptor latest = parent(); latest.getStep(1).getMetaAttributes().put("jira.permission", "changed");
        WorkflowAppendPlanner.plan(parent(), latest, child(), Map.of("X", "A", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void willNotSilentlyDropChangedWorkflowProperties() {
        WorkflowDescriptor latest = parent(); latest.getMetaAttributes().put("business-property", "changed");
        WorkflowAppendPlanner.plan(parent(), latest, child(), Map.of("X", "A", "Y", "A"));
    }
}
