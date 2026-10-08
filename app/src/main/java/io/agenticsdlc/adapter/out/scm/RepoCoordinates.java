package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.ScmKind;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Where a repository lives on its provider, parsed from the HTTPS clone URL.
 *
 * @param owner GitHub owner, GitLab namespace path (may contain '/'), Bitbucket workspace, Azure DevOps organization
 * @param project Azure DevOps project; null for other providers
 * @param name repository name without {@code .git}
 */
record RepoCoordinates(ScmKind kind, String host, String owner, String project, String name) {

	static RepoCoordinates of(RepositoryRef repository) {
		URI url = repository.cloneUrl();
		String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
		List<String> segments = Arrays.stream(url.getPath().split("/")).filter(s -> !s.isBlank()).toList();
		return switch (repository.kind()) {
			case GITHUB, BITBUCKET -> {
				require(segments.size() == 2, url, "owner/repository");
				yield new RepoCoordinates(repository.kind(), host, segments.get(0), null, stripGit(segments.get(1)));
			}
			case GITLAB -> {
				require(segments.size() >= 2, url, "namespace/project");
				yield new RepoCoordinates(repository.kind(), host,
						String.join("/", segments.subList(0, segments.size() - 1)), null, stripGit(segments.getLast()));
			}
			case AZURE_DEVOPS -> azure(url, host, segments);
		};
	}

	/** {@code dev.azure.com/{org}/{project}/_git/{repo}} or legacy {@code {org}.visualstudio.com/{project}/_git/{repo}}. */
	private static RepoCoordinates azure(URI url, String host, List<String> segments) {
		int git = segments.indexOf("_git");
		if (host.equals("dev.azure.com") || host.equals("ssh.dev.azure.com")) {
			require(git == 2 && segments.size() == 4, url, "{organization}/{project}/_git/{repository}");
			return new RepoCoordinates(ScmKind.AZURE_DEVOPS, host, segments.get(0), segments.get(1),
					stripGit(segments.get(3)));
		}
		if (host.endsWith(".visualstudio.com")) {
			require(git == 1 && segments.size() == 3, url, "{project}/_git/{repository}");
			return new RepoCoordinates(ScmKind.AZURE_DEVOPS, host, host.substring(0, host.indexOf('.')), segments.get(0),
					stripGit(segments.get(2)));
		}
		throw new IllegalArgumentException("not an Azure DevOps URL: " + url);
	}

	private static String stripGit(String name) {
		return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
	}

	private static void require(boolean ok, URI url, String expected) {
		if (!ok) {
			throw new IllegalArgumentException("cannot read " + expected + " from clone URL " + url);
		}
	}
}
