package io.conddo.payments.repository;

import io.conddo.payments.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByRoutepayEventId(String routepayEventId);

    Optional<WebhookEvent> findFirstByPayloadSha256(String payloadSha256);
}
