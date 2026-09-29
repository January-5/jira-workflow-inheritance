package igsl.com.jira.workflow.rest;

import com.google.gson.*;
import igsl.com.jira.workflow.service.PublicationService;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.function.Supplier;

@Named @Path("/publication") @Produces("application/json; charset=UTF-8")
public class PublicationResource {
    private final PublicationService service;
    private final Gson gson = new Gson();
    @Inject public PublicationResource(PublicationService service) { this.service = service; }
    @GET public Response status(@QueryParam("workflow") String workflow) { return result(() -> service.status(workflow)); }
    @POST @Path("/preview") @Consumes("application/json") public Response preview(@HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> { Request request = parse(header, body); return service.preview(request.workflow, request.version); });
    }
    @POST @Path("/publish") @Consumes("application/json") public Response publish(@HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> { Request request = parse(header, body); return service.publish(request.workflow, request.version,
                request.fingerprint, request.draftFingerprint, request.parentRevision); });
    }
    @POST @Path("/recover") @Consumes("application/json") public Response recover(@HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> { Request request = parse(header, body); return service.recover(request.workflow, request.attemptId); });
    }
    @POST @Path("/dismiss") @Consumes("application/json") public Response dismiss(@HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> { Request request = parse(header, body); return service.dismiss(request.workflow, request.attemptId); });
    }
    @POST @Path("/resolve") @Consumes("application/json") public Response resolve(@HeaderParam("X-Workflow-Admin") String header, String body) {
        return result(() -> { Request request = parse(header, body); return service.acknowledgeResolution(request.workflow, request.attemptId); });
    }
    private Request parse(String header, String body) {
        if (!"1".equals(header)) throw new SecurityException("Missing administration request header");
        if (body == null || body.length() > 10000) throw new IllegalArgumentException("Invalid request size");
        Request request = gson.fromJson(body, Request.class);
        if (request == null || request.workflow == null || request.workflow.isBlank() || request.workflow.length() > 255
                || request.version < 0) throw new IllegalArgumentException("Invalid workflow or version");
        return request;
    }
    private Response result(Supplier<?> work) {
        try { return response(200, work.get()); }
        catch (SecurityException error) { return response(403, Map.of("error", String.valueOf(error.getMessage()))); }
        catch (ConcurrentModificationException error) { return response(409, Map.of("error", String.valueOf(error.getMessage()))); }
        catch (IllegalArgumentException | JsonParseException error) { return response(400, Map.of("error", String.valueOf(error.getMessage()))); }
    }
    private Response response(int status, Object value) {
        return Response.status(status).header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .entity(gson.toJson(value)).build();
    }
    private static final class Request {
        String workflow, fingerprint, draftFingerprint, parentRevision, attemptId;
        long version;
    }
}
