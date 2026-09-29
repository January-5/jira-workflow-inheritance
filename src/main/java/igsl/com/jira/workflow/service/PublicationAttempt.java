package igsl.com.jira.workflow.service;

/** Durable recovery evidence; native publication and plugin settings are separate boundaries. */
public final class PublicationAttempt {
    public String id;
    public String workflow;
    public String actor;
    public long configurationVersion;
    public String parentRevision;
    public String liveFingerprint;
    public String draftFingerprint;
    public String targetContentFingerprint;
    public String state;
    public String error;
    public long updatedAt;
}
