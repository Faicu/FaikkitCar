package ro.faicu.faikkitcar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellIdentityNr;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Semnalul mobil al navigatiei (cartela din ea) pe fiecare punct de traseu: tehnologia, canalul
 * (din el serverul afla banda), puterea in dBm, operatorul si daca are internet. Asa se vede pe
 * harta unde cade reteaua. getAllCellInfo cere doar permisiunea de locatie (o avem pentru GPS).
 */
final class CellSignal {
    private CellSignal() {
    }

    /** Adauga in punct: rat (lte/nr/3g/2g/-), ch (EARFCN/ARFCN), sig (dBm), op (MCC+MNC), net (0/1/2). */
    static void put(Context c, JSONObject p) throws JSONException {
        p.put("net", online(c));
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            List<CellInfo> cells = tm.getAllCellInfo();
            CellInfo cell = null;
            if (cells != null) {
                for (CellInfo ci : cells) {
                    if (ci.isRegistered()) {
                        cell = ci;
                        break;
                    }
                }
            }
            if (cell == null) {
                p.put("rat", "-");
                return;
            }
            String op = null;
            if (cell instanceof CellInfoLte) {
                CellInfoLte l = (CellInfoLte) cell;
                p.put("rat", "lte");
                put(p, "ch", l.getCellIdentity().getEarfcn());
                int rsrp = l.getCellSignalStrength().getRsrp();
                put(p, "sig", rsrp != Integer.MAX_VALUE ? rsrp : l.getCellSignalStrength().getDbm());
                op = op(l.getCellIdentity().getMccString(), l.getCellIdentity().getMncString());
            } else if (cell instanceof CellInfoNr) {
                CellInfoNr n = (CellInfoNr) cell;
                CellIdentityNr id = (CellIdentityNr) n.getCellIdentity();
                p.put("rat", "nr");
                put(p, "ch", id.getNrarfcn());
                put(p, "sig", ((CellSignalStrengthNr) n.getCellSignalStrength()).getSsRsrp());
                op = op(id.getMccString(), id.getMncString());
            } else if (cell instanceof CellInfoWcdma) {
                CellInfoWcdma w = (CellInfoWcdma) cell;
                p.put("rat", "3g");
                put(p, "ch", w.getCellIdentity().getUarfcn());
                put(p, "sig", w.getCellSignalStrength().getDbm());
                op = op(w.getCellIdentity().getMccString(), w.getCellIdentity().getMncString());
            } else if (cell instanceof CellInfoGsm) {
                CellInfoGsm g = (CellInfoGsm) cell;
                p.put("rat", "2g");
                put(p, "ch", g.getCellIdentity().getArfcn());
                put(p, "sig", g.getCellSignalStrength().getDbm());
                op = op(g.getCellIdentity().getMccString(), g.getCellIdentity().getMncString());
            }
            if (op == null) op = tm.getNetworkOperator();
            if (op != null && !op.isEmpty()) p.put("op", op);
        } catch (RuntimeException ignored) {
            // Fara permisiune sau ROM-ul nu raporteaza celulele: punctul ramane doar cu „net”.
        }
    }

    /** 0 = fara internet, 1 = internet pe date mobile, 2 = internet pe Wi-Fi (hotspot). */
    private static int online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return 0;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return 0;
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 2 : 1;
    }

    /** Valorile lipsa vin ca Integer.MAX_VALUE; canalul e >= 0, puterea intre -160 si -20 dBm. */
    private static void put(JSONObject p, String key, int v) throws JSONException {
        boolean ok = key.equals("ch") ? v >= 0 && v != Integer.MAX_VALUE : v >= -160 && v <= -20;
        if (ok) p.put(key, v);
    }

    private static String op(String mcc, String mnc) {
        return mcc == null || mnc == null ? null : mcc + mnc;
    }
}
