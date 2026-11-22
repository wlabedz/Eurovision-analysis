package pl.uj.eurovision.controllers;

import org.springframework.web.bind.annotation.*;
import pl.uj.eurovision.models.Contest;
import pl.uj.eurovision.services.ContestService;
import java.util.List;

@RestController
@RequestMapping("/api/contests")
@CrossOrigin(origins = "${app.frontend-url}")
public class ContestController {
    private final ContestService contestService;

    public ContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    @GetMapping("/{year}")
    public Contest getContest(@PathVariable int year) {
        return contestService.getContest(year);
    }

    @GetMapping
    public List<Contest> getAllContests() {
        return contestService.getAllContests();
    }
}