package igsl.com.jira.workflow.jira;

import com.atlassian.event.api.*;
import com.atlassian.jira.event.issue.*;
import com.atlassian.jira.event.issue.commit.OnCommitIssueEventBundle;
import com.atlassian.jira.event.commit.OnCommitEvent;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import igsl.com.jira.workflow.service.ProgressUpdateService;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import javax.inject.*;

@Named
public class ProgressEventListener implements InitializingBean, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(ProgressEventListener.class);
    private final EventPublisher publisher;
    private final ProgressUpdateService progress;
    @Inject public ProgressEventListener(@ComponentImport EventPublisher publisher, ProgressUpdateService progress) {
        this.publisher = publisher; this.progress = progress;
    }
    @Override public void afterPropertiesSet() { publisher.register(this); }
    @Override public void destroy() { publisher.unregister(this); }

    @EventListener public void beforeCommit(IssueEvent event) { capture(event); }
    @EventListener public void beforeCommitBundle(IssueEventBundle bundle) {
        for (JiraIssueEvent event : bundle.getEvents()) {
            IssueEvent issueEvent = unwrap(event);
            if (issueEvent != null) capture(issueEvent);
        }
    }
    private void capture(IssueEvent event) {
        try { progress.capture(event); }
        catch (RuntimeException error) { LOG.error("Cannot capture progress evidence for issue {}", event.getIssue().getId(), error); }
    }
    @EventListener public void afterCommit(OnCommitIssueEventBundle bundle) {
        for (OnCommitEvent event : bundle.getOnCommitEvents()) {
            IssueEvent issueEvent = unwrap(event.getWrappedEvent());
            if (issueEvent == null) continue;
            if (!"workflow".equals(issueEvent.getParams().get("eventsource")) || issueEvent.getChangeLog() == null) continue;
            try { progress.applyCommitted(issueEvent.getIssue().getId(), issueEvent.getChangeLog().getLong("id")); }
            catch (RuntimeException error) { LOG.error("Progress recovery is pending for issue {}", issueEvent.getIssue().getId(), error); }
        }
    }

    private static IssueEvent unwrap(Object event) {
        if (event instanceof IssueEvent) return (IssueEvent) event;
        if (event instanceof DelegatingJiraIssueEvent) return ((DelegatingJiraIssueEvent) event).asIssueEvent();
        return null;
    }
}
