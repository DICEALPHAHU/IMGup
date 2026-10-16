package com.github.dicealphahu.imgup;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片校验与重编码。
 *
 * 用 JDK 自带的 ImageIO，所以整个插件不需要任何额外依赖 —— 这是「只留一个 jar」
 * 的前提。代价是 JPEG 质量只能靠 ImageWriteParam 调，不如 Pillow 精细，
 * 但海报这种用途完全够。
 *
 * 安全上的几条红线：
 *   · 格式看**真实字节**，不看文件名也不信 Content-Type
 *   · 先读图片头拿到尺寸，超过像素上限直接拒绝（防解压炸弹）
 *   · 一律重新编码，绝不把原始字节直接落盘（顺手去掉 EXIF 之类的东西）
 *   · 拒绝动图
 */
public final class ImageTools {

    /** 处理失败时抛出，code 是语言中立的错误码，前端据此查文案。 */
    public static final class ImageException extends Exception {
        private final String code;

        public ImageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 处理结果。 */
    public record Processed(byte[] data, String extension, int width, int height) {
    }

    private ImageTools() {
    }

    /** 看真实字节判断格式，只认 PNG / JPG。 */
    public static String sniff(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "PNG";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "JPEG";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "GIF";
        }
        if (data.length >= 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            return "PDF";
        }
        return null;
    }

    /**
     * 校验、必要时缩放、重编码。
     *
     * @param raw          上传的原始字节
     * @param maxBytes     字节上限
     * @param maxDimension 长边像素上限（只缩不放）
     * @param maxPixels    总像素上限
     * @param jpegQuality  重编码 JPEG 的质量
     */
    public static Processed process(byte[] raw, int maxBytes, int maxDimension,
                                    int maxPixels, int jpegQuality)
            throws ImageException {
        if (raw == null || raw.length == 0) {
            throw new ImageException("bad_image", "文件是空的");
        }
        if (raw.length > maxBytes) {
            throw new ImageException("too_large", "文件超过体积上限");
        }

        String format = sniff(raw);
        if ("GIF".equals(format)) {
            throw new ImageException("animated_not_allowed", "不支持动图，请换成静态的 PNG 或 JPG");
        }
        if ("PDF".equals(format)) {
            throw new ImageException("pdf_not_allowed", "不支持 PDF，请上传 PNG 或 JPG");
        }
        if (format == null) {
            throw new ImageException("bad_image", "这个文件不是有效的 PNG / JPG 图片");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (input == null) {
                throw new ImageException("bad_image", "无法读取这个图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ImageException("bad_image", "没有可用的解码器能读这个图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);

                // 先看有几帧：动图直接拒
                int frames = 1;
                try {
                    frames = reader.getNumImages(true);
                } catch (UnsupportedOperationException | IOException ignored) {
                    frames = 1;
                }
                if (frames > 1) {
                    throw new ImageException("animated_not_allowed",
                            "不支持动图，请换成静态的 PNG 或 JPG");
                }

                // 只读图片头拿尺寸，不解码整张图 —— 这是防解压炸弹的关键
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new ImageException("bad_image", "图片尺寸不合法");
                }
                long pixels = (long) width * (long) height;
                if (pixels > maxPixels) {
                    throw new ImageException("image_too_big",
                            "图片像素太多（" + width + "x" + height + "），请先缩小再上传");
                }

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new ImageException("bad_image", "图片解码失败");
                }

                BufferedImage scaled = downscale(image, maxDimension);
                int outWidth = scaled.getWidth();
                int outHeight = scaled.getHeight();

                boolean asJpeg = "JPEG".equals(format) && !hasAlpha(scaled);
                byte[] out = asJpeg
                        ? encodeJpeg(scaled, jpegQuality)
                        : encodePng(scaled);
                return new Processed(out, asJpeg ? "jpg" : "png", outWidth, outHeight);
            } finally {
                reader.dispose();
            }
        } catch (ImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImageException("bad_image", "图片处理失败：" + e.getMessage());
        }
    }

    /** 长边超过上限才缩，绝不放大。 */
    private static BufferedImage downscale(BufferedImage source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxDimension) {
            return source;
        }
        double ratio = (double) maxDimension / (double) longest;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        int type = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static boolean hasAlpha(BufferedImage image) {
        return image.getColorModel().hasAlpha();
    }

    /** 统一输出 RGB（JPEG 不支持透明通道，直接落盘会变红或全黑）。 */
    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage image, int quality) throws IOException {
        BufferedImage rgb = toRgb(image);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("这个 JDK 没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                float q = Math.max(0.3f, Math.min(1.0f, quality / 100.0f));
                try {
                    param.setCompressionQuality(q);
                } catch (RuntimeException ignored) {
                    // 个别编码器不支持，用默认质量即可
                }
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        BufferedImage source = image;
        if (source.getType() != BufferedImage.TYPE_INT_ARGB
                && source.getColorModel().hasAlpha()) {
            BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            try {
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
            source = argb;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
        if (!ImageIO.write(source, "png", out)) {
            throw new IOException("这个 JDK 没有 PNG 编码器");
        }
        return out.toByteArray();
    }
}
