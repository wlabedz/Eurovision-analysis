package pl.uj.eurovision.models;

import java.util.List;
import java.util.Map;

public record ContestDetails(
        int year,
        String arena,
        String city,
        String country,
        String countryCode,
        String slogan,
        String logoUrl,
        List<String> presenters,
        List<String> broadcasters,
        List<ContestantDetails> contestants,
        List<RoundDetails> rounds,
        Map<String, String> countries) {

    public ContestDetails {
        presenters = presenters == null ? List.of() : presenters;
        broadcasters = broadcasters == null ? List.of() : broadcasters;
        contestants = contestants == null ? List.of() : contestants;
        rounds = rounds == null ? List.of() : rounds;
        countries = countries == null ? Map.of() : countries;
    }
}
