package dev.whitedev.jpi.investigation.assistant;

import dev.whitedev.jpi.investigation.InvestigationReport;
import dev.whitedev.jpi.investigation.InvestigationTarget;

import java.util.List;

public record AssistantReport(InvestigationReport investigation, String constantsRaw, String usersRaw,
                              List<Edge> edges, List<Chain> chains, List<InvestigationTarget> probes,
                              List<Finding> findings, List<String> notices, int analyzedMethods, boolean partial) {
    public record Method(String identifier, String owner, String name, String descriptor) {
        public String key() { return identifier + "\u0000" + owner + "\u0000" + name + "\u0000" + descriptor; }
        public String display() { return owner + "." + name + descriptor; }
        public InvestigationTarget target() {
            return new InvestigationTarget(identifier, owner, name, descriptor, "", 0, 0);
        }
    }

    public record Edge(Method caller, Method callee, boolean staticReference, long observedHits,
                       boolean reflective, boolean failed) { }

    public record Chain(List<Method> methods, int confidence, int observedEdges, int edgeCount) { }

    public record Finding(String category, Method caller, Method api, boolean observed, long hits) { }

    public String markdown() {
        StringBuilder text = new StringBuilder("# Investigation Assistant\n\nQuery: `")
                .append(investigation.query().replace("`", "'")).append("`\n\n")
                .append("Status: ").append(partial ? "partial" : "completed").append("\n")
                .append("Ranked entry points: ").append(investigation.entryPoints().size()).append("\n")
                .append("Analyzed methods: ").append(analyzedMethods).append("\n")
                .append("Call graph edges: ").append(edges.size()).append("\n\n")
                .append("## Likely execution chains\n\n");
        if (chains.isEmpty()) text.append("No connected chain could be established.\n\n");
        for (Chain chain : chains) {
            text.append("```text\n");
            for (int i = 0; i < chain.methods.size(); i++) {
                if (i > 0) text.append("  -> ");
                Method method = chain.methods.get(i);
                text.append(method.display()).append(" [").append(method.identifier()).append("]\n");
            }
            text.append("```\nHeuristic confidence: ").append(chain.confidence).append("/100\n")
                    .append("Observed edges: ").append(chain.observedEdges).append('/').append(chain.edgeCount)
                    .append(". This is a connected call graph path, not proof of one complete execution.\n\n");
        }
        text.append("## API evidence\n\n");
        if (findings.isEmpty()) text.append("No supported Crypto or Network API reference found within the scan bounds.\n\n");
        for (Finding finding : findings) text.append("- ").append(finding.category).append(": `")
                .append(finding.caller.display()).append("` calls `").append(finding.api.display()).append("` (")
                .append(finding.observed ? "observed, " + finding.hits + " hits" : "static reference")
                .append(")\n");
        text.append("\n## Suggested trace points\n\n");
        for (InvestigationTarget probe : probes) text.append("- `").append(probe.displayName())
                .append("` [").append(probe.classIdentifier()).append("]\n");
        text.append("\n## Call graph\n\n");
        for (Edge edge : edges) text.append("- `").append(edge.caller.display()).append("` -> `")
                .append(edge.callee.display()).append("` [")
                .append(edge.caller.identifier()).append(" -> ").append(edge.callee.identifier()).append(", ")
                .append(edge.staticReference ? "static" : "observed")
                .append(edge.observedHits > 0 ? ", observed " + edge.observedHits + "x" : "")
                .append(edge.reflective ? ", reflection" : "").append(edge.failed ? ", exception evidence" : "")
                .append("]\n");
        text.append("\n## Limits and notices\n\n")
                .append("Confidence is a deterministic ranking score, not a probability. Static edges do not establish call order. ")
                .append("Runtime edges cover active probes and may be name-aggregated across classloaders. ")
                .append("The assistant does not execute target methods or install probes.\n\n");
        for (String notice : notices) text.append("- ").append(notice).append('\n');
        return text.toString();
    }

    public String text() {
        return markdown().replace("```text\n", "").replace("```\n", "")
                .replace("`", "").replaceAll("(?m)^#{1,2}\\s+", "");
    }
}
