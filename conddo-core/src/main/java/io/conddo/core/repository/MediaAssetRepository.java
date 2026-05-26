package io.conddo.core.repository;

import io.conddo.core.domain.MediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    Page<MediaAsset> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<MediaAsset> findByKindOrderByCreatedAtDesc(String kind, Pageable pageable);
}
