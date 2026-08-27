package narrator.langchain;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import narrator.langchain.prompt.LangChainPrompt;
import narrator.langchain.prompt.ReviewPrompt;
import org.refactoringminer.astDiff.graph.cluster.traverse.Splitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LangChainClient {
    private final ChatLanguageModel model;
    private final LangChainPrompt prompt;
    private static final double temperature = 0.2;

    public LangChainClient(ChatLanguageModel model) {
        this.model = model;
        this.prompt = new ReviewPrompt();
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
                    .temperature(temperature)
                    .build();
        } else if ("openai".equalsIgnoreCase(provider)) {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(temperature)
                    .build();
        } else if ("ollama".equalsIgnoreCase(provider)) {
            model = OllamaChatModel.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(999)) // Increase timeout for local large models
                    .temperature(temperature)
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return model.generate(prompt);
    }

    public String processChapter(String content, List<String> understandings) {
        return generate(this.prompt.chapter(content, understandings));
    }

    public String compileResults(List<String> results, List<String> understandings) {
        String aggregatedUnderstanding = null;
        String aggregatedResult = null;
        List<List<Integer>> splits = Splitter.createBalancedSplits(understandings);
        for (int i = 0; i < splits.size(); i++) {
            List<Integer> split = splits.get(i);

            List<LangChainPrompt.StringIndex> splitUnderstandings = new ArrayList<>();
            if (aggregatedUnderstanding != null) {
                splitUnderstandings.add(new LangChainPrompt.StringIndex(aggregatedUnderstanding, split.get(0) - 1));
            }
            splitUnderstandings.addAll(split.stream()
                    .map(index -> new LangChainPrompt.StringIndex(understandings.get(index), index)).toList());
            aggregatedUnderstanding = model.generate(this.prompt.understanding(splitUnderstandings));

            List<LangChainPrompt.StringIndex> splitResults = new ArrayList<>();
            if (aggregatedResult != null) {
                splitResults.add(new LangChainPrompt.StringIndex(aggregatedResult, split.get(0) - 1));
            }
            splitResults.addAll(split.stream()
                    .map(index -> new LangChainPrompt.StringIndex(results.get(index), index)).toList());
            aggregatedResult = model.generate(this.prompt.result(splitResults, aggregatedUnderstanding, i == splits.size() - 1));
        }

        return aggregatedResult;
    }
}
