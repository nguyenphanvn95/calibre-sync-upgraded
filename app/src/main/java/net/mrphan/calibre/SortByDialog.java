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
 * Shared "Sort by..." picker used by both CollectionFragment (Device Library) and
 * OPDSBrowserFragment (Calibre Library): a centered, floating Material dialog with a
 * "Sort Ascending"/"Sort Descending" toggle header (tap flips it in place, matching the
 * reference mock-up screenshot) followed by each sort field as icon + label + radio button (see
 * dialog_sort_by.xml, reusing item_view_mode_dialog_row.xml exactly like ViewModePickerDialog).
 * <p>
 * The header alone never dismisses the dialog -- only tapping a field row commits the
 * (field, ascending) pair and closes it, so the user can freely flip direction before picking a
 * field. Each fragment only offers the fields it can actually back with real data (see
 * CollectionFragment#showSortByDialog() and OPDSBrowserFragment#showSortByDialog()), so
 * {@link Option#id} is left as a plain caller-defined int rather than a shared enum, same
 * convention as ViewModePickerDialog#Option.
 */
final class SortByDialog {

    /** One row: icon + label resource ids, plus the caller-defined id returned on selection. */
    static final class Option {
        final int iconRes;
        final int labelRes;
        /** Dynamic label (e.g. a Custom Column's Calibre-defined name, which is a plain string
         * from the DB, not a string resource) -- takes priority over labelRes when non-null. See
         * the {@link #Option(int, CharSequence, int)} constructor below. */
        final CharSequence label;
        final int id;

        Option(int iconRes, int labelRes, int id) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.label = null;
            this.id = id;
        }

        /** For rows whose label isn't a compile-time string resource -- e.g. a Custom Column's
         * label, which comes from Calibre Desktop and varies per library/user. */
        Option(int iconRes, CharSequence label, int id) {
            this.iconRes = iconRes;
            this.labelRes = 0;
            this.label = label;
            this.id = id;
        }
    }

    interface OnSortChosen {
        void onSortChosen(int fieldId, boolean ascending);
    }

    private SortByDialog() {
    }

    /**
     * @param options        fields to show, in order
     * @param selectedId     which field's radio button starts checked (matched against
     *                       Option#id; no match just leaves every radio button unchecked)
     * @param startAscending initial direction shown in the header toggle
     * @param callback       fired once, when the user taps a field row; the dialog dismisses
     *                       itself first
     */
    static void show(Context context, List<Option> options, int selectedId, boolean startAscending,
                      OnSortChosen callback) {
        if (context == null) return;

        LayoutInflater inflater = LayoutInflater.from(context);
        View body = inflater.inflate(R.layout.dialog_sort_by, null, false);
        LinearLayout container = body.findViewById(R.id.sort_field_container);
        View directionToggle = body.findViewById(R.id.sort_direction_toggle);
        TextView directionLabel = body.findViewById(R.id.sort_direction_label);

        // Held in a 1-element array so the click listener below (defined before `ascending`
        // would otherwise be effectively-final) can flip it in place.
        final boolean[] ascending = {startAscending};
        Runnable refreshDirectionLabel = () -> directionLabel.setText(
                ascending[0] ? R.string.sort_direction_ascending : R.string.sort_direction_descending);
        refreshDirectionLabel.run();
        directionToggle.setOnClickListener(v -> {
            ascending[0] = !ascending[0];
            refreshDirectionLabel.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(body)
                .create();

        for (Option option : options) {
            View row = inflater.inflate(R.layout.item_view_mode_dialog_row, container, false);
            ((ImageView) row.findViewById(R.id.view_mode_icon)).setImageResource(option.iconRes);
            ((TextView) row.findViewById(R.id.view_mode_label)).setText(
                    option.label != null ? option.label : context.getString(option.labelRes));
            ((RadioButton) row.findViewById(R.id.view_mode_radio)).setChecked(option.id == selectedId);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onSortChosen(option.id, ascending[0]);
            });
            container.addView(row);
        }

        dialog.show();
    }
}
