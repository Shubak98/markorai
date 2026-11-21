package net.gsantner.markor.ai_assistant;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ImageEncodingHelper {
    private static final int MAX_DIMENSION = 1280;
    private static final int MAX_BASE64_CHARS = 1_000_000;

    private ImageEncodingHelper() {
    }

    public static String encodeJpeg(final ContentResolver resolver, final Uri uri) throws IOException {
        if (resolver == null || uri == null) {
            throw new IOException("The selected image is unavailable.");
        }
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Could not open the selected image.");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("The selected file is not a supported image.");
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        final Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Could not reopen the selected image.");
            }
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) {
            throw new IOException("Could not decode the selected image.");
        }
        try {
            Bitmap scaled = bitmap;
            final int longestSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longestSide > MAX_DIMENSION) {
                final float scale = (float) MAX_DIMENSION / longestSide;
                scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            }
            final boolean scaledFromOriginal = scaled != bitmap;
            try {
                while (true) {
                    final ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)) {
                        throw new IOException("Could not compress the selected image.");
                    }
                    final byte[] jpeg = output.toByteArray();
                    if (jpeg.length > MAX_BASE64_CHARS * 3 / 4) {
                        if (Math.max(scaled.getWidth(), scaled.getHeight()) <= 320) {
                            throw new IOException("The selected image is too large to attach "
                                    + "(maximum about 1 MB).");
                        }
                        final Bitmap smaller = Bitmap.createScaledBitmap(scaled,
                                Math.max(1, Math.round(scaled.getWidth() * 0.8f)),
                                Math.max(1, Math.round(scaled.getHeight() * 0.8f)), true);
                        if (scaled != bitmap) {
                            scaled.recycle();
                        }
                        scaled = smaller;
                        continue;
                    }
                    final String encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP);
                    if (encoded.length() > MAX_BASE64_CHARS) {
                        throw new IOException("The selected image is too large to attach (maximum about 1 MB).");
                    }
                    return encoded;
                }
            } finally {
                if (scaled != bitmap && (scaledFromOriginal || !scaled.isRecycled())) {
                    scaled.recycle();
                }
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static int sampleSize(final int width, final int height) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > MAX_DIMENSION * 2) {
            sample *= 2;
        }
        return sample;
    }
}
