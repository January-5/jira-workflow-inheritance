package igsl.com.jira.workflow.service;

import com.atlassian.beehive.ClusterLockService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.*;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.google.gson.Gson;
import javax.inject.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/** Database-backed SAL records, guarded by a cluster lock and optimistic revision checks. */
@Named
public class ConfigurationStore {
    private static final String PREFIX = "igsl.workflow.v1.";
    private static final int CHUNK_SIZE = 12000;
    private static final int MAX_CHUNKS = 256;
    private final PluginSettingsFactory settings;
    private final ClusterLockService locks;
    private final TransactionTemplate transactions;
    private final Gson gson = new Gson();

    @Inject public ConfigurationStore(@ComponentImport PluginSettingsFactory settings,
            @ComponentImport ClusterLockService locks, @ComponentImport TransactionTemplate transactions) {
        this.settings = settings; this.locks = locks; this.transactions = transactions;
    }

    public <T> T locked(String scope, Supplier<T> work) {
        return exclusive(scope, () -> transactions.execute(work::get));
    }

    /** Holds a cluster lock without wrapping external Jira mutations in a SAL transaction. */
    public <T> T exclusive(String scope, Supplier<T> work) {
        Lock lock = locks.getLockForName(PREFIX + digest(scope));
        lock.lock();
        try { return work.get(); }
        finally { lock.unlock(); }
    }

    public WorkflowConfiguration get(String name) {
        return locked(name, () -> read(name));
    }

    private WorkflowConfiguration read(String name) {
        String json = readRecord(settings.createGlobalSettings(), PREFIX + digest(name));
        return json == null ? null : gson.fromJson(json, WorkflowConfiguration.class);
    }

    public WorkflowConfiguration save(WorkflowConfiguration value, long expectedVersion, String actor, String action) {
        return locked(value.workflow, () -> {
            WorkflowConfiguration current = read(value.workflow);
            long actual = current == null ? 0 : current.version;
            if (expectedVersion != actual) throw new ConcurrentModificationException("Configuration changed; reload before saving");
            // Copy caller input; a failed transaction must not advance the caller's version.
            WorkflowConfiguration next = gson.fromJson(gson.toJson(value), WorkflowConfiguration.class);
            next.version = Math.addExact(actual, 1);
            next.auditSequence = (current == null ? 0 : current.auditSequence) + 1;
            PluginSettings global = settings.createGlobalSettings();
            String key = PREFIX + digest(value.workflow);
            writeRecord(global, key, gson.toJson(next));
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("sequence", next.auditSequence); audit.put("time", System.currentTimeMillis());
            audit.put("actor", actor); audit.put("action", action); audit.put("version", next.version);
            writeRecord(global, key + ".audit." + next.auditSequence, gson.toJson(audit));
            return next;
        });
    }

    public List<Map> audit(String name, long before, int limit) {
        if (limit < 1 || limit > 100 || before < 0) throw new IllegalArgumentException("Invalid audit page");
        return locked(name, () -> {
            WorkflowConfiguration current = read(name);
            if (current == null) return Collections.emptyList();
            long start = before == 0 ? current.auditSequence : Math.min(current.auditSequence, before - 1);
            List<Map> events = new ArrayList<>();
            PluginSettings global = settings.createGlobalSettings();
            for (long i = start; i > 0 && events.size() < limit; i--) {
                String json = readRecord(global, PREFIX + digest(name) + ".audit." + i);
                if (json != null) events.add(gson.fromJson(json, Map.class));
            }
            return events;
        });
    }

    public void auditOnly(String workflow, String actor, String action) {
        locked(workflow, () -> {
            WorkflowConfiguration current = read(workflow);
            if (current == null) throw new IllegalArgumentException("Unknown workflow configuration");
            current.auditSequence++;
            PluginSettings global = settings.createGlobalSettings();
            String key = PREFIX + digest(workflow);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("sequence", current.auditSequence); event.put("time", System.currentTimeMillis());
            event.put("actor", actor); event.put("action", action); event.put("version", current.version);
            writeRecord(global, key + ".audit." + current.auditSequence, gson.toJson(event));
            writeRecord(global, key, gson.toJson(current)); return null;
        });
    }

    private String readRecord(PluginSettings global, String key) {
        Object countValue = global.get(key + ".count");
        if (countValue == null) return null;
        int count = Integer.parseInt((String) countValue);
        if (count < 1 || count > MAX_CHUNKS) throw new IllegalStateException("Invalid stored record length");
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Object chunk = global.get(key + "." + i);
            if (!(chunk instanceof String)) throw new IllegalStateException("Incomplete stored record");
            json.append(chunk);
        }
        return json.toString();
    }

    // Internal repositories acquire their own named lock before using these methods.
    <T> T readEntity(String key, Class<T> type) {
        String value = readRecord(settings.createGlobalSettings(), PREFIX + key);
        return value == null ? null : gson.fromJson(value, type);
    }
    void writeEntity(String key, Object value) {
        writeRecord(settings.createGlobalSettings(), PREFIX + key, gson.toJson(value));
    }

    private void writeRecord(PluginSettings global, String key, String json) {
        int count = Math.max(1, (json.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (count > MAX_CHUNKS) throw new IllegalArgumentException("Workflow configuration exceeds record size limit");
        Object oldCount = global.get(key + ".count");
        for (int i = 0; i < count; i++) global.put(key + "." + i,
                json.substring(i * CHUNK_SIZE, Math.min(json.length(), (i + 1) * CHUNK_SIZE)));
        if (oldCount != null) for (int i = count; i < Integer.parseInt((String) oldCount); i++) global.remove(key + "." + i);
        global.put(key + ".count", Integer.toString(count));
    }

    public static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
