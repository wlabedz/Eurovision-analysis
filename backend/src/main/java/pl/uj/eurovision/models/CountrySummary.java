package pl.uj.eurovision.models;

import java.util.List;

public record CountrySummary(
        String code,
        String name,
        int from,
        int to,
        String type,
        SummaryStats overall,
        SummaryStats finals,
        SummaryStats semifinals,
        List<VoterSummary> voters) {}
