package com.example.example04;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private List<WifiP2pDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private SocketServerThread serverThread;
    private SocketClientThread clientThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize WiFi Direct
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = wifiP2pManager.initialize(this, getMainLooper(), null);

        // Create an intent filter for broadcast receiver
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // Register the broadcast receiver
        receiver = new WiFiDirectBroadcastReceiver();
        registerReceiver(receiver, intentFilter);

        // Initialize UI components
        ListView listView = findViewById(R.id.listView);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);

        // Set item click listener
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                connectToDevice(position);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    // Broadcast receiver to handle Wi-Fi Direct events
    private class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                // Update the list of available peers
                wifiP2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                    @Override
                    public void onPeersAvailable(WifiP2pDeviceList peers) {
                        deviceList.clear();
                        deviceList.addAll(peers.getDeviceList());
                        updateDeviceList();
                    }
                });
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                // Respond to connection changes (connected or disconnected)
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    wifiP2pManager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                        @Override
                        public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            // Handle connection info (e.g., obtain group owner IP)
                            if (info.groupFormed && info.isGroupOwner) {
                                // This device is the group owner (server)
                                Log.d("WiFiDirect", "Connected as group owner");
                                // After connection is established, start text exchange
                                startTextExchange();
                            } else if (info.groupFormed) {
                                // This device is a client
                                Log.d("WiFiDirect", "Connected as client");
                            }
                        }
                    });
                }
            }
        }
    }

    // Update the device list in the UI
    private void updateDeviceList() {
        List<String> deviceNames = new ArrayList<>();
        for (WifiP2pDevice device : deviceList) {
            deviceNames.add(device.deviceName);
        }
        adapter.clear();
        adapter.addAll(deviceNames);
        adapter.notifyDataSetChanged();
    }

    // Initiate a connection to the selected device
    private void connectToDevice(int position) {
        final WifiP2pDevice device = deviceList.get(position);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // Connection initiation successful
                Log.d("WiFiDirect", "Connection initiation successful");
            }

            @Override
            public void onFailure(int reason) {
                // Connection initiation failed
                Log.d("WiFiDirect", "Connection initiation failed. Reason: " + reason);
            }
        });
    }

    // Start text exchange after connection is established
    private void startTextExchange() {
        serverThread = new SocketServerThread();
        serverThread.start();

        clientThread = new SocketClientThread(deviceList.get(0).deviceAddress);
        clientThread.start();
    }

    // Thread for the server socket (to receive messages)
    private class SocketServerThread extends Thread {
        private ServerSocket serverSocket;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(8888);
                Socket clientSocket = serverSocket.accept();

                // Read data from the input stream
                BufferedReader inputReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                while (true) {
                    String message = inputReader.readLine();
                    if (message != null) {
                        // Handle received message (e.g., update UI)
                        Log.d("WiFiDirect", "Received message: " + message);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Thread for the client socket (to send messages)
    private class SocketClientThread extends Thread {
        private String hostAddress;

        public SocketClientThread(String hostAddress) {
            this.hostAddress = hostAddress;
        }

        @Override
        public void run() {
            try {
                Socket socket = new Socket();
                socket.bind(null);
                socket.connect(new InetSocketAddress(hostAddress, 8888), 5000);

                // Write data to the output stream
                BufferedWriter outputWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                outputWriter.write("Hello, this is a test message!");
                outputWriter.newLine();
                outputWriter.flush();

                // Close the socket after sending the message
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
