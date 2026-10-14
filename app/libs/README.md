# app/libs

Đặt file `sherpa-onnx.aar` vào đây trước khi build (không commit sẵn vì đây là binary lớn,
không có trên Maven Central).

Tải từ: https://github.com/k2-fsa/sherpa-onnx/releases (bản TTS Android mới nhất)
- Tải `sherpa-onnx-vX.Y.Z-android.tar.bz2`, giải nén để lấy file `sherpa-onnx-*.aar`.
- Đổi tên (hoặc copy) thành `sherpa-onnx.aar` trong thư mục này.
- Chỉ cần giữ `armeabi-v7a` + `arm64-v8a` để đỡ nặng APK nếu release có build riêng theo ABI.

Xem thêm README.md ở gốc project, mục "TTS Models / NghiTTS setup".
