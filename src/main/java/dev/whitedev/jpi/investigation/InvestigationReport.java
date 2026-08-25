package dev.whitedev.jpi.investigation;

import java.util.List;
import java.util.Map;

public record InvestigationReport(String query, List<InvestigationTarget> entryPoints,
                                  List<String> constants, List<String> runtimePaths,
                                  Map<String, Long> runtimeHits, int matchingClasses) {
}
