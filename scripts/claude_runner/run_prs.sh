#!/bin/bash

# Default values
DEFAULT_MODEL="gemma4:31b"
DATASET_DIR="dataset/ContextCRBench/java"
RESULTS_DIR="scripts/claude_runner/results"

# The SWRench review prompt merging the analytical depth of hybrid_review and the strict YAML output of npr_review
SWR_PROMPT="You are PR-Reviewer, an experienced software engineer and language model designed to review a Git Pull Request (PR).
Your task is to provide a thorough, constructive, and concise review.

**Review Focus:**
When analyzing the PR, you must independently investigate the following aspects:
1. **Logic & Correctness**: Logic errors, potential bugs, or unhandled edge cases.
2. **Maintainability**: Readability, clarity, and maintainability of the code.
3. **Best Practices**: Adherence to programming best practices, design patterns, and established coding conventions.
4. **Performance**: Performance implications or inefficiencies.
5. **Security**: Potential security vulnerabilities (e.g., exposure of sensitive information, SQL injection, XSS, CSRF).
6. **Documentation**: Adequacy and clarity of comments and documentation.
7. **Testing**: Test coverage and the quality/relevance of new or modified tests.
8. **Refactoring**: Opportunities for simplification, refactoring for better structure, or use of more idiomatic language constructs.

The output must be a YAML object equivalent to the following Pydantic definitions:
=====
class KeyIssuesComponentLink(BaseModel):
    relevant_file: str = Field(description=\"The full file path of the relevant file\")
    issue_header: str = Field(description=\"One or two word title for the issue. For example: 'Possible Bug', etc.\")
    issue_content: str = Field(description=\"A short and concise summary of what should be further inspected and validated during the PR review process for this issue.\")

class Review(BaseModel):
    key_issues_to_review: List[KeyIssuesComponentLink] = Field(\"A diverse list of all high-priority bugs, problems or performance concerns identified in the PR code, which the PR reviewer should further focus on and validate during the review process.\")
    security_concerns: str = Field(description=\"Does this PR code introduce possible vulnerabilities such as exposure of sensitive information (e.g., API keys, secrets, passwords), or security concerns like SQL injection, XSS, CSRF, and others ? Answer 'No' (without explaining why) if there are no possible issues. If there are security concerns or issues, start your answer with a short header, such as: 'Sensitive information exposure: ...', 'SQL injection: ...' etc. Explain your answer. Be specific and give examples if possible\")

class PRReview(BaseModel):
    review: Review
=====

Answer should be a valid YAML, and nothing else. Each YAML output MUST be after a newline, with proper indent, and block scalar indicator ('|').

Example output:
\`\`\`yaml
review:
  key_issues_to_review:
    - relevant_file: |
        directory/xxx.py
      issue_header: |
        Possible Bug
      issue_content: |
        ...
    - ...
  security_concerns: |
    No
\`\`\`"

# Arguments
CUTOFF=$1
MODEL=${2:-$DEFAULT_MODEL}

if [ -z "$CUTOFF" ]; then
    echo "Usage: $0 <cutoff_date (YYYY-MM-DD)> [model]"
    echo "Example: $0 2023-01-01 gemma4:31b"
    exit 1
fi

mkdir -p "$RESULTS_DIR"

echo "Using Model: $MODEL"
echo "Cutoff Date: $CUTOFF"

# Iterate over PR files
for pr_file in "$DATASET_DIR"/*.json; do
    [ -e "$pr_file" ] || continue

    # Extract created_at date
    created_at=$(jq -r '.created_at' "$pr_file")
    pr_date=$(echo "$created_at" | cut -d'T' -f1)

    if [[ "$pr_date" > "$CUTOFF" ]]; then
        pr_id=$(basename "$pr_file" .json)
        echo "Processing $pr_id (Date: $pr_date)..."

        # Extract PR details for URL construction
        repo=$(jq -r '.repo' "$pr_file")
        pr_num=$(jq -r '.pr_number' "$pr_file")
        pr_url="https://github.com/$repo/pull/$pr_num"

        # Construct prompt
        prompt="$SWR_PROMPT\n\nNow, please review the PR at: $pr_url"

        # Run claude in a separate session
        # We use --model $MODEL and pass the prompt
        # We redirect stdout and stderr to the result file.
        claude --model "$MODEL" "$prompt" > "$RESULTS_DIR/$pr_id.txt" 2>&1

        echo "Result saved to $RESULTS_DIR/$pr_id.txt"
    else
        echo "Skipping $pr_file (Date: $pr_date <= $CUTOFF)"
    fi
done

echo "Done."
