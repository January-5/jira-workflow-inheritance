package igsl.com.jira.workflow.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure validation; callers must validate and persist under one cluster-safe transaction. */
public final class InheritanceRules {
    private InheritanceRules() { }

    /** Relations are keyed by child workflow name, with the parent name as the value. */
    public static void validateNewRelation(String parent, String child, Map<String, String> relations) {
        requireName(parent);
        requireName(child);
        validateExistingRelations(relations);
        if (parent.equals(child)) {
            throw new IllegalArgumentException("A workflow cannot inherit itself");
        }
        if (relations.containsKey(child)) {
            throw new IllegalArgumentException("The child already has a parent");
        }
        if (relations.containsKey(parent) || relations.containsValue(child)) {
            throw new IllegalArgumentException("Only one inheritance level is supported");
        }
    }

    public static void validateWorkflowDeletion(String workflow, boolean referencedByProjectOrScheme,
                                                Map<String, String> relations) {
        requireName(workflow);
        validateExistingRelations(relations);
        if (referencedByProjectOrScheme || relations.containsValue(workflow)) {
            throw new IllegalArgumentException("The workflow is referenced by a project, scheme or child");
        }
    }

    private static void validateExistingRelations(Map<String, String> relations) {
        Objects.requireNonNull(relations, "relations");
        Set<String> parents = new HashSet<>();
        relations.forEach((child, parent) -> {
            requireName(child);
            requireName(parent);
            parents.add(parent);
        });
        for (String child : relations.keySet()) {
            if (parents.contains(child)) {
                throw new IllegalArgumentException("Existing relations contain a cycle or multiple levels");
            }
        }
    }

    static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("An identifier is required");
        }
        return name;
    }
}
