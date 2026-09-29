package igsl.com.jira.workflow.jira;

import com.opensymphony.workflow.loader.*;
import java.util.*;

/** Three-way append plan. Returns a detached action; no caller-owned descriptor is mutated. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ActionAppendPlanner {
    private ActionAppendPlanner() { }

    public static ActionDescriptor plan(ActionDescriptor previousParent, ActionDescriptor publishedParent,
                                        ActionDescriptor child) {
        ActionDescriptor result = DescriptorXml.action(child);
        appendProperties(previousParent.getMetaAttributes(), publishedParent.getMetaAttributes(), result.getMetaAttributes());
        result.setView(singleValue(previousParent.getView(), publishedParent.getView(), result.getView(), "transition view"));
        List<ValidatorDescriptor> additions = additions(previousParent.getValidators(), publishedParent.getValidators(), "validators");
        for (ValidatorDescriptor validator : additions) {
            if (!contains(result.getValidators(), validator)) result.getValidators().add(
                    DescriptorFactory.getFactory().createValidatorDescriptor(DescriptorXml.element(validator.asXML())));
        }
        appendConditions(previousParent, publishedParent, result);
        // Execution position must be established against Jira's mandatory functions before any append.
        unchanged(previousParent.getPreFunctions(), publishedParent.getPreFunctions(), "pre-functions");
        unchanged(previousParent.getPostFunctions(), publishedParent.getPostFunctions(), "action post-functions");
        if (previousParent.getUnconditionalResult() == null || publishedParent.getUnconditionalResult() == null) {
            throw new IllegalArgumentException("Missing unconditional result requires manual merge");
        }
        unchanged(previousParent.getUnconditionalResult().getPreFunctions(), publishedParent.getUnconditionalResult().getPreFunctions(), "result pre-functions");
        unchanged(previousParent.getUnconditionalResult().getPostFunctions(), publishedParent.getUnconditionalResult().getPostFunctions(), "result post-functions");
        unchanged(previousParent.getUnconditionalResult().getValidators(), publishedParent.getUnconditionalResult().getValidators(), "result validators");
        unchanged(previousParent.getConditionalResults(), publishedParent.getConditionalResults(), "conditional results");
        if (previousParent.getAutoExecute() != publishedParent.getAutoExecute()
                || previousParent.isFinish() != publishedParent.isFinish()) {
            throw new IllegalArgumentException("Execution flags changed; manual merge required");
        }
        return result;
    }

    private static void appendProperties(Map oldValues, Map newValues, Map child) {
        for (Object key : oldValues.keySet()) {
            if (!newValues.containsKey(key) || !Objects.equals(oldValues.get(key), newValues.get(key))) {
                throw new IllegalArgumentException("Parent property modified or removed: " + key);
            }
        }
        for (Object key : newValues.keySet()) {
            if (oldValues.containsKey(key)) continue;
            if (child.containsKey(key) && !Objects.equals(child.get(key), newValues.get(key))) {
                throw new IllegalArgumentException("Child property conflicts: " + key);
            }
            child.put(key, newValues.get(key));
        }
    }

    private static String singleValue(String oldValue, String newValue, String childValue, String label) {
        if (Objects.equals(oldValue, newValue)) return childValue;
        if (oldValue != null && !oldValue.isEmpty()) throw new IllegalArgumentException("Parent " + label + " modified or removed");
        if (childValue == null || childValue.isEmpty() || Objects.equals(childValue, newValue)) return newValue;
        throw new IllegalArgumentException("Child " + label + " conflicts");
    }

    private static <T extends AbstractDescriptor> List<T> additions(List<T> oldValues, List<T> newValues, String label) {
        // The old list must remain as an ordered subsequence: edits/removals/reordering are not additions.
        List<T> added = new ArrayList<>(); int cursor = 0;
        for (T item : newValues) {
            if (cursor < oldValues.size() && equal(oldValues.get(cursor), item)) cursor++;
            else added.add(item);
        }
        if (cursor != oldValues.size()) throw new IllegalArgumentException("Parent " + label + " modified, removed or reordered");
        return added;
    }

    private static void appendConditions(ActionDescriptor oldParent, ActionDescriptor newParent, ActionDescriptor child) {
        ConditionsDescriptor oldGroup = conditions(oldParent), newGroup = conditions(newParent);
        if (equal(oldGroup, newGroup)) return;
        ConditionsDescriptor added;
        if (oldGroup == null) added = copy(newGroup);
        else {
            if (newGroup == null || !"AND".equalsIgnoreCase(oldGroup.getType()) || !"AND".equalsIgnoreCase(newGroup.getType())) {
                throw new IllegalArgumentException("Parent condition group cannot be safely expressed as an AND addition");
            }
            List<AbstractDescriptor> items = additions(oldGroup.getConditions(), newGroup.getConditions(), "conditions");
            added = copy(newGroup); added.getConditions().clear();
            for (AbstractDescriptor item : items) {
                if (item instanceof ConditionsDescriptor) added.getConditions().add(copy((ConditionsDescriptor) item));
                else if (item instanceof ConditionDescriptor) added.getConditions().add(DescriptorFactory.getFactory()
                        .createConditionDescriptor(DescriptorXml.element(item.asXML())));
                else throw new IllegalArgumentException("Unknown condition descriptor");
            }
        }
        if (added == null || added.getConditions().isEmpty()) return;
        ConditionsDescriptor existing = conditions(child);
        if (containsGroup(existing, added)) return;
        ConditionsDescriptor combined = DescriptorFactory.getFactory().createConditionsDescriptor();
        combined.setType("AND");
        if (existing != null) combined.getConditions().add(copy(existing));
        combined.getConditions().add(added);
        RestrictionDescriptor restriction = new RestrictionDescriptor(); restriction.setConditionsDescriptor(combined);
        child.setRestriction(restriction);
    }
    private static boolean containsGroup(ConditionsDescriptor existing, ConditionsDescriptor group) {
        return required(existing, group);
    }
    private static boolean required(AbstractDescriptor existing, AbstractDescriptor target) {
        existing = unwrap(existing); target = unwrap(target);
        if (equal(existing, target)) return true;
        if (existing == null || target == null) return false;
        if (target instanceof ConditionsDescriptor && "AND".equalsIgnoreCase(((ConditionsDescriptor) target).getType())) {
            for (Object item : ((ConditionsDescriptor) target).getConditions()) {
                if (!required(existing, (AbstractDescriptor) item)) return false;
            }
            return true;
        }
        if (existing instanceof ConditionsDescriptor && "AND".equalsIgnoreCase(((ConditionsDescriptor) existing).getType())) {
            for (Object item : ((ConditionsDescriptor) existing).getConditions()) {
                if (required((AbstractDescriptor) item, target)) return true;
            }
        }
        return false;
    }
    private static AbstractDescriptor unwrap(AbstractDescriptor value) {
        while (value instanceof ConditionsDescriptor && ((ConditionsDescriptor) value).getConditions().size() == 1) {
            value = (AbstractDescriptor) ((ConditionsDescriptor) value).getConditions().get(0);
        }
        return value;
    }
    private static ConditionsDescriptor conditions(ActionDescriptor action) {
        return action.getRestriction() == null ? null : action.getRestriction().getConditionsDescriptor();
    }
    private static ConditionsDescriptor copy(ConditionsDescriptor source) {
        if (source == null) return null;
        ConditionsDescriptor result = DescriptorFactory.getFactory().createConditionsDescriptor();
        result.setType(source.getType());
        for (Object item : source.getConditions()) {
            if (item instanceof ConditionsDescriptor) result.getConditions().add(copy((ConditionsDescriptor) item));
            else if (item instanceof ConditionDescriptor) result.getConditions().add(DescriptorFactory.getFactory()
                    .createConditionDescriptor(DescriptorXml.element(((ConditionDescriptor) item).asXML())));
            else throw new IllegalArgumentException("Unknown condition descriptor");
        }
        return result;
    }
    private static boolean equal(AbstractDescriptor left, AbstractDescriptor right) {
        left = unwrap(left); right = unwrap(right);
        if (left == right) return true;
        if (left == null || right == null || left.getClass() != right.getClass()) return false;
        if (left instanceof ValidatorDescriptor) {
            ValidatorDescriptor a = (ValidatorDescriptor) left, b = (ValidatorDescriptor) right;
            return text(a.getType()).equals(text(b.getType())) && text(a.getName()).equals(text(b.getName())) && a.getArgs().equals(b.getArgs());
        }
        if (left instanceof FunctionDescriptor) {
            FunctionDescriptor a = (FunctionDescriptor) left, b = (FunctionDescriptor) right;
            return text(a.getType()).equals(text(b.getType())) && text(a.getName()).equals(text(b.getName())) && a.getArgs().equals(b.getArgs());
        }
        if (left instanceof ConditionDescriptor) {
            ConditionDescriptor a = (ConditionDescriptor) left, b = (ConditionDescriptor) right;
            return text(a.getType()).equals(text(b.getType())) && text(a.getName()).equals(text(b.getName()))
                    && a.isNegate() == b.isNegate() && a.getArgs().equals(b.getArgs());
        }
        if (left instanceof ConditionsDescriptor) {
            ConditionsDescriptor a = (ConditionsDescriptor) left, b = (ConditionsDescriptor) right;
            if (!text(a.getType()).equalsIgnoreCase(text(b.getType())) || a.getConditions().size() != b.getConditions().size()) return false;
            for (int i = 0; i < a.getConditions().size(); i++) if (!equal((AbstractDescriptor) a.getConditions().get(i), (AbstractDescriptor) b.getConditions().get(i))) return false;
            return true;
        }
        return left.asXML().equals(right.asXML());
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static boolean contains(List<? extends AbstractDescriptor> items, AbstractDescriptor target) {
        return items.stream().anyMatch(item -> equal(item, target));
    }
    private static void unchanged(List<? extends AbstractDescriptor> oldValues, List<? extends AbstractDescriptor> newValues, String label) {
        if (oldValues.size() != newValues.size()) throw new IllegalArgumentException("Changed " + label + " requires verified native placement");
        for (int i = 0; i < oldValues.size(); i++) if (!equal(oldValues.get(i), newValues.get(i))) {
            throw new IllegalArgumentException("Changed " + label + " requires manual merge");
        }
    }
}
