package org.jboss.processFlow.services.remote.cdi;

import java.util.List;
import org.kie.internal.task.api.UserGroupCallback;
import org.jbpm.shared.services.cdi.Selectable;
import javax.enterprise.inject.Alternative;

/*
  purpose
    - provides ability to opt out of any jbpm human task UserGroupCallback security

  details
    - in some scenarios, it might be desirable not to by-pass UserGroupCallback security required by jbpm
    - the assumption would be that the invocation to task resources via REST API would already have been authorized by a service upstream of this call
*/
@Alternative
@Selectable
public class NoOpUserGroupCallbackImpl implements UserGroupCallback {

    public boolean existsUser(String userId) {
        return true;
    }
    public boolean existsGroup(String groupId) {
        return true;
    }

    public List<String> getGroupsForUser(String userId, List<String> groupIds, List<String> allExistingGroupIds) {
        return groupIds;
    }
} 
