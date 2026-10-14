package net.mrphan.calibre;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Quickly reads title/author/cover from a book file just found on disk that isn't in SQLite yet
 * (e.g. the user copied it directly into the book folder outside of a Calibre desktop
 * connection). Only reads enough to show something reasonable in Device Library right away --
 * this is not a full EPUB/PDF reader (that already exists in the reader/ package for actually
 * opening books; not reused here to avoid pulling the heavy Readium dependency into a simple
 * background scan task).
 * <p>
 * Cover extraction follows the same per-format dispatch calibre desktop's own metadata readers
 * use (epub/pdf/cbz/... each parsed with its own logic), reimplemented here in Java on top of
 * the same ZipFile/DocumentBuilder machinery already used for title/author, rather than porting
 * calibre's Python readers directly.
 */
final class BookMetadataSniffer {

    static final class Result {
        final String title;
        final String author;
        /** Raw cover image bytes (any format the source embedded it in -- JPEG/PNG/GIF), or null
         * if no cover could be found/rendered for this file. Never written to disk as-is --
         * CoverManager#saveCover() normalizes it to JPEG. */
        final byte[] coverBytes;
        Result(String title, String author, byte[] coverBytes) {
            this.title = title;
            this.author = author;
            this.coverBytes = coverBytes;
        }
    }

    static Result sniff(File file) {
        String name = file.getName();
        String lower = name.toLowerCase();
        String fallbackTitle = stripTrailingHashSuffix(stripExtension(name));
        if (lower.endsWith(".epub")) {
            Result r = sniffEpub(file, fallbackTitle);
            if (r != null) return r;
        } else if (lower.endsWith(".pdf")) {
            byte[] cover = extractPdfCover(file);
            return new Result(fallbackTitle, "", cover);
        } else if (lower.endsWith(".cbz")) {
            byte[] cover = extractCbzCover(file);
            return new Result(fallbackTitle, "", cover);
        }
        // Other formats: use the filename as the title, leave the author blank, no cover. The
        // user can correct metadata later through BookInfoDialogFragment if the app supports
        // editing it (check that feature exists before assuming so).
        return new Result(fallbackTitle, "", null);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Some sources (e.g. "tóm tắt sách" summary apps) name their exported files like
     * "Sao chúng ta lại ngủ_ (41a2744c).epub", appending a short hex id in parentheses purely
     * for their own de-duplication on disk -- it carries no meaning for the reader and isn't
     * part of the book's actual title. When the filename is used as the fallback title (EPUB
     * has no dc:title, or the file isn't an EPUB at all), that trailing id would otherwise leak
     * straight into Device Library's book title. Strip it here so it never shows up: matches an
     * optional trailing underscore/space, then "(<6-12 hex chars>)" at the very end of the
     * string, e.g. "Foo_ (41a2744c)" -> "Foo", "Foo (a1b2c3d4)" -> "Foo". Left untouched if the
     * parenthesized part isn't pure hex (e.g. a real "(Unabridged)" or "(Vol. 2)" suffix) or
     * would strip the whole title down to nothing.
     */
    static String stripTrailingHashSuffix(String title) {
        if (title == null) return null;
        String stripped = title.replaceAll(
                "[ _]*\\([0-9a-fA-F]{6,12}\\)\\s*$", "").trim();
        return stripped.isEmpty() ? title : stripped;
    }

    /** EPUB is a zip file. Structure: META-INF/container.xml points at the real *.opf path
     * (usually OEBPS/content.opf or similar); that .opf file contains dc:title/dc:creator per
     * the Dublin Core standard every valid EPUB must have, plus the cover reference (see
     * extractEpubCover). */
    private static Result sniffEpub(File file, String fallbackTitle) {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return null;
            String opfPath = readOpfPathFromContainer(zip, containerEntry);
            if (opfPath == null) return null;
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) return null;
            Document opfDoc;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opfDoc = newDocumentBuilder().parse(is);
            }
            String title = stripTrailingHashSuffix(firstNonEmptyTagText(opfDoc, "dc:title", "title"));
            String author = firstNonEmptyTagText(opfDoc, "dc:creator", "creator");
            String opfDir = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            byte[] cover = extractEpubCover(zip, opfDoc, opfDir);
            return new Result(
                    title != null && !title.isEmpty() ? title : fallbackTitle,
                    author != null ? author : "", cover);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static String readOpfPathFromContainer(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = newDocumentBuilder().parse(is);
            NodeList rootfiles = doc.getElementsByTagName("rootfile");
            if (rootfiles.getLength() == 0) return null;
            return rootfiles.item(0).getAttributes().getNamedItem("full-path").getNodeValue();
        }
    }

    /** Finds the cover image in an EPUB, following the same priority order calibre desktop's
     * EPUB reader uses:
     * (1) {@code <meta name="cover" content="manifest-id"/>} in {@code <metadata>} -> resolve
     *     that id in {@code <manifest>} (the widely-supported EPUB2 convention).
     * (2) A {@code <manifest>} item with {@code properties="cover-image"} (EPUB3).
     * The {@code <guide><reference type="cover" .../></guide>} fallback (whose href usually
     * points at an HTML cover *page*, not an image directly) is deliberately not implemented --
     * (1) and (2) already cover the large majority of real-world EPUBs, and resolving an HTML
     * cover page into the actual image it embeds would add a second, heavier parsing pass for
     * a rare case. Returns null (no throw) if no cover is found -- the book is still added
     * normally, just without a cover until the source file changes or the user sets one. */
    private static byte[] extractEpubCover(ZipFile zip, Document opfDoc, String opfDir) {
        String coverItemId = null;
        NodeList metas = opfDoc.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Node n = metas.item(i);
            Node nameAttr = n.getAttributes().getNamedItem("name");
            if (nameAttr != null && "cover".equals(nameAttr.getNodeValue())) {
                Node contentAttr = n.getAttributes().getNamedItem("content");
                if (contentAttr != null) coverItemId = contentAttr.getNodeValue();
                break;
            }
        }
        NodeList items = opfDoc.getElementsByTagName("item");
        String href = null;
        if (coverItemId != null) {
            href = findManifestHrefById(items, coverItemId);
        }
        if (href == null) {
            // EPUB3: item has properties="cover-image"
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                Node props = n.getAttributes().getNamedItem("properties");
                if (props != null && props.getNodeValue().contains("cover-image")) {
                    Node hrefAttr = n.getAttributes().getNamedItem("href");
                    if (hrefAttr != null) href = hrefAttr.getNodeValue();
                    break;
                }
            }
        }
        if (href == null) return null;
        // href in the .opf is relative to the folder containing the .opf inside the zip (not
        // the zip root) -- an easy mistake to make; resolve against opfDir, same as
        // readOpfPathFromContainer's caller already does for the .opf path itself.
        ZipEntry imgEntry = zip.getEntry(opfDir + href);
        if (imgEntry == null) return null;
        try (InputStream is = zip.getInputStream(imgEntry)) {
            return readAllBytes(is);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static String findManifestHrefById(NodeList items, String id) {
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            Node idAttr = n.getAttributes().getNamedItem("id");
            if (idAttr != null && id.equals(idAttr.getNodeValue())) {
                Node hrefAttr = n.getAttributes().getNamedItem("href");
                return hrefAttr != null ? hrefAttr.getNodeValue() : null;
            }
        }
        return null;
    }

    /** Renders the first page of the PDF as a bitmap using android.graphics.pdf.PdfRenderer
     * (built into the platform since API 21, no external library needed) -- the same fallback
     * calibre desktop's own PDF metadata reader uses to synthesize a cover for PDFs that don't
     * embed a dedicated cover image. Scaled down to a modest fixed width so the cached cover
     * file stays small, rather than keeping the page's native resolution. Returns null (never
     * throws) on any failure -- a single corrupt/unreadable/blank-page PDF must not interrupt
     * the rest of a scan. */
    private static byte[] extractPdfCover(File file) {
        android.os.ParcelFileDescriptor pfd = null;
        android.graphics.pdf.PdfRenderer renderer = null;
        android.graphics.pdf.PdfRenderer.Page page = null;
        try {
            pfd = android.os.ParcelFileDescriptor.open(
                    file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new android.graphics.pdf.PdfRenderer(pfd);
            if (renderer.getPageCount() == 0) return null;
            page = renderer.openPage(0);
            if (page.getWidth() <= 0 || page.getHeight() <= 0) return null;
            int targetWidth = 400;
            int targetHeight = (int) ((float) page.getHeight() / page.getWidth() * targetWidth);
            if (targetHeight <= 0) return null;
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null,
                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Logger.e(e);
            return null;
        } finally {
            if (page != null) try { page.close(); } catch (Exception ignored) {}
            if (renderer != null) try { renderer.close(); } catch (Exception ignored) {}
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    /** CBZ cover convention: the first image (alphabetically by entry name) in the archive,
     * matching how comic-book readers/libraries generally pick a CBZ's cover. */
    private static byte[] extractCbzCover(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            List<ZipEntry> imageEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName().toLowerCase();
                if (!e.isDirectory() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) {
                    imageEntries.add(e);
                }
            }
            if (imageEntries.isEmpty()) return null;
            imageEntries.sort((a, b) -> a.getName().compareTo(b.getName()));
            try (InputStream is = zip.getInputStream(imageEntries.get(0))) {
                return readAllBytes(is);
            }
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static String firstNonEmptyTagText(Document doc, String... tagNames) {
        for (String tag : tagNames) {
            NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
                String text = nodes.item(0).getTextContent().trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entity resolution to avoid XXE when parsing an .epub file that isn't
        // fully trusted (a book the user added to the folder themselves).
        //
        // NOTE: "http://apache.org/xml/features/disallow-doctype-decl" is a Xerces-specific
        // feature URI. On a real Android device DocumentBuilderFactory.newInstance() resolves to
        // the platform's own built-in parser (not Xerces), which does NOT recognize this feature
        // and throws ParserConfigurationException as soon as setFeature() is called -- on every
        // single call, for every EPUB. Since that exception propagates out of here and sniffEpub()
        // catches it as a generic Exception (returning null -> falling back to filename-only, no
        // cover), this was silently breaking EPUB title/author/cover extraction for 100% of books
        // on-device, even though it worked fine in a desktop JVM test. Make it best-effort: try to
        // harden against DOCTYPE-based XXE, but don't let an unsupported feature name break parsing
        // entirely on platforms whose parser doesn't support it.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception unsupported) {
            Logger.d("DocumentBuilderFactory does not support disallow-doctype-decl, continuing without it");
        }
        return factory.newDocumentBuilder();
    }
}
