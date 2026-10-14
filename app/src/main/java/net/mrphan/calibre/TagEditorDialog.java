package net.mrphan.calibre;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.List;

/* Dialog hiển thị + cho sửa "Local tags" của 1 quyển sách, đọc/ghi thẳng qua
   MetadataDatabaseHelper#getTagNamesForBook/getAllTagNames/addTagToBook/removeTagFromBook. Mỗi
   thao tác thêm/xoá thẻ được lưu ngay lập tức (không có nút Save riêng), giống kiểu chip-editor
   của calibre-web-companion. */
public final class TagEditorDialog {

    public interface OnTagsChangedListener {
        void onTagsChanged();
    }

    private TagEditorDialog() {
    }

    public static void show(Context context, MetadataDatabaseHelper db, int metadataId,
                             OnTagsChangedListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_tag_editor, null);
        ChipGroup chipGroup = view.findViewById(R.id.tagEditorChipGroup);
        AutoCompleteTextView input = view.findViewById(R.id.tagEditorInput);

        List<String> allTags = db.getAllTagNames();
        input.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_dropdown_item_1line, allTags));

        for (String tag : db.getTagNamesForBook(metadataId)) {
            addChip(context, chipGroup, tag, db, metadataId, listener);
        }

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!isDone) return false;
            String typed = input.getText() == null ? "" : input.getText().toString().trim();
            if (!typed.isEmpty()) {
                db.addTagToBook(metadataId, typed);
                addChip(context, chipGroup, typed, db, metadataId, listener);
                if (listener != null) listener.onTagsChanged();
                input.setText("");
            }
            return true;
        });

        new AlertDialog.Builder(context)
                .setTitle(R.string.tag_editor_title)
                .setView(view)
                .setPositiveButton(R.string.tag_editor_done, null)
                .show();
    }

    private static void addChip(Context context, ChipGroup chipGroup, String tagName,
                                 MetadataDatabaseHelper db, int metadataId,
                                 OnTagsChangedListener listener) {
        Chip chip = new Chip(context);
        chip.setText(tagName);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            db.removeTagFromBook(metadataId, tagName);
            chipGroup.removeView(chip);
            if (listener != null) listener.onTagsChanged();
        });
        chipGroup.addView(chip);
    }
}
