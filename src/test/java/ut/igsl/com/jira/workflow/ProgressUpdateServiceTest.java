package ut.igsl.com.jira.workflow;

import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.changehistory.*;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.issue.index.IssueIndexingService;
import com.atlassian.jira.workflow.*;
import igsl.com.jira.workflow.jira.ProgressFieldType;
import igsl.com.jira.workflow.service.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.Assert.*;

public class ProgressUpdateServiceTest {
    private final ConfigurationStore store = new ConfigurationStoreTest().store;
    private final ProgressJournal journal = new ProgressJournal(store);
    private int writes, indexes;
    private BigDecimal lastValue;
    private boolean indexUnavailable, laterHistory, changesBeforeIndex;
    private int issueReads;
    private String currentStatus = "B", currentWorkflow = "child";
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private final MutableIssue issue = proxy(MutableIssue.class, (p,m,a) -> {
        switch (m.getName()) {
            case "getId": return 129L;
            case "getStatusId": return currentStatus;
            case "setCustomFieldValue": return null;
            default: throw new UnsupportedOperationException(m.getName());
        }
    });
    private final ProgressFieldType type = new ProgressFieldType(null, null, null) {
        @Override public void writeCalculatedValue(CustomField field, Issue issue, BigDecimal value) { writes++; lastValue = value; }
    };
    private final CustomField field = proxy(CustomField.class, (p,m,a) -> m.getName().equals("getCustomFieldType") ? type : null);
    private final ProgressFieldService fields = new ProgressFieldService(null, null, null) {
        @Override public List<CustomField> forIssue(Issue value) { return List.of(field); }
    };
    private final IssueManager issues = proxy(IssueManager.class, (p,m,a) -> {
        if (++issueReads > 1 && changesBeforeIndex) laterHistory = true;
        return issue;
    });
    private final JiraWorkflow workflow = proxy(JiraWorkflow.class, (p,m,a) -> currentWorkflow);
    private final WorkflowManager workflows = proxy(WorkflowManager.class, (p,m,a) -> workflow);
    private final ChangeHistoryManager history = proxy(ChangeHistoryManager.class, (p,m,a) -> {
        if (m.getName().equals("getChangeHistoryById")) return change((Long) a[0]);
        if (m.getName().equals("getChangeHistoriesSince")) return laterHistory ? List.of(change(11)) : List.of(change(10));
        throw new UnsupportedOperationException(m.getName());
    });
    private final IssueIndexingService indexing = proxy(IssueIndexingService.class, (p,m,a) -> {
        if (indexUnavailable) throw new IllegalStateException("index unavailable"); indexes++; return null;
    });
    private final ProgressUpdateService service = new ProgressUpdateService(store, journal, fields, issues, workflows, history, indexing);
    private ChangeHistory change(long id) {
        return new ChangeHistory(null, null, null) {
            @Override public Long getId() { return id; }
            @Override public Long getIssueId() { return 129L; }
            @Override public Timestamp getTimePerformed() { return new Timestamp(1000); }
            @Override public List<ChangeItemBean> getChangeItemBeans() {
                return List.of(new ChangeItemBean(ChangeItemBean.STATIC_FIELD, "status", "A", "A", "B", "B"));
            }
        };
    }
    private void capture(long transition, int percentage) {
        ProgressReceipt receipt = new ProgressReceipt(); receipt.issueId = 129; receipt.transitionId = transition;
        receipt.occurredAt = 1000; receipt.workflow = "child"; receipt.targetStatus = "B";
        receipt.ruleRevision = "original-rules"; receipt.percentage = BigDecimal.valueOf(percentage); journal.capture(receipt);
    }
    @Test public void appliesCapturedValueAndIndexesOnlyOnce() {
        capture(10, 50); service.applyCommitted(129, 10); service.applyCommitted(129, 10);
        assertEquals(BigDecimal.valueOf(50), lastValue); assertEquals(1, writes); assertEquals(1, indexes);
        assertEquals("APPLIED", journal.get(129).state);
    }
    @Test public void recoveryKeepsOriginalValueAfterNewConfiguration() {
        capture(10, 50); indexUnavailable = true;
        try { service.applyCommitted(129, 10); fail(); } catch (IllegalStateException expected) { }
        assertEquals("FAILED", journal.get(129).state);
        WorkflowConfiguration changed = new WorkflowConfiguration(); changed.workflow = "child";
        changed.publishedRevision = "new-rules"; changed.publishedProgress.put("B", BigDecimal.valueOf(70));
        store.save(changed, 0, "admin", "NEW_CONFIG"); indexUnavailable = false;
        service.applyCommitted(129, 10);
        assertEquals(BigDecimal.valueOf(50), lastValue); assertEquals("original-rules", journal.get(129).ruleRevision);
    }
    @Test public void laterTransitionPreventsOldWrite() {
        capture(10, 50); capture(11, 70); service.applyCommitted(129, 10); assertEquals(0, writes);
    }
    @Test public void laterHistoryPreventsOldWriteEvenAfterReturningToSameStatus() {
        capture(10, 50); laterHistory = true; service.applyCommitted(129, 10);
        assertEquals(0, writes); assertEquals("SUPERSEDED", journal.get(129).state);
    }
    @Test public void migratedWorkflowPreventsOldRecovery() {
        capture(10, 50); currentWorkflow = "another-workflow"; service.applyCommitted(129, 10);
        assertEquals(0, writes); assertEquals("SUPERSEDED", journal.get(129).state);
    }
    @Test public void newerHistoryBetweenFieldWriteAndIndexPreventsStaleIndex() {
        capture(10, 50); changesBeforeIndex = true; service.applyCommitted(129, 10);
        assertEquals(1, writes); assertEquals(0, indexes);
        assertEquals("SUPERSEDED", journal.get(129).state);
    }
}
