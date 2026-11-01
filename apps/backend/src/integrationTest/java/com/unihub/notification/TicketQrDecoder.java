package com.unihub.notification;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Reads the QR of a confirmation email back, as a phone would (Phase 5 checklist D55, AC55.1): the
 * text of the code in the PNG that Mailpit kept.
 */
final class TicketQrDecoder {

    private TicketQrDecoder() {}

    static String decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var pixels =
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var bitmap =
                new BinaryBitmap(
                        new HybridBinarizer(
                                new RGBLuminanceSource(
                                        image.getWidth(), image.getHeight(), pixels)));
        return new QRCodeReader()
                .decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE))
                .getText();
    }
}
