package igsl.com.jira.workflow.rest;

import com.google.gson.*;
import igsl.com.jira.workflow.service.*;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.*;

@Path("/recovery") @Produces("application/json; charset=UTF-8") @Named
public class RecoveryResource {
    private final AdminAccess access;
    private final ProgressJournal journal;
    private final ProgressUpdateService updater;
    private final ConfigurationStore store;
    private final Gson gson = new Gson();
    @Inject public RecoveryResource(AdminAccess access, ProgressJournal journal, ProgressUpdateService updater, ConfigurationStore store) {
        this.access = access; this.journal = journal; this.updater = updater; this.store = store;
    }

    @GET public Response list(@QueryParam("cursor") @DefaultValue("0:0") String cursor,
                             @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            access.requireAdmin();
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
            String[] parts = cursor.split(":");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
            int shard = Integer.parseInt(parts[0]); long after = Long.parseLong(parts[1]);
            if (shard < 0 || shard > 128 || after < 0) throw new IllegalArgumentException("Invalid cursor");
            List<ProgressReceipt> rows = new ArrayList<>();
            String next = null;
            for (; shard < 128; shard++, after = 0) {
                List<ProgressReceipt> items = journal.pending(shard, after, limit - rows.size());
                rows.addAll(items);
                if (rows.size() == limit) { next = shard + ":" + items.get(items.size() - 1).issueId; break; }
            }
            Map<String, Object> page = new LinkedHashMap<>(); page.put("rows", rows); page.put("nextCursor", next);
            return response(200, page);
        } catch (SecurityException denied) { return response(403, Map.of("error", denied.getMessage())); }
        catch (IllegalArgumentException invalid) { return response(400, Map.of("error", invalid.getMessage())); }
    }

    @POST @Path("/retry") @Consumes("application/json") public Response retry(@HeaderParam("X-Workflow-Admin") String header, String body) {
        try {
            String actor = access.requireAdmin().getKey();
            if (!"1".equals(header)) throw new SecurityException("Missing administration request header");
            if (body == null || body.length() > 4096) throw new IllegalArgumentException("Invalid request");
            Retry request = gson.fromJson(body, Retry.class);
            if (request == null || request.issueId <= 0 || request.transitionId <= 0) throw new IllegalArgumentException("Issue and transition IDs are required");
            ProgressReceipt receipt = journal.get(request.issueId);
            if (receipt == null || receipt.transitionId != request.transitionId) return response(409, Map.of("error", "Receipt changed; refresh before retrying"));
            store.auditOnly(receipt.workflow, actor, "RETRY_PROGRESS issue=" + request.issueId + " transition=" + request.transitionId);
            try { updater.applyCommitted(request.issueId, request.transitionId); }
            catch (RuntimeException failed) { return response(409, Map.of("error", "Recovery is still pending; refresh to view the recorded failure")); }
            return response(200, journal.get(request.issueId));
        } catch (SecurityException denied) { return response(403, Map.of("error", denied.getMessage())); }
        catch (IllegalArgumentException | JsonParseException invalid) { return response(400, Map.of("error", String.valueOf(invalid.getMessage()))); }
    }
    private Response response(int status, Object body) {
        return Response.status(status).header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").entity(gson.toJson(body)).build();
    }
    public static class Retry { public long issueId; public long transitionId; }
}
