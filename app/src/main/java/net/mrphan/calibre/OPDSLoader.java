package net.mrphan.calibre;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Message;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;

public abstract class OPDSLoader {


    public OPDSLoader() {
        super();
        resetPages();
    }


    private Thread xml_thread;
    private final String uuid_identifier = "urn:uuid:";
    private final String tags_identifier = "TAGS: ";
    // Da doi chieu voi source that cua Calibre (ACQUISITION_ENTRY() trong src/calibre/srv/opds.py,
    // ban 9.11.0): Publisher/Language/Identifiers/Comments va moi custom column deu duoc Calibre
    // gop chung vao MOT khoi entry/content/div duy nhat, duoi dang cac dong "Nhan: gia tri<br/>"
    // noi tiep nhau, voi phan comments (khong nhan) luon nam o cuoi. Series/Rating/Tags cung nam
    // trong khoi nay voi cung dinh dang. Vi vay toan bo entry/content duoc gom (buffer) roi phan
    // tich qua parseContentBlock() thay vi khop tung TEXT event roi rac tai dung entry/content/div.
    private final String series_identifier = "SERIES: ";
    private final String rating_identifier = "RATING: ";
    // Ky tu sao dac ma Calibre dung cho RATING (rating_to_stars()) -- khong phai so, nen khong
    // the Double.parseDouble() duoc, phai dem so ky tu nay.
    private static final char RATING_STAR_CHAR = '\u2605';

    // Nhan field Calibre phat ra co the la tieng Anh mac dinh hoac da dich theo locale cua
    // Content Server. Key phai la chu thuong, khong dau (xem stripDiacritics()).
    private static final java.util.Map<String, String> FIELD_LABEL_ALIASES = buildFieldLabelAliases();

    private static java.util.Map<String, String> buildFieldLabelAliases() {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        // English (mac dinh)
        m.put("publisher", "publisher");
        m.put("languages", "language");
        m.put("identifiers", "identifiers");
        // Tieng Viet (neu Content Server chay giao dien VI)
        m.put("nha xuat ban", "publisher");
        m.put("ngon ngu", "language");
        m.put("so nhan dang", "identifiers");
        // Them ngon ngu / bien the khac tai day neu phat hien qua test thuc te
        return m;
    }

    private static String stripDiacritics(String s) {
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    // Cac the "khoi" ma comments_to_html() cua Calibre hay dung de boc doan van -- khi gap, chen
    // thay doan trong buffer de tach doan mo ta voi cac dong nhan.
    private static boolean isBlockLevelTag(String localName) {
        return "p".equalsIgnoreCase(localName)
                || "div".equalsIgnoreCase(localName)
                || "li".equalsIgnoreCase(localName)
                || "blockquote".equalsIgnoreCase(localName);
    }

    private boolean insideContentBlock = false;
    private StringBuilder contentBuffer;

    private MetadataDatabaseHelper mDB;
    protected Context context;
    private URL OPDSUrl;
    private Stack<OPDSPage> pages;
    private OPDSPage current_page;
    boolean triggerStop = false;
    // Content Server HTTP Basic Auth (see README "OPDS Basic Auth"). Set via setCredentials()
    // by whoever owns this OPDSLoader (OPDSBrowserFragment), applied to *every* HttpURLConnection
    // this class opens -- the feed/catalog XML fetch, the cover thumbnail fetch, and (by anyone
    // downloading the actual book file through the same Content Server root) reused via
    // getAuthorizationHeader() below -- so there's a single place to get this right instead of
    // three separate copy-pasted header-setting call sites.
    private String csUsername;
    private String csPassword;
    private volatile boolean lastRequestUnauthorized = false;
    private static final int XML_PARSE_COMPLETE = 0;
    private static final int XML_PARSE_UNAUTHORIZED = 1;
    private Handler mainThreadHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == XML_PARSE_UNAUTHORIZED) {
                onUnauthorized();
                triggerStop = false;
            } else if (msg.what == XML_PARSE_COMPLETE) {
                onParseComplete(triggerStop ? null: current_page);
                triggerStop = false;
            }
        }
    };

    public void setCredentials(String username, String password) {
        this.csUsername = (username != null && !username.isEmpty()) ? username : null;
        this.csPassword = password;
    }

    /** @return the "Basic &lt;base64&gt;" header value for the currently configured Content
     * Server credentials, or null if none are set (e.g. server doesn't require a login). */
    public String getAuthorizationHeader() {
        if (csUsername == null) return null;
        String pw = csPassword != null ? csPassword : "";
        try {
            return "Basic " + android.util.Base64.encodeToString(
                    (csUsername + ":" + pw).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private void applyAuth(HttpURLConnection connection) {
        String header = getAuthorizationHeader();
        if (header != null) {
            connection.setRequestProperty("Authorization", header);
        }
    }

    /** Ket qua goi lai cho {@link #resolveOpenSearchTemplate}: [template] la URL template OpenSearch
     * (con nguyen "{searchTerms}", CHUA thay the) neu tim thay, hoac null neu tai lieu khong hop le/
     * loi mang -- luon duoc goi tren main thread. */
    public interface OpenSearchTemplateCallback {
        void onResult(String template);
    }

    /** Tai va phan tich mot tai lieu OpenSearchDescription (theo chuan OpenSearch ma cac OPDS
     * server -- Calibre Content Server, Calibre-Web, COPS, ... -- deu quang cao qua
     * {@code <link rel="search" type="application/opensearchdescription+xml" href="...">} o feed
     * goc/hien tai, xem OPDSPage#getNavigationalLinks()), tim the {@code <Url template="...">}
     * de lay ra URL tim kiem thuc su (uu tien type="application/atom+xml" vi day la dinh dang
     * OPDS/Atom ma app nay parse duoc, cac type khac nhu text/html se bi bo qua neu co ban Atom).
     * KHONG dung cung mot HTTP thread nhu beginParsingXml() (chi 1 xml_thread cho ca class, dang
     * ban khi dang load trang) -- tao thread rieng vi day la 1 request phu, ngan, doc lap. */
    public void resolveOpenSearchTemplate(final URL descriptorUrl, final OpenSearchTemplateCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = null;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) descriptorUrl.openConnection();
                    applyAuth(connection);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(false);
                    XmlPullParser xpp = factory.newPullParser();
                    xpp.setInput(connection.getInputStream(), null);
                    String fallbackTemplate = null;
                    int eventType = xpp.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && "Url".equalsIgnoreCase(xpp.getName())) {
                            String template = xpp.getAttributeValue(null, "template");
                            if (template != null) {
                                String type = xpp.getAttributeValue(null, "type");
                                if (fallbackTemplate == null) fallbackTemplate = template;
                                if (type != null && type.contains("atom+xml")) {
                                    result = template;
                                    break;
                                }
                            }
                        }
                        eventType = xpp.next();
                    }
                    if (result == null) result = fallbackTemplate;
                } catch (Exception e) {
                    Logger.e(e);
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final String finalResult = result;
                mainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(finalResult);
                    }
                });
            }
        }).start();
    }

    private Runnable xml_runnable = new Runnable() {
        public void run() {
            try {
                mDB = new MetadataDatabaseHelper(context);
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(true);
                XmlPullParser xpp = factory.newPullParser();
                HttpURLConnection connection = (HttpURLConnection) current_page.peekURL().openConnection();
                applyAuth(connection);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    lastRequestUnauthorized = true;
                    return;
                }
                int linkType = OPDSEntry.LINK_TYPE_UNKNOWN;
                xpp.setInput(connection.getInputStream(), null);
                int eventType = xpp.getEventType();
                StringBuilder heirarchy = new StringBuilder();
                Stack<Integer> heirarchy_length = new Stack<Integer>();
                current_page.clearNavigationalLinks();
                OPDSEntry current_entry = new OPDSEntry();
                while (eventType != XmlPullParser.END_DOCUMENT && !triggerStop) {
                    if(eventType == XmlPullParser.START_DOCUMENT) {
                        //Log.d(LOG_TAG,"Start document");
                    } else if(eventType == XmlPullParser.START_TAG) {
                        heirarchy_length.push(heirarchy.length());
                        heirarchy.append("/"+xpp.getName());
                        if (heirarchy.toString().endsWith("feed/entry")) {
                            current_entry = new OPDSEntry();
                        } else if (heirarchy.toString().endsWith("entry/link")) {
                            linkType = getLinkType(xpp);
                            if (linkType == OPDSEntry.LINK_TYPE_COVER) {
                                // Original, full-resolution cover.jpg -- always preferred over
                                // the "thumbnail" link below when the feed offers both.
                                byte[] cover = getThumb(current_entry.uuid, xpp.getAttributeValue(null, "href"), true);
                                if (cover != null) {
                                    current_entry.thumb = cover;
                                    current_entry.hasFullSizeCover = true;
                                }
                            } else if (linkType == OPDSEntry.LINK_TYPE_THUMB) {
                                if (!current_entry.hasFullSizeCover) {
                                    current_entry.thumb = getThumb(current_entry.uuid, xpp.getAttributeValue(null, "href"), false);
                                }
                            } else if (linkType == OPDSEntry.LINK_TYPE_ACQ) {
                                // Calibre lists one rel=".../acquisition" link per format the
                                // book is available in (EPUB, PDF, MOBI, an MP3/M4B audiobook,
                                // etc). This used to overwrite current_entry.href on every match,
                                // silently keeping only whichever format was listed last in the
                                // feed -- see OPDSEntry#formats.
                                OPDSEntry.Format fmt = new OPDSEntry.Format();
                                fmt.href = xpp.getAttributeValue(null, "href");
                                fmt.mimeType = xpp.getAttributeValue(null, "type");
                                String lengthAttr = xpp.getAttributeValue(null, "length");
                                if (lengthAttr != null) {
                                    try {
                                        fmt.fileSizeBytes = Long.parseLong(lengthAttr);
                                    } catch (NumberFormatException ignored) {
                                        fmt.fileSizeBytes = -1;
                                    }
                                }
                                current_entry.formats.add(fmt);
                                current_entry.linkType = linkType;
                                current_entry.href = fmt.href;
                            } else if (linkType != OPDSEntry.LINK_TYPE_UNKNOWN) {
                                current_entry.linkType = Integer.valueOf(linkType);
                                current_entry.href = xpp.getAttributeValue(null, "href");
                            }
                        }
                        else if (heirarchy.toString().endsWith("entry/content")) {
                            insideContentBlock = true;
                            contentBuffer = new StringBuilder();
                        } else if (insideContentBlock) {
                            // Cac the con nam trong content/div: br tach dong, cac the khoi tach doan.
                            String localName = xpp.getName();
                            if ("br".equalsIgnoreCase(localName)) {
                                contentBuffer.append('\n');
                            } else if (isBlockLevelTag(localName) && contentBuffer.length() > 0) {
                                contentBuffer.append("\n\n");
                            }
                        }
                        else if (heirarchy.toString().endsWith("feed/link") && xpp.getAttributeValue(null, "rel") != null) {
                            //Logger.d(xpp.getAttributeValue(null, "rel") + " = "+ xpp.getAttributeValue(null, "href"));
                            current_page.addNavigationalLink(xpp.getAttributeValue(null, "rel"), xpp.getAttributeValue(null, "href"));
                        }
                        //Log.d(LOG_TAG,"Now in: "+heirarchy.toString());
                    } else if(eventType == XmlPullParser.TEXT) {
                        if (heirarchy.toString().endsWith("entry/title")) {
                            current_entry.title = xpp.getText();
                        } else if (heirarchy.toString().endsWith("entry/author/name")) {
                            current_entry.author = xpp.getText();
                        } else if (heirarchy.toString().endsWith("entry/id") && xpp.getText().startsWith(uuid_identifier)) {
                            Cursor cs;
                            current_entry.uuid = xpp.getText().substring(uuid_identifier.length());
                            cs = mDB.getByUuid(new String[] {"_id" }, current_entry.uuid);
                            if (cs.moveToFirst()) {
                                current_entry.id = cs.getInt(0);
                            }
                        } else if (insideContentBlock) {
                            contentBuffer.append(xpp.getText());
                        } else if (heirarchy.toString().endsWith("entry/updated")) {
                            current_entry.updated = xpp.getText();
                        } else if (heirarchy.toString().endsWith("entry/published")) {
                            current_entry.published = xpp.getText();
                        }
                    } else if(eventType == XmlPullParser.END_TAG) {
                        if (heirarchy.toString().endsWith("entry/content")) {
                            insideContentBlock = false;
                            if (contentBuffer != null) {
                                parseContentBlock(contentBuffer.toString(), current_entry);
                                contentBuffer = null;
                            }
                        }
                        if (heirarchy.toString().endsWith("feed/entry")) {
                            if (current_entry.linkType == OPDSEntry.LINK_TYPE_ACQ) {
                                if (current_entry.id < 0 && current_entry.uuid != null && current_entry.author != null &&
                                        current_entry.title != null)  {
                                    // Truoc day chi luu uuid/author/title/tags/thumb -- moi field
                                    // khac ma parseContentBlock()/entry.published da phan tich
                                    // duoc tu feed (publisher/pubdate/series/rating/ngon ngu/dinh
                                    // danh/mo ta) bi am tham bo qua, nen 1 sach tai qua OPDS mat
                                    // het "Publication info" sau khi tai ve (xem
                                    // DeviceBookDetailActivity, doc thang tu metadata.calibre --
                                    // von duoc rebuild tu chinh cac cot SQLite nay). Dung ban day
                                    // du cua insertMetadata() de khong con mat du lieu nua, giong
                                    // cach GDriveBookEntryBuilder#ensureBareMetadataRegistered da
                                    // lam cho nguon GDrive.
                                    current_entry.id = mDB.insertMetadata(current_entry.uuid,null,
                                            current_entry.author,
                                            current_entry.title,
                                            current_entry.tags,
                                            current_entry.thumb,
                                            null,
                                            current_entry.publisher,
                                            current_entry.published,
                                            current_entry.series,
                                            current_entry.seriesIndex >= 0 ? current_entry.seriesIndex : null,
                                            current_entry.rating >= 0 ? current_entry.rating : null,
                                            current_entry.language,
                                            identifiersToJson(current_entry.identifiers),
                                            null,
                                            null);
                                    if (current_entry.summary != null && !current_entry.summary.isEmpty()) {
                                        mDB.saveComments(current_entry.uuid, current_entry.summary);
                                    }
                                } else if (current_entry.uuid != null) {
                                    // Sach nay da co dong trong TABLE_METADATA tu truoc (vd da
                                    // tai ve o mot lan duyet OPDS truoc khi ban va cham nay ton
                                    // tai, hoac don gian la da duoc thay qua feed nay roi) -- bo
                                    // sung cac field con thieu thay vi bo qua vinh vien, nhung
                                    // KHONG dung insertMetadata() (se putNull lpath, xoa mat
                                    // trang thai "da tai ve" cua sach neu no da co file tren may).
                                    mDB.backfillExtendedMetadataIfMissing(current_entry.uuid,
                                            current_entry.publisher,
                                            current_entry.published,
                                            current_entry.series,
                                            current_entry.seriesIndex >= 0 ? current_entry.seriesIndex : null,
                                            current_entry.rating >= 0 ? current_entry.rating : null,
                                            current_entry.language,
                                            identifiersToJson(current_entry.identifiers));
                                    if (current_entry.summary != null && !current_entry.summary.isEmpty()) {
                                        mDB.saveCommentsIfEmpty(current_entry.uuid, current_entry.summary);
                                    }
                                }
                                if (current_entry.id >= 0) {
                                     current_page.addBook(current_entry);
                                     // Bao cho subclass (OPDSBrowserFragment) biet ngay 1 sach
                                     // vua duoc them vao trang -- de UI co the hien sach do
                                     // (va anh bia vua tai xong) ngay lap tuc thay vi phai doi
                                     // onParseComplete() o cuoi, tuc la doi CA feed (moi anh
                                     // bia cua moi sach) tai xong. Goi dong bo (block thread
                                     // nay) de tranh doc/ghi dong thoi vao books_cursor --
                                     // OPDSPage/MatrixCursor khong an toan cho concurrent use.
                                     notifyBookAdded(current_entry);
                                }
                            } else if (current_entry.linkType == OPDSEntry.LINK_TYPE_CAT) {
                               Logger.d("OPDS catalog link: title=\"" + current_entry.title + "\" href=" + current_entry.href);
                               current_page.addCatalogLink(current_entry.title, current_entry.href);
                            }
                        }
                        heirarchy.setLength(heirarchy_length.pop());
                    }
                    eventType = xpp.next();

                }

            } catch (Exception e) {
                Logger.e(e);
                current_page = null;
            } finally {
                //Log.d(LOG_TAG, "XMLParsing Complete");
                if (lastRequestUnauthorized) {
                    lastRequestUnauthorized = false;
                    mainThreadHandler.sendEmptyMessage(XML_PARSE_UNAUTHORIZED);
                } else {
                    mainThreadHandler.sendEmptyMessage(XML_PARSE_COMPLETE);
                }
                mDB.close();
            }
        }
    };

    abstract void onStartParse();
    abstract void onParseComplete(OPDSPage p);
    /** Called on the main thread when the Content Server replied HTTP 401 to a feed/catalog
     * request -- i.e. it requires (or was given wrong) Basic Auth credentials. */
    abstract void onUnauthorized();

    /** Called on the main thread immediately after each book entry has been fully parsed and
     * added to the page being built (current_page.addBook() already ran) -- lets subclasses
     * show books as they arrive instead of waiting for onParseComplete() at the very end,
     * which only fires once the ENTIRE feed -- including every entry's cover image -- has
     * finished downloading. Default no-op so existing subclasses aren't forced to override
     * this. See notifyBookAdded() for why this call is synchronous from the background
     * thread's point of view. */
    void onBookAdded(OPDSPage page, OPDSEntry entry) {
    }

    /** Posts onBookAdded() to the main thread and BLOCKS this (background, xml_thread) thread
     * until it returns. This is deliberate, not an oversight: OPDSPage's book list is backed
     * by a MatrixCursor that is explicitly documented as "not safe for concurrent use", so the
     * background thread must not add another row (the very next entry) while the main thread
     * might still be reading the cursor to render the one just added. Blocking here costs at
     * most a fast in-process message round-trip (a few ms), negligible next to the network
     * request for the next cover image. */
    private void notifyBookAdded(final OPDSEntry entry) {
        if (triggerStop) return;
        final OPDSPage pageAtCallTime = current_page;
        final CountDownLatch latch = new CountDownLatch(1);
        mainThreadHandler.post(new Runnable() {
            public void run() {
                try {
                    onBookAdded(pageAtCallTime, entry);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    /** Phan tich khoi noi dung da gom cua entry/content: moi dong "Nhan: gia tri<br/>" (Tags,
     * Series, Rating, Publisher, Languages, Identifiers, ...) duoc dien vao current_entry tuong
     * ung, con phan con lai khong khop nhan nao (luon nam o cuoi, theo cach Calibre sinh feed)
     * duoc gop lai thanh Comments/mo ta sach. Xem prompt-fix-opds-metadata-parsing.md muc 3.4. */
    private void parseContentBlock(String raw, OPDSEntry entry) {
        if (raw == null) return;
        StringBuilder comments = new StringBuilder();
        boolean stillInLabelRows = true;
        for (String rawLine : raw.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (stillInLabelRows) {
                if (line.startsWith(rating_identifier)) {
                    entry.rating = countStarChars(line.substring(rating_identifier.length()));
                    continue;
                }
                if (line.startsWith(tags_identifier)) {
                    entry.tags = splitAndTrim(line.substring(tags_identifier.length()), ",");
                    continue;
                }
                if (line.startsWith(series_identifier)) {
                    applySeries(line.substring(series_identifier.length()).trim(), entry);
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String labelKey = stripDiacritics(line.substring(0, colon).trim().toLowerCase(Locale.ROOT));
                    String value = line.substring(colon + 1).trim();
                    String normalized = FIELD_LABEL_ALIASES.get(labelKey);
                    if (normalized != null && !value.isEmpty()) {
                        if ("publisher".equals(normalized)) {
                            entry.publisher = value;
                        } else if ("language".equals(normalized)) {
                            entry.language = value;
                        } else if ("identifiers".equals(normalized)) {
                            applyIdentifiers(value, entry);
                        }
                        continue;
                    }
                }
                // Dong dau tien khong khop bat ky nhan nao da biet -> day la diem bat dau cua
                // phan comments/mo ta (khong nhan), khong con dong "Nhan: gia tri" nao sau no
                // nua theo cach Calibre sinh feed.
                stillInLabelRows = false;
            }

            if (comments.length() > 0) comments.append("\n\n");
            comments.append(line);
        }
        if (comments.length() > 0) {
            entry.summary = comments.toString();
        }
    }

    private static int countStarChars(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == RATING_STAR_CHAR) count++;
        }
        return count;
    }

    private static String[] splitAndTrim(String csv, String sep) {
        String[] parts = csv.split(sep);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private void applySeries(String rest, OPDSEntry entry) {
        int bracket = rest.lastIndexOf('[');
        if (bracket > 0 && rest.endsWith("]")) {
            entry.series = rest.substring(0, bracket).trim();
            try {
                entry.seriesIndex = Double.parseDouble(rest.substring(bracket + 1, rest.length() - 1));
            } catch (NumberFormatException ignored) {
                entry.seriesIndex = -1;
            }
        } else {
            entry.series = rest;
        }
    }

    private void applyIdentifiers(String csv, OPDSEntry entry) {
        // Vi du gia tri thuc te: "isbn:9780000000000, asin:B000000000"
        for (String part : csv.split(",")) {
            String p = part.trim();
            int c = p.indexOf(':');
            if (c > 0) {
                String scheme = p.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String value = p.substring(c + 1).trim();
                if (!scheme.isEmpty() && !value.isEmpty()) {
                    entry.identifiers.put(scheme, value);
                }
            }
        }
    }

    /** Serializes an OPDSEntry's identifiers Map to the same JSON-object string convention
     * MetadataDatabaseHelper's identifiers_json column and DeviceBookMetadataWriter's
     * toIdentifiersJson() use ({"isbn":"...","asin":"..."}), or null if there's nothing to
     * save. */
    private static String identifiersToJson(java.util.Map<String, String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) return null;
        JSONObject ids = new JSONObject();
        try {
            for (java.util.Map.Entry<String, String> e : identifiers.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    ids.put(e.getKey().trim(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // JSONObject.put() only throws for a null key, already guarded above.
        }
        return ids.length() > 0 ? ids.toString() : null;
    }

    private int getLinkType(XmlPullParser xpp) throws XmlPullParserException, IOException  {
        String rel = xpp.getAttributeValue(null, "rel");
        String type = xpp.getAttributeValue(null, "type");
        if (rel != null && rel.endsWith("acquisition")) return OPDSEntry.LINK_TYPE_ACQ;
        if (rel != null && rel.endsWith("thumbnail")) return OPDSEntry.LINK_TYPE_THUMB;
        // Calibre's "http://opds-spec.org/image" link: the original cover.jpg, full resolution,
        // as opposed to ".../image/thumbnail" above which is caught by the check just above
        // this one (it ends in "thumbnail", not "/image").
        if (rel != null && rel.endsWith("/image")) return OPDSEntry.LINK_TYPE_COVER;
        //if (rel != null && rel.equals("next")) return LINK_TYPE_NEXT;
        if (type != null && type.endsWith("opds-catalog")) return OPDSEntry.LINK_TYPE_CAT;
        return OPDSEntry.LINK_TYPE_UNKNOWN;
    }

    public void setContext(Context context) {
        this.context = context;
    }
    // A real thumbnail (the old "http://opds-spec.org/image/thumbnail" link) is typically only
    // a few KB; a full-resolution cover.jpg is comfortably larger. Used to decide whether an
    // already-cached blob is an old low-res thumbnail that should be upgraded, without forcing
    // a network re-fetch of covers that have already been upgraded.
    private static final int FULL_COVER_MIN_BYTES = 8 * 1024;

    private byte[] getThumb(String uuid, String url, boolean isFullCover) {
        Cursor cs = mDB.getByUuid(new String[] {"thumbnail"}, uuid);
        try {
            if (cs.moveToFirst() && cs.getBlob(0) != null) {
                byte[] cached = cs.getBlob(0);
                if (!isFullCover || cached.length > FULL_COVER_MIN_BYTES) {
                    return cached;
                }
                // Otherwise: this is a full-cover request and the cached blob looks like an
                // old low-res thumbnail cached before this link was preferred -- fall through
                // and fetch/replace it with the real cover instead of returning it as-is.
            }

            try {
                if (triggerStop) return null;
                URL tmp_cs = new URL(current_page.peekURL().getProtocol(),
                                     current_page.peekURL().getHost(),
                                     current_page.peekURL().getPort(),
                                    url);
                if (triggerStop) return null;
                HttpURLConnection connection = (HttpURLConnection) tmp_cs.openConnection();
                applyAuth(connection);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) return null;
                // Don't rely on Content-Length: InputStream#read(byte[]) is only guaranteed to
                // read *up to* that many bytes in one call, not exactly that many -- for network
                // streams it commonly returns a partial read (especially for larger cover images
                // or a slow/flaky connection), which silently produced a truncated JPEG that
                // either failed to decode or rendered as a blank/partial thumbnail. Content-Length
                // can also be -1 entirely (e.g. the Content Server serving the cover with chunked
                // transfer-encoding), which would throw NegativeArraySizeException on
                // `new byte[content_length]` and get swallowed by the catch below, permanently
                // leaving that book without a cover. Read in a loop until EOF instead.
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = connection.getInputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
                in.close();
                connection.disconnect();
                byte[] thumb_bytes = buffer.toByteArray();
                if (BitmapFactory.decodeByteArray(thumb_bytes,0,thumb_bytes.length) != null) {
                    if (isFullCover) {
                        // Persist immediately so already-known books get upgraded to the
                        // full-resolution cover on disk too, not just for this session.
                        mDB.updateThumbnail(uuid, thumb_bytes);
                    }
                    return thumb_bytes;
                }
            } catch (Exception e) {
                Logger.e(e);
            }
            return null;
        } finally {
            cs.close();
        }
    }
    public OPDSPage getPreviousPage() {
        if (pages.isEmpty()) return null;
        if (isParsingXml()) triggerStop = true;
        current_page = pages.pop();
        return current_page;
    }

    /** Drops the currently tracked page without pushing it onto the back-stack, so the next
     * beginParsingXml() call starts a fresh navigation root instead of treating the dropped page
     * as the new page's parent. Used together with resetPages() when leaving the OPDS "choose a
     * library" screen: a library's default "latest books" page should not have a back-stack
     * parent, so that pageHasParent() correctly reports false there and the fragment knows to
     * treat Back/Up as "return to the library list" rather than "go to a parent page" -- see
     * OPDSBrowserFragment#openLibrary. */
    public void discardCurrentPage() {
        current_page = null;
    }

    public void resetPages() {
        if (pages != null ) {
            pages.clear();
        } else {
            pages = new Stack<OPDSPage>();

        }
    }
    public boolean pageHasParent() {
        return (pages != null && !pages.isEmpty());
    }
    public boolean isParsingXml() {
        if (xml_thread != null && xml_thread.isAlive()) return true;
        return false;
    }
    public boolean beginParsingXml(URL url, boolean more) {
        if (!isParsingXml()) {
            if (current_page == null)
                current_page = new OPDSPage(url);
            else {
                if (more)
                    current_page.addURL(url);
                else  {
                    pages.push(current_page);
                    current_page = new OPDSPage(url);
                }
            }
            xml_thread = new Thread(xml_runnable);
            xml_thread.start();
            return true;
        }
        return false;
    }
}
