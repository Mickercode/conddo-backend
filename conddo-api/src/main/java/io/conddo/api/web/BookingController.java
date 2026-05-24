package io.conddo.api.web;

import io.conddo.api.web.dto.AvailabilityRequest;
import io.conddo.api.web.dto.BookingEvent;
import io.conddo.api.web.dto.CreateBookingRequest;
import io.conddo.api.web.dto.LinkResponse;
import io.conddo.api.web.dto.UpdateBookingRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.BookingService;
import io.conddo.core.service.BookingService.Availability;
import io.conddo.core.service.BookingService.Performance;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped bookings (§11.5): calendar, availability, the shareable
 * self-book link, and weekly performance. Tenant comes from the JWT (RLS).
 * Reads are open to any staff role; writes default to TENANT_ADMIN / SUPER_ADMIN.
 * The PUBLIC client-facing self-book endpoints live in {@code PublicBookingController}.
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<BookingEvent>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(bookingService.list(from, to).stream().map(BookingEvent::from).toList());
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<BookingEvent>> create(@Valid @RequestBody CreateBookingRequest request) {
        BookingEvent body = BookingEvent.from(bookingService.create(
                request.customerId(), request.customerName(), request.service(),
                request.start(), request.end(), request.mode(), request.amount(), request.notes(), "confirmed"));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/upcoming")
    @PreAuthorize(READ)
    public ApiResponse<List<BookingEvent>> upcoming() {
        return ApiResponse.ok(bookingService.upcoming().stream().map(BookingEvent::from).toList());
    }

    @GetMapping("/availability")
    @PreAuthorize(READ)
    public ApiResponse<Availability> availability() {
        return ApiResponse.ok(bookingService.availability());
    }

    @PutMapping("/availability")
    @PreAuthorize(WRITE)
    public ApiResponse<Availability> updateAvailability(@RequestBody AvailabilityRequest request) {
        return ApiResponse.ok(bookingService.updateAvailability(
                request.workingHours(), request.slotDurationMinutes(), request.bufferMinutes()));
    }

    @GetMapping("/link")
    @PreAuthorize(READ)
    public ApiResponse<LinkResponse> link() {
        return ApiResponse.ok(LinkResponse.from(bookingService.link()));
    }

    @PostMapping("/link")
    @PreAuthorize(WRITE)
    public ApiResponse<LinkResponse> regenerateLink() {
        return ApiResponse.ok(LinkResponse.from(bookingService.regenerateLink()));
    }

    @GetMapping("/performance")
    @PreAuthorize(READ)
    public ApiResponse<Performance> performance(@RequestParam(required = false) String range) {
        return ApiResponse.ok(bookingService.performance());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<BookingEvent> get(@PathVariable UUID id) {
        return ApiResponse.ok(BookingEvent.from(bookingService.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<BookingEvent> update(@PathVariable UUID id, @RequestBody UpdateBookingRequest request) {
        return ApiResponse.ok(BookingEvent.from(bookingService.update(id, request.start(), request.end(),
                request.service(), request.mode(), request.status(), request.amount(), request.notes())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        bookingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
