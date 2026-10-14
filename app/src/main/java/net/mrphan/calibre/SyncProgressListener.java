package net.mrphan.calibre;

/** Callback nho gon de DeviceLibrarySyncManager bao tien trinh len DeviceLibrarySyncWorker.
 * Worker dung cap (done,total) nay cho 2 viec: dung R.string co san de dung lai text notification
 * dang chay nen ("Dang tai len 3/12"), VA setProgressAsync(Data) de BackgroundJobsBottomSheet doc
 * realtime qua WorkInfo#getProgress(). total <= 0 nghia la chua biet tong so (vi du dang
 * download truoc khi tai xong manifest) -- UI nen hien progress bar dang indeterminate trong
 * truong hop do. */
interface SyncProgressListener {
    void onProgress(int done, int total);
}
