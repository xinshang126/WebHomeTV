package com.fongmi.android.tv.utils;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** File chooser policy, independent of Android's optional system file chooser. */
public final class VodFileSelection {
    private VodFileSelection() {}

    public static boolean accepts(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".json")
                && file.isFile() && file.canRead();
    }

    public static String pathFromRoot(String selected, String sharedRoot) {
        String boundary = sharedRoot.endsWith(File.separator) ? sharedRoot : sharedRoot + File.separator;
        return selected.startsWith(boundary) ? selected.substring(boundary.length() - 1) : selected;
    }

    public static List<File> list(File target) {
        try {
            File[] files = target.listFiles(file -> file.isDirectory() || accepts(file));
            if (files == null) return null;
            Arrays.sort(files, Comparator.comparing(File::isDirectory).reversed()
                    .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            return Arrays.asList(files);
        } catch (SecurityException denied) {
            return null;
        }
    }

    public static boolean currentNavigation(int request, int current, boolean closed) {
        return request == current && !closed;
    }
}
