package igsl.com.jira.workflow.service;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.*;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import javax.inject.*;

@Named
public class AdminAccess {
    private final JiraAuthenticationContext authentication;
    private final GlobalPermissionManager permissions;
    @Inject public AdminAccess(@ComponentImport JiraAuthenticationContext authentication,
                               @ComponentImport GlobalPermissionManager permissions) {
        this.authentication = authentication; this.permissions = permissions;
    }
    public ApplicationUser requireAdmin() {
        ApplicationUser user = authentication.getLoggedInUser();
        if (user == null || !permissions.hasPermission(GlobalPermissionKey.ADMINISTER, user)) {
            throw new SecurityException("Jira administrator permission is required");
        }
        return user;
    }
}
