package io.agenticsdlc.adapter.out.jira;

import tools.jackson.databind.JsonNode;

/**
 * Converts Atlassian Document Format (Jira Cloud REST v3 rich text) to plain text for the agents: paragraphs and
 * headings become lines, list items get bullets or numbers, code blocks are fenced, mentions and links keep their
 * visible text. Unknown nodes contribute their children's text, so new node types degrade gracefully.
 */
final class AdfText {

	private AdfText() {
	}

	static String toText(JsonNode adf) {
		if (adf == null || adf.isNull() || adf.isMissingNode()) {
			return "";
		}
		if (adf.isString()) {
			return adf.asString();
		}
		StringBuilder out = new StringBuilder();
		render(adf, out, "");
		return out.toString().replaceAll("\n{3,}", "\n\n").strip();
	}

	private static void render(JsonNode node, StringBuilder out, String indent) {
		String type = node.path("type").asString("");
		switch (type) {
			case "text" -> out.append(node.path("text").asString(""));
			case "hardBreak" -> out.append('\n').append(indent);
			case "mention", "emoji", "date", "status" -> out.append(node.path("attrs").path("text").asString(""));
			case "inlineCard", "blockCard" -> out.append(node.path("attrs").path("url").asString(""));
			case "codeBlock" -> {
				out.append("```").append(node.path("attrs").path("language").asString("")).append('\n');
				children(node, out, indent);
				out.append("\n```\n\n");
			}
			case "bulletList", "orderedList" -> {
				int number = 1;
				for (JsonNode item : node.path("content")) {
					out.append(indent).append(type.equals("bulletList") ? "- " : (number++) + ". ");
					StringBuilder itemText = new StringBuilder();
					children(item, itemText, indent + "  ");
					out.append(itemText.toString().strip()).append('\n');
				}
				out.append('\n');
			}
			case "heading" -> {
				out.append("#".repeat(Math.max(1, node.path("attrs").path("level").asInt(1)))).append(' ');
				children(node, out, indent);
				out.append("\n\n");
			}
			case "paragraph", "blockquote", "panel" -> {
				children(node, out, indent);
				out.append("\n\n");
			}
			default -> children(node, out, indent);
		}
	}

	private static void children(JsonNode node, StringBuilder out, String indent) {
		for (JsonNode child : node.path("content")) {
			render(child, out, indent);
		}
	}
}
