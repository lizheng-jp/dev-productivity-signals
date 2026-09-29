package dev.productivity.signals.service;

import dev.productivity.signals.entity.UserFeedback;
import dev.productivity.signals.repository.UserFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserFeedbackServiceTest {

    @Test
    void savesValidatedAnonymousFeedback() {
        UserFeedbackRepository repository = mock(UserFeedbackRepository.class);
        when(repository.saveAndFlush(any(UserFeedback.class))).thenAnswer(invocation -> {
            UserFeedback feedback = invocation.getArgument(0);
            feedback.setId(1L);
            return feedback;
        });
        UserFeedbackService service = new UserFeedbackService(repository);

        UserFeedbackService.SubmissionResponse response = service.submit(
                new UserFeedbackService.SubmissionRequest(
                        " improvement ", " Better chart ", " Dashboard ", " Add a weekly view. ", "en"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("NEW");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void rejectsBlankRequiredFields() {
        UserFeedbackService service = new UserFeedbackService(mock(UserFeedbackRepository.class));

        assertThatThrownBy(() -> service.submit(
                new UserFeedbackService.SubmissionRequest("bug", " ", null, "details", "ja")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
