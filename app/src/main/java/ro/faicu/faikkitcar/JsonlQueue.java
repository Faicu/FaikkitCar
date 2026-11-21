package ro.faicu.faikkitcar;

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
 * O coada de trimis la server intr-un fisier (un JSON pe linie): adaugarea scrie doar la coada
 * fisierului, rescrierea se face doar dupa trimitere. Peste `max` elemente se pierd cele mai vechi.
 */
final class JsonlQueue {
    private final String name;
    private final int max;

    JsonlQueue(String name, int max) {
        this.name = name;
        this.max = max;
    }

    private File file(Context c) {
        return new File(c.getFilesDir(), name);
    }

    synchronized void add(Context c, JSONObject item) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            w.write(item.toString());
            w.write('\n');
        } catch (IOException ignored) {
        }
    }

    /** Mai multe deodata, cu o singura deschidere a fisierului. */
    synchronized void addAll(Context c, List<JSONObject> items) {
        if (items.isEmpty()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            for (JSONObject item : items) {
                w.write(item.toString());
                w.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    /** Fara citirea fisierului: pentru verificarea din tick-ul de 5 s al serviciului. */
    synchronized boolean isEmpty(Context c) {
        return file(c).length() == 0;
    }

    synchronized int size(Context c) {
        return readAll(c).size();
    }

    /** Primele n elemente, fara sa le scoatem (le scoate drop dupa succes). */
    synchronized JSONArray peek(Context c, int n) {
        JSONArray out = new JSONArray();
        List<String> all = readAll(c);
        // Cand coada a crescut peste limita, sarim peste cele mai vechi.
        int from = Math.max(0, all.size() - max);
        for (int i = from; i < all.size() && out.length() < n; i++) {
            try {
                out.put(new JSONObject(all.get(i)));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** Scoate primele n elemente (dupa taierea la `max`, ca in peek). */
    synchronized void drop(Context c, int n) {
        List<String> all = readAll(c);
        int from = Math.max(0, all.size() - max) + n;
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

    private List<String> readAll(Context c) {
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
