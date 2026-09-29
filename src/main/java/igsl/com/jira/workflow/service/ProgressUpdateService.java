package igsl.com.jira.workflow.service;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.changehistory.*;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.issue.index.IssueIndexingService;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import igsl.com.jira.workflow.jira.ProgressFieldType;
import javax.inject.*;
import java.math.BigDecimal;
import java.util.*;

/** Capture before commit, write only after verifying committed status history. */
@Named
public class ProgressUpdateService {
    private final ConfigurationStore store;
    private final ProgressJournal journal;
    private final ProgressFieldService fields;
    private final IssueManager issues;
    private final WorkflowManager workflows;
    private final ChangeHistoryManager history;
    private final IssueIndexingService indexing;

    @Inject public ProgressUpdateService(ConfigurationStore store, ProgressJournal journal, ProgressFieldService fields,
            @ComponentImport IssueManager issues, @ComponentImport WorkflowManager workflows,
            @ComponentImport ChangeHistoryManager history, @ComponentImport IssueIndexingService indexing) {
        this.store = store; this.journal = journal; this.fields = fields; this.issues = issues;
        this.workflows = workflows; this.history = history; this.indexing = indexing;
    }

    public void capture(IssueEvent event) {
        // Verified in Jira 9.12.11 FireIssueEventFunction: eventsource=workflow.
        // Import/move events are not treated as workflow transitions merely because status changed.
        if (!"workflow".equals(event.getParams().get("eventsource")) || event.getChangeLog() == null) return;
        Long transition = event.getChangeLog().getLong("id");
        ChangeHistory change = history.getChangeHistoryById(transition);
        if (change == null || !event.getIssue().getId().equals(change.getIssueId())) return;
        String target = target(change);
        if (target == null) return; // Same-state/create events require a separate verified source/token.
        String workflow = workflows.getWorkflow(event.getIssue()).getName();
        WorkflowConfiguration config = store.get(workflow);
        if (config == null || config.publishedRevision == null) return;
        String stage = config.publishedStages.getOrDefault(target, target);
        BigDecimal percentage = config.publishedProgress.get(stage);
        if (percentage == null) throw new IllegalStateException("Published progress rule missing for workflow " + workflow + ", status " + target);
        ProgressReceipt receipt = new ProgressReceipt();
        receipt.issueId = event.getIssue().getId(); receipt.transitionId = transition;
        receipt.occurredAt = change.getTimePerformed().getTime(); receipt.workflow = workflow;
        receipt.targetStatus = target; receipt.ruleRevision = config.publishedRevision; receipt.percentage = percentage;
        journal.capture(receipt);
    }

    public void applyCommitted(long issueId, long transitionId) {
        try {
            Boolean indexRequired = store.locked(ProgressJournal.scope(issueId), () -> {
                ProgressReceipt receipt = journal.get(issueId);
                if (receipt == null || receipt.transitionId != transitionId || "APPLIED".equals(receipt.state)
                        || "SUPERSEDED".equals(receipt.state)) return false;
                MutableIssue issue = issues.getIssueObject(issueId);
                if (!stillApplicable(receipt, issue)) {
                    journal.update(issueId, transitionId, "SUPERSEDED", "Issue has a newer status history or a different workflow");
                    return false;
                }
                List<CustomField> progressFields = fields.forIssue(issue);
                if (progressFields.isEmpty()) throw new IllegalStateException("No progress field is configured for this issue context");
                for (CustomField field : progressFields) {
                    if (!(field.getCustomFieldType() instanceof ProgressFieldType)) throw new IllegalStateException("Unexpected progress field type");
                    ((ProgressFieldType) field.getCustomFieldType()).writeCalculatedValue(field, issue, receipt.percentage);
                }
                journal.update(issueId, transitionId, "INDEX_PENDING", null);
                return true;
            });
            if (!indexRequired) return;
            // The field transaction has completed before indexing; retry can recover an index failure.
            store.locked(ProgressJournal.scope(issueId), () -> {
                ProgressReceipt latest = journal.get(issueId);
                if (latest == null || latest.transitionId != transitionId) return null;
                MutableIssue issue = issues.getIssueObject(issueId);
                if (!stillApplicable(latest, issue)) {
                    journal.update(issueId, transitionId, "SUPERSEDED", "Issue changed before progress indexing");
                    return null;
                }
                for (CustomField field : fields.forIssue(issue)) issue.setCustomFieldValue(field, latest.percentage.doubleValue());
                try { indexing.reIndex(issue); }
                catch (Exception error) { throw new IllegalStateException("Progress index update failed", error); }
                journal.update(issueId, transitionId, "APPLIED", null);
                return null;
            });
        } catch (RuntimeException error) {
            journal.update(issueId, transitionId, "FAILED", error.getMessage());
            throw error;
        }
    }

    private boolean stillApplicable(ProgressReceipt receipt, MutableIssue issue) {
        if (issue == null || !receipt.targetStatus.equals(issue.getStatusId())
                || !receipt.workflow.equals(workflows.getWorkflow(issue).getName())) return false;
        ChangeHistory original = history.getChangeHistoryById(receipt.transitionId);
        if (original == null || !issue.getId().equals(original.getIssueId()) || !receipt.targetStatus.equals(target(original))) return false;
        for (ChangeHistory later : history.getChangeHistoriesSince(issue, new Date(Math.max(0, receipt.occurredAt - 1)))) {
            if (later.getId() > receipt.transitionId && target(later) != null) return false;
        }
        return true;
    }
    private static String target(ChangeHistory change) {
        String target = null;
        for (ChangeItemBean item : change.getChangeItemBeans()) {
            if (ChangeItemBean.STATIC_FIELD.equals(item.getFieldType()) && "status".equals(item.getField())) {
                if (target != null) throw new IllegalStateException("Ambiguous status change history");
                target = item.getTo();
            }
        }
        return target;
    }
}
