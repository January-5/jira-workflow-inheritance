package igsl.com.jira.workflow.rest;

import com.google.gson.*;
import igsl.com.jira.workflow.service.*;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.io.*;
import java.nio.charset.StandardCharsets;

@Path("/admin")
@Produces("application/json; charset=UTF-8")
@Named
public class AdminResource {
    private final ConfigurationService service;
    private final ProgressFieldService fields;
    private final Gson gson = new Gson();
    @Inject public AdminResource(ConfigurationService service, ProgressFieldService fields) { this.service = service; this.fields = fields; }

    @GET @Path("/console") @Produces("text/html; charset=UTF-8") public Response console() {
        service.list(0, 1, ""); // Same server-side Jira administrator check as the data endpoints.
        return asset("admin.html", "text/html; charset=UTF-8");
    }
    @GET @Path("/console.js") @Produces("application/javascript") public Response script() {
        return asset("admin.js", "application/javascript; charset=UTF-8");
    }
    @GET @Path("/console.css") @Produces("text/css") public Response style() {
        return asset("admin.css", "text/css; charset=UTF-8");
    }

    @GET @Path("/workflows") public Response list(@QueryParam("start") @DefaultValue("0") int start,
            @QueryParam("limit") @DefaultValue("50") int limit, @QueryParam("q") String query) {
        return result(() -> service.list(start, limit, query));
    }
    @GET @Path("/configuration") public Response inspect(@QueryParam("workflow") String name) {
        return result(() -> service.inspect(name));
    }
    @GET @Path("/audit") public Response audit(@QueryParam("workflow") String name,
            @QueryParam("before") @DefaultValue("0") long before,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return result(() -> service.audit(name, before, limit));
    }
    @POST @Path("/draft") @Consumes("application/json") public Response draft(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.saveDraft(request.workflow, request.version, request.progress, request.stages);
        });
    }
    @POST @Path("/activate") @Consumes("application/json") public Response activate(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.activateRules(request.workflow, request.version, request.fingerprint);
        });
    }
    @POST @Path("/children") @Consumes("application/json") public Response createChild(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.createChild(request.workflow, request.child, request.description);
        });
    }
    @POST @Path("/retry") @Consumes("application/json") public Response retry(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.retryProgressSync(request.workflow, request.version);
        });
    }
    @POST @Path("/preview-sync") @Consumes("application/json") public Response previewSync(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.previewSync(request.workflow, request.version);
        });
    }
    @POST @Path("/resolve") @Consumes("application/json") public Response resolve(
            @HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> {
            Request request = parse(header, body);
            return service.resolveChild(request.workflow, request.version, request.fingerprint, request.parentRevision);
        });
    }
    @POST @Path("/field") @Consumes("application/json") public Response field(@HeaderParam("X-Workflow-Admin") String header) {
        return result(() -> {
            if (!"1".equals(header)) throw new SecurityException("Missing administration request header");
            return fields.ensureField();
        });
    }
    private Response asset(String file, String type) {
        try (InputStream stream = getClass().getResourceAsStream("/admin/" + file)) {
            if (stream == null) return Response.status(404).build();
            return Response.ok(new String(stream.readAllBytes(), StandardCharsets.UTF_8), type)
                    .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'")
                    .build();
        } catch (IOException error) { throw new IllegalStateException("Administration resource unavailable", error); }
    }
    private Request parse(String header, String body) {
        // Non-simple same-origin request, in addition to Jira REST's own CSRF protection.
        if (!"1".equals(header)) throw new SecurityException("Missing administration request header");
        if (body == null || body.length() > 1_000_000) throw new IllegalArgumentException("Invalid request size");
        Request request = gson.fromJson(body, Request.class);
        if (request == null || request.workflow == null || request.workflow.isBlank()
                || request.workflow.length() > 255 || request.version < 0) throw new IllegalArgumentException("Invalid workflow or version");
        return request;
    }
    private Response result(Supplier<?> operation) {
        try { return response(200, operation.get()); }
        catch (SecurityException error) { return response(403, Map.of("error", error.getMessage())); }
        catch (ConcurrentModificationException error) { return response(409, Map.of("error", error.getMessage())); }
        catch (IllegalArgumentException | JsonParseException error) { return response(400, Map.of("error", String.valueOf(error.getMessage()))); }
    }
    private Response response(int status, Object value) {
        return Response.status(status).header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").entity(gson.toJson(value)).build();
    }
    public static final class Request {
        public String workflow;
        public long version;
        public String fingerprint;
        public String parentRevision;
        public String child;
        public String description;
        public Map<String, BigDecimal> progress = new LinkedHashMap<>();
        public Map<String, String> stages = new LinkedHashMap<>();
    }
}
