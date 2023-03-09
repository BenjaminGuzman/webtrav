package dev.benjaminguzman.webtraverser;

import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

@CommandLine.Command(
	name = "webtraverser",
	mixinStandardHelpOptions = true,
	description = "Generate graph of webpage links"
)
public class Main implements Runnable {
	@CommandLine.Option(names = {"--url"}, description = "Webpage URL", required = true)
	private String url;

	@CommandLine.Option(names = {"--ua", "--user-agent"}, description = "User-Agent header", defaultValue = "webtraverser")
	private String userAgent;

	@CommandLine.Option(names = {"-d", "--max-depth"}, description = "Maximum graph depth allowed", defaultValue = "10")
	private int maxDepth;

	@CommandLine.Option(names = {"-o", "--output"}, description = "Output file. Valid extensions: svg, dot. Use special value '-' to print to stdout", defaultValue = "out.svg")
	private String output;

	@NotNull
	private final Set<String> visitedUrls = new HashSet<>();

	@NotNull
	private final StringBuilder nodesBuilder = new StringBuilder();

	@NotNull
	private final StringBuilder edgesBuilder = new StringBuilder();

	@NotNull
	private static final String DOT_TAB = "\t";

	@NotNull
	private static final String lineSep = System.lineSeparator();

	public static void main(String... args) {
		new CommandLine(new Main()).execute(args);
	}

	@Override
	public void run() {
		StringBuilder graphBuilder = new StringBuilder();
		graphBuilder.append("digraph web {").append(lineSep)
			.append(DOT_TAB + "layout=twopi").append(lineSep)
			.append(DOT_TAB + "ranksep=5").append(lineSep);
		visitUrl(url, 0);
		graphBuilder.append(nodesBuilder)
			.append(edgesBuilder)
			.append("}")
			.append(lineSep);

		output = output.strip();
		if (output.toLowerCase().endsWith(".svg")) {
			try {
				Process proc = Runtime.getRuntime().exec(new String[]{"dot", "-Tsvg"});
				OutputStream procStdin = proc.getOutputStream();
				InputStream procStderr = proc.getErrorStream();
				InputStream procStdout = proc.getInputStream();
				try (procStdin) {
					procStdin.write(graphBuilder.toString().getBytes(StandardCharsets.UTF_8));
				}

				try (procStdout) {
					Files.copy(procStdout, Path.of(output), StandardCopyOption.REPLACE_EXISTING);
				}

				try (procStderr) {
					byte[] errMsg = procStderr.readAllBytes();
					if (errMsg.length > 0) {
						System.err.println("Failed to create output");
						System.err.writeBytes(procStderr.readAllBytes());
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else if (output.toLowerCase().endsWith(".dot")) {
			InputStream is = new ByteArrayInputStream(graphBuilder.toString().getBytes(StandardCharsets.UTF_8));
			try {
				Files.copy(is, Path.of(output));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			if (!"-".equals(output))
				System.out.println("Don't know how to handle " + output + ". Writing to stdout");
			System.out.println(graphBuilder);
		}
	}

	private String visitUrl(@NotNull String url, int depth) {
		if (depth >= maxDepth)
			return "";
		if (visitedUrls.contains(url))
			return "";

		System.out.println(DOT_TAB.repeat(depth) + url);

		Document doc;
		try {
			doc = Jsoup.connect(url).get();
			visitedUrls.add(url);
		} catch (IOException e) {
			// TODO print error
			e.printStackTrace();
			return "";
		}

		String title = doc.title().replace("&", "and"); // TODO escape illegal characters the right way
		title = title.equals("") ? "NO TITLE" : title;

		// add the node
		String nodeName = "\"" + title + doc.hashCode() + "\"";
		nodesBuilder.append(DOT_TAB)
			.append(nodeName)
			.append("[label=<")
			.append(title)
			.append("<br/>")
			// TODO include screenshot in graph
			// TODO change color depending on depth
			.append("<font point-size=\"10\">")
			.append(url)
			.append("</font>")
			.append(">]")
			.append(lineSep);

		// continue traversing the graph
		List<String> links = getAvailableLinks(doc);
		links.stream()
			.filter(link -> !visitedUrls.contains(link))
			.map(link -> visitUrl(link, depth + 1))
			.filter(childNodeName -> !childNodeName.isBlank())
			.forEach(childNodeName -> edgesBuilder
				.append(DOT_TAB)
				.append(nodeName)
				.append(" -> ")
				.append(childNodeName)
				.append(" [ label=\"a#unique-selector\" ]")
				.append(lineSep)
			);

		return nodeName;
	}

	@NotNull
	private List<String> getAvailableLinks(@NotNull Document doc) {
		List<String> availableLinks = new ArrayList<>();
		Elements links = doc.select("a");
		for (Element link : links) {
			String linkUrl = link.attr("href").strip().toLowerCase();
			if (linkUrl.equals(""))
				continue;
			if (linkUrl.startsWith("http")) // absolute link
				availableLinks.add(linkUrl);
			else { // relative link
				// TODO handle relative links the right way. Probably using URL?
				if (linkUrl.startsWith("./")) {
					if (url.endsWith("/"))
						linkUrl = url + linkUrl.substring(2);
					else
						linkUrl = url + "/" + linkUrl.substring(2);
				} else if (linkUrl.startsWith("/")) {
					if (url.endsWith("/"))
						linkUrl = url + linkUrl.substring(1);
					else
						linkUrl = url + linkUrl;
				} else {
					linkUrl = url + linkUrl;
				}
				availableLinks.add(linkUrl);
			}

		}

		return availableLinks;
	}
}