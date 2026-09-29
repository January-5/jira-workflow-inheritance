package ut.igsl.com.jira.workflow;

import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.event.issue.*;
import com.atlassian.jira.event.issue.commit.OnCommitIssueEventBundle;
import com.atlassian.jira.event.commit.OnCommitEvent;
import com.atlassian.jira.issue.Issue;
import igsl.com.jira.workflow.jira.ProgressEventListener;
import igsl.com.jira.workflow.service.ProgressUpdateService;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProgressEventListenerTest {
    private final List<IssueEvent> captured = new ArrayList<>();
    private final List<Long> applied = new ArrayList<>();
    private final List<String> lifecycle = new ArrayList<>();
    private final ProgressUpdateService service = new ProgressUpdateService(null, null, null, null, null, null, null) {
        @Override public void capture(IssueEvent event) { captured.add(event); }
        @Override public void applyCommitted(long issueId, long transitionId) {
            assertEquals(42L, issueId); applied.add(transitionId);
        }
    };
    private final EventPublisher publisher = proxy(EventPublisher.class, (p,m,a) -> { lifecycle.add(m.getName()); return null; });
    private final ProgressEventListener listener = new ProgressEventListener(publisher, service);
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private IssueEvent event(String source) {
        Issue issue = proxy(Issue.class, (p,m,a) -> m.getName().equals("getId") ? 42L : null);
        GenericValue change = new GenericValue(new org.ofbiz.core.entity.model.ModelEntity()) {
            @Override public Long getLong(String name) { assertEquals("id", name); return 123L; }
        };
        return new IssueEvent(issue, null, null, null, change, new HashMap<>(Map.of("eventsource", source)), 13L);
    }
    private DelegatingJiraIssueEvent delegated(IssueEvent event) {
        return proxy(DelegatingJiraIssueEvent.class, (p,m,a) -> m.getName().equals("asIssueEvent") ? event : null);
    }
    @Test public void registersAndUnregistersSameListener() {
        listener.afterPropertiesSet(); listener.destroy();
        assertEquals(List.of("register", "unregister"), lifecycle);
    }
    @Test public void capturesNativeDelegatingBundle() {
        IssueEvent event = event("workflow");
        IssueEventBundle bundle = proxy(IssueEventBundle.class, (p,m,a) -> List.of(delegated(event)));
        listener.beforeCommitBundle(bundle);
        assertSame(event, captured.get(0));
    }
    @Test public void handlesBothRawAndDelegatedCommitEvents() {
        IssueEvent event = event("workflow");
        OnCommitEvent<IssueEvent> raw = () -> event;
        OnCommitEvent<DelegatingJiraIssueEvent> wrapped = () -> delegated(event);
        OnCommitIssueEventBundle bundle = () -> List.of(raw, wrapped);
        listener.afterCommit(bundle);
        assertEquals(List.of(123L, 123L), applied); // Service receipts deduplicate native duplicate dispatch.
    }
    @Test public void ignoresNonWorkflowCommittedEvents() {
        OnCommitEvent<IssueEvent> moved = () -> event("move");
        listener.afterCommit(() -> List.of(moved));
        assertTrue(applied.isEmpty());
    }
}
