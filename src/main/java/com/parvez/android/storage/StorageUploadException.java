package com.parvez.android.storage;

import java.io.IOException;

/** Provider-independent upload rejection; HTTP translation belongs to the document layer. */
public class StorageUploadException extends IOException {
    public enum Reason { EMPTY, TOO_LARGE }

    private final Reason reason;

    public StorageUploadException(Reason reason) {
        super(reason == Reason.EMPTY ? "Document is empty" : "Document exceeds the upload limit");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
