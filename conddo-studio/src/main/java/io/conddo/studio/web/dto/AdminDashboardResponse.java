package io.conddo.studio.web.dto;

import io.conddo.studio.jobs.JobService.JobView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin overview: all jobs + counts by status and by SLA tone. */
public record AdminDashboardResponse(List<JobCard> jobs, Map<String, Long> byStatus, Map<String, Long> bySla) {

    public static AdminDashboardResponse from(List<JobView> views) {
        List<JobCard> cards = views.stream().map(JobCard::from).toList();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySla = new LinkedHashMap<>();
        for (JobCard card : cards) {
            byStatus.merge(card.status(), 1L, Long::sum);
            bySla.merge(card.slaTone(), 1L, Long::sum);
        }
        return new AdminDashboardResponse(cards, byStatus, bySla);
    }
}
