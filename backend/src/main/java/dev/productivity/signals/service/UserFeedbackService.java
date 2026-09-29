package dev.productivity.signals.service;

import dev.productivity.signals.entity.UserFeedback;
import dev.productivity.signals.repository.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserFeedbackService {

    private static final Set<String> CATEGORIES = Set.of("improvement", "bug", "question", "other");
    private static final Set<String> LOCALES = Set.of("ja", "en");

    private final UserFeedbackRepository repository;

    public record SubmissionRequest(
            String category,
            String subject,
            String context,
            String details,
            String locale) {
    }

    public record SubmissionResponse(long id, String status, Instant createdAt) {
    }

    @Transactional
    public SubmissionResponse submit(SubmissionRequest request) {
        if (request == null) {
            throw badRequest();
        }

        String category = normalize(request.category()).toLowerCase(Locale.ROOT);
        String subject = normalize(request.subject());
        String context = normalizeNullable(request.context());
        String details = normalize(request.details());
        String locale = normalize(request.locale()).toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)
                || subject.isEmpty() || subject.length() > 120
                || (context != null && context.length() > 300)
                || details.isEmpty() || details.length() > 5000
                || !LOCALES.contains(locale)) {
            throw badRequest();
        }

        Instant now = Instant.now();
        UserFeedback feedback = new UserFeedback();
        feedback.setCategory(category);
        feedback.setSubject(subject);
        feedback.setContext(context);
        feedback.setDetails(details);
        feedback.setLocale(locale);
        feedback.setStatus("NEW");
        feedback.setCreatedAt(now);
        feedback.setUpdatedAt(now);
        UserFeedback saved = repository.saveAndFlush(feedback);
        return new SubmissionResponse(saved.getId(), saved.getStatus(), saved.getCreatedAt());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeNullable(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid feedback");
    }
}
