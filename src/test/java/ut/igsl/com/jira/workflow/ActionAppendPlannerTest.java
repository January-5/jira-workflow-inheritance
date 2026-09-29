package ut.igsl.com.jira.workflow;

import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.jira.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class ActionAppendPlannerTest {
    private ActionDescriptor action() { return JiraGraphReaderTest.action(10, 2); }
    private ValidatorDescriptor validator(String value) {
        ValidatorDescriptor result = DescriptorFactory.getFactory().createValidatorDescriptor();
        result.setType("class"); result.getArgs().put("class.name", value); return result;
    }
    private ConditionsDescriptor group(String name, String type) {
        ConditionDescriptor condition = DescriptorFactory.getFactory().createConditionDescriptor();
        condition.setType("class"); condition.getArgs().put("class.name", name);
        ConditionsDescriptor group = DescriptorFactory.getFactory().createConditionsDescriptor();
        group.setType(type); group.getConditions().add(condition); return group;
    }
    private void conditions(ActionDescriptor action, ConditionsDescriptor group) {
        RestrictionDescriptor restriction = new RestrictionDescriptor(); restriction.setConditionsDescriptor(group); action.setRestriction(restriction);
    }
    @Test public void addsValidatorAfterLocalValidatorAndRetryDoesNotDuplicate() {
        ActionDescriptor previous = action(), next = action(), child = action();
        next.getValidators().add(validator("Parent")); child.getValidators().add(validator("Child"));
        String unchangedChild = child.asXML();
        ActionDescriptor result = ActionAppendPlanner.plan(previous, next, child);
        assertEquals(2, result.getValidators().size());
        assertEquals("Child", ((ValidatorDescriptor) result.getValidators().get(0)).getArgs().get("class.name"));
        assertEquals(2, ActionAppendPlanner.plan(previous, next, result).getValidators().size());
        assertEquals(unchangedChild, child.asXML());
    }
    @Test(expected = IllegalArgumentException.class) public void parentValidatorModificationIsNotAnotherInvocation() {
        ActionDescriptor previous = action(), next = action();
        previous.getValidators().add(validator("Old")); next.getValidators().add(validator("Modified"));
        ActionAppendPlanner.plan(previous, next, action());
    }
    @Test(expected = IllegalArgumentException.class) public void parentPropertyRemovalRequiresResolution() {
        ActionDescriptor previous = action(); previous.getMetaAttributes().put("property", "original");
        ActionAppendPlanner.plan(previous, action(), action());
    }
    @Test public void conflictingPropertyNeverPartiallyModifiesChild() {
        ActionDescriptor next = action(), child = action();
        next.getMetaAttributes().put("new", "safe"); next.getMetaAttributes().put("shared", "parent");
        child.getMetaAttributes().put("shared", "local");
        String before = child.asXML();
        try { ActionAppendPlanner.plan(action(), next, child); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(before, child.asXML());
    }
    @Test public void preservesChildOrGroupInsideCombinedAnd() {
        ActionDescriptor next = action(), child = action();
        conditions(next, group("Parent", "AND"));
        ConditionsDescriptor childOr = group("Child", "OR");
        childOr.getConditions().add(group("OtherChild", "AND").getConditions().get(0));
        conditions(child, childOr);
        ActionDescriptor merged = ActionAppendPlanner.plan(action(), next, child);
        ConditionsDescriptor combined = merged.getRestriction().getConditionsDescriptor();
        assertEquals("AND", combined.getType()); assertEquals(2, combined.getConditions().size());
        assertEquals("OR", ((ConditionsDescriptor) combined.getConditions().get(0)).getType());
        assertEquals(merged.asXML(), ActionAppendPlanner.plan(action(), next, merged).asXML());
    }
    @Test(expected = IllegalArgumentException.class) public void changedPostFunctionRequiresNativePlacementReview() {
        ActionDescriptor next = action();
        FunctionDescriptor function = DescriptorFactory.getFactory().createFunctionDescriptor(); function.setType("class");
        function.getArgs().put("class.name", "custom.BusinessAction"); next.getUnconditionalResult().getPostFunctions().add(function);
        ActionAppendPlanner.plan(action(), next, action());
    }
    @Test public void preservesLocalViewWhenParentDoesNotChangeIt() {
        ActionDescriptor child = action(); child.setView("child-view");
        assertEquals("child-view", ActionAppendPlanner.plan(action(), action(), child).getView());
    }
    @Test(expected = IllegalArgumentException.class) public void differingViewsAreNotOverwritten() {
        ActionDescriptor next = action(), child = action(); next.setView("parent-view"); child.setView("child-view");
        ActionAppendPlanner.plan(action(), next, child);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsExternalEntityInput() {
        DescriptorXml.element("<!DOCTYPE action [<!ENTITY unsafe SYSTEM 'file:///not-readable'>]><action>&unsafe;</action>");
    }
}
