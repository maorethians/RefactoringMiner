package narrator.langchain.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReviewPrompt {
  // TODO: our representation must be the most effective one for understanding the changes. Are we using it at the highest level of effectiveness?
  public String chapterUnderstanding(String content, List<String> understandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer specializing in complex system architecture. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Your objective is to architect a high-fidelity technical map of the changes within a single chapter. ")
            .append("This map must serve as the definitive systemic ground truth for a subsequent rigorous audit")
            .append("—meaning you must capture the deep semantic intent and architectural implications of every change, rather than providing a surface-level summary.\n\n");

    prompt.append("### CURRENT CHAPTER\n")
            .append("The following block contains the raw code changes and diffs for the current chapter. This is your primary source of truth for the technical mapping task:\n")
            .append(content).append("\n\n");

    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("### DEPENDENCY CONTEXT\n")
              .append("To ensure systemic continuity, you are provided with the technical mappings from preceding chapters. ")
              .append("These establish the architectural baseline and dependency chain necessary to resolve identifier references and interpret the systemic intent of the current chapter:\n")
              .append(String.join("\n", understandings.stream().map(understanding -> "<technical_mapping>\n" + understanding + "\n</technical_mapping>").toList()))
              .append("\n\n");
    }

    prompt.append("### YOUR TASK\n");
    prompt.append("#### Construction of the High-Fidelity Technical Map\n")
            .append("You must architect a comprehensive technical map of this chapter. This map is the definitive systemic ground truth for a subsequent high-rigor audit; ")
            .append("any omission or ambiguity here will directly compromise the quality of the final review. ")
            .append("Your goal is to eliminate all surface-level interpretation and replace it with deep architectural deduction.\n");
    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("CRITICAL: Use the provided Dependency Context as your baseline. You must explicitly bridge the gap between previous chapters and this one, resolving identifier references and documenting how these changes evolve the system state established in preceding mappings.\n");
    }
    prompt.append("Your mapping MUST be structured into two rigorous dimensions:\n")
            .append("1. SYSTEMIC IDENTIFIER TRACKING: Provide a precise map of every modified or introduced identifier (classes, methods, variables). For each, you must document:\n")
            .append("   - THE DELTA: Exactly how the role or responsibility has changed.\n")
            .append("   - ARCHITECTURAL IMPACT: The ripple effect this change has on dependent components and systemic interactions.\n")
            .append("2. LOGIC & INTENT DEDUCTION: Perform a deep trace of the logic flow to deduce the exact intended behavior. You must provide:\n")
            .append("   - TECHNICAL JUSTIFICATION: The specific technical reason for this implementation path.\n")
            .append("   - BEHAVIORAL RESULT: The precise resulting systemic behavior.\n")
            .append("STRICT CONSTRAINT: Zero tolerance for generic summaries. Avoid phrases like 'improved performance' or 'cleaned up code.' Instead, provide evidence-based technical details (e.g., 'replaced linear search with a binary search to reduce lookup time from O(n) to O(log n)').\n");

    return prompt.toString();
  }

  // TODO: is checking against mapping is the effective approach?
  public String chapterResult(String content, String understanding) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer and Lead Technical Auditor known for meticulous rigor and a zero-tolerance policy for low-signal noise. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Your objective is to conduct a high-signal audit of a single chapter, utilizing a pre-synthesized technical map to identify critical flaws, systemic risks, and architectural regressions.\n\n");

    prompt.append("### CURRENT CHAPTER\n")
            .append("The following block contains the raw code changes and diffs for this chapter. This serves as your primary evidentiary source for the audit:\n")
            .append("<chapter_content>\n")
            .append(content)
            .append("\n</chapter_content>\n\n");

    prompt.append("### TECHNICAL MAPPING (THE AUDIT BENCHMARK)\n")
            .append("Below is the high-fidelity technical map of this chapter. This document serves as your definitive architectural benchmark—it describes the intended systemic state and logic flow.  ")
            .append("Your primary analytical loop is to verify the raw code evidence against this benchmark: identify where the implementation diverges from the map, ")
            .append("where the map's stated intent is flawed, or where the realized behavior introduces risks not captured in the mapping.\n")
            .append("<technical_mapping>\n")
            .append(understanding)
            .append("\n</technical_mapping>")
            .append("\n\n");

    prompt.append("### YOUR TASK\n");
    prompt.append("#### Execution of the High-Signal Adversarial Audit\n")
            .append("Using the Technical Mapping as your benchmark and the Chapter Content as your evidence, conduct an adversarial audit. ")
            .append("You are not 'reviewing' code; you are interrogating the implementation to find where it fails the architectural specification (The Map). ")
            .append("Your goal is to expose the gap between intended design and realized execution.\n\n");

    prompt.append("PRIMARY OBJECTIVE: Hunt for 'Unmapped Behavior'. ")
            .append("Identify any logic, side effects, or functionality present in the code that is absent from the Technical Mapping. ")
            .append("Unmapped behavior is a high-probability indicator of architectural drift, undocumented dependencies, or critical bugs.\n\n");

    prompt.append("Analyze the chapter through these three rigorous lenses:\n")
            .append("1. SEMANTIC INTEGRITY & SECURITY: Does the code execute exactly what the map intends, and nothing more? Where does the implementation diverge from the mapping's logic? Does this divergence introduce security vulnerabilities or fail to handle edge cases explicitly mentioned in the intent?\n")
            .append("2. RESOURCE EFFICIENCY & OBSERVABILITY: Is the mapped behavior implemented with optimal complexity? Identify systemic bottlenecks or 'blind spots' where a failure would occur without leaving a traceable log or metric.\n")
            .append("3. ARCHITECTURAL DRIFT & VERIFIABILITY: Does this implementation introduce technical debt that contradicts the architectural baseline? Is the resulting behavior deterministic and verifiable, or does it introduce ambiguity?\n\n");

    prompt.append("OUTPUT STANDARDS (ZERO TOLERANCE POLICY):\n")
            .append("Produce review comments adhering to these absolute constraints:\n")
            .append("- EVIDENCE-BASED RCA: Zero tolerance for compliments, generic advice, or hedging ('consider...', 'perhaps...'). Every finding must be a formal Root Cause Analysis following this structure: [Symptom] -> [Technical Cause] -> [Systemic Risk]. If you cannot prove the flaw with specific code references, discard it.\n")
            .append("- SYSTEMIC OVER SURFACE: Prioritize architectural regressions and systemic flaws over trivial style or formatting issues. If a finding does not represent a systemic risk to the system's integrity, it is noise—discard it.\n")
            .append("- ATOMICITY & UNICITY: Consolidate multiple occurrences of the same pattern into a single finding. Each unique issue must be reported exactly once; redundant comments are strictly forbidden as they degrade signal-to-noise ratio.\n")
            .append("- STRICT ANCHORING: Every comment MUST be anchored to one or more specific change IDs.\n");

    return prompt.toString();
  }

  public String understanding(List<StringIndex> understandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal System Architect specializing in high-fidelity technical modeling. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, and for each chapter, a rigorous technical map has been produced. ")
            .append("Your objective is to aggregate the mappings of consecutive chapters into a single, unified Master Technical Map. ")
            .append("This document will serve as the definitive architectural ground truth for the final synthesis of all audit findings.\n")
            .append("CRITICAL REQUIREMENT: This is a LOSSLESS aggregation, not a summary. You are strictly forbidden from abstracting or omitting any technical detail. ")
            .append("Every single identifier, systemic interaction, and logic step present in the input mappings must be preserved with absolute fidelity in the Master Map. ")
            .append("Any omission at this stage will create an analytical blind spot in the final audit.\n")
            .append("Your task is to transform these fragmented maps into a cohesive systemic model that explicitly captures how the changes evolve across chapters, ensuring a seamless technical continuity for the subsequent audit synthesis phase.\n\n");

    prompt.append("### SOURCE MATERIAL: SEQUENTIAL CHAPTER MAPPINGS\n")
            .append("The following mappings are provided in their strict dependency order. Each represents a discrete step in the system's architectural evolution. ")
            .append("You must process these sequentially to ensure that the Master Map preserves the correct progression of state and identifies how each chapter builds upon its predecessors:\n");
    for (int i = 0; i < understandings.size(); i++) {
      String index = "";
      if (i == 0 && understandings.get(i).index() != 0) {
        index += "1-";
      }
      index += understandings.get(i).index() + 1;

      prompt.append("<chapter_").append(index).append(">\n")
              .append(understandings.get(i).str()).append("\n")
              .append("</chapter_").append(index).append(">\n");
    }
    prompt.append("\n");

    prompt.append("### YOUR TASK\n")
            .append("Consolidate these sequential inputs into a single, unified Master Technical Map. This is not a summary; it is a high-fidelity technical assembly.")
            .append(" The resulting map MUST adhere strictly to the original structural format, consisting of exactly two primary sections:\n")
            .append("1. IDENTIFIER TRACKING: A consolidated union of every modified or introduced identifier across all provided chapters. ")
            .append("You must document their roles, responsibilities, and systemic interactions, ensuring that the evolution of each identifier is preserved if it appears in multiple chapters.\n")
            .append("2. LOGIC & INTENT ANALYSIS: A comprehensive assembly of the logic flows. This should capture the overarching intended behavior and technical justifications as a continuous narrative across these chapters.\n\n")
            .append("CRITICAL REQUIREMENTS (THE LOSSLESS MANDATE):\n")
            .append("- ZERO OMISSION POLICY: You are strictly forbidden from omitting any identifier or critical logic step. Before finalizing, verify that every unique technical detail found in the <chapter_X> tags is represented in your output.\n")
            .append("- SYSTEMIC CONTINUITY: While you should eliminate redundant phrasing, you must explicitly articulate the systemic interactions and dependencies between different chapters to ensure no context is lost during aggregation.\n")
            .append("- STRICT STRUCTURAL CONTRACT: The output must be a direct consolidation of the input format. Do not introduce new sections, headers, or meta-commentary. Any deviation from the two-section structure will break the downstream audit pipeline.\n");

    return prompt.toString();
  }

  public String result(List<String> results, String understanding) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer and Chief Audit Curator responsible for the final synthesis of a high-rigor technical review. ")
            .append("The audit process was modularized: the PR was decomposed into chapters, which were mapped and audited independently to ensure maximum depth. ")
            .append("Your objective is to consolidate these independent findings into a unified, cohesive final report that eliminates fragmentation and redundancy while strictly preserving all unique technical insights.\n\n")
            .append("You are operating without access to the raw source code; therefore, you must rely on the provided inputs as your absolute boundaries:\n")
            .append("1. THE GLOBAL TECHNICAL MAPPING: The definitive architectural ground truth. Use this as the Supreme Arbitrator to resolve contradictions and verify systemic interactions.\n")
            .append("2. MODULAR CHAPTER FINDINGS: High-signal audit results from each chapter. Treat these as authoritative discoveries that must be preserved unless they are absolute duplicates or proven incorrect by the Global Mapping.\n\n");

    prompt.append("### AUDIT EVIDENCE BASE\n")
            .append("The following sources constitute the entirety of the available evidence for this synthesis. You must not assume any information outside these blocks.\n\n")
            .append("#### [Sourcing 1] THE ARBITRATION BASELINE (Global Technical Mapping)\n")
            .append("Use this to resolve systemic contradictions and verify architectural intent:\n")
            .append("<mapping>\n").append(understanding).append("\n</mapping>\n\n")
            .append("#### [Sourcing 2] AUTHORITATIVE DISCOVERIES (Modular Chapter Findings)\n")
            .append("These are high-signal findings from independent audits. Preserve all unique insights:\n");
    for (int i = 0; i < results.size(); i++) {
      prompt.append("<chapter_").append(i + 1).append(">\n")
              .append(results.get(i)).append("\n")
              .append("</chapter_").append(i + 1).append(">\n");
    }
    prompt.append("\n");

    prompt.append("### CURATION FRAMEWORK & SYNTHESIS PROTOCOLS\n")
            .append("Transform the modular findings into a final list of review comments by applying these high-rigor protocols:\n")
            .append("1. SYSTEMIC GROUPING & NUANCE PRESERVATION: Do not simply list fragmented findings, nor should you compress them into generic summaries. Merge overlapping or related comments across different chapters into single, systemic observations. ")
            .append("CRITICAL: The resulting consolidated finding must be an additive assembly of all original nuances. If three findings identify different symptoms of the same flaw, the final comment must detail ALL three symptoms—do not sacrifice specificity for brevity.\n")
            .append("2. ARBITRATED CONFLICT RESOLUTION: When findings from different chapters contradict each other:\n")
            .append("   - Use the Global Technical Mapping as the Supreme Arbitrator to determine the technically accurate state.\n")
            .append("   - If the mapping provides a clear answer, retain only the correct version.\n")
            .append("   - If the conflict persists despite consulting the mapping, discard BOTH findings. In a high-rigor audit, an unresolvable contradiction is noise that risks providing incorrect guidance.\n")
            .append("3. EVIDENCE PROVENANCE (ABSOLUTE ID FIDELITY): Traceability to the raw evidence is non-negotiable. Preserve the exact hunk IDs from the source findings without modification or hallucination. ")
            .append("When grouping multiple findings into one systemic observation, you MUST collect and list every associated hunk ID as a comma-separated list at the top of the comment.\n")
            .append("4. SIGNAL INTEGRITY: Ensure final consolidated comments maintain the rigor of the original audits. They must remain evidence-based and actionable. Zero tolerance for hedging language ('consider', 'perhaps', 'maybe') or generic filler. Every finding must be a sharp, technical observation anchored in proof.\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS (STRICT MACHINE-READABLE SPECIFICATION)\n")
            .append("Your response must contain a list of finalized review comments wrapped in <review_comments> tags. ")
            .append("Do not include any introductory text, meta-commentary, or concluding remarks inside these tags.\n")
            .append("Each comment MUST strictly adhere to this two-part structure:\n")
            .append("1. THE HEADER LINE: The first line of each comment must consist ONLY of the comma-separated list of hunk IDs. ")
            .append("CRITICAL: Do not add labels, prefixes, or text such as 'IDs:' or 'Hunks:'. The line must contain nothing but the 4-character alphanumeric IDs and commas/spaces. Any extra characters will cause the parser to fail.\n")
            .append("2. THE REVIEW TEXT: All subsequent lines following the header are the high-fidelity review text for that finding.\n")
            .append("Separate individual comments with one or more blank lines. Ensure every single finding begins with its own Header Line.\n");

    return prompt.toString();
  }

  public static List<ReviewComment> parseResult(String response) throws Exception {
    if (response == null || response.isEmpty()) {
      return null;
    }
    ;

    List<ReviewComment> comments = new ArrayList<>();

    Pattern outerPattern = Pattern.compile("<review_comments>(.*?)</review_comments>", Pattern.DOTALL);
    Matcher outerMatcher = outerPattern.matcher(response);
    if (!outerMatcher.find()) {
      return null;
    }

    String content = outerMatcher.group(1).trim();
    String[] lines = content.split("\\r?\\n");

    String currentHunksStr = null;
    StringBuilder currentText = new StringBuilder();

    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.isEmpty()) continue;

      // A header line is one that consists only of valid hunk IDs and separators (commas, spaces)
      if (isHeaderLine(trimmedLine)) {
        // Commit previous comment if it exists
        if (currentHunksStr != null && currentText.length() > 0) {
          List<String> ids = extractHunkIds(currentHunksStr);
          if (!ids.isEmpty()) {
            comments.add(new ReviewComment(ids, currentText.toString().trim()));
          }
        }
        currentHunksStr = trimmedLine;
        currentText = new StringBuilder();
      } else {
        // Append to the current comment's text
        if (currentHunksStr != null) {
          if (currentText.length() > 0) {
            currentText.append("\n");
          }
          currentText.append(line);
        }
      }
    }

    // Commit the final comment
    if (currentHunksStr != null && currentText.length() > 0) {
      List<String> ids = extractHunkIds(currentHunksStr);
      if (!ids.isEmpty()) {
        comments.add(new ReviewComment(ids, currentText.toString().trim()));
      }
    }

    return comments.isEmpty() ? null : comments;
  }

  private static boolean isHeaderLine(String line) {
    if (line == null || line.isEmpty()) return false;
    // A line is a header if it contains at least one valid ID and NO other non-separator characters
    List<String> ids = extractHunkIds(line);
    if (ids.isEmpty()) return false;

    // Check if the line contains only IDs, commas, spaces
    String stripped = line.replaceAll("[A-Z0-9]{4}", "").replaceAll("[,\\s]", "");
    return stripped.isEmpty();
  }

  private static List<String> extractHunkIds(String hunksStr) {
    List<String> ids = new ArrayList<>();

    if (hunksStr == null) {
      return ids;
    }

    // Match exactly 4 characters from the ALPHABET (A-Z, 0-9), ensuring they are not part of a longer sequence
    Pattern p = Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{4}(?![A-Z0-9])");
    Matcher m = p.matcher(hunksStr);
    while (m.find()) {
      ids.add(m.group());
    }

    return ids;
  }

  public record ReviewComment(List<String> hunkIds, String text) {
  }

  public record ParsedResponse(String understanding, String result) {
  }

  public record StringIndex(String str, int index) {
  }
}
