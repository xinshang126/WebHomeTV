package com.fongmi.android.tv.utils;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileFilter;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VodFileSelectionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void showsDirectoriesAndReadableJsonOnly() throws Exception {
        File root = temp.getRoot();
        File json = temp.newFile("jktv+test.JSON");
        File nested = temp.newFolder("jar");
        File jar = temp.newFile("spider.jar");
        assertEquals(List.of(nested, json), VodFileSelection.list(root));
        assertTrue(VodFileSelection.accepts(json));
        assertFalse(VodFileSelection.accepts(jar));
        assertFalse(VodFileSelection.accepts(nested));
    }

    @Test public void distinguishesEmptyAndInaccessibleDirectories() throws Exception {
        File denied = new File(temp.getRoot(), "denied") {
            @Override public File[] listFiles(FileFilter filter) { return null; }
        };
        assertEquals(List.of(), VodFileSelection.list(temp.newFolder("empty")));
        assertNull(VodFileSelection.list(denied));
    }

    @Test public void leavesTheOriginalFolderForRelativeCompanionFiles() {
        assertEquals("/Download/bundle/config.json", VodFileSelection.pathFromRoot(
                "/storage/emulated/0/Download/bundle/config.json", "/storage/emulated/0"));
        assertEquals("/storage/emulated/01/config.json", VodFileSelection.pathFromRoot(
                "/storage/emulated/01/config.json", "/storage/emulated/0"));
        assertEquals("/storage/USB/bundle/config.json", VodFileSelection.pathFromRoot(
                "/storage/USB/bundle/config.json", "/storage/emulated/0"));
    }

    @Test public void cancellationAndBackDiscardPreviousFolderResults() {
        assertTrue(VodFileSelection.currentNavigation(3, 3, false));
        assertFalse(VodFileSelection.currentNavigation(1, 2, false));
        assertFalse(VodFileSelection.currentNavigation(3, 3, true));
    }
}
