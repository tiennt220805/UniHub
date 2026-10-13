package com.unihub.user.application.port.out;

/** The export file exists but cannot be read as UTF-8 text (user.md §2, FILE_UNREADABLE). */
public class StudentExportUnreadableException extends RuntimeException {

    public StudentExportUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
