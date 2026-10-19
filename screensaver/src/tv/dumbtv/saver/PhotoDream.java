package tv.dumbtv.saver;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/** Full-screen photo. Any key wakes the TV. Swap the file to change the picture. */
public final class PhotoDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        image.setLayoutParams(lp);

        File dir = getExternalFilesDir(null);
        Bitmap bitmap = null;
        if (dir != null) {
            for (String name : new String[] {"screensaver.png", "screensaver.jpg", "screensaver.webp"}) {
                File file = new File(dir, name);
                if (!file.isFile()) continue;
                bitmap = BitmapFactory.decodeFile(file.getPath());
                if (bitmap != null) break;
            }
        }
        if (bitmap != null) image.setImageBitmap(bitmap);
        setContentView(image);
    }

    /**
     * Waking the screensaver reveals whatever activity was underneath. On Fire OS that is
     * Amazon's home, and a HOME press does not start it again, so the dumbtv hook never sees a
     * new window. Open dumbtv home ourselves before the dream finishes.
     */
    @Override
    public void onWakeUp() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName("tv.dumbtv.home", "tv.dumbtv.home.Home")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
        super.onWakeUp();
    }
}
