package dev.nativeagent.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Serves a single captured PNG by an Android-granted, read-only URI. */
public final class PhoneCaptureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private Path file(Uri uri) throws FileNotFoundException {
        var path = uri.getPathSegments();
        if (!PhoneCapture.AUTHORITY.equals(uri.getAuthority()) || path.size() != 1 || !PhoneCapture.valid(path.get(0))) {
            throw new FileNotFoundException("Invalid capture URI");
        }
        Path file = PhoneCapture.folder(getContext()).resolve(path.get(0));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("Capture unavailable");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri).toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        var columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        var cursor = new MatrixCursor(columns);
        try {
            var capture = file(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = switch (columns[i]) {
                    case OpenableColumns.DISPLAY_NAME -> "phone-calculator.png";
                    case OpenableColumns.SIZE -> capture.toFile().length();
                    default -> null;
                };
            }
            cursor.addRow(row);
        } catch (FileNotFoundException missing) { /* Empty result. */ }
        return cursor;
    }

    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
