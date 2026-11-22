package pl.uj.eurovision.models;

import java.util.List;

public record RoundDetails(
        String name,
        String date,
        String time,
        List<PerformanceDetails> performances,
        List<Integer> disqualifieds) {

    public RoundDetails {
        performances = performances == null ? List.of() : performances;
        disqualifieds = disqualifieds == null ? List.of() : disqualifieds;
    }
}
