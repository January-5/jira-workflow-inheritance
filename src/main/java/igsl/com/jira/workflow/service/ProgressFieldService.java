package igsl.com.jira.workflow.service;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.context.GlobalIssueContext;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import igsl.com.jira.workflow.jira.ProgressFieldType;
import javax.inject.*;
import java.util.*;
import java.util.stream.Collectors;

@Named
public class ProgressFieldService {
    private final CustomFieldManager fields;
    private final AdminAccess access;
    private final ConfigurationStore store;
    @Inject public ProgressFieldService(@ComponentImport CustomFieldManager fields, AdminAccess access, ConfigurationStore store) {
        this.fields = fields; this.access = access; this.store = store;
    }
    public List<Map<String, String>> ensureField() {
        access.requireAdmin();
        return store.locked("field-setup", () -> {
            List<CustomField> existing = allFields();
            if (existing.isEmpty()) {
                var type = fields.getCustomFieldType(ProgressFieldType.KEY);
                var searcher = fields.getCustomFieldSearcher("igsl.com.jira-workflow-inheritance:progress-searcher");
                if (type == null || searcher == null) {
                    throw new IllegalStateException("Progress field type or searcher module is not available; check plugin enablement");
                }
                try {
                    existing = List.of(fields.createCustomField("工作流进度", "系统维护的累计百分比；配置发布不重算已有 Issue。",
                            type, searcher,
                            List.of(GlobalIssueContext.getInstance()), Collections.singletonList(null)));
                } catch (org.ofbiz.core.entity.GenericEntityException error) { throw new IllegalStateException("Cannot create Jira progress field", error); }
            }
            return existing.stream().map(field -> Map.of("id", field.getId(), "name", field.getName(),
                    "jql", "cf[" + field.getIdAsLong() + "] >= 60.25")).collect(Collectors.toList());
        });
    }
    public List<CustomField> forIssue(Issue issue) {
        return fields.getCustomFieldObjects(issue).stream().filter(this::isProgress).collect(Collectors.toList());
    }
    private List<CustomField> allFields() { return fields.getCustomFieldObjects().stream().filter(this::isProgress).collect(Collectors.toList()); }
    private boolean isProgress(CustomField field) { return ProgressFieldType.KEY.equals(field.getCustomFieldType().getKey()); }
}
