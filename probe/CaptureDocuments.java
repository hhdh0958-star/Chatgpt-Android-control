package dev.nativeagent.probe;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Read-only screenshot source for the system document picker. */
public final class CaptureDocuments extends DocumentsProvider {
    static final String AUTHORITY = "dev.nativeagent.probe.documents";
    static final String ROOT = "phone-captures";
    private static final String LABEL = "手机截图";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        var cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        var row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, LABEL).add(Root.COLUMN_MIME_TYPES, "image/png")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_camera);
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        var cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        if (ROOT.equals(id)) {
            cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, ROOT).add(Document.COLUMN_DISPLAY_NAME, LABEL)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0);
        } else addCapture(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort)
            throws FileNotFoundException {
        if (!ROOT.equals(parent)) throw new FileNotFoundException("Unknown capture folder");
        var cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        try {
            var latest = PhoneCapture.latest(getContext());
            if (latest != null) addCapture(cursor, latest.getLastPathSegment());
        } catch (FileNotFoundException replaced) {
            // A concurrent capture may replace this image before the picker queries it.
        } catch (IOException failure) { throw missing(failure); }
        cursor.setNotificationUri(getContext().getContentResolver(),
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT));
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Capture is read only");
        if (cancellation != null) cancellation.throwIfCanceled();
        return ParcelFileDescriptor.open(file(id).toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void addCapture(MatrixCursor cursor, String id) throws FileNotFoundException {
        Path path = file(id);
        try {
            long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
            cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, "phone-calculator.png")
                .add(Document.COLUMN_MIME_TYPE, "image/png").add(Document.COLUMN_FLAGS, 0)
                .add(Document.COLUMN_SIZE, size).add(Document.COLUMN_LAST_MODIFIED, modified);
        } catch (IOException failure) { throw missing(failure); }
    }

    private Path file(String id) throws FileNotFoundException {
        if (!PhoneCapture.valid(id)) throw new FileNotFoundException("Invalid capture ID");
        Path file = PhoneCapture.folder(getContext()).resolve(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("Capture unavailable");
        return file;
    }

    private static FileNotFoundException missing(IOException failure) {
        var missing = new FileNotFoundException("Capture unavailable");
        missing.initCause(failure);
        return missing;
    }
}
