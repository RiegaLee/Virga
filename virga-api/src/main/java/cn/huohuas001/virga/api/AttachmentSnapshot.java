package cn.huohuas001.virga.api;

/** Immutable attachment metadata. Attachment contents are not downloaded by the API. */
public final class AttachmentSnapshot {
    private final String id;
    private final String filename;
    private final String url;
    private final String contentType;
    private final Integer size;
    private final Integer width;
    private final Integer height;
    private final String asrReferText;

    public AttachmentSnapshot(
        String id,
        String filename,
        String url,
        String contentType,
        Integer size,
        Integer width,
        Integer height,
        String asrReferText
    ) {
        this.id = id;
        this.filename = filename;
        this.url = url;
        this.contentType = contentType;
        this.size = size;
        this.width = width;
        this.height = height;
        this.asrReferText = asrReferText;
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getUrl() { return url; }
    public String getContentType() { return contentType; }
    public Integer getSize() { return size; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getAsrReferText() { return asrReferText; }
}
