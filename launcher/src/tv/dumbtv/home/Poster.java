package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One title in the tops row: poster, rank, name, and the same focus ring as an app tile. */
final class Poster extends View {
    private final String name;
    private final int rank;
    private final int cardW, cardH, labelH;
    private final float radius, stroke;
    private final RectF card = new RectF();
    private final RectF dest = new RectF();
    private final Path clip = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap art;

    Poster(Context c, int rank, String name, int cardW, int cardH, int accent) {
        super(c);
        this.rank = rank;
        this.name = name;
        this.cardW = cardW;
        this.cardH = cardH;
        labelH = Ui.dp(c, 36);
        radius = Ui.dp(c, 10);
        stroke = Ui.dp(c, 2.5f);
        fill.setColor(Ui.SURFACE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextSize(Ui.dp(c, 13));
        setFocusable(true);
        setClickable(true);
        setContentDescription(rank + ". " + name);
    }

    void setArt(Bitmap next) {
        Bitmap old = art;
        art = next;
        if (old != null && old != next) old.recycle();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        setArt(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused ? 1.06f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(110).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            float scale = Math.max(cardW / (float) art.getWidth(), cardH / (float) art.getHeight());
            float dw = art.getWidth() * scale, dh = art.getHeight() * scale;
            float left = (cardW - dw) / 2f, top = (cardH - dh) / 2f;
            dest.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(art, null, dest, bitmapPaint);
        }
        canvas.restore();
        if (isFocused()) {
            float half = stroke / 2f;
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius, radius, ring);
        }
        String line = rank + "  " + name;
        float room = cardW - Ui.dp(getContext(), 4);
        CharSequence s = TextUtils.ellipsize(line, under, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), 0, cardH + Ui.dp(getContext(), 16), under);
    }
}
