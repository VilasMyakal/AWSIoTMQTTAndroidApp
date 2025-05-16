// BluetoothConnection.java
package com.example.awsiotcertapp;

import android.Manifest;
import android.bluetooth.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class BluetoothConnection extends AppCompatActivity {

    private static final int REQUEST_BLUETOOTH_CONNECT = 100;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int SEND_INTERVAL_MS = 1000;

    Button disconnectBluetoothBtn, listBluetoothDevicesBtn, connectBluetoothBtn;
    ListView listViewDevices;
    TextView resultTextView;

    ArrayList<String> deviceList = new ArrayList<>();
    ArrayAdapter<String> devicesnames;

    BluetoothAdapter bluetoothAdapter;
    BluetoothDevice selectedDevice;
    BluetoothSocket bluetoothSocket;
    OutputStream outputStream;
    InputStream inputStream;

    Handler handler = new Handler();
    volatile boolean ackReceived = false;
    int data_stat = 0;
    byte[] fullDataBuffer = new byte[814];
    Thread readThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_connection);

        disconnectBluetoothBtn = findViewById(R.id.disconnectBluetoothBtn);
        listBluetoothDevicesBtn = findViewById(R.id.listBluetoothDevicesBtn);
        connectBluetoothBtn = findViewById(R.id.connectBluetoothBtn);
        listViewDevices = findViewById(R.id.listViewDevices);
        resultTextView = findViewById(R.id.resultTextView);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        devicesnames = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        listViewDevices.setAdapter(devicesnames);

        listBluetoothDevicesBtn.setOnClickListener(v -> checkAndShowBondedDevices());
        listViewDevices.setOnItemClickListener((p, v, pos, id) -> selectDevice(
                pos));
        connectBluetoothBtn.setOnClickListener(v -> connectToSelectedDevice());
        disconnectBluetoothBtn.setOnClickListener(v -> disconnectBluetooth());
    }

    private void checkAndShowBondedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
        } else {
            showBondedDevices();
        }
    }

    private void showBondedDevices() {
        deviceList.clear();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            deviceList.add(device.getName() + " - " + device.getAddress());
        }
        devicesnames.notifyDataSetChanged();
    }

    private void selectDevice(int position) {
        String mac = deviceList.get(position).substring(deviceList.get(position).length() - 17);
        selectedDevice = bluetoothAdapter.getRemoteDevice(mac);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Toast.makeText(this, "Selected: " + selectedDevice.getName(), Toast.LENGTH_SHORT).show();
    }

    private void connectToSelectedDevice() {
        if (selectedDevice == null) return;

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                bluetoothSocket = selectedDevice.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();

                startReadThread();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
                    sendSetTimeCommand();
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void sendSetTimeCommand() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!ackReceived) {
                    Calendar c = Calendar.getInstance();
                    byte[] cmd = new byte[10];
                    cmd[0] = 0x50;
                    cmd[1] = 0x00;
                    cmd[2] = 0x07;
                    cmd[3] = (byte) (c.get(Calendar.YEAR));     // low byte of year
                    cmd[4] = (byte) (c.get(Calendar.MONTH) + 1); // month
                    cmd[5] = (byte) c.get(Calendar.DAY_OF_MONTH);
                    cmd[6] = (byte) c.get(Calendar.HOUR_OF_DAY);
                    cmd[7] = (byte) c.get(Calendar.MINUTE);
                    cmd[8] = (byte) c.get(Calendar.SECOND);
                    cmd[9] = 0x5F;
                    try {
                        outputStream.write(cmd);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    handler.postDelayed(this, SEND_INTERVAL_MS);
                }
            }
        }, SEND_INTERVAL_MS);
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            int bytes;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) handleIncomingData(Arrays.copyOf(buffer, bytes));
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        });
        readThread.start();
    }

    ByteArrayOutputStream dynamicDataBuffer = new ByteArrayOutputStream();

    private void handleIncomingData(byte[] data) {
        if (data.length >= 3 && data[0] == 0x50 && data[1] == 0x02 && data[data.length - 1] == 0x5F && !ackReceived) {
            ackReceived = true;
            runOnUiThread(() -> resultTextView.setText("✅ Time ACK received. Sending data request..."));
            sendUART("40014F"); // Request data
            data_stat = 1;
        }
        else if (data_stat == 1) {
            // Accumulate variable-length data starting with 0x40 and ending with 0x4F
            if (data[0] == 0x40) {
                dynamicDataBuffer.reset(); // start fresh
            }

            try {
                dynamicDataBuffer.write(data);
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (data[data.length - 1] == 0x4F) {
                data_stat = 2;
                sendUART("40024F"); // Ask for checksum
            }
        }
        else if (data_stat == 2 && data.length == 3 && data[0] == 0x40 && data[2] == 0x4F) {
            int receivedChecksum = data[1] & 0xFF;
            byte[] fullData = dynamicDataBuffer.toByteArray();

            int sum = 0;
            for (int i = 1; i < fullData.length - 1; i++) {
                sum += (fullData[i] & 0xFF);
            }

            if ((sum % 256) == receivedChecksum) {
                data_stat = 7;
                runOnUiThread(() -> {
                    resultTextView.setText("✅ Checksum matched. Sending to MainActivity...");
                    Log.d("BT", "Received " + dynamicDataBuffer.size() + " bytes total");
                    Toast.makeText(getApplicationContext(), bytesToHex(fullData),Toast.LENGTH_LONG).show();
//                    Intent intent = new Intent(this, MainActivity.class);
//                    intent.putExtra("received_data_hex", bytesToHex(fullData));
//                    startActivity(intent);
                });
            } else {
                data_stat = 5;
                sendUART("40044F"); // Resend data
                dynamicDataBuffer.reset();
            }
        }
    }

    private void sendUART(String hex) {
        try {
            outputStream.write(hexStringToBytes(hex));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private void disconnectBluetooth() {
        try {
            if (bluetoothSocket != null) bluetoothSocket.close();
            if (readThread != null) readThread.interrupt();
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQUEST_BLUETOOTH_CONNECT && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            showBondedDevices();
        }
    }
}
