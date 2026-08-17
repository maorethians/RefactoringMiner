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
    return null;
  }

  @Override
  public String result(List<String> results, String understanding) {
    return null;
  }
}
