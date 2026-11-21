package ro.faicu.faikkitcar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Coada punctelor de traseu netrimise (`trip_points.jsonl`). Fata de jurnal sunt mult mai multe
 * (un punct la 5 s in mers) si pot astepta ore fara semnal, deci stau intr-un fisier.
 */
final class PointQueue {
    // ~ 3 zile de condus fara internet; peste atat pierdem cele mai vechi puncte.
    private static final JsonlQueue Q = new JsonlQueue("trip_points.jsonl", 40_000);

    private PointQueue() {}

    static void add(Context c, JSONObject point) {
        Q.add(c, point);
    }

    static boolean isEmpty(Context c) {
        return Q.isEmpty(c);
    }

    static int size(Context c) {
        return Q.size(c);
    }

    static JSONArray peek(Context c, int max) {
        return Q.peek(c, max);
    }

    static void drop(Context c, int n) {
        Q.drop(c, n);
    }
}
