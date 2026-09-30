package ro.faicu.vwwelcome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Coada punctelor de traseu netrimise, intr-un fisier (un JSON pe linie). Fata de jurnal sunt
 * mult mai multe (un punct la 5 s in mers) si pot astepta ore fara semnal, deci adaugarea
 * trebuie sa fie ieftina: scriem doar la coada fisierului, il rescriem doar dupa trimitere.
 */
final class PointQueue {
    // ~ 3 zile de condus fara internet; peste atat pierdem cele mai vechi puncte.
    private static final int MAX_POINTS = 40_000;

    private PointQueue() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "trip_points.jsonl");
    }

    static synchronized void add(Context c, JSONObject point) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            w.write(point.toString());
            w.write('\n');
        } catch (IOException ignored) {
        }
    }

    /** Fara citirea fisierului: pentru verificarea din tick-ul de 5 s al serviciului. */
    static synchronized boolean isEmpty(Context c) {
        return file(c).length() == 0;
    }

    static synchronized int size(Context c) {
        return readAll(c).size();
    }

    /** Primele max puncte, fara sa le scoatem (le scoate drop dupa succes). */
    static synchronized JSONArray peek(Context c, int max) {
        JSONArray out = new JSONArray();
        List<String> all = readAll(c);
        // Cand coada a crescut peste limita, sarim peste cele mai vechi puncte.
        int from = Math.max(0, all.size() - MAX_POINTS);
        for (int i = from; i < all.size() && out.length() < max; i++) {
            try {
                out.put(new JSONObject(all.get(i)));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** Scoate primele n puncte (dupa taierea la MAX_POINTS, ca in peek). */
    static synchronized void drop(Context c, int n) {
        List<String> all = readAll(c);
        int from = Math.max(0, all.size() - MAX_POINTS) + n;
        File f = file(c);
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (int i = from; i < all.size(); i++) {
                w.write(all.get(i));
                w.write('\n');
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        tmp.renameTo(f);
    }

    private static List<String> readAll(Context c) {
        List<String> lines = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return lines;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (IOException ignored) {
        }
        return lines;
    }
}
