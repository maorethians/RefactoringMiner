package narrator;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.kohsuke.github.GHRepository;
import org.refactoringminer.astDiff.graph.Edge;
import org.refactoringminer.astDiff.graph.HunkNetwork;
import org.refactoringminer.astDiff.graph.Node;
import gr.uom.java.xmi.RepositoryStateFiles;
import org.refactoringminer.astDiff.models.ASTDiff;
import org.refactoringminer.astDiff.models.ProjectASTDiff;
import org.refactoringminer.astDiff.utils.URLHelper;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

public class Driver {

    public static Graph<Node, Edge> getPullRequestGraph(String url) throws Exception {
        GitHistoryRefactoringMinerImpl miner = new GitHistoryRefactoringMinerImpl();

        String repo = URLHelper.getRepo(url);
        int PR = Integer.parseInt(URLHelper.getPRID(url));

        GHRepository repository = miner.getGitHubRepository(repo);
        RepositoryStateFiles PRFiles = new RepositoryStateFiles(repository, repository.getPullRequest(PR).getHead().getSha());

        ProjectASTDiff projectASTDiff = miner.diffAtPullRequest(repo, PR, 1000);
        return getGraph(projectASTDiff, PRFiles);
    }

    public static Graph<Node, Edge> getCommitGraph(String url) throws Exception {
        GitHistoryRefactoringMinerImpl miner = new GitHistoryRefactoringMinerImpl();

        String repo = URLHelper.getRepo(url);
        String commit = URLHelper.getCommit(url);

        GHRepository repository = miner.getGitHubRepository(repo);
        RepositoryStateFiles commitFiles = new RepositoryStateFiles(repository, commit);

        ProjectASTDiff projectASTDiff = miner.diffAtCommit(repo, commit, 1000);
        return getGraph(projectASTDiff, commitFiles);
    }

    public static Graph<Node, Edge> getCompareGraph(String url) throws Exception {
        GitHistoryRefactoringMinerImpl miner = new GitHistoryRefactoringMinerImpl();

        String repo = URLHelper.getRepo(url);
        Pair<String, String> range = URLHelper.getCommitPairFromGitHubCompareURL(url);

        GHRepository repository = miner.getGitHubRepository(repo);
        RepositoryStateFiles rangeFiles = new RepositoryStateFiles(repository, range.getRight());

        ProjectASTDiff projectASTDiff = miner.diffAtGitHubCompare(repo, range.getLeft(), range.getRight());
        return getGraph(projectASTDiff, rangeFiles);
    }

    private static Graph<Node, Edge> getGraph(ProjectASTDiff projectASTDiff, RepositoryStateFiles repositoryFiles) {
        HunkNetwork network = new HunkNetwork(projectASTDiff.getModelDiff(), projectASTDiff.getFileContentsBefore(),
                projectASTDiff.getFileContentsAfter(), repositoryFiles);

        Set<ASTDiff> authoritativeDiffs = new LinkedHashSet<>(projectASTDiff.getDiffSet());
        Set<ASTDiff> diffSet = new LinkedHashSet<>(authoritativeDiffs);
        diffSet.addAll(projectASTDiff.getMoveDiffSet());
        for (ASTDiff diff : diffSet) {
            network.importDiff(diff, authoritativeDiffs.contains(diff));
        }

        network.importFiles();

        network.process();

        return network.getGraph();
    }
}
