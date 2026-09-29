package ut.igsl.com.jira.workflow;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import com.opensymphony.workflow.loader.*;
import igsl.com.jira.workflow.jira.DescriptorXml;
import igsl.com.jira.workflow.service.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class PublicationServiceTest {
    private final ConfigurationStore store = new ConfigurationStoreTest().store;
    private final Map<String, WorkflowDescriptor> live = new HashMap<>(), drafts = new HashMap<>();
    private boolean nativeValidationFails, nativeThrowsBefore, nativeThrowsAfter, failRuleCommit, failAfterRuleCommit, publishWrongDraft;
    private int nativePublications;
    private final ApplicationUser user = proxy(ApplicationUser.class, (p,m,a) -> m.getName().equals("getKey") ? "admin" : null);
    private final AdminAccess access = new AdminAccess(null, null) { @Override public ApplicationUser requireAdmin() { return user; } };
    private final WorkflowManager manager = proxy(WorkflowManager.class, (p,m,a) -> {
        if (m.getName().equals("getWorkflow")) return workflow((String) a[0], false);
        if (m.getName().equals("getDraftWorkflow")) return workflow((String) a[0], true);
        throw new UnsupportedOperationException(m.getName());
    });
    private final WorkflowService nativeService = proxy(WorkflowService.class, (p,m,a) -> {
        if (m.getName().equals("validateOverwriteWorkflow")) {
            if (nativeValidationFails) ((JiraServiceContext) a[0]).getErrorCollection().addErrorMessage("Native migration required");
            return null;
        }
        if (m.getName().equals("overwriteActiveWorkflow")) {
            nativePublications++;
            if (nativeThrowsBefore) throw new IllegalStateException("Native unavailable");
            String name = (String) a[1];
            WorkflowDescriptor published = DescriptorXml.workflow(drafts.remove(name));
            published.getMetaAttributes().put(JiraWorkflow.JIRA_META_UPDATED_DATE, "publication-time");
            if (publishWrongDraft) published.getMetaAttributes().put("unexpected", "changed");
            live.put(name, published);
            if (nativeThrowsAfter) throw new IllegalStateException("Response failed after native publication");
            return null;
        }
        throw new UnsupportedOperationException(m.getName());
    });
    private final ConfigurationService configurations = new ConfigurationService(store, access, manager, nativeService) {
        @Override public WorkflowConfiguration activateRules(String name, long version, String fingerprint) {
            if (failRuleCommit) throw new IllegalStateException("Settings temporarily unavailable");
            WorkflowConfiguration result = super.activateRules(name, version, fingerprint);
            if (failAfterRuleCommit) throw new IllegalStateException("Failure before settings transaction completes");
            return result;
        }
    };
    private final PublicationService publications = new PublicationService(store, access, manager, nativeService, configurations);
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private JiraWorkflow workflow(String name, boolean draft) {
        WorkflowDescriptor descriptor = (draft ? drafts : live).get(name);
        if (descriptor == null) return null;
        return proxy(JiraWorkflow.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getName": return name;
                case "getDescriptor": return descriptor;
                case "isDraftWorkflow": return draft;
                case "isActive": return true;
                case "hasDraftWorkflow": return drafts.containsKey(name);
                case "getLinkedStatusId": return ((StepDescriptor) a[0]).getMetaAttributes().get(JiraWorkflow.STEP_STATUS_KEY);
                case "getLinkedStatusIds":
                    Set<String> ids = new LinkedHashSet<>();
                    for (Object item : descriptor.getSteps()) ids.add((String) ((StepDescriptor) item).getMetaAttributes().get(JiraWorkflow.STEP_STATUS_KEY));
                    return ids;
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
    }
    private WorkflowConfiguration setup() {
        live.put("parent", JiraGraphReaderTest.descriptor());
        drafts.put("parent", DescriptorXml.workflow(live.get("parent")));
        WorkflowConfiguration config = new WorkflowConfiguration(); config.workflow = "parent";
        config.inheritedStatuses = Set.of("s1", "s2", "s3");
        config.draftProgress = Map.of("s1", BigDecimal.ZERO, "s2", BigDecimal.valueOf(70), "s3", BigDecimal.valueOf(100));
        config.publishedProgress = Map.of("s1", BigDecimal.ZERO, "s2", BigDecimal.valueOf(60), "s3", BigDecimal.valueOf(100));
        config.publishedRevision = "old"; config.workflowFingerprint = ConfigurationStore.digest(live.get("parent").asXML());
        config.publishedDescriptor = DescriptorXml.serialize(live.get("parent"));
        return store.save(config, 0, "admin", "SETUP");
    }
    private WorkflowConfiguration publish(Map<String, Object> preview) {
        return publications.publish((String) preview.get("workflow"), ((Number) preview.get("version")).longValue(),
                (String) preview.get("fingerprint"), (String) preview.get("draftFingerprint"), (String) preview.get("parentRevision"));
    }
    @Test public void nativePublicationPrecedesRuleActivationAndRecordsSuccess() {
        WorkflowConfiguration config = setup(); Map<String, Object> preview = publications.preview("parent", config.version);
        assertEquals(0, nativePublications); assertEquals(BigDecimal.valueOf(60), store.get("parent").publishedProgress.get("s2"));
        WorkflowConfiguration applied = publish(preview);
        assertEquals(1, nativePublications); assertEquals(BigDecimal.valueOf(70), applied.publishedProgress.get("s2"));
        assertFalse(drafts.containsKey("parent")); assertEquals("APPLIED", publications.status("parent").state);
    }
    @Test public void changedDraftInvalidatesPreviouslyReviewedPublication() {
        WorkflowConfiguration config = setup(); var preview = publications.preview("parent", config.version);
        drafts.get("parent").getMetaAttributes().put("edited", "later");
        try { publish(preview); fail(); } catch (ConcurrentModificationException expected) { }
        assertEquals(0, nativePublications); assertNull(publications.status("parent"));
    }
    @Test public void nativeValidationFailureDoesNotCreateAttemptOrPublish() {
        WorkflowConfiguration config = setup(); nativeValidationFails = true;
        try { publications.preview("parent", config.version); fail(); } catch (IllegalArgumentException expected) { }
        assertNull(publications.status("parent")); assertEquals(0, nativePublications);
    }
    @Test public void deletingInheritedParentStatusIsRejectedBeforePublication() {
        WorkflowConfiguration config = setup(); drafts.get("parent").getSteps().remove(2);
        try { publications.preview("parent", config.version); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, nativePublications);
    }
    @Test public void recoveryAdoptsMatchingLiveWorkflowWithoutRepublishing() {
        WorkflowConfiguration config = setup(); failRuleCommit = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        PublicationAttempt attempt = publications.status("parent"); assertEquals("PENDING", attempt.state);
        assertEquals(BigDecimal.valueOf(60), store.get("parent").publishedProgress.get("s2"));
        failRuleCommit = false; publications.recover("parent", attempt.id); publications.recover("parent", attempt.id);
        assertEquals(1, nativePublications); assertEquals("APPLIED", publications.status("parent").state);
    }
    @Test public void nativeResponseFailureAfterPublicationCanRecover() {
        WorkflowConfiguration config = setup(); nativeThrowsAfter = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        publications.recover("parent", publications.status("parent").id);
        assertEquals(1, nativePublications); assertEquals("APPLIED", publications.status("parent").state);
    }
    @Test public void failedUnpublishedAttemptCanBeDismissedWithoutDeletingDraft() {
        WorkflowConfiguration config = setup(); nativeThrowsBefore = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        String attemptId = publications.status("parent").id;
        try { publications.recover("parent", attemptId); fail(); } catch (IllegalArgumentException expected) { }
        publications.dismiss("parent", attemptId); assertTrue(drafts.containsKey("parent"));
        nativeThrowsBefore = false; publish(publications.preview("parent", config.version));
        assertEquals("APPLIED", publications.status("parent").state);
    }
    @Test public void unexpectedNativePublishedContentKeepsOldRules() {
        WorkflowConfiguration config = setup(); publishWrongDraft = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("PENDING", publications.status("parent").state);
        assertEquals(BigDecimal.valueOf(60), store.get("parent").publishedProgress.get("s2"));
        try { publications.dismiss("parent", publications.status("parent").id); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void changedProgressDraftCannotBeAdoptedByOldRecovery() {
        WorkflowConfiguration config = setup(); failRuleCommit = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        WorkflowConfiguration edited = store.get("parent"); store.save(edited, edited.version, "admin", "EDIT"); failRuleCommit = false;
        try { publications.recover("parent", publications.status("parent").id); fail(); } catch (ConcurrentModificationException expected) { }
        assertEquals(1, nativePublications);
    }
    @Test public void unauthorizedUserCannotPreviewOrRecover() {
        AdminAccess denied = new AdminAccess(null, null) { @Override public ApplicationUser requireAdmin() { throw new SecurityException("denied"); } };
        PublicationService restricted = new PublicationService(store, denied, manager, nativeService, configurations);
        try { restricted.preview("parent", 0); fail(); } catch (SecurityException expected) { }
        try { restricted.recover("parent", "id"); fail(); } catch (SecurityException expected) { }
        assertEquals(0, nativePublications);
    }
    @Test public void failedSettingsTransactionDoesNotLeaveActivatedRules() {
        WorkflowConfiguration config = setup(); failAfterRuleCommit = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        assertEquals(config.version, store.get("parent").version);
        assertEquals(BigDecimal.valueOf(60), store.get("parent").publishedProgress.get("s2"));
        failAfterRuleCommit = false; publications.recover("parent", publications.status("parent").id);
        assertEquals("APPLIED", publications.status("parent").state);
    }
    @Test public void manuallyReconciledPublicationCanBeClosedWithoutReplayingAnything() {
        WorkflowConfiguration config = setup(); failRuleCommit = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        String id = publications.status("parent").id;
        try { publications.acknowledgeResolution("parent", id); fail(); } catch (IllegalArgumentException expected) { }
        failRuleCommit = false;
        configurations.activateRules("parent", config.version, ConfigurationStore.digest(live.get("parent").asXML()));
        long resolvedVersion = store.get("parent").version;
        assertEquals("RESOLVED", publications.acknowledgeResolution("parent", id).state);
        assertEquals(resolvedVersion, store.get("parent").version); assertEquals(1, nativePublications);
        try { publications.recover("parent", id); fail(); } catch (IllegalArgumentException expected) { }
    }
    private WorkflowConfiguration child() {
        setup();
        live.put("child", DescriptorXml.workflow(live.get("parent")));
        drafts.put("child", DescriptorXml.workflow(live.get("child")));
        WorkflowConfiguration config = new WorkflowConfiguration(); config.workflow = "child"; config.parent = "parent";
        config.inheritedStatuses = Set.of("s1", "s2", "s3");
        config.publishedProgress = new LinkedHashMap<>(store.get("parent").publishedProgress);
        config.publishedRevision = "old";
        config.workflowFingerprint = ConfigurationStore.digest(live.get("child").asXML());
        return store.save(config, 0, "admin", "CREATE_CHILD");
    }
    @Test public void childPublicationRetainsLocalBusinessProperties() {
        WorkflowConfiguration child = child(); drafts.get("child").getMetaAttributes().put("local", "keep");
        WorkflowConfiguration result = publish(publications.preview("child", child.version));
        assertEquals("SYNCED", result.syncStatus); assertEquals("keep", live.get("child").getMetaAttributes().get("local"));
        assertEquals("old", result.publishedRevision);
    }
    @Test public void childCannotPublishRemovalOfInheritedNode() {
        WorkflowConfiguration child = child(); drafts.get("child").getSteps().remove(2);
        try { publications.preview("child", child.version); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, nativePublications);
    }
    @Test public void childCannotPublishShortcutBetweenParentNodes() {
        WorkflowConfiguration child = child();
        live.get("parent").getStep(1).getActions().add(JiraGraphReaderTest.action(10, 2));
        live.get("parent").getStep(2).getActions().add(JiraGraphReaderTest.action(20, 3));
        WorkflowConfiguration parent = store.get("parent"); parent.workflowFingerprint = ConfigurationStore.digest(live.get("parent").asXML());
        store.save(parent, parent.version, "admin", "PARENT_UPDATED");
        drafts.put("child", DescriptorXml.workflow(live.get("parent")));
        drafts.get("child").getStep(1).getActions().add(JiraGraphReaderTest.action(30, 3));
        try { publications.preview("child", child.version); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, nativePublications);
    }
    @Test public void changedParentRevisionInvalidatesChildPreview() {
        WorkflowConfiguration child = child(); var preview = publications.preview("child", child.version);
        WorkflowConfiguration parent = store.get("parent"); parent.publishedRevision = "new";
        store.save(parent, parent.version, "admin", "PARENT_UPDATED");
        try { publish(preview); fail(); } catch (ConcurrentModificationException expected) { }
        assertEquals(0, nativePublications);
    }
    @Test public void nativePublicationMetadataDoesNotCreateFalseChildStructuralConflict() {
        WorkflowConfiguration child = child(); drafts.remove("child");
        WorkflowConfiguration parent = store.get("parent"); parent.children.add("child");
        parent = store.save(parent, parent.version, "admin", "REGISTER_CHILD");
        publish(publications.preview("parent", parent.version));
        assertEquals("SYNCED", store.get("child").syncStatus);
        assertEquals(BigDecimal.valueOf(70), store.get("child").publishedProgress.get("s2"));
        assertEquals(store.get("parent").publishedRevision, store.get("child").publishedRevision);
    }
    @Test public void pendingPublicationCannotBeRepeatedAsANewAttempt() {
        WorkflowConfiguration config = setup(); nativeThrowsBefore = true;
        var preview = publications.preview("parent", config.version);
        try { publish(preview); fail(); } catch (IllegalStateException expected) { }
        try { publish(preview); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(1, nativePublications);
    }
    @Test public void recoveryRejectsStaleAttemptIdentity() {
        WorkflowConfiguration config = setup(); nativeThrowsAfter = true;
        try { publish(publications.preview("parent", config.version)); fail(); } catch (IllegalStateException expected) { }
        try { publications.recover("parent", "another-attempt"); fail(); } catch (ConcurrentModificationException expected) { }
        assertEquals(1, nativePublications);
    }
    @Test public void firstRegistrationStillProtectsExistingPublishedParentStates() {
        WorkflowConfiguration config = setup(); config.inheritedStatuses = Set.of();
        config = store.save(config, config.version, "admin", "FIRST_REGISTRATION");
        drafts.get("parent").getSteps().remove(2);
        try { publications.preview("parent", config.version); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, nativePublications);
    }
}
