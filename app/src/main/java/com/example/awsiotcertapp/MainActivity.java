// MainActivity.java
package com.example.awsiotcertapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.eclipse.paho.client.mqttv3.MqttException;

public class MainActivity extends AppCompatActivity implements MqttClientHandler.Listener {

    private Spinner spinnerRole;
    private Button buttonConnect, buttonDisconnect, buttonSubscribe, buttonPublish;
    private EditText editTextSubTopic, editTextPubTopic, editTextMessage;
    private TextView textViewLog;
    private ScrollView scrollLog;
    private MqttClientHandler mqtt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerRole      = findViewById(R.id.spinnerRole);
        buttonConnect    = findViewById(R.id.buttonConnect);
        buttonDisconnect = findViewById(R.id.buttonDisconnect);
        buttonSubscribe  = findViewById(R.id.buttonSubscribe);
        buttonPublish    = findViewById(R.id.buttonPublish);
        editTextSubTopic = findViewById(R.id.editTextSubTopic);
        editTextPubTopic = findViewById(R.id.editTextPubTopic);
        editTextMessage  = findViewById(R.id.editTextMessage);
        textViewLog      = findViewById(R.id.textViewLog);
        scrollLog        = findViewById(R.id.scrollLog);

        // Spinner role setup
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.roles_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRole.setAdapter(adapter);

        buttonConnect.setOnClickListener(v -> {
            String role = spinnerRole.getSelectedItem().toString();
            try {
                mqtt = new MqttClientHandler(getApplicationContext(), role, this);
                mqtt.connect();
            } catch (Exception e) {
                onLog("! INIT EX: " + e.getMessage());
                return;
            }
            boolean canSub = role.equals("Subscriber") || role.equals("Both");
            boolean canPub = role.equals("Publisher")  || role.equals("Both");

            editTextSubTopic.setEnabled(canSub);
            buttonSubscribe.setEnabled(canSub);
            editTextPubTopic.setEnabled(canPub);
            editTextMessage.setEnabled(canPub);
            buttonPublish.setEnabled(canPub);
        });

        buttonDisconnect.setOnClickListener(v -> {
            if (mqtt != null && mqtt.isConnected()) {
                try {
                    mqtt.disconnet();
                } catch (MqttException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Toast.makeText(getApplicationContext(), "Connect first!!", Toast.LENGTH_SHORT).show();
            }
        });

        buttonSubscribe.setOnClickListener(v -> {
            String topic = editTextSubTopic.getText().toString().trim();
            if (!topic.isEmpty()) mqtt.subscribe(topic);
        });

        buttonPublish.setOnClickListener(v -> {
            String topic = editTextPubTopic.getText().toString().trim();
            String msg   = editTextMessage.getText().toString();
            if (!topic.isEmpty() && !msg.isEmpty()) mqtt.publish(topic, msg);
        });

        // Handle incoming hex data from BluetoothConnection
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("received_data_hex")) {
            String hexData = intent.getStringExtra("received_data_hex");
            if (hexData != null) {
                spinnerRole.setSelection(2); // Set to "Both"
                editTextPubTopic.setText("bt/data");
                editTextMessage.setText(hexData);
                buttonConnect.performClick();
                new android.os.Handler().postDelayed(() -> buttonPublish.performClick(), 2000);
            }
        }
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> {
            textViewLog.append(line + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }
}
