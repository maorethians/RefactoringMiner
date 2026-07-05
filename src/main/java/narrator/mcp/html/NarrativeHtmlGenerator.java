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
        // 1. Generate Grain Level pages
        for (GrainLevel level : GrainLevel.values()) {
            generateGrainLevelPage(level, clusters, -1);
        }

        // 2. Generate Overview page
        generateOverviewPage(clusters);
        return getOverviewPath();
    }

    private void generateOverviewPage(List<Cluster> clusters) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Narrative Overview"));
        html.append("<div class='max-w-5xl mx-auto px-4 py-12'>");
        html.append("<header class='text-center mb-12'>");
        html.append("<h1 class='text-4xl font-extrabold text-slate-900 mb-4'>Narrative Overview</h1>");
        html.append("<p class='text-lg text-slate-600'>Select a grain level to explore the architectural changes in detail.</p>");
        html.append("</header>");

        html.append("<div class='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6'>");
        for (GrainLevel level : GrainLevel.values()) {
            int count = narrator.getFlatChapters(level, clusters).size();
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

    public void generateGrainLevelPage(GrainLevel level, List<Cluster> clusters, int expandedChapterIndex) throws IOException {
        List<String> chapters = narrator.getFlatChapters(level, clusters);
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader(level + " Overview"));
        html.append("<div class='max-w-4xl mx-auto px-4 py-12'>");
        html.append(getBreadcrumbs(level));
        html.append("<header class='mb-8'>");
        html.append("<h1 class='text-3xl font-bold text-slate-900 mb-2'>Grain Level: ").append(level).append("</h1>");
        html.append("<p class='text-slate-600'>Explore the detailed chapters for this level of granularity.</p>");
        html.append("</header>");

        html.append("<div class='space-y-6'>");
        for (int i = 0; i < chapters.size(); i++) {
            String content = chapters.get(i);

            String openAttr = (i == expandedChapterIndex) ? " open" : "";
            html.append("<details").append(openAttr).append(" class='group bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden transition-all duration-200'>");
            html.append("<summary class='flex items-center justify-between p-4 cursor-pointer hover:bg-slate-50 transition-colors list-none'>");
            html.append("<span class='font-medium text-slate-700 group-open:text-indigo-600'>Chapter ").append(i + 1).append("</span>");
            html.append("<span class='text-slate-400 group-open:rotate-180 transition-transform duration-200'>&darr;</span>");
            html.append("</summary>");

            html.append("<div class='p-4 pt-0 border-t border-slate-100'>");
            html.append("<div class='bg-slate-900 rounded-lg shadow-inner overflow-hidden border border-slate-800'>");
            html.append("<div class='bg-slate-800 px-4 py-2 border-b border-slate-700 flex items-center justify-between'>");
            html.append("<span class='text-xs font-mono text-slate-400'>Diff Content</span>");
            html.append("<div class='flex space-x-1.5'>");
            html.append("<div class='w-2 h-2 rounded-full bg-red-500/50'></div>");
            html.append("<div class='w-2 h-2 rounded-full bg-yellow-500/50'></div>");
            html.append("<div class='w-2 h-2 rounded-full bg-green-500/50'></div>");
            html.append("</div>");
            html.append("</div>");
            html.append("<div class='p-4 font-mono text-sm text-slate-300 leading-relaxed overflow-x-auto whitespace-pre-wrap'>");
            html.append(content);
            html.append("</div>");
            html.append("</div>");
            html.append("</div>");
            html.append("</details>");
        }
        html.append("</div>");
        html.append("</div>");
        html.append(getHtmlFooter());
        writeFile("grain_" + level.name().toLowerCase() + ".html", html.toString());
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

    private String getBreadcrumbs(GrainLevel level) {
        StringBuilder html = new StringBuilder();
        html.append("<nav class='flex text-sm text-slate-500 mb-6' aria-label='Breadcrumb'>");
        html.append("<ol class='flex items-center space-x-2'>");
        html.append("<li><a href='index.html' class='text-indigo-600 hover:text-indigo-800 font-medium'>Overview</a></li>");
        html.append("<li class='flex items-center space-x-2'>");
        html.append("<span class='text-slate-400'>&rsaquo;</span>");
        html.append("<a href='grain_" + level.name().toLowerCase() + ".html' class='text-indigo-600 hover:text-indigo-800 font-medium'>" + level + "</a></li>");
        html.append("</ol></nav>");
        return html.toString();
    }

    public String getOverviewPath() {
        return baseDir.resolve("index.html").toAbsolutePath().toString();
    }

}
