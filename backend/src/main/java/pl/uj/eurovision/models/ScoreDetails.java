package pl.uj.eurovision.models;

import java.util.Map;

public record ScoreDetails(String name, Integer points, Map<String, Integer> votes) {

    public ScoreDetails {
        votes = votes == null ? Map.of() : votes;
    }
}
