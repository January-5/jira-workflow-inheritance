package ut.igsl.com.jira.workflow;

import igsl.com.jira.workflow.domain.PublishedProgressRules;
import igsl.com.jira.workflow.domain.TransitionProgress;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class ProgressRulesTest {
    @Test(expected = IllegalArgumentException.class) public void rejectsMoreThanTwoDecimalPlaces() {
        new PublishedProgressRules("p", "v1", Set.of("A"), Map.of("A", new BigDecimal("60.251")), Set.of(), Map.of());
    }
    @Test public void acceptsTwoDecimalPlacesAndTrailingZerosWithoutRounding() {
        PublishedProgressRules rules = new PublishedProgressRules("p", "v1", Set.of("A"),
                Map.of("A", new BigDecimal("60.250")), Set.of(), Map.of());
        assertEquals(0, rules.progressAt("A").compareTo(new BigDecimal("60.25")));
    }
    private static BigDecimal n(int value) { return BigDecimal.valueOf(value); }
    private PublishedProgressRules rules(String revision, int b) {
        return new PublishedProgressRules("child", revision, Set.of("A", "B", "C"),
                Map.of("A", n(0), "B", n(b), "C", n(100)),
                Set.of("X", "Y"), Map.of("X", "A", "Y", "B"));
    }
    @Test public void successfulTransitionUsesTargetPercentage() {
        TransitionProgress value = TransitionProgress.afterTransition(true, null, "child", "B", rules("v1", 60));
        assertEquals(n(60), value.getPercentage());
        assertEquals("B", value.getTargetStatusId());
        assertEquals("v1", value.getRuleRevision());
    }
    @Test public void rejectedTransitionPreservesSavedValueEvenWithoutRules() {
        TransitionProgress saved = TransitionProgress.afterTransition(true, null, "child", "B", rules("v1", 50));
        assertSame(saved, TransitionProgress.afterTransition(false, saved, "child", "C", null));
    }
    @Test public void rollbackToExtensionUsesAssignedStageInsteadOfPriorValue() {
        TransitionProgress saved = TransitionProgress.afterTransition(true, null, "child", "B", rules("v1", 60));
        assertEquals(n(0), TransitionProgress.afterTransition(true, saved, "child", "X", rules("v1", 60)).getPercentage());
    }
    @Test public void newPublishedSnapshotDoesNotChangeSavedProgress() {
        TransitionProgress saved = TransitionProgress.afterTransition(true, null, "child", "B", rules("v1", 50));
        PublishedProgressRules newer = rules("v2", 60);
        assertEquals(n(50), saved.getPercentage());
        assertEquals("v1", saved.getRuleRevision());
        assertEquals(n(60), TransitionProgress.afterTransition(true, saved, "child", "B", newer).getPercentage());
    }
    @Test public void pendingChildUsesItsOwnPublishedSnapshot() {
        PublishedProgressRules oldChild = rules("child-v1", 50);
        PublishedProgressRules newChild = rules("child-v2", 70);
        assertEquals(n(50), TransitionProgress.afterTransition(true, null, "child", "B", oldChild).getPercentage());
        assertEquals(n(70), TransitionProgress.afterTransition(true, null, "child", "B", newChild).getPercentage());
    }
    @Test public void stageReassignmentAffectsOnlyFutureTransitions() {
        TransitionProgress saved = TransitionProgress.afterTransition(true, null, "child", "X", rules("v1", 60));
        PublishedProgressRules newer = new PublishedProgressRules("child", "v2", Set.of("A", "D", "B", "C"),
                Map.of("A", n(0), "D", n(30), "B", n(60), "C", n(100)), Set.of("X"), Map.of("X", "D"));
        assertEquals(n(0), saved.getPercentage());
        assertEquals(n(30), TransitionProgress.afterTransition(true, saved, "child", "X", newer).getPercentage());
    }
    @Test public void permitsNonMonotonicValuesAndNonStandardEndpoints() {
        PublishedProgressRules value = new PublishedProgressRules("p", "v1", Set.of("A", "B", "C"),
                Map.of("A", n(90), "B", n(10), "C", n(80)), Set.of(), Map.of());
        assertEquals(n(80), value.progressAt("C"));
    }
    @Test public void configurationCopiesMutableInput() {
        Map<String, BigDecimal> values = new HashMap<>(Map.of("A", n(50)));
        PublishedProgressRules snapshot = new PublishedProgressRules("p", "v1", Set.of("A"), values, Set.of(), Map.of());
        values.put("A", n(99));
        assertEquals(n(50), snapshot.progressAt("A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsMissingParentPercentage() {
        new PublishedProgressRules("p", "v1", Set.of("A", "B"), Map.of("A", n(0)), Set.of(), Map.of());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnassignedExtension() {
        new PublishedProgressRules("p", "v1", Set.of("A"), Map.of("A", n(0)), Set.of("X"), Map.of());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsExtensionToExtensionMapping() {
        new PublishedProgressRules("p", "v1", Set.of("A"), Map.of("A", n(0)),
                Set.of("X", "Y"), Map.of("X", "Y", "Y", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsIndependentExtensionPercentage() {
        new PublishedProgressRules("p", "v1", Set.of("A"), Map.of("A", n(0), "X", n(10)), Set.of("X"), Map.of("X", "A"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsOutOfRangePercentage() {
        new PublishedProgressRules("p", "v1", Set.of("A"), Map.of("A", n(101)), Set.of(), Map.of());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownStatusRatherThanAssumingZero() {
        rules("v1", 60).progressAt("unknown");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsWrongWorkflowSnapshot() {
        TransitionProgress.afterTransition(true, null, "other", "B", rules("v1", 60));
    }
}
