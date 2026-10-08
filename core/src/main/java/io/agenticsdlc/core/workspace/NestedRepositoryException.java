package io.agenticsdlc.core.workspace;

/**
 * The working copy contains a git repository the base commit did not have. Git would record it as a gitlink
 * (submodule pointer), so its files would silently be missing from the diff and the pushed branch.
 */
public final class NestedRepositoryException extends IllegalStateException {

	public NestedRepositoryException(String path) {
		super(path + " is a nested git repository; delete " + path + "/.git so its files are tracked as normal files");
	}
}
