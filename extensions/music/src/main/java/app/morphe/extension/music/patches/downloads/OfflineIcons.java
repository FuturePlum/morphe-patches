/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Icons of the offline screens, drawn in code on a 24 x 24 grid.
 * They never depend on the names of the drawables of the app, which change between versions.
 */
final class OfflineIcons {

    enum Icon {
        CLOSE, CHEVRON_DOWN, CHEVRON_LEFT, PLAY, PAUSE, PREVIOUS, NEXT, SHUFFLE, REPEAT, REPEAT_ONE,
        HEART, HEART_FILLED, QUEUE, SEARCH, SORT, MORE, TRASH, DOWNLOAD, UPLOAD, PERSON, CHECK, PLUS,
        NOTE, CHEVRON_UP, HANDLE, BARS
    }

    private OfflineIcons() {
    }

    /** @param color Color of the icon, also changeable later with a color filter. */
    static Drawable get(Icon icon, int sizePx, int color) {
        return new IconDrawable(icon, sizePx, color);
    }

    private record Part(Path path, boolean fill) {
    }

    private static Part stroke(Path path) {
        return new Part(path, false);
    }

    private static Part fill(Path path) {
        return new Part(path, true);
    }

    private static Path poly(boolean close, float... xy) {
        Path path = new Path();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        if (close) path.close();
        return path;
    }

    private static Path dot(float x, float y, float radius) {
        Path path = new Path();
        path.addCircle(x, y, radius, Path.Direction.CW);
        return path;
    }

    private static Path round(float l, float t, float r, float b, float radius) {
        Path path = new Path();
        path.addRoundRect(new RectF(l, t, r, b), radius, radius, Path.Direction.CW);
        return path;
    }

    private static Path heart() {
        Path p = new Path();
        p.moveTo(12, 20.5f);
        p.cubicTo(5, 15.5f, 3, 12f, 3, 9f);
        p.cubicTo(3, 6.5f, 5, 4.5f, 7.5f, 4.5f);
        p.cubicTo(9.5f, 4.5f, 11, 5.5f, 12, 7f);
        p.cubicTo(13, 5.5f, 14.5f, 4.5f, 16.5f, 4.5f);
        p.cubicTo(19, 4.5f, 21, 6.5f, 21, 9f);
        p.cubicTo(21, 12f, 19, 15.5f, 12, 20.5f);
        p.close();
        return p;
    }

    private static Path repeatTop() {
        Path p = new Path();
        p.moveTo(4, 12);
        p.lineTo(4, 10);
        p.quadTo(4, 7, 7, 7);
        p.lineTo(19, 7);
        p.moveTo(16, 4);
        p.lineTo(19.5f, 7);
        p.lineTo(16, 10);
        return p;
    }

    private static Path repeatBottom() {
        Path p = new Path();
        p.moveTo(20, 12);
        p.lineTo(20, 14);
        p.quadTo(20, 17, 17, 17);
        p.lineTo(5, 17);
        p.moveTo(8, 14);
        p.lineTo(4.5f, 17);
        p.lineTo(8, 20);
        return p;
    }

    private static List<Part> parts(Icon icon) {
        List<Part> list = new ArrayList<>();
        switch (icon) {
            case CLOSE -> {
                list.add(stroke(poly(false, 6, 6, 18, 18)));
                list.add(stroke(poly(false, 18, 6, 6, 18)));
            }
            case CHEVRON_DOWN -> list.add(stroke(poly(false, 5.5f, 9, 12, 15.5f, 18.5f, 9)));
            case CHEVRON_UP -> list.add(stroke(poly(false, 5.5f, 15, 12, 8.5f, 18.5f, 15)));
            case CHEVRON_LEFT -> list.add(stroke(poly(false, 15, 5, 8, 12, 15, 19)));
            case PLAY -> list.add(fill(poly(true, 7, 4.5f, 7, 19.5f, 19.5f, 12)));
            case PAUSE -> {
                list.add(fill(round(6, 4.5f, 10, 19.5f, 1)));
                list.add(fill(round(14, 4.5f, 18, 19.5f, 1)));
            }
            case PREVIOUS -> {
                list.add(fill(round(5.5f, 5, 8, 19, 1)));
                list.add(fill(poly(true, 19, 5, 19, 19, 9, 12)));
            }
            case NEXT -> {
                list.add(fill(round(16, 5, 18.5f, 19, 1)));
                list.add(fill(poly(true, 5, 5, 5, 19, 15, 12)));
            }
            case SHUFFLE -> {
                Path a = new Path();
                a.moveTo(3.5f, 7);
                a.lineTo(7, 7);
                a.cubicTo(11, 7, 13, 17, 17, 17);
                a.lineTo(19.5f, 17);
                Path b = new Path();
                b.moveTo(3.5f, 17);
                b.lineTo(7, 17);
                b.cubicTo(11, 17, 13, 7, 17, 7);
                b.lineTo(19.5f, 7);
                list.add(stroke(a));
                list.add(stroke(b));
                list.add(stroke(poly(false, 17, 4, 20.5f, 7, 17, 10)));
                list.add(stroke(poly(false, 17, 14, 20.5f, 17, 17, 20)));
            }
            case REPEAT -> {
                list.add(stroke(repeatTop()));
                list.add(stroke(repeatBottom()));
            }
            case REPEAT_ONE -> {
                list.add(stroke(repeatTop()));
                list.add(stroke(repeatBottom()));
                list.add(stroke(poly(false, 10.5f, 10.8f, 12, 9.8f, 12, 14.2f)));
            }
            case HEART -> list.add(stroke(heart()));
            case HEART_FILLED -> list.add(fill(heart()));
            case QUEUE -> {
                list.add(stroke(poly(false, 4, 7, 14, 7)));
                list.add(stroke(poly(false, 4, 12, 14, 12)));
                list.add(stroke(poly(false, 4, 17, 10, 17)));
                list.add(fill(poly(true, 15.5f, 13.5f, 15.5f, 20.5f, 21, 17)));
            }
            case SEARCH -> {
                Path circle = new Path();
                circle.addCircle(10.5f, 10.5f, 5.5f, Path.Direction.CW);
                list.add(stroke(circle));
                list.add(stroke(poly(false, 14.6f, 14.6f, 19.5f, 19.5f)));
            }
            case SORT -> {
                list.add(stroke(poly(false, 4, 7, 20, 7)));
                list.add(stroke(poly(false, 4, 12, 15, 12)));
                list.add(stroke(poly(false, 4, 17, 10, 17)));
            }
            case MORE -> {
                list.add(fill(dot(12, 5, 1.9f)));
                list.add(fill(dot(12, 12, 1.9f)));
                list.add(fill(dot(12, 19, 1.9f)));
            }
            case TRASH -> {
                list.add(stroke(poly(false, 4.5f, 7, 19.5f, 7)));
                list.add(stroke(poly(false, 9, 7, 9, 4.5f, 15, 4.5f, 15, 7)));
                list.add(stroke(poly(false, 6.5f, 7, 7.5f, 19.5f, 16.5f, 19.5f, 17.5f, 7)));
                list.add(stroke(poly(false, 10.2f, 10.5f, 10.2f, 16.5f)));
                list.add(stroke(poly(false, 13.8f, 10.5f, 13.8f, 16.5f)));
            }
            case DOWNLOAD -> {
                list.add(stroke(poly(false, 12, 4, 12, 15)));
                list.add(stroke(poly(false, 7, 10.5f, 12, 15.5f, 17, 10.5f)));
                list.add(stroke(poly(false, 5, 20, 19, 20)));
            }
            case UPLOAD -> {
                list.add(stroke(poly(false, 12, 16, 12, 5)));
                list.add(stroke(poly(false, 7, 9.5f, 12, 4.5f, 17, 9.5f)));
                list.add(stroke(poly(false, 5, 20, 19, 20)));
            }
            case PERSON -> {
                Path head = new Path();
                head.addCircle(12, 8, 4, Path.Direction.CW);
                Path body = new Path();
                body.moveTo(4.5f, 20);
                body.cubicTo(4.5f, 14.5f, 19.5f, 14.5f, 19.5f, 20);
                list.add(stroke(head));
                list.add(stroke(body));
            }
            case CHECK -> list.add(stroke(poly(false, 5, 12.5f, 10, 17.5f, 19, 7.5f)));
            case PLUS -> {
                list.add(stroke(poly(false, 12, 5, 12, 19)));
                list.add(stroke(poly(false, 5, 12, 19, 12)));
            }
            case HANDLE -> {
                list.add(stroke(poly(false, 5, 9.5f, 19, 9.5f)));
                list.add(stroke(poly(false, 5, 14.5f, 19, 14.5f)));
            }
            case BARS -> {
                list.add(fill(round(6, 11, 9.2f, 19.5f, 1.2f)));
                list.add(fill(round(10.4f, 4.5f, 13.6f, 19.5f, 1.2f)));
                list.add(fill(round(14.8f, 8, 18, 19.5f, 1.2f)));
            }
            case NOTE -> {
                list.add(fill(dot(8.5f, 17, 3.2f)));
                list.add(stroke(poly(false, 11.5f, 17, 11.5f, 5, 18.5f, 7.5f)));
            }
        }
        return list;
    }

    private static final class IconDrawable extends Drawable {
        private final List<Part> parts;
        private final int size;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable(Icon icon, int size, int color) {
            this.size = size;
            parts = parts(icon);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2f);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int count = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            float scale = Math.min(getBounds().width(), getBounds().height()) / 24f;
            canvas.scale(scale, scale);
            for (Part part : parts) {
                canvas.drawPath(part.path(), part.fill() ? fillPaint : strokePaint);
            }
            canvas.restoreToCount(count);
        }

        @Override
        public void setAlpha(int alpha) {
            strokePaint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            strokePaint.setColorFilter(filter);
            fillPaint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
