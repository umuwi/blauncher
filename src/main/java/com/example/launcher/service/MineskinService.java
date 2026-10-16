package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads a local skin image to MineSkin and returns a hosted texture URL on Minecraft's own
 * texture CDN. This gives an offline/custom profile a real skin URL without needing a third-party
 * skin site account.
 */
public class MineskinService {

    private static final String UPLOAD_URL = "https://api.mineskin.org/generate/upload";

    public String uploadSkin(Path skinFile) throws IOException {
        byte[] originalBytes = Files.readAllBytes(skinFile);
        String originalName = skinFile.getFileName().toString();

        UploadResult result = tryUpload(originalBytes, originalName, detectContentType(originalBytes));
        if (result.code >= 200 && result.code < 300) {
            return parseUrl(result.response);
        }

        // MineSkin sometimes rejects non-standard/oversized skin uploads with 400.
        // Try standard 64x64 PNG first, then a much smaller 64x64 JPEG.
        if (result.code == 400) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (image != null) {
                if (image.getWidth() != 64 || image.getHeight() != 64) {
                    byte[] resizedPng = resizeToPng(image, 64);
                    String resizedPngName = replaceExtension(originalName, "png");
                    UploadResult pngRetry = tryUpload(resizedPng, resizedPngName, "image/png");
                    if (pngRetry.code >= 200 && pngRetry.code < 300) {
                        return parseUrl(pngRetry.response);
                    }
                }

                byte[] resizedJpeg = resizeToJpeg(image, 64);
                String resizedJpegName = replaceExtension(originalName, "jpg");
                UploadResult jpegRetry = tryUpload(resizedJpeg, resizedJpegName, "image/jpeg");
                if (jpegRetry.code >= 200 && jpegRetry.code < 300) {
                    return parseUrl(jpegRetry.response);
                }
                throw new IOException("MineSkin upload failed (" + jpegRetry.code + "): " + jpegRetry.response);
            }
        }

        throw new IOException("MineSkin upload failed (" + result.code + "): " + result.response);
    }

    private static UploadResult tryUpload(byte[] fileBytes, String fileName, String contentType) throws IOException {
        String boundary = "----BLauncher" + Long.toHexString(System.nanoTime());
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(UPLOAD_URL).toURL().openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", "BLauncher");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            writeBoundary(os, boundary);
            writeHeader(os, "file", fileName, contentType);
            os.write(fileBytes);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new UploadResult(code, response);
    }

    private static String parseUrl(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        return root.getAsJsonObject("data").getAsJsonObject("texture").get("url").getAsString();
    }

    private static String detectContentType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static byte[] resizeToPng(BufferedImage source, int targetSize) throws IOException {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        BufferedImage resized = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((double) targetSize / srcW, (double) targetSize / srcH);
            int drawW = Math.max(1, (int) Math.round(srcW * scale));
            int drawH = Math.max(1, (int) Math.round(srcH * scale));
            int x = (targetSize - drawW) / 2;
            int y = (targetSize - drawH) / 2;
            g.drawImage(source, x, y, drawW, drawH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(resized, "png", out);
        if (!written) {
            throw new IOException("PNG writer unavailable");
        }
        return out.toByteArray();
    }

    private static byte[] resizeToJpeg(BufferedImage source, int targetSize) throws IOException {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        BufferedImage resized = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetSize, targetSize);
            double scale = Math.min((double) targetSize / srcW, (double) targetSize / srcH);
            int drawW = Math.max(1, (int) Math.round(srcW * scale));
            int drawH = Math.max(1, (int) Math.round(srcH * scale));
            int x = (targetSize - drawW) / 2;
            int y = (targetSize - drawH) / 2;
            g.drawImage(source, x, y, drawW, drawH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(resized, "jpg", out);
        if (!written) {
            throw new IOException("JPEG writer unavailable");
        }
        return out.toByteArray();
    }

    private static String replaceExtension(String fileName, String newExtension) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName + "." + newExtension;
        }
        return fileName.substring(0, dot) + "." + newExtension;
    }

    private static void writeBoundary(OutputStream os, String boundary) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeHeader(OutputStream os, String name, String fileName, String contentType) throws IOException {
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static class UploadResult {
        private final int code;
        private final String response;

        UploadResult(int code, String response) {
            this.code = code;
            this.response = response;
        }
    }
}
