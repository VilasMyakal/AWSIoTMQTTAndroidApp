package com.example.awsiotcertapp;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.UUID;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class MqttClientHandler {
    private static final String TAG      = "MqttClientHandler";
    private static final String ENDPOINT = "a2iyihwz3gxngo-ats.iot.ap-south-1.amazonaws.com";
    private static final int    PORT     = 8883;
    private static final String PROTO    = "ssl";

    private final Context           context;
    private final MqttAndroidClient client;
    private final String            role;
    private final Listener          ui;

    public interface Listener {
        void onLog(String line);
    }

    public MqttClientHandler(Context ctx, String role, Listener ui) throws Exception {
        this.context = ctx;
        this.role    = role;
        this.ui      = ui;

        String clientId = UUID.randomUUID().toString();
        String serverUri = PROTO + "://" + ENDPOINT + ":" + PORT;
        client = new MqttAndroidClient(context, serverUri, clientId);

        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {
                ui.onLog("** Connection lost: " + cause.getMessage());
            }
            @Override public void messageArrived(String topic, MqttMessage msg) {
                ui.onLog("[Recv][" + topic + "] " + new String(msg.getPayload()));
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {
                ui.onLog("** Delivery complete");
            }
        });
    }

    /**
     * Connect to AWS IoT Core.
     */
    public void connect() {
        try {
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setSocketFactory(getSocketFactory());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);

            client.connect(opts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    ui.onLog("** CONNECTED as " + role);
                    configureBuffer();
                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    ui.onLog("** CONNECT FAIL: " + exception.getMessage());
                }
            });
        } catch (Exception e) {
            ui.onLog("** CONNECT EX: " + e.getMessage());
        }
    }

    public void disconnet() throws MqttException {
        client.disconnect();
    }
    /**
     * Check if MQTT client is currently connected.
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    private void configureBuffer() {
        DisconnectedBufferOptions buf = new DisconnectedBufferOptions();
        buf.setBufferEnabled(true);
        buf.setBufferSize(100);
        buf.setPersistBuffer(false);
        buf.setDeleteOldestMessages(false);
        client.setBufferOpts(buf);
    }

    public void subscribe(String topic) {
        if (!isConnected()) {
            ui.onLog("! Can't subscribe, not connected.");
            return;
        }
        try {
            client.subscribe(topic, 1, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken asyncActionToken) {
                    ui.onLog("[Sub OK][" + topic + "]");
                }
                @Override public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    ui.onLog("[Sub FAIL][" + topic + "] " + exception.getMessage());
                }
            });
        } catch (MqttException e) {
            ui.onLog("[Sub EX][" + topic + "] " + e.getMessage());
        }
    }

    public void publish(String topic, String message) {
        if (!isConnected()) {
            ui.onLog("! Can't publish, not connected.");
            return;
        }
        try {
            MqttMessage m = new MqttMessage(message.getBytes());
            m.setQos(1);
            client.publish(topic, m, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken asyncActionToken) {
                    ui.onLog("[Pub OK][" + topic + "]");
                }
                @Override public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    ui.onLog("[Pub FAIL][" + topic + "] " + exception.getMessage());
                }
            });
        } catch (MqttException e) {
            ui.onLog("[Pub EX][" + topic + "] " + e.getMessage());
        }
    }

    private SocketFactory getSocketFactory() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        InputStream caIn = context.getResources().openRawResource(R.raw.root_ca);
        X509Certificate ca = (X509Certificate) cf.generateCertificate(caIn);
        caIn.close();

        InputStream certIn = context.getResources().openRawResource(R.raw.client_certificate);
        X509Certificate cert = (X509Certificate) cf.generateCertificate(certIn);
        certIn.close();

        InputStream keyIn = context.getResources().openRawResource(R.raw.private_key);
        byte[] keyBytes = new byte[keyIn.available()];
        keyIn.read(keyBytes);
        keyIn.close();
        String pem = new String(keyBytes)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\r|\\n", "");
        byte[] decoded = android.util.Base64.decode(pem, android.util.Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);

        KeyStore caKs = KeyStore.getInstance(KeyStore.getDefaultType());
        caKs.load(null);
        caKs.setCertificateEntry("ca-cert", ca);
        TrustManagerFactory tmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(caKs);

        KeyStore clKs = KeyStore.getInstance(KeyStore.getDefaultType());
        clKs.load(null);
        clKs.setCertificateEntry("client-cert", cert);
        clKs.setKeyEntry("priv-key", privateKey, "".toCharArray(), new java.security.cert.Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clKs, "".toCharArray());

        SSLContext ssl = SSLContext.getInstance("TLSv1.2");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ssl.getSocketFactory();
    }
}
