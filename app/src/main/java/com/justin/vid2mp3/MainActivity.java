package com.justin.vid2mp3;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.io.BufferedInputStream;
import java.io.FileInputStream;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final String FOLDER = "Music/Video to MP3";

    // colours
    private static final int BG = 0xFFF5F2FA, CARD = 0xFFFFFFFF, ACCENT = 0xFF6750A4, ACCENT_LIGHT = 0xFFEADDFF,
            TEXT = 0xFF1C1B1F, TEXT2 = 0xFF625B71, OK = 0xFF2E7D32, ERR = 0xFFB3261E;

    // UI
    private Button pickBtn, exportBtn;
    private TextView status, sourceName, extractResult, enhanceResult, enhanceHint;
    private ProgressBar progress;
    private LinearLayout extractRow, enhanceRow, enhanceBody, enhanceCard;
    private Button playOrig, shareOrig, playEnh, shareEnh;
    private Switch moduleSwitch, swNoise, swClarity, swReverb, swNormalize;
    private SeekBar sbNoise, sbReverb;
    private Button[] bitrateBtns = new Button[3];
    private static final int[] BITRATES = {128, 192, 320};
    private int bitrate = 192;

    // state
    private volatile boolean busy;
    private File pcmFile;
    private int pcmRate, pcmChannels;
    private String baseName;
    private Uri origUri, enhUri;
    private MediaPlayer player;
    private Button playingBtn;
    private SharedPreferences prefs;
    private long lastUiUpdate;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", 0);
        bitrate = prefs.getInt("bitrate", 192);
        getWindow().setStatusBarColor(ACCENT);
        setContentView(buildUi());
        loadFxPrefs();
        pcmFile = new File(getCacheDir(), "audio.pcm");
        pcmFile.delete();

        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) startExtract((Uri) p);
        }
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        if (isFinishing() && pcmFile != null && !busy) pcmFile.delete();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFxPrefs();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(root);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(4), dp(16), dp(4), dp(12));
        header.addView(text("Video to MP3", 26, TEXT, true));
        TextView sub = text("Pull the audio out of any video in your gallery", 14, TEXT2, false);
        header.addView(sub);
        root.addView(header);

        // ---- Extract card
        LinearLayout c1 = card();
        c1.addView(text("1  ·  Extract audio", 17, TEXT, true));
        pickBtn = button("Choose video", true);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickVideo(); }
        });
        c1.addView(pickBtn, lpMatch(dp(12)));
        sourceName = text("", 13, TEXT2, false);
        sourceName.setVisibility(View.GONE);
        c1.addView(sourceName, lpMatch(dp(8)));

        progress = new ProgressBar(this, null, 0x01010078 /* android.R.attr.progressBarStyleHorizontal */);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        c1.addView(progress, lpMatch(dp(10)));
        status = text("", 13, TEXT2, false);
        status.setVisibility(View.GONE);
        c1.addView(status, lpMatch(dp(4)));

        extractRow = new LinearLayout(this);
        extractRow.setOrientation(LinearLayout.VERTICAL);
        extractRow.setVisibility(View.GONE);
        extractResult = text("", 14, OK, true);
        extractRow.addView(extractResult);
        LinearLayout r1 = row();
        playOrig = button("▶  Play", false);
        shareOrig = button("Share", false);
        r1.addView(playOrig, lpWeight());
        r1.addView(spacer());
        r1.addView(shareOrig, lpWeight());
        extractRow.addView(r1, lpMatch(dp(8)));
        playOrig.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePlay(origUri, playOrig); }
        });
        shareOrig.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { share(origUri); }
        });
        c1.addView(extractRow, lpMatch(dp(12)));

        // MP3 quality
        c1.addView(text("MP3 quality", 13, TEXT2, false), lpMatch(dp(16)));
        LinearLayout br = row();
        for (int i = 0; i < 3; i++) {
            final int kbps = BITRATES[i];
            Button b = button(kbps + " kbps", false);
            b.setTextSize(13);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setBitrate(kbps); }
            });
            bitrateBtns[i] = b;
            if (i > 0) br.addView(spacer());
            br.addView(b, lpWeight());
        }
        c1.addView(br, lpMatch(dp(6)));
        setBitrate(bitrate);
        root.addView(c1, lpMatch(dp(8)));

        // ---- Enhance card
        enhanceCard = card();
        LinearLayout eh = row();
        eh.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("2  ·  Enhance module", 17, TEXT, true));
        titles.addView(text("Make a cleaned-up second version", 13, TEXT2, false));
        eh.addView(titles, lpWeight());
        moduleSwitch = new Switch(this);
        eh.addView(moduleSwitch);
        enhanceCard.addView(eh);

        enhanceBody = new LinearLayout(this);
        enhanceBody.setOrientation(LinearLayout.VERTICAL);

        swNoise = toggle("Noise reduction", "Removes steady background hiss, hum and wind");
        sbNoise = slider("Strength");
        swClarity = toggle("Voice clarity", "Cuts rumble and boominess, brightens voices");
        swReverb = toggle("Reverb", "Adds room / hall ambience");
        sbReverb = slider("Amount");
        swNormalize = toggle("Normalize volume", "Makes it as loud as possible without distortion");

        enhanceBody.addView(swNoise, lpMatch(dp(14)));
        enhanceBody.addView((View) sbNoise.getTag(), lpMatch(dp(2)));
        enhanceBody.addView(swClarity, lpMatch(dp(14)));
        enhanceBody.addView(swReverb, lpMatch(dp(14)));
        enhanceBody.addView((View) sbReverb.getTag(), lpMatch(dp(2)));
        enhanceBody.addView(swNormalize, lpMatch(dp(14)));

        exportBtn = button("Export enhanced MP3", true);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startEnhance(); }
        });
        enhanceBody.addView(exportBtn, lpMatch(dp(18)));
        enhanceHint = text("Extract a video first, then export an enhanced version.", 12, TEXT2, false);
        enhanceBody.addView(enhanceHint, lpMatch(dp(6)));

        enhanceRow = new LinearLayout(this);
        enhanceRow.setOrientation(LinearLayout.VERTICAL);
        enhanceRow.setVisibility(View.GONE);
        enhanceResult = text("", 14, OK, true);
        enhanceRow.addView(enhanceResult);
        LinearLayout r2 = row();
        playEnh = button("▶  Play", false);
        shareEnh = button("Share", false);
        r2.addView(playEnh, lpWeight());
        r2.addView(spacer());
        r2.addView(shareEnh, lpWeight());
        enhanceRow.addView(r2, lpMatch(dp(8)));
        playEnh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePlay(enhUri, playEnh); }
        });
        shareEnh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { share(enhUri); }
        });
        enhanceBody.addView(enhanceRow, lpMatch(dp(12)));
        enhanceCard.addView(enhanceBody, lpMatch(0));

        moduleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                enhanceBody.setVisibility(on ? View.VISIBLE : View.GONE);
                prefs.edit().putBoolean("module", on).apply();
            }
        });
        CompoundButton.OnCheckedChangeListener sliderVis = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { updateSliderVisibility(); }
        };
        swNoise.setOnCheckedChangeListener(sliderVis);
        swReverb.setOnCheckedChangeListener(sliderVis);
        root.addView(enhanceCard, lpMatch(dp(14)));

        TextView foot = text("Files are saved to your phone's Music › Video to MP3 folder.", 12, TEXT2, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, lpMatch(dp(18)));

        refreshButtons();
        return scroll;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTag(Boolean.valueOf(primary));
        styleButton(b, primary, false);
        b.setMinHeight(dp(48));
        return b;
    }

    private void styleButton(Button b, boolean primary, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(24));
        if (primary) {
            d.setColor(ACCENT);
            b.setTextColor(0xFFFFFFFF);
        } else if (selected) {
            d.setColor(ACCENT_LIGHT);
            d.setStroke(dp(2), ACCENT);
            b.setTextColor(ACCENT);
        } else {
            d.setColor(0xFFFFFFFF);
            d.setStroke(dp(1), 0xFF79747E);
            b.setTextColor(ACCENT);
        }
        b.setBackground(d);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(20));
        c.setBackground(d);
        c.setElevation(dp(1));
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return v;
    }

    private Switch toggle(String title, String desc) {
        Switch s = new Switch(this);
        s.setText(title + "\n" + desc);
        s.setTextSize(15);
        s.setTextColor(TEXT);
        return s;
    }

    /** Returns a SeekBar whose tag holds the row (label + bar) to add to the layout. */
    private SeekBar slider(String label) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), 0, 0, 0);
        TextView l = text(label, 13, TEXT2, false);
        r.addView(l, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        r.addView(sb, lpWeight());
        sb.setTag(r);
        return sb;
    }

    private LinearLayout.LayoutParams lpMatch(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams lpWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void setBitrate(int kbps) {
        bitrate = kbps;
        for (int i = 0; i < 3; i++) styleButton(bitrateBtns[i], false, BITRATES[i] == kbps);
        if (prefs != null) prefs.edit().putInt("bitrate", kbps).apply();
    }

    private void updateSliderVisibility() {
        ((View) sbNoise.getTag()).setVisibility(swNoise.isChecked() ? View.VISIBLE : View.GONE);
        ((View) sbReverb.getTag()).setVisibility(swReverb.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void loadFxPrefs() {
        boolean module = prefs.getBoolean("module", true);
        moduleSwitch.setChecked(module);
        enhanceBody.setVisibility(module ? View.VISIBLE : View.GONE);
        swNoise.setChecked(prefs.getBoolean("noise", true));
        swClarity.setChecked(prefs.getBoolean("clarity", false));
        swReverb.setChecked(prefs.getBoolean("reverb", false));
        swNormalize.setChecked(prefs.getBoolean("normalize", true));
        sbNoise.setProgress(prefs.getInt("noiseAmt", 60));
        sbReverb.setProgress(prefs.getInt("reverbAmt", 40));
        updateSliderVisibility();
    }

    private void saveFxPrefs() {
        if (prefs == null || swNoise == null) return;
        prefs.edit()
                .putBoolean("noise", swNoise.isChecked())
                .putBoolean("clarity", swClarity.isChecked())
                .putBoolean("reverb", swReverb.isChecked())
                .putBoolean("normalize", swNormalize.isChecked())
                .putInt("noiseAmt", sbNoise.getProgress())
                .putInt("reverbAmt", sbReverb.getProgress())
                .apply();
    }

    private void refreshButtons() {
        boolean haveAudio = pcmFile != null && pcmFile.exists() && pcmRate > 0;
        pickBtn.setEnabled(!busy);
        pickBtn.setAlpha(busy ? 0.5f : 1f);
        exportBtn.setEnabled(!busy && haveAudio);
        exportBtn.setAlpha(!busy && haveAudio ? 1f : 0.5f);
        enhanceHint.setVisibility(haveAudio ? View.GONE : View.VISIBLE);
        for (Button b : bitrateBtns) b.setEnabled(!busy);
    }

    private void showStatus(String msg, int color) {
        status.setText(msg);
        status.setTextColor(color);
        status.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ picking

    private void pickVideo() {
        if (busy) return;
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("video/*");
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent f = new Intent(Intent.ACTION_GET_CONTENT);
            f.setType("video/*");
            f.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(f, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startExtract(data.getData());
        }
    }

    // ------------------------------------------------------------------ worker plumbing

    private void setProgress(final float frac, final String label) {
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate < 120 && frac < 1f) return;
        lastUiUpdate = now;
        runOnUiThread(new Runnable() {
            public void run() {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(Math.round(Math.max(0, Math.min(1, frac)) * 1000));
                showStatus(label + "  " + Math.round(frac * 100) + "%", TEXT2);
            }
        });
    }

    private void beginWork() {
        busy = true;
        lastUiUpdate = 0;
        stopPlayback();
        getWindow().addFlags(0x80); // FLAG_KEEP_SCREEN_ON
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        refreshButtons();
    }

    private void endWork(final String msg, final boolean error) {
        runOnUiThread(new Runnable() {
            public void run() {
                busy = false;
                getWindow().clearFlags(0x80);
                progress.setVisibility(View.GONE);
                if (error) showStatus(msg, ERR); else status.setVisibility(View.GONE);
                refreshButtons();
            }
        });
    }

    // ------------------------------------------------------------------ extraction

    private void startExtract(final Uri video) {
        if (busy) return;
        beginWork();
        extractRow.setVisibility(View.GONE);
        enhanceRow.setVisibility(View.GONE);
        pcmRate = 0;
        baseName = cleanName(queryName(video));
        sourceName.setText("🎬  " + baseName);
        sourceName.setVisibility(View.VISIBLE);
        showStatus("Reading video…", TEXT2);
        final int kbps = bitrate;
        new Thread(new Runnable() {
            public void run() {
                try {
                    decodeToPcm(video);
                    final String name = baseName + ".mp3";
                    final Uri out = saveMp3(name, new Mp3Writer() {
                        public void write(OutputStream os) throws IOException { encodePcm(os, kbps); }
                    });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            origUri = out;
                            extractResult.setText("✓  Saved " + name);
                            extractRow.setVisibility(View.VISIBLE);
                        }
                    });
                    endWork(null, false);
                } catch (Throwable t) {
                    pcmRate = 0;
                    endWork(errorText(t), true);
                }
            }
        }).start();
    }

    private void decodeToPcm(Uri video) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        OutputStream os = null;
        try {
            ex.setDataSource(this, video, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new UserException("This video has no audio track.");
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int encoding = 2; // ENCODING_PCM_16BIT

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            os = new BufferedOutputStream(new FileOutputStream(pcmFile), 1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            boolean wroteAny = false;
            int outCh = Math.min(srcCh, 2);
            short[] sbuf = new short[0];
            float[] fbuf = new float[0];
            byte[] bytes = new byte[0];
            long idleLoops = 0;

            while (true) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    int newRate = of.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : rate;
                    int newCh = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : srcCh;
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (wroteAny && (newRate != rate || Math.min(newCh, 2) != outCh))
                        throw new UserException("The audio format changes mid-video, which isn't supported.");
                    rate = newRate; srcCh = newCh; outCh = Math.min(srcCh, 2);
                } else if (oi >= 0) {
                    idleLoops = 0;
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer bb = ob.slice().order(ByteOrder.nativeOrder());
                        int samples;
                        if (encoding == 4) { // float
                            FloatBuffer fb = bb.asFloatBuffer();
                            samples = fb.remaining();
                            if (fbuf.length < samples) fbuf = new float[samples];
                            fb.get(fbuf, 0, samples);
                            if (sbuf.length < samples) sbuf = new short[samples];
                            for (int i = 0; i < samples; i++) {
                                float v = fbuf[i];
                                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                                sbuf[i] = (short) (v * 32767f);
                            }
                        } else if (encoding == 3) { // 8-bit unsigned
                            samples = bb.remaining();
                            if (sbuf.length < samples) sbuf = new short[samples];
                            for (int i = 0; i < samples; i++) sbuf[i] = (short) (((bb.get(i) & 0xff) - 128) << 8);
                        } else {
                            ShortBuffer sb = bb.asShortBuffer();
                            samples = sb.remaining();
                            if (sbuf.length < samples) sbuf = new short[samples];
                            sb.get(sbuf, 0, samples);
                        }
                        int frames = samples / srcCh;
                        int outBytes = frames * outCh * 2;
                        if (bytes.length < outBytes) bytes = new byte[outBytes];
                        int b = 0;
                        for (int f = 0; f < frames; f++) {
                            int base = f * srcCh;
                            if (outCh == 1) {
                                short v = sbuf[base];
                                bytes[b++] = (byte) v; bytes[b++] = (byte) (v >> 8);
                            } else {
                                int l, r;
                                if (srcCh >= 6) { // 5.1 downmix: FL FR FC LFE BL BR
                                    float c = sbuf[base + 2] * 0.7071f;
                                    l = Math.round((sbuf[base] + c + sbuf[base + 4] * 0.7071f) / 2.4142f);
                                    r = Math.round((sbuf[base + 1] + c + sbuf[base + 5] * 0.7071f) / 2.4142f);
                                } else {
                                    l = sbuf[base]; r = sbuf[base + 1];
                                }
                                bytes[b++] = (byte) l; bytes[b++] = (byte) (l >> 8);
                                bytes[b++] = (byte) r; bytes[b++] = (byte) (r >> 8);
                            }
                        }
                        os.write(bytes, 0, b);
                        wroteAny = true;
                        if (durUs > 0) setProgress(0.5f * Math.min(1f, info.presentationTimeUs / (float) durUs), "Extracting audio…");
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (inDone && ++idleLoops > 500) {
                    break; // decoder stalled after EOS (~5 s); keep what we have
                }
            }
            os.close();
            os = null;
            if (!wroteAny || pcmFile.length() == 0) throw new UserException("Couldn't decode any audio from this video.");
            pcmRate = rate;
            pcmChannels = outCh;
        } finally {
            if (os != null) try { os.close(); } catch (IOException ignored) { }
            if (dec != null) {
                try { dec.stop(); } catch (Throwable ignored) { }
                try { dec.release(); } catch (Throwable ignored) { }
            }
            ex.release();
        }
    }

    private int effectiveBitrate(int kbps, int rate) {
        if (rate < 16000) return Math.min(kbps, 64);
        if (rate < 32000) return Math.min(kbps, 160);
        return kbps;
    }

    private void encodePcm(OutputStream os, int kbps) throws IOException {
        final int ch = pcmChannels;
        Mp3Encoder enc = new Mp3Encoder(pcmRate, ch, effectiveBitrate(kbps, pcmRate), 5);
        InputStream in = new BufferedInputStream(new FileInputStream(pcmFile), 1 << 16);
        try {
            final int block = 4096;
            byte[] raw = new byte[block * ch * 2];
            short[] pcm = new short[block * ch];
            byte[] mp3 = new byte[Mp3Encoder.outBufSize(block)];
            long total = pcmFile.length(), done = 0;
            while (true) {
                int got = AudioFx.readFully(in, raw, raw.length);
                int frames = got / (2 * ch);
                if (frames == 0) break;
                for (int i = 0; i < frames * ch; i++) pcm[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
                os.write(mp3, 0, enc.encode(pcm, frames, mp3));
                done += got;
                setProgress(0.5f + 0.5f * done / (float) total, "Converting to MP3…");
                if (got < raw.length) break;
            }
            os.write(mp3, 0, enc.flush(mp3));
        } finally {
            in.close();
            enc.close();
        }
    }

    // ------------------------------------------------------------------ enhance

    private void startEnhance() {
        if (busy || pcmRate == 0) return;
        final AudioFx.Settings s = new AudioFx.Settings();
        s.noiseReduction = swNoise.isChecked();
        s.noiseStrength = sbNoise.getProgress() / 100f;
        s.clarity = swClarity.isChecked();
        s.reverb = swReverb.isChecked();
        s.reverbAmount = sbReverb.getProgress() / 100f;
        s.normalize = swNormalize.isChecked();
        if (!s.anyEnabled()) {
            Toast.makeText(this, "Turn on at least one effect first", Toast.LENGTH_SHORT).show();
            return;
        }
        saveFxPrefs();
        beginWork();
        enhanceRow.setVisibility(View.GONE);
        showStatus("Preparing…", TEXT2);
        final int kbps = bitrate;
        final int rate = pcmRate, ch = pcmChannels;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String name = baseName + " (enhanced).mp3";
                    final Uri out = saveMp3(name, new Mp3Writer() {
                        public void write(OutputStream os) throws IOException {
                            AudioFx.process(pcmFile, rate, ch, s, getCacheDir(), os, effectiveBitrate(kbps, rate), 5,
                                    new AudioFx.Progress() {
                                        public void update(float f, String label) { setProgress(f, label); }
                                    });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            enhUri = out;
                            enhanceResult.setText("✓  Saved " + name + "\n" + s.describe());
                            enhanceRow.setVisibility(View.VISIBLE);
                        }
                    });
                    endWork(null, false);
                } catch (Throwable t) {
                    endWork(errorText(t), true);
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ saving / sharing / playback

    interface Mp3Writer { void write(OutputStream os) throws IOException; }

    private Uri saveMp3(String displayName, Mp3Writer writer) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.TITLE, displayName.substring(0, displayName.length() - 4));
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(1));
        Uri collection = MediaStore.Audio.Media.getContentUri("external_primary");
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("Couldn't create the MP3 file in your Music folder.");
        boolean ok = false;
        try {
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("Couldn't open the MP3 file for writing.");
            OutputStream bos = new BufferedOutputStream(os, 1 << 16);
            try {
                writer.write(bos);
            } finally {
                bos.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(0));
            cr.update(uri, done, null, null);
            ok = true;
            return uri;
        } finally {
            if (!ok) try { cr.delete(uri, null, null); } catch (Throwable ignored) { }
        }
    }

    private void share(Uri uri) {
        if (uri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mpeg");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share MP3"));
    }

    private void togglePlay(Uri uri, final Button btn) {
        if (uri == null) return;
        boolean wasThis = playingBtn == btn;
        stopPlayback();
        if (wasThis) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { stopPlayback(); }
            });
            player.prepare();
            player.start();
            playingBtn = btn;
            btn.setText("■  Stop");
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, "Can't play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            player.release();
            player = null;
        }
        if (playingBtn != null) {
            playingBtn.setText("▶  Play");
            playingBtn = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    static class UserException extends IOException {
        UserException(String m) { super(m); }
    }

    private String errorText(Throwable t) {
        if (t instanceof UserException) return t.getMessage();
        if (t instanceof OutOfMemoryError) return "Ran out of memory — try a shorter video.";
        String m = t.getMessage();
        return "Something went wrong: " + (m != null ? m : t.getClass().getSimpleName());
    }

    private String queryName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "audio";
        return name;
    }

    private static String cleanName(String n) {
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        if (n.isEmpty()) n = "audio";
        if (n.length() > 80) n = n.substring(0, 80);
        return n;
    }
}
