package ut.igsl.com.jira.workflow;

import igsl.com.jira.workflow.service.*;
import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class ProgressJournalTest {
    private final ProgressJournal journal = new ProgressJournal(new ConfigurationStoreTest().store);
    private ProgressReceipt receipt(long transition, String version, int percentage) {
        ProgressReceipt receipt = new ProgressReceipt(); receipt.issueId = 129; receipt.transitionId = transition;
        receipt.workflow = "child"; receipt.targetStatus = "B"; receipt.ruleRevision = version;
        receipt.percentage = BigDecimal.valueOf(percentage); return receipt;
    }
    @Test public void duplicateCaptureKeepsOriginalRuleSnapshot() {
        journal.capture(receipt(10, "v1", 50)); journal.capture(receipt(10, "v2", 60));
        assertEquals("v1", journal.get(129).ruleRevision); assertEquals(BigDecimal.valueOf(50), journal.get(129).percentage);
        assertEquals(1, journal.pending(1, 100).size());
    }
    @Test public void olderRetryCannotOverwriteLaterTransition() {
        journal.capture(receipt(10, "v1", 50)); journal.capture(receipt(11, "v2", 70));
        journal.update(129, 10, "APPLIED", null);
        assertEquals(11, journal.get(129).transitionId); assertEquals("CAPTURED", journal.get(129).state);
        assertEquals(BigDecimal.valueOf(70), journal.get(129).percentage);
    }
    @Test public void completedReceiptIsNotQueuedButDuplicateDoesNotRequeue() {
        journal.capture(receipt(10, "v1", 50)); journal.update(129, 10, "APPLIED", null);
        journal.capture(receipt(10, "v1", 50)); assertTrue(journal.pending(1, 100).isEmpty());
    }
    @Test public void failedReceiptIsDurableAndRetainsEvidence() {
        journal.capture(receipt(10, "v1", 50)); journal.update(129, 10, "FAILED", "index unavailable");
        ProgressReceipt failed = journal.pending(1, 100).get(0);
        assertEquals("v1", failed.ruleRevision); assertEquals(1, failed.attempts); assertEquals("index unavailable", failed.error);
    }
}
