package com.vileo.gdevelop.editor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
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

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.effect.TimestampWrapper;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
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
    private static final int PICK_VIDEOS=7411, PICK_AUDIO=7412, PICK_IMAGE=7413;
    private CallbackContext pickerCallback;
    private int pickerRequest=-1;
    private Transformer transformer;
    private File outputFile;
    private volatile boolean exporting=false;
    private File mediaDir;

    @Override protected void pluginInitialize(){ super.pluginInitialize(); mediaDir=new File(cordova.getContext().getFilesDir(),"vileo_media"); if(!mediaDir.exists())mediaDir.mkdirs(); }

    @Override public boolean execute(String action, JSONArray args, CallbackContext cb){
        try{
            switch(action){
                case "pickVideos": openPicker("video/*",true,PICK_VIDEOS,cb); return true;
                case "pickAudio": openPicker("audio/*",false,PICK_AUDIO,cb); return true;
                case "pickImage": openPicker("image/*",false,PICK_IMAGE,cb); return true;
                case "getMediaInfo": getMediaInfo(args.optString(0,""),cb); return true;
                case "exportProject": exportProject(args.optJSONObject(0),cb); return true;
                case "getProgress": getProgress(cb); return true;
                case "cancelExport": cancelExport(cb); return true;
                case "cleanupCache": cleanupCache(cb); return true;
                default:return false;
            }
        }catch(Exception e){cb.error(error("EXECUTE_FAILED",e.getMessage()));return true;}
    }

    private void openPicker(String type, boolean multiple, int code, CallbackContext cb){
        if(pickerCallback!=null){cb.error(error("PICKER_BUSY","Окно выбора уже открыто."));return;}
        pickerCallback=cb;pickerRequest=code;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);cordova.startActivityForResult(this,i,code);
    }

    @Override public void onActivityResult(int requestCode,int resultCode,Intent intent){
        super.onActivityResult(requestCode,resultCode,intent);if(requestCode!=pickerRequest||pickerCallback==null)return;final CallbackContext cb=pickerCallback;pickerCallback=null;pickerRequest=-1;
        if(resultCode!=Activity.RESULT_OK||intent==null){cb.error(error("PICK_CANCELLED","Выбор отменён."));return;}
        final List<Uri> uris=new ArrayList<>();if(intent.getClipData()!=null){for(int i=0;i<intent.getClipData().getItemCount();i++)uris.add(intent.getClipData().getItemAt(i).getUri());}else if(intent.getData()!=null)uris.add(intent.getData());
        if(uris.isEmpty()){cb.error(error("PICK_EMPTY","Файл не выбран."));return;}
        cordova.getThreadPool().execute(()->{try{JSONArray arr=new JSONArray();for(Uri uri:uris)arr.put(importUri(uri,requestCode));if(requestCode==PICK_VIDEOS)cb.success(arr);else cb.success(arr.getJSONObject(0));}catch(Exception e){cb.error(error("PICK_FAILED",e.getMessage()));}});
    }

    private JSONObject importUri(Uri uri,int kind)throws Exception{
        try{cordova.getContext().getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        String name=queryName(uri);if(name==null||name.trim().isEmpty())name="media_"+System.currentTimeMillis();String ext=extension(name);if(ext.isEmpty())ext=kind==PICK_AUDIO?".m4a":kind==PICK_IMAGE?".png":".mp4";
        File dst=new File(mediaDir,"m_"+System.currentTimeMillis()+"_"+Math.abs(uri.toString().hashCode())+ext);copy(uri,dst);
        JSONObject o=new JSONObject();o.put("filePath",dst.getAbsolutePath());o.put("previewUrl","file://"+dst.getAbsolutePath());o.put("name",name);o.put("sizeBytes",dst.length());
        if(kind==PICK_IMAGE){o.put("type","image");return o;}JSONObject info=readInfo(dst);merge(o,info);o.put("type",kind==PICK_AUDIO?"audio":"video");return o;
    }

    private void getMediaInfo(String path,CallbackContext cb){cordova.getThreadPool().execute(()->{try{cb.success(readInfo(new File(path)));}catch(Exception e){cb.error(error("INFO_FAILED",e.getMessage()));}});}

    private void exportProject(JSONObject project,CallbackContext cb){
        if(project==null)project=new JSONObject();if(exporting){cb.error(error("EXPORT_BUSY","Экспорт уже выполняется."));return;}final JSONObject p=project;
        cordova.getActivity().runOnUiThread(()->{try{
            JSONArray clips=p.optJSONArray("clips");if(clips==null||clips.length()==0){cb.error(error("NO_CLIPS","Нет клипов для экспорта."));return;}
            exporting=true;String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());outputFile=new File(cordova.getContext().getCacheDir(),"Vileo_"+stamp+".mp4");if(outputFile.exists())outputFile.delete();
            JSONObject ex=p.optJSONObject("export");if(ex==null)ex=new JSONObject();JSONObject canvas=p.optJSONObject("canvas");if(canvas==null)canvas=new JSONObject();
            final int fps=clampInt(ex.optInt("fps",30),12,60);List<EditedMediaItem> editedClips=new ArrayList<>();
            for(int i=0;i<clips.length();i++)editedClips.add(buildClip(clips.getJSONObject(i),fps));
            EditedMediaItemSequence videoSeq=EditedMediaItemSequence.withAudioAndVideoFrom(editedClips);
            List<EditedMediaItemSequence> seqs=new ArrayList<>();seqs.add(videoSeq);
            JSONObject music=p.optJSONObject("music");if(music!=null&&!music.optString("filePath","").isEmpty()&&new File(music.optString("filePath")).exists()){
                MediaItem mi=MediaItem.fromUri(Uri.fromFile(new File(music.optString("filePath"))));List<AudioProcessor> ap=new ArrayList<>();float mv=clampFloat((float)music.optDouble("volume",.6),0f,1f);if(mv<.999f)ap.add(constantGain(mv));EditedMediaItem em=new EditedMediaItem.Builder(mi).setRemoveVideo(true).setEffects(new Effects(ap,Collections.emptyList())).build();EditedMediaItemSequence ms=EditedMediaItemSequence.withAudioFrom(Collections.singletonList(em)).buildUpon().setIsLooping(music.optBoolean("loop",true)).build();seqs.add(ms);
            }
            List<Effect> globalFx=buildGlobalEffects(p,canvas);Composition.Builder compB=new Composition.Builder(seqs).setEffects(new Effects(Collections.emptyList(),globalFx));if("sdr".equals(ex.optString("hdr","keep")))compB.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);Composition comp=compB.build();
            int bitrate=ex.optInt("bitrate",0);if(bitrate<=0){int shortSide=canvas.optInt("shortSide",1080);bitrate=shortSide>=2000?28000000:shortSide>=1000?10000000:5000000;if(fps>=50)bitrate=(int)(bitrate*1.45);}
            VideoEncoderSettings ves=new VideoEncoderSettings.Builder().setBitrate(bitrate).build();AudioEncoderSettings aes=new AudioEncoderSettings.Builder().setBitrate(clampInt(ex.optInt("audioBitrate",192000),64000,320000)).build();DefaultEncoderFactory ef=new DefaultEncoderFactory.Builder(cordova.getContext()).setEnableFallback(true).setRequestedVideoEncoderSettings(ves).setRequestedAudioEncoderSettings(aes).build();
            Transformer.Builder tb=new Transformer.Builder(cordova.getContext()).setEncoderFactory(ef).setAudioMimeType(MimeTypes.AUDIO_AAC);tb.setVideoMimeType("h265".equals(ex.optString("codec","h264"))?MimeTypes.VIDEO_H265:MimeTypes.VIDEO_H264);
            final JSONObject exportCfg=ex;transformer=tb.addListener(new Transformer.Listener(){
                @Override public void onCompleted(@NonNull Composition composition,@NonNull ExportResult result){exporting=false;final File out=outputFile;cordova.getThreadPool().execute(()->{try{JSONObject r=new JSONObject();r.put("outputPath",out.getAbsolutePath());r.put("sizeBytes",out.length());r.put("videoMimeType",result.videoMimeType);r.put("audioMimeType",result.audioMimeType);if(exportCfg.optBoolean("saveToGallery",true))r.put("contentUri",saveToGallery(out));cb.success(r);}catch(Exception e){cb.error(error("SAVE_FAILED",e.getMessage()));}});}
                @Override public void onError(@NonNull Composition composition,@NonNull ExportResult result,@NonNull ExportException e){exporting=false;cb.error(error("EXPORT_FAILED",e.getMessage()));}
            }).build();transformer.start(comp,outputFile.getAbsolutePath());
        }catch(Exception e){exporting=false;transformer=null;cb.error(error("EXPORT_START_FAILED",e.getMessage()));}});
    }

    private EditedMediaItem buildClip(JSONObject c,int fps)throws Exception{
        String path=c.optString("filePath","");File f=new File(path);if(path.isEmpty()||!f.exists())throw new Exception("Не найден клип: "+c.optString("name",path));long start=Math.max(0,c.optLong("trimStart",0)),end=Math.max(0,c.optLong("trimEnd",c.optLong("durationMs",0)));
        MediaItem.ClippingConfiguration.Builder clip=new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start);if(end>start)clip.setEndPositionMs(end);MediaItem mi=new MediaItem.Builder().setUri(Uri.fromFile(f)).setClippingConfiguration(clip.build()).build();
        List<Effect> vf=new ArrayList<>();float rot=(float)c.optDouble("rotation",0),zoom=clampFloat((float)c.optDouble("zoom",1),.25f,4f);float sx=(c.optBoolean("mirrorX",false)?-1f:1f)*zoom,sy=(c.optBoolean("mirrorY",false)?-1f:1f)*zoom;if(Math.abs(rot)>.001f||Math.abs(sx-1f)>.001f||Math.abs(sy-1f)>.001f)vf.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(rot).setScale(sx,sy).build());
        float br=clampFloat((float)c.optDouble("brightness",0),-1f,1f),co=clampFloat((float)c.optDouble("contrast",0),-1f,1f),sat=clampFloat((float)c.optDouble("saturation",0),-100f,100f),hue=(float)c.optDouble("hue",0);if(Math.abs(br)>.001f)vf.add(new Brightness(br));if(Math.abs(co)>.001f)vf.add(new Contrast(co));if(Math.abs(sat)>.001f||Math.abs(hue)>.001f)vf.add(new HslAdjustment.Builder().adjustSaturation(sat).adjustHue(hue).build());float blur=clampFloat((float)c.optDouble("blur",0),0f,12f);if(blur>.1f)vf.add(new GaussianBlur(Math.max(.1f,blur)));
        applyFilter(c.optString("filter","none"),vf);List<AudioProcessor> ap=new ArrayList<>();float vol=clampFloat((float)c.optDouble("volume",1),0f,1f);if(vol<.999f)ap.add(constantGain(vol));Effects effects=new Effects(ap,vf);EditedMediaItem.Builder eb=new EditedMediaItem.Builder(mi).setRemoveAudio(c.optBoolean("muted",false)).setEffects(effects).setFrameRate(fps);final float speed=clampFloat((float)c.optDouble("speed",1),.25f,4f);if(Math.abs(speed-1f)>.001f)eb.setSpeed(new SpeedProvider(){@Override public float getSpeed(long timeUs){return speed;}@Override public long getNextSpeedChangeTimeUs(long timeUs){return C.TIME_UNSET;}});return eb.build();
    }

    private void applyFilter(String name,List<Effect> vf){
        if("warm".equals(name))vf.add(new RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.02f).setBlueScale(.92f).build());
        else if("cool".equals(name))vf.add(new RgbAdjustment.Builder().setRedScale(.94f).setGreenScale(1.01f).setBlueScale(1.09f).build());
        else if("vivid".equals(name))vf.add(new HslAdjustment.Builder().adjustSaturation(22f).adjustLightness(2f).build());
        else if("mono".equals(name))vf.add(new HslAdjustment.Builder().adjustSaturation(-100f).build());
        else if("vintage".equals(name)){vf.add(new HslAdjustment.Builder().adjustSaturation(-20f).adjustLightness(3f).build());vf.add(new RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.0f).setBlueScale(.82f).build());}
        else if("cinema".equals(name)){vf.add(new Contrast(.12f));vf.add(new RgbAdjustment.Builder().setRedScale(1.04f).setGreenScale(.99f).setBlueScale(1.05f).build());}
    }

    private List<Effect> buildGlobalEffects(JSONObject p,JSONObject canvas)throws Exception{
        List<Effect> fx=new ArrayList<>();int w=canvas.optInt("outputWidth",0),h=canvas.optInt("outputHeight",0),shortSide=canvas.optInt("shortSide",1080);int layout="crop".equals(canvas.optString("fit","fit"))?Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP:Presentation.LAYOUT_SCALE_TO_FIT;if(w>0&&h>0)fx.add(Presentation.createForWidthAndHeight(w,h,layout));else if(shortSide>0)fx.add(Presentation.createForShortSide(shortSide));
        JSONArray texts=p.optJSONArray("textOverlays");if(texts!=null)for(int i=0;i<texts.length();i++){JSONObject o=texts.getJSONObject(i);String text=o.optString("text","").trim();if(text.isEmpty())continue;SpannableString ss=new SpannableString(text);int col=parseColor(o.optString("color","#ffffff"));int size=clampInt(o.optInt("size",48),12,200);ss.setSpan(new ForegroundColorSpan(col),0,ss.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);ss.setSpan(new AbsoluteSizeSpan(size,true),0,ss.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);ss.setSpan(new StyleSpan(Typeface.BOLD),0,ss.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);float x=clampFloat((float)o.optDouble("x",.5),0,1)*2f-1f,y=(1f-clampFloat((float)o.optDouble("y",.8),0,1))*2f-1f;StaticOverlaySettings set=new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(x,y).setRotationDegrees((float)o.optDouble("rotation",0)).setAlphaScale(clampFloat((float)o.optDouble("opacity",1),0,1)).build();TextOverlay ov=TextOverlay.createStaticTextOverlay(ss,set);OverlayEffect oe=new OverlayEffect(Collections.<TextureOverlay>singletonList(ov));long a=Math.max(0,o.optLong("startMs",0))*1000L,b=Math.max(a+1000,o.optLong("endMs",a/1000+3000))*1000L;fx.add(new TimestampWrapper(oe,a,b));}
        JSONArray imgs=p.optJSONArray("imageOverlays");if(imgs!=null)for(int i=0;i<imgs.length();i++){JSONObject o=imgs.getJSONObject(i);File f=new File(o.optString("filePath",""));if(!f.exists())continue;float x=clampFloat((float)o.optDouble("x",.5),0,1)*2f-1f,y=(1f-clampFloat((float)o.optDouble("y",.5),0,1))*2f-1f,sc=clampFloat((float)o.optDouble("scale",.28),.03f,1.5f);StaticOverlaySettings set=new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(x,y).setScale(sc,sc).setRotationDegrees((float)o.optDouble("rotation",0)).setAlphaScale(clampFloat((float)o.optDouble("opacity",1),0,1)).build();BitmapOverlay ov=BitmapOverlay.createStaticBitmapOverlay(cordova.getContext(),Uri.fromFile(f),set);OverlayEffect oe=new OverlayEffect(Collections.<TextureOverlay>singletonList(ov));long a=Math.max(0,o.optLong("startMs",0))*1000L,b=Math.max(a+1000,o.optLong("endMs",a/1000+3000))*1000L;fx.add(new TimestampWrapper(oe,a,b));}
        return fx;
    }

    private GainProcessor constantGain(final float gain){return new GainProcessor(new GainProcessor.GainProvider(){@Override public float getGainFactorAtSamplePosition(long samplePosition,int sampleRate){return gain;}@Override public long isUnityUntil(long samplePosition,int sampleRate){return gain>=.999f?C.TIME_END_OF_SOURCE:C.TIME_UNSET;}});}

    private void getProgress(CallbackContext cb){cordova.getActivity().runOnUiThread(()->{try{JSONObject o=new JSONObject();if(!exporting||transformer==null){o.put("state","idle");o.put("progress",exporting?0:100);cb.success(o);return;}ProgressHolder h=new ProgressHolder();int s=transformer.getProgress(h);o.put("stateCode",s);o.put("progress",s==Transformer.PROGRESS_STATE_AVAILABLE?h.progress:0);o.put("state",s==Transformer.PROGRESS_STATE_AVAILABLE?"available":s==Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY?"waiting":"unavailable");cb.success(o);}catch(Exception e){cb.error(error("PROGRESS_FAILED",e.getMessage()));}});}
    private void cancelExport(CallbackContext cb){cordova.getActivity().runOnUiThread(()->{try{if(transformer!=null)transformer.cancel();exporting=false;if(outputFile!=null&&outputFile.exists())outputFile.delete();cb.success();}catch(Exception e){cb.error(error("CANCEL_FAILED",e.getMessage()));}});}
    private void cleanupCache(CallbackContext cb){cordova.getThreadPool().execute(()->{try{File[] fs=cordova.getContext().getCacheDir().listFiles();int n=0;if(fs!=null)for(File f:fs)if(f.getName().startsWith("Vileo_")&&f.isFile()&&f.delete())n++;JSONObject o=new JSONObject();o.put("deleted",n);cb.success(o);}catch(Exception e){cb.error(error("CLEAN_FAILED",e.getMessage()));}});}

    private JSONObject readInfo(File f)throws Exception{if(f==null||!f.exists())throw new Exception("Файл не найден");MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(f.getAbsolutePath());JSONObject o=new JSONObject();o.put("durationMs",parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));o.put("width",parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));o.put("height",parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));o.put("rotation",parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));o.put("filePath",f.getAbsolutePath());o.put("name",f.getName());o.put("sizeBytes",f.length());return o;}finally{try{m.release();}catch(Exception ignored){}}}
    private String queryName(Uri u){try(Cursor c=cordova.getContext().getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return null;}
    private void copy(Uri u,File dst)throws Exception{try(InputStream in=cordova.getContext().getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(dst)){if(in==null)throw new Exception("Не удалось открыть файл");byte[] b=new byte[256*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}}
    private String saveToGallery(File src)throws Exception{String name=src.getName();ContentResolver cr=cordova.getContext().getContentResolver();if(Build.VERSION.SDK_INT>=29){ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,name);v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/Vileo");v.put(MediaStore.Video.Media.IS_PENDING,1);Uri u=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("MediaStore не создал файл");try(InputStream in=new FileInputStream(src);OutputStream out=cr.openOutputStream(u)){if(out==null)throw new Exception("MediaStore output недоступен");byte[] b=new byte[256*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);cr.update(u,v,null,null);return u.toString();}File dir=new File(cordova.getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES),"Vileo");if(!dir.exists())dir.mkdirs();File dst=new File(dir,name);try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}MediaScannerConnection.scanFile(cordova.getContext(),new String[]{dst.getAbsolutePath()},new String[]{"video/mp4"},null);return Uri.fromFile(dst).toString();}
    private static void merge(JSONObject a,JSONObject b)throws Exception{java.util.Iterator<String> it=b.keys();while(it.hasNext()){String k=it.next();a.put(k,b.get(k));}}
    private static String extension(String n){int i=n.lastIndexOf('.');return i>=0?n.substring(i).replaceAll("[^A-Za-z0-9.]",""):"";}
    private static JSONObject error(String c,String m){JSONObject o=new JSONObject();try{o.put("code",c);o.put("message",m==null?c:m);}catch(Exception ignored){}return o;}
    private static long parseLong(String s){try{return Long.parseLong(s==null?"0":s);}catch(Exception e){return 0;}}
    private static float clampFloat(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static int clampInt(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private static int parseColor(String s){try{return Color.parseColor(s);}catch(Exception e){return Color.WHITE;}}
}
