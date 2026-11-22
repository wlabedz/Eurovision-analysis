package pl.uj.eurovision.models;

import java.util.List;

public record VoteGraph(
        int from,
        int to,
        String round,
        String type,
        int yearsWithVotes,
        List<VoteGraphNode> nodes,
        List<VoteGraphEdge> edges) {}
