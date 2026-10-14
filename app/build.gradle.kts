import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

fun secretProperty(name: String, fallback: String = ""): String {
    return (project.findProperty(name) as String?)
        ?: localProperties.getProperty(name)
        ?: fallback
}

plugins {
    id("com.android.application")
    // Kotlin is needed only for the EPUB reader (Readium kotlin-toolkit is Kotlin-only).
    // PdfReaderActivity/CbzReaderActivity/everything else, including the Cloud Libraries
    // Google Drive integration (OAuth 2.0 Authorization Code Flow via AppAuth, see
    // GoogleDriveOAuthManager.java / GoogleDriveCloudProvider.java), stayed plain Java --
    // fully blocking APIs, no coroutines needed.
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.mrphan.calibre"
    // NOTE: pick the latest stable compileSdk/targetSdk available in your Android Studio /
    // command-line SDK manager at build time -- do not blindly trust this number, it was set
    // at the time this migration was written (see README "Environment requirements").
    compileSdk = 35

    defaultConfig {
        applicationId = "net.mrphan.calibre"
        // minSdk 24 (Android 7.0): the lowest version that still has a real
        // Notification.Builder/channel-capable API surface and keeps the foreground-service
        // code path simple. See README for the reasoning.
        minSdk = 24
        targetSdk = 35
        versionCode = 18
        versionName = "3.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // net.openid:appauth ships a RedirectUriReceiverActivity in its own AndroidManifest.xml
        // with <data android:scheme="${appAuthRedirectScheme}"/> -- the manifest merger requires
        // SOME value here for any app that depends on the library, even though this project
        // never actually uses that activity (the OAuth redirect is intercepted inside the
        // GoogleDriveOAuthActivity WebView via shouldOverrideUrlLoading, see the comment on that
        // activity in AndroidManifest.xml). The value just needs to be a syntactically valid,
        // unused custom scheme -- using the package name keeps it collision-safe.
        manifestPlaceholders["appAuthRedirectScheme"] = "net.mrphan.calibre"

        // Restricted, read-only Drive API key (KHÔNG phải OAuth client secret) -- dùng để gọi
        // Drive v3 REST API công khai (files.get?alt=media) cho các file đã share "Anyone with
        // the link", giống hệt cơ chế GDrivePublicFileService bên bản Flutter. Đọc từ
        // gradle.properties (KHÔNG commit giá trị thật vào git) -- key phải được restrict trên
        // Google Cloud Console: chỉ scope "Google Drive API" + restrict theo package name
        // "net.mrphan.calibre" + SHA-1 chữ ký app.
        buildConfigField(
            "String", "GDRIVE_PUBLIC_API_KEY",
            "\"${project.findProperty("GDRIVE_PUBLIC_API_KEY") ?: "REPLACE_WITH_YOUR_RESTRICTED_DRIVE_API_KEY"}\""
        )
        buildConfigField(
            "String", "GOOGLE_DRIVE_CLIENT_ID",
            "\"${secretProperty("GOOGLE_DRIVE_CLIENT_ID")}\""
        )
        buildConfigField(
            "String", "GOOGLE_DRIVE_CLIENT_SECRET",
            "\"${secretProperty("GOOGLE_DRIVE_CLIENT_SECRET")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Debug builds sign automatically with Android's built-in debug keystore -- nothing to
        // configure, `./gradlew assembleDebug` just works.
        //
        // For a RELEASE build you must supply your own keystore. This project intentionally
        // does NOT hard-code a production keystore/password (see the security note in
        // README.md about the earlier build guide that had a hard-coded "123456" keystore
        // password and a hard-coded Google API key -- don't reuse that pattern here, and
        // rotate that key if it's still live).
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only actually signs with a release key if keystore.properties exists (see above);
            // otherwise `assembleRelease` will fail at the signing step until you provide one,
            // which is intentional -- see README.
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by Readium kotlin-toolkit (java.time usage internally) on minSdk 24 devices.
        isCoreLibraryDesugaringEnabled = true
    }

    // PageCurlMathTest (app/src/test/.../reader/epub/curl/PageCurlMathTest.kt) exercises
    // PageCurlMath, which builds an android.graphics.Path. Plain JVM unit tests run against the
    // unmocked Android SDK stub jar by default, so any Path method call throws "not mocked"
    // unless returnDefaultValues is enabled here -- no need for Robolectric just for this.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

    // pickFirst cho cac thu vien native ma sherpa-onnx.aar (dung cho NghiTTS) dong goi san,
    // phong truong hop mot dependency khac trong tuong lai cung mang theo ban trung ten.
    packaging {
        jniLibs {
            pickFirsts += "**/libonnxruntime.so"
            pickFirsts += "**/libonnxruntime4j_jni.so"
            pickFirsts += "**/libc++_shared.so"
        }
        // google-auth-library-oauth2-http and google-auth-library-credentials (transitive deps
        // pulled in by the Google Drive stack) both ship a META-INF/INDEX.LIST, which collides
        // during resource merging. These files are just JAR indexes used by some JVM classloaders
        // at runtime and aren't needed/used on Android, so it's safe to just drop them (as opposed
        // to excluding actual license/notice files, which you generally want to keep one of).
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }
}

// FIX BUILD RELEASE: "ERROR: R8: Library class android.content.res.XmlResourceParser implements
// program class org.xmlpull.v1.XmlPullParser" tren minifyReleaseWithR8.
//
// Nguyen nhan: com.github.thegrizzlylabs:sardine-android (dung cho WebDAV/NAS, xem
// WebDavCloudProvider.java) keo theo org.simpleframework:simple-xml de parse XML PROPFIND cua
// WebDAV, va simple-xml lai keo theo (transitively) mot ban jar rieng cua interface
// org.xmlpull.v1.XmlPullParser (qua module xmlpull hoac xpp3, tuy phien ban resolve duoc).
// Android SDK (android.jar) DA CO SAN cung mot interface nay o "library classpath" (vi du
// android.content.res.XmlResourceParser implements no) -- R8 khong cho phep 1 class vua la
// "library class" (tu android.jar) vua la "program class" (duoc compile thang vao APK) cung
// luc, nen bao loi cung nay thay vi chi warning.
//
// Cach fix: loai bo han jar rieng (xmlpull/xpp3) khoi dependency resolution, de sardine-android/
// simple-xml dung lai interface + implementation da co san tren may that. Bang chung day la fix
// AN TOAN (khong lam gay tinh nang nao): OPDSLoader.java va OPDSLibraryStatsLoader.java trong
// chinh project nay da goi thang XmlPullParserFactory.newInstance() de parse OPDS feed, KHONG can
// khai bao bat ky dependency xmlpull/xpp3 nao ca -- tuc la Android runtime that (khac voi jar
// android.jar chi dung luc compile) da co san mot implementation hoat dong day du cua ca hai. Loai
// bo ban jar du thua (chi danh cho JVM desktop thong thuong, khong danh cho Android) khoi
// sardine-android/simple-xml chi la buoc cuoi cung de dong bo voi cach OPDS da va dang lam.
configurations.all {
    exclude(group = "xmlpull", module = "xmlpull")
    exclude(group = "xpp3", module = "xpp3")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.fragment:fragment:1.8.2")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.cursoradapter:cursoradapter:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    // Explicit dependency: MaterialCardView's base attrs (cardCornerRadius, cardElevation,
    // cardBackgroundColor, contentPadding, etc.) actually live in androidx.cardview's own
    // attrs.xml, not material's. Material normally pulls this in transitively, but declaring
    // it explicitly avoids "attribute res-auto:cardElevation not found" AAPT errors if that
    // transitive resolution ever gets interrupted (stale/corrupted Gradle cache, network hiccup
    // mid-download, etc.) -- see item_book_carousel.xml build failure.
    implementation("androidx.cardview:cardview:1.0.0")
    // Chay dong bo Device Library <-> Google Drive duoi nen (DeviceLibrarySyncWorker), khong
    // chan UI va tu dong retry khi mat mang giua chung -- xem DeviceLibrarySyncScheduler.
    implementation("androidx.work:work-runtime:2.9.1")
    // Calibre Library grid view (book_item_grid.xml): keeps the cover thumbnail at a fixed
    // 2:3 aspect ratio regardless of column width (2/3/4-per-row), which plain LinearLayout
    // can't express without a fixed height that would look wrong at other column counts.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- OPDS Basic Auth: encrypted storage for Content Server username/password ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- In-app PDF / CBZ readers (ZoomableImageView + paging) ---
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // --- In-app EPUB reader ---
    // NOTE: pinned to Readium kotlin-toolkit 3.1.2, NOT the latest 3.3.0. As of 3.3.0, Readium's
    // audiobook/TTS "Media Navigator" feature pulls in newer androidx.media3 (1.10.0) and
    // androidx.core (1.18.0) transitive dependencies that require compileSdk 36 + Android
    // Gradle Plugin 8.9.1+ -- this project only uses the EPUB navigator (no audiobook/TTS), so
    // there's no reason to force that whole toolchain upgrade just to pull in unused code.
    // 3.1.2 still targets Android SDK 34 per Readium's own migration guide, so it builds cleanly
    // against this project's compileSdk 35 / AGP 8.5.2. If you later want the audiobook/TTS
    // features and are willing to bump compileSdk to 36 + AGP to 8.9.1+ project-wide, you can
    // move this back up to 3.3.0 (or newer) at that point.
    // kotlin-toolkit 3.x still has a large, actively-evolving API surface; EpubReaderActivity.kt
    // was written against the documented API and the public migration guide, but this sandbox
    // has no Android SDK/emulator to actually compile or run it against -- build this module
    // first (`./gradlew :app:compileDebugKotlin`) and fix up any API drift against the toolkit's
    // own test-app (https://github.com/readium/kotlin-toolkit/tree/develop/test-app) before
    // relying on it. See README "In-app reader / EPUB (Readium)".
    implementation("org.readium.kotlin-toolkit:readium-shared:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Cloud Libraries: Google Drive (OAuth 2.0 Authorization Code Flow, Web Application
    // OAuth Client hardcode trong GoogleDriveOAuthConfig — KHÔNG còn phụ thuộc Play Services / SHA-1
    // / package signature, xem GoogleDriveOAuthManager.java + GoogleDriveTokenManager.java). ---
    implementation("net.openid:appauth:0.11.1")
    // Chrome Custom Tabs: mở trang đăng nhập Google trong trình duyệt hệ thống thật (nổi trên
    // app, không văng hẳn sang app Chrome riêng biệt) -- xem GoogleDriveOAuthActivity. Google
    // chặn cứng WebView nhúng cho trang OAuth (kể cả giả User-Agent), Custom Tabs thì được chấp
    // nhận vì dùng chung engine/cookie jar với Chrome thật.
    implementation("androidx.browser:browser:1.8.0")
    // Client Drive v3 REST thuần Java (không kéo theo Play Services) — chỉ cần một
    // HttpRequestInitializer tự viết gắn "Authorization: Bearer <access_token>", xem
    // GoogleDriveAuthHttpInitializer.java.
    implementation("com.google.api-client:google-api-client-gson:2.4.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20251210-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // --- Cloud Libraries: Calibre Libraries on GDrive (giả lập OPDS server nội bộ từ
    // metadata_public.json chia sẻ công khai trên Google Drive, KHÔNG dùng OAuth -- xem
    // GDrivePublicFileClient / GDriveOpdsHttpServer). NanoHTTPD là embedded HTTP server nhẹ,
    // dùng lại được route pattern giống bản Flutter (gdrive_local_server.dart, dùng package
    // shelf bên đó).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // --- Cloud Libraries: WebDAV / FTP for self-hosted NAS ---
    implementation("com.github.thegrizzlylabs:sardine-android:0.9")
    implementation("commons-net:commons-net:3.11.1")

    // --- Edge TTS: WebSocket client tới dịch vụ Read Aloud không chính thức của Microsoft Edge ---
    // (Android không có WebSocket client built-in đơn giản; OkHttp rất nhẹ và đã là de-facto
    // standard cho việc này.)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reader/TTS background music: resolve YouTube watch/short links to playable audio streams.
    // Vendored as local JARs because this project's Gradle JDK cannot currently validate JitPack
    // certificates reliably, while the same artifacts are reachable from PowerShell/browser.
    implementation(files("libs/NewPipeExtractor-v0.26.5.jar"))
    implementation(files("libs/nanojson-e9d656ddb49a412a5a0a5d5ef20ca7ef09549996.jar"))
    implementation(files("libs/protobuf-javalite-4.35.1.jar"))
    implementation(files("libs/rhino-1.8.1.jar"))

    // --- NghiTTS: đọc thư mục models do người dùng chọn qua SAF (DocumentFile) ---
    implementation("androidx.documentfile:documentfile:1.0.1")

    // --- NghiTTS: sherpa-onnx (bundles ONNX Runtime + espeak-ng natively) để chạy model
    // Piper/VITS .onnx offline trên máy. Không có trên Maven Central nên add làm file cục bộ --
    // tải file .aar từ https://github.com/k2-fsa/sherpa-onnx/releases (bản TTS Android mới
    // nhất) và đặt vào app/libs/sherpa-onnx.aar trước khi build. Xem README "TTS Models /
    // NghiTTS setup".
    implementation(files("libs/sherpa-onnx.aar"))

    // --- TTS background playback: MediaSessionCompat + MediaStyle notification for
    // TtsPlaybackService. Deliberately androidx.media (compat), NOT androidx.media3 -- see the
    // Readium 3.1.2 pin comment above for why media3's compileSdk 36 requirement is off-limits
    // for this project right now.
    implementation("androidx.media:media:1.7.0")
    // LifecycleService (TtsPlaybackService extends it, same lifecycleScope style already used
    // throughout EpubReaderActivity) needs this artifact explicitly -- lifecycle-runtime-ktx
    // pulled in transitively by fragment/appcompat does not include it.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // --- Quét mã QR để nhập thư viện Calibre-on-GDrive (thay cho gõ tay link/ID) ---
    // CameraX: quản lý camera lifecycle-aware, không cần tự xử lý Camera1/Camera2 API thô.
    // Pinned tới 1.5.1 (bản ổn định mới nhất tại thời điểm viết, xem CameraX release blog) --
    // nếu Maven Central có bản ổn định mới hơn khi build, cập nhật lại số này.
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    // ML Kit Barcode Scanning: decode QR on-device, không gửi ảnh lên server nào -- không phát
    // sinh thêm rủi ro privacy/network so với phần còn lại của app.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // JVM unit tests for the reader.convert package (PalmDocDecompressorTest and friends) --
    // pure Kotlin/JVM, no Android SDK/emulator needed to run these.
    testImplementation("junit:junit:4.13.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
