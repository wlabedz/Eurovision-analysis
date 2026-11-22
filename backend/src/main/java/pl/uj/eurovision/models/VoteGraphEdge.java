package pl.uj.eurovision.models;

import java.util.Map;

public record VoteGraphEdge(String from, String to, int points, Map<Integer, Integer> byYear) {}
