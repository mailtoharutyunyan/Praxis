package io.agenticsdlc.adapter.out.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.ScmKind;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RepoCoordinatesTest {

	@ParameterizedTest
	@CsvSource({
			"GITHUB, https://github.com/acme/shop.git, github.com, acme, , shop",
			"GITHUB, https://ghe.corp.example/acme/shop, ghe.corp.example, acme, , shop",
			"GITLAB, https://gitlab.com/group/sub/shop.git, gitlab.com, group/sub, , shop",
			"BITBUCKET, https://bitbucket.org/ws/shop.git, bitbucket.org, ws, , shop",
			"AZURE_DEVOPS, https://dev.azure.com/org/Proj/_git/repo, dev.azure.com, org, Proj, repo",
			"AZURE_DEVOPS, https://org.visualstudio.com/Proj/_git/repo, org.visualstudio.com, org, Proj, repo" })
	void parsesCloneUrls(ScmKind kind, String url, String host, String owner, String project, String name) {
		RepoCoordinates c = RepoCoordinates.of(new RepositoryRef(kind, URI.create(url)));
		assertThat(c.host()).isEqualTo(host);
		assertThat(c.owner()).isEqualTo(owner);
		assertThat(c.project()).isEqualTo(project);
		assertThat(c.name()).isEqualTo(name);
	}

	@ParameterizedTest
	@CsvSource({ "GITHUB, https://github.com/acme", "GITLAB, https://gitlab.com/shop.git",
			"AZURE_DEVOPS, https://dev.azure.com/org/repo", "AZURE_DEVOPS, https://example.com/a/_git/b" })
	void rejectsUnexpectedShapes(ScmKind kind, String url) {
		assertThatThrownBy(() -> RepoCoordinates.of(new RepositoryRef(kind, URI.create(url))))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
