package igsl.com.jira.workflow.jira;

import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.context.IssueContext;
import com.atlassian.jira.issue.customfields.converters.DoubleConverter;
import com.atlassian.jira.issue.customfields.impl.NumberCFType;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.persistence.CustomFieldValuePersister;
import com.atlassian.jira.issue.customfields.view.CustomFieldParams;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.rest.*;
import com.atlassian.jira.util.*;
import com.atlassian.jira.web.bean.BulkEditBean;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.*;

/** Persisted number, not a calculated field: reads, exports and indexing never recalculate progress. */
public class ProgressFieldType extends NumberCFType {
    public static final String KEY = "igsl.com.jira-workflow-inheritance:progress-field";
    private static final String READ_ONLY = "Workflow progress is maintained by the system";

    @Inject public ProgressFieldType(@ComponentImport CustomFieldValuePersister persister,
            @ComponentImport DoubleConverter converter, @ComponentImport GenericConfigManager configManager) {
        super(persister, converter, configManager);
    }

    @Override public void createValue(CustomField field, Issue issue, Double value) { /* User input is never persisted. */ }
    @Override public void updateValue(CustomField field, Issue issue, Double value) { /* Preserve the saved value on edits/imports. */ }
    @Override public void setDefaultValue(FieldConfig config, Double value) {
        if (value != null) throw new IllegalArgumentException(READ_ONLY);
    }
    @Override public Double getDefaultValue(FieldConfig config) { return null; }
    @Override public String availableForBulkEdit(BulkEditBean bean) { return "bulk.edit.unavailable"; }
    @Override public void validateFromParams(CustomFieldParams params, ErrorCollection errors, FieldConfig config) {
        if (params != null && params.getAllValues().stream().anyMatch(value -> value != null && !value.toString().isBlank())) {
            errors.addError(config.getFieldId(), READ_ONLY);
        }
    }
    @Override public RestFieldOperationsHandler getRestFieldOperation(CustomField field) {
        return new RestFieldOperationsHandler() {
            public Set<String> getSupportedOperations() { return Collections.emptySet(); }
            public ErrorCollection updateIssueInputParameters(IssueContext context, Issue issue, String fieldId,
                    IssueInputParameters parameters, List<FieldOperationHolder> operations) {
                SimpleErrorCollection errors = new SimpleErrorCollection(); errors.addError(fieldId, READ_ONLY); return errors;
            }
        };
    }

    /** Internal writer. Call only for a captured successful transition within the issue update transaction. */
    public void writeCalculatedValue(CustomField field, Issue issue, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0
                || value.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("Invalid progress percentage");
        super.updateValue(field, issue, value.doubleValue());
    }
}
