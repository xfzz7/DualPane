package io.github.xfzz7.dualpane;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class VideoLibrary {
    static final class Entry {
        final String title;
        final String uri;

        Entry(String title, String uri) {
            this.title = title;
            this.uri = uri;
        }
    }

    private static final String[] EXT = {".mp4", ".m4v", ".mov", ".3gp", ".webm", ".ts", ".mkv"};

    private VideoLibrary() {}

    static List<Entry> list(Context context) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Entry> out = new ArrayList<>();
        queryStore(context, seen, out);
        walk(new File("/storage"), 0, seen, out);
        walk(new File("/sdcard"), 0, seen, out);
        return out;
    }

    private static void queryStore(Context context, LinkedHashSet<String> seen, List<Entry> out) {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA
        };
        try (Cursor cursor = context.getContentResolver().query(
                collection, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            while (cursor.moveToNext() && out.size() < 150) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                String data = dataCol >= 0 ? cursor.getString(dataCol) : null;
                String uri = data != null && new File(data).exists()
                        ? Uri.fromFile(new File(data)).toString()
                        : ContentUris.withAppendedId(collection, id).toString();
                add(out, seen, name == null ? context.getString(R.string.fallback_video_name) : name, uri);
            }
        } catch (Exception ignored) {
            // The TV may refuse the store until storage permission is granted.
        }
    }

    private static void walk(File dir, int depth, LinkedHashSet<String> seen, List<Entry> out) {
        if (dir == null || depth > 3 || out.size() >= 150 || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (out.size() >= 150) {
                return;
            }
            String name = kid.getName();
            if (name.startsWith(".") || name.equals("Android")) {
                continue;
            }
            if (kid.isDirectory()) {
                walk(kid, depth + 1, seen, out);
            } else if (isVideo(name) && kid.length() > 32 * 1024) {
                add(out, seen, name, Uri.fromFile(kid).toString());
            }
        }
    }

    private static void add(List<Entry> out, LinkedHashSet<String> seen, String title, String uri) {
        if (uri == null || !seen.add(uri)) {
            return;
        }
        out.add(new Entry(title, uri));
    }

    private static boolean isVideo(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : EXT) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static List<Entry> sorted(List<Entry> entries) {
        Collections.sort(entries, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return entries;
    }
}
