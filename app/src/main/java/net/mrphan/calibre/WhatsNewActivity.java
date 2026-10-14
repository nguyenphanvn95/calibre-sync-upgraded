package net.mrphan.calibre;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Man hinh "What's New", thay the AlertDialog "About" cu (xem MainActivity#showAboutDialog() da
 * bi go bo, nav_about trong nav_drawer_menu.xml gio mo Activity nay). Tieu de dang
 * "<ten app> <versionName> (<versionCode>)" + danh sach cac khoi "What's new in vX.X" voi bullet
 * ben duoi, doc tu 2 mang song song whats_new_versions/whats_new_bullets trong strings.xml
 * (giong bo cuc man hinh "What's new" cua Moon+ Reader trong anh mau nguoi dung gui) -- noi
 * dung rut gon tu CHANGELOG.md, khong dua nguyen van chi tiet ky thuat (ten class/ham) vao day vi
 * day la man hinh danh cho nguoi dung cuoi, khong phai changelog cho dev.
 */
public class WhatsNewActivity extends AppCompatActivity {

    public static Intent buildIntent(Context context) {
        return new Intent(context, WhatsNewActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whats_new);

        findViewById(R.id.whats_new_back).setOnClickListener(v -> finish());

        TextView titleView = findViewById(R.id.whats_new_title);
        titleView.setText(getString(R.string.whats_new_title_format,
                getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        populateContent();
    }

    private void populateContent() {
        LinearLayout container = findViewById(R.id.whats_new_container);
        String[] versions = getResources().getStringArray(R.array.whats_new_versions);
        String[] bullets = getResources().getStringArray(R.array.whats_new_bullets);
        // 2 mang phai cung do dai/dung thu tu (khop theo index) -- xem strings.xml, comment tren
        // ca 2 khai bao string-array. Neu 1 trong 2 mang bi sua thieu dong bo, chi lay phan
        // ngan hon de tranh ArrayIndexOutOfBoundsException, thay vi crash man hinh nay.
        int count = Math.min(versions.length, bullets.length);
        // dp -> px thuc su (density la 1 he so float, vd 2.75 tren man hinh xxhdpi) -- ep ve int
        // truc tiep nhu ban cu se lam tron mat gan het khoang cach (vd 2.75 -> 2), dung
        // TypedValue.applyDimension moi ra dung px nhu khi khai bao "24dp" trong layout XML.
        float density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < count; i++) {
            TextView header = new TextView(this);
            header.setText(getString(R.string.whats_new_section_format, versions[i]));
            header.setTypeface(header.getTypeface(), Typeface.BOLD);
            header.setTextSize(17);
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = Math.round(24 * density);
            headerParams.bottomMargin = Math.round(8 * density);
            header.setLayoutParams(headerParams);
            container.addView(header);

            TextView body = new TextView(this);
            body.setText(bullets[i]);
            body.setTextSize(15);
            body.setLineSpacing(6 * density, 1f);
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            container.addView(body);
        }

        // Khoang trong o cuoi de bullet cuoi cung khong dinh sat canh man hinh khi cuon toi day.
        View bottomSpacer = new View(this);
        bottomSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(24 * density)));
        container.addView(bottomSpacer);
    }
}
