package io.conddo.studio.notify;

import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import io.conddo.studio.sse.JobLifecycleEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Brevo mirror picks the right recipients (assignee for reassign/revision,
 * fan-out to TEAM_LEAD + ADMIN for escalation), passes the job number through
 * to the subject, and stays quiet when the targeted staff doesn't exist or
 * there's nobody to page.
 */
class StudioEmailNotifierTest {

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StudioMailer mailer = mock(StudioMailer.class);
    private final StudioEmailNotifier notifier = new StudioEmailNotifier(staffRepository, mailer);

    @Test
    void reassignSendsToTheNewOwner() {
        UUID staffId = UUID.randomUUID();
        Staff staff = staff("dele@studio.test", "Dele Dev");
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        notifier.onReassigned(new JobLifecycleEvent.JobReassigned(UUID.randomUUID(), "WB-1001", staffId));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("dele@studio.test"), subject.capture(), anyString(), anyString());
        assertEquals("New job assigned: WB-1001", subject.getValue());
    }

    @Test
    void revisionRequestedIncludesFeedback() {
        UUID staffId = UUID.randomUUID();
        Staff staff = staff("dele@studio.test", "Dele Dev");
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        notifier.onRevisionRequested(new JobLifecycleEvent.JobRevisionRequested(
                UUID.randomUUID(), "WB-1001", staffId, "Logo is too small"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("dele@studio.test"), eq("Revision requested: WB-1001"), anyString(), text.capture());
        assertTrue(text.getValue().contains("Logo is too small"),
                "feedback should be quoted in the plain-text body");
    }

    @Test
    void revisionTreatsMissingFeedbackAsEmpty() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff("dele@studio.test", "Dele Dev")));

        notifier.onRevisionRequested(new JobLifecycleEvent.JobRevisionRequested(
                UUID.randomUUID(), "WB-1001", staffId, null));

        verify(mailer).send(eq("dele@studio.test"), anyString(), anyString(), anyString());
    }

    @Test
    void reassignToUnknownStaffDoesNothing() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

        notifier.onReassigned(new JobLifecycleEvent.JobReassigned(UUID.randomUUID(), "WB-1001", staffId));

        verifyNoInteractions(mailer);
    }

    @Test
    void escalationFansOutToEveryLeadAndAdmin() {
        when(staffRepository.findByRoleAndActiveTrue("TEAM_LEAD"))
                .thenReturn(List.of(staff("lara@studio.test", "Lara Lead"),
                        staff("len@studio.test", "Len Lead")));
        when(staffRepository.findByRoleAndActiveTrue("ADMIN"))
                .thenReturn(List.of(staff("ada@studio.test", "Ada Admin")));

        notifier.onEscalated(new JobLifecycleEvent.JobEscalated(UUID.randomUUID(), "WB-1001", "Overdue 6h"));

        verify(mailer).send(eq("lara@studio.test"), eq("Escalation: WB-1001"), anyString(), anyString());
        verify(mailer).send(eq("len@studio.test"), eq("Escalation: WB-1001"), anyString(), anyString());
        verify(mailer).send(eq("ada@studio.test"), eq("Escalation: WB-1001"), anyString(), anyString());
        verify(mailer, times(3)).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void escalationWithNoLeadsOrAdminsLogsAndDrops() {
        when(staffRepository.findByRoleAndActiveTrue("TEAM_LEAD")).thenReturn(List.of());
        when(staffRepository.findByRoleAndActiveTrue("ADMIN")).thenReturn(List.of());

        notifier.onEscalated(new JobLifecycleEvent.JobEscalated(UUID.randomUUID(), "WB-1001", "Overdue"));

        verify(mailer, never()).send(anyString(), anyString(), any(), any());
    }

    @Test
    void escalationWithMissingReasonStillSends() {
        when(staffRepository.findByRoleAndActiveTrue("TEAM_LEAD"))
                .thenReturn(List.of(staff("lara@studio.test", "Lara Lead")));
        when(staffRepository.findByRoleAndActiveTrue("ADMIN")).thenReturn(List.of());

        notifier.onEscalated(new JobLifecycleEvent.JobEscalated(UUID.randomUUID(), "WB-1001", null));

        verify(mailer).send(eq("lara@studio.test"), eq("Escalation: WB-1001"), anyString(), anyString());
    }

    // ----- helpers ------------------------------------------------------------

    private static Staff staff(String email, String name) {
        Staff s = new Staff(email, "hash", name, "DEVELOPER", List.of());
        setId(s, UUID.randomUUID());
        return s;
    }

    private static void setId(Staff staff, UUID id) {
        try {
            Field f = Staff.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(staff, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
