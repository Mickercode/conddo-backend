package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.OrderStage;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.OrderStageRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import io.conddo.core.vertical.VerticalConfig;
import io.conddo.core.vertical.VerticalConfigRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The tenant's order pipeline (§11.4). Stages are <b>vertical-specific</b>: a
 * tenant with no overrides uses its vertical's default stages (from
 * {@link VerticalConfigRegistry}); the first edit materialises those defaults
 * into {@code order_stages} so they can be renamed, reordered, or removed.
 */
@Service
public class OrderStageService {

    /** Fallback vertical when a tenant's vertical is unknown/unset. */
    private static final String DEFAULT_VERTICAL = "general";

    private final OrderStageRepository stageRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final VerticalConfigRegistry verticals;

    public OrderStageService(OrderStageRepository stageRepository, TenantRepository tenantRepository,
                             TenantSession tenantSession, VerticalConfigRegistry verticals) {
        this.stageRepository = stageRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.verticals = verticals;
    }

    /** The effective stage names in pipeline order — overrides if any, else vertical defaults. */
    @Transactional(readOnly = true)
    public List<String> effectiveStageNames() {
        tenantSession.bind();
        return resolveStageNames();
    }

    /** Effective stages as views (stored rows have an id; defaults don't). */
    @Transactional(readOnly = true)
    public List<StageView> list() {
        tenantSession.bind();
        List<OrderStage> stored = stageRepository.findAllByOrderByPositionAsc();
        if (!stored.isEmpty()) {
            List<StageView> views = new ArrayList<>();
            for (OrderStage s : stored) {
                views.add(new StageView(s.getId(), s.getName(), s.getPosition()));
            }
            return views;
        }
        List<StageView> views = new ArrayList<>();
        List<String> names = defaultStageNames();
        for (int i = 0; i < names.size(); i++) {
            views.add(new StageView(null, names.get(i), i));
        }
        return views;
    }

    /** The default first stage new orders land in. */
    @Transactional(readOnly = true)
    public String firstStage() {
        tenantSession.bind();
        List<String> names = resolveStageNames();
        return names.isEmpty() ? "Received" : names.get(0);
    }

    @Transactional
    public OrderStage add(String name, Integer position) {
        tenantSession.bind();
        materializeDefaultsIfEmpty();
        int pos = position != null ? position : nextPosition();
        return stageRepository.save(new OrderStage(TenantContext.require(), name, pos));
    }

    @Transactional
    public OrderStage update(UUID id, String name, Integer position) {
        tenantSession.bind();
        OrderStage stage = require(id);
        stage.setName(name);
        stage.setPosition(position);
        return stageRepository.save(stage);
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        stageRepository.delete(require(id));
    }

    // ----- internals ---------------------------------------------------------

    /** Resolve effective names assuming the tenant is already bound. */
    private List<String> resolveStageNames() {
        List<OrderStage> stored = stageRepository.findAllByOrderByPositionAsc();
        if (!stored.isEmpty()) {
            return stored.stream().map(OrderStage::getName).toList();
        }
        return defaultStageNames();
    }

    private List<String> defaultStageNames() {
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
        VerticalConfig config = verticals.find(tenant.getVerticalId());
        if (config == null) {
            config = verticals.require(DEFAULT_VERTICAL);
        }
        return config.orderStages();
    }

    private void materializeDefaultsIfEmpty() {
        if (stageRepository.findAllByOrderByPositionAsc().isEmpty()) {
            List<String> names = defaultStageNames();
            UUID tenantId = TenantContext.require();
            for (int i = 0; i < names.size(); i++) {
                stageRepository.save(new OrderStage(tenantId, names.get(i), i));
            }
        }
    }

    private int nextPosition() {
        return stageRepository.findAllByOrderByPositionAsc().stream()
                .mapToInt(OrderStage::getPosition).max().orElse(-1) + 1;
    }

    private OrderStage require(UUID id) {
        return stageRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Stage not found"));
    }

    /** A pipeline stage for the API ({@code id} is null for an unmaterialised default). */
    public record StageView(UUID id, String name, int position) {
    }
}
