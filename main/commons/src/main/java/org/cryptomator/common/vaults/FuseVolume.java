package org.cryptomator.common.vaults;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.common.mountpoint.InvalidMountPointException;
import org.cryptomator.common.mountpoint.MountPointChooser;
import org.cryptomator.cryptofs.CryptoFileSystem;
import org.cryptomator.frontend.fuse.mount.CommandFailedException;
import org.cryptomator.frontend.fuse.mount.EnvironmentVariables;
import org.cryptomator.frontend.fuse.mount.FuseMountFactory;
import org.cryptomator.frontend.fuse.mount.FuseNotSupportedException;
import org.cryptomator.frontend.fuse.mount.Mount;
import org.cryptomator.frontend.fuse.mount.Mounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class FuseVolume implements Volume {

	private static final Logger LOG = LoggerFactory.getLogger(FuseVolume.class);

	private final Set<MountPointChooser> choosers;

	private Mount fuseMnt;
	private Path mountPoint;

	//Cleanup
	private boolean cleanupRequired;
	private MountPointChooser usedChooser;

	@Inject
	public FuseVolume(@Named("orderedValidMountPointChoosers") Set<MountPointChooser> choosers) {
		this.choosers = choosers;
	}

	@Override
	public void mount(CryptoFileSystem fs, String mountFlags) throws InvalidMountPointException, FuseNotSupportedException, VolumeException {
		this.mountPoint = determineMountPoint();

		mount(fs.getPath("/"), mountFlags);
	}

	private Path determineMountPoint() throws InvalidMountPointException {
		for (MountPointChooser chooser : this.choosers) {
			Optional<Path> chosenPath = chooser.chooseMountPoint();
			if (chosenPath.isEmpty()) {
				//Chooser was applicable, but couldn't find a feasible mountpoint
				continue;
			}
			this.cleanupRequired = chooser.prepare(chosenPath.get()); //Fail entirely if an Exception occurs
			this.usedChooser = chooser;
			return chosenPath.get();
		}
		String tried = Joiner.on(", ").join(this.choosers.stream()
				.map((mpc) -> mpc.getClass().getTypeName())
				.collect(ImmutableSet.toImmutableSet()));
		throw new InvalidMountPointException(String.format("No feasible MountPoint found! Tried %s", tried));
	}

	private void mount(Path root, String mountFlags) throws VolumeException {
		try {
			Mounter mounter = FuseMountFactory.getMounter();
			EnvironmentVariables envVars = EnvironmentVariables.create() //
					.withFlags(splitFlags(mountFlags)).withMountPoint(mountPoint) //
					.build();
			this.fuseMnt = mounter.mount(root, envVars);
		} catch (CommandFailedException e) {
			throw new VolumeException("Unable to mount Filesystem", e);
		}
	}

	private String[] splitFlags(String str) {
		return Splitter.on(' ').splitToList(str).toArray(String[]::new);
	}

	@Override
	public void reveal() throws VolumeException {
		try {
			fuseMnt.revealInFileManager();
		} catch (CommandFailedException e) {
			LOG.debug("Revealing the vault in file manger failed: " + e.getMessage());
			throw new VolumeException(e);
		}
	}

	@Override
	public boolean supportsForcedUnmount() {
		return true;
	}

	@Override
	public synchronized void unmountForced() throws VolumeException {
		try {
			fuseMnt.unmountForced();
			fuseMnt.close();
		} catch (CommandFailedException e) {
			throw new VolumeException(e);
		}
		cleanupMountPoint();
	}

	@Override
	public synchronized void unmount() throws VolumeException {
		try {
			fuseMnt.unmount();
			fuseMnt.close();
		} catch (CommandFailedException e) {
			throw new VolumeException(e);
		}
		cleanupMountPoint();
	}

	private void cleanupMountPoint() {
		if (this.cleanupRequired) {
			this.usedChooser.cleanup(this.mountPoint);
		}
	}

	@Override
	public boolean isSupported() {
		return FuseVolume.isSupportedStatic();
	}

	@Override
	public Optional<Path> getMountPoint() {
		return Optional.ofNullable(mountPoint);
	}

	@Override
	public MountPointRequirement getMountPointRequirement() {
		return SystemUtils.IS_OS_WINDOWS ? MountPointRequirement.PARENT_NO_MOUNT_POINT : MountPointRequirement.EMPTY_MOUNT_POINT;
	}

	public static boolean isSupportedStatic() {
		return FuseMountFactory.isFuseSupported();
	}

}
