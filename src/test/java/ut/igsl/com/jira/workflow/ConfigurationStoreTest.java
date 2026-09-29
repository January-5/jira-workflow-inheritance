package ut.igsl.com.jira.workflow;

import com.atlassian.beehive.*;
import com.atlassian.sal.api.pluginsettings.*;
import com.atlassian.sal.api.transaction.*;
import igsl.com.jira.workflow.service.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;

public class ConfigurationStoreTest {
    private final Map<String, Object> data = new HashMap<>();
    private final PluginSettings settings = new PluginSettings() {
        public Object get(String key) { return data.get(key); }
        public Object put(String key, Object value) {
            if (value instanceof String && ((String) value).length() > 12000) throw new AssertionError("Unchunked record");
            return data.put(key, value);
        }
        public Object remove(String key) { return data.remove(key); }
    };
    private static class TestLock extends ReentrantLock implements ClusterLock { }
    private final Map<String, TestLock> locks = new HashMap<>();
    final ConfigurationStore store = new ConfigurationStore(new PluginSettingsFactory() {
        public PluginSettings createGlobalSettings() { return settings; }
        public PluginSettings createSettingsForKey(String key) { return settings; }
    }, name -> locks.computeIfAbsent(name, ignored -> new TestLock()), new TransactionTemplate() {
        public <T> T execute(TransactionCallback<T> callback) {
            Map<String, Object> snapshot = new HashMap<>(data);
            try { return callback.doInTransaction(); }
            catch (RuntimeException error) { data.clear(); data.putAll(snapshot); throw error; }
        }
    });
    private WorkflowConfiguration config() {
        WorkflowConfiguration value = new WorkflowConfiguration(); value.workflow = "测试流程"; return value;
    }
    @Test public void persistsCopyAndAudit() {
        WorkflowConfiguration input = config();
        WorkflowConfiguration saved = store.save(input, 0, "admin-key", "CREATE");
        assertEquals(0, input.version); assertEquals(1, saved.version);
        input.parent = "unexpected";
        assertNull(store.get(input.workflow).parent);
        assertEquals("CREATE", store.audit(input.workflow, 0, 10).get(0).get("action"));
    }
    @Test(expected = ConcurrentModificationException.class) public void rejectsStaleWrite() {
        store.save(config(), 0, "admin", "FIRST"); store.save(config(), 0, "admin", "STALE");
    }
    @Test public void staleWriteDoesNotAppendAuditOrChangeValue() {
        store.save(config(), 0, "admin", "FIRST");
        try { store.save(config(), 0, "admin", "STALE"); fail(); }
        catch (ConcurrentModificationException expected) { }
        assertEquals(1, store.audit("测试流程", 0, 10).size());
        assertEquals(1, store.get("测试流程").version);
    }
    @Test public void roundTripsLargeConfigAndRemovesObsoleteChunks() {
        WorkflowConfiguration input = config(); input.syncReason = "大".repeat(25000);
        WorkflowConfiguration saved = store.save(input, 0, "admin", "LARGE");
        assertEquals(input.syncReason, store.get(input.workflow).syncReason);
        int before = data.size(); saved.syncReason = "small";
        store.save(saved, saved.version, "admin", "SMALL");
        assertEquals("small", store.get(input.workflow).syncReason);
        assertTrue(data.size() <= before + 2);
    }
    @Test public void auditPaginationReturnsOlderEvents() {
        WorkflowConfiguration saved = store.save(config(), 0, "admin", "FIRST");
        store.save(saved, 1, "admin", "SECOND");
        assertEquals("FIRST", store.audit(saved.workflow, 2, 1).get(0).get("action"));
    }
}
