package igsl.com.jira.workflow.jira;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.domain.WorkflowGraph;
import java.util.*;

/** Reads the actual 9.12 workflow descriptor, never layout coordinates. */
public final class JiraGraphReader {
    private JiraGraphReader() { }

    public static WorkflowGraph read(JiraWorkflow workflow) {
        return read(workflow.getDescriptor(), workflow::getLinkedStatusId);
    }

    public static WorkflowGraph read(WorkflowDescriptor descriptor) {
        return read(descriptor, step -> (String) step.getMetaAttributes().get(JiraWorkflow.STEP_STATUS_KEY));
    }

    private static WorkflowGraph read(WorkflowDescriptor descriptor, java.util.function.Function<StepDescriptor, String> statusId) {
        if (!descriptor.getSplits().isEmpty() || !descriptor.getJoins().isEmpty()) {
            throw new IllegalArgumentException("Split/join descriptors require manual path validation");
        }
        Map<Integer, String> statuses = new LinkedHashMap<>();
        for (Object value : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) value;
            String status = statusId.apply(step);
            if (status == null || statuses.containsValue(status)) {
                throw new IllegalArgumentException("Missing or ambiguous Jira status mapping at step " + step.getId());
            }
            statuses.put(step.getId(), status);
        }
        List<WorkflowGraph.Edge> edges = new ArrayList<>();
        for (Object value : descriptor.getInitialActions()) {
            add(edges, WorkflowGraph.INITIAL, (ActionDescriptor) value, statuses);
        }
        for (Object value : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) value;
            String source = statuses.get(step.getId());
            Set<Integer> added = new HashSet<>();
            for (Object valueAction : step.getActions()) {
                ActionDescriptor action = (ActionDescriptor) valueAction;
                add(edges, source, action, statuses); added.add(action.getId());
            }
            for (Object commonId : step.getCommonActions()) {
                ActionDescriptor action = (ActionDescriptor) descriptor.getCommonActions().get(Integer.valueOf(commonId.toString()));
                if (action == null) throw new IllegalArgumentException("Missing common transition " + commonId);
                if (added.add(action.getId())) add(edges, source, action, statuses);
            }
            for (Object action : descriptor.getGlobalActions()) add(edges, source, (ActionDescriptor) action, statuses);
        }
        return new WorkflowGraph(new LinkedHashSet<>(statuses.values()), edges);
    }

    private static void add(List<WorkflowGraph.Edge> edges, String source, ActionDescriptor action,
                            Map<Integer, String> statuses) {
        List<ResultDescriptor> results = new ArrayList<>();
        if (action.getUnconditionalResult() != null) results.add(action.getUnconditionalResult());
        for (Object result : action.getConditionalResults()) results.add((ResultDescriptor) result);
        if (results.isEmpty()) throw new IllegalArgumentException("Transition has no result: " + action.getId());
        int resultIndex = 0;
        for (ResultDescriptor result : results) {
            String destination = result.getStep() == -1 ? source : statuses.get(result.getStep());
            if (destination == null || result.getSplit() > 0 || result.getJoin() > 0) {
                throw new IllegalArgumentException("Unsupported transition result: " + action.getId());
            }
            edges.add(new WorkflowGraph.Edge(action.getId() + ":" + resultIndex++, source, destination));
        }
    }
}
