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
    prompt.append("You are a Senior System Architect. Your task is to synthesize the technical understandings of all chapters in a pull request into one single, comprehensive 'Global PR Understanding'.\n\n")
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
    return null;
  }
}
