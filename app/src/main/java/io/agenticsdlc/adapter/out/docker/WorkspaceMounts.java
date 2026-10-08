package io.agenticsdlc.adapter.out.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.VolumeOptions;
import java.nio.file.Path;
import java.util.List;

/**
 * Mounts a directory of the workspace into a container. On a host install that is a bind mount of the path. When this
 * app itself runs in a container, the path exists only inside it, so the directory is mounted from the workspace
 * volume instead, by its path relative to the workspace root (a volume subpath, Docker Engine API 1.45+).
 */
final class WorkspaceMounts {

	private final Path root;
	private final String volume;

	WorkspaceMounts(Path root, String volume) {
		this.root = root.toAbsolutePath().normalize();
		this.volume = volume == null ? "" : volume.strip();
	}

	/** Identifies this installation's workspace: the volume name, else the root path. */
	String identity() {
		return usesVolume() ? "volume:" + volume : root.toString();
	}

	boolean usesVolume() {
		return !volume.isEmpty();
	}

	HostConfig mount(HostConfig host, Path directory, String target, boolean readOnly) {
		if (!usesVolume()) {
			return host.withBinds(readOnly ? new Bind(directory.toString(), new Volume(target), AccessMode.ro)
					: new Bind(directory.toString(), new Volume(target)));
		}
		Path relative = root.relativize(directory.toAbsolutePath().normalize());
		if (relative.toString().isEmpty() || relative.startsWith("..")) {
			throw new IllegalArgumentException(directory + " is not inside the workspace root " + root);
		}
		return host.withMounts(List.of(new Mount().withType(MountType.VOLUME).withSource(volume).withTarget(target)
				.withReadOnly(readOnly).withVolumeOptions(new SubpathOptions(relative.toString().replace('\\', '/')))));
	}

	/** docker-java has no field for the volume subpath yet; it is serialised like the others. */
	static final class SubpathOptions extends VolumeOptions {

		private static final long serialVersionUID = 1L;

		@JsonProperty("Subpath")
		private final String subpath;

		SubpathOptions(String subpath) {
			this.subpath = subpath;
		}

		public String getSubpath() {
			return subpath;
		}
	}
}
