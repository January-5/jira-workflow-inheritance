package igsl.com.jira.workflow.service;

import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import igsl.com.jira.workflow.domain.*;
import igsl.com.jira.workflow.jira.*;
import javax.inject.*;
import java.util.*;

/** Explicit managed publication; does not claim to veto publication through other Jira entry points. */
@Named
public class PublicationService {
    private final ConfigurationStore store;
    private final AdminAccess access;
    private final WorkflowManager workflows;
    private final WorkflowService nativeService;
    private final ConfigurationService configurations;

    @Inject public PublicationService(ConfigurationStore store, AdminAccess access,
            @ComponentImport WorkflowManager workflows, @ComponentImport WorkflowService nativeService,
            ConfigurationService configurations) {
        this.store = store; this.access = access; this.workflows = workflows;
        this.nativeService = nativeService; this.configurations = configurations;
    }

    public Map<String, Object> preview(String name, long version) {
        ApplicationUser user = access.requireAdmin();
        return store.exclusive("management", () -> {
            Checked checked = check(name, version, user);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workflow", name); result.put("version", version);
            result.put("fingerprint", fingerprint(checked.live));
            result.put("draftFingerprint", fingerprint(checked.draft));
            result.put("parentRevision", checked.parentRevision);
            result.put("statuses", checked.draft.getLinkedStatusIds());
            result.put("valid", true);
            return result;
        });
    }

    public WorkflowConfiguration publish(String name, long version, String liveFingerprint,
            String draftFingerprint, String parentRevision) {
        ApplicationUser user = access.requireAdmin();
        return store.exclusive("management", () -> {
            PublicationAttempt previous = read(name);
            if (previous != null && !Set.of("APPLIED", "DISMISSED", "RESOLVED").contains(previous.state))
                throw new IllegalArgumentException("Previous publication requires recovery or explicit resolution first");
            Checked checked = check(name, version, user);
            if (!Objects.equals(liveFingerprint, fingerprint(checked.live))
                    || !Objects.equals(draftFingerprint, fingerprint(checked.draft))
                    || !Objects.equals(parentRevision, checked.parentRevision))
                throw new ConcurrentModificationException("Workflow or parent changed after preview; reload");
            PublicationAttempt attempt = new PublicationAttempt();
            attempt.id = UUID.randomUUID().toString(); attempt.workflow = name; attempt.actor = user.getKey();
            attempt.configurationVersion = version; attempt.parentRevision = parentRevision;
            attempt.liveFingerprint = liveFingerprint; attempt.draftFingerprint = draftFingerprint;
            attempt.targetContentFingerprint = contentFingerprint(checked.draft);
            attempt.state = "PREPARED"; save(attempt);
            try {
                JiraServiceContextImpl context = new JiraServiceContextImpl(user);
                nativeService.overwriteActiveWorkflow(context, name);
                nativeErrors(context);
                attempt.state = "NATIVE_PUBLISHED"; save(attempt);
                return finish(attempt);
            } catch (RuntimeException error) {
                attempt.state = "PENDING"; attempt.error = String.valueOf(error.getMessage()); save(attempt);
                throw error;
            }
        });
    }

    /** Recovery never republishes a draft: it can only adopt the already-published, matching descriptor. */
    public WorkflowConfiguration recover(String name, String attemptId) {
        ApplicationUser user = access.requireAdmin();
        return store.exclusive("management", () -> {
            PublicationAttempt attempt = read(name);
            if (attempt == null || !Objects.equals(attemptId, attempt.id)) throw new ConcurrentModificationException("Publication attempt changed; reload");
            if ("APPLIED".equals(attempt.state)) return store.get(name);
            if (Set.of("DISMISSED", "RESOLVED").contains(attempt.state)) throw new IllegalArgumentException("Publication attempt is already closed");
            try {
                return store.locked("management", () -> {
                    WorkflowConfiguration result = finish(attempt);
                    store.auditOnly(name, user.getKey(), "RECOVER_PUBLICATION_RULES");
                    return result;
                });
            }
            catch (RuntimeException error) {
                attempt.state = "PENDING"; attempt.error = String.valueOf(error.getMessage()); save(attempt); throw error;
            }
        });
    }

    /** Clears only a failed attempt whose native live workflow is still exactly the pre-publication version. */
    public PublicationAttempt dismiss(String name, String attemptId) {
        ApplicationUser user = access.requireAdmin();
        return store.exclusive("management", () -> {
            PublicationAttempt attempt = read(name);
            if (attempt == null || !Objects.equals(attemptId, attempt.id)) throw new ConcurrentModificationException("Publication attempt changed; reload");
            if (Set.of("APPLIED", "RESOLVED").contains(attempt.state)) throw new IllegalArgumentException("A completed publication cannot be dismissed");
            JiraWorkflow live = workflows.getWorkflow(name);
            if (live == null || !Objects.equals(attempt.liveFingerprint, fingerprint(live)))
                throw new IllegalArgumentException("Live workflow changed; recover the publication instead");
            attempt.state = "DISMISSED"; save(attempt);
            store.auditOnly(name, user.getKey(), "DISMISS_UNPUBLISHED_ATTEMPT");
            return attempt;
        });
    }

    public PublicationAttempt status(String name) {
        access.requireAdmin();
        return store.exclusive("management", () -> read(name));
    }

    /** Administrator has separately reconciled native workflow and effective rules; close evidence only. */
    public PublicationAttempt acknowledgeResolution(String name, String attemptId) {
        ApplicationUser user = access.requireAdmin();
        return store.locked("management", () -> {
            PublicationAttempt attempt = read(name);
            if (attempt == null || !Objects.equals(attemptId, attempt.id)) throw new ConcurrentModificationException("Publication attempt changed; reload");
            if (Set.of("APPLIED", "DISMISSED", "RESOLVED").contains(attempt.state)) return attempt;
            JiraWorkflow live = workflows.getWorkflow(name);
            WorkflowConfiguration config = store.get(name);
            if (live == null || workflows.getDraftWorkflow(name) != null || config == null || config.publishedRevision == null
                    || !Set.of("PUBLISHED", "SYNCED").contains(config.syncStatus)
                    || !Objects.equals(config.workflowFingerprint, fingerprint(live)))
                throw new IllegalArgumentException("Resolve native workflow and activate its matching progress rules first");
            Set<String> extensions = new LinkedHashSet<>(live.getLinkedStatusIds()); extensions.removeAll(config.inheritedStatuses);
            new PublishedProgressRules(name, config.publishedRevision, config.inheritedStatuses,
                    config.publishedProgress, extensions, config.publishedStages);
            if (config.parent != null) {
                WorkflowConfiguration parent = store.get(config.parent);
                JiraWorkflow liveParent = workflows.getWorkflow(config.parent);
                if (parent == null || liveParent == null || !Objects.equals(config.publishedRevision, parent.publishedRevision)
                        || !Objects.equals(parent.workflowFingerprint, fingerprint(liveParent)))
                    throw new IllegalArgumentException("Resolve child against the current parent first");
                WorkflowGraph.validateInheritance(JiraGraphReader.read(liveParent), JiraGraphReader.read(live));
            }
            attempt.state = "RESOLVED"; attempt.error = null; save(attempt);
            store.auditOnly(name, user.getKey(), "ACKNOWLEDGE_PUBLICATION_RESOLUTION");
            return attempt;
        });
    }

    private WorkflowConfiguration finish(PublicationAttempt attempt) {
        return store.locked("management", () -> {
            JiraWorkflow live = workflows.getWorkflow(attempt.workflow);
            if (live == null || workflows.getDraftWorkflow(attempt.workflow) != null
                    || !Objects.equals(attempt.targetContentFingerprint, contentFingerprint(live)))
                throw new IllegalArgumentException("Published descriptor does not match this attempt, or a draft exists; resolve in Jira first");
            WorkflowConfiguration config = store.get(attempt.workflow);
            if (config == null || config.version != attempt.configurationVersion)
                throw new ConcurrentModificationException("Progress configuration changed during publication; administrator resolution required");
            WorkflowConfiguration applied = config.parent == null
                    ? configurations.activateRules(attempt.workflow, config.version, fingerprint(live))
                    : configurations.resolveChild(attempt.workflow, config.version, fingerprint(live), attempt.parentRevision);
            attempt.state = "APPLIED"; attempt.error = null; save(attempt);
            store.auditOnly(attempt.workflow, attempt.actor, "PUBLISH_DRAFT_AND_RULES");
            return applied;
        });
    }

    private Checked check(String name, long version, ApplicationUser user) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Workflow is required");
        WorkflowConfiguration config = store.get(name);
        if (config == null) throw new IllegalArgumentException("Save progress configuration first");
        if (version != config.version) throw new ConcurrentModificationException("Configuration changed; reload");
        JiraWorkflow live = workflows.getWorkflow(name), draft = workflows.getDraftWorkflow(name);
        if (live == null || draft == null || !live.isActive()) throw new IllegalArgumentException("An active workflow with a native Jira draft is required");
        JiraGraphReader.read(draft);
        String parentRevision = null;
        if (config.parent == null) {
            if (!draft.getLinkedStatusIds().containsAll(config.inheritedStatuses)
                    || !draft.getLinkedStatusIds().containsAll(live.getLinkedStatusIds()))
                throw new IllegalArgumentException("Parent states cannot be deleted");
            new PublishedProgressRules(name, "draft", draft.getLinkedStatusIds(), config.draftProgress, Set.of(), Map.of());
        } else {
            WorkflowConfiguration parent = store.get(config.parent);
            JiraWorkflow liveParent = workflows.getWorkflow(config.parent);
            if (parent == null || parent.publishedRevision == null || liveParent == null
                    || !Objects.equals(parent.workflowFingerprint, fingerprint(liveParent)))
                throw new IllegalArgumentException("Parent rules must match its current published workflow");
            WorkflowGraph.validateInheritance(JiraGraphReader.read(liveParent), JiraGraphReader.read(draft));
            Set<String> extensions = new LinkedHashSet<>(draft.getLinkedStatusIds()); extensions.removeAll(parent.inheritedStatuses);
            new PublishedProgressRules(name, parent.publishedRevision, parent.inheritedStatuses,
                    parent.publishedProgress, extensions, config.draftStages);
            parentRevision = parent.publishedRevision;
        }
        JiraServiceContextImpl context = new JiraServiceContextImpl(user);
        nativeService.validateOverwriteWorkflow(context, name); nativeErrors(context);
        return new Checked(live, draft, parentRevision);
    }

    private PublicationAttempt read(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Workflow is required");
        return store.locked("publication:" + name, () -> store.readEntity("publication." + ConfigurationStore.digest(name), PublicationAttempt.class));
    }
    private void save(PublicationAttempt attempt) {
        store.locked("publication:" + attempt.workflow, () -> {
            attempt.updatedAt = System.currentTimeMillis();
            store.writeEntity("publication." + ConfigurationStore.digest(attempt.workflow), attempt); return null;
        });
    }
    private static void nativeErrors(JiraServiceContextImpl context) {
        if (context.getErrorCollection().hasAnyErrors()) throw new IllegalArgumentException(context.getErrorCollection().toString());
    }
    private static String fingerprint(JiraWorkflow workflow) { return ConfigurationStore.digest(workflow.getDescriptor().asXML()); }
    private static String contentFingerprint(JiraWorkflow workflow) {
        return DescriptorXml.contentFingerprint(workflow.getDescriptor());
    }
    private record Checked(JiraWorkflow live, JiraWorkflow draft, String parentRevision) { }
}
