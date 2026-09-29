package ut.igsl.com.jira.workflow;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.customfields.persistence.CustomFieldValuePersister;
import com.atlassian.jira.issue.fields.CustomField;
import igsl.com.jira.workflow.jira.ProgressFieldType;
import org.junit.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class ProgressFieldTypeTest {
    private int writes;
    private Object lastValues;
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private final CustomField field = proxy(CustomField.class, (p,m,a) -> m.getName().equals("getId") ? "customfield_123" : null);
    private final Issue issue = proxy(Issue.class, (p,m,a) -> m.getName().equals("getId") ? 123L : null);
    private final CustomFieldValuePersister persister = proxy(CustomFieldValuePersister.class, (p,m,a) -> {
        if (m.getName().equals("updateValues")) { writes++; lastValues = a[3]; return null; }
        if (m.getName().equals("getValues")) return List.of(60.25d);
        throw new UnsupportedOperationException(m.getName());
    });
    private final ProgressFieldType type = new ProgressFieldType(persister, null, null);
    @Test public void ignoresDirectCreateAndUpdateInput() {
        type.createValue(field, issue, 99d); type.updateValue(field, issue, 99d);
        type.updateValue(field, issue, null); assertEquals(0, writes);
    }
    @Test public void internalWriterPersistsNumericPercentage() {
        type.writeCalculatedValue(field, issue, new BigDecimal("60.25"));
        assertEquals(1, writes); assertEquals(List.of(60.25d), lastValues);
    }
    @Test(expected = IllegalArgumentException.class) public void internalWriterRejectsInvalidPrecision() {
        type.writeCalculatedValue(field, issue, new BigDecimal("60.251"));
    }
    @Test public void restHasNoWritableOperations() {
        assertTrue(type.getRestFieldOperation(field).getSupportedOperations().isEmpty());
        assertTrue(type.getRestFieldOperation(field).updateIssueInputParameters(null, issue, "customfield_123", null, List.of()).hasAnyErrors());
    }
    @Test public void readingDoesNotWriteOrRecalculate() {
        assertEquals(Double.valueOf(60.25), type.getValueFromIssue(field, issue)); assertEquals(0, writes);
    }
}
