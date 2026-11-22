package pl.uj.eurovision.models;

import java.util.List;

public record PerformanceDetails(int contestantId, Integer running, Integer place, List<ScoreDetails> scores) {

    public PerformanceDetails {
        scores = scores == null ? List.of() : scores;
    }
}
