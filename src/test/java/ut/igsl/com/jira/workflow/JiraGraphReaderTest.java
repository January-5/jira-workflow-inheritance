package ut.igsl.com.jira.workflow;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.domain.WorkflowGraph;
import igsl.com.jira.workflow.jira.JiraGraphReader;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class JiraGraphReaderTest {
    static final DescriptorFactory FACTORY = DescriptorFactory.getFactory();
    static WorkflowDescriptor descriptor() {
        WorkflowDescriptor descriptor = FACTORY.createWorkflowDescriptor();
        for (int i = 1; i <= 3; i++) {
            StepDescriptor step = FACTORY.createStepDescriptor(); step.setId(i); step.setName("Status " + i);
            step.getMetaAttributes().put(JiraWorkflow.STEP_STATUS_KEY, "s" + i);
            step.setParent(descriptor); descriptor.addStep(step);
        }
        return descriptor;
    }
    static ActionDescriptor action(int id, int target) {
        ActionDescriptor action = FACTORY.createActionDescriptor(); action.setId(id); action.setName("Transition " + id);
        ResultDescriptor result = FACTORY.createResultDescriptor(); result.setStep(target); result.setStatus("done");
        action.setUnconditionalResult(result); return action;
    }
    static JiraWorkflow workflow(WorkflowDescriptor descriptor) {
        return (JiraWorkflow) Proxy.newProxyInstance(JiraWorkflow.class.getClassLoader(), new Class<?>[]{JiraWorkflow.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getDescriptor")) return descriptor;
                if (method.getName().equals("getLinkedStatusId")) return "s" + ((StepDescriptor) args[0]).getId();
                throw new UnsupportedOperationException(method.getName());
            });
    }
    @Test public void readsInitialOrdinaryCommonAndGlobalActions() {
        WorkflowDescriptor descriptor = descriptor();
        descriptor.addInitialAction(action(1, 1));
        descriptor.getStep(1).getActions().add(action(2, 2));
        descriptor.addCommonAction(action(3, 3));
        descriptor.getStep(2).getCommonActions().add("3");
        descriptor.addGlobalAction(action(4, 1));
        WorkflowGraph graph = JiraGraphReader.read(workflow(descriptor));
        assertEquals(Set.of("s1"), graph.nextInherited(WorkflowGraph.INITIAL, graph.states()));
        assertEquals(Set.of("s1", "s2"), graph.nextInherited("s1", graph.states()));
        assertEquals(Set.of("s1", "s3"), graph.nextInherited("s2", graph.states()));
        assertEquals(Set.of("s1"), graph.nextInherited("s3", graph.states()));
    }
    @Test public void readsConditionalAndSameStateResults() {
        WorkflowDescriptor descriptor = descriptor();
        ActionDescriptor action = action(2, -1);
        ConditionalResultDescriptor result = FACTORY.createConditionalResultDescriptor(); result.setStep(3);
        action.getConditionalResults().add(result); descriptor.getStep(1).getActions().add(action);
        WorkflowGraph graph = JiraGraphReader.read(workflow(descriptor));
        assertEquals(Set.of("s1", "s3"), graph.nextInherited("s1", graph.states()));
    }
    @Test public void commonActionExpandedByNativeLoaderIsNotDuplicated() {
        WorkflowDescriptor descriptor = descriptor();
        ActionDescriptor common = action(10, 2); descriptor.addCommonAction(common);
        descriptor.getStep(1).getCommonActions().add(10);
        descriptor.getStep(1).getActions().add(common);
        WorkflowGraph graph = JiraGraphReader.read(workflow(descriptor));
        assertEquals(1, graph.outgoing("s1").size());
    }
    @Test(expected = IllegalArgumentException.class) public void refusesUnmappedTarget() {
        WorkflowDescriptor descriptor = descriptor(); descriptor.getStep(1).getActions().add(action(2, 99));
        JiraGraphReader.read(workflow(descriptor));
    }
}
