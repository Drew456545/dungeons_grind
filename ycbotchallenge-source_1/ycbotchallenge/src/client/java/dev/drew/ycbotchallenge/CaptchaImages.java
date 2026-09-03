package dev.drew.ycbotchallenge;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/**
 * Image plumbing for the captcha solver, free of Minecraft types so it can be
 * unit-checked. Bench 2026-09-03 (Qwen3-VL-4B on the "pnGe" capture): inputs
 * over ~1024 px wide hallucinate letters out of the noise specks, and a 128 px
 * map upscaled x4 with nearest neighbour reads "prGe" while x2 nearest or x4
 * bilinear read "pnGe" — hence the width cap and the smoothing switch.
 */
final class CaptchaImages {
    private CaptchaImages() {}

    /** Scale to {@code width} keeping aspect; bilinear when {@code smooth}, else nearest neighbour. */
    static BufferedImage scale(BufferedImage src, int width, boolean smooth) {
        if (width <= 0 || width == src.getWidth()) return src;
        int height = Math.max(1, (int) Math.round(src.getHeight() * (width / (double) src.getWidth())));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    /** PNG -> at most {@code maxPx} wide (bilinear); the same bytes when already small. */
    static byte[] downscalePng(byte[] png, int maxPx) throws Exception {
        if (png == null || maxPx <= 0) return png;
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || img.getWidth() <= maxPx) return png;
        return encodePng(scale(img, maxPx, true));
    }

    static byte[] encodePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static Integer pngWidth(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            return img == null ? null : img.getWidth();
        } catch (Exception e) {
            return null;
        }
    }
}
