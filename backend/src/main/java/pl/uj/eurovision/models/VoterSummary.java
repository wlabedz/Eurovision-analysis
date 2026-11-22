package pl.uj.eurovision.models;

public record VoterSummary(
        String code,
        String name,
        SummaryStats overall,
        SummaryStats finals,
        SummaryStats semifinals) {}
