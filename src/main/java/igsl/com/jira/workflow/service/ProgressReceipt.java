package igsl.com.jira.workflow.service;

import java.math.BigDecimal;

/** Recovery evidence is captured once; retries never look up a later progress configuration. */
public final class ProgressReceipt {
    public long issueId;
    public long transitionId;
    public long occurredAt;
    public String workflow;
    public String targetStatus;
    public String ruleRevision;
    public BigDecimal percentage;
    public String state = "CAPTURED";
    public String error;
    public int attempts;
}
