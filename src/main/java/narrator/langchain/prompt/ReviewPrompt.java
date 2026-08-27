package narrator.langchain.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReviewPrompt implements LangChainPrompt {
  public record ReviewComment(List<String> hunkIds, String text) {}
  public record ParsedResponse(String understanding, String result) {}

  @Override

  public String chapter(String content, List<String> understandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer specializing in complex system architecture and high-rigor technical audits. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Your current objective is to perform a deep-dive review of a single chapter from this sequence.\n\n");

    prompt.append("### CURRENT CHAPTER\n")
         .append("The following content constitutes the current chapter, containing the actual code changes and diffs that must be reviewed:\n")
         .append(content).append("\n\n");

    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("### DEPENDENCY CONTEXT\n")
              .append("The following technical mappings were synthesized from previous chapters. These represent the prerequisite architectural knowledge that the current chapter depends on:\n")
              .append(String.join("\n", understandings.stream().map(understanding -> "<technical_mapping>\n" + understanding + "\n</technical_mapping>").toList()))
              .append("\n\n");
    }

    prompt.append("### YOUR TASK\n");
    prompt.append("#### Step 1: High-Fidelity Technical Mapping\n")
            .append("Before beginning your review, you must construct a comprehensive technical map of the changes in this chapter. ")
            .append("This is a mandatory analytical phase to prevent surface-level analysis and ensure deep systemic understanding. ");
    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("Integrate the provided Dependency Context to resolve references and understand how these changes build upon previous chapters. ");
    }
    prompt.append("Your mapping must include:\n")
            .append("- IDENTIFIER TRACKING: A precise map of every modified or introduced identifier (classes, methods, variables), documenting their roles, responsibilities, and systemic interactions.\n")
            .append("- LOGIC & INTENT ANALYSIS: A trace of the logic flow to deduce the exact intended behavior and its technical justification.\n");
    prompt.append("#### Step 2: High-Signal Audit\n")
            .append("Using your Technical Mapping, conduct a rigorous multi-dimensional audit (Correctness, Security, Efficiency, Maintainability, Observability, and Verification). ")
            .append("Produce high-signal review comments adhering to these absolute standards:\n")
            .append("- EVIDENCE-BASED ONLY: No compliments and no generic advice. Zero tolerance for hedging language (e.g., 'consider...', 'ensure...', 'it might be...'). Every finding must provide a specific technical justification for the flaw and a concrete, actionable resolution.\n")
            .append("- SYSTEMIC OVER SURFACE: Prioritize systemic architectural flaws over trivial style or formatting issues.\n")
            .append("- ATOMICITY & UNICITY: Consolidate multiple occurrences of the same pattern into a single finding and explicitly list all affected change IDs for that finding. Each unique issue must be reported exactly once; redundant or repeated comments are strictly forbidden as they degrade signal-to-noise ratio.\n")
            .append("- STRICT ANCHORING: Every comment MUST be anchored to one or more specific change IDs.\n\n");

    prompt.append("### OUTPUT FORMAT\n")
            .append("CRITICAL: You must provide your response in EXACTLY this format. The following tags are mandatory for parsing; any deviation (including omitting the tags) will cause the process to fail.\n\n")
            .append("<mapping>\n")
            .append("[Complete result of Step 1: High-Fidelity Technical Mapping, including precise identifier tracking and logic analysis]\n")
            .append("</mapping>\n\n")
            .append("<findings>\n")
            .append("[Complete result of Step 2: High-Signal Audit Findings. Each finding must be unique and reported exactly once; redundant comments are strictly forbidden. All findings must be strictly anchored to change IDs and supported by technical justification and actionable resolution]\n")
            .append("</findings>");

    return prompt.toString();
  }

  public static ParsedResponse parseChapter(String response) {
    int understandingOpen = response.indexOf("<mapping>");
    int understandingClose = response.indexOf("</mapping>");
    int resultOpen = response.indexOf("<findings>");
    int resultClose = response.indexOf("</findings>");

    if (understandingOpen == -1 || understandingClose == -1 || understandingOpen >= understandingClose
            || resultOpen == -1 || resultClose == -1 || resultOpen >= resultClose) {
      return null;
    }

    String understanding = response.substring(understandingOpen + 9, understandingClose).trim();
    String result = response.substring(resultOpen + 10, resultClose).trim();
    return new ParsedResponse(understanding, result);
  }

  @Override
  public String understanding(List<StringIndex> understandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Senior System Architect. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("For each chapter, a high-fidelity technical map has been produced, focusing on precise identifier tracking and logic analysis. ")
            .append("Your task is to synthesize the mappings of consecutive chapters into a single, unified comprehensive mapping that preserves the full technical rigor of the originals.\n\n");

    prompt.append("### INPUT: CHAPTER TECHNICAL MAPPINGS\n")
            .append("Below are the technical mappings for consecutive chapters:\n");
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
            .append("Synthesize these inputs into a unified technical map. The resulting mapping MUST follow the exact structural format of the original chapter mappings, consisting of two primary sections:\n")
            .append("1. IDENTIFIER TRACKING: A consolidated and precise map of every modified or introduced identifier across all provided chapters, documenting their roles, responsibilities, and systemic interactions.\n")
            .append("2. LOGIC & INTENT ANALYSIS: A synthesized trace of the logic flow that captures the overall intended behavior and technical justification across these chapters.\n\n")
            .append("CRITICAL REQUIREMENTS:\n")
            .append("- NO LOSS OF DETAIL: This is a technical synthesis, not a summary. You MUST NOT omit any identifiers or critical logic steps mentioned in any of the input mappings.\n")
            .append("- HIGH-FIDELITY AGGREGATION: While you should eliminate redundancy, you must ensure that the systemic interactions between different chapters are clearly articulated in the unified map.\n")
            .append("- STRUCTURAL ADHERENCE: The output must be a direct aggregation of the input format; do not introduce new sections or change the existing ones.");

    return prompt.toString();
  }

  @Override
  public String result(List<String> results, String understanding) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer and Lead Synthesizer performing a final technical audit of a pull request. ")
            .append("The review process has been modularized: the PR was decomposed into chapters, and each chapter was audited independently. ")
            .append("Your objective is to synthesize these modular findings into a cohesive, high-rigor final report that avoids fragmentation and redundancy.\n\n")
            .append("You have two primary inputs:\n")
            .append("1. THE GLOBAL TECHNICAL MAPPING: A unified technical map of all changes across the PR. Use this as your ground truth for systemic architecture and identifier tracking.\n")
            .append("2. MODULAR CHAPTER FINDINGS: Independent audit results from each chapter, strictly anchored to change IDs.\n\n");

    prompt.append("### INPUT DATA\n\n");
    prompt.append("#### Global Technical Mapping:\n").append("<mapping>\n").append(understanding).append("\n</mapping>\n\n");
    prompt.append("#### Modular Findings From Chapters:\n");
    for (int i = 0; i < results.size(); i++) {
      prompt.append("<chapter_").append(i + 1).append(">\n");
      prompt.append(results.get(i)).append("\n");
      prompt.append("</chapter_").append(i + 1).append(">\n");
    }
    prompt.append("\n");

    prompt.append("### SYNTHESIS LOGIC & RULES\n")
            .append("Transform the modular findings into a final list of review comments by applying these rules:\n\n")
            .append("1. SEMANTIC CONSOLIDATION: Do not simply list findings. Merge overlapping or related comments across different chapters into single, systemic observations. For example, if multiple chapters identify symptoms of the same underlying architectural flaw, synthesize them into one comprehensive finding.\n")
            .append("2. RIGOROUS CONFLICT RESOLUTION: If findings from different chapters contradict each other:\n")
            .append("   - Consult the Global Technical Mapping to determine the technically accurate state.\n")
            .append("   - Keep only the correct version.\n")
            .append("   - If the conflict cannot be resolved with absolute certainty, discard BOTH comments. It is better to omit a finding than to provide contradictory or incorrect guidance.\n")
            .append("3. ABSOLUTE ID FIDELITY: Traceability is critical. Preserve the exact hunk IDs from the source findings. Do not modify or hallucinate IDs. When consolidating multiple findings into one, you MUST collect and list all associated hunk IDs for that consolidated comment.\n")
            .append("4. SIGNAL PRESERVATION: Maintain the high-signal standard of the original audits. Ensure final comments remain evidence-based, actionable, and free of hedging language (e.g., avoid 'consider', 'perhaps', 'maybe').\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n")
            .append("Your response must be a list of finalized review comments wrapped in <review_comments> tags.\n")
            .append("Each comment MUST strictly adhere to this structure:\n")
            .append("- LINE 1: Only the comma-separated list of hunk IDs.\n")
            .append("- SUBSEQUENT LINES: The high-fidelity review text.\n\n")
            .append("Separate individual comments with one or more blank lines.");

    return prompt.toString();
  }

  public static List<ReviewComment> parseResult(String response) throws Exception {
    if (response == null || response.isEmpty()) {
      return null;
    };

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
}
