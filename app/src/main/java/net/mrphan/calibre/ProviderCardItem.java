package net.mrphan.calibre;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/** Plain bean describing one row in the Cloud Libraries card list. Not persisted -- the list of
 * these is rebuilt fresh every time ConnectionFragment#onCreateView runs. */
public class ProviderCardItem {
    public final ProviderCardType type;
    @DrawableRes
    public final int iconRes;
    @StringRes
    public final int titleRes;
    public final boolean comingSoon;

    public ProviderCardItem(ProviderCardType type, @DrawableRes int iconRes,
                             @StringRes int titleRes, boolean comingSoon) {
        this.type = type;
        this.iconRes = iconRes;
        this.titleRes = titleRes;
        this.comingSoon = comingSoon;
    }
}
