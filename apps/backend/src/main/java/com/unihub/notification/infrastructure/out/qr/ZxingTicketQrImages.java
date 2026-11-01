package com.unihub.notification.infrastructure.out.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.unihub.notification.application.port.out.TicketQrImageException;
import com.unihub.notification.application.port.out.TicketQrImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * {@link TicketQrImages} with ZXing's core and the JDK's {@code ImageIO} (Phase 5 checklist D55).
 * Level Q, as on the Web page (checkin.md §5): a phone scans it off a screen or a printout. Black
 * on white, a four-module quiet zone as the QR standard asks, {@value #MODULE_PIXELS} pixels per
 * module so a mail client that scales the image down still leaves every module readable.
 */
@Component
class ZxingTicketQrImages implements TicketQrImages {
    static final int MODULE_PIXELS = 6;

    private static final int QUIET_ZONE_MODULES = 4;
    private static final Map<EncodeHintType, Object> HINTS =
            Map.of(
                    EncodeHintType.ERROR_CORRECTION,
                    ErrorCorrectionLevel.Q,
                    EncodeHintType.MARGIN,
                    QUIET_ZONE_MODULES,
                    EncodeHintType.CHARACTER_SET,
                    StandardCharsets.UTF_8.name());

    @Override
    public byte[] pngOf(String payload) {
        if (payload == null || payload.isEmpty()) {
            throw new TicketQrImageException("there is nothing to draw");
        }
        BitMatrix matrix;
        try {
            // width and height 0: the matrix at its natural size, which the loop below scales
            matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, HINTS);
        } catch (WriterException | IllegalArgumentException e) {
            // never the payload itself: it is the ticket
            throw new TicketQrImageException(
                    "a payload of " + payload.length() + " characters cannot be drawn as a QR", e);
        }
        var image =
                new BufferedImage(
                        matrix.getWidth() * MODULE_PIXELS,
                        matrix.getHeight() * MODULE_PIXELS,
                        BufferedImage.TYPE_BYTE_BINARY);
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                var dark = matrix.get(x / MODULE_PIXELS, y / MODULE_PIXELS);
                image.setRGB(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new TicketQrImageException("no PNG writer is available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
