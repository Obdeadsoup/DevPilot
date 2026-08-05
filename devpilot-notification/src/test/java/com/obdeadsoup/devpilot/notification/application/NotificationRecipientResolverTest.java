package com.obdeadsoup.devpilot.notification.application;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;import org.junit.jupiter.api.Test;import java.util.*;import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class NotificationRecipientResolverTest {
 @Test void fallsBackAndDeduplicatesManagers(){var q=mock(ProjectNotificationRecipientQuery.class);when(q.findManagerUserIds(1,2)).thenReturn(new LinkedHashSet<>(List.of(7L,8L)));var r=new NotificationRecipientResolver(q);assertThat(r.assigneeOrReporter(null,6)).isEqualTo(6);assertThat(r.assigneeAndManagers(7L,1,2)).containsExactly(7L,8L);}
}
