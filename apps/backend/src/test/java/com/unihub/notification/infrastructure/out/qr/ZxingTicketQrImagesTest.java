package com.unihub.notification.infrastructure.out.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.unihub.notification.application.port.out.TicketQrImageException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The QR of the confirmation email (Phase 5 checklist D55, AC55.1): what is drawn is read back by a
 * QR decoder and is exactly the payload, at error-correction level Q (Q12).
 */
class ZxingTicketQrImagesTest {
    private final ZxingTicketQrImages images = new ZxingTicketQrImages();

    /** A compact JWS of the shape a ticket has: ES256, three claims. A new key each time. */
    private static String aRealTicketPayload() throws JOSEException {
        var key = new ECKeyGenerator(Curve.P_256).generate();
        var claims =
                "{\"ticket_code\":\"0123456789ABCDEFGHJKMNPQRS\",\"workshop_id\":\""
                        + "6b1f0c3e-2a44-4d8e-9a53-1f0a7c5d9e21\",\"iat\":1793500000}";
        var jws = new JWSObject(new JWSHeader(JWSAlgorithm.ES256), new Payload(claims));
        jws.sign(new ECDSASigner(key));
        return jws.serialize();
    }

    private static BufferedImage decodePng(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static Result read(byte[] png) throws Exception {
        var image = decodePng(png);
        var pixels =
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var bitmap =
                new BinaryBitmap(
                        new HybridBinarizer(
                                new RGBLuminanceSource(
                                        image.getWidth(), image.getHeight(), pixels)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE));
    }

    @Test
    @DisplayName("a real ticket payload, drawn and read back, is exactly that string (AC55.1)")
    void roundTrip_isTheSamePayload() throws Exception {
        var payload = aRealTicketPayload();

        var result = read(images.pngOf(payload));

        assertThat(result.getText()).isEqualTo(payload);
    }

    @Test
    @DisplayName("the code is drawn at error-correction level Q, as the Web page does")
    void errorCorrection_isQ() throws Exception {
        var result = read(images.pngOf(aRealTicketPayload()));

        assertThat(result.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL))
                .isEqualTo("Q");
    }

    @Test
    @DisplayName("the same payload always gives the same image")
    void sameInput_sameBytes() throws Exception {
        var payload = aRealTicketPayload();

        assertThat(images.pngOf(payload)).isEqualTo(images.pngOf(payload));
    }

    @Test
    @DisplayName("two different payloads give two different images")
    void differentInput_differentBytes() throws Exception {
        assertThat(images.pngOf(aRealTicketPayload()))
                .isNotEqualTo(images.pngOf(aRealTicketPayload()));
    }

    @Test
    @DisplayName("it is a black-and-white PNG with a four-module quiet zone around the code")
    void image_hasAQuietZoneAndOnlyBlackAndWhite() throws Exception {
        var png = images.pngOf(aRealTicketPayload());
        var image = decodePng(png);

        assertThat(png[1]).as("PNG signature").isEqualTo((byte) 'P');
        assertThat(image.getWidth()).isEqualTo(image.getHeight());
        assertThat(image.getWidth() % ZxingTicketQrImages.MODULE_PIXELS).isZero();
        var quiet = 4 * ZxingTicketQrImages.MODULE_PIXELS;
        for (var i = 0; i < quiet; i++) {
            for (var j = 0; j < image.getWidth(); j++) {
                assertThat(image.getRGB(j, i) & 0xFFFFFF).as("top margin").isEqualTo(0xFFFFFF);
                assertThat(image.getRGB(i, j) & 0xFFFFFF).as("left margin").isEqualTo(0xFFFFFF);
            }
        }
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                var rgb = image.getRGB(x, y) & 0xFFFFFF;
                assertThat(rgb == 0 || rgb == 0xFFFFFF).isTrue();
            }
        }
    }

    @Test
    @DisplayName("an empty payload cannot be drawn")
    void emptyPayload_isRefused() {
        assertThatThrownBy(() -> images.pngOf("")).isInstanceOf(TicketQrImageException.class);
        assertThatThrownBy(() -> images.pngOf(null)).isInstanceOf(TicketQrImageException.class);
    }

    @Test
    @DisplayName("a payload too long for a QR code is refused, and the message never holds it")
    void tooLongPayload_isRefusedWithoutEchoingIt() {
        var tooLong = "ticket-secret-".repeat(400);

        assertThatThrownBy(() -> images.pngOf(tooLong))
                .isInstanceOf(TicketQrImageException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ticket-secret"));
    }
}
