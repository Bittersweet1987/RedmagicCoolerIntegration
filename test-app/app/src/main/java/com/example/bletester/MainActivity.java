package com.example.bletester;

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
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String[] NAME_FILTERS = {"MAGCOOLER", "REDMAGIC", "COOLER"};

    /** Kuehlstufen 1-9 wie von Goper gesendet (per HCI-Snoop bestaetigt, nicht linear). */
    private static final byte[] FAN_LEVELS = {
            0x28, 0x2E, 0x34, 0x3A, 0x40, 0x44, 0x48, 0x4C, 0x50
    };

    private TextView txtLog;
    private TextView txtStatus;
    private Spinner spinnerChar;
    private EditText editHex;
    private android.widget.SeekBar seekFan;
    private TextView txtFanValue;
    private TextView txtTemperature;
    private EditText editMac;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;

    private final List<BluetoothGattCharacteristic> writableChars = new ArrayList<>();
    private final Map<UUID, BluetoothGattCharacteristic> allChars = new HashMap<>();
    private final ArrayDeque<Runnable> gattQueue = new ArrayDeque<>();
    private boolean gattBusy = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);
    private Runnable pendingAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtLog = findViewById(R.id.txtLog);
        txtStatus = findViewById(R.id.txtStatus);
        txtTemperature = findViewById(R.id.txtTemperature);
        spinnerChar = findViewById(R.id.spinnerChar);
        editHex = findViewById(R.id.editHex);

        Button btnScanConnect = findViewById(R.id.btnScanConnect);
        Button btnDirectConnect = findViewById(R.id.btnDirectConnect);
        editMac = findViewById(R.id.editMac);
        Button btnLedOn = findViewById(R.id.btnLedOn);
        Button btnLedOff = findViewById(R.id.btnLedOff);
        Button btnCoolOn = findViewById(R.id.btnCoolOn);
        Button btnCoolOff = findViewById(R.id.btnCoolOff);
        Button btnFanSend = findViewById(R.id.btnFanSend);
        seekFan = findViewById(R.id.seekFan);
        txtFanValue = findViewById(R.id.txtFanValue);
        Button btnDiabloOn = findViewById(R.id.btnDiabloOn);
        Button btnDiabloOff = findViewById(R.id.btnDiabloOff);
        Button btnAutoTempOn = findViewById(R.id.btnAutoTempOn);
        Button btnAutoTempOff = findViewById(R.id.btnAutoTempOff);
        Button btnSendHex = findViewById(R.id.btnSendHex);
        Button btnShare = findViewById(R.id.btnShare);
        Button btnClear = findViewById(R.id.btnClear);

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager.getAdapter();

        btnScanConnect.setOnClickListener(v -> requestPermissionsThen(this::startScan));
        btnDirectConnect.setOnClickListener(v -> requestPermissionsThen(this::connectDirect));
        btnLedOn.setOnClickListener(v -> {
            log("LED AN (0x1013 = 01 00 00 00)");
            sendToUuid(0x1013, new byte[]{0x01, 0x00, 0x00, 0x00});
        });
        btnLedOff.setOnClickListener(v -> {
            log("LED AUS (0x1013 = 06 00 00 00)");
            sendToUuid(0x1013, new byte[]{0x06, 0x00, 0x00, 0x00});
        });
        btnCoolOn.setOnClickListener(v -> sendToUuid(0x1011, new byte[]{0x02}));
        btnCoolOff.setOnClickListener(v -> sendToUuid(0x1011, new byte[]{0x03}));
        int[] stufeIds = {R.id.btnStufe1, R.id.btnStufe2, R.id.btnStufe3, R.id.btnStufe4, R.id.btnStufe5,
                R.id.btnStufe6, R.id.btnStufe7, R.id.btnStufe8, R.id.btnStufe9};
        for (int i = 0; i < stufeIds.length; i++) {
            final int stufe = i + 1;
            final byte value = FAN_LEVELS[i];
            findViewById(stufeIds[i]).setOnClickListener(v -> {
                log(String.format(Locale.ROOT, "Kuehlstufe %d -> 0x%02X (%d)", stufe, value, value & 0xFF));
                sendToUuid(0x1012, new byte[]{value});
            });
        }
        btnFanSend.setOnClickListener(v -> sendToUuid(0x1012, new byte[]{(byte) seekFan.getProgress()}));
        seekFan.setProgress(0x28);
        txtFanValue.setText("0x28 (40)");
        seekFan.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                txtFanValue.setText(String.format(Locale.ROOT, "0x%02X (%d)", progress, progress));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        btnDiabloOn.setOnClickListener(v -> sendToUuid(0x1017, new byte[]{0x01}));
        btnDiabloOff.setOnClickListener(v -> sendToUuid(0x1017, new byte[]{0x00}));
        btnAutoTempOn.setOnClickListener(v -> sendToUuid(0x1018, new byte[]{0x01}));
        btnAutoTempOff.setOnClickListener(v -> sendToUuid(0x1018, new byte[]{0x00}));
        btnSendHex.setOnClickListener(v -> sendRawHex(editHex.getText().toString()));
        btnShare.setOnClickListener(v -> copyLogToClipboard());
        btnClear.setOnClickListener(v -> txtLog.setText(""));

        log("App gestartet. Bluetooth verfügbar: " + (bluetoothAdapter != null));
    }

    // ---------- Permissions ----------

    private void requestPermissionsThen(Runnable action) {
        List<String> needed = new ArrayList<>();
        String[] perms;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        log("Benötigte Permissions: " + TextUtils.join(",", perms) + "  fehlend: " + needed);
        if (!needed.isEmpty()) {
            pendingAfterPermission = action;
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1);
        } else {
            action.run();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        log("Permission-Ergebnis: allGranted=" + allGranted);
        if (allGranted && pendingAfterPermission != null) {
            pendingAfterPermission.run();
        } else if (!allGranted) {
            Toast.makeText(this, "Berechtigungen nötig für BLE", Toast.LENGTH_LONG).show();
        }
        pendingAfterPermission = null;
    }

    private void connectDirect() {
        if (bluetoothAdapter == null) {
            log("Kein Bluetooth-Adapter.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            log("Bluetooth ist aus. Bitte einschalten.");
            return;
        }
        String mac = editMac.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (TextUtils.isEmpty(mac)) {
            log("Keine MAC-Adresse eingegeben. Entweder MAC eintragen oder \"Scan + Verbinden\" nutzen.");
            return;
        }
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
            connectTo(device);
        } catch (IllegalArgumentException e) {
            log("Ungültige MAC-Adresse: " + e.getMessage());
        }
    }

    private boolean hasConnectPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ---------- Scan ----------

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            log("Bluetooth ist aus. Bitte einschalten.");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("Kein BLE-Scanner verfügbar.");
            return;
        }
        log("Scanne nach Kühler (Name enthält MAGCOOLER/REDMAGIC/COOLER)...");
        txtStatus.setText("Scanne...");
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            log("SecurityException beim Scan: " + e.getMessage());
            return;
        }
        mainHandler.postDelayed(this::stopScan, 15000);
    }

    private void stopScan() {
        if (scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = null;
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
            }
            if (name == null) name = "";
            log("Gefunden: " + name + " [" + device.getAddress() + "]");
            for (String filter : NAME_FILTERS) {
                if (name.toUpperCase(Locale.ROOT).contains(filter)) {
                    stopScan();
                    connectTo(device);
                    return;
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("Scan fehlgeschlagen, Code=" + errorCode);
        }
    };

    // ---------- Connect ----------

    private void connectTo(BluetoothDevice device) {
        log("Verbinde mit " + device.getAddress() + " ...");
        txtStatus.setText("Verbinde...");
        try {
            gatt = device.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            log("SecurityException beim Connect: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Verbunden. Entdecke Services...");
                mainHandler.post(() -> txtStatus.setText("Verbunden, entdecke Services..."));
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    log("SecurityException discoverServices: " + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Getrennt. status=" + status);
                mainHandler.post(() -> {
                    txtStatus.setText("Getrennt");
                    txtTemperature.setText("Temperatur: --");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("Services entdeckt, status=" + status);
            writableChars.clear();
            allChars.clear();
            List<String> spinnerLabels = new ArrayList<>();
            for (BluetoothGattService service : g.getServices()) {
                log("SERVICE " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int props = c.getProperties();
                    String propStr = propsToString(props);
                    log("  CHAR " + c.getUuid() + "  props=" + propStr);
                    allChars.put(c.getUuid(), c);

                    boolean writable = (props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                    if (writable) {
                        writableChars.add(c);
                        spinnerLabels.add(shortUuid(c.getUuid()) + "  (" + propStr + ")");
                    }

                    boolean notifiable = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (notifiable) {
                        enqueueEnableNotify(g, c);
                    }
                }
            }
            mainHandler.post(() -> {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_spinner_item, spinnerLabels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerChar.setAdapter(adapter);
                txtStatus.setText("Verbunden. " + writableChars.size() + " schreibbare Characteristics.");
            });
            processQueue();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            log("Notify aktiviert für " + descriptor.getCharacteristic().getUuid() + " status=" + status);
            gattBusy = false;
            processQueue();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            log("WRITE OK an " + characteristic.getUuid() + " status=" + status);
            gattBusy = false;
            processQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            log("NOTIFY von " + characteristic.getUuid() + " : " + bytesToHex(value));
            updateTemperatureIfPresent(characteristic.getUuid(), value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            log("READ von " + characteristic.getUuid() + " status=" + status + " : " + bytesToHex(characteristic.getValue()));
            updateTemperatureIfPresent(characteristic.getUuid(), characteristic.getValue());
            gattBusy = false;
            processQueue();
        }
    };

    private void enqueueEnableNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        gattQueue.add(() -> {
            try {
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(descriptor);
                } else {
                    log("Kein CCCD descriptor für " + c.getUuid());
                    gattBusy = false;
                    processQueue();
                }
            } catch (SecurityException e) {
                log("SecurityException enableNotify: " + e.getMessage());
                gattBusy = false;
                processQueue();
            }
        });
    }

    private void processQueue() {
        if (gattBusy) return;
        Runnable next = gattQueue.poll();
        if (next == null) return;
        gattBusy = true;
        next.run();
    }

    // ---------- Sending commands ----------

    private void sendFrame(int cmd1, int cmd2, int ack, byte[] payload) {
        int payloadLen = payload == null ? 0 : payload.length;
        byte[] frame = new byte[4 + payloadLen + 1];
        frame[0] = (byte) cmd1;
        frame[1] = (byte) cmd2;
        frame[2] = (byte) (5 + payloadLen);
        frame[3] = (byte) ack;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 4, payload.length);
        }
        int sum = 0;
        for (int i = 0; i < frame.length - 1; i++) {
            sum = (sum + (frame[i] & 0xFF)) & 0xFF;
        }
        frame[frame.length - 1] = (byte) sum;
        sendBytes(frame);
    }

    private void sendRawHex(String hex) {
        if (TextUtils.isEmpty(hex)) {
            log("Kein Hex eingegeben.");
            return;
        }
        String cleaned = hex.replaceAll("[^0-9a-fA-F]", "");
        if (cleaned.length() % 2 != 0) {
            log("Ungültige Hex-Länge.");
            return;
        }
        byte[] bytes = new byte[cleaned.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        sendBytes(bytes);
    }

    private void sendBytes(byte[] bytes) {
        if (gatt == null) {
            log("Nicht verbunden.");
            return;
        }
        int idx = spinnerChar.getSelectedItemPosition();
        if (idx < 0 || idx >= writableChars.size()) {
            log("Keine Characteristic ausgewählt.");
            return;
        }
        BluetoothGattCharacteristic c = writableChars.get(idx);
        log("SEND an " + c.getUuid() + " : " + bytesToHex(bytes));
        gattQueue.add(() -> {
            try {
                c.setValue(bytes);
                boolean ok = gatt.writeCharacteristic(c);
                if (!ok) {
                    log("writeCharacteristic() lieferte false");
                    gattBusy = false;
                    processQueue();
                }
            } catch (SecurityException e) {
                log("SecurityException write: " + e.getMessage());
                gattBusy = false;
                processQueue();
            }
        });
        processQueue();
    }

    /**
     * Temperatur kommt auf zwei Wegen rein:
     *  - 0x1015 als Notify im Format "04 TT" (TT = Grad Celsius), im Wechsel mit "05 00"
     *  - 0x1014 als einzelnes Byte beim direkten Read
     */
    private void updateTemperatureIfPresent(UUID uuid, byte[] value) {
        if (value == null || value.length == 0) return;
        Integer celsius = null;
        if (uuid.equals(shortToUuid(0x1015))) {
            if (value.length >= 2 && value[0] == 0x04) {
                celsius = value[1] & 0xFF;
            }
        } else if (uuid.equals(shortToUuid(0x1014))) {
            celsius = value[0] & 0xFF;
        }
        if (celsius == null) return;
        final int c = celsius;
        mainHandler.post(() -> txtTemperature.setText("Temperatur: " + c + " °C"));
    }

    private static UUID shortToUuid(int shortVal) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", shortVal));
    }

    private void sendToUuid(int shortVal, byte[] bytes) {
        if (gatt == null) {
            log("Nicht verbunden.");
            return;
        }
        BluetoothGattCharacteristic c = allChars.get(shortToUuid(shortVal));
        if (c == null) {
            log(String.format(Locale.ROOT, "Characteristic 0x%04x nicht gefunden (noch nicht verbunden?)", shortVal));
            return;
        }
        log("SEND an " + c.getUuid() + " : " + bytesToHex(bytes));
        gattQueue.add(() -> {
            try {
                c.setValue(bytes);
                boolean ok = gatt.writeCharacteristic(c);
                if (!ok) {
                    log("writeCharacteristic() lieferte false");
                    gattBusy = false;
                    processQueue();
                }
            } catch (SecurityException e) {
                log("SecurityException write: " + e.getMessage());
                gattBusy = false;
                processQueue();
            }
        });
        processQueue();
    }

    private void readUuid(int shortVal) {
        if (gatt == null) {
            log("Nicht verbunden.");
            return;
        }
        BluetoothGattCharacteristic c = allChars.get(shortToUuid(shortVal));
        if (c == null) {
            log(String.format(Locale.ROOT, "Characteristic 0x%04x nicht gefunden", shortVal));
            return;
        }
        gattQueue.add(() -> {
            try {
                boolean ok = gatt.readCharacteristic(c);
                if (!ok) {
                    log("readCharacteristic() lieferte false");
                    gattBusy = false;
                    processQueue();
                }
            } catch (SecurityException e) {
                log("SecurityException read: " + e.getMessage());
                gattBusy = false;
                processQueue();
            }
        });
        processQueue();
    }

    // ---------- Helpers ----------

    private String propsToString(int props) {
        StringBuilder sb = new StringBuilder();
        if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("READ ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("WRITE ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("WRITE_NR ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("NOTIFY ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("INDICATE ");
        return sb.toString().trim();
    }

    private String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.length() > 13 ? s.substring(0, 13) + "…" : s;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X ", b));
        }
        return sb.toString().trim();
    }

    private void log(String msg) {
        String line = timeFmt.format(new Date()) + "  " + msg;
        android.util.Log.d("BleTester", line);
        mainHandler.post(() -> {
            txtLog.append(line + "\n");
        });
    }

    private void copyLogToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("BLE Log", txtLog.getText().toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Log kopiert", Toast.LENGTH_SHORT).show();
    }
}
