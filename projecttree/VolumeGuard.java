package dev.nativeagent.projecttree;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Authorize actual kernel file handles on the selected public volume, before the first byte is written. */
final class VolumeGuard {
    private final String prefix;
    private final Path publicRoot;
    private final long publicDevice;
    private final long dataDevice;
    private final List<Path> aliases = new ArrayList<>();
    private final Path mediaRoot;

    VolumeGuard(Context context, String treeId) throws IOException {
        int colon = treeId.indexOf(':');
        if (colon <= 0) throw new IOException("Invalid tree identity");
        prefix = treeId.substring(0, colon + 1);
        String[] project = TreeFileStore.segments(treeId.substring(colon + 1));
        if (project[0].equalsIgnoreCase("Android")) throw new IOException("Expected a user project folder");
        String volumeId = treeId.substring(0, colon);
        StorageVolume volume = context.getSystemService(StorageManager.class).getStorageVolumes().stream()
            .filter(v -> v.isPrimary() ? volumeId.equals("primary") : volumeId.equalsIgnoreCase(v.getUuid()))
            .findFirst().orElseThrow(() -> new IOException("Unknown storage volume"));
        if (volume.getDirectory() == null || !"mounted".equals(volume.getState())) throw new IOException("Volume unavailable");
        publicRoot = volume.getDirectory().toPath().normalize();
        if (!publicRoot.startsWith("/storage") || publicRoot.getNameCount() < 2) throw new IOException("Unexpected volume root");
        try {
            publicDevice = Os.stat(publicRoot.toString()).st_dev;
            dataDevice = Os.stat(context.getFilesDir().getPath()).st_dev;
            if (publicDevice == Os.stat("/").st_dev) throw new IOException("System volume rejected");
        } catch (ErrnoException error) { throw new IOException("Cannot verify storage volume", error); }
        aliases.add(publicRoot);
        String user = publicRoot.getFileName().toString();
        if (volume.isPrimary() && publicRoot.equals(Path.of("/storage/emulated").resolve(user)) && user.matches("[0-9]+")) {
            for (String mount : List.of("user", "pass_through")) {
                aliases.add(Path.of("/mnt", mount, user, "emulated", user));
            }
            mediaRoot = Path.of("/data/media").resolve(user);
        } else mediaRoot = null;
    }

    private String relative(String documentId) throws IOException {
        if (!documentId.startsWith(prefix)) throw new IOException("Different volume");
        String value = documentId.substring(prefix.length());
        TreeFileStore.segments(value);
        return value;
    }

    void directory(String documentId) throws IOException {
        Path current = publicRoot;
        try {
            for (String part : relative(documentId).split("/")) {
                current = current.resolve(part);
                var stat = Os.lstat(current.toString());
                if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_dev != publicDevice) {
                    throw new IOException("Directory is not on the authorized public volume");
                }
            }
        } catch (ErrnoException error) { throw new IOException("Cannot verify project directory", error); }
    }

    void file(ParcelFileDescriptor descriptor, String documentId) throws IOException {
        String relative = relative(documentId);
        try {
            var stat = Os.fstat(descriptor.getFileDescriptor());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1) throw new IOException("Expected a regular project file");
            String target = Os.readlink("/proc/self/fd/" + descriptor.getFd());
            for (Path root : aliases) {
                if (root.resolve(relative).toString().equals(target) && stat.st_dev == publicDevice) return;
            }
            // ExternalStorageProvider may return a direct descriptor for the emulated user's media directory.
            if (mediaRoot != null && mediaRoot.resolve(relative).toString().equals(target) && stat.st_dev == dataDevice) return;
            throw new IOException("Descriptor is outside the authorized project volume");
        } catch (ErrnoException error) { throw new IOException("Cannot verify project descriptor", error); }
    }
}
