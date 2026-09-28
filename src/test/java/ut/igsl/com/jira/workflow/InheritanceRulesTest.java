package ut.igsl.com.jira.workflow;

import igsl.com.jira.workflow.domain.InheritanceRules;
import org.junit.Test;
import java.util.Map;

public class InheritanceRulesTest {
    @Test public void allowsOneParentWithMultipleChildren() {
        InheritanceRules.validateNewRelation("parent", "second", Map.of("first", "parent"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsSelfInheritance() {
        InheritanceRules.validateNewRelation("same", "same", Map.of());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsSecondParent() {
        InheritanceRules.validateNewRelation("another", "child", Map.of("child", "parent"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsGrandchild() {
        InheritanceRules.validateNewRelation("child", "grandchild", Map.of("child", "parent"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsTurningParentIntoChild() {
        InheritanceRules.validateNewRelation("grandparent", "parent", Map.of("child", "parent"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsCorruptExistingCycle() {
        InheritanceRules.validateNewRelation("p", "c", Map.of("a", "b", "b", "a"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsReferencedWorkflowDeletion() {
        InheritanceRules.validateWorkflowDeletion("workflow", true, Map.of());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsParentDeletionWithoutProjectReference() {
        InheritanceRules.validateWorkflowDeletion("parent", false, Map.of("child", "parent"));
    }
    @Test public void allowsUnreferencedWorkflowDeletion() {
        InheritanceRules.validateWorkflowDeletion("unused", false, Map.of("child", "parent"));
    }
}
