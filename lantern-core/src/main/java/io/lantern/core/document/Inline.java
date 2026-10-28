package io.lantern.core.document;

/**
 * An element of rich text: a run of text, a cross-reference or a citation. Rich text is a non-empty
 * list of these.
 */
public sealed interface Inline permits TextRun, CrossReference, Citation {}
