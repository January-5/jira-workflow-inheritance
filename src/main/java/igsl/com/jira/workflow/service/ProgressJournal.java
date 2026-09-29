package igsl.com.jira.workflow.service;

import javax.inject.*;
import java.util.*;

/** One latest receipt per issue plus a sharded recovery index; no scan of all Jira issues. */
@Named
public class ProgressJournal {
    private static final int SHARDS = 128;
    private final ConfigurationStore store;
    @Inject public ProgressJournal(ConfigurationStore store) { this.store = store; }

    public ProgressReceipt get(long issueId) {
        return store.locked(scope(issueId), () -> read(issueId));
    }
    public ProgressReceipt capture(ProgressReceipt incoming) {
        if (incoming.issueId <= 0 || incoming.transitionId <= 0 || incoming.percentage == null
                || incoming.ruleRevision == null || incoming.workflow == null || incoming.targetStatus == null) {
            throw new IllegalArgumentException("A complete successful-transition receipt is required");
        }
        return store.locked(scope(incoming.issueId), () -> {
            ProgressReceipt existing = read(incoming.issueId);
            if (existing != null && existing.transitionId >= incoming.transitionId) return existing;
            store.writeEntity(key(incoming.issueId), incoming);
            index(incoming.issueId, true);
            return read(incoming.issueId);
        });
    }
    public ProgressReceipt update(long issueId, long transitionId, String state, String error) {
        return store.locked(scope(issueId), () -> {
            ProgressReceipt current = read(issueId);
            if (current == null || current.transitionId != transitionId) return current;
            current.state = state;
            current.error = error == null ? null : error.substring(0, Math.min(2000, error.length()));
            if ("FAILED".equals(state)) current.attempts++;
            store.writeEntity(key(issueId), current);
            index(issueId, !("APPLIED".equals(state) || "SUPERSEDED".equals(state)));
            return current;
        });
    }
    public List<ProgressReceipt> pending(int shard, int limit) {
        return pending(shard, 0, limit);
    }
    public List<ProgressReceipt> pending(int shard, long afterIssue, int limit) {
        if (shard < 0 || shard >= SHARDS || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid recovery page");
        List<Long> ids = store.locked("progress-index/" + shard, () -> {
            PendingIds index = store.readEntity("progress-index." + shard, PendingIds.class);
            if (index == null) return Collections.emptyList();
            List<Long> result = new ArrayList<>();
            for (Long id : new TreeSet<>(index.ids)) { if (result.size() == limit) break; if (id > afterIssue) result.add(id); }
            return result;
        });
        List<ProgressReceipt> receipts = new ArrayList<>();
        for (Long id : ids) { ProgressReceipt receipt = get(id); if (receipt != null) receipts.add(receipt); }
        return receipts;
    }
    private ProgressReceipt read(long issueId) { return store.readEntity(key(issueId), ProgressReceipt.class); }
    private void index(long issueId, boolean add) {
        int shard = (int) (issueId % SHARDS);
        store.locked("progress-index/" + shard, () -> {
            PendingIds index = store.readEntity("progress-index." + shard, PendingIds.class);
            if (index == null) index = new PendingIds();
            if (add) { index.ids.remove(issueId); index.ids.add(issueId); } else index.ids.remove(issueId);
            store.writeEntity("progress-index." + shard, index); return null;
        });
    }
    static String scope(long issueId) { return "progress-issue/" + issueId; }
    private static String key(long issueId) { return "progress-issue." + issueId; }
    private static class PendingIds { Set<Long> ids = new LinkedHashSet<>(); }
}
