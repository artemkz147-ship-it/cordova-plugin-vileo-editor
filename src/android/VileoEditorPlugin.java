package com.vileo.gdevelop.editor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class VileoEditorPlugin extends CordovaPlugin {
    private static final int PICK_VIDEO_REQUEST = 7319;
    private CallbackContext pickCallback;
    private Transformer currentTransformer;
    private File currentOutputFile;
    private volatile boolean exportRunning = false;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "pickVideo": pickVideo(callbackContext); return true;
            case "getVideoInfo": getVideoInfo(args.optString(0, ""), callbackContext); return true;
            case "exportVideo": exportVideo(args.optJSONObject(0), callbackContext); return true;
            case "getProgress": getProgress(callbackContext); return true;
            case "cancelExport": cancelExport(callbackContext); return true;
            default: return false;
        }
    }

    private void pickVideo(CallbackContext cb) {
        if (pickCallback != null) { cb.error(error("PICKER_BUSY", "Выбор файла уже открыт.")); return; }
        pickCallback = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        cordova.startActivityForResult(this, i, PICK_VIDEO_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != PICK_VIDEO_REQUEST || pickCallback == null) return;
        final CallbackContext cb = pickCallback; pickCallback = null;
        if (resultCode != Activity.RESULT_OK || intent == null || intent.getData() == null) { cb.error(error("PICK_CANCELLED", "Видео не выбрано.")); return; }
        final Uri uri = intent.getData();
        try { cordova.getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        cordova.getThreadPool().execute(() -> {
            try {
                String name = queryDisplayName(uri);
                if (name == null || name.trim().isEmpty()) name = "video_" + System.currentTimeMillis() + ".mp4";
                String safe = sanitizeFileName(name);
                if (!safe.contains(".")) safe += ".mp4";
                File dst = new File(cordova.getContext().getCacheDir(), "vileo_src_" + System.currentTimeMillis() + "_" + safe);
                copyUriToFile(uri, dst);
                JSONObject out = readInfo(dst);
                out.put("name", name);
                out.put("filePath", dst.getAbsolutePath());
                out.put("cacheName", dst.getName());
                out.put("previewUrl", "cdvfile://localhost/cache/" + Uri.encode(dst.getName()));
                cb.success(out);
            } catch (Exception e) { cb.error(error("PICK_FAILED", e.getMessage())); }
        });
    }

    private void getVideoInfo(String path, CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try { cb.success(readInfo(new File(path))); }
            catch (Exception e) { cb.error(error("INFO_FAILED", e.getMessage())); }
        });
    }

    private void exportVideo(JSONObject options, CallbackContext cb) {
        if (options == null) options = new JSONObject();
        if (exportRunning) { cb.error(error("EXPORT_BUSY", "Экспорт уже выполняется.")); return; }
        final JSONObject o = options;
        final String inputPath = o.optString("inputPath", "");
        if (inputPath.isEmpty() || !new File(inputPath).exists()) { cb.error(error("INPUT_NOT_FOUND", "Исходный видеофайл не найден.")); return; }
        cordova.getActivity().runOnUiThread(() -> {
            try {
                exportRunning = true;
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                currentOutputFile = new File(cordova.getContext().getCacheDir(), "Vileo_" + stamp + ".mp4");
                if (currentOutputFile.exists()) currentOutputFile.delete();

                long startMs = Math.max(0, o.optLong("startMs", 0));
                long endMs = Math.max(0, o.optLong("endMs", 0));
                MediaItem.ClippingConfiguration.Builder clip = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs);
                if (endMs > startMs) clip.setEndPositionMs(endMs);
                MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.fromFile(new File(inputPath))).setClippingConfiguration(clip.build()).build();

                List<Effect> videoEffects = new ArrayList<>();
                float rotation = (float)o.optDouble("rotationDegrees", 0);
                if (Math.abs(rotation) > 0.001f) videoEffects.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation).build());
                float brightness = clampFloat((float)o.optDouble("brightness", 0), -1f, 1f);
                if (Math.abs(brightness) > 0.001f) videoEffects.add(new Brightness(brightness));
                float contrast = clampFloat((float)o.optDouble("contrast", 0), -1f, 1f);
                if (Math.abs(contrast) > 0.001f) videoEffects.add(new Contrast(contrast));
                float saturation = clampFloat((float)o.optDouble("saturation", 0), -100f, 100f);
                if (Math.abs(saturation) > 0.001f) videoEffects.add(new HslAdjustment.Builder().adjustSaturation(saturation).build());

                String overlayText = o.optString("text", "").trim();
                if (!overlayText.isEmpty()) {
                    int color = parseColor(o.optString("textColor", "#ffffff"));
                    int size = Math.max(18, Math.min(160, o.optInt("textSize", 54)));
                    String position = o.optString("textPosition", "bottom");
                    SpannableString ss = new SpannableString(overlayText);
                    ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ss.setSpan(new AbsoluteSizeSpan(size, true), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ss.setSpan(new StyleSpan(Typeface.BOLD), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    float y = position.equals("top") ? 0.78f : position.equals("center") ? 0f : -0.78f;
                    StaticOverlaySettings settings = new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build();
                    TextOverlay overlay = TextOverlay.createStaticTextOverlay(ss, settings);
                    List<TextureOverlay> overlays = Collections.singletonList((TextureOverlay)overlay);
                    videoEffects.add(new OverlayEffect(overlays));
                }

                Effects effects = new Effects(Collections.emptyList(), videoEffects);
                EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(o.optBoolean("removeAudio", false))
                        .setEffects(effects);

                final float speed = clampFloat((float)o.optDouble("speed", 1.0), 0.25f, 4f);
                if (Math.abs(speed - 1f) > 0.001f) {
                    editedBuilder.setSpeed(new SpeedProvider() {
                        @Override public float getSpeed(long timeUs) { return speed; }
                        @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
                    });
                }
                EditedMediaItem edited = editedBuilder.build();

                currentTransformer = new Transformer.Builder(cordova.getContext())
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                                exportRunning = false;
                                final File outFile = currentOutputFile;
                                final boolean save = o.optBoolean("saveToGallery", true);
                                cordova.getThreadPool().execute(() -> {
                                    try {
                                        JSONObject res = new JSONObject();
                                        res.put("outputPath", outFile.getAbsolutePath());
                                        res.put("cacheName", outFile.getName());
                                        res.put("previewUrl", "cdvfile://localhost/cache/" + Uri.encode(outFile.getName()));
                                        res.put("sizeBytes", outFile.length());
                                        if (save) res.put("contentUri", saveToGallery(outFile));
                                        cb.success(res);
                                    } catch (Exception e) { cb.error(error("SAVE_FAILED", e.getMessage())); }
                                });
                            }
                            @Override public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult, @NonNull ExportException exportException) {
                                exportRunning = false;
                                cb.error(error("EXPORT_FAILED", exportException.getMessage()));
                            }
                        }).build();
                currentTransformer.start(edited, currentOutputFile.getAbsolutePath());
            } catch (Exception e) {
                exportRunning = false; currentTransformer = null;
                cb.error(error("EXPORT_START_FAILED", e.getMessage()));
            }
        });
    }

    private void getProgress(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            try {
                JSONObject out = new JSONObject();
                if (!exportRunning || currentTransformer == null) { out.put("state", "idle"); out.put("progress", exportRunning ? 0 : 100); cb.success(out); return; }
                ProgressHolder holder = new ProgressHolder();
                int state = currentTransformer.getProgress(holder);
                out.put("stateCode", state);
                out.put("state", state == Transformer.PROGRESS_STATE_AVAILABLE ? "available" : state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY ? "waiting" : state == Transformer.PROGRESS_STATE_UNAVAILABLE ? "unavailable" : "working");
                out.put("progress", state == Transformer.PROGRESS_STATE_AVAILABLE ? holder.progress : 0);
                cb.success(out);
            } catch (Exception e) { cb.error(error("PROGRESS_FAILED", e.getMessage())); }
        });
    }

    private void cancelExport(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            try { if (currentTransformer != null) currentTransformer.cancel(); exportRunning = false; if (currentOutputFile != null && currentOutputFile.exists()) currentOutputFile.delete(); cb.success(); }
            catch (Exception e) { cb.error(error("CANCEL_FAILED", e.getMessage())); }
        });
    }

    private JSONObject readInfo(File file) throws Exception {
        if (file == null || !file.exists()) throw new Exception("Файл не найден");
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            JSONObject o = new JSONObject();
            o.put("durationMs", parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
            o.put("width", parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
            o.put("height", parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
            o.put("rotation", parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));
            o.put("filePath", file.getAbsolutePath()); o.put("name", file.getName()); o.put("sizeBytes", file.length());
            return o;
        } finally { try { mmr.release(); } catch (Exception ignored) {} }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = cordova.getContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }
    private void copyUriToFile(Uri uri, File dst) throws Exception {
        try (InputStream in = cordova.getContext().getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new Exception("Не удалось открыть видео"); byte[] buf = new byte[256 * 1024]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
    private String saveToGallery(File src) throws Exception {
        String name = src.getName(); ContentResolver cr = cordova.getContext().getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues(); v.put(MediaStore.Video.Media.DISPLAY_NAME, name); v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Vileo"); v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v); if (uri == null) throw new Exception("MediaStore не создал файл");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) { if (out == null) throw new Exception("MediaStore output недоступен"); byte[] buf = new byte[256 * 1024]; int n; while ((n = in.read(buf)) > 0) out.write(buf,0,n); }
            v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(uri, v, null, null); return uri.toString();
        }
        File dir = new File(cordova.getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Vileo"); if (!dir.exists()) dir.mkdirs(); File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { byte[] buf = new byte[256 * 1024]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n); }
        MediaScannerConnection.scanFile(cordova.getContext(), new String[]{dst.getAbsolutePath()}, new String[]{"video/mp4"}, null); return Uri.fromFile(dst).toString();
    }
    private static JSONObject error(String code, String message) { JSONObject o = new JSONObject(); try { o.put("code", code); o.put("message", message == null ? code : message); } catch (Exception ignored) {} return o; }
    private static String sanitizeFileName(String s) { return s.replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private static long parseLong(String s) { try { return Long.parseLong(s == null ? "0" : s); } catch (Exception e) { return 0; } }
    private static float clampFloat(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static int parseColor(String c) { try { return Color.parseColor(c); } catch (Exception e) { return Color.WHITE; } }
}
