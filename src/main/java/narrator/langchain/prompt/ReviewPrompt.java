package narrator.langchain.prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReviewPrompt implements LangChainPrompt {
  public record ReviewComment(List<String> hunkIds, String text) {}
  public record ParsedResponse(String understanding, String result) {}

  @Override

  public String chapter(String content, List<String> understandings) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are an expert senior software engineer conducting an objective, evidence-based technical review of a pull request. ")
            .append("The PR's changes have been divided into 'chapters' based on dependencies. You are reviewing one specific chapter. ")
            .append("Your goal is to identify actual defects and critical flaws. \n\n");

    prompt.append("### CURRENT CHAPTER CONTENT\n");
    prompt.append("This is the content of the current chapter you are reviewing:\n");
    prompt.append(content).append("\n\n");

    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("### CONTEXT\n");
      prompt.append("Below are the 'Understandings' from previous chapters which this current chapter depends on:\n");
      prompt.append(String.join("\n", understandings.stream().map(understanding -> "<understanding>\n" + understanding + "\n</understanding>").toList()));
      prompt.append("\n\n");
    }

    prompt.append("### YOUR TASK\n");
    prompt.append("#### Step 1: Technical Mapping (Understanding)\n")
            .append("Create a high-fidelity technical map of the changes. Focus on factual correctness and systemic intent:\n");
    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("- Leverage the provided dependency context in understanding the dependencies of the current chapter.\n");
    }
    prompt.append("- Map all changed identifiers, their roles, and how they interact.\n")
            .append("- Describe the logic flow: what is the intended behavior of these changes?\n");

    prompt.append("#### Step 2: High-Signal Review Comments (Result)\n")
            .append("Using your technical mapping from Step 1, conduct an objective review from different perspectives (including Functional Correctness, Security & Robustness, Resource Efficiency, Architectural Integrity, Maintainability, Observability, and Verification Rigor), and produce high-signal review comments adhering to these strict standards:\n")
            .append("- NO COMPLIMENTS: Do not praise the code.\n")
            .append("- NO GENERIC ADVICE: Avoid generic 'Verify' or 'Ensure' advice; every flagged issue must include a specific technical justification and a proposed fix.\n")
            .append("- NO SURFACE-LEVEL NITPICKS: Focus on systemic impact and technical correctness rather than trivial style issues.\n")
            .append("- Every review comment MUST reference the specific change IDs to ensure it is anchored to the exact hunks.\n\n");

    prompt.append("### OUTPUT FORMAT\n");
    prompt.append("You must provide your response in exactly this format:\n\n");
    prompt.append("<understanding>\n");
    prompt.append("[Your detailed technical mapping and understanding of the changes and identifiers within this chapter]\n");
    prompt.append("</understanding>\n\n");
    prompt.append("<result>\n");
    prompt.append("[Your modular, high-signal review comments referencing change IDs]\n");
    prompt.append("</result>\n\n");

    return prompt.toString();
  }

  public static ParsedResponse parseChapter(String response) {
    int understandingOpen = response.indexOf("<understanding>");
    int understandingClose = response.indexOf("</understanding>");
    int resultOpen = response.indexOf("<result>");
    int resultClose = response.indexOf("</result>");

    if (understandingOpen == -1 || understandingClose == -1 || understandingOpen >= understandingClose
            || resultOpen == -1 || resultClose == -1 || resultOpen >= resultClose) {
      return null;
    }

    String understanding = response.substring(understandingOpen + 15, understandingClose).trim();
    String result = response.substring(resultOpen + 8, resultClose).trim();
    return new ParsedResponse(understanding, result);
  }

  @Override
  public String understanding(List<String> understandings) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are a Senior System Architect. Your task is to synthesize the technical understandings of multiple components in a pull request into one single, comprehensive 'Global PR Understanding'.\n\n")
            .append("The changes of the PR have been divided into 'chapters' based on dependencies. ")
            .append("The input consists of separate understanding blocks from various chapters, ordered by their dependencies. ")
            .append("Together, they form a coherent narrative of the entire change set.\n\n");

    prompt.append("### INPUT DATA\n");
    prompt.append("Here are the understandings from all chapters:\n");
    for (int i = 0; i < understandings.size(); i++) {
      prompt.append("<chapter").append(i + 1).append(">\n");
      prompt.append(understandings.get(i)).append("\n");
      prompt.append("</chapter").append(i + 1).append(">\n");
    }

    prompt.append("\n### YOUR TASK\n");
    prompt.append("Synthesize these fragments into a unified, aggregated technical map of the pull request. ")
            .append("Your goal is to create a global view that allows any subsequent agent to understand the entire impact of the PR without reading individual chapters.\n\n")
            .append("CRITICAL REQUIREMENTS:\n")
            .append("- NO LOSS OF DETAIL: While you should be compact, you MUST NOT omit any identifiers mentioned across the chapters.\n")
            .append("- IDENTIFIER TRACKING: Extract every changed/introduced identifier and describe its role in the overall change.\n")
            .append("- CHANGE ANALYSIS: For each key entity, summarize exactly what was modified or added.\n")
            .append("- RELATIONAL MAPPING: Clearly define the cross-relations and dependencies between these identifiers (e.g., 'Identifier A was modified to support the new logic in Identifier B').\n")
            .append("- COHERENT NARRATIVE: Use the dependency order of the chapters to trace how changes propagate through the system.\n\n");

    prompt.append("### OUTPUT FORMAT\n");
    prompt.append("Please provide the Global PR Understanding using the following structure:\n\n")
            .append("1. OVERALL PURPOSE: A high-level summary of what this PR achieves technically.\n\n")
            .append("2. GLOBAL IDENTIFIER MAP: A comprehensive list of all affected identifiers, their roles, and the specific changes made to them.\n\n")
            .append("3. SYSTEM INTERACTION GRAPH: A description of how these identifiers relate to each other and how the flow of data/control has changed across the PR.\n\n")
            .append("4. CRITICAL IMPACT AREAS: Identification of the most sensitive parts of the system affected by this change.");

    return prompt.toString();
  }

  @Override
  public String result(List<String> results, String understanding) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are the Lead Reviewer and Final Synthesizer for reviewing a pull request. Your task is to aggregate all intermediate findings of multiple components in a PR into a single, comprehensive, and high-fidelity final report.\n\n")
            .append("The changes of the PR have been divided into 'chapters' based on dependencies. ")
            .append("You have two primary inputs:\n")
            .append("1. The GLOBAL PR UNDERSTANDING: A synthesized technical map of the entire change set.\n")
            .append("2. INTERMEDIATE RESULTS: Modular review findings from individual chapters, ordered by their dependencies.\n\n");

    prompt.append("### INPUT DATA\n\n");
    prompt.append("#### Global PR Understanding:\n").append("<understanding>\n").append(understanding).append("\n</understanding>\n\n");
    prompt.append("#### Intermediate Results From Chapters:\n");
    for (int i = 0; i < results.size(); i++) {
      prompt.append("<chapter").append(i + 1).append(">\n");
      prompt.append(results.get(i)).append("\n");
      prompt.append("</chapter").append(i + 1).append(">\n");
    }

    prompt.append("\n### YOUR TASK\n");
    prompt.append("Synthesize the intermediate results into a final list of review comments. You must follow these logic rules:\n\n")
            .append("1. DEDUPLICATION & MERGING: If there are multiple similar or overlapping comments, merge them into one clear, coherent comment that captures all relevant points.\n")
            .append("2. CONFLICT RESOLUTION: If two findings conflict:\n")
            .append("   - Use the GLOBAL PR UNDERSTANDING to determine which finding is technically accurate.\n")
            .append("   - If you can resolve the conflict, keep the correct version.\n")
            .append("   - If the conflict cannot be resolved with the provided information, eliminate both conflicting comments to avoid presenting contradictory advice.\n")
            .append("3. HUNK ID FIDELITY: You must preserve the exact hunk IDs from the source comments. Do not modify, hallucinate, or guess IDs. When merging multiple comments into one, ensure all associated hunk IDs are accurately collected.\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n");
    prompt.append("Your output must be a list of review comments wrapped in <review_comments> tags.\n")
            .append("Each review comment must follow this structural pattern:\n")
            .append("1. FIRST LINE: Must contain ONLY the comma-separated list of hunk IDs this comment refers to.\n")
            .append("2. SUBSEQUENT LINES: The detailed review text.\n\n")
            .append("Separate individual comments with one or more blank lines for clarity.");

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
