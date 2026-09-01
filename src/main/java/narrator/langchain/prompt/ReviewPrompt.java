package narrator.langchain.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.refactoringminer.astDiff.graph.Node;

public class ReviewPrompt {
  private String chapterSpecification() {
    StringBuilder spec = new StringBuilder();

    spec.append("### CHANGE REPRESENTATION\n")
            .append("The chapter is not a textual diff. It is a structured view derived from an AST comparison of the two revisions, ")
            .append("in which related edits have already been grouped.\n")
            .append("- <sub_chapter> groups edits that share an enclosing construct; it is one coherent unit of work.\n")
            .append("- <change> holds one edit. A before_* element paired with an after_* element is the same code before and after that edit—one change, not two. ")
            .append("A lone <added> or <deleted> element is an insertion or a removal with no counterpart.\n")
            .append("- Element tags name the operation: <added>, <deleted>, <unchanged>, and the paired forms before_change/after_change (edited in place), ")
            .append("before_move/after_move (relocated intact), and before_move_and_change/after_move_and_change (both). ")
            .append("- <context> shows the enclosing construct before and after the edit. It restates, in situ, the same code that the <change> elements isolate. ")
            .append("It is orientation, not additional change.\n")
            .append("- <dependencies> and <dependency> contain code that the edits in this chapter depend on, supplied so that identifiers resolve.\n")
            .append("- Every element carries location=\"<file>::<Type>#<member>\", the construct enclosing it, and id=\"#XXXXX\", which identifies it uniquely within the pull request.\n")
            .append("Edits are captured at expression and statement granularity, so a single logical change is routinely spread across several <change> blocks inside one <sub_chapter>. ")
            .append("The blocks carry no line numbers, and the order in which they appear is not the order of the code in the file; the code inside <context> is where the real sequence is visible.\n\n");

    return spec.toString();
  }

  // TODO: our representation must be the most effective one for understanding the changes. Are we using it at the highest level of effectiveness?
  public String chapterUnderstanding(String content, List<String> dependencyUnderstandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer specializing in complex system architecture. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Your objective is to architect a high-fidelity technical map of the changes within a single chapter. ")
            .append("This map must serve as the definitive systemic ground truth for a subsequent rigorous audit")
            .append("—meaning you must capture the deep semantic intent and architectural implications of every change, rather than providing a surface-level summary.\n\n");

    prompt.append(chapterSpecification());

    prompt.append("### CURRENT CHAPTER\n")
            .append("The following block contains the changes that make up the current chapter, in the representation described above. This is your primary source of truth for the technical mapping task:\n")
            .append(content).append("\n\n");

    if (dependencyUnderstandings != null && !dependencyUnderstandings.isEmpty()) {
      prompt.append("### DEPENDENCY CONTEXT\n")
              .append("To ensure systemic continuity, you are provided with the technical mappings from preceding chapters. ")
              .append("These establish the architectural baseline and dependency chain necessary to resolve identifier references and interpret the systemic intent of the current chapter:\n")
              .append(String.join("\n", dependencyUnderstandings.stream().map(dependencyUnderstanding -> "<technical_mapping>\n" + dependencyUnderstanding + "\n</technical_mapping>").toList()))
              .append("\n\n");
    }

    prompt.append("### YOUR TASK\n");
    prompt.append("#### Construction of the High-Fidelity Technical Map\n")
            .append("You must architect a comprehensive technical map of this chapter. This map is the definitive systemic ground truth for a subsequent high-rigor audit; ")
            .append("any omission or ambiguity here will directly compromise the quality of the final review. ")
            .append("Your goal is to eliminate all surface-level interpretation and replace it with deep architectural deduction. ")
            .append("Zero tolerance for generic summaries. Avoid phrases like 'improved performance' or 'cleaned up code.' Instead, provide evidence-based technical details (e.g., 'replaced linear search with a binary search to reduce lookup time from O(n) to O(log n)').\n")
            .append("STRICT ANCHORING: Every mapping MUST be anchored to one or more specific change IDs.\n");
    if (dependencyUnderstandings != null && !dependencyUnderstandings.isEmpty()) {
      prompt.append("CRITICAL: Use the provided Dependency Context as your baseline. You must explicitly bridge the gap between previous chapters and this one, resolving identifier references and documenting how these changes evolve the system state established in preceding mappings.\n");
    }
    prompt.append("Your mapping MUST be structured into two rigorous dimensions:\n")
            .append("1. SYSTEMIC IDENTIFIER TRACKING: Provide a precise map of every modified or introduced identifier (classes, methods, variables). For each, you must document:\n")
            .append("   - THE DELTA: Exactly how the role or responsibility has changed.\n")
            .append("   - ARCHITECTURAL IMPACT: The ripple effect this change has on dependent components and systemic interactions.\n")
            .append("2. LOGIC & INTENT DEDUCTION: Perform a deep trace of the logic flow to deduce the exact intended behavior. You must provide:\n")
            .append("   - TECHNICAL JUSTIFICATION: The specific technical reason for this implementation path.\n")
            .append("   - BEHAVIORAL RESULT: The precise resulting systemic behavior.\n");

    return prompt.toString();
  }

  // TODO: is checking against mapping is the effective approach?
  public String chapterResult(String content, String understanding) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer and Lead Technical Auditor known for meticulous rigor and a zero-tolerance policy for low-signal noise. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Your objective is to conduct a high-signal audit of a single chapter, utilizing a pre-synthesized technical map to identify critical flaws, systemic risks, and architectural regressions.\n\n");

    prompt.append(chapterSpecification());

    prompt.append("### CURRENT CHAPTER\n")
            .append("The following block contains the changes that make up this chapter, in the representation described above. This serves as your primary evidentiary source for the audit:\n")
            .append(content).append("\n\n");

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
            .append("- STRICT ANCHORING: Every comment MUST be anchored to one or more specific change IDs.\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n")
            .append("Your response must be a list of review comments. Adhere to this structure for each comment:\n")
            .append("- LINE 1: Only the comma-separated list of change IDs.\n")
            .append("- SUBSEQUENT LINES: The high-fidelity review text.\n")
            .append("Separate individual comments with one or more blank lines.");

    return prompt.toString();
  }

  public String result(List<String> results, List<String> understandings) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Principal Software Engineer and Lead Synthesizer performing a final technical audit of a pull request. ")
            .append("The review process has been modularized: the PR was decomposed into chapters, and each chapter was audited independently. ")
            .append("Your objective is to synthesize these modular findings into a cohesive, high-rigor final report that avoids fragmentation and redundancy.\n\n")
            .append("You have two primary inputs:\n")
            .append("1. TECHNICAL MAPPINGS: The technical map of each chapter. Use them as your ground truth for systemic architecture and identifier tracking.\n")
            .append("2. MODULAR CHAPTER FINDINGS: Independent audit results from each chapter, strictly anchored to change IDs.\n\n");

    prompt.append("### INPUT DATA\n\n");
    prompt.append("#### Technical Mappings:\n");
    for (int i = 0; i < understandings.size(); i++) {
      prompt.append("<chapter_").append(i + 1).append(">\n");
      prompt.append(understandings.get(i)).append("\n");
      prompt.append("</chapter_").append(i + 1).append(">\n");
    }
    prompt.append("\n");
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
            .append("   - Consult the Technical Mappings to determine the technically accurate state.\n")
            .append("   - Keep only the correct version.\n")
            .append("   - If the conflict cannot be resolved with absolute certainty, discard BOTH comments. It is better to omit a finding than to provide contradictory or incorrect guidance.\n")
            .append("3. ABSOLUTE ID FIDELITY: Traceability is critical. Preserve the exact hunk IDs from the source findings. Do not modify or hallucinate IDs. When consolidating multiple findings into one, you MUST collect and list all associated hunk IDs for that consolidated comment.\n")
            .append("4. SIGNAL PRESERVATION: Maintain the high-signal standard of the original audits. Ensure final comments remain evidence-based, actionable, and free of hedging language (e.g., avoid 'consider', 'perhaps', 'maybe').\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n")
            .append("Your response must be a list of finalized review comments wrapped in <review_comments> tags.\n")
            .append("Each comment MUST strictly adhere to this structure:\n")
            .append("- LINE 1: Only the comma-separated list of hunk IDs.\n")
            .append("- SUBSEQUENT LINES: The high-fidelity review text.\n")
            .append("Separate individual comments with one or more blank lines.");

    return prompt.toString();
  }

  public static List<ReviewComment> parseResult(String response) {
    if (response == null || response.isEmpty()) {
      return null;
    }

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
    String stripped = Node.PROMPT_ID_PATTERN.matcher(line).replaceAll("").replaceAll("[,\\s]", "");
    return stripped.isEmpty();
  }

  private static List<String> extractHunkIds(String hunksStr) {
    List<String> ids = new ArrayList<>();

    if (hunksStr == null) {
      return ids;
    }

    Matcher m = Node.PROMPT_ID_PATTERN.matcher(hunksStr);
    while (m.find()) {
      ids.add(m.group());
    }

    return ids;
  }

  public record ReviewComment(List<String> hunkIds, String text) {
    @NotNull
    @Override
    public String toString() {
      return String.join(",", hunkIds) + "\n" + text;
    }
  }

  public record ParsedResponse(String understanding, String result) {
  }
}
