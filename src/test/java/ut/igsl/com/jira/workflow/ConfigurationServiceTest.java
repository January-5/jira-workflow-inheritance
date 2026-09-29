package ut.igsl.com.jira.workflow;

import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import igsl.com.jira.workflow.service.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigurationServiceTest {
    private final ConfigurationStore store = new ConfigurationStoreTest().store;
    private final Map<String, JiraWorkflow> nativeWorkflows = new LinkedHashMap<>();
    private final Set<String> drafts = new HashSet<>();
    private final Map<String, WorkflowDescriptor> descriptors = new HashMap<>();
    private final ApplicationUser user = proxy(ApplicationUser.class, (p,m,a) -> m.getName().equals("getKey") ? "admin-key" : null);
    private final AdminAccess access = new AdminAccess(null, null) { @Override public ApplicationUser requireAdmin() { return user; } };
    private final WorkflowManager manager = proxy(WorkflowManager.class, (p,m,a) -> {
        switch (m.getName()) {
            case "getWorkflow": return nativeWorkflows.get(a[0]);
            case "getDraftWorkflow": return drafts.contains(a[0]) ? nativeWorkflows.get(a[0]) : null;
            case "getWorkflows": return nativeWorkflows.values();
            default: throw new UnsupportedOperationException(m.getName());
        }
    });
    private final WorkflowService nativeService = proxy(WorkflowService.class, (p,m,a) -> {
        if (m.getName().equals("validateCopyWorkflow")) return null;
        if (m.getName().equals("copyWorkflow")) return add((String) a[1]);
        throw new UnsupportedOperationException(m.getName());
    });
    private final ConfigurationService service = new ConfigurationService(store, access, manager, nativeService);
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private JiraWorkflow add(String name) {
        descriptors.put(name, JiraGraphReaderTest.descriptor());
        JiraWorkflow workflow = proxy(JiraWorkflow.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getName": return name;
                case "getLinkedStatusIds": return Set.of("s1", "s2", "s3");
                case "getDescriptor": return descriptors.get(name);
                case "getLinkedStatusId": return "s" + ((com.opensymphony.workflow.loader.StepDescriptor) a[0]).getId();
                case "hasDraftWorkflow": return drafts.contains(name);
                case "isDraftWorkflow": case "isActive": return false;
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
        nativeWorkflows.put(name, workflow); return workflow;
    }
    private WorkflowConfiguration parent() {
        add("parent");
        WorkflowConfiguration saved = service.saveDraft("parent", 0, values(60), Map.of());
        return service.activateRules("parent", saved.version, ConfigurationStore.digest(descriptors.get("parent").asXML()));
    }
    private Map<String, BigDecimal> values(int middle) {
        return Map.of("s1", BigDecimal.ZERO, "s2", BigDecimal.valueOf(middle), "s3", BigDecimal.valueOf(100));
    }
    @Test public void createsIndependentChildWithParentSnapshot() {
        parent(); WorkflowConfiguration child = service.createChild("parent", "child", "description");
        assertEquals("parent", child.parent); assertEquals("SYNCED", child.syncStatus);
        assertEquals(BigDecimal.valueOf(60), child.publishedProgress.get("s2"));
        assertTrue(store.get("parent").children.contains("child"));
    }
    @Test public void draftDoesNotReplacePublishedValues() {
        WorkflowConfiguration published = parent();
        WorkflowConfiguration saved = service.saveDraft("parent", published.version, values(70), Map.of());
        assertEquals(BigDecimal.valueOf(60), saved.publishedProgress.get("s2"));
        assertEquals(BigDecimal.valueOf(70), saved.draftProgress.get("s2"));
    }
    @Test public void progressSyncPreservesConflictedChildAndUpdatesOtherChild() {
        parent(); service.createChild("parent", "old-child", ""); service.createChild("parent", "new-child", "");
        drafts.add("old-child");
        WorkflowConfiguration saved = service.saveDraft("parent", store.get("parent").version, values(70), Map.of());
        service.activateRules("parent", saved.version, ConfigurationStore.digest(descriptors.get("parent").asXML()));
        assertEquals("PENDING", store.get("old-child").syncStatus);
        assertEquals(BigDecimal.valueOf(60), store.get("old-child").publishedProgress.get("s2"));
        assertEquals(BigDecimal.valueOf(70), store.get("new-child").publishedProgress.get("s2"));
        drafts.remove("old-child");
        WorkflowConfiguration retry = service.retryProgressSync("old-child", store.get("old-child").version);
        assertEquals("SYNCED", retry.syncStatus); assertEquals(BigDecimal.valueOf(70), retry.publishedProgress.get("s2"));
        service.retryProgressSync("old-child", retry.version);
        assertEquals(BigDecimal.valueOf(70), store.get("old-child").publishedProgress.get("s2"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsGrandchildCreation() {
        parent(); service.createChild("parent", "child", ""); service.createChild("child", "grandchild", "");
    }
    @Test(expected = IllegalArgumentException.class) public void willNotPublishAdministratorJiraDraft() {
        WorkflowConfiguration published = parent(); drafts.add("parent");
        service.activateRules("parent", published.version, published.workflowFingerprint);
    }
    @Test(expected = ConcurrentModificationException.class) public void rejectsStaleWorkflowFingerprint() {
        WorkflowConfiguration published = parent(); service.activateRules("parent", published.version, "old-fingerprint");
    }
    @Test(expected = SecurityException.class) public void nonAdminCannotSaveEvenValidConfig() {
        AdminAccess denied = new AdminAccess(null, null) { @Override public ApplicationUser requireAdmin() { throw new SecurityException("denied"); } };
        new ConfigurationService(store, denied, manager, nativeService).saveDraft("parent", 0, values(60), Map.of());
    }
    @Test public void resolvingLocalConfigurationChangeKeepsItAndAdoptsParentRules() {
        WorkflowConfiguration parent = parent();
        WorkflowConfiguration child = service.createChild("parent", "child", "");
        descriptors.get("child").getMetaAttributes().put("local", "preserved");
        WorkflowConfiguration resolved = service.resolveChild("child", child.version,
                ConfigurationStore.digest(descriptors.get("child").asXML()), parent.publishedRevision);
        assertEquals("SYNCED", resolved.syncStatus);
        assertEquals("preserved", descriptors.get("child").getMetaAttributes().get("local"));
    }
    @Test(expected = ConcurrentModificationException.class) public void resolutionRefusesStaleParentVersion() {
        parent(); WorkflowConfiguration child = service.createChild("parent", "child", "");
        service.resolveChild("child", child.version, child.workflowFingerprint, "old-parent-version");
    }
    @Test public void synchronizationPreviewDoesNotPublishOrChangeConfiguration() {
        parent(); WorkflowConfiguration child = service.createChild("parent", "child", "");
        String descriptor = descriptors.get("child").asXML();
        Map<String, Object> preview = service.previewSync("child", child.version);
        assertEquals(false, preview.get("published"));
        assertEquals(child.version, store.get("child").version);
        assertEquals(descriptor, descriptors.get("child").asXML());
        assertNotNull(store.get("child").parentDescriptor);
    }
    @Test(expected = IllegalArgumentException.class) public void synchronizationPreviewPreservesAdministratorDraft() {
        parent(); WorkflowConfiguration child = service.createChild("parent", "child", ""); drafts.add("child");
        service.previewSync("child", child.version);
    }
    @Test(expected = ConcurrentModificationException.class) public void synchronizationPreviewRefusesStaleVersion() {
        parent(); WorkflowConfiguration child = service.createChild("parent", "child", "");
        service.previewSync("child", child.version - 1);
    }
}
