package dev.nativeagent.probe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONException;
import org.json.JSONObject;

final class PhoneCapture {
    static final String AUTHORITY = "dev.nativeagent.probe.captures";
    interface WindowCheck { void run() throws IOException, InterruptedException; }
    interface FrameReader<T> { T read(Bitmap bitmap) throws IOException, InterruptedException, JSONException; }

    static Path folder(Context context) {
        return context.getCacheDir().toPath().resolve("phone-captures");
    }

    static boolean valid(String name) {
        return name != null && name.matches("[a-f0-9]{32}\\.png");
    }

    static synchronized Uri latest(Context context) throws IOException {
        Path dir = folder(context);
        if (!Files.isDirectory(dir)) return null;
        try (var files = Files.list(dir)) {
            return files.filter(f -> valid(f.getFileName().toString())).findFirst()
                .map(f -> Uri.parse("content://" + AUTHORITY + "/" + f.getFileName())).orElse(null);
        }
    }

    static String exportLatest(Context context) throws IOException {
        Uri capture = latest(context);
        if (capture == null) throw new IOException("No calculator screenshot");
        var resolver = context.getContentResolver();
        String filename = "NativeAgent-calculator-" + capture.getLastPathSegment().substring(0, 8) + ".png";
        var values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/NativeAgent");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        try (var input = resolver.openInputStream(capture)) {
            if (input == null) throw new IOException("Calculator screenshot unavailable");
            Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new IOException("Android could not create a Download item");
            boolean published = false;
            try {
                try (var output = resolver.openOutputStream(item)) {
                    if (output == null) throw new IOException("Android could not open the Download item");
                    input.transferTo(output);
                }
                var ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                if (resolver.update(item, ready, null, null) != 1) throw new IOException("Android did not publish the screenshot");
                published = true;
                try (var row = resolver.query(item, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
                    return row != null && row.moveToFirst() ? row.getString(0) : filename;
                }
            } finally {
                if (!published) resolver.delete(item, null, null);
            }
        }
    }

    static JSONObject saveWindow(AccessibilityService service, int window, WindowCheck checkWindow)
            throws IOException, InterruptedException, JSONException {
        return inspectWindow(service, window, checkWindow, bitmap -> publish(service, bitmap));
    }

    /** Processes the approved window frame in memory. The bitmap is valid only during the callback. */
    static <T> T inspectWindow(AccessibilityService service, int window, WindowCheck checkWindow, FrameReader<T> reader)
            throws IOException, InterruptedException, JSONException {
        checkWindow.run();
        var response = new CompletableFuture<AccessibilityService.ScreenshotResult>();
        service.takeScreenshotOfWindow(window, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                if (!response.complete(result)) result.getHardwareBuffer().close();
            }
            @Override public void onFailure(int error) {
                response.completeExceptionally(new IOException("Android declined window screenshot (" + error + ")"));
            }
        });
        try {
            var result = response.get(4, TimeUnit.SECONDS);
            try (var buffer = result.getHardwareBuffer()) {
                checkWindow.run();
                if ((long) buffer.getWidth() * buffer.getHeight() > 8_500_000) throw new IOException("Screenshot exceeds size limit");
                var hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                if (hardware == null) throw new IOException("Screenshot bitmap unavailable");
                Bitmap bitmap;
                try { bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                finally { hardware.recycle(); }
                if (bitmap == null) throw new IOException("Screenshot pixel copy failed");
                try {
                    checkWindow.run();
                    return reader.read(bitmap);
                }
                finally { bitmap.recycle(); }
            }
        } catch (InterruptedException stopped) {
            discard(response);
            throw stopped;
        } catch (TimeoutException timeout) {
            discard(response);
            throw new IOException("Window screenshot timed out");
        } catch (ExecutionException failure) {
            throw new IOException(failure.getCause());
        }
    }

    private static void discard(CompletableFuture<AccessibilityService.ScreenshotResult> response) {
        if (!response.cancel(false)) response.thenAccept(result -> result.getHardwareBuffer().close());
    }

    private static synchronized JSONObject publish(Context context, Bitmap bitmap) throws IOException, JSONException {
        Path folder = Files.createDirectories(folder(context));
        Path staging = Files.createTempFile(folder, ".capture-", ".tmp");
        try {
            try (var output = Files.newOutputStream(staging)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
            }
            byte[] png = Files.readAllBytes(staging);
            if (png.length > 12 * 1024 * 1024) throw new IOException("Screenshot exceeds byte limit");
            String digest;
            try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            Path target = folder.resolve(UUID.randomUUID().toString().replace("-", "") + ".png");
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            try (var previous = Files.list(folder)) {
                for (Path image : previous.filter(f -> valid(f.getFileName().toString()) && !f.equals(target)).toList()) {
                    var uri = Uri.parse("content://" + AUTHORITY + "/" + image.getFileName());
                    context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.revokeUriPermission(android.provider.DocumentsContract.buildDocumentUri(CaptureDocuments.AUTHORITY,
                        image.getFileName().toString()), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Files.deleteIfExists(image);
                }
            }
            context.getContentResolver().notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(
                CaptureDocuments.AUTHORITY, CaptureDocuments.ROOT), null);
            return new JSONObject().put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("sha256", digest).put("bytes", png.length);
        } finally { Files.deleteIfExists(staging); }
    }
}
