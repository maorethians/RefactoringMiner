package narrator.langchain;

import narrator.graph.cluster.traverse.GrainLevel;
import narrator.service.NarrativeService;

public class NarrativeRunner {
    public static void main(String[] args) {
        // =========================================================================
        // CONFIGURATION: Change these values before running
        // =========================================================================
        String url = "https://github.com/TeamNewPipe/NewPipe/pull/10018";
        GrainLevel level = GrainLevel.FILE;
        String task = "You are PR-Reviewer, an experienced software engineer and language model designed to review a Git Pull Request (PR).\n" +
                "Your task is to provide a thorough, constructive, and concise review.\n" +
                "\n" +
                "**Review Focus:**\n" +
                "When analyzing the PR, you must independently investigate the following aspects:\n" +
                "1. **Logic & Correctness**: Logic errors, potential bugs, or unhandled edge cases.\n" +
                "2. **Maintainability**: Readability, clarity, and maintainability of the code.\n" +
                "3. **Best Practices**: Adherence to programming best practices, design patterns, and established coding conventions.\n" +
                "4. **Performance**: Performance implications or inefficiencies.\n" +
                "5. **Security**: Potential security vulnerabilities (e.g., exposure of sensitive information, SQL injection, XSS, CSRF).\n" +
                "6. **Documentation**: Adequacy and clarity of comments and documentation.\n" +
                "7. **Testing**: Test coverage and the quality/relevance of new or modified tests.\n" +
                "8. **Refactoring**: Opportunities for simplification, refactoring for better structure, or use of more idiomatic language constructs.\n" +
                "\n" +
                "The output must be a YAML object equivalent to the following Pydantic definitions:\n" +
                "=====\n" +
                "class KeyIssuesComponentLink(BaseModel):\n" +
                "    relevant_file: str = Field(description=\"The full file path of the relevant file\")\n" +
                "    issue_header: str = Field(description=\"One or two word title for the issue. For example: 'Possible Bug', etc.\")\n" +
                "    issue_content: str = Field(description=\"A short and concise summary of what should be further inspected and validated during the PR review process for this issue.\")\n" +
                "\n" +
                "class Review(BaseModel):\n" +
                "    key_issues_to_review: List[KeyIssuesComponentLink] = Field(\"A diverse list of all high-priority bugs, problems or performance concerns identified in the PR code, which the PR reviewer should further focus on and validate during the review process.\")\n" +
                "    security_concerns: str = Field(description=\"Does this PR code introduce possible vulnerabilities such as exposure of sensitive information (e.g., API keys, secrets, passwords), or security concerns like SQL injection, XSS, CSRF, and others ? Answer 'No' (without explaining why) if there are no possible issues. If there are security concerns or issues, start your answer with a short header, such as: 'Sensitive information exposure: ...', 'SQL injection: ...' etc. Explain your answer. Be specific and give examples if possible\")\n" +
                "\n" +
                "class PRReview(BaseModel):\n" +
                "    review: Review\n" +
                "=====\n" +
                "\n" +
                "Answer should be a valid YAML, and nothing else. Each YAML output MUST be after a newline, with proper indent, and block scalar indicator ('|').\n" +
                "\n" +
                "Example output:\n" +
                "```yaml\n" +
                "review:\n" +
                "  key_issues_to_review:\n" +
                "    - relevant_file: |\n" +
                "        directory/xxx.py\n" +
                "      issue_header: |\n" +
                "        Possible Bug\n" +
                "      issue_content: |\n" +
                "        ...\n" +
                "    - ...\n" +
                "  security_concerns: |\n" +
                "    No\n" +
                "```";
        // =========================================================================

        try {
            System.out.println("Initializing Narrative Processing...");
            System.out.println("URL: " + url);
            System.out.println("Level: " + level);
            System.out.println("Task: " + task);
            System.out.println("--------------------------------------------------");

            NarrativeService narrativeService = new NarrativeService();
            NarrativeProcessor processor = new NarrativeProcessor(narrativeService);

            NarrativeRequest request = new NarrativeRequest(url, level, task);

            NarrativeResponse response = processor.process(request);

            System.out.println("\n--------------------------------------------------");
            System.out.println("FINAL RESULT:");
            System.out.println("--------------------------------------------------");
            System.out.println(response.getFinalResult());

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
