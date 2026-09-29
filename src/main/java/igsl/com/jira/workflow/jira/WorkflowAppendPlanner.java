package igsl.com.jira.workflow.jira;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.domain.WorkflowGraph;
import java.util.*;

/** Detached three-way plan for ordinary transitions and unambiguous inserted chains. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class WorkflowAppendPlanner {
    private WorkflowAppendPlanner() { }
    public record Plan(WorkflowDescriptor descriptor, Map<String, String> stages) { }

    public static Plan plan(WorkflowDescriptor previous, WorkflowDescriptor parent, WorkflowDescriptor child,
                            Map<String, String> stages) {
        ordinary(previous); ordinary(parent); ordinary(child);
        unchangedWorkflowConfiguration(previous, parent);
        WorkflowGraph oldGraph = JiraGraphReader.read(previous), parentGraph = JiraGraphReader.read(parent);
        WorkflowGraph.validateInheritance(oldGraph, JiraGraphReader.read(child));
        if (!parentGraph.states().containsAll(oldGraph.states())) throw conflict("Parent states cannot be deleted");
        WorkflowDescriptor result = DescriptorXml.workflow(child);
        Map<String, StepDescriptor> oldSteps = steps(previous), parentSteps = steps(parent), childSteps = steps(result);
        Map<String, String> mappedStages = new LinkedHashMap<>(stages);
        Set<String> inserted = new LinkedHashSet<>(parentSteps.keySet()); inserted.removeAll(oldSteps.keySet());
        for (String state : inserted) if (childSteps.containsKey(state)) throw conflict("Inserted parent status already exists as child extension: " + state);
        Set<String> consumed = new HashSet<>();
        int nextStep = childSteps.values().stream().mapToInt(StepDescriptor::getId).max().orElse(0) + 1;
        int nextAction = actions(result).stream().mapToInt(ActionDescriptor::getId).max().orElse(0) + 1;
        for (String source : oldSteps.keySet()) {
            StepDescriptor oldSource = oldSteps.get(source), newSource = parentSteps.get(source);
            if (!Objects.equals(oldSource.getMetaAttributes(), newSource.getMetaAttributes())
                    || !xml(oldSource.getPermissions()).equals(xml(newSource.getPermissions()))
                    || !xml(oldSource.getPreFunctions()).equals(xml(newSource.getPreFunctions()))
                    || !xml(oldSource.getPostFunctions()).equals(xml(newSource.getPostFunctions())))
                throw conflict("Changed parent state configuration requires explicit merge: " + source);
            if (oldSource.getActions().size() != newSource.getActions().size()) throw conflict("Added/removed parent branches require explicit mapping");
            for (Object value : oldSource.getActions()) {
                ActionDescriptor oldAction = (ActionDescriptor) value;
                ActionDescriptor newAction = newSource.getAction(oldAction.getId());
                if (newAction == null) throw conflict("Parent transition identity changed");
                String destination = target(previous, oldAction, source);
                List<String> chain = new ArrayList<>();
                String cursor = target(parent, newAction, source);
                while (inserted.contains(cursor)) {
                    if (!consumed.add(cursor)) throw conflict("Shared or cyclic inserted chain requires explicit mapping");
                    chain.add(cursor);
                    StepDescriptor node = parentSteps.get(cursor);
                    if (node.getActions().size() != 1) throw conflict("Inserted branch requires explicit mapping");
                    cursor = target(parent, (ActionDescriptor) node.getActions().get(0), cursor);
                }
                if (!destination.equals(cursor)) throw conflict("Parent destination changed rather than inserting states");
                List<ActionDescriptor> path = path(result, source, destination, oldSteps.keySet());
                ActionDescriptor terminal = path.get(path.size() - 1);
                ActionDescriptor appended = ActionAppendPlanner.plan(oldAction, newAction, terminal);
                replace(result, terminal, appended);
                if (path.size() == 1) path.set(0, appended);
                if (chain.isEmpty()) continue;
                ActionDescriptor first = path.get(0);
                int extensionEntry = first.getUnconditionalResult().getStep();
                if (extensionEntry == -1) extensionEntry = childSteps.get(source).getId();
                List<StepDescriptor> copies = new ArrayList<>();
                for (String state : chain) {
                    StepDescriptor copy = DescriptorFactory.getFactory().createStepDescriptor(
                            DescriptorXml.element(parentSteps.get(state).asXML()), result);
                    copy.setId(nextStep++);
                    for (Object action : copy.getActions()) ((ActionDescriptor) action).setId(nextAction++);
                    result.addStep(copy); childSteps.put(state, copy); copies.add(copy);
                }
                first.getUnconditionalResult().setStep(copies.get(0).getId());
                for (int i = 0; i < copies.size(); i++) {
                    ActionDescriptor action = (ActionDescriptor) copies.get(i).getActions().get(0);
                    action.getUnconditionalResult().setStep(i + 1 < copies.size() ? copies.get(i + 1).getId() : extensionEntry);
                }
                String newStage = chain.get(chain.size() - 1);
                for (int i = 1; i < path.size(); i++) {
                    String extension = owner(result, path.get(i).getId());
                    if (!oldSteps.containsKey(extension)) {
                        if (!source.equals(mappedStages.get(extension))) throw conflict("Extension has explicit different stage; resolve manually: " + extension);
                        mappedStages.put(extension, newStage);
                    }
                }
            }
        }
        if (!consumed.equals(inserted)) throw conflict("Unmapped new parent states");
        // Preserve initial workflow semantics; changes require a separate explicit mapping.
        if (!xml(previous.getInitialActions()).equals(xml(parent.getInitialActions()))) throw conflict("Initial transitions changed");
        WorkflowGraph.validateInheritance(parentGraph, JiraGraphReader.read(result));
        return new Plan(result, Collections.unmodifiableMap(mappedStages));
    }

    private static List<ActionDescriptor> path(WorkflowDescriptor workflow, String source, String destination, Set<String> inherited) {
        Map<String, StepDescriptor> nodes = steps(workflow);
        List<List<ActionDescriptor>> matches = new ArrayList<>();
        record Search(String state, List<ActionDescriptor> path) { }
        Deque<Search> queue = new ArrayDeque<>(); queue.add(new Search(source, List.of()));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Search search = queue.removeFirst();
            if (!visited.add(search.state)) throw conflict("Converging or cyclic child extension paths require explicit mapping");
            for (Object value : nodes.get(search.state).getActions()) {
                ActionDescriptor action = (ActionDescriptor) value;
                String next = target(workflow, action, search.state);
                List<ActionDescriptor> route = new ArrayList<>(search.path); route.add(action);
                if (destination.equals(next)) matches.add(route);
                else if (!inherited.contains(next)) queue.addLast(new Search(next, route));
            }
        }
        if (matches.size() != 1) throw conflict("Expected exactly one replacement child path");
        return matches.get(0);
    }
    private static void replace(WorkflowDescriptor descriptor, ActionDescriptor original, ActionDescriptor replacement) {
        for (Object value : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) value;
            int index = step.getActions().indexOf(original);
            if (index >= 0) { replacement.setParent(step); step.getActions().set(index, replacement); return; }
        }
        throw conflict("Child terminal transition is missing");
    }
    private static String owner(WorkflowDescriptor descriptor, int actionId) {
        for (var entry : steps(descriptor).entrySet()) if (entry.getValue().getAction(actionId) != null) return entry.getKey();
        throw conflict("Transition source is missing");
    }
    private static String target(WorkflowDescriptor descriptor, ActionDescriptor action, String source) {
        int id = action.getUnconditionalResult().getStep();
        if (id == -1) return source;
        StepDescriptor step = descriptor.getStep(id);
        if (step == null) throw conflict("Missing target step");
        return status(step);
    }
    private static String status(StepDescriptor step) { return (String) step.getMetaAttributes().get(JiraWorkflow.STEP_STATUS_KEY); }
    private static Map<String, StepDescriptor> steps(WorkflowDescriptor descriptor) {
        Map<String, StepDescriptor> result = new LinkedHashMap<>();
        for (Object value : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) value;
            if (status(step) == null || result.put(status(step), step) != null) throw conflict("Missing or duplicate status");
        }
        return result;
    }
    private static List<ActionDescriptor> actions(WorkflowDescriptor descriptor) {
        List<ActionDescriptor> result = new ArrayList<>(descriptor.getInitialActions());
        for (Object value : descriptor.getSteps()) result.addAll(((StepDescriptor) value).getActions());
        return result;
    }
    private static void ordinary(WorkflowDescriptor descriptor) {
        if (!descriptor.getCommonActions().isEmpty() || !descriptor.getGlobalActions().isEmpty()
                || !descriptor.getSplits().isEmpty() || !descriptor.getJoins().isEmpty()) throw conflict("Complex transition kinds require explicit mapping");
        Set<Integer> ids = new HashSet<>();
        for (ActionDescriptor action : actions(descriptor)) {
            if (!ids.add(action.getId()) || !action.getConditionalResults().isEmpty() || action.getUnconditionalResult() == null
                    || action.getUnconditionalResult().getSplit() > 0 || action.getUnconditionalResult().getJoin() > 0)
                throw conflict("Ambiguous transition identity/result");
        }
    }
    private static void unchangedWorkflowConfiguration(WorkflowDescriptor previous, WorkflowDescriptor parent) {
        Map oldMeta = new LinkedHashMap(previous.getMetaAttributes()), newMeta = new LinkedHashMap(parent.getMetaAttributes());
        for (String key : List.of(JiraWorkflow.JIRA_META_UPDATED_DATE, JiraWorkflow.JIRA_META_UPDATE_AUTHOR_KEY,
                JiraWorkflow.JIRA_META_UPDATE_AUTHOR_NAME)) { oldMeta.remove(key); newMeta.remove(key); }
        String oldConditions = previous.getGlobalConditions() == null ? "" : previous.getGlobalConditions().asXML();
        String newConditions = parent.getGlobalConditions() == null ? "" : parent.getGlobalConditions().asXML();
        if (!oldMeta.equals(newMeta) || !oldConditions.equals(newConditions)
                || !xml(previous.getRegisters()).equals(xml(parent.getRegisters()))
                || !previous.getTriggerFunctions().keySet().equals(parent.getTriggerFunctions().keySet()))
            throw conflict("Changed workflow-level configuration requires explicit merge");
        for (Object key : previous.getTriggerFunctions().keySet()) {
            AbstractDescriptor before = (AbstractDescriptor) previous.getTriggerFunctions().get(key);
            AbstractDescriptor after = (AbstractDescriptor) parent.getTriggerFunctions().get(key);
            if (!before.asXML().equals(after.asXML())) throw conflict("Changed workflow trigger requires explicit merge");
        }
    }
    private static List<String> xml(List<AbstractDescriptor> descriptors) {
        List<String> result = new ArrayList<>(); for (AbstractDescriptor descriptor : descriptors) result.add(descriptor.asXML()); return result;
    }
    private static IllegalArgumentException conflict(String message) { return new IllegalArgumentException(message); }
}
