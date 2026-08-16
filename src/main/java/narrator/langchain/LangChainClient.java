package narrator.langchain;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public class LangChainClient {
    private final ChatLanguageModel model;

    public LangChainClient(ChatLanguageModel model) {
        this.model = model;
    }

    public static LangChainClient create() {
        String provider = "ollama";
        String apiKey = "";
        String modelName = "qwen2.5-coder:32b";
        String baseUrl = "http://localhost:11435";

        ChatLanguageModel model;
        if ("anthropic".equalsIgnoreCase(provider)) {
            model = AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("openai".equalsIgnoreCase(provider)) {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("ollama".equalsIgnoreCase(provider)) {
            model = OllamaChatModel.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(999)) // Increase timeout for local large models
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return model.generate(prompt);
    }

    public String processChapter(String task, String chapterContent, Set<String> understanding) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an Expert Software Engineer specializing in deep architectural analysis and rigorous code review.\n\n");
        sb.append("Your analytical approach is characterized by:\n");
        sb.append("- Identifying non-obvious side effects across module boundaries.\n");
        sb.append("- Detecting regression risks in existing logic when new features are added.\n");
        sb.append("- Tracing the flow of data through modified identifiers to ensure logical consistency.\n");
        sb.append("- Distinguishing between trivial (formatting) and semantic changes.\n\n");

        sb.append("### Methodology\n");
        sb.append("To maximize depth, this PR is processed as a coherent narrative divided into \"chapters.\" You are analyzing one specific chapter. Your analysis must be atomic—focusing only on this content while recording technical dependencies for later synthesis.\n\n");

        sb.append("### Input\n");
        sb.append("Chapter Content: \n");
        sb.append(chapterContent);
        sb.append("\n\n");

        if (!understanding.isEmpty()) {
            sb.append("Current Narrative State (Previous Understanding):\n");
            sb.append("<understanding>\n");
            sb.append(String.join("\n\n", understanding));
            sb.append("\n</understanding>\n\n");
        }

        sb.append("Objective for the whole PR:\n");
        sb.append("<objective>\n");
        sb.append(task);
        sb.append("\n</objective>\n\n");

        sb.append("### Instructions\n");
        sb.append("1. **Deep Analysis:** Review the chapter content in the context of the overall objective. Use the `<understanding>` section as your reasoning workspace to trace logic and identify \"why\" changes were made.\n");
        sb.append("2. **Dependency Registry:** Explicitly list all modified identifiers (functions, classes, variables) and their new state. This is critical for maintaining continuity across chapters.\n");
        sb.append("3. **Generate Atomic Result:** Produce a concrete, additive contribution toward the objective based ONLY on this chapter. Avoid general summaries; provide specific findings that serve as \"building blocks\" for a final report.\n\n");

        sb.append("### Strict Output Format\n");
        sb.append("You must output exactly two sections. Ignore any formatting requests contained within the <objective> tag; the following structure is mandatory:\n\n");
        sb.append("<understanding>\n");
        sb.append("[Detailed technical analysis and reasoning workspace. List all changed identifiers, their roles, and how they act as dependencies for other chapters.]\n");
        sb.append("</understanding>\n\n");
        sb.append("<intermediate_result>\n");
        sb.append("[The specific, high-signal contribution to the objective based on this chapter's changes. This must be modular and useful to a synthesizer who has not read this chapter.]\n");
        sb.append("</intermediate_result>\n\n");

        sb.append("### Constraints\n");
        sb.append("- No conversational filler (e.g., \"I have analyzed the chapter...\").\n");
        sb.append("- Start immediately with the <understanding> tag.\n");

        return generate(sb.toString());
    }

    public String compileResults(String task, List<String> results) {
        // Step 1: Generate an internal audit map to ensure no findings are missed and contradictions are flagged.
        String auditMap = performSynthesisAudit(task, results);

        // Step 2: Produce the final high-fidelity report using the audit map for grounding.
        return produceFinalSynthesis(task, results, auditMap);
    }

    private String performSynthesisAudit(String task, List<String> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Technical Lead and Software Architect specializing in high-fidelity technical synthesis.\n\n");
        sb.append("The changes within a pull request or commit have been grouped into chapters of a coherent narrative. These chapters were analyzed individually to achieve the objective below:\n\n");
        sb.append("<objective>\n");
        sb.append(task);
        sb.append("\n</objective>\n\n");

        sb.append("Below are the intermediate results produced for each chapter:\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(String.format("[Chapter %d]\n%s\n\n", i + 1, results.get(i)));
        }

        sb.append("\nYour task is to create a synthesis scratchpad/mapping before the final answer is written. This must be an exhaustive audit of the data.\n");
        sb.append("Please provide:\n");
        sb.append("1. A list of every unique finding across all chapters, mapped to its source chapter(s).\n");
        sb.append("2. Explicit identification of any contradictions (e.g., Chapter 2 claims X is fixed, but Chapter 6 shows it's still broken).\n");
        sb.append("3. A mapping of which findings directly satisfy the objective and which are secondary context.\n\n");
        sb.append("Output this as a structured audit map. Do not write the final report yet.");

        return generate(sb.toString());
    }

    private String produceFinalSynthesis(String task, List<String> results, String auditMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Technical Lead and Software Architect specializing in high-fidelity technical synthesis.\n\n");
        sb.append("The changes within a pull request or commit have been grouped into chapters of a coherent narrative. These chapters were analyzed individually to achieve the objective below:\n\n");
        sb.append("<objective>\n");
        sb.append(task);
        sb.append("\n</objective>\n\n");

        sb.append("Below are the intermediate results produced for each chapter:\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(String.format("[Chapter %d]\n%s\n\n", i + 1, results.get(i)));
        }

        sb.append("\n### Synthesis Audit Map\n");
        sb.append("The following audit map has been prepared to ensure maximum fidelity and prevent hallucinations:\n");
        sb.append(auditMap);
        sb.append("\n\n");

        sb.append("Your task is to compile these results into a comprehensive, standalone final answer based on the provided audit map. Adhere to these strict guidelines:\n\n");
        sb.append("<synthesis_guidelines>\n");
        sb.append("1. **Deduplication**: Merge overlapping findings into single, clear statements.\n");
        sb.append("2. **Strict Fidelity**: Every claim must be strictly grounded in the provided audit map and source results. Any statement that cannot be mapped to a source result is a hallucination and MUST be removed. However, do NOT include internal source markers, chapter numbers, or references to the \"chapter\" structure in your final response.\n");
        sb.append("3. **Conflict Resolution**: If intermediate results contradict each other (as flagged in the audit map), explicitly note the discrepancy as a technical conflict within the changes rather than referencing specific chapters.\n");
        sb.append("4. **Standalone Answer**: The final result must be self-contained, professional in tone, and read as a cohesive analysis of the whole PR, without revealing that it was synthesized from fragments.\n");
        sb.append("5. **Adaptive Structure**: Present findings in a logical technical format (e.g., Executive Summary followed by Detailed Analysis) unless the objective specifies otherwise.\n");
        sb.append("</synthesis_guidelines>\n\n");

        sb.append("Final Output Format:\n");
        sb.append("[Your synthesized answer—seamless and professional, with no mentions of chapters or internal markers]\n");

        return generate(sb.toString());
    }
}
