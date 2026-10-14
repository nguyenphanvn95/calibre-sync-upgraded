# Keep JSON-driven model classes reachable via reflection-ish org.json usage.
-keep class net.mrphan.calibre.Book { *; }
-keep class net.mrphan.calibre.ConnectionInfo { *; }

# org.json is part of the Android platform (android.jar) on-device; nothing to keep there.

# AndroidX Preference / Fragment / FileProvider all ship their own consumer proguard rules,
# no extra keep rules needed for them.

# --- WebView @JavascriptInterface bridges -------------------------------------------------
# BUG FIX (release-only, silently broken feature): none of this was previously kept. WebView's
# JavaScript side calls these methods BY NAME (window.AndroidBridge.foo(), window.
# CsyncSelectionBridge.onSelectionActive(), window.CsyncImageBridge.onImageTapped(...)) --
# R8 has no way to see that reflection-style call from injected JS, so without this rule it is
# free to rename/remove these methods in a release build. The app would still compile and run,
# but every one of these features would quietly stop working ONLY in release (works fine in
# debug, where isMinifyEnabled = false) -- this exact class of bug: GDriveLibraryJsBridge (Cloud
# Libraries GDrive web view), ReaderSelectionToolbar (highlight/search/share/dictionary/translate
# pill toolbar), and ReaderImageTapWatcher (Image Tool: tap-to-open ảnh trong chương). This single
# rule covers all current and future @JavascriptInterface bridges in the app.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- WebDAV / NAS (sardine-android + org.simpleframework:simple-xml) --------------------------
# BUG FIX (release-only): sardine-android's internal WebDAV response model classes (Multistatus/
# Response/Prop/... under com.thegrizzlylabs.sardineandroid.*) are populated by simple-xml purely
# via reflection on @Element/@ElementList/@Attribute-annotated fields -- there is no other code
# path that "uses" those fields, so R8 will happily rename or strip them as dead code without this
# rule, silently breaking WebDAV/NAS folder listing and file transfer in release only (see
# WebDavCloudProvider.java). -dontwarn entries are for the xmlpull/xpp3 classes simple-xml
# references reflectively that were intentionally excluded above (see build.gradle.kts) --
# harmless at runtime since the platform supplies working implementations of the same interfaces.
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keepclassmembers,allowobfuscation class * {
    @org.simpleframework.xml.* <fields>;
    @org.simpleframework.xml.* <methods>;
}
-dontwarn org.simpleframework.xml.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.xmlpull.mxp1.**

# --- Google Drive REST API (com.google.api-client / com.google.apis:google-api-services-drive) --
# BUG FIX (release-only): Drive API model classes (com.google.api.services.drive.model.File,
# FileList, ...) are (de)serialized by GsonFactory purely via reflection on
# @com.google.api.client.util.Key-annotated fields (see GoogleDriveCloudProvider.java). Without
# this rule R8 can rename those fields, so the JSON keys Google's API actually returns
# ("id", "name", "mimeType", ...) stop matching the (renamed) field names, and every Drive API
# response silently deserializes to empty/null fields in release only.
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
# google-http-client/google-api-client optionally reference these JVM-only classes (via
# reflection/optional feature detection) that simply don't exist on Android -- harmless to not
# find them at runtime since those code paths are never taken, but without -dontwarn R8 can print
# enough "missing class" noise to obscure a real problem in the build log.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.apache.http.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# --- NghiTTS: sherpa-onnx (app/libs/sherpa-onnx.aar, local file dependency) -------------------
# BUG FIX (release-only): this is a *local* `implementation(files(...))` dependency, not a Maven
# artifact -- AGP does not reliably pick up a bundled consumer-rules.pro from a local .aar the
# same way it does for a normal Maven dependency, so any consumer rules sherpa-onnx ships
# internally may or may not actually apply here. Its Kotlin wrapper classes
# (com.k2fsa.sherpa.onnx.OfflineTts/OfflineTtsConfig/OfflineTtsModelConfig/...) bridge into native
# (JNI) code both via `external fun` (already covered by the default
# proguard-android-optimize.txt native-method rule) AND via native code reading config object
# fields back by name through JNI reflection -- if R8 renames those fields/methods, native model
# loading fails silently in release only (works fine in debug). Keep the whole package intact.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# --- Nghe Epub TTS native bridge -------------------------------------------------------------
# JNI symbols in libpiperncnn.so are exported for this exact package/class/method surface.
-keep class com.pipodev.ngheepub.tts.PiperNcnn { *; }
-dontwarn com.pipodev.ngheepub.tts.**

# --- Readium (EPUB navigator/streamer/shared) -------------------------------------------------
# BUG FIX (release-only crash: Fragment$InstantiationException wrapping
# NoSuchMethodException: org.readium.r2.navigator.epub.EpubNavigatorFragment.<init> [], seen in
# crash reports on activity relaunch -- rotation, split-screen resize, process-death restore).
# FragmentManager can fall back to instantiating a Fragment via reflection (its public no-arg
# constructor) when restoring saved state; without a keep rule R8 is free to strip that
# constructor as "unused" from any Fragment subclass it can't see called directly, including
# library ones like EpubNavigatorFragment. EpubReaderActivity#onCreate has the primary fix (it
# now prevents FragmentManager from ever attempting that reflection-restore for this fragment in
# the first place), but this rule is defense-in-depth against the same failure mode for that
# class and any other Fragment subclass, ours or a library's, that might hit it some other way.
#
# Also keeps readium-shared's publication/metadata model classes intact -- they're populated via
# kotlinx.serialization (parsing OPF/manifest JSON) purely by field/property NAME, which R8 has
# no way to see is "used" and would otherwise be free to rename/strip, silently breaking EPUB
# metadata parsing in release builds only (works fine in debug, where isMinifyEnabled = false).
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>();
}
-keep class org.readium.r2.navigator.** { *; }
-keep class org.readium.r2.shared.** { *; }
-keep class org.readium.r2.streamer.** { *; }
-dontwarn org.readium.r2.navigator.**
-dontwarn org.readium.r2.shared.**
-dontwarn org.readium.r2.streamer.**
