package pl.uj.eurovision.services;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pl.uj.eurovision.models.ContestDetails;
import pl.uj.eurovision.models.ContestantDetails;
import pl.uj.eurovision.models.CountryOption;
import pl.uj.eurovision.models.PerformanceDetails;
import pl.uj.eurovision.models.RoundDetails;
import pl.uj.eurovision.models.ScoreDetails;
import pl.uj.eurovision.models.VoteGraph;
import pl.uj.eurovision.models.VoteGraphEdge;
import pl.uj.eurovision.models.VoteGraphNode;

@Service
public class VoteGraphService {
    private static final int FIRST_YEAR = 1956;
    private static final int LAST_YEAR = 2026;
    private static final int MIN_COUNTRIES = 2;
    private static final int MAX_COUNTRIES = 16;
    private static final int DOWNLOAD_THREADS = 6;
    private static final Set<String> VOTE_TYPES = Set.of("total", "jury", "public");

    private final ContestService contestService;

    public VoteGraphService(ContestService contestService) {
        this.contestService = contestService;
    }

    public List<CountryOption> getCountries() {
        return contestService.getCountryNames().entrySet().stream()
                .map(entry -> new CountryOption(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CountryOption::name))
                .toList();
    }

    public VoteGraph getGraph(int from, int to, String countries, String round, String type) {
        if (from < FIRST_YEAR || to > LAST_YEAR || from > to) {
            throw badRequest("Year range must be within " + FIRST_YEAR + "-" + LAST_YEAR);
        }
        if (!VOTE_TYPES.contains(type)) {
            throw badRequest("Unknown vote type");
        }

        Predicate<String> roundFilter = roundFilter(round);
        Map<String, String> names = contestService.getCountryNames();
        Set<String> selected = parseCountries(countries, names);

        List<ContestDetails> contests = loadContests(from, to);
        Map<String, Map<String, Map<Integer, Integer>>> flows = new HashMap<>();
        int yearsWithVotes = 0;

        for (ContestDetails contest : contests) {
            Map<Integer, String> contestantCountries = contest.contestants().stream()
                    .collect(Collectors.toMap(ContestantDetails::id, ContestantDetails::country));
            boolean hasVotes = false;

            for (RoundDetails contestRound : contest.rounds()) {
                if (!roundFilter.test(contestRound.name())) continue;

                for (PerformanceDetails performance : contestRound.performances()) {
                    String receiver = contestantCountries.get(performance.contestantId());
                    for (ScoreDetails score : performance.scores()) {
                        if (!score.name().equals(type) || score.votes().isEmpty()) continue;
                        hasVotes = true;
                        if (!selected.contains(receiver)) continue;

                        for (Map.Entry<String, Integer> vote : score.votes().entrySet()) {
                            if (!selected.contains(vote.getKey()) || vote.getValue() <= 0) continue;
                            flows.computeIfAbsent(vote.getKey(), key -> new HashMap<>())
                                    .computeIfAbsent(receiver, key -> new TreeMap<>())
                                    .merge(contest.year(), vote.getValue(), Integer::sum);
                        }
                    }
                }
            }
            if (hasVotes) yearsWithVotes++;
        }

        List<VoteGraphEdge> edges = buildEdges(flows);
        return new VoteGraph(from, to, round, type, yearsWithVotes, buildNodes(selected, names, edges), edges);
    }

    // Uses a dedicated pool: the cache downloads inside computeIfAbsent, which can
    // deadlock on the common ForkJoinPool used by parallel streams.
    private List<ContestDetails> loadContests(int from, int to) {
        try (ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS)) {
            List<Future<ContestDetails>> downloads = IntStream.rangeClosed(from, to)
                    .mapToObj(year -> pool.submit(() -> contestService.getContestDetails(year)))
                    .toList();

            List<ContestDetails> contests = new ArrayList<>();
            for (Future<ContestDetails> download : downloads) {
                contests.add(download.get());
            }
            return contests;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResponseStatusException statusException) {
                throw statusException;
            }
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to fetch data", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "Data download was interrupted", e);
        }
    }

    private List<VoteGraphEdge> buildEdges(Map<String, Map<String, Map<Integer, Integer>>> flows) {
        List<VoteGraphEdge> edges = new ArrayList<>();
        flows.forEach((voter, receivers) -> receivers.forEach((receiver, byYear) -> {
            int points = byYear.values().stream().mapToInt(Integer::intValue).sum();
            edges.add(new VoteGraphEdge(voter, receiver, points, byYear));
        }));
        edges.sort(Comparator.comparingInt(VoteGraphEdge::points).reversed()
                .thenComparing(VoteGraphEdge::from)
                .thenComparing(VoteGraphEdge::to));
        return edges;
    }

    private List<VoteGraphNode> buildNodes(Set<String> selected, Map<String, String> names, List<VoteGraphEdge> edges) {
        return selected.stream()
                .map(code -> new VoteGraphNode(
                        code,
                        names.get(code),
                        edges.stream().filter(e -> e.from().equals(code)).mapToInt(VoteGraphEdge::points).sum(),
                        edges.stream().filter(e -> e.to().equals(code)).mapToInt(VoteGraphEdge::points).sum()))
                .sorted(Comparator.comparing(VoteGraphNode::name))
                .toList();
    }

    private Set<String> parseCountries(String countries, Map<String, String> names) {
        Set<String> selected = Arrays.stream(countries.split(","))
                .map(code -> code.trim().toUpperCase())
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (selected.size() < MIN_COUNTRIES || selected.size() > MAX_COUNTRIES) {
            throw badRequest("Choose between " + MIN_COUNTRIES + " and " + MAX_COUNTRIES + " countries");
        }
        if (!names.keySet().containsAll(selected)) {
            throw badRequest("Unknown country code");
        }
        return selected;
    }

    private Predicate<String> roundFilter(String round) {
        return switch (round) {
            case "final" -> "final"::equals;
            case "semifinals" -> name -> name.startsWith("semifinal");
            case "all" -> name -> true;
            default -> throw badRequest("Unknown round");
        };
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
