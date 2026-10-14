package net.mrphan.calibre;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Nhúng metadata + (tùy chọn) bìa mới vào chính file .epub, bằng cách viết lại content.opf và
 * (nếu có bìa mới) entry ảnh bìa trong cùng file zip, giữ nguyên 100% mọi entry khác byte-for-byte
 * (chương sách, CSS, font, ảnh khác...). Không dùng thư viện epub ngoài -- tái sử dụng đúng cách
 * BookMetadataSniffer đã dùng để ĐỌC (container.xml -> tìm .opf -> DocumentBuilder) cho việc GHI.
 * <p>
 * Quan trọng: không bao giờ chèn XML comment ({@code <!-- ... -->}) vào content.opf khi ghi lại
 * -- nếu nội dung đó vô tình chứa "--" thì file XML sẽ không hợp lệ, có thể khiến nhiều epub
 * reader từ chối mở file. Class này chỉ sửa nội dung các thẻ trong {@code <metadata>}, không
 * chèn comment nào cả. */
final class EpubMetadataEmbedder implements EbookMetadataEmbedder {

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private static final String OPF_NS = "http://www.idpf.org/2007/opf";

    @Override
    public boolean supports(String extension) {
        return "epub".equalsIgnoreCase(extension);
    }

    @Override
    public void embed(File bookFile, BookMetadataForm form) throws Exception {
        File tmp = new File(bookFile.getParentFile(), bookFile.getName() + ".tmp");
        try (ZipFile zip = new ZipFile(bookFile)) {
            String opfPath = readOpfPath(zip);
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Document opfDoc = parseEntry(zip, opfPath);
            String coverHref = applyMetadataToOpf(opfDoc, form);
            byte[] newOpfBytes = serializeXml(opfDoc);

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry outEntry = new ZipEntry(entry.getName());
                    zos.putNextEntry(outEntry);
                    if (entry.getName().equals(opfPath)) {
                        zos.write(newOpfBytes);
                    } else if (form.newCoverBytes != null && coverHref != null
                            && entry.getName().equals(resolveHref(opfDir, coverHref))) {
                        zos.write(form.newCoverBytes);
                    } else {
                        try (InputStream in = zip.getInputStream(entry)) {
                            copy(in, zos);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
        if (!tmp.renameTo(bookFile)) {
            throw new IOException("Could not replace original epub file (rename failed)");
        }
    }

    private String readOpfPath(ZipFile zip) throws Exception {
        ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
        if (containerEntry == null) throw new IOException("Missing META-INF/container.xml");
        Document doc = parseEntry(zip, "META-INF/container.xml");
        NodeList rootfiles = doc.getElementsByTagName("rootfile");
        if (rootfiles.getLength() == 0) throw new IOException("No rootfile in container.xml");
        Node fullPathAttr = rootfiles.item(0).getAttributes().getNamedItem("full-path");
        if (fullPathAttr == null) throw new IOException("rootfile missing full-path attribute");
        return fullPathAttr.getNodeValue();
    }

    private Document parseEntry(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("Missing entry: " + path);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Xerces-only feature -- see BookMetadataSniffer#newDocumentBuilder for why this is
            // best-effort on the platform's built-in parser.
        }
        try (InputStream is = zip.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(is);
        }
    }

    /** Ghi form vào {@code <metadata>} của content.opf theo chuẩn Dublin Core (dc:title,
     * dc:creator, dc:publisher, dc:date, dc:language, dc:description, dc:identifier, dc:subject),
     * cộng thêm {@code <meta name="calibre:series"/>}/{@code <meta name="calibre:series_index"/>}
     * theo đúng quy ước Calibre desktop hay dùng cho series (để Calibre desktop đọc lại được).
     * @return href ảnh bìa hiện tại trong manifest (để embed() biết entry nào cần thay byte ảnh
     * mới), hoặc null nếu không tìm thấy cover-image trong manifest. */
    private String applyMetadataToOpf(Document doc, BookMetadataForm form) {
        Element metadata = firstElement(doc.getElementsByTagName("metadata"));
        if (metadata == null) {
            // content.opf không hợp lệ chút nào (thiếu <metadata>) -- không có gì để sửa an
            // toàn, coi như không tìm thấy bìa để thay và để nguyên phần còn lại của file.
            return null;
        }

        setOrCreateText(doc, metadata, DC_NS, "dc:title", form.title);
        setOrCreateText(doc, metadata, DC_NS, "dc:publisher", form.publisher);
        setOrCreateText(doc, metadata, DC_NS, "dc:description", form.comments);
        if (!isEmpty(form.pubDate)) {
            setOrCreateText(doc, metadata, DC_NS, "dc:date", form.pubDate);
        }

        // dc:creator có thể có nhiều thẻ (nhiều tác giả) -- xóa hết thẻ cũ rồi thêm lại theo form.
        removeAllTags(metadata, "dc:creator");
        for (String author : form.authors) {
            if (isEmpty(author)) continue;
            Element e = doc.createElementNS(DC_NS, "dc:creator");
            e.setAttributeNS(OPF_NS, "opf:role", "aut");
            e.setTextContent(author);
            metadata.appendChild(e);
        }

        removeAllTags(metadata, "dc:language");
        for (String lang : form.languages) {
            if (isEmpty(lang)) continue;
            Element e = doc.createElementNS(DC_NS, "dc:language");
            e.setTextContent(lang);
            metadata.appendChild(e);
        }

        // dc:subject = tags trong chuẩn EPUB/Dublin Core.
        removeAllTags(metadata, "dc:subject");
        for (String tag : form.tags) {
            if (isEmpty(tag)) continue;
            Element e = doc.createElementNS(DC_NS, "dc:subject");
            e.setTextContent(tag);
            metadata.appendChild(e);
        }

        setCalibreSeriesMeta(doc, metadata, form);
        setIdentifiers(doc, metadata, form);

        return findCoverHref(doc);
    }

    /** Tìm/tạo đúng 1 thẻ localName trong namespace ns bên trong metadata rồi set text content
     * thành value; nếu value rỗng thì xóa hẳn thẻ đi (không để lại thẻ trống). */
    private void setOrCreateText(Document doc, Element metadata, String ns, String qualifiedName, String value) {
        String localName = qualifiedName.contains(":")
                ? qualifiedName.substring(qualifiedName.indexOf(':') + 1) : qualifiedName;
        NodeList existing = metadata.getElementsByTagNameNS(ns, localName);
        if (isEmpty(value)) {
            removeAllTags(metadata, qualifiedName);
            return;
        }
        if (existing.getLength() > 0) {
            // Giữ nguyên thẻ đầu tiên, set lại text content; xóa các thẻ trùng lặp còn lại (nếu
            // OPF gốc có nhiều hơn 1 thẻ cùng loại này, điều không nên xảy ra với các field
            // single-value như title/publisher/description/date).
            Element first = (Element) existing.item(0);
            first.setTextContent(value);
            for (int i = existing.getLength() - 1; i >= 1; i--) {
                Node n = existing.item(i);
                if (n.getParentNode() != null) n.getParentNode().removeChild(n);
            }
        } else {
            Element e = doc.createElementNS(ns, qualifiedName);
            e.setTextContent(value);
            metadata.appendChild(e);
        }
    }

    /** Xóa mọi thẻ con trực tiếp của metadata có tên qualifiedName (ví dụ "dc:creator"). */
    private void removeAllTags(Element metadata, String qualifiedName) {
        NodeList children = metadata.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && (qualifiedName.equals(n.getNodeName()) || qualifiedName.equals(((Element) n).getTagName()))) {
                metadata.removeChild(n);
            }
        }
    }

    /** Series ghi theo quy ước Calibre: {@code <meta name="calibre:series" content="..."/>} và
     * {@code <meta name="calibre:series_index" content="..."/>}, xóa 2 thẻ meta này trước khi
     * ghi lại (nếu form.series rỗng thì chỉ xóa, không tạo lại). */
    private void setCalibreSeriesMeta(Document doc, Element metadata, BookMetadataForm form) {
        removeCalibreMeta(metadata, "calibre:series");
        removeCalibreMeta(metadata, "calibre:series_index");
        if (isEmpty(form.series)) return;
        appendCalibreMeta(doc, metadata, "calibre:series", form.series);
        if (!isEmpty(form.seriesIndex)) {
            appendCalibreMeta(doc, metadata, "calibre:series_index", form.seriesIndex.trim());
        }
    }

    private void removeCalibreMeta(Element metadata, String metaName) {
        NodeList metas = metadata.getElementsByTagName("meta");
        for (int i = metas.getLength() - 1; i >= 0; i--) {
            Node n = metas.item(i);
            Node nameAttr = n.getAttributes().getNamedItem("name");
            if (nameAttr != null && metaName.equals(nameAttr.getNodeValue())) {
                n.getParentNode().removeChild(n);
            }
        }
    }

    private void appendCalibreMeta(Document doc, Element metadata, String metaName, String content) {
        Element meta = doc.createElement("meta");
        meta.setAttribute("name", metaName);
        meta.setAttribute("content", content);
        metadata.appendChild(meta);
    }

    /** Identifiers: xóa hết dc:identifier hiện có rồi ghi lại theo form.identifiers. Giữ nguyên
     * identifier gốc dùng làm unique-identifier của package (thường là BookID/uuid) nếu form
     * không có key trùng tên -- form không có ô sửa riêng cho identifier "uuid" đó, chỉ thêm
     * các identifier người dùng khai báo (isbn, asin, ...). */
    private void setIdentifiers(Document doc, Element metadata, BookMetadataForm form) {
        // Tìm id của package's unique-identifier (thuộc tính unique-identifier trên <package>)
        // để không xóa nhầm nó -- các dc:identifier khác (isbn, asin...) mới thuộc phạm vi sửa
        // của form.
        String packageUniqueIdRef = findPackageUniqueIdentifierRef(doc);

        NodeList identifiers = metadata.getElementsByTagNameNS(DC_NS, "identifier");
        for (int i = identifiers.getLength() - 1; i >= 0; i--) {
            Element el = (Element) identifiers.item(i);
            String idAttr = el.getAttribute("id");
            boolean isPackageUniqueId = packageUniqueIdRef != null && packageUniqueIdRef.equals(idAttr);
            if (!isPackageUniqueId) {
                metadata.removeChild(el);
            }
        }
        for (java.util.Map.Entry<String, String> e : form.identifiers.entrySet()) {
            String scheme = e.getKey();
            String value = e.getValue();
            if (isEmpty(scheme) || isEmpty(value)) continue;
            Element el = doc.createElementNS(DC_NS, "dc:identifier");
            el.setAttributeNS(OPF_NS, "opf:scheme", scheme.toUpperCase(java.util.Locale.ROOT));
            el.setTextContent(value);
            metadata.appendChild(el);
        }
    }

    private String findPackageUniqueIdentifierRef(Document doc) {
        NodeList packages = doc.getElementsByTagName("package");
        if (packages.getLength() == 0) return null;
        Node attr = packages.item(0).getAttributes().getNamedItem("unique-identifier");
        return attr != null ? attr.getNodeValue() : null;
    }

    /** Tìm href ảnh bìa hiện tại trong manifest, theo cùng thứ tự ưu tiên
     * BookMetadataSniffer#extractEpubCover đã dùng để ĐỌC: (1) meta name="cover" trỏ tới 1
     * manifest item id, (2) manifest item có properties="cover-image" (EPUB3). */
    private String findCoverHref(Document doc) {
        String coverItemId = null;
        NodeList metas = doc.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Node n = metas.item(i);
            Node nameAttr = n.getAttributes().getNamedItem("name");
            if (nameAttr != null && "cover".equals(nameAttr.getNodeValue())) {
                Node contentAttr = n.getAttributes().getNamedItem("content");
                if (contentAttr != null) coverItemId = contentAttr.getNodeValue();
                break;
            }
        }
        NodeList items = doc.getElementsByTagName("item");
        if (coverItemId != null) {
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                Node idAttr = n.getAttributes().getNamedItem("id");
                if (idAttr != null && coverItemId.equals(idAttr.getNodeValue())) {
                    Node hrefAttr = n.getAttributes().getNamedItem("href");
                    if (hrefAttr != null) return hrefAttr.getNodeValue();
                }
            }
        }
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            Node props = n.getAttributes().getNamedItem("properties");
            if (props != null && props.getNodeValue().contains("cover-image")) {
                Node hrefAttr = n.getAttributes().getNamedItem("href");
                if (hrefAttr != null) return hrefAttr.getNodeValue();
            }
        }
        return null;
    }

    private static String resolveHref(String opfDir, String href) {
        return opfDir + href;
    }

    private byte[] serializeXml(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(bos));
        return bos.toByteArray();
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static Element firstElement(NodeList list) {
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
