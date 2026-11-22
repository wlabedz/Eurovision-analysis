package pl.uj.eurovision.controllers;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import pl.uj.eurovision.models.CountryOption;
import pl.uj.eurovision.models.VoteGraph;
import pl.uj.eurovision.services.VoteGraphService;

@RestController
@RequestMapping("/api/votes")
@CrossOrigin(origins = "${app.frontend-url}")
public class VoteGraphController {
    private final VoteGraphService voteGraphService;

    public VoteGraphController(VoteGraphService voteGraphService) {
        this.voteGraphService = voteGraphService;
    }

    @GetMapping("/countries")
    public List<CountryOption> getCountries() {
        return voteGraphService.getCountries();
    }

    // Example: /api/votes/graph?from=2016&to=2026&countries=PL,UA,LT&round=final&type=total
    @GetMapping("/graph")
    public VoteGraph getGraph(
            @RequestParam int from,
            @RequestParam int to,
            @RequestParam String countries,
            @RequestParam(defaultValue = "final") String round,
            @RequestParam(defaultValue = "total") String type) {
        return voteGraphService.getGraph(from, to, countries, round, type);
    }
}
