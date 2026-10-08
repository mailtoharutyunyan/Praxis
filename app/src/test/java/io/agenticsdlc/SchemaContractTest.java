package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * The schema repeats enum names in check constraints and in the claimable-runs partial index.
 * These tests fail the build when the Java enums and the migrations drift apart, and prove the
 * database itself rejects states the domain forbids.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaContractTest {

	private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

	@Autowired
	DatabaseClient db;

	@Test
	void checkConstraintsListEveryEnumConstant() {
		assertThat(quotedNames(constraint("runs_state_ck"))).isEqualTo(names(RunState.values()));
		assertThat(quotedNames(constraint("runs_risk_ck"))).isEqualTo(names(RiskLevel.values()));
		assertThat(quotedNames(constraint("runs_gates_ck"))).isEqualTo(names(Gate.values()));
		assertThat(quotedNames(constraint("tasks_origin_ck"))).isEqualTo(names(TaskOrigin.values()));
		assertThat(quotedNames(constraint("tasks_scm_kind_ck"))).isEqualTo(names(ScmKind.values()));
		assertThat(quotedNames(constraint("tasks_trust_ck"))).isEqualTo(names(Trust.values()));
	}

	@Test
	void claimableIndexCoversExactlyTheWorkingStates() {
		String indexDef = db.sql("select pg_get_indexdef('runs_claimable_idx'::regclass) as def")
				.map(row -> row.get("def", String.class)).one().block();
		assertThat(quotedNames(indexDef)).isEqualTo(names(RunState.working().toArray(RunState[]::new)));
	}

	@Test
	void databaseRejectsGatePolicyWithoutPublish() {
		UUID taskId = insertTask();
		assertThatThrownBy(() -> insertRun(taskId, "SPECIFYING", "'MEDIUM'", "array['SPEC']", "null", "null"))
				.hasMessageContaining("runs_gates_ck");
	}

	@Test
	void databaseRejectsPendingGateOutsideApprovalOrPolicy() {
		UUID taskId = insertTask();
		assertThatThrownBy(() -> insertRun(taskId, "SPECIFYING", "'LOW'", "array['PUBLISH']", "'PUBLISH'", "null"))
				.hasMessageContaining("runs_pending_gate_ck");
		assertThatThrownBy(() -> insertRun(taskId, "AWAITING_APPROVAL", "'LOW'", "array['PUBLISH']", "'SPEC'", "null"))
				.hasMessageContaining("runs_pending_gate_ck");
	}

	@Test
	void databaseRejectsNeedsHumanWithoutResumeState() {
		UUID taskId = insertTask();
		assertThatThrownBy(() -> insertRun(taskId, "NEEDS_HUMAN", "null", "null", "null", "null"))
				.hasMessageContaining("runs_resume_state_ck");
	}

	@Test
	void databaseAcceptsAValidAwaitingRun() {
		UUID taskId = insertTask();
		insertRun(taskId, "AWAITING_APPROVAL", "'HIGH'", "array['SPEC','IMPLEMENTATION','PUBLISH']", "'SPEC'", "null");
	}

	private String constraint(String name) {
		return db.sql("select pg_get_constraintdef(oid) as def from pg_constraint where conname = :name")
				.bind("name", name)
				.map(row -> row.get("def", String.class)).one().block();
	}

	private UUID insertTask() {
		UUID id = UUID.randomUUID();
		db.sql("""
				insert into tasks (id, origin, title, description, scm_kind, clone_url, trust, requested_by, created_at)
				values (:id, 'PROMPT', 't', 'd', 'GITHUB', 'https://github.com/acme/shop.git', 'TRUSTED', 'tester', now())
				""").bind("id", id).then().block();
		return id;
	}

	/** Literal SQL fragments, test-only: lets each case express exactly the row shape under test. */
	private void insertRun(UUID taskId, String state, String risk, String gates, String pendingGate, String resumeState) {
		db.sql("insert into runs (id, task_id, state, risk, gates, pending_gate, resume_state, created_at, updated_at) "
				+ "values (:id, :taskId, '" + state + "', " + risk + ", " + gates + ", " + pendingGate + ", "
				+ resumeState + ", now(), now())")
				.bind("id", UUID.randomUUID()).bind("taskId", taskId).then().block();
	}

	private static Set<String> quotedNames(String sql) {
		assertThat(sql).isNotNull();
		Matcher m = QUOTED.matcher(sql);
		Set<String> found = new java.util.TreeSet<>();
		while (m.find()) {
			found.add(m.group(1));
		}
		return found;
	}

	private static Set<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(java.util.TreeSet::new));
	}
}
