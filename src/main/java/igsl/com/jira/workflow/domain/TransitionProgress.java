package igsl.com.jira.workflow.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** A calculated value and its recovery evidence; this class does not write Jira issues. */
public final class TransitionProgress {
    private final String workflowName;
    private final String targetStatusId;
    private final String ruleRevision;
    private final BigDecimal percentage;

    private TransitionProgress(PublishedProgressRules rules, String targetStatusId) {
        this.workflowName = rules.getWorkflowName();
        this.targetStatusId = targetStatusId;
        this.ruleRevision = rules.getRevision();
        this.percentage = rules.progressAt(targetStatusId);
    }

    /** The caller must supply the snapshot applicable to this transition, not a later publication. */
    public static TransitionProgress afterTransition(boolean succeeded, TransitionProgress savedValue,
                                                     String workflowName, String targetStatusId,
                                                     PublishedProgressRules applicableRules) {
        if (!succeeded) {
            return savedValue;
        }
        Objects.requireNonNull(applicableRules, "Successful transitions require published rules");
        if (!applicableRules.getWorkflowName().equals(workflowName)) {
            throw new IllegalArgumentException("Progress rules belong to a different workflow");
        }
        return new TransitionProgress(applicableRules, targetStatusId);
    }

    public String getWorkflowName() { return workflowName; }
    public String getTargetStatusId() { return targetStatusId; }
    public String getRuleRevision() { return ruleRevision; }
    public BigDecimal getPercentage() { return percentage; }
}
