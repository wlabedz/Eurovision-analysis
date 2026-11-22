package pl.uj.eurovision.services;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import pl.uj.eurovision.models.Contest;
import java.util.List;
import java.util.ArrayList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper; // Spring Boot 3: com.fasterxml.jackson.databind.ObjectMapper

@Service
public class ContestService {
    private final RestClient http;
    private final ObjectMapper mapper;
    private final String dataRef;
    private final Map<Integer, Contest> cache = new ConcurrentHashMap<>();
    private volatile Map<String, String> countryMapping;

    public ContestService(ObjectMapper mapper,
                      @Value("${app.data-url}") String dataUrl,
                      @Value("${app.data-ref}") String dataRef,
                      @Value("${app.github-token}") String token) {
        this.http = RestClient.builder()
                .baseUrl(dataUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github.raw+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.mapper = mapper;
        this.dataRef = dataRef;
        this.countryMapping = getCountryMapping(); // Load country mapping on service initialization
    }

    public Contest getContest(int year) {
        return cache.computeIfAbsent(year, this::download);
    }

    public List<Contest> getAllContests() {
        List<Contest> contests = new ArrayList<>();
        for (int year = 1956; year <= 2026; year++) {
            Contest contest = getContest(year);
            contests.add(contest);
        }
        return contests;
    }

    private Contest download(int year) {
        try {
            String json = http.get()
                    .uri("/raw/contests/{year}.json?ref={ref}", year, dataRef)
                    .retrieve()
                    .body(String.class);
            Contest contest = mapper.readValue(json, Contest.class);
            String countryName = contest.country();
            String countryCode = countryMapping.get(countryName);
            if (countryCode != null) {
                contest = new Contest(contest.year(), contest.arena(), contest.city(), countryCode, contest.slogan(), contest.logoUrl());
            }
            return contest;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(NOT_FOUND, "No data available for year " + year);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to fetch data", e);
        }
    }

    private Map<String, String> getCountryMapping() {
        if (countryMapping == null) {
            countryMapping = downloadCountryMapping();
        }
        return countryMapping;
    }

    private Map<String, String> downloadCountryMapping() {
        try {
            String json = http.get()
                    .uri("/countries.json?ref={ref}", dataRef)
                    .retrieve()
                    .body(String.class);
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to fetch country mapping", e);
        }
    }
}


