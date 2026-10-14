package net.mrphan.calibre;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class OPDSEntry implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final int LINK_TYPE_NEXT = 4;
	public static final int LINK_TYPE_THUMB = 3;
	public static final int LINK_TYPE_ACQ = 2;
	public static final int LINK_TYPE_CAT = 1;
	public static final int LINK_TYPE_UNKNOWN = 0;
	/** Calibre's OPDS "http://opds-spec.org/image" link -- the original, full-resolution
	 * cover.jpg from the book's own library folder, as opposed to the much smaller/blurrier
	 * "http://opds-spec.org/image/thumbnail" link (LINK_TYPE_THUMB). */
	public static final int LINK_TYPE_COVER = 5;
	public int id = -1;
	public String uuid;
	public String title;
	public String author;
	public String href;
	public byte[] thumb;
	/** Whether {@link #thumb} was already populated from the full-resolution LINK_TYPE_COVER
	 * link, so a subsequent (lower quality) LINK_TYPE_THUMB link for the same entry doesn't
	 * overwrite it. */
	public boolean hasFullSizeCover = false;
	public String[] tags;
	public int linkType;

	/** Book summary/description, from &lt;entry&gt;&lt;summary&gt;. Distinct from the
	 * "TAGS: a,b,c" text Calibre also stuffs into &lt;entry&gt;&lt;content&gt;&lt;div&gt; (see
	 * OPDSLoader) -- that one is parsed separately into {@link #tags}, so the two don't
	 * conflict. Null if the feed doesn't include a summary for this entry. */
	public String summary;

	/** Last-updated timestamp, raw ISO-8601 string from &lt;entry&gt;&lt;updated&gt; (Atom).
	 * Shown as-is (or lightly reformatted) in the UI -- not parsed into a Date since nothing
	 * currently sorts by it. Null if the feed doesn't include it. */
	public String updated;

	/** Book language, e.g. "vi" or "en", from &lt;dc:language&gt; if Calibre's feed exposes it.
	 * May be null. */
	public String language;

	/** Publisher name, from &lt;dc:publisher&gt;&lt;name&gt; if present. May be null. */
	public String publisher;

	/** Publication date of the book (Atom &lt;published&gt;, distinct from {@link #updated} which
	 * is the *feed entry's* last-modified time). Raw ISO-8601 string, same convention as
	 * {@link #updated} -- format with OPDSFormatUtils.formatIsoDatePrefix() at display time. May
	 * be null if the feed doesn't include it. */
	public String published;

	/** Series name, if this book belongs to one. Calibre-Web/Content Server feeds vary in how
	 * they expose this (some via a &lt;category scheme="...series..."&gt; entry, some inside the
	 * &lt;content&gt; div alongside "TAGS: "). See OPDSLoader for the actual extraction logic --
	 * verify the real tag/scheme name against a live feed from the target server before assuming
	 * this always populates. */
	public String series;

	/** 1-based position of this book within {@link #series}, e.g. 2 for "Book 2" of a series.
	 * -1 if unknown or this book has no series. */
	public double seriesIndex = -1;

	/** Star rating, typically 0-5 (Calibre stores 0-10 internally as "half-star" units in some
	 * exports -- normalize to a 0-5 scale when parsing, see OPDSLoader). -1 if not provided. */
	public double rating = -1;

	/** Identifiers such as ISBN, ASIN, DOI... key = identifier scheme (lowercase, e.g. "isbn"),
	 * value = the identifier string. Empty map (never null) if the feed provides none. */
	public java.util.Map<String, String> identifiers = new java.util.HashMap<String, String>();

	/** One acquisition (download) link for a single format Calibre is offering this book in
	 * (e.g. EPUB, PDF, MOBI, MP3, CBZ...). Calibre's OPDS feed lists one
	 * rel=".../acquisition" &lt;link&gt; per available format on the same entry -- see
	 * {@link #formats}. */
	public static class Format implements Serializable {
		private static final long serialVersionUID = 1L;
		public String href;
		/** The link's "type" attribute, e.g. "application/epub+zip". May be null. */
		public String mimeType;
		/** File size in bytes, from the acquisition link's "length" attribute when the
		 * Content Server exposes it (not every Calibre version does). -1 if unknown -- the UI
		 * must hide the size instead of showing a bogus "-1 B". */
		public long fileSizeBytes = -1;
	}

	/** Every acquisition link this entry offers, in feed order. A book stored in Calibre with
	 * multiple formats (e.g. both EPUB and PDF, or an ebook plus an MP3/M4B audiobook) lists one
	 * acquisition link per format here; {@link #href} above still holds the *last* one parsed,
	 * kept for backward compatibility with code that only cares about a single link (catalog
	 * navigation links, which are never repeated, still only use href/linkType). */
	public List<Format> formats = new ArrayList<Format>();
}

