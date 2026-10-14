package net.mrphan.calibre;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Màn hình quét mã QR full-screen dùng cho luồng "Quét QR để nhập thư viện Calibre-on-GDrive"
 * (xem GDriveOpdsLibraryListBottomSheet). Chỉ có 1 trách nhiệm duy nhất: mở camera, decode 1 mã
 * QR, trả text thô về qua RESULT_OK, KHÔNG tự parse/validate link ở đây, việc đó do
 * GDrivePublicFileClient.extractFileId() + AddGDriveOpdsLibraryDialogFragment đảm nhận (tái dùng
 * nguyên pipeline thêm thư viện thủ công đã có).
 */
public class QrScanActivity extends AppCompatActivity {

    public static final String EXTRA_SCANNED_TEXT = "scanned_text";

    private final ActivityResultLauncher<String> requestCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.gdrive_opds_qr_camera_permission_denied, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    private ExecutorService cameraExecutor;
    private final AtomicBoolean handled = new AtomicBoolean(false);
    private PreviewView previewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        previewView = findViewById(R.id.previewView);
        TextView tvHint = findViewById(R.id.tvQrScanHint);
        tvHint.setText(R.string.gdrive_opds_qr_scan_hint);
        findViewById(R.id.btnQrScanCancel).setOnClickListener(v -> finish());

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalysis(cameraProvider);
            } catch (Exception e) {
                Logger.e(e);
                Toast.makeText(this, R.string.gdrive_opds_qr_camera_error, Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewAndAnalysis(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        BarcodeScanner scanner = BarcodeScanning.getClient();
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> analyzeFrame(imageProxy, scanner));

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy, BarcodeScanner scanner) {
        // handled.get(): sau khi đã bắt được 1 mã QR hợp lệ và đang finish() Activity, bỏ qua mọi
        // frame tới sau đó, tránh gọi onBarcodesDetected() lặp lại nhiều lần cho cùng 1 mã QR còn
        // đứng yên trong khung hình trong lúc Activity đang đóng.
        if (handled.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(this::onBarcodesDetected)
                .addOnFailureListener(Logger::e)
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onBarcodesDetected(List<Barcode> barcodes) {
        if (barcodes.isEmpty() || handled.get()) return;
        String rawValue = barcodes.get(0).getRawValue();
        if (rawValue == null || rawValue.trim().isEmpty()) return;

        if (handled.compareAndSet(false, true)) {
            Intent result = new Intent();
            result.putExtra(EXTRA_SCANNED_TEXT, rawValue.trim());
            setResult(RESULT_OK, result);
            runOnUiThread(this::finish);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
