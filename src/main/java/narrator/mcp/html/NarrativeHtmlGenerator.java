package narrator.mcp.html;

import narrator.graph.Node;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.traverse.GrainLevel;
import narrator.graph.cluster.traverse.Narrator;
import narrator.graph.cluster.traverse.TraversalPattern;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class NarrativeHtmlGenerator {
    private final String url;
    private final Narrator narrator;
    private final Path baseDir;

    public NarrativeHtmlGenerator(String url, Narrator narrator) throws IOException {
        this.url = url;
        this.narrator = narrator;
        // Create a unique directory in /tmp based on the URL hash
        String urlHash = Integer.toHexString(url.hashCode());
        this.baseDir = Paths.get("/tmp", "narratives", urlHash);
        Files.createDirectories(baseDir);
    }

    public String generateAll(List<Cluster> clusters) throws IOException {
        // 1. Generate Chapter pages
        for (GrainLevel level : GrainLevel.values()) {
            List<TraversalPattern> chapters = narrator.getNarrative(level);
            if (chapters == null) continue;

            for (int i = 0; i < chapters.size(); i++) {
                TraversalPattern pattern = chapters.get(i);
                Cluster cluster = findClusterForNode(pattern.getLead(), clusters);
                String content = (cluster != null) ? pattern.extended(cluster, level) : "[Content unavailable]";
                generateChapterPage(level, i, chapters.size(), pattern, content);
            }
        }

        // 2. Generate Grain Level pages
        for (GrainLevel level : GrainLevel.values()) {
            generateGrainLevelPage(level);
        }

        // 3. Generate Overview page
        generateOverviewPage();
        return getOverviewPath();
    }

    private void generateOverviewPage() throws IOException {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Narrative Overview"));
        html.append("<div class='max-w-5xl mx-auto px-4 py-12'>");
        html.append("<header class='text-center mb-12'>");
        html.append("<h1 class='text-4xl font-extrabold text-slate-900 mb-4'>Narrative Overview</h1>");
        html.append("<p class='text-lg text-slate-600'>Select a grain level to explore the architectural changes in detail.</p>");
        html.append("</header>");

        html.append("<div class='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6'>");
        for (GrainLevel level : GrainLevel.values()) {
            int count = narrator.getNarrative(level).size();
            String filename = "grain_" + level.name().toLowerCase() + ".html";
            html.append("<a href='").append(filename).append("' class='group p-6 bg-white rounded-xl shadow-sm border border-slate-200 hover:border-indigo-500 hover:shadow-md transition-all duration-200'>");
            html.append("<div class='flex items-center justify-between mb-4'>");
            html.append("<h3 class='text-xl font-bold text-indigo-600 group-hover:text-indigo-700'>" + level + "</h3>");
            html.append("<span class='px-2.5 py-0.5 rounded-full text-xs font-medium bg-indigo-100 text-indigo-800'>" + count + " chapters</span>");
            html.append("</div>");
            html.append("<p class='text-slate-600 text-sm leading-relaxed'>" + level.getDescription() + "</p>");
            html.append("</a>");
        }
        html.append("</div>");
        html.append("</div>");
        html.append(getHtmlFooter());
        writeFile("index.html", html.toString());
    }

    private void generateGrainLevelPage(GrainLevel level) throws IOException {
        List<TraversalPattern> chapters = narrator.getNarrative(level);
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader(level + " Overview"));
        html.append("<div class='max-w-4xl mx-auto px-4 py-12'>");
        html.append(getBreadcrumbs(level, null));
        html.append("<header class='mb-8'>");
        html.append("<h1 class='text-3xl font-bold text-slate-900 mb-2'>Grain Level: ").append(level).append("</h1>");
        html.append("<p class='text-slate-600'>Explore the detailed chapters for this level of granularity.</p>");
        html.append("</header>");

        html.append("<div class='bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden'>");
        html.append("<ul class='divide-y divide-slate-200'>");
        for (int i = 0; i < chapters.size(); i++) {
            String filename = "chapter_" + level.name().toLowerCase() + "_" + (i + 1) + ".html";
            html.append("<li class='hover:bg-slate-50 transition-colors'>");
            html.append("<a href='").append(filename).append("' class='flex items-center justify-between p-4 text-slate-700 hover:text-indigo-600'>");
            html.append("<span class='font-medium'>Chapter ").append(i + 1).append("</span>");
            html.append("<span class='text-slate-400'>&rarr;</span>");
            html.append("</a>");
            html.append("</li>");
        }
        html.append("</ul>");
        html.append("</div>");
        html.append("</div>");
        html.append(getHtmlFooter());
        writeFile("grain_" + level.name().toLowerCase() + ".html", html.toString());
    }

    private void generateChapterPage(GrainLevel level, int index, int totalChapters, TraversalPattern pattern, String content) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Chapter " + (index + 1)));
        html.append("<div class='max-w-5xl mx-auto px-4 py-12'>");
        html.append(getBreadcrumbs(level, index));

        html.append("<header class='flex items-center justify-between mb-8'>");
        html.append("<div>");
        html.append("<h1 class='text-3xl font-bold text-slate-900'>Chapter ").append(index + 1).append("</h1>");
        html.append("<p class='text-slate-600'>Level: ").append(level).append("</p>");
        html.append("</div>");

        html.append("<div class='flex items-center space-x-4'>");
        if (index > 0) {
            String prevFile = "chapter_" + level.name().toLowerCase() + "_" + index + ".html";
            html.append("<a href='").append(prevFile).append("' class='px-4 py-2 bg-white border border-slate-200 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors'>&larr; Previous</a>");
        } else {
            html.append("<span class='px-4 py-2 bg-slate-100 border border-slate-200 rounded-lg text-sm font-medium text-slate-400 cursor-not-allowed'>&larr; Previous</span>");
        }

        if (index < totalChapters - 1) {
            String nextFile = "chapter_" + level.name().toLowerCase() + "_" + (index + 2) + ".html";
            html.append("<a href='").append(nextFile).append("' class='px-4 py-2 bg-white border border-slate-200 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors'>Next &rarr;</a>");
        } else {
            html.append("<span class='px-4 py-2 bg-slate-100 border border-slate-200 rounded-lg text-sm font-medium text-slate-400 cursor-not-allowed'>Next &rarr;</span>");
        }
        html.append("</div>");
        html.append("</header>");

        html.append("<div class='bg-slate-900 rounded-xl shadow-2xl overflow-hidden border border-slate-800'>");
        html.append("<div class='bg-slate-800 px-4 py-2 border-b border-slate-700 flex items-center justify-between'>");
        html.append("<span class='text-xs font-mono text-slate-400'>Diff Content</span>");
        html.append("<div class='flex space-x-1.5'>");
        html.append("<div class='w-3 h-3 rounded-full bg-red-500/50'></div>");
        html.append("<div class='w-3 h-3 rounded-full bg-yellow-500/50'></div>");
        html.append("<div class='w-3 h-3 rounded-full bg-green-500/50'></div>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='p-6 font-mono text-sm text-slate-300 leading-relaxed overflow-x-auto whitespace-pre-wrap'>");
        html.append(content);
        html.append("</div>");
        html.append("</div>");

        html.append("</div>");
        html.append(getHtmlFooter());
        writeFile("chapter_" + level.name().toLowerCase() + "_" + (index + 1) + ".html", html.toString());
    }

    private void writeFile(String filename, String content) throws IOException {
        Files.write(baseDir.resolve(filename), content.getBytes(StandardCharsets.UTF_8));
    }

    private String getHtmlHeader(String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='en'><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>").append(title).append("</title>");
        html.append("<script src='https://cdn.tailwindcss.com'></script>");
        html.append("<style>body { font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; }</style>");
        html.append("</head><body class='bg-slate-50 text-slate-900'>");
        return html.toString();
    }

    private String getHtmlFooter() {
        return "</body></html>";
    }

    private String getBreadcrumbs(GrainLevel level, Integer index) {
        StringBuilder html = new StringBuilder();
        html.append("<nav class='flex text-sm text-slate-500 mb-6' aria-label='Breadcrumb'>");
        html.append("<ol class='flex items-center space-x-2'>");
        html.append("<li><a href='index.html' class='text-indigo-600 hover:text-indigo-800 font-medium'>Overview</a></li>");
        html.append("<li class='flex items-center space-x-2'>");
        html.append("<span class='text-slate-400'>&rsaquo;</span>");
        html.append("<a href='grain_" + level.name().toLowerCase() + ".html' class='text-indigo-600 hover:text-indigo-800 font-medium'>" + level + "</a></li>");
        if (index != null) {
            html.append("<li class='flex items-center space-x-2'>");
            html.append("<span class='text-slate-400'>&rsaquo;</span>");
            html.append("<span class='text-slate-700 font-semibold'>Chapter ").append(index + 1).append("</span></li>");
        }
        html.append("</ol></nav>");
        return html.toString();
    }

    public String getOverviewPath() {
        return baseDir.resolve("index.html").toAbsolutePath().toString();
    }

    public String getChapterPath(GrainLevel level, int index) {
        return baseDir.resolve("chapter_" + level.name().toLowerCase() + "_" + (index + 1) + ".html").toAbsolutePath().toString();
    }

    private Cluster findClusterForNode(Node node, List<Cluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }

        for (Cluster cluster : clusters) {
            if (cluster.getGraph().vertexSet().contains(node)) {
                return cluster;
            }
        }

        return null;
    }

    // We need a way to update the content of a chapter page when the actual diff is ready
    public void updateChapterContent(GrainLevel level, int index, String content) throws IOException {
        String filename = "chapter_" + level.name().toLowerCase() + "_" + (index + 1) + ".html";

        // We need the total number of chapters to correctly render the Next button
        int totalChapters = narrator.getNarrative(level).size();

        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Chapter " + (index + 1)));
        html.append("<div class='max-w-5xl mx-auto px-4 py-12'>");
        html.append(getBreadcrumbs(level, index));

        html.append("<header class='flex items-center justify-between mb-8'>");
        html.append("<div>");
        html.append("<h1 class='text-3xl font-bold text-slate-900'>Chapter ").append(index + 1).append("</h1>");
        html.append("<p class='text-slate-600'>Level: ").append(level).append("</p>");
        html.append("</div>");

        html.append("<div class='flex items-center space-x-4'>");
        if (index > 0) {
            String prevFile = "chapter_" + level.name().toLowerCase() + "_" + index + ".html";
            html.append("<a href='").append(prevFile).append("' class='px-4 py-2 bg-white border border-slate-200 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors'>&larr; Previous</a>");
        } else {
            html.append("<span class='px-4 py-2 bg-slate-100 border border-slate-200 rounded-lg text-sm font-medium text-slate-400 cursor-not-allowed'>&larr; Previous</span>");
        }

        if (index < totalChapters - 1) {
            String nextFile = "chapter_" + level.name().toLowerCase() + "_" + (index + 2) + ".html";
            html.append("<a href='").append(nextFile).append("' class='px-4 py-2 bg-white border border-slate-200 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors'>Next &rarr;</a>");
        } else {
            html.append("<span class='px-4 py-2 bg-slate-100 border border-slate-200 rounded-lg text-sm font-medium text-slate-400 cursor-not-allowed'>Next &rarr;</span>");
        }
        html.append("</div>");
        html.append("</header>");

        html.append("<div class='bg-slate-900 rounded-xl shadow-2xl overflow-hidden border border-slate-800'>");
        html.append("<div class='bg-slate-800 px-4 py-2 border-b border-slate-700 flex items-center justify-between'>");
        html.append("<span class='text-xs font-mono text-slate-400'>Diff Content</span>");
        html.append("<div class='flex space-x-1.5'>");
        html.append("<div class='w-3 h-3 rounded-full bg-red-500/50'></div>");
        html.append("<div class='w-3 h-3 rounded-full bg-yellow-500/50'></div>");
        html.append("<div class='w-3 h-3 rounded-full bg-green-500/50'></div>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='p-6 font-mono text-sm text-slate-300 leading-relaxed overflow-x-auto whitespace-pre-wrap'>");
        html.append(content);
        html.append("</div>");
        html.append("</div>");

        html.append("</div>");
        html.append(getHtmlFooter());

        writeFile(filename, html.toString());
    }
}
