package net.mrphan.calibre;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parse % tien trinh doc tu JSON da luu trong MetadataDatabaseHelper.last_locator, KHONG mo
 * lai file sach, KHONG goi Readium. Chi doc duoc voi EPUB (Readium Locator co
 * locations.totalProgression). PDF/CBZ luu {"type":"pdf_page"/"cbz_page","page":N} khong co
 * tong so trang trong JSON nay nen tra ve null (UI phai an dong % di, khong hien "0%").
 */
final class ReadingProgressUtil {

    private ReadingProgressUtil() {}

    /** @return 0..100, hoac null neu khong co / khong tinh duoc (an dong progress trong UI). */
    static Integer progressPercentFromLocator(String lastLocatorJson) {
        if (lastLocatorJson == null || lastLocatorJson.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(lastLocatorJson);
            // Bo qua ban ghi PDF/CBZ (khong co tong so trang) -- tranh hieu nham day la EPUB.
            String type = root.optString("type", null);
            if ("pdf_page".equals(type) || "cbz_page".equals(type)) return null;

            // EPUB duoc luu dang bao boc {"type":"epub_locator","locator":{...}} boi
            // EpubReaderActivity#saveLastLocator / ReaderAnnotation#encodeLocator -- Readium
            // Locator.toJSON() dat "locations" (va totalProgression ben trong no) o BEN TRONG
            // object "locator" nay, KHONG phai o goc JSON. Doc nham o goc khien locations luon
            // null va % luon bi an -- day chinh la loi can sua.
            JSONObject locatorObj = root.optJSONObject("locator");
            JSONObject locations = (locatorObj != null)
                    ? locatorObj.optJSONObject("locations")
                    : root.optJSONObject("locations"); // fallback: JSON Locator "tran", khong bao boc
            if (locations == null || !locations.has("totalProgression")) return null;
            double fraction = locations.getDouble("totalProgression");
            int percent = (int) Math.round(fraction * 100);
            if (percent < 0) percent = 0;
            if (percent > 100) percent = 100;
            return percent;
        } catch (JSONException e) {
            return null;
        }
    }
}
