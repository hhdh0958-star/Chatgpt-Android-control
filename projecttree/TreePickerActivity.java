package dev.nativeagent.projecttree;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import java.io.IOException;

/** Hosts Android's directory chooser and returns to the original foreground app. */
public final class TreePickerActivity extends Activity {
    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (savedState != null) return;
        Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        choose.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(choose, PICK);
        } catch (android.content.ActivityNotFoundException unavailable) {
            message("系统目录选择器不可用。");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null) {
            try {
                TreeFileStore.select(this, data.getData(), data.getFlags());
                message("手机项目目录已授权。");
            } catch (IOException | SecurityException invalid) {
                Log.w("NativeAgentTree", "Selection rejected: " + invalid.getClass().getSimpleName());
                message("请选择手机内部存储或存储卡中可读写的项目文件夹。");
            }
        }
        finish();
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
