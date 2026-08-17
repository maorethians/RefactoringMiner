package narrator.langchain.prompt;

import java.util.List;
import java.util.Set;

public class ReviewPrompt implements LangChainPrompt {
  @Override
  public String chapter(String content, Set<String> understandings) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are an expert Senior Software Engineer conducting a deep, rigorous code review of a pull request. ")
            .append("The PR's changes have been divided into 'chapters' based on dependencies. You are reviewing one specific chapter. ")
            .append("Your goal is to produce a high-signal technical analysis and modular review comments.\n\n");

    if (understandings != null && !understandings.isEmpty()) {
      prompt.append("### CONTEXT\n");
      prompt.append("Below are the 'Understandings' from previous chapters that this current chapter depends on. ")
              .append("Use these to understand the state of the code and any identifiers introduced or modified before this chapter:\n");
      for (String u : understandings) {
        prompt.append("\n---\n").append(u).append("\n");
      }
      prompt.append("\n");
    }

    prompt.append("### CURRENT CHAPTER CONTENT\n");
    prompt.append("This is the content of the current chapter you are reviewing:\n");
    prompt.append(content).append("\n\n");

    prompt.append("### YOUR TASK\n");
    prompt.append("Perform a thorough review from all necessary aspects (Correctness, Security, Performance, Maintainability, Readability) with maximum depth.\n\n");

    prompt.append("#### Step 1: UNDERSTANDING\n");
    prompt.append("Create a detailed technical analysis and reasoning workspace. You must:\n")
            .append("- Analyze all changed identifiers, their roles, and the purpose of the changes.\n")
            .append("- Explain how these changes interact with the dependency understandings provided above.\n")
            .append("- Explicitly list identifiers that may act as dependencies for subsequent chapters (i.e., what a later reviewer needs to know about this chapter).\n\n");

    prompt.append("#### Step 2: INTERMEDIATE_RESULT\n");
    prompt.append("Based on your understanding, provide the specific, high-signal contributions to the overall code review.\n")
            .append("- Focus on critical issues, potential bugs, or significant improvements.\n")
            .append("- The result must be modular and useful for a final synthesizer who has not read this chapter's raw content.\n")
            .append("- IMPORTANT: Every review comment MUST refer to the specific change ID (e.g., 'Change PNHM: ...') provided in the <added id=\"...\"> or <modified id=\"...\"> tags so that the comment can be traced back to the exact hunk.\n\n");

    prompt.append("### OUTPUT FORMAT\n");
    prompt.append("You must provide your response in exactly this format:\n\n");
    prompt.append("UNDERSTANDING:\n[Your detailed technical analysis and reasoning workspace]\n\n");
    prompt.append("INTERMEDIATE_RESULT:\n[Your modular review comments referencing change IDs]");

    return prompt.toString();
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
      prompt.append("\n--- Chapter ").append(i + 1).append(" Understanding ---\n")
              .append(understandings.get(i)).append("\n");
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

    prompt.append("### INPUT DATA\n");
    prompt.append("#### Global PR Understanding:\n").append(understanding).append("\n\n");
    prompt.append("#### Intermediate Results From Chapters:\n");
    for (int i = 0; i < results.size(); i++) {
      prompt.append("\n--- Result from Chapter ").append(i + 1).append(" ---\n")
              .append(results.get(i)).append("\n");
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
    prompt.append("Your output must be a simple list of review comments. Do not use JSON or YAML. Instead, follow these strict formatting rules:\n\n")
            .append("- Each comment must start with the hunk IDs it refers to in this format: `[Hunks: ID1, ID2]` (e.g., `[Hunks: PNHM, ABC1]`).\n")
            .append("- The review content follows immediately after the hunk IDs.\n")
            .append("- Use exactly `---COMMENT_SEPARATOR---` as a delimiter between separate review comments.\n\n");

    prompt.append("Example Output:\n")
            .append("[Hunks: PNHM]\nThe topic list fetch logic in AdminBrokerProcessor is missing a null check, which could lead to an NPE if the request header is malformed.\n")
            .append("---COMMENT_SEPARATOR---\n")
            .append("[Hunks: ABC1, DEF2]\nThe synchronization strategy across these two methods needs to be unified to prevent potential deadlocks in high-concurrency scenarios.");

    return prompt.toString();
  }
}
