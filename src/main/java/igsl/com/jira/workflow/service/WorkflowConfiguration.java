package igsl.com.jira.workflow.service;

import java.math.BigDecimal;
import java.util.*;

/** Persisted plugin configuration; never a replacement for a Jira workflow descriptor. */
public final class WorkflowConfiguration {
    public String workflow;
    public String parent;
    public Set<String> children = new LinkedHashSet<>();
    public long version;
    public Map<String, BigDecimal> draftProgress = new LinkedHashMap<>();
    public Map<String, String> draftStages = new LinkedHashMap<>();
    public Map<String, BigDecimal> publishedProgress = new LinkedHashMap<>();
    public Map<String, String> publishedStages = new LinkedHashMap<>();
    public Set<String> inheritedStatuses = new LinkedHashSet<>();
    public String publishedRevision;
    public String workflowFingerprint;
    public String parentFingerprint;
    public String publishedDescriptor;
    public String parentDescriptor;
    public String syncStatus = "UNPUBLISHED";
    public String syncReason;
    public long auditSequence;
}
