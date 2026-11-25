package dev.belegagent.app.review;

import dev.belegagent.app.intake.Tenants;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ReviewController {

    private final ReviewService service;
    private final Tenants tenants;

    ReviewController(ReviewService service, Tenants tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    /** Review queue: documents in NEEDS_REVIEW with their remaining validation errors, oldest first. */
    @GetMapping("/api/review")
    List<ReviewService.QueueItem> queue(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @RequestParam(defaultValue = "50") int limit) {
        return service.queue(tenants.resolve(tenantHeader), limit);
    }

    /** Approve or correct a document; it becomes APPROVED. */
    @PostMapping("/api/documents/{id}/review")
    Review review(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest request) {
        return service.review(tenants.resolve(tenantHeader), id, request);
    }

    @GetMapping("/api/documents/{id}/review")
    Review latest(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @PathVariable UUID id) {
        return service.latest(tenants.resolve(tenantHeader), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not reviewed yet"));
    }

    @ExceptionHandler(ReviewService.ErrorsRemainException.class)
    ProblemDetail errorsRemain(ReviewService.ErrorsRemainException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), e.getMessage());
        problem.setProperty("errors", e.errors());
        return problem;
    }
}
