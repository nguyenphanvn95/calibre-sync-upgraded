package net.mrphan.calibre.reader.chrome

/**
 * Which concrete in-app reader is currently hosting the chrome overlay. Used by
 * [ReaderAction.isRelevantFor] so a single shared [ReaderActionRegistry] can hide/disable actions
 * that don't make sense for a given format (e.g. "Font size" only makes sense for EPUB's reflowed
 * text, not for PDF/CBZ's rendered page images).
 */
enum class ReaderKind {
    EPUB,
    PDF,
    CBZ,
}
