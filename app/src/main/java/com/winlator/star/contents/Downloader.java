package com.winlator.star.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

public class Downloader {

    /** Reports download progress. fraction is 0..1, or -1 when total size is unknown. */
    public interface ProgressListener {
        void onProgress(float fraction);
    }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, ProgressListener listener) {
        return downloadFile(address, file, false, listener);
    }

    /**
     * Download {@code address} into {@code file}. When {@code resume} is true and a partial
     * {@code file} already exists, an HTTP {@code Range: bytes=N-} request is sent and the body
     * appended, so a download interrupted by process death picks up where it left off. This is
     * backward-safe: a server that ignores Range (returns 200) simply restarts the file from
     * scratch, and a server that answers 416 (range past EOF) is treated as "already complete".
     */
    public static boolean downloadFile(String address, File file, boolean resume, ProgressListener listener) {
        HttpURLConnection http = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            long existing = (resume && file.exists()) ? file.length() : 0;

            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Bannerlator");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

            boolean append = false;
            long readTotal = 0;
            long total;

            if (connection instanceof HttpURLConnection) {
                http = (HttpURLConnection) connection;
                int code = http.getResponseCode();
                if (existing > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                    // 206: server honoured the range — resume by appending.
                    append = true;
                    readTotal = existing;
                    total = existing + connection.getContentLengthLong();
                } else if (existing > 0 && code == 416 /* Requested Range Not Satisfiable */) {
                    // The local file is already at/past EOF — treat the partial as the finished file.
                    if (listener != null) listener.onProgress(1f);
                    return true;
                } else {
                    // 200 (or any non-206): full body — restart the file from scratch.
                    total = connection.getContentLengthLong();
                }
            } else {
                total = connection.getContentLengthLong();
            }

            input = connection.getInputStream();
            output = new FileOutputStream(file.getAbsolutePath(), append);

            byte[] data = new byte[8192];

            float lastReported = -2f;
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                readTotal += count;
                if (listener != null) {
                    float fraction = total > 0 ? (float) readTotal / (float) total : -1f;
                    // Throttle to whole-percent steps to avoid flooding the UI thread.
                    if (fraction < 0f || fraction - lastReported >= 0.01f || fraction >= 1f) {
                        lastReported = fraction;
                        listener.onProgress(fraction);
                    }
                }
            }

            output.flush();
            if (listener != null) listener.onProgress(1f);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            try { if (input != null) input.close(); } catch (Exception ignored) {}
        }
    }

    public static String downloadString(String address) {
        InputStream input = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Bannerlator");
            input = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (input != null) input.close(); } catch (Exception ignored) {}
        }
    }
}
