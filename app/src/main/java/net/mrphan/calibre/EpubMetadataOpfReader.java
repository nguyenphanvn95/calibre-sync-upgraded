package net.mrphan.calibre;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Backs the "Get metadata from ebook" toolbar action (menu_get_metadata_from_ebook) in
 * MetadataEditorActivity: reads the Dublin Core + calibre {@code <meta>} fields out of an EPUB's
 * content.opf so the user doesn't have to retype them by hand when metadata.calibre is missing or
 * wrong for a book. Only EPUB is supported -- PDF/CBZ don't carry a Dublin Core metadata file the
 * way EPUB does (see EbookMetadataEmbedderFactory for the analogous per-format dispatch on the
 * write side; a future PDF/CBZ reader would plug in the same way there).
 * <p>
 * Deliberately a separate class from BookMetadataSniffer (used during the background disk scan
 * to grab a *cover-only* first impression of a brand new file): that one only needs title/author/
 * cover and is tuned for scanning speed over many files, while this needs the *full* field set
 * for a single user-initiated action. Both independently resolve content.opf via
 * META-INF/container.xml and parse it the same (non-namespace-aware) way, since qualified tag
 * names like "dc:title" and attribute names like "opf:file-as" are then readable as plain
 * literal strings -- see BookMetadataSniffer#newDocumentBuilder's comment for why the
 * DocumentBuilder itself is configured the way it is.
 */
final class EpubMetadataOpfReader {

    /** One field per BookMetadataForm slot this can fill in. Anything content.opf didn't have is
     * left null/empty here -- MetadataEditorActivity only overwrites a view when the
     * corresponding field here is non-empty, matching BookMetadataForm's own "don't invent
     * placeholder values" rule (see that class's class-level comment). */
    static final class Result {
        String title;
        String titleSort;
        final List<String> authors = new ArrayList<>();
        String authorSort;
        String publisher;
        String pubDate;
        final List<String> languages = new ArrayList<>();
        String series;
        String seriesIndex;
        final List<String> tags = new ArrayList<>();
        final Map<String, String> identifiers = new LinkedHashMap<>();
        String comments;
        /** Custom Column defs/values found via <meta name="calibre:user_metadata:#lookup">
         * tags (DATABASE_VERSION 13) -- see the bottom of parse() below. customColumnValues is
         * in the same canonical value_text form CustomColumnSync/TABLE_CUSTOM_COLUMN_VALUES use
         * (MULTI_JOIN-joined for is_multiple), matching BookMetadataForm's convention. */
        final List<CustomColumnSync.ColumnDef> customColumnDefs = new ArrayList<>();
        final Map<String, String> customColumnValues = new LinkedHashMap<>();
    }

    /** Returns null (never throws) if the file can't be read as a valid EPUB/.opf -- the caller
     * shows a single generic "couldn't read metadata" toast either way, so there's no need to
     * distinguish the failure reason here. */
    static Result read(File epubFile) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return null;
            String opfPath;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                Document containerDoc = newDocumentBuilder().parse(is);
                NodeList rootfiles = containerDoc.getElementsByTagName("rootfile");
                if (rootfiles.getLength() == 0) return null;
                Node fullPathAttr = rootfiles.item(0).getAttributes().getNamedItem("full-path");
                if (fullPathAttr == null) return null;
                opfPath = fullPathAttr.getNodeValue();
            }
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) return null;
            Document opf;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opf = newDocumentBuilder().parse(is);
            }
            return parse(opf);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static Result parse(Document opf) {
        Result r = new Result();

        r.title = firstText(opf, "dc:title", "title");

        NodeList creators = elementsByAnyTagName(opf, "dc:creator", "creator");
        for (int i = 0; i < creators.getLength(); i++) {
            Node n = creators.item(i);
            String name = textOf(n);
            if (!isEmpty(name)) r.authors.add(name);
            if (r.authorSort == null) {
                Node fileAs = attr(n, "opf:file-as", "file-as");
                if (fileAs != null && !isEmpty(fileAs.getNodeValue())) {
                    r.authorSort = fileAs.getNodeValue().trim();
                }
            }
        }

        r.publisher = firstText(opf, "dc:publisher", "publisher");

        String rawDate = firstText(opf, "dc:date", "date");
        // Trim a full ISO timestamp down to the plain YYYY-MM-DD the pubdate field's hint asks
        // for (see activity_metadata_editor.xml / R.string.metadata_editor_pubdate) -- a
        // calibre-produced .opf writes a full "2012-04-22T20:58:16+00:00" timestamp, but the
        // form only wants the date part.
        if (rawDate != null && rawDate.length() >= 10) {
            r.pubDate = rawDate.substring(0, 10);
        } else {
            r.pubDate = rawDate;
        }

        NodeList languages = elementsByAnyTagName(opf, "dc:language", "language");
        for (int i = 0; i < languages.getLength(); i++) {
            String v = textOf(languages.item(i));
            if (!isEmpty(v)) r.languages.add(v);
        }

        NodeList subjects = elementsByAnyTagName(opf, "dc:subject", "subject");
        for (int i = 0; i < subjects.getLength(); i++) {
            String v = textOf(subjects.item(i));
            if (!isEmpty(v)) r.tags.add(v);
        }

        r.comments = firstText(opf, "dc:description", "description");

        NodeList identifiers = elementsByAnyTagName(opf, "dc:identifier", "identifier");
        for (int i = 0; i < identifiers.getLength(); i++) {
            Node n = identifiers.item(i);
            Node scheme = attr(n, "opf:scheme", "scheme");
            String v = textOf(n);
            if (scheme != null && !isEmpty(scheme.getNodeValue()) && !isEmpty(v)) {
                r.identifiers.put(scheme.getNodeValue().trim().toLowerCase(Locale.ROOT), v);
            }
        }

        // calibre:title_sort / calibre:series / calibre:series_index don't have their own Dublin
        // Core element -- calibre writes them as <meta name="..." content="..."/> instead.
        NodeList metas = opf.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Node n = metas.item(i);
            Node nameAttr = n.getAttributes().getNamedItem("name");
            if (nameAttr == null) continue;
            String name = nameAttr.getNodeValue();
            Node contentAttr = n.getAttributes().getNamedItem("content");
            String content = contentAttr == null ? null : contentAttr.getNodeValue();
            if (isEmpty(content)) continue;
            if ("calibre:title_sort".equals(name)) {
                r.titleSort = content.trim();
            } else if ("calibre:series".equals(name)) {
                r.series = content.trim();
            } else if ("calibre:series_index".equals(name)) {
                r.seriesIndex = content.trim();
            }
        }

        // Custom Column defs/values: Calibre embeds each one as its own
        // <meta name="calibre:user_metadata:#lookup" content="{...JSON dict incl. "#value#"...}"/>
        // tag -- one tag per column, rather than Wireless Device sync's single combined
        // "user_metadata" object. Rebuild that combined shape here so CustomColumnSync's
        // existing parser (parseFromWireJson(), shared with insertJSON()) can be reused as-is
        // instead of duplicating its per-datatype value-normalization logic a second time.
        JSONObject userMetadata = new JSONObject();
        for (int i = 0; i < metas.getLength(); i++) {
            Node n = metas.item(i);
            Node nameAttr = n.getAttributes().getNamedItem("name");
            if (nameAttr == null) continue;
            String name = nameAttr.getNodeValue();
            if (name == null || !name.startsWith("calibre:user_metadata:")) continue;
            String lookupName = name.substring("calibre:user_metadata:".length());
            Node contentAttr = n.getAttributes().getNamedItem("content");
            String content = contentAttr == null ? null : contentAttr.getNodeValue();
            if (isEmpty(content)) continue;
            try {
                userMetadata.put(lookupName, new JSONObject(content));
            } catch (JSONException malformed) {
                // 1 cột đọc lỗi (JSON hỏng trong riêng thẻ <meta> này) không được làm hỏng các
                // cột Custom Column khác hay phần Dublin Core đã đọc được ở trên.
                Logger.e(malformed);
            }
        }
        if (userMetadata.length() > 0) {
            try {
                JSONObject synthetic = new JSONObject();
                synthetic.put("user_metadata", userMetadata);
                CustomColumnSync.ParsedCustomColumns parsed = CustomColumnSync.parseFromWireJson(synthetic);
                r.customColumnDefs.addAll(parsed.defs);
                for (CustomColumnSync.ColumnValue v : parsed.values) {
                    r.customColumnValues.put(v.lookupName, v.valueText);
                }
            } catch (JSONException e) {
                Logger.e(e);
            }
        }

        return r;
    }

    // ---- small DOM helpers -----------------------------------------------------------------

    private static String firstText(Document doc, String... tagNames) {
        NodeList nodes = elementsByAnyTagName(doc, tagNames);
        return nodes.getLength() > 0 ? textOf(nodes.item(0)) : null;
    }

    /** Real-world calibre/Sigil-produced .opf files use the prefixed tag name ("dc:title"); try
     * that first and fall back to the bare local name ("title") for hand-written or older files
     * -- mirrors BookMetadataSniffer#firstNonEmptyTagText's same fallback for the same reason. */
    private static NodeList elementsByAnyTagName(Document doc, String... tagNames) {
        for (String tag : tagNames) {
            NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0) return nodes;
        }
        return doc.getElementsByTagName(tagNames[tagNames.length - 1]);
    }

    private static String textOf(Node n) {
        String t = n.getTextContent();
        return t == null ? null : t.trim();
    }

    private static Node attr(Node n, String... names) {
        for (String name : names) {
            Node a = n.getAttributes().getNamedItem(name);
            if (a != null) return a;
        }
        return null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Same XXE-hardening as BookMetadataSniffer#newDocumentBuilder -- see that method's
        // comment for why the setFeature() call is wrapped in a try/catch instead of asserting.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception unsupported) {
            Logger.d("DocumentBuilderFactory does not support disallow-doctype-decl, continuing without it");
        }
        return factory.newDocumentBuilder();
    }
}
