package io.agenticsdlc.adapter.in.eval;

import io.agenticsdlc.core.eval.EvalHarness;
import io.agenticsdlc.core.eval.EvalReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an evaluation suite and writes {@code report.md} and {@code report.json}. Used by the {@code eval} profile from
 * the command line, and by CI to block prompt or model changes that lower the pass rate.
 */
public class EvalCommand {

	private static final Logger log = LoggerFactory.getLogger(EvalCommand.class);

	private final EvalHarness harness;
	private final JsonMapper json;

	public EvalCommand(EvalHarness harness, JsonMapper json) {
		this.harness = harness;
		this.json = json;
	}

	public EvalReport execute(Path suiteFile, Path outputDir) throws IOException {
		EvalSuite suite = EvalSuite.load(suiteFile);
		log.info("evaluating {}: {} case(s) x {} trial(s)", suite.name(), suite.cases().size(), suite.trials());
		EvalReport report = harness.run(suite.name(), suite.cases(), suite.trials(), suite.concurrency()).block();
		Files.createDirectories(outputDir);
		Files.writeString(outputDir.resolve("report.md"), report.toMarkdown());
		Files.writeString(outputDir.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
				"suite", report.suite(), "trialsPerCase", report.trialsPerCase(), "passAt1", report.passAt1(),
				"passHatK", report.passHatK(), "meanCostUsd", report.meanCostUsd(), "meanTokens", report.meanTokens(),
				"medianDurationSeconds", report.medianDuration().toSeconds(), "trials", report.trials())));
		log.info("pass@1 {} pass^{} {} → {}", report.passAt1(), report.trialsPerCase(), report.passHatK(), outputDir);
		return report;
	}
}
