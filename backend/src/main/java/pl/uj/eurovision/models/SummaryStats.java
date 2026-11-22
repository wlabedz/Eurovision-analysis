package pl.uj.eurovision.models;

public record SummaryStats(int performances, int points, double average) {

    public static SummaryStats of(int performances, int points) {
        double average = performances == 0 ? 0 : Math.round(points * 100.0 / performances) / 100.0;
        return new SummaryStats(performances, points, average);
    }
}
