package io.conddo.api.web;

import io.conddo.api.web.dto.CreateCustomerRequest;
import io.conddo.api.web.dto.CustomerProfile;
import io.conddo.api.web.dto.CustomerRow;
import io.conddo.api.web.dto.MeasurementsBody;
import io.conddo.api.web.dto.NotesBody;
import io.conddo.api.web.dto.TagBody;
import io.conddo.api.web.dto.UpdateCustomerRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Customer;
import io.conddo.core.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped CRM (§11.3). The tenant comes from the JWT {@code tenant_id}
 * claim (SUPER_ADMIN may target one via {@code X-Act-As-Tenant}) and is enforced
 * by RLS — these methods never see another tenant's data. Reads are open to any
 * staff role; writes default to {@code TENANT_ADMIN} (or an acting SUPER_ADMIN).
 *
 * <p>Order/payment history, CSV import/export, bulk actions, and single-message
 * send are deferred until the Orders (§11.4), Notifications, and Billing modules
 * they depend on are in place.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    /** Any staff role may read the CRM. */
    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    /** Writes default to TENANT_ADMIN (or an acting SUPER_ADMIN). */
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** List with search + segment filter + pagination. {@code filter}/{@code segment} are aliases. */
    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<CustomerRow>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String segment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Customer> result = customerService.list(
                search, filter != null ? filter : segment, PageRequest.of(page, size));
        List<CustomerRow> rows = result.getContent().stream().map(CustomerRow::from).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<CustomerProfile>> create(@Valid @RequestBody CreateCustomerRequest request) {
        Customer created = customerService.create(
                request.fullName(), request.email(), request.phone(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(CustomerProfile.from(created)));
    }

    /** Segment definitions + live member counts (must precede {@code /{id}}). */
    @GetMapping("/segments")
    @PreAuthorize(READ)
    public ApiResponse<List<CustomerService.Segment>> segments() {
        return ApiResponse.ok(customerService.segments());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<CustomerProfile> get(@PathVariable UUID id) {
        return ApiResponse.ok(CustomerProfile.from(customerService.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<CustomerProfile> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateCustomerRequest request) {
        Customer updated = customerService.update(id, request.fullName(), request.email(), request.phone());
        return ApiResponse.ok(CustomerProfile.from(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/notes")
    @PreAuthorize(READ)
    public ApiResponse<NotesBody> getNotes(@PathVariable UUID id) {
        return ApiResponse.ok(new NotesBody(customerService.get(id).getNotes()));
    }

    @PutMapping("/{id}/notes")
    @PreAuthorize(WRITE)
    public ApiResponse<NotesBody> putNotes(@PathVariable UUID id, @RequestBody NotesBody body) {
        return ApiResponse.ok(new NotesBody(customerService.setNotes(id, body.notes()).getNotes()));
    }

    @GetMapping("/{id}/measurements")
    @PreAuthorize(READ)
    public ApiResponse<MeasurementsBody> getMeasurements(@PathVariable UUID id) {
        return ApiResponse.ok(new MeasurementsBody(customerService.get(id).getMeasurements()));
    }

    @PutMapping("/{id}/measurements")
    @PreAuthorize(WRITE)
    public ApiResponse<MeasurementsBody> putMeasurements(@PathVariable UUID id, @RequestBody MeasurementsBody body) {
        return ApiResponse.ok(new MeasurementsBody(
                customerService.setMeasurements(id, body.measurements()).getMeasurements()));
    }

    @PostMapping("/{id}/tags")
    @PreAuthorize(WRITE)
    public ApiResponse<CustomerProfile> addTag(@PathVariable UUID id, @Valid @RequestBody TagBody body) {
        return ApiResponse.ok(CustomerProfile.from(customerService.addTag(id, body.tag())));
    }

    @DeleteMapping("/{id}/tags")
    @PreAuthorize(WRITE)
    public ApiResponse<CustomerProfile> removeTag(@PathVariable UUID id, @RequestParam String tag) {
        return ApiResponse.ok(CustomerProfile.from(customerService.removeTag(id, tag)));
    }
}
