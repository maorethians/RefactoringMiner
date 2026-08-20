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
    prompt.append("You are an expert Senior Software Engineer and a critical code auditor conducting a deep, rigorous, and adversarial code review of a pull request. ")
            .append("The PR's changes have been divided into 'chapters' based on dependencies. You are reviewing one specific chapter. ")
            .append("Your goal is to identify non-obvious flaws, architectural misalignments, and significant optimization opportunities. Avoid surface-level nitpicks; focus on systemic impact and technical correctness.\n\n");

    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("### CONTEXT\n");
      prompt.append("Below are the 'Understandings' from previous chapters that this current chapter depends on. ")
              .append("Use these to understand the state of the code and any identifiers introduced or modified before this chapter:\n");
      prompt.append(String.join("\n", understandings.stream().map(understanding -> "<understanding>\n" + understanding + "\n</understanding>").toList()));
      prompt.append("\n\n");
    }

    prompt.append("### CURRENT CHAPTER CONTENT\n");
    prompt.append("This is the content of the current chapter you are reviewing:\n");
    prompt.append(content).append("\n\n");

    prompt.append("### YOUR TASK\n")
            .append("Perform an exhaustive review focusing on Correctness, Security, Performance, Maintainability, and Readability. ")
            .append("Assume the code contains at least one subtle flaw or a significant optimization opportunity—your job is to find it.\n\n");

    prompt.append("#### Step 1: Deep Technical Analysis (Understanding)\n")
            .append("Create a detailed reasoning workspace. You must be adversarial and thorough:\n")
            .append("- Analyze all changed identifiers, their roles, and the purpose of the changes.\n")
            .append("- Search for edge cases: check for nulls, empty collections, timeouts, overflows, or unhandled exceptions.\n")
            .append("- Evaluate Concurrency & Resources: identify potential race conditions, deadlocks, or resource leaks (e.g., unclosed streams).\n")
            .append("- Analyze Complexity: look for inefficient algorithms (O(n^2) where O(n log n) is possible) or suboptimal data structure choices.\n")
            .append("- Check Architectural Alignment: does this change deviate from existing patterns or introduce technical debt?\n");
    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("- Explain exactly how these changes interact with the dependency understandings provided above, and if those interactions introduce new risks.\n");
    }
    prompt.append("- Explicitly list identifiers that may act as dependencies for subsequent chapters.\n\n");

    prompt.append("#### Step 2: High-Signal Review Comments (Intermediate Result)\n")
            .append("Based on your analysis, produce the review findings. You must adhere to these strict quality standards:\n")
            .append("- NO COMPLIMENTS: Do not praise the code or tell the author they did a good job.\n")
            .append("- NO GENERIC ADVICE: Forbidden are comments like 'ensure X is handled' or 'consider checking Y'. If you suspect an issue, explain EXACTLY why it is a problem in this specific context and provide the fix.\n")
            .append("- SPECIFIC ALTERNATIVES: When suggesting an improvement, refactoring, or rewrite, do not be vague. Provide the concrete logic, a pseudo-code sketch, or a detailed description of the better approach.\n")
            .append("- FOCUS ON IMPACT: Prioritize critical bugs and architectural flaws over stylistic preferences.\n")
            .append("- IMPORTANT: Every review comment MUST refer to the specific change IDs (e.g., 'Change PNHM: ...') so it can be traced back to the exact hunks.\n\n");

    prompt.append("### OUTPUT FORMAT\n");
    prompt.append("You must provide your response in exactly this format:\n\n");
    prompt.append("<understanding>\n");
    prompt.append("[Your detailed technical analysis and adversarial reasoning workspace]\n");
    prompt.append("</understanding>\n\n");
    prompt.append("<intermediate_result>\n");
    prompt.append("[Your modular, specific review comments referencing change IDs]\n");
    prompt.append("</intermediate_result>\n");

    return prompt.toString();
  }

  public static ParsedResponse parseChapter(String response) {
    int understandingOpen = response.indexOf("<understanding>");
    int understandingClose = response.indexOf("</understanding>");
    int resultOpen = response.indexOf("<intermediate_result>");
    int resultClose = response.indexOf("</intermediate_result>");

    if (understandingOpen == -1 || understandingClose == -1 || understandingOpen >= understandingClose
            || resultOpen == -1 || resultClose == -1 || resultOpen >= resultClose) {
      return null;
    }

    String understanding = response.substring(understandingOpen + 15, understandingClose).trim();
    String result = response.substring(resultOpen + 21, resultClose).trim();
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
            .append("1. DEDUPLICATION & MERGING: If multiple chapters produced similar or overlapping comments on the same change(s), merge them into one clear, coherent comment that captures all relevant points.\n")
            .append("2. CONFLICT RESOLUTION: If two findings conflict (e.g., one suggests a change is correct and another flags it as a bug):\n")
            .append("   - Use the GLOBAL PR UNDERSTANDING to determine which finding is technically accurate.\n")
            .append("   - If you can resolve the conflict, keep the correct version.\n")
            .append("   - If the conflict cannot be resolved with the provided information, eliminate both conflicting comments to avoid presenting contradictory advice.\n")
            .append("3. SIGNAL FILTERING: Ensure only high-signal findings make it to the final report. Redundant or trivial observations should be consolidated.\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n");
    prompt.append("Your output must be a structured list of review comments wrapped in <review_comments> tags. Each comment must be enclosed in <comment> tags with separate fields for hunk IDs and the review text:\n\n")
            .append("- Use `<hunks>` to list the hunk IDs this comment refers to (e.g., `<hunks>PNHM, ABC1</hunks>`).\n")
            .append("- Use `<text>` for the actual review content.\n\n");

    prompt.append("Example Output:\n")
            .append("<review_comments>\n")
            .append("  <comment>\n")
            .append("    <hunks>PNHM</hunks>\n")
            .append("    <text>The topic list fetch logic in AdminBrokerProcessor is missing a null check, which could lead to an NPE if the request header is malformed.</text>\n")
            .append("  </comment>\n")
            .append("  <comment>\n")
            .append("    <hunks>ABC1, DEF2</hunks>\n")
            .append("    <text>The synchronization strategy across these two methods needs to be unified to prevent potential deadlocks in high-concurrency scenarios.</text>\n")
            .append("  </comment>\n")
            .append("</review_comments>");

    return prompt.toString();
  }

  public static List<ReviewComment> parseResult(String response) throws Exception {
    List<ReviewComment> comments = new ArrayList<>();
    if (response == null || response.isEmpty()) {
      return comments;
    };

    Pattern commentPattern = Pattern.compile("<comment>(.*?)</comment>", Pattern.DOTALL);
    Matcher commentMatcher = commentPattern.matcher(response);
    while (commentMatcher.find()) {
      String content = commentMatcher.group(1);

      String hunksStr = extractTagContent(content, "hunks");
      String text = extractTagContent(content, "text");

      if (hunksStr != null && text != null) {
        List<String> hunkIds = Arrays.stream(hunksStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        comments.add(new ReviewComment(hunkIds, text));
      } else {
        throw new Exception("Invalid Comment Format:" + content);
      }
    }

    return comments;
  }

  private static String extractTagContent(String input, String tag) {
    Pattern p = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
    Matcher m = p.matcher(input);
    return m.find() ? m.group(1).trim() : null;
  }
}
