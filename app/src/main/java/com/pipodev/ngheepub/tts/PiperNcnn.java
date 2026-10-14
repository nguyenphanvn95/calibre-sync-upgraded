package com.pipodev.ngheepub.tts;

public final class PiperNcnn {
    static {
        System.loadLibrary("g2p_ffi");
        System.loadLibrary("piperncnn");
    }

    public native void clearBuffer();
    public native void clearOovWords();
    public native boolean feedAudioBuffer(short[] data);
    public native int getBufferedSamples();
    public native String[] getOovWords();
    public native boolean initMinimalG2PData(String path);
    public native boolean isBufferLow();
    public native boolean loadModelFromFile(String modelPath, String tokensPath, String lang, int flags);
    public native void startStream();
    public native void stop();
    public native boolean synthesize(String text, int speakerId, double speed);
    public native short[] synthesizeToBuffer(String text, int speakerId, double speed);
}
