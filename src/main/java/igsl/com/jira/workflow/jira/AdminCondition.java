package igsl.com.jira.workflow.jira;

import com.atlassian.plugin.web.Condition;
import igsl.com.jira.workflow.service.AdminAccess;
import javax.inject.Inject;
import java.util.Map;

public final class AdminCondition implements Condition {
    private final AdminAccess access;
    @Inject public AdminCondition(AdminAccess access) { this.access = access; }
    @Override public void init(Map<String, String> parameters) { }
    @Override public boolean shouldDisplay(Map<String, Object> context) {
        try { access.requireAdmin(); return true; }
        catch (SecurityException denied) { return false; }
    }
}
