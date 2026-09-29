package igsl.com.jira.workflow.service;

import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import igsl.com.jira.workflow.domain.*;
import igsl.com.jira.workflow.jira.JiraGraphReader;
import igsl.com.jira.workflow.jira.DescriptorXml;
import igsl.com.jira.workflow.jira.WorkflowAppendPlanner;
import javax.inject.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Named
public class ConfigurationService {
    private final ConfigurationStore store;
    private final AdminAccess access;
    private final WorkflowManager workflows;
    private final WorkflowService nativeService;

    @Inject public ConfigurationService(ConfigurationStore store, AdminAccess access,
            @ComponentImport WorkflowManager workflows, @ComponentImport WorkflowService nativeService) {
        this.store = store; this.access = access; this.workflows = workflows; this.nativeService = nativeService;
    }

    public List<Map<String, Object>> list(int start, int limit, String query) {
        access.requireAdmin();
        if (start < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page; maximum size is 100");
        String match = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return workflows.getWorkflows().stream().filter(w -> w.getName().toLowerCase(Locale.ROOT).contains(match))
            .sorted(Comparator.comparing(JiraWorkflow::getName)).skip(start).limit(limit).map(w -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", w.getName()); row.put("active", w.isActive());
                row.put("hasDraft", w.hasDraftWorkflow()); row.put("configuration", store.get(w.getName()));
                return row;
            }).collect(Collectors.toList());
    }

    public Map<String, Object> inspect(String name) {
        access.requireAdmin();
        JiraWorkflow live = requireWorkflow(name);
        JiraWorkflow draft = workflows.getDraftWorkflow(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configuration", store.get(name)); result.put("name", name);
        JiraWorkflow editing = draft == null ? live : draft;
        result.put("statuses", editing.getLinkedStatusObjects().stream().map(status -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", status.getId()); row.put("name", status.getName()); return row;
        }).collect(Collectors.toList()));
        result.put("hasDraft", draft != null); result.put("active", live.isActive());
        result.put("fingerprint", fingerprint(live));
        WorkflowConfiguration config = store.get(name);
        if (config != null && config.parent != null) {
            WorkflowConfiguration parent = store.get(config.parent);
            result.put("parentRevision", parent == null ? null : parent.publishedRevision);
        }
        return result;
    }

    public WorkflowConfiguration saveDraft(String name, long expected, Map<String, BigDecimal> progress,
                                             Map<String, String> stages) {
        ApplicationUser user = access.requireAdmin();
        if (progress == null || stages == null) throw new IllegalArgumentException("Progress and stages must be objects, not null");
        return store.locked("management", () -> {
            JiraWorkflow live = requireWorkflow(name);
            JiraWorkflow draft = workflows.getDraftWorkflow(name);
            JiraWorkflow target = draft == null ? live : draft;
            WorkflowConfiguration config = store.get(name);
            if (config == null) { config = new WorkflowConfiguration(); config.workflow = name; }
            if (config.version != expected) throw new ConcurrentModificationException("Configuration changed; reload");
            if (config.parent == null) {
                if (stages == null || !stages.isEmpty()) throw new IllegalArgumentException("Parent workflows cannot define extension stages");
                new PublishedProgressRules(name, "draft", target.getLinkedStatusIds(), progress, Set.of(), Map.of());
                if (!target.getLinkedStatusIds().containsAll(config.inheritedStatuses)) {
                    throw new IllegalArgumentException("Parent states cannot be removed or replaced");
                }
                config.draftProgress = new LinkedHashMap<>(progress);
                config.draftStages = new LinkedHashMap<>();
            } else {
                if (progress == null || !progress.isEmpty()) throw new IllegalArgumentException("Child progress is inherited and cannot be edited");
                WorkflowConfiguration parent = requirePublished(config.parent);
                Set<String> extensions = new LinkedHashSet<>(target.getLinkedStatusIds());
                extensions.removeAll(parent.inheritedStatuses);
                new PublishedProgressRules(name, "draft", parent.inheritedStatuses,
                        parent.publishedProgress, extensions, stages);
                WorkflowGraph.validateInheritance(JiraGraphReader.read(requireWorkflow(config.parent)), JiraGraphReader.read(target));
                config.draftStages = new LinkedHashMap<>(stages);
            }
            return store.save(config, expected, user.getKey(), "SAVE_PROGRESS_DRAFT");
        });
    }

    /** Explicit progress-rule activation against the existing live descriptor. Never publishes a Jira draft. */
    public WorkflowConfiguration activateRules(String name, long expected, String expectedFingerprint) {
        ApplicationUser user = access.requireAdmin();
        return store.locked("management", () -> {
            JiraWorkflow live = requireWorkflow(name);
            if (live.hasDraftWorkflow()) throw new IllegalArgumentException("Resolve the Jira draft through the native publication process first");
            if (!fingerprint(live).equals(expectedFingerprint)) throw new ConcurrentModificationException("Live workflow changed; reload");
            WorkflowConfiguration config = store.get(name);
            if (config == null) throw new IllegalArgumentException("Save progress configuration first");
            if (config.version != expected) throw new ConcurrentModificationException("Configuration changed; reload");
            if (config.parent != null) throw new IllegalArgumentException("Child activation belongs to the synchronization process");
            if (!live.getLinkedStatusIds().containsAll(config.inheritedStatuses)) throw new IllegalArgumentException("Parent states cannot be deleted");
            new PublishedProgressRules(name, "pending", live.getLinkedStatusIds(), config.draftProgress, Set.of(), Map.of());
            boolean onlyProgressChanged = Objects.equals(config.workflowFingerprint, fingerprint(live));
            config.inheritedStatuses = new LinkedHashSet<>(live.getLinkedStatusIds());
            config.publishedProgress = new LinkedHashMap<>(config.draftProgress);
            config.publishedRevision = UUID.randomUUID().toString();
            config.workflowFingerprint = fingerprint(live);
            config.publishedDescriptor = DescriptorXml.serialize(live.getDescriptor());
            config.syncStatus = "PUBLISHED"; config.syncReason = null;
            WorkflowConfiguration saved = store.save(config, expected, user.getKey(), "ACTIVATE_PROGRESS_RULES");
            for (String childName : saved.children) {
                synchronizeProgress(saved, childName, onlyProgressChanged, user.getKey());
            }
            return saved;
        });
    }

    public WorkflowConfiguration createChild(String parentName, String childName, String description) {
        ApplicationUser user = access.requireAdmin();
        if (childName == null || childName.isBlank() || childName.length() > 255) throw new IllegalArgumentException("Child name is required (maximum 255 characters)");
        if (description != null && description.length() > 2000) throw new IllegalArgumentException("Description exceeds 2000 characters");
        return store.locked("management", () -> {
            JiraWorkflow parentWorkflow = requireWorkflow(parentName);
            WorkflowConfiguration parent = requirePublished(parentName);
            if (parent.parent != null) throw new IllegalArgumentException("Only one inheritance level is supported");
            if (childName.equals(parentName) || workflows.getWorkflow(childName) != null || store.get(childName) != null) {
                throw new IllegalArgumentException("Child workflow must be new and different from its parent");
            }
            if (!fingerprint(parentWorkflow).equals(parent.workflowFingerprint)) {
                throw new IllegalArgumentException("Parent workflow changed since its progress rules were activated");
            }
            JiraServiceContextImpl context = new JiraServiceContextImpl(user);
            nativeService.validateCopyWorkflow(context, childName);
            check(context);
            JiraWorkflow copied = nativeService.copyWorkflow(context, childName, description == null ? "" : description, parentWorkflow);
            check(context);
            if (copied == null) throw new IllegalStateException("Jira did not return a copied workflow");
            WorkflowConfiguration child = new WorkflowConfiguration();
            child.workflow = childName; child.parent = parentName;
            child.inheritedStatuses = new LinkedHashSet<>(parent.inheritedStatuses);
            child.publishedProgress = new LinkedHashMap<>(parent.publishedProgress);
            child.publishedRevision = parent.publishedRevision;
            child.parentFingerprint = parent.workflowFingerprint;
            child.parentDescriptor = parent.publishedDescriptor;
            child.publishedDescriptor = DescriptorXml.serialize(copied.getDescriptor());
            child.workflowFingerprint = fingerprint(copied); child.syncStatus = "SYNCED";
            WorkflowConfiguration saved = store.save(child, 0, user.getKey(), "CREATE_CHILD");
            parent.children.add(childName);
            store.save(parent, parent.version, user.getKey(), "REGISTER_CHILD");
            return saved;
        });
    }

    public List<Map> audit(String name, long before, int limit) {
        access.requireAdmin();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Workflow name is required");
        return store.audit(name, before, limit);
    }

    public WorkflowConfiguration retryProgressSync(String childName, long expected) {
        ApplicationUser user = access.requireAdmin();
        return store.locked("management", () -> {
            WorkflowConfiguration child = store.get(childName);
            if (child == null || child.parent == null) throw new IllegalArgumentException("Child workflow is required");
            if (child.version != expected) throw new ConcurrentModificationException("Configuration changed; reload");
            WorkflowConfiguration parent = requirePublished(child.parent);
            if (!Objects.equals(fingerprint(requireWorkflow(parent.workflow)), parent.workflowFingerprint)) {
                throw new IllegalArgumentException("Parent changed since progress activation; confirm parent first");
            }
            synchronizeProgress(parent, childName, Objects.equals(child.parentFingerprint, parent.workflowFingerprint), user.getKey());
            return store.get(childName);
        });
    }

    /** Administrator has resolved/published the Jira workflow itself; validate before adopting new rules. */
    public WorkflowConfiguration resolveChild(String childName, long expected, String childFingerprint, String parentRevision) {
        ApplicationUser user = access.requireAdmin();
        return store.locked("management", () -> {
            WorkflowConfiguration child = store.get(childName);
            if (child == null || child.parent == null) throw new IllegalArgumentException("Child workflow is required");
            if (child.version != expected) throw new ConcurrentModificationException("Child configuration changed; reload");
            WorkflowConfiguration parent = requirePublished(child.parent);
            JiraWorkflow liveParent = requireWorkflow(child.parent), liveChild = requireWorkflow(childName);
            if (!Objects.equals(parentRevision, parent.publishedRevision) || !Objects.equals(childFingerprint, fingerprint(liveChild))) {
                throw new ConcurrentModificationException("Workflow or parent revision changed; reload");
            }
            if (liveChild.hasDraftWorkflow()) throw new IllegalArgumentException("Resolve the native Jira draft first");
            if (!Objects.equals(parent.workflowFingerprint, fingerprint(liveParent))) throw new IllegalArgumentException("Activate current parent rules first");
            WorkflowGraph.validateInheritance(JiraGraphReader.read(liveParent), JiraGraphReader.read(liveChild));
            Set<String> extensions = new LinkedHashSet<>(liveChild.getLinkedStatusIds()); extensions.removeAll(parent.inheritedStatuses);
            new PublishedProgressRules(childName, parent.publishedRevision, parent.inheritedStatuses,
                    parent.publishedProgress, extensions, child.draftStages);
            child.publishedProgress = new LinkedHashMap<>(parent.publishedProgress);
            child.publishedStages = new LinkedHashMap<>(child.draftStages);
            child.inheritedStatuses = new LinkedHashSet<>(parent.inheritedStatuses);
            child.parentFingerprint = parent.workflowFingerprint; child.workflowFingerprint = fingerprint(liveChild);
            child.parentDescriptor = parent.publishedDescriptor;
            child.publishedDescriptor = DescriptorXml.serialize(liveChild.getDescriptor());
            child.publishedRevision = parent.publishedRevision; child.syncStatus = "SYNCED"; child.syncReason = null;
            return store.save(child, expected, user.getKey(), "RESOLVE_CHILD_RULES");
        });
    }

    private void synchronizeProgress(WorkflowConfiguration parent, String childName, boolean onlyProgressChanged, String actor) {
        WorkflowConfiguration child = store.get(childName);
        if (child == null || !parent.workflow.equals(child.parent)) throw new IllegalStateException("Missing or mismatched child relationship");
        JiraWorkflow live = workflows.getWorkflow(childName);
        String reason = null;
        if (live == null) reason = "Child workflow no longer exists";
        else if (live.hasDraftWorkflow()) reason = "Existing Jira draft requires administrator resolution";
        else if (!onlyProgressChanged) reason = "Parent descriptor changed; structural/configuration merge required";
        else if (!Objects.equals(fingerprint(live), child.workflowFingerprint)) reason = "Child descriptor changed; validate local changes before synchronization";
        else if (!child.draftStages.equals(child.publishedStages)) reason = "Unpublished extension-stage changes require administrator resolution";
        if (reason != null) {
            child.syncStatus = "PENDING"; child.syncReason = reason;
        } else {
            Set<String> extensions = new LinkedHashSet<>(live.getLinkedStatusIds());
            extensions.removeAll(parent.inheritedStatuses);
            try {
                new PublishedProgressRules(childName, parent.publishedRevision, parent.inheritedStatuses,
                        parent.publishedProgress, extensions, child.publishedStages);
                child.publishedProgress = new LinkedHashMap<>(parent.publishedProgress);
                child.publishedRevision = parent.publishedRevision;
                child.parentFingerprint = parent.workflowFingerprint;
                child.parentDescriptor = parent.publishedDescriptor;
                child.syncStatus = "SYNCED"; child.syncReason = null;
            } catch (IllegalArgumentException conflict) {
                child.syncStatus = "PENDING"; child.syncReason = conflict.getMessage();
            }
        }
        store.save(child, child.version, actor, "SYNC_PROGRESS_RULES");
    }

    /** Read-only plan; conflicts do not create/overwrite native drafts or change effective progress. */
    public Map<String, Object> previewSync(String childName, long expected) {
        access.requireAdmin();
        return store.locked("management", () -> {
            WorkflowConfiguration child = requirePublished(childName);
            if (child.parent == null) throw new IllegalArgumentException("Child workflow is required");
            if (child.version != expected) throw new ConcurrentModificationException("Child configuration changed; reload");
            WorkflowConfiguration parent = requirePublished(child.parent);
            JiraWorkflow live = requireWorkflow(childName), liveParent = requireWorkflow(child.parent);
            if (live.hasDraftWorkflow()) throw new IllegalArgumentException("Existing Jira draft requires administrator resolution");
            if (!Objects.equals(parent.workflowFingerprint, fingerprint(liveParent))) throw new IllegalArgumentException("Activate current parent rules first");
            if (child.parentDescriptor == null) throw new IllegalArgumentException("Previous parent descriptor is unavailable; administrator resolution is required");
            if (!child.draftStages.equals(child.publishedStages)) throw new IllegalArgumentException("Unpublished extension-stage changes require administrator resolution");
            WorkflowAppendPlanner.Plan plan = WorkflowAppendPlanner.plan(DescriptorXml.workflow(child.parentDescriptor),
                    liveParent.getDescriptor(), live.getDescriptor(), child.publishedStages);
            WorkflowGraph graph = JiraGraphReader.read(plan.descriptor());
            Set<String> extensions = new LinkedHashSet<>(graph.states()); extensions.removeAll(parent.inheritedStatuses);
            new PublishedProgressRules(childName, parent.publishedRevision, parent.inheritedStatuses,
                    parent.publishedProgress, extensions, plan.stages());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workflow", childName); result.put("version", child.version);
            result.put("parentRevision", parent.publishedRevision);
            result.put("fingerprint", fingerprint(live));
            result.put("states", graph.states()); result.put("stages", plan.stages());
            result.put("paths", graph.edges().stream().map(edge -> Map.of("id", edge.id, "from", edge.from, "to", edge.to)).collect(Collectors.toList()));
            result.put("published", false);
            return result;
        });
    }

    private WorkflowConfiguration requirePublished(String name) {
        WorkflowConfiguration config = store.get(name);
        if (config == null || config.publishedRevision == null) throw new IllegalArgumentException("Workflow has no published progress rules: " + name);
        return config;
    }
    private JiraWorkflow requireWorkflow(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Workflow name is required");
        JiraWorkflow workflow = workflows.getWorkflow(name);
        if (workflow == null || workflow.isDraftWorkflow()) throw new IllegalArgumentException("Live workflow not found: " + name);
        return workflow;
    }
    private static String fingerprint(JiraWorkflow workflow) { return ConfigurationStore.digest(workflow.getDescriptor().asXML()); }
    private static void check(JiraServiceContextImpl context) {
        if (context.getErrorCollection().hasAnyErrors()) throw new IllegalArgumentException(context.getErrorCollection().toString());
    }
}
