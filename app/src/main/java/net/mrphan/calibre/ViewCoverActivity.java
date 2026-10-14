package net.mrphan.calibre;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen, pinch-zoomable view of a book's cover, launched from BookDetailActivity's
 * "view cover" action button. Reuses ZoomableImageView (already used by PdfReaderActivity/
 * CbzReaderActivity for page zoom/pan) instead of writing new zoom/pan logic.
 */
public class ViewCoverActivity extends AppCompatActivity {

    public static final String EXTRA_COVER_BYTES = "cover_bytes";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_view_cover);

        ZoomableImageView imageView = findViewById(R.id.vc_image);
        byte[] cover = getIntent().getByteArrayExtra(EXTRA_COVER_BYTES);
        if (cover != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(cover, 0, cover.length);
            if (bmp != null) {
                imageView.setImageBitmap(bmp);
            }
        }

        ImageButton btnClose = findViewById(R.id.vc_btn_close);
        btnClose.setOnClickListener(v -> finish());
    }
}
