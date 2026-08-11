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
        String modelName = "qwen3.6:35b";
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
        sb.append("You are an Expert Software Engineer specialized at deeply reviewing and analyzing changes within commits and pull requests.\n");
        sb.append("Instead of dealing with a commit or PR as a long list of seemingly isolated changes which reduces the depth of analysis and understanding of the changes for any objective, the changes are grouped and ordered by their relations and dependencies into chapters of a coherent narrative, to focus on one chapter at a time to increase the throughness of the analysis of the changes necessary for any requested objectives on those changes.\n\n");

        sb.append("The focus of your task is on the following chapter of the narrative for the commit or PR:\n");
        sb.append(chapterContent);
        sb.append("\n\n");

        if (!understanding.isEmpty()) {
            sb.append("The analysis and understanding of the agents focused on chapters containing some dependencies of the current chapter are kept for you and provided below. Take advantage of these understandings during the analysis of the current chapter for a more informed and detailed analysis and understanding of the current chapter:");
            sb.append("<understanding>\n");
            sb.append(String.join("\n\n", understanding));
            sb.append("\n</understanding>\n\n");
        }

        sb.append("Following the analysis and understanding of the current chapter, your task is to pursue the objective below specifically for this chapter and produce an intermediate result for this objective on this chapter. This intermediate result for this chapter will finally be aggregated with the intermediate results produced for the rest of the chapters to synthesize a final result for accomplishing the objective for the whole commit or PR:\n");
        sb.append("<objective>\n");
        sb.append(task);
        sb.append("\n</objective>\n\n");

        sb.append("You must produce your output for this chapter with exactly two sections of understanding and intermediate result in the following format. Any format that may have been requested in the objective will be considered during synthesizing the final result and the format below must be prioritized for your output:\n");
        sb.append("<understanding>{Your analysis and understanding of the current chapter. This understanding must capture all the identifiers and changes made to them in this chapter which may be referenced in other chapters and may act as a dependency in the changes of the other chapters.}</understanding>\n");
        sb.append("<intermediate_result>{The intermediate result of pursuing the objective specifically for the current chapter. This intermediate result will directly contribute to synthesizing the final result beside the intermediate result of the rest of the chapters for fulfilling the objective on the whole commit or PR.}</intermediate_result>\n");

        return generate(sb.toString());
    }

    public String compileResults(String task, List<String> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# PERSONA\n");
        sb.append("You are a Technical Auditor and Synthesis Expert. Your primary objective is absolute fidelity to the source fragments. You value accuracy over narrative flow; if the data is fragmented, the report should reflect that fragmentation rather than smoothing it over with assumptions.\n\n");

        sb.append("# TASK\n");
        sb.append(String.format("Compile the following intermediate results into a comprehensive final answer for the objective:\n%s\n\n", task));

        sb.append("# INTERMEDIATE RESULTS\n");
        sb.append("The following fragments were extracted sequentially from the narrative:\n");
        sb.append("<fragments>\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(String.format("[Fragment %d]: %s\n\n", i + 1, results.get(i)));
        }
        sb.append("</fragments>\n\n");

        sb.append("# SYNTHESIS GUIDELINES\n");
        sb.append("When merging these fragments, apply the following logic:\n\n");
        sb.append("1. **Deduplication**: Identify and merge overlapping findings. If multiple chapters mention the same fact, consolidate it into a single clear statement.\n");
        sb.append("2. **Conflict Resolution**: If fragments contradict each other, prioritize the information from the later fragment (as it represents a later state in the narrative), but note the evolution of the logic if relevant.\n");
        sb.append("3. **Logical Structuring**: Organize the final answer by logical flow (e.g., Cause -> Effect or Setup -> Implementation) rather than chronological order of chapters.\n");
        sb.append("4. **Strict Fidelity & Evidence Mapping**: Every claim in your final response must be mapped to its source fragment using a bracketed index (e.g., [Fragment 2], [Fragment 5]). If you cannot map a statement directly to a provided fragment, it is a hallucination and must be removed.\n\n");

        sb.append("# OUTPUT REQUIREMENTS\n");
        sb.append("- Provide a professional, technical response.\n");
        sb.append("- Use headings, bullet points, or tables where appropriate to increase readability.\n");
        sb.append("- Ensure the final output is a standalone answer that requires no further context to understand.\n");
        sb.append("- Include a \"Missing Information\" section to explicitly list gaps that prevented a fully coherent narrative based on the provided fragments.\n\n");

        sb.append("# FINAL RESPONSE\n");
        sb.append("[Your synthesized answer here]");

        return generate(sb.toString());
    }
}
