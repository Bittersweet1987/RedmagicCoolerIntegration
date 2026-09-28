package de.redmagiccooler.blediagnose;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BLE-Diagnose: prueft Schritt fuer Schritt, warum ein Android-Geraet den RedMagic-Kuehler
 * (oder ueberhaupt BLE-Geraete) nicht findet, und schreibt ein lesbares Log fuer Entwickler.
 * Sendet keine Befehle an den Kuehler, der optionale Verbindungstest liest nur.
 */
public class MainActivity extends Activity {

    private static final String APP_VERSION = "1.0";
    private static final UUID COOLER_SERVICE = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String[] NAME_HINTS = {"MAGCOOLER", "REDMAGIC", "RED MAGIC", "COOLER"};
    private static final long SCAN_ALL_MS = 12000;
    private static final long SCAN_FILTER_MS = 7000;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long GATT_OP_TIMEOUT_MS = 3000;
    private static final long NOTIFY_LISTEN_MS = 5000;
    private static final int REQ_PERMS = 42;
    private static final int MAX_LISTED_DEVICES = 40;

    // Android drosselt mehr als 5 startScan-Aufrufe in 30 s pro App.
    private static final List<Long> scanStarts = new ArrayList<Long>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder details = new StringBuilder();
    private final List<String> findings = new ArrayList<String>();

    private TextView txt;
    private ScrollView scroll;
    private Button btnStart;
    private Button btnShare;
    private Button btnCopy;
    private CheckBox cbConnect;

    private boolean running;
    private long t0;
    private String report = "";
    private Uri savedUri;
    private String savedPath;

    private BluetoothManager btManager;
    private BluetoothAdapter adapter;
    private ScanRun scanAll;
    private ScanRun scanUuid;
    private ScanRun scanName;
    private Dev cooler;

    private BluetoothGatt gatt;
    private final List<Runnable> gattOps = new ArrayList<Runnable>();
    private Runnable gattOpTimeout;
    private Runnable connectTimeout;
    private boolean connectDone;
    private boolean gattConnected;
    private boolean coolerServiceSeen;
    private int notifyCount;
    private final Map<String, Integer> notifyPerChar = new LinkedHashMap<String, Integer>();

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("BLE-Diagnose für RedMagic-Kühler");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Kühler einschalten, die Hersteller-App und andere Kühler-Apps schließen, "
                + "dann „Test starten“. Der Test dauert ca. 30–50 s. Danach mit „Log teilen“ an den Entwickler schicken.");
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = addButton(row, "Test starten");
        btnShare = addButton(row, "Log teilen");
        btnCopy = addButton(row, "Kopieren");
        root.addView(row);

        cbConnect = new CheckBox(this);
        cbConnect.setText("Verbindungstest, falls Kühler gefunden (nur lesen, sendet keine Befehle)");
        cbConnect.setChecked(true);
        root.addView(cbConnect);

        scroll = new ScrollView(this);
        txt = new TextView(this);
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        txt.setTextIsSelectable(true);
        txt.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(txt);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        btnShare.setEnabled(false);
        btnCopy.setEnabled(false);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTest();
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareReport();
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReport();
            }
        });
        txt.setText("Bereit.");
    }

    private Button addButton(LinearLayout row, String label) {
        Button b = new Button(this);
        b.setText(label);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        closeGatt();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Log

    private synchronized void log(String line) {
        long ms = SystemClock.elapsedRealtime() - t0;
        details.append(String.format(Locale.ROOT, "+%6.2fs  %s%n", ms / 1000.0, line));
        final String snapshot = details.toString();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                txt.setText(snapshot);
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private synchronized void section(String name) {
        details.append("\n--- ").append(name).append(" ---\n");
    }

    private void finding(String level, String text) {
        findings.add("[" + level + "] " + text);
    }

    // ------------------------------------------------------------------ Ablauf

    private void startTest() {
        if (running) return;
        running = true;
        btnStart.setEnabled(false);
        btnShare.setEnabled(false);
        btnCopy.setEnabled(false);
        synchronized (this) {
            details.setLength(0);
        }
        findings.clear();
        cooler = null;
        scanAll = null;
        scanUuid = null;
        scanName = null;
        savedUri = null;
        savedPath = null;
        connectDone = false;
        gattConnected = false;
        coolerServiceSeen = false;
        notifyCount = 0;
        notifyPerChar.clear();
        t0 = SystemClock.elapsedRealtime();

        section("GERÄT & APP");
        logEnvironment();

        List<String> missing = missingPermissions();
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            log("Frage fehlende Berechtigungen an: " + shortPerms(missing));
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        } else {
            afterPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMS || !running) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            log("Antwort Dialog: " + shortPerm(permissions[i]) + " -> " + (granted ? "erteilt" : "ABGELEHNT"));
        }
        afterPermissions();
    }

    private void afterPermissions() {
        section("BERECHTIGUNGEN");
        checkPermissions();

        section("BLUETOOTH, STANDORT, ENERGIE");
        boolean btOk = checkBluetoothAndLocation();
        if (!btOk) {
            finishTest();
            return;
        }

        section("VERBUNDENE / GEKOPPELTE GERÄTE");
        listConnectedAndBonded();

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                runScanAll();
            }
        }, 500);
    }

    private void runScanAll() {
        scanAll = new ScanRun("SCAN 1: OHNE FILTER (wie die Test-App)");
        startScan(scanAll, null, "keiner", SCAN_ALL_MS, new Runnable() {
            @Override
            public void run() {
                evaluateScanAll();
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runScanUuid();
                    }
                }, 1000);
            }
        });
    }

    private void runScanUuid() {
        scanUuid = new ScanRun("SCAN 2: HARDWARE-FILTER AUF SERVICE-UUID");
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(COOLER_SERVICE)).build());
        startScan(scanUuid, filters, "serviceUuid=" + COOLER_SERVICE, SCAN_FILTER_MS, new Runnable() {
            @Override
            public void run() {
                evaluateFilterScan(scanUuid, "Filter auf Service-UUID");
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runScanName();
                    }
                }, 1000);
            }
        });
    }

    private void runScanName() {
        final String name = cooler != null ? cooler.advName : null;
        if (name == null || name.length() == 0) {
            afterScans();
            return;
        }
        scanName = new ScanRun("SCAN 3: HARDWARE-FILTER AUF GERÄTENAMEN");
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(new ScanFilter.Builder().setDeviceName(name).build());
        startScan(scanName, filters, "deviceName=\"" + name + "\"", SCAN_FILTER_MS, new Runnable() {
            @Override
            public void run() {
                evaluateFilterScan(scanName, "Filter auf exakten Namen \"" + name + "\"");
                afterScans();
            }
        });
    }

    private void afterScans() {
        if (cooler != null && cbConnect.isChecked()) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startConnectTest();
                }
            }, 800);
        } else {
            if (cooler != null) log("Verbindungstest übersprungen (Häkchen nicht gesetzt).");
            finishTest();
        }
    }

    // ------------------------------------------------------------------ Umgebung

    private void logEnvironment() {
        log("Hersteller/Modell: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        String patch = Build.VERSION.SDK_INT >= 23 ? ", Patch " + Build.VERSION.SECURITY_PATCH : "";
        log("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + patch + ")");
        log("Firmware: " + Build.DISPLAY);
        log("Diese App: BLE-Diagnose " + APP_VERSION + ", targetSdk " + getApplicationInfo().targetSdkVersion
                + ", BLUETOOTH_SCAN ohne neverForLocation, fragt Standort mit ab");
    }

    private List<String> requiredPermissions() {
        List<String> p = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return p;
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<String>();
        for (String p : requiredPermissions()) {
            if (!has(p)) missing.add(p);
        }
        return missing;
    }

    private boolean has(String perm) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissions() {
        for (String p : requiredPermissions()) {
            log(String.format(Locale.ROOT, "%-24s %s", shortPerm(p), has(p) ? "erteilt" : "FEHLT"));
        }
        boolean fine = has(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) {
                finding("FEHLER", "BLUETOOTH_SCAN („Geräte in der Nähe“) nicht erteilt. startScan() wirft dann "
                        + "SecurityException oder liefert nichts. In den App-Einstellungen erlauben.");
            }
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) {
                finding("FEHLER", "BLUETOOTH_CONNECT nicht erteilt. Ohne sie gibt device.getName() null zurück "
                        + "und connectGatt() schlägt fehl.");
            }
            if (!fine) {
                finding("HINWEIS", "Standort-Berechtigung fehlt. Eine App, die BLUETOOTH_SCAN OHNE "
                        + "neverForLocation deklariert, braucht sie trotzdem.");
            }
        } else if (!fine) {
            finding("FEHLER", "ACCESS_FINE_LOCATION fehlt. Bis Android 11 liefert ein BLE-Scan ohne diese "
                    + "Berechtigung stumm 0 Ergebnisse.");
        }
    }

    private boolean checkBluetoothAndLocation() {
        boolean leFeature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        log("BLE-Hardware (FEATURE_BLUETOOTH_LE): " + yn(leFeature));
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = btManager != null ? btManager.getAdapter() : null;
        if (!leFeature || adapter == null) {
            finding("FEHLER", "Kein Bluetooth-LE-Adapter verfügbar.");
            return false;
        }

        boolean enabled = adapter.isEnabled();
        log("Bluetooth eingeschaltet: " + yn(enabled) + " (state=" + adapterState(adapter.getState()) + ")");
        log("Offloaded Filtering: " + yn(adapter.isOffloadedFilteringSupported())
                + ", Offloaded Batching: " + yn(adapter.isOffloadedScanBatchingSupported()));
        if (Build.VERSION.SDK_INT >= 26) {
            log("LE 2M PHY: " + yn(adapter.isLe2MPhySupported())
                    + ", Extended Advertising: " + yn(adapter.isLeExtendedAdvertisingSupported()));
        }

        boolean loc = isLocationOn();
        log("Standort (GPS-Schalter): " + (loc ? "AN" : "AUS"));
        if (!loc) {
            if (Build.VERSION.SDK_INT < 31) {
                finding("FEHLER", "Standort ist aus. Bis Android 11 liefert ein BLE-Scan dann 0 Ergebnisse.");
            } else {
                finding("WARNUNG", "Standort ist aus. Apps, die BLUETOOTH_SCAN ohne neverForLocation deklarieren, "
                        + "bekommen dann je nach Hersteller keine Scan-Ergebnisse. Testweise einschalten.");
            }
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            log("Bildschirm an: " + yn(pm.isInteractive()) + ", Energiesparmodus: " + yn(pm.isPowerSaveMode()));
            if (Build.VERSION.SDK_INT >= 23) {
                log("Akku-Optimierung für diese App ignoriert: " + yn(pm.isIgnoringBatteryOptimizations(getPackageName())));
            }
            if (pm.isPowerSaveMode()) {
                finding("HINWEIS", "Energiesparmodus ist aktiv. Er kann BLE-Scans drosseln.");
            }
        }

        if (!enabled) {
            finding("FEHLER", "Bluetooth ist ausgeschaltet. Scans liefern nichts. Einschalten und Test wiederholen.");
            return false;
        }
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        log("getBluetoothLeScanner(): " + (sc != null ? "vorhanden" : "NULL"));
        if (sc == null) {
            finding("FEHLER", "getBluetoothLeScanner() liefert null (Bluetooth startet evtl. gerade neu).");
            return false;
        }
        return true;
    }

    private boolean isLocationOn() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                return lm != null && lm.isLocationEnabled();
            }
            int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            log("Standortstatus nicht lesbar: " + e);
            return true;
        }
    }

    private void listConnectedAndBonded() {
        try {
            List<BluetoothDevice> con = btManager.getConnectedDevices(BluetoothProfile.GATT);
            log("Aktuell per BLE (GATT) mit dem Handy verbunden: " + con.size());
            for (BluetoothDevice d : con) {
                String n = safeName(d);
                log("  " + d.getAddress() + "  " + quote(n));
                if (looksLikeCooler(n)) {
                    finding("WARNUNG", "Der Kühler " + quote(n) + " [" + d.getAddress() + "] ist gerade mit diesem "
                            + "Handy verbunden (evtl. durch eine andere App). Solange er verbunden ist, sendet er "
                            + "keine Werbung und taucht in keinem Scan auf. Andere App schließen oder Bluetooth kurz aus/an.");
                }
            }
        } catch (SecurityException e) {
            log("Verbundene Geräte nicht lesbar: " + e.getMessage());
        }
        try {
            int coolerBonded = 0;
            int total = 0;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                total++;
                String n = safeName(d);
                if (looksLikeCooler(n)) {
                    coolerBonded++;
                    log("  gekoppelt: " + d.getAddress() + "  " + quote(n));
                }
            }
            log("Gekoppelte Geräte gesamt: " + total + ", davon Kühler: " + coolerBonded);
        } catch (SecurityException e) {
            log("Gekoppelte Geräte nicht lesbar: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Scans

    private void startScan(final ScanRun run, List<ScanFilter> filters, String filterDesc, long ms, final Runnable next) {
        section(run.title);
        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) {
            run.error = "getBluetoothLeScanner() == null";
            log("Kein Scanner: " + run.error);
            main.post(next);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        Iterator<Long> it = scanStarts.iterator();
        while (it.hasNext()) {
            if (now - it.next() > 30000) it.remove();
        }
        if (scanStarts.size() >= 5) {
            log("ACHTUNG: schon " + scanStarts.size() + " Scan-Starts in den letzten 30 s, Android drosselt jetzt evtl.");
            finding("WARNUNG", "Test zu schnell wiederholt: mehr als 5 Scan-Starts in 30 s werden von Android "
                    + "still blockiert. 30 s warten und neu testen.");
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        log("startScan(filter=" + filterDesc + ", mode=LOW_LATENCY, dauer=" + (ms / 1000) + " s)");
        try {
            sc.startScan(filters, settings, run);
            scanStarts.add(now);
            run.startedAt = now;
        } catch (SecurityException e) {
            run.error = "SecurityException: " + e.getMessage();
            log("startScan fehlgeschlagen: " + run.error);
            main.post(next);
            return;
        } catch (Exception e) {
            run.error = e.toString();
            log("startScan fehlgeschlagen: " + run.error);
            main.post(next);
            return;
        }

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sc.stopScan(run);
                } catch (Exception e) {
                    log("stopScan: " + e);
                }
                logScanRun(run);
                next.run();
            }
        }, ms);
    }

    private void logScanRun(ScanRun run) {
        log("Scan beendet: Rohergebnisse=" + run.raw + ", verschiedene Geräte=" + run.devs.size()
                + (run.failCode != null ? ", onScanFailed=" + run.failCode + " (" + scanError(run.failCode) + ")" : ""));
        List<Dev> list = new ArrayList<Dev>(run.devs.values());
        Collections.sort(list, new Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                if (a.cooler != b.cooler) return a.cooler ? -1 : 1;
                return b.bestRssi - a.bestRssi;
            }
        });
        int shown = 0;
        for (Dev d : list) {
            if (shown >= MAX_LISTED_DEVICES) {
                log("  … und " + (list.size() - shown) + " weitere Geräte");
                break;
            }
            log("  " + d.describe());
            shown++;
        }
    }

    private void evaluateScanAll() {
        ScanRun r = scanAll;
        if (r.error != null) {
            finding("FEHLER", "Scan ohne Filter konnte nicht gestartet werden: " + r.error);
            return;
        }
        if (r.failCode != null) {
            finding("FEHLER", "Scan ohne Filter: onScanFailed(" + r.failCode + ") = " + scanError(r.failCode)
                    + ". " + scanErrorHint(r.failCode));
            return;
        }
        if (r.raw == 0) {
            finding("FEHLER", "Scan ohne Filter lief " + (SCAN_ALL_MS / 1000) + " s und lieferte 0 Ergebnisse, "
                    + "nicht einmal fremde Geräte. Ursache liegt bei Berechtigungen, Standort, Drosselung oder "
                    + "dem Bluetooth-Stack, NICHT beim Kühler.");
            return;
        }
        for (Dev d : r.devs.values()) {
            if (d.cooler && (cooler == null || d.bestRssi > cooler.bestRssi)) cooler = d;
        }
        if (cooler == null) {
            finding("WARNUNG", "Scan funktioniert (" + r.devs.size() + " Geräte), aber kein Kühler dabei. Kühler an? "
                    + "In Reichweite? Schon mit einem anderen Handy / einer anderen App verbunden?");
            return;
        }
        finding("OK", "Kühler gefunden: " + quote(cooler.bestName()) + " [" + cooler.mac + "], "
                + cooler.bestRssi + " dBm, erstes Paket nach "
                + String.format(Locale.ROOT, "%.1f", cooler.firstSeenMs / 1000.0) + " s.");
        String nameSrc;
        if (cooler.advName != null && cooler.sysName != null) nameSrc = "im Werbepaket UND per device.getName()";
        else if (cooler.advName != null) nameSrc = "nur im Werbepaket (scanRecord.getDeviceName())";
        else nameSrc = "nur per device.getName() (aus dem Cache)";
        finding("INFO", "Name des Kühlers kommt " + nameSrc + ".");
        if (cooler.uuids.isEmpty()) {
            finding("INFO", "Der Kühler sendet KEINE Service-UUIDs in seiner Werbung.");
        } else {
            finding("INFO", "Beworbene Service-UUIDs des Kühlers: " + cooler.uuids);
        }
    }

    private void evaluateFilterScan(ScanRun r, String what) {
        if (r.error != null) {
            finding("WARNUNG", what + ": Scan-Start fehlgeschlagen: " + r.error);
            return;
        }
        if (r.failCode != null) {
            finding("WARNUNG", what + ": onScanFailed(" + r.failCode + ") = " + scanError(r.failCode));
            return;
        }
        boolean hit = false;
        for (Dev d : r.devs.values()) {
            if (d.cooler) hit = true;
        }
        if (hit) {
            finding("OK", what + " findet den Kühler.");
        } else if (cooler != null) {
            finding("HINWEIS", what + " findet den Kühler NICHT, obwohl er ohne Filter sichtbar war. "
                    + "Diesen Filter nicht als einzigen Suchweg verwenden. Ohne Filter scannen und in Software "
                    + "auf den Namen prüfen.");
        } else {
            finding("INFO", what + ": 0 Treffer (Kühler war auch ohne Filter nicht sichtbar).");
        }
    }

    private class ScanRun extends ScanCallback {
        final String title;
        final Map<String, Dev> devs = new LinkedHashMap<String, Dev>();
        int raw;
        Integer failCode;
        String error;
        long startedAt;

        ScanRun(String title) {
            this.title = title;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            failCode = errorCode;
            log("onScanFailed(" + errorCode + ") = " + scanError(errorCode));
        }

        private void handle(ScanResult r) {
            raw++;
            BluetoothDevice device = r.getDevice();
            String mac = device.getAddress();
            Dev d = devs.get(mac);
            if (d == null) {
                d = new Dev(mac);
                d.firstSeenMs = SystemClock.elapsedRealtime() - startedAt;
                devs.put(mac, d);
                if (devs.size() == 1) {
                    log("Erstes Scan-Ergebnis nach " + String.format(Locale.ROOT, "%.1f", d.firstSeenMs / 1000.0) + " s");
                }
            }
            d.count++;
            d.lastRssi = r.getRssi();
            if (r.getRssi() > d.bestRssi) d.bestRssi = r.getRssi();
            if (Build.VERSION.SDK_INT >= 26) d.connectable = r.isConnectable();
            ScanRecord rec = r.getScanRecord();
            if (rec != null) {
                if (rec.getDeviceName() != null) d.advName = rec.getDeviceName();
                List<ParcelUuid> uu = rec.getServiceUuids();
                if (uu != null) {
                    for (ParcelUuid p : uu) {
                        String s = p.getUuid().toString();
                        if (!d.uuids.contains(s)) d.uuids.add(s);
                    }
                }
                SparseArray<byte[]> m = rec.getManufacturerSpecificData();
                if (m != null && m.size() > 0) {
                    d.mfr = String.format(Locale.ROOT, "0x%04X:%s", m.keyAt(0), hex(m.valueAt(0)));
                }
            }
            if (d.sysName == null) d.sysName = safeName(device);
            if (!d.cooler && (looksLikeCooler(d.advName) || looksLikeCooler(d.sysName)
                    || d.uuids.contains(COOLER_SERVICE.toString()))) {
                d.cooler = true;
                log("*** KÜHLER-KANDIDAT: " + d.describe());
            }
        }
    }

    private static class Dev {
        final String mac;
        String advName;
        String sysName;
        int count;
        int lastRssi;
        int bestRssi = -999;
        long firstSeenMs;
        Boolean connectable;
        String mfr;
        boolean cooler;
        final List<String> uuids = new ArrayList<String>();

        Dev(String mac) {
            this.mac = mac;
        }

        String bestName() {
            if (advName != null) return advName;
            return sysName;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%s %4d dBm %3dx", mac, bestRssi, count));
            if (advName != null) sb.append("  adv=").append(quote(advName));
            if (sysName != null && !sysName.equals(advName)) sb.append("  sys=").append(quote(sysName));
            if (advName == null && sysName == null) sb.append("  (ohne Namen)");
            if (!uuids.isEmpty()) sb.append("  uuids=").append(uuids);
            if (mfr != null) sb.append("  mfr=").append(mfr);
            if (connectable != null && !connectable) sb.append("  nicht-verbindbar");
            if (cooler) sb.append("  <== KÜHLER");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ Verbindungstest

    private void startConnectTest() {
        section("VERBINDUNGSTEST (nur lesen)");
        BluetoothDevice dev = adapter.getRemoteDevice(cooler.mac);
        log("connectGatt(autoConnect=false, TRANSPORT_LE) zu " + cooler.mac);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = dev.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException e) {
            log("connectGatt: SecurityException " + e.getMessage());
            finding("FEHLER", "Verbinden nicht erlaubt (BLUETOOTH_CONNECT fehlt).");
            finishTest();
            return;
        }
        if (gatt == null) {
            log("connectGatt() lieferte null");
            finding("FEHLER", "connectGatt() lieferte null.");
            finishTest();
            return;
        }
        connectTimeout = new Runnable() {
            @Override
            public void run() {
                log("Timeout: nach " + (CONNECT_TIMEOUT_MS / 1000) + " s keine Verbindung / keine Services");
                finding("FEHLER", "Verbindungstest: Timeout nach " + (CONNECT_TIMEOUT_MS / 1000) + " s.");
                endConnectTest();
            }
        };
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    log("onConnectionStateChange status=" + status + " (" + gattStatus(status) + ") newState="
                            + connState(newState));
                    if (connectDone) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gattConnected = true;
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (connectDone || gatt == null) return;
                                log("discoverServices()");
                                try {
                                    gatt.discoverServices();
                                } catch (SecurityException e) {
                                    log("discoverServices: " + e.getMessage());
                                }
                            }
                        }, 600);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        String hint = status == 133 ? " Status 133 = allgemeiner GATT-Fehler, oft weil noch eine "
                                + "alte Verbindung offen ist (gatt.close() vergessen) oder der Kühler belegt ist." : "";
                        finding("FEHLER", "Verbindung unerwartet getrennt, status=" + status + " (" + gattStatus(status) + ")." + hint);
                        endConnectTest();
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (connectDone) return;
                    main.removeCallbacks(connectTimeout);
                    log("onServicesDiscovered status=" + status + " (" + gattStatus(status) + ")");
                    onServices(g);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            onRead(c, c.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            onRead(c, value, status);
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, final BluetoothGattDescriptor d, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    log("  CCCD geschrieben für " + shortUuid(d.getCharacteristic().getUuid()) + " status=" + status);
                    nextGattOp();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onNotify(c, c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(c, value);
        }
    };

    private void onRead(final BluetoothGattCharacteristic c, final byte[] value, final int status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                String v = value == null ? "null" : quote(new String(value, Charset.forName("UTF-8")).trim()) + "  hex=" + hex(value);
                log("  Lesen " + shortUuid(c.getUuid()) + " " + charName(c.getUuid()) + " status=" + status + " -> " + v);
                nextGattOp();
            }
        });
    }

    private void onNotify(final BluetoothGattCharacteristic c, final byte[] value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                notifyCount++;
                String key = shortUuid(c.getUuid());
                Integer n = notifyPerChar.get(key);
                n = n == null ? 1 : n + 1;
                notifyPerChar.put(key, n);
                if (n <= 3) log("  NOTIFY " + key + ": " + hex(value));
            }
        });
    }

    private void onServices(BluetoothGatt g) {
        List<BluetoothGattService> services = g.getServices();
        log("Services: " + services.size());
        for (BluetoothGattService s : services) {
            boolean isCooler = COOLER_SERVICE.equals(s.getUuid());
            if (isCooler) coolerServiceSeen = true;
            log("  SERVICE " + s.getUuid() + (isCooler ? "  <== Kühler-Service" : ""));
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                log("    CHAR " + shortUuid(c.getUuid()) + "  " + props(c.getProperties())
                        + (c.getDescriptor(CCCD) != null ? "  [CCCD]" : ""));
            }
        }
        if (coolerServiceSeen) {
            finding("OK", "Verbindung und Service-Discovery klappen, Kühler-Service " + COOLER_SERVICE + " vorhanden.");
        } else {
            finding("WARNUNG", "Verbunden, aber Kühler-Service " + COOLER_SERVICE + " nicht gefunden.");
        }

        gattOps.clear();
        String[] infoChars = {"2a29", "2a24", "2a26", "2a28", "2a27"};
        for (String sc : infoChars) {
            final BluetoothGattCharacteristic c = findChar(g, sc);
            if (c == null) continue;
            gattOps.add(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!gatt.readCharacteristic(c)) {
                            log("  readCharacteristic(" + shortUuid(c.getUuid()) + ") = false");
                            nextGattOp();
                        }
                    } catch (SecurityException e) {
                        log("  read: " + e.getMessage());
                        nextGattOp();
                    }
                }
            });
        }
        String[] notifyChars = {"1015", "1016"};
        for (String sc : notifyChars) {
            final BluetoothGattCharacteristic c = findChar(g, sc);
            if (c == null) continue;
            gattOps.add(new Runnable() {
                @Override
                public void run() {
                    enableNotify(c);
                }
            });
        }
        gattOps.add(new Runnable() {
            @Override
            public void run() {
                log("Warte " + (NOTIFY_LISTEN_MS / 1000) + " s auf Notifications …");
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        log("Notifications empfangen: " + notifyCount + " " + notifyPerChar);
                        if (notifyCount > 0) {
                            finding("OK", "Kühler sendet Status-Notifications (" + notifyCount + " in "
                                    + (NOTIFY_LISTEN_MS / 1000) + " s).");
                        } else {
                            finding("WARNUNG", "Keine Notifications vom Kühler empfangen.");
                        }
                        endConnectTest();
                    }
                }, NOTIFY_LISTEN_MS);
            }
        });
        nextGattOp();
    }

    private void enableNotify(BluetoothGattCharacteristic c) {
        try {
            boolean ok = gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) {
                log("  Notify " + shortUuid(c.getUuid()) + ": setCharacteristicNotification=" + ok + ", kein CCCD");
                nextGattOp();
                return;
            }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(d)) {
                log("  writeDescriptor(" + shortUuid(c.getUuid()) + ") = false");
                nextGattOp();
            }
        } catch (SecurityException e) {
            log("  notify: " + e.getMessage());
            nextGattOp();
        }
    }

    private void nextGattOp() {
        if (gattOpTimeout != null) main.removeCallbacks(gattOpTimeout);
        if (connectDone || gatt == null || gattOps.isEmpty()) return;
        Runnable op = gattOps.remove(0);
        gattOpTimeout = new Runnable() {
            @Override
            public void run() {
                log("  keine Antwort nach " + (GATT_OP_TIMEOUT_MS / 1000) + " s, weiter");
                nextGattOp();
            }
        };
        if (!gattOps.isEmpty()) main.postDelayed(gattOpTimeout, GATT_OP_TIMEOUT_MS);
        op.run();
    }

    private void endConnectTest() {
        if (connectDone) return;
        connectDone = true;
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        if (gattOpTimeout != null) main.removeCallbacks(gattOpTimeout);
        gattOps.clear();
        if (!gattConnected) log("Es kam keine Verbindung zustande.");
        closeGatt();
        log("Verbindung getrennt und mit gatt.close() freigegeben.");
        finishTest();
    }

    private void closeGatt() {
        if (gatt == null) return;
        try {
            gatt.disconnect();
            gatt.close();
        } catch (SecurityException ignored) {
        }
        gatt = null;
    }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, String short16) {
        UUID u = UUID.fromString("0000" + short16 + "-0000-1000-8000-00805f9b34fb");
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(u);
            if (c != null) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ Abschluss

    private void finishTest() {
        section("ENDE");
        log("Test beendet.");

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String fileName = "ble-diagnose-" + stamp + ".txt";

        StringBuilder sb = new StringBuilder();
        sb.append("===== BLE-DIAGNOSE RedMagic-Kühler =====\n");
        sb.append("Erstellt: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).append('\n');
        sb.append("Gerät:    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("App:      BLE-Diagnose ").append(APP_VERSION).append('\n');
        sb.append("\n===== ZUSAMMENFASSUNG =====\n");
        sb.append(scanLine("Scan ohne Filter      ", scanAll));
        sb.append(scanLine("Filter Service-UUID   ", scanUuid));
        sb.append(scanLine("Filter Gerätename     ", scanName));
        if (findings.isEmpty()) sb.append("Keine Auffälligkeiten.\n");
        for (String f : findings) sb.append("• ").append(f).append('\n');
        sb.append("\n===== DETAILS =====");
        synchronized (this) {
            sb.append(details);
        }
        report = sb.toString();

        saveReport(fileName);
        if (savedPath != null) report = report.replaceFirst("\n\n===== ZUSAMMENFASSUNG", "\nDatei:    " + savedPath + "\n\n===== ZUSAMMENFASSUNG");

        running = false;
        txt.setText(report);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_UP);
            }
        });
        btnStart.setEnabled(true);
        btnShare.setEnabled(true);
        btnCopy.setEnabled(true);
    }

    private String scanLine(String label, ScanRun r) {
        if (r == null) return label + ": nicht ausgeführt\n";
        if (r.error != null) return label + ": FEHLER " + r.error + "\n";
        String fail = r.failCode != null ? ", onScanFailed=" + r.failCode + " " + scanError(r.failCode) : "";
        int coolers = 0;
        for (Dev d : r.devs.values()) if (d.cooler) coolers++;
        return label + ": " + r.raw + " Ergebnisse, " + r.devs.size() + " Geräte, Kühler: "
                + (coolers > 0 ? "JA" : "nein") + fail + "\n";
    }

    private void saveReport(String fileName) {
        byte[] data = report.getBytes(Charset.forName("UTF-8"));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("MediaStore insert == null");
                OutputStream os = getContentResolver().openOutputStream(uri);
                try {
                    os.write(data);
                } finally {
                    os.close();
                }
                savedUri = uri;
                savedPath = "Download/" + fileName;
            } else {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(data);
                } finally {
                    fos.close();
                }
                savedPath = f.getAbsolutePath();
            }
        } catch (Exception e) {
            savedPath = null;
            Toast.makeText(this, "Speichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "BLE-Diagnose " + Build.MANUFACTURER + " " + Build.MODEL);
        if (savedUri != null) {
            // Datei anhängen, damit lange Logs nicht am Nachrichtenlimit (z. B. Discord 2000 Zeichen) scheitern.
            send.putExtra(Intent.EXTRA_STREAM, savedUri);
            send.setClipData(ClipData.newRawUri("log", savedUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.putExtra(Intent.EXTRA_TEXT, report);
        }
        startActivity(Intent.createChooser(send, "Log teilen"));
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("BLE-Diagnose", report));
        Toast.makeText(this, "Log kopiert (" + report.length() + " Zeichen)", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ Helfer

    private String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean looksLikeCooler(String name) {
        if (name == null) return false;
        String u = name.toUpperCase(Locale.ROOT);
        for (String h : NAME_HINTS) {
            if (u.contains(h)) return true;
        }
        return false;
    }

    private static String quote(String s) {
        return s == null ? "(null)" : "\"" + s + "\"";
    }

    private static String yn(boolean b) {
        return b ? "ja" : "NEIN";
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", b[i]));
        }
        return sb.toString();
    }

    private static String shortUuid(UUID u) {
        String s = u.toString();
        if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) return "0x" + s.substring(4, 8);
        return s;
    }

    private static String charName(UUID u) {
        String s = shortUuid(u);
        if (s.equals("0x2a29")) return "(Hersteller)";
        if (s.equals("0x2a24")) return "(Modell)";
        if (s.equals("0x2a26")) return "(Firmware)";
        if (s.equals("0x2a28")) return "(Software)";
        if (s.equals("0x2a27")) return "(Hardware)";
        return "";
    }

    private static String props(int p) {
        StringBuilder sb = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("READ ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("WRITE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("WRITE_NR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("NOTIFY ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("INDICATE ");
        return sb.toString().trim();
    }

    private static String shortPerm(String p) {
        return p.substring(p.lastIndexOf('.') + 1);
    }

    private static String shortPerms(List<String> ps) {
        StringBuilder sb = new StringBuilder();
        for (String p : ps) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(shortPerm(p));
        }
        return sb.toString();
    }

    private static String adapterState(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_OFF: return "OFF";
            case BluetoothAdapter.STATE_TURNING_ON: return "TURNING_ON";
            case BluetoothAdapter.STATE_ON: return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF: return "TURNING_OFF";
            default: return String.valueOf(s);
        }
    }

    private static String connState(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED: return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING: return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return String.valueOf(s);
        }
    }

    private static String gattStatus(int s) {
        switch (s) {
            case 0: return "SUCCESS";
            case 5: return "INSUFFICIENT_AUTHENTICATION";
            case 8: return "CONNECTION_TIMEOUT";
            case 19: return "REMOTE_USER_TERMINATED";
            case 22: return "LOCAL_HOST_TERMINATED";
            case 62: return "FAILED_TO_ESTABLISH";
            case 133: return "GATT_ERROR";
            case 257: return "GATT_FAILURE";
            default: return "unbekannt";
        }
    }

    private static String scanError(int code) {
        switch (code) {
            case 1: return "SCAN_FAILED_ALREADY_STARTED";
            case 2: return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
            case 3: return "SCAN_FAILED_INTERNAL_ERROR";
            case 4: return "SCAN_FAILED_FEATURE_UNSUPPORTED";
            case 5: return "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES";
            case 6: return "SCAN_FAILED_SCANNING_TOO_FREQUENTLY";
            default: return "unbekannt";
        }
    }

    private static String scanErrorHint(int code) {
        switch (code) {
            case 1: return "Mit demselben Callback läuft schon ein Scan, erst stopScan() aufrufen.";
            case 2: return "Zu viele gleichzeitige Scanner in der App oder Bluetooth-Stack hängt. Alte Scans "
                    + "stoppen, sonst Bluetooth aus/an.";
            case 6: return "Mehr als 5 startScan() in 30 s. Seltener scannen.";
            default: return "";
        }
    }
}
