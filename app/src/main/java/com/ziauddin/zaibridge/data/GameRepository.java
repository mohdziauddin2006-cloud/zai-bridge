package com.ziauddin.zaibridge.data;

import android.content.Context;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's only view of storage. It lists completed imports under
 * {@code files/games/}, never their contents: OBBs live inside those
 * directories and are deliberately invisible to the UI.
 */
public final class GameRepository {

    private GameRepository() {
    }

    public static File root(Context context) {
        File root = new File(context.getFilesDir(), "games");
        if (!root.exists() && !root.mkdirs()) {
            // A read-only filesDir is a broken install; callers will simply see
            // an empty library rather than crash.
            return root;
        }
        return root;
    }

    public static List<Game> loadAll(Context context) {
        List<Game> games = new ArrayList<>();
        File root = root(context);
        File[] dirs = root.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory() && !pathname.getName().startsWith(".");
            }
        });
        if (dirs != null) {
            for (File dir : dirs) {
                Game game = Game.load(dir);
                if (game != null) games.add(game);
            }
        }
        // Most recently imported first, which is what a launcher grid wants.
        Collections.sort(games, (a, b) -> Long.compare(b.importedAt, a.importedAt));
        return games;
    }

    public static Game load(File dir) {
        return Game.load(dir);
    }

    public static File gameDir(Context context, String directoryName) {
        return new File(root(context), directoryName);
    }
}
