package net.mrphan.calibre;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.List;

/**
 * Shared "Display" mode picker used by both CollectionFragment (Device Library) and
 * OPDSBrowserFragment (Calibre Library): a centered, floating Material dialog listing each
 * view-mode option as icon + label + radio button (see dialog_view_mode_picker.xml /
 * item_view_mode_dialog_row.xml), replacing the anchored dropdown submenu Android draws by
 * default for a nested {@code <menu>} item under the toolbar's overflow ("Display") entry.
 * <p>
 * Both fragments already had their own int-based mode identifiers (CollectionFragment's
 * ViewMode#prefValue, OPDSBrowserFragment's plain column count) before this dialog existed, so
 * {@link Option#id} is left as a plain int rather than introducing a shared enum -- each caller
 * passes back whatever it already used as a menu item id / pref value.
 */
final class ViewModePickerDialog {

    /** One row: icon + label resource ids, plus the caller-defined id returned on selection. */
    static final class Option {
        final int iconRes;
        final int labelRes;
        final int id;

        Option(int iconRes, int labelRes, int id) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.id = id;
        }
    }

    interface OnOptionChosen {
        void onOptionChosen(int id);
    }

    private ViewModePickerDialog() {
    }

    /**
     * @param options    rows to show, in order
     * @param selectedId which option's radio button starts checked (matched against Option#id;
     *                   no match just leaves every radio button unchecked)
     * @param callback   fired once, when the user taps a row; the dialog dismisses itself first
     */
    static void show(Context context, List<Option> options, int selectedId, OnOptionChosen callback) {
        if (context == null) return;

        LayoutInflater inflater = LayoutInflater.from(context);
        View body = inflater.inflate(R.layout.dialog_view_mode_picker, null, false);
        LinearLayout container = body.findViewById(R.id.view_mode_row_container);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(body)
                .create();

        for (Option option : options) {
            View row = inflater.inflate(R.layout.item_view_mode_dialog_row, container, false);
            ((ImageView) row.findViewById(R.id.view_mode_icon)).setImageResource(option.iconRes);
            ((TextView) row.findViewById(R.id.view_mode_label)).setText(option.labelRes);
            ((RadioButton) row.findViewById(R.id.view_mode_radio)).setChecked(option.id == selectedId);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onOptionChosen(option.id);
            });
            container.addView(row);
        }

        dialog.show();
    }
}
