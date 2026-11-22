package dev.nativeagent.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Non-exported, same-process, single-use decision. No model or external component can answer it. */
public final class WriteReviewActivity extends Activity {
    private static final AtomicReference<Review> pending = new AtomicReference<>();
    private record Review(String token, String title, String detail, CountDownLatch done,
                          java.util.concurrent.atomic.AtomicBoolean approved) {
        Review(String title, String detail) {
            this(UUID.randomUUID().toString(), title, detail, new CountDownLatch(1),
                new java.util.concurrent.atomic.AtomicBoolean());
        }
    }
    private Review review;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = new Runnable() {
        public void run() {
            if (review == null || pending.get() != review) finish();
            else handler.postDelayed(this, 300);
        }
    };

    static boolean ask(Context context, String title, String detail) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper() || detail.length() > 24000) return false;
        Review request = new Review(title, detail);
        if (!pending.compareAndSet(null, request)) return false;
        try {
            context.startActivity(new Intent(context, WriteReviewActivity.class)
                .putExtra("token", request.token()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return request.done().await(60, TimeUnit.SECONDS) && request.approved().get();
        } finally { pending.compareAndSet(request, null); }
    }

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        review = pending.get();
        if (review == null || !review.token().equals(getIntent().getStringExtra("token"))) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        var dialog = new AlertDialog.Builder(this).setTitle(review.title()).setMessage(review.detail())
            .setNegativeButton("取消", (d,w) -> decide(false))
            .setPositiveButton("仅允许这一次", (d,w) -> decide(true))
            .setOnCancelListener(d -> decide(false)).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        handler.postDelayed(expire, 300);
    }

    private void decide(boolean allowed) {
        if (review != null && pending.compareAndSet(review, null)) {
            review.approved().set(allowed);
            review.done().countDown();
        }
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!isChangingConfigurations() && review != null && pending.compareAndSet(review, null)) review.done().countDown();
        super.onDestroy();
    }
}
