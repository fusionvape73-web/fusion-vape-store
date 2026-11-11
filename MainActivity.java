package com.fusionvapestore.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int REQ_BT_CONNECT = 501;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private String pendingPrintText;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0A9F9A);
        getWindow().setNavigationBarColor(0xFF0A9F9A);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        webView.addJavascriptInterface(new PrinterBridge(), "AndroidPrinter");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");
        requestBluetoothPermission();
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
        }
    }

    private class PrinterBridge {
        @JavascriptInterface public void printText(String text) {
            runOnUiThread(() -> {
                pendingPrintText = text;
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestBluetoothPermission();
                    Toast.makeText(MainActivity.this, "Izinkan akses Bluetooth, lalu tekan Cetak Thermal lagi.", Toast.LENGTH_LONG).show();
                    return;
                }
                choosePrinterAndPrint(text);
            });
        }
    }

    private void choosePrinterAndPrint(String text) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { toast("HP ini tidak mendukung Bluetooth."); return; }
        if (!adapter.isEnabled()) { toast("Aktifkan Bluetooth terlebih dahulu."); return; }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            ArrayList<BluetoothDevice> printers = new ArrayList<>();
            ArrayList<String> labels = new ArrayList<>();
            for (BluetoothDevice d : bonded) { printers.add(d); labels.add(d.getName() + "\n" + d.getAddress()); }
            if (printers.isEmpty()) { toast("Pairing printer thermal lewat Pengaturan Bluetooth HP terlebih dahulu."); return; }
            new AlertDialog.Builder(this).setTitle("Pilih printer thermal")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> sendToPrinter(printers.get(which), text))
                .setNegativeButton("Batal", null).show();
        } catch (SecurityException e) { toast("Akses Bluetooth belum diizinkan. Tutup dan buka kembali aplikasi."); }
    }

    private void sendToPrinter(BluetoothDevice device, String text) {
        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                OutputStream out = socket.getOutputStream();
                out.write(new byte[]{0x1B, 0x40}); // initialize ESC/POS
                out.write(new byte[]{0x1B, 0x61, 0x00}); // left align
                out.write(text.getBytes(Charset.forName("GBK")));
                out.write(new byte[]{0x0A,0x0A,0x0A});
                out.write(new byte[]{0x1D, 0x56, 0x00}); // full cut (if supported)
                out.flush();
                runOnUiThread(() -> toast("Struk berhasil dikirim ke printer."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Gagal mencetak. Pastikan printer menyala, sudah pairing, dan mendukung ESC/POS Bluetooth."));
            } finally { try { if (socket != null) socket.close(); } catch (Exception ignored) {} }
        }).start();
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_CONNECT && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingPrintText != null) {
            String text = pendingPrintText; pendingPrintText = null; choosePrinterAndPrint(text);
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() { if (webView != null) webView.destroy(); super.onDestroy(); }
}
