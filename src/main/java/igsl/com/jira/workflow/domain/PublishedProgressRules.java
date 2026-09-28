package igsl.com.jira.workflow.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable snapshot of the rules actually published for one workflow. No draft fallback. */
public final class PublishedProgressRules {
    private final String workflowName;
    private final String revision;
    private final Map<String, BigDecimal> parentProgress;
    private final Map<String, String> extensionStages;

    public PublishedProgressRules(String workflowName, String revision, Set<String> parentStatusIds,
                                  Map<String, BigDecimal> parentProgress,
                                  Set<String> extensionStatusIds, Map<String, String> extensionStages) {
        this.workflowName = InheritanceRules.requireName(workflowName);
        this.revision = InheritanceRules.requireName(revision);
        Objects.requireNonNull(parentStatusIds, "parentStatusIds");
        Objects.requireNonNull(parentProgress, "parentProgress");
        Objects.requireNonNull(extensionStatusIds, "extensionStatusIds");
        Objects.requireNonNull(extensionStages, "extensionStages");
        if (parentStatusIds.isEmpty() || !parentStatusIds.equals(parentProgress.keySet())) {
            throw new IllegalArgumentException("Every parent status must have exactly one progress value");
        }
        if (!extensionStatusIds.equals(extensionStages.keySet())) {
            throw new IllegalArgumentException("Every extension status must have an explicit parent stage");
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>(parentProgress);
        values.forEach((status, value) -> {
            InheritanceRules.requireName(status);
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("Progress must be configured between 0 and 100: " + status);
            }
        });
        Map<String, String> stages = new LinkedHashMap<>(extensionStages);
        stages.forEach((extension, stage) -> {
            InheritanceRules.requireName(extension);
            InheritanceRules.requireName(stage);
            if (values.containsKey(extension) || !values.containsKey(stage)) {
                throw new IllegalArgumentException("Extension must reference a parent status: " + extension);
            }
        });
        this.parentProgress = Collections.unmodifiableMap(values);
        this.extensionStages = Collections.unmodifiableMap(stages);
    }

    public String getWorkflowName() { return workflowName; }
    public String getRevision() { return revision; }

    public BigDecimal progressAt(String statusId) {
        InheritanceRules.requireName(statusId);
        String stage = extensionStages.getOrDefault(statusId, statusId);
        BigDecimal progress = parentProgress.get(stage);
        if (progress == null) {
            throw new IllegalArgumentException("Status has no published progress rule: " + statusId);
        }
        return progress;
    }
}
